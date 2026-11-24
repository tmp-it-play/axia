package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import team.themoment.axia.simulation.nation.PopulationGroup
import team.themoment.axia.simulation.society.Identity
import kotlin.math.roundToInt

enum class PartyRole(
    val label: String,
    internal val backing: Double,
) {
    LEAD("정부 주도", 28.0),
    COALITION("연정", 20.0),
    SUPPLY("정부 협력", 10.0),
    OPPOSITION("야당", -22.0),
    OTHER("기타", 0.0),
}

/** 한 나라에서 창당 순서로 부여한 번호와 창당 당시 이름·성향. 개명 뒤에도 선거구의 지지 기반은 같다. */
@Serializable
data class PartyFoundation(
    val id: Int,
    val originalName: String,
    val identity: Identity,
)

@Serializable
data class ParliamentaryGroup(
    val name: String,
    val seats: Int,
    val role: PartyRole,
    val constituency: PopulationGroup? = null,
    val nonVoting: Int = 0,
    /** 일반 의석 외에 정당에 배정된 유보석. 표결에는 참여하되 다음 지역구 선거의 득표 기반에서는 뺀다. */
    val reservedSeats: Int = 0,
    val foundation: PartyFoundation? = null,
    /** 정당·의원단 의석 중 지역구 의석. 최초 의회에서 미상일 때만 null이다. */
    val districtMandates: Int? = null,
) {
    init {
        require(name.isNotBlank() && seats >= 0 && nonVoting in 0..seats && reservedSeats in 0..seats)
        require(districtMandates == null || districtMandates in 0..(seats - reservedSeats))
    }

    val identity: Identity? get() = foundation?.identity ?: partyIdentity(name, constituency)

    internal val modelName: String get() = foundation?.originalName ?: name

    internal fun supportMood(
        groups: Map<PopulationGroup, Double>,
        identities: Map<Identity, Double>,
        fallback: Double,
    ): Double {
        val moods = listOfNotNull(groups[constituency], identities[identity])
        return if (moods.isEmpty()) fallback else moods.average()
    }

    val directSeats: Int get() = seats - reservedSeats
    val votingSeats: Int get() = seats - nonVoting
    val canNegotiate: Boolean get() = votingSeats > 0 && (role == PartyRole.OPPOSITION || role == PartyRole.SUPPLY)
}

@Serializable
data class Legislature(
    val name: String,
    val groups: List<ParliamentaryGroup>,
    val filledSeats: Int,
    /** 정원 기준 등 별도 규칙이 있으면 필요한 찬성표를 직접 지정한다. */
    private val absoluteMajority: Int? = null,
    val vacancies: List<SeatVacancy> = emptyList(),
    val unassignedVacancies: Int = 0,
) {
    @Transient
    val votingSeats: Int = groups.sumOf { it.votingSeats }

    @Transient
    val required: Int = absoluteMajority ?: (votingSeats / 2 + 1)

    init {
        require(name.isNotBlank() && filledSeats > 0 && groups.sumOf { it.seats } == filledSeats)
        require(groups.distinctBy { it.name }.size == groups.size && required > 0 && unassignedVacancies >= 0)
    }

    /** 선거나 정부 재구성 후의 의회. 이름과 특별 가결선 규칙은 그대로 둔다. */
    internal fun withGroups(newGroups: List<ParliamentaryGroup>): Legislature =
        copy(groups = newGroups, filledSeats = newGroups.sumOf { it.seats })

    internal fun withNewTerm(newGroups: List<ParliamentaryGroup>): Legislature =
        copy(groups = newGroups, filledSeats = newGroups.sumOf { it.seats }, vacancies = emptyList(), unassignedVacancies = 0)

    internal fun forecast(
        policyScore: Double,
        constituencyMean: Double,
        groupScores: Map<PopulationGroup, Double>,
        negotiatingWith: String?,
        sponsor: String? = null,
        identityScores: Map<Identity, Double> = emptyMap(),
        districtOpinion: Map<String, List<Double>> = emptyMap(),
    ): RollCall {
        val rows =
            groups.map { party ->
                val interest = party.supportMood(groupScores, identityScores, constituencyMean) - constituencyMean
                // 협의는 표결을 2주 늦춘다. 정책에 호의적인 지지 기반일수록 협의로 더 많은 표를 얻는다.
                val concession = if (party.name == negotiatingWith) (10.0 + interest * 0.5).coerceIn(0.0, 20.0) else 0.0
                val backing =
                    when {
                        sponsor == null -> party.role.backing
                        party.name == sponsor -> PartyRole.LEAD.backing
                        party.role == PartyRole.OPPOSITION -> PartyRole.SUPPLY.backing
                        party.role == PartyRole.OTHER -> 0.0
                        else -> -party.role.backing
                    }
                val support = policyScore + backing + interest * 0.5

                val districts = districtOpinion[party.name].orEmpty().take(party.votingSeats)

                fun seatsWith(points: Double): Int {
                    // 의석별 고정 분위수로 같은 안의 전망과 표결에서 같은 의원이 이탈한다.
                    val localYes =
                        districts.withIndex().count { (index, local) ->
                            val cutoff = (index + 0.5) * 100.0 / districts.size
                            val alignment = if (sponsor == null) 1.0 else -1.0
                            points + local.coerceIn(-25.0, 25.0) * 0.6 * alignment >= cutoff
                        }
                    val atLarge = party.votingSeats - districts.size
                    return localYes + (atLarge * points.coerceIn(0.0, 100.0) / 100).roundToInt()
                }
                val yes = seatsWith(support + concession)
                PartyVote(party, yes, yes - seatsWith(support))
            }
        return RollCall(rows, required, negotiatingWith)
    }
}

data class PartyVote(
    val party: ParliamentaryGroup,
    val yes: Int,
    val gainedByNegotiation: Int,
) {
    val no: Int get() = party.votingSeats - yes
}

data class RollCall(
    val rows: List<PartyVote>,
    val required: Int,
    val negotiatingWith: String?,
) {
    val yes: Int = rows.sumOf { it.yes }
    val no: Int = rows.sumOf { it.no }
    val passes: Boolean get() = yes >= required

    /** 가결선까지 표결권 있는 의석의 10% 이내가 부족하면 첫 심의에서 재심의할 수 있다. */
    val close: Boolean get() = (required - yes) in 1..((yes + no) / 10)
}
