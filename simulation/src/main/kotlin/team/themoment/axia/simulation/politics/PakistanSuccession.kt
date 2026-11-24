package team.themoment.axia.simulation.politics

import team.themoment.axia.simulation.people.LeadershipState
import team.themoment.axia.simulation.people.Person
import java.time.LocalDate
import kotlin.random.Random

/** 헌법 224A조의 공동 지명과 국민의회 규칙 35조의 총리 선거. 후보 선호·출석표는 게임 추정이다. */
internal class PakistanSuccession(
    private val leadership: LeadershipState,
    private val random: Random,
) {
    private data class Nominee(
        val person: Person,
        val treasury: Double,
        val opposition: Double,
    ) {
        val commonSupport: Double get() = minOf(treasury, opposition)
    }

    private var nominees = emptyList<Nominee>()
    private var dissolution: LocalDate? = null
    private var selectionDue: LocalDate? = null
    private var meeting: LocalDate? = null
    private var voteDue: LocalDate? = null
    private var finalists = emptySet<String>()
    private var repeatRunoff = false
    private var elected: String? = null
    private var supporters = emptySet<String>()
    var termEnd: LocalDate? = null
        private set

    val next: LocalDate? get() = listOfNotNull(selectionDue, voteDue).minOrNull()
    val awaitingElectionOfPremier: Boolean get() = voteDue != null

    companion object {
        // 국민의회 첫 회의부터 5년 임기. 만료 시 60일 이내에 총선을 실시한다(헌법 제52·224조).
        fun regularPoll(previousPoll: LocalDate): LocalDate = previousPoll.plusDays(21).plusYears(5).plusDays(60)
    }

    fun reset() {
        nominees = emptyList()
        dissolution = null
        selectionDue = null
        meeting = null
        voteDue = null
        finalists = emptySet()
        repeatRunoff = false
        elected = null
        supporters = emptySet()
    }

    fun dissolve(
        chamber: Legislature,
        date: LocalDate,
        poll: LocalDate,
        early: Boolean,
    ): ConfidenceDecision {
        reset()
        termEnd = null
        dissolution = date
        selectionDue = date.plusDays(3)
        nominees =
            List(4) { index ->
                val sponsor = random.nextDouble(0.6, 1.0)
                val other = random.nextDouble()
                Nominee(leadership.nominate(null, date), if (index < 2) sponsor else other, if (index < 2) other else sponsor)
            }
        val notice = "국민의회 해산 · 총선 $poll. 퇴임 총리와 야당 대표가 과도 총리 후보를 협의한다. 임명까지 현 총리가 직무를 수행한다."
        return ConfidenceDecision(chamber, listOf(notice), election = poll, early = early)
    }

    fun afterElection(
        chamber: Legislature,
        date: LocalDate,
    ): ConfidenceDecision {
        meeting = date.plusDays(21)
        voteDue = meeting
        finalists = emptySet()
        repeatRunoff = false
        elected = null
        supporters = emptySet()
        termEnd = requireNotNull(meeting).plusYears(5)
        val unseated =
            chamber.withGroups(
                chamber.groups.map {
                    if (it.role ==
                        PartyRole.OTHER
                    ) {
                        it
                    } else {
                        it.copy(role = PartyRole.OPPOSITION)
                    }
                },
            )
        return ConfidenceDecision(unseated, listOf("국민의회 개원 예정일은 ${meeting}이다. 현 과도 총리가 후임 취임까지 직무를 수행한다."))
    }

    fun advance(
        chamber: Legislature,
        date: LocalDate,
    ): ConfidenceDecision? {
        val statements = mutableListOf<String>()
        if (selectionDue?.let { date >= it } == true) statements += chooseCaretaker(date)
        if (voteDue?.let { date >= it } != true) return if (statements.isEmpty()) null else ConfidenceDecision(chamber, statements)
        if (date == meeting) {
            voteDue = date.plusDays(3)
            statements += "새 국민의회 개원. 의원 선서와 의장단 선출 뒤 ${voteDue}에 총리를 선출한다."
            return ConfidenceDecision(chamber, statements)
        }
        elected?.let { winner ->
            val party = chamber.groups.single { it.modelName == winner }
            val cabinet =
                chamber.groups.map { member ->
                    when {
                        member.role == PartyRole.OTHER -> member
                        member.modelName == winner -> member.copy(role = PartyRole.LEAD)
                        member.modelName in supporters -> member.copy(role = PartyRole.SUPPLY)
                        else -> member.copy(role = PartyRole.OPPOSITION)
                    }
                }
            voteDue = null
            statements += "국민의회가 선출한 ${party.name} 후보가 대통령 앞에서 선서한다."
            return ConfidenceDecision(chamber.withGroups(cabinet), statements, successor = true)
        }
        val unaffiliated = chamber.groups.filter { it.role == PartyRole.OTHER && it.votingSeats > 0 }
        val parties = chamber.groups.filter { it.role != PartyRole.OTHER && it.votingSeats > 0 }
        val eligible = parties.ifEmpty { unaffiliated }
        val first = finalists.isEmpty()
        val proposal = if (first) ElectionModel.formGovernment(eligible, 169, emptyMap()) else emptyList()
        val partyOfNominee = proposal.find { it.role == PartyRole.LEAD }?.modelName
        val partners = proposal.filter { it.role in setOf(PartyRole.COALITION, PartyRole.SUPPLY) }.mapTo(mutableSetOf()) { it.modelName }
        val candidates = eligible.filter { if (first) it.modelName !in partners else it.modelName in finalists }
        if (candidates.isEmpty()) {
            voteDue = date.plusDays(1)
            return ConfidenceDecision(chamber, statements + "총리 후보 등록을 다시 받는다.")
        }
        val backing = mutableMapOf<String, String>()
        val votes = candidates.associateWith { 0 }.toMutableMap()
        for (party in parties) {
            val nearest = candidates.groupBy { ideologicalDistance(party.identity, it.identity) }.minBy { it.key }.value
            val nominee = if (party.modelName in partners) partyOfNominee else party.modelName
            val preferred = candidates.find { it.modelName == nominee } ?: nearest.random(random)
            backing[party.modelName] = preferred.modelName
            // 동수 재투표에서 기권·출석 변동이 생길 수 있다. 정당별 결과는 표결권을 가진 의석 안에서 정한다.
            val cast = if (repeatRunoff) (0 until party.votingSeats).count { random.nextDouble() >= 0.02 } else party.votingSeats
            votes[preferred] = votes.getValue(preferred) + cast
        }
        // 입당하지 않은 일반 의석도 표결권을 갖는다. 무소속 의원의 선택은 의원별로 모형화한다.
        val weighted = candidates.map { it to it.votingSeats.coerceAtLeast(1) }
        val weight = weighted.sumOf { it.second }
        repeat(unaffiliated.sumOf { it.votingSeats }) {
            if (repeatRunoff && random.nextDouble() < 0.02) return@repeat
            var ticket = random.nextInt(weight)
            val nominee =
                weighted
                    .first {
                        ticket -= it.second
                        ticket < 0
                    }.first
            votes[nominee] = votes.getValue(nominee) + 1
        }
        val threshold = if (first) 169 else votes.values.sum() / 2 + 1
        val winner = votes.entries.firstOrNull { it.value >= threshold }
        val kind = if (first) "총리 선거 1차" else "총리 결선"
        if (winner != null) {
            elected = winner.key.modelName
            supporters = backing.filterValues { it == elected }.keys
            statements += "$kind · ${winner.key.name} ${winner.value}표로 당선(가결선 ${threshold}표)."
        } else if (candidates.size > 1) {
            val highest = votes.values.max()
            val tied = votes.filterValues { it == highest }.keys
            val cutoff = if (tied.size > 1) highest else votes.values.sortedDescending()[1]
            finalists = votes.filterValues { it >= cutoff }.keys.mapTo(mutableSetOf()) { it.modelName }
            repeatRunoff = !first
            statements +=
                "$kind 과반 미달(가결선 ${threshold}표). ${votes.filterKeys { it.modelName in finalists }.keys.joinToString { it.name }} 후보로 재투표한다."
        } else {
            finalists = emptySet()
            statements += "단독 총리 후보가 재적 과반을 얻지 못해 후보 등록을 다시 받는다."
        }
        voteDue = date.plusDays(1)
        return ConfidenceDecision(chamber, statements)
    }

    private fun chooseCaretaker(date: LocalDate): String {
        val start = requireNotNull(dissolution)
        val winner: Nominee?
        val authority: String
        when {
            date <= start.plusDays(3) -> {
                authority = "퇴임 총리와 야당 대표가 합의한"
                winner = nominees.maxBy { it.commonSupport }.takeIf { it.commonSupport >= 0.72 }
                if (winner == null) {
                    selectionDue = start.plusDays(6)
                    val government = nominees.take(2).joinToString { it.person.name }
                    val opposition = nominees.drop(2).joinToString { it.person.name }
                    return "과도 총리 합의 실패. 퇴임 총리 후보($government)·야당 대표 후보($opposition)를 여야 각 4명의 의회 위원회에 회부했다."
                }
            }

            date <= start.plusDays(6) -> {
                authority = "여야 동수 위원회가 선정한"
                val tally = IntArray(nominees.size)
                repeat(8) { member ->
                    val choice =
                        nominees.indices.maxBy { index ->
                            val candidate = nominees[index]
                            (if (member < 4) candidate.treasury else candidate.opposition) + random.nextDouble(-0.35, 0.35)
                        }
                    tally[choice]++
                }
                winner = nominees.getOrNull(tally.indexOfFirst { it >= 5 })
                if (winner == null) {
                    selectionDue = start.plusDays(8)
                    return "의회 위원회가 과도 총리를 선정하지 못했다. 동일한 네 후보를 선거관리위원회에 회부했다."
                }
            }

            else -> {
                authority = "선거관리위원회가 최종 선정한"
                winner = nominees.maxBy { it.commonSupport }
            }
        }
        val appointee = leadership.seatHeadNominee(winner.person, "과도 총리", date)
        selectionDue = null
        nominees = emptyList()
        return "$authority ${appointee.name} 과도 총리 취임"
    }
}
