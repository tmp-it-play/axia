package team.themoment.axia.simulation.politics

import team.themoment.axia.simulation.nation.PopulationGroup
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

data class PartyResult(
    val name: String,
    val role: PartyRole,
    val voteShare: Double,
    val seatsBefore: Int,
    val seatsAfter: Int,
)

data class ExecutiveResult(
    val title: String,
    val incumbent: String,
    val challenger: String?,
    /** 양자 대결에서 집권당 후보의 득표율. */
    val incumbentShare: Double,
) {
    val winner: String get() = if (challenger == null || incumbentShare >= 0.5) incumbent else challenger
}

data class ElectionResult(
    val date: LocalDate,
    val early: Boolean,
    /** 의회 선거가 있었으면 정당별 결과. 선거 전 의석 순으로 정렬한다. */
    val parties: List<PartyResult>?,
    val executive: ExecutiveResult?,
    val governmentBefore: String,
    val governmentAfter: String,
    /** 의회 해산으로 폐기돼 초안으로 돌아간 재정안이 있었는지. */
    val billLapsed: Boolean,
) {
    val governmentChanged: Boolean get() = governmentBefore != governmentAfter
}

/** 지금 선거를 치르면 나올 예상(무작위 변동 없음). */
data class SeatProjection(
    val name: String,
    val current: Int,
    val projected: Int,
    val voteShare: Double,
)

/**
 * 득표와 의석 계산. 무작위 변동은 호출자가 넘겨 결정론을 유지한다.
 *
 * 정당의 기준 득표율은 현재 의석 비중을 선거제도의 의석 편향만큼 되돌린 값이다. 그래서 조건이 그대로면 의석도 그대로 나온다.
 * 정부 정당은 지지율이 50%에서 벗어난 만큼, 야당은 지지 집단의 불만만큼 득표가 움직인다. 권위주의 체제는 집권 세력에 가점을 준다.
 */
internal object ElectionModel {
    private const val GOVERNMENT_SWING = 0.6
    private const val OPPOSITION_SWING = 0.3
    private const val CONSTITUENCY_WEIGHT = 0.4
    private const val EXECUTIVE_SWING = 0.35
    private const val AUTOCRATIC_DAMPING = 0.5

    fun voteShares(
        groups: List<ParliamentaryGroup>,
        system: ElectoralSystem,
        regime: Regime,
        approval: Double,
        satisfaction: Map<PopulationGroup, Double>,
        noise: (ParliamentaryGroup) -> Double,
    ): Map<String, Double> {
        val totalSeats = groups.sumOf { it.seats }.toDouble()
        val implied = groups.associate { it.name to (it.seats / totalSeats).pow(1.0 / system.seatExponent) }
        // 권위주의 체제의 집권 우위는 이미 현재 의석에 담겨 있다. 가점을 더하면 선거마다 누적되므로,
        // 대신 지지율, 불만, 무작위 변동이 덜 반영되게만 한다.
        val damping = if (regime.autocratic) AUTOCRATIC_DAMPING else 1.0
        val swing = ((approval - 50.0) / 50.0).coerceIn(-1.0, 1.0) * damping
        val weights =
            groups.associate { party ->
                val shift =
                    when (party.role) {
                        PartyRole.LEAD, PartyRole.COALITION -> {
                            GOVERNMENT_SWING * swing
                        }

                        PartyRole.SUPPLY -> {
                            0.5 * GOVERNMENT_SWING * swing
                        }

                        PartyRole.OPPOSITION -> {
                            val discontent = party.constituency?.let { (50.0 - satisfaction.getValue(it)) / 50.0 } ?: 0.0
                            -OPPOSITION_SWING * swing + CONSTITUENCY_WEIGHT * discontent * damping
                        }

                        PartyRole.OTHER -> {
                            0.0
                        }
                    }
                party.name to implied.getValue(party.name) * exp(shift + noise(party) * damping)
            }
        val sum = weights.values.sum()
        return weights.mapValues { it.value / sum }
    }

    /** 최대잉여법. 의석 비중 ∝ 득표율^지수. */
    fun allocateSeats(
        shares: Map<String, Double>,
        system: ElectoralSystem,
        seats: Int,
    ): Map<String, Int> {
        val weights = shares.mapValues { it.value.pow(system.seatExponent) }
        val sum = weights.values.sum()
        val quotas = weights.mapValues { it.value / sum * seats }
        val allocated = quotas.mapValues { floor(it.value).toInt() }.toMutableMap()
        var remaining = seats - allocated.values.sum()
        quotas.entries.sortedByDescending { it.value - floor(it.value) }.forEach { (name, _) ->
            if (remaining > 0) {
                allocated[name] = allocated.getValue(name) + 1
                remaining -= 1
            }
        }
        return allocated
    }

    /** 새 의석으로 정당 목록을 다시 만든다. 의석을 모두 잃은 정당은 빠지고, 표결 불참 의석은 같은 비율로 유지한다. */
    fun reseat(
        groups: List<ParliamentaryGroup>,
        seats: Map<String, Int>,
    ): List<ParliamentaryGroup> =
        groups.mapNotNull { party ->
            val newSeats = seats.getValue(party.name)
            if (newSeats == 0) {
                null
            } else {
                val nonVoting = (newSeats * party.nonVoting.toDouble() / party.seats).roundToInt().coerceIn(0, newSeats)
                party.copy(seats = newSeats, nonVoting = nonVoting)
            }
        }

    /**
     * 의회에서 정부를 만든다. 무소속은 그대로 둔다.
     * - 기존 정부 블록(주도·연정·협력)이 과반을 지키면 정부를 유지하고, 블록 안의 제1당이 주도한다.
     * - 아니면 [president]가 있으면 그 정당이, 없으면 제1당이 과반이 될 때까지 영입한다.
     *   영입 주체가 정부에 있었다면 기존 연정 상대부터, 야당이었다면 다른 야당부터 의석 순으로 영입한다.
     * 처음부터 정부 주도 정당이 없는 의회(예: 이란, 이집트)는 정부를 재구성하지 않는다.
     */
    fun formGovernment(
        groups: List<ParliamentaryGroup>,
        required: Int,
        previousRoles: Map<String, PartyRole>,
        president: String? = null,
    ): List<ParliamentaryGroup> {
        if (PartyRole.LEAD !in previousRoles.values) return groups
        val inGovernment = { name: String ->
            previousRoles[name].let { it == PartyRole.LEAD || it == PartyRole.COALITION || it == PartyRole.SUPPLY }
        }
        if (president == null) {
            val bloc = groups.filter { it.role != PartyRole.OTHER && inGovernment(it.name) }
            if (bloc.sumOf { it.votingSeats } >= required) {
                val leader = bloc.maxBy { it.votingSeats }.name
                return groups.map { party ->
                    val role =
                        when {
                            party.name == leader -> PartyRole.LEAD
                            party.role == PartyRole.OTHER -> PartyRole.OTHER
                            previousRoles[party.name] == PartyRole.LEAD -> PartyRole.COALITION
                            else -> previousRoles.getValue(party.name)
                        }
                    party.copy(role = role)
                }
            }
        }
        val candidates = groups.filter { it.role != PartyRole.OTHER }.sortedByDescending { it.votingSeats }
        val formateur = candidates.firstOrNull { it.name == president } ?: candidates.firstOrNull() ?: return groups
        val formateurWasInGovernment = inGovernment(formateur.name)
        val order =
            (candidates - formateur).sortedWith(
                compareBy({ inGovernment(it.name) != formateurWasInGovernment }, { -it.votingSeats }),
            )
        val coalition = mutableSetOf(formateur.name)
        var seats = formateur.votingSeats
        for (party in order) {
            if (seats >= required) break
            coalition += party.name
            seats += party.votingSeats
        }
        return groups.map { party ->
            val role =
                when {
                    party.role == PartyRole.OTHER -> PartyRole.OTHER
                    party.name == formateur.name -> PartyRole.LEAD
                    party.name in coalition -> PartyRole.COALITION
                    else -> PartyRole.OPPOSITION
                }
            party.copy(role = role)
        }
    }

    /** 집권당 후보와 제1야당 후보의 양자 대결. */
    fun executiveRace(
        groups: List<ParliamentaryGroup>,
        title: String,
        regime: Regime,
        approval: Double,
        noise: Double,
    ): ExecutiveResult? {
        val incumbent = groups.firstOrNull { it.role == PartyRole.LEAD } ?: return null
        val challenger = groups.filter { it.role == PartyRole.OPPOSITION }.maxByOrNull { it.seats }
        val swing = ((approval - 50.0) / 50.0).coerceIn(-1.0, 1.0) * if (regime.autocratic) 0.5 else 1.0
        val share = (0.5 + EXECUTIVE_SWING * swing + regime.incumbentBias * 0.3 + noise).coerceIn(0.05, 0.95)
        return ExecutiveResult(title, incumbent.name, challenger?.name, share)
    }

    /** 대통령 정당이 바뀌면 새 대통령 정당이 정부를 주도하고 나머지 정당은 야당이 된다. */
    fun installPresident(
        groups: List<ParliamentaryGroup>,
        winner: String,
    ): List<ParliamentaryGroup> {
        if (groups.firstOrNull { it.role == PartyRole.LEAD }?.name == winner) return groups
        return groups.map { party ->
            val role =
                when {
                    party.name == winner -> PartyRole.LEAD
                    party.role == PartyRole.OTHER -> PartyRole.OTHER
                    else -> PartyRole.OPPOSITION
                }
            party.copy(role = role)
        }
    }
}
