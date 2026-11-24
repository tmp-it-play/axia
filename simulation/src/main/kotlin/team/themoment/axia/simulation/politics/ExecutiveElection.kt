package team.themoment.axia.simulation.politics

import team.themoment.axia.simulation.people.LeadershipState
import team.themoment.axia.simulation.people.Person
import team.themoment.axia.simulation.society.Identity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

data class ExecutiveNomination(
    val nominee: Person,
    val rival: Person? = null,
    val primaryShare: Double? = null,
)

data class CandidateVote(
    val person: Person,
    val share: Double,
)

data class ExecutiveCampaign(
    val firstDate: LocalDate,
    val nominations: List<ExecutiveNomination>,
    val firstBallot: List<CandidateVote> = emptyList(),
    val runoffDate: LocalDate? = null,
)

data class ExecutiveResult(
    val title: String,
    val campaign: ExecutiveCampaign,
    val ballot: List<CandidateVote>,
    val winner: Person?,
    val round: Int,
)

/** 경선으로 확정한 인물을 본선·결선·취임까지 유지한다. 총선이 중간에 있어도 후보를 다시 뽑지 않는다. */
internal class ExecutiveElection(
    private val leadership: LeadershipState,
    private val regime: Regime,
    private val termLimit: Int?,
    private val random: Random,
) {
    var campaign: ExecutiveCampaign? = null
        private set
    private var incumbentId = 0
    private var base = emptyMap<Int, Double>()
    private var identities = emptyMap<Int, Identity?>()

    fun cancel() {
        campaign = null
    }

    fun partyNamesInOfficeOrCampaign(): Set<String> =
        buildSet {
            leadership.current().all().mapNotNullTo(this) { it.party }
            campaign?.nominations?.forEach {
                it.nominee.party?.let(::add)
                it.rival?.party?.let(::add)
            }
            campaign?.firstBallot?.mapNotNullTo(this) { it.person.party }
        }

    fun renameParty(change: PartyRename) {
        fun update(person: Person): Person =
            if (person.party == change.old) {
                leadership.changeAffiliation(person.id, change.name)
                person.copy(party = change.name)
            } else {
                person
            }

        leadership.current().all().forEach(::update)
        campaign =
            campaign?.let { slate ->
                slate.copy(
                    nominations = slate.nominations.map { it.copy(nominee = update(it.nominee), rival = it.rival?.let(::update)) },
                    firstBallot = slate.firstBallot.map { it.copy(person = update(it.person)) },
                )
            }
    }

    fun prepare(
        date: LocalDate,
        election: LocalDate?,
        rule: ExecutiveElectionRule?,
        groups: List<ParliamentaryGroup>,
        approval: Double,
    ): Boolean {
        if (election == null || rule == null || campaign != null || date < election.minusDays(42)) return false
        val incumbent = leadership.executiveIncumbent()
        incumbentId = incumbent.id
        val eligible = leadership.eligibleIncumbent(termLimit)
        val parties = groups.filter { it.role != PartyRole.OTHER }.sortedByDescending { it.seats }
        val candidates = mutableListOf<ExecutiveNomination>()
        val strengths = mutableMapOf<Int, Double>()
        val positions = mutableMapOf<Int, Identity?>()

        fun nominate(
            party: String?,
            officeBloc: Boolean,
            strength: Double,
            identity: Identity?,
        ) {
            val sitting = eligible?.takeIf { officeBloc }
            val first = sitting ?: leadership.nominate(party, date)
            val rival = if (rule.partyPrimary && party != null) leadership.nominate(party, date) else null
            val nomination =
                if (rival == null) {
                    ExecutiveNomination(first)
                } else {
                    val advantage = if (sitting != null) (approval - 35.0) / 130.0 else 0.0
                    val firstShare = (0.50 + advantage + random.nextDouble(-0.17, 0.17)).coerceIn(0.12, 0.88)
                    if (firstShare < 0.5) {
                        ExecutiveNomination(rival, first, 1.0 - firstShare)
                    } else {
                        ExecutiveNomination(first, rival, firstShare)
                    }
                }
            candidates += nomination
            val id = nomination.nominee.id
            strengths[id] = strength * (if (officeBloc) exp(regime.incumbentBias * 1.7) else 1.0)
            positions[id] = identity
        }
        if (rule.nonpartisan || parties.isEmpty()) {
            nominate(null, true, 0.46, null)
            nominate(null, false, 0.33, Identity.EGALITARIANS)
            nominate(null, false, 0.21, Identity.FREE_MARKET)
        } else {
            // 무소속 현직도 자신의 지지층으로 출마한다. 의석을 잃은 현직 정당도 후보를 낼 수 있다.
            if (parties.none { it.name == incumbent.party } && (incumbent.party != null || eligible != null)) {
                nominate(incumbent.party, true, 0.8 * sqrt(parties.first().seats.toDouble()), null)
            }
            parties.forEach { party ->
                nominate(party.name, party.name == incumbent.party, sqrt(party.seats.toDouble()), party.identity)
            }
        }
        campaign = ExecutiveCampaign(election, candidates.toList())
        base = strengths
        identities = positions
        return true
    }

    fun vote(
        date: LocalDate,
        rule: ExecutiveElectionRule,
        approval: Double,
    ): ExecutiveResult {
        val slate = requireNotNull(campaign)
        val incumbent = leadership.executiveIncumbent()
        val round = if (slate.runoffDate == null) 1 else 2
        val first = slate.firstBallot
        val contenders = if (round == 1) slate.nominations.map { it.nominee } else first.take(2).map { it.person }
        val entrants =
            contenders.associateWith { candidate ->
                // 경선 뒤 퇴임한 현직은 현직 지위를 잃었으므로 대체 후보가 같은 표 기반을 이어받는다.
                if (candidate.id == incumbentId && incumbent.id != incumbentId) leadership.nominate(candidate.party, date) else candidate
            }
        val weights =
            contenders.associate { candidate ->
                val affiliated = candidate.id == incumbentId || (candidate.party != null && candidate.party == incumbent.party)
                val weight =
                    if (round == 1) {
                        val swing = if (affiliated) (approval - 50.0) / (if (regime.autocratic) 55.0 else 31.0) else 0.0
                        base.getValue(candidate.id) * exp(swing) * (if (candidate.id == incumbent.id) 1.15 else 1.0)
                    } else {
                        val other = contenders.first { it.id != candidate.id }
                        first.first { it.person.id == candidate.id }.share +
                            first.drop(2).sumOf { voter ->
                                val here = ideologicalDistance(identities[voter.person.id], identities[candidate.id])
                                val there = ideologicalDistance(identities[voter.person.id], identities[other.id])
                                voter.share / (1.0 + exp((here - there) * 3.0))
                            }
                    }
                val entrant = entrants.getValue(candidate)
                if (entrant.id != candidate.id) identities = identities + (entrant.id to identities[candidate.id])
                entrant to weight * exp(random.nextDouble(-0.15, 0.15))
            }
        val total = weights.values.sum()
        val ballot = weights.map { CandidateVote(it.key, it.value / total) }.sortedByDescending { it.share }
        val runoff = round == 1 && rule.runoffDays > 0 && ballot.size >= 2 && ballot.first().share <= 0.5
        if (runoff) {
            val next =
                if (rule.lastSundayRunoff) {
                    date.with(
                        TemporalAdjusters.lastInMonth(DayOfWeek.SUNDAY),
                    )
                } else {
                    date.plusDays(rule.runoffDays.toLong())
                }
            val updated = slate.copy(firstBallot = ballot, runoffDate = next)
            campaign = updated
            return ExecutiveResult(rule.title, updated, ballot, null, round)
        }
        val winner = ballot.first().person
        leadership.seatPresident(winner, date)
        campaign = null
        return ExecutiveResult(rule.title, slate, ballot, leadership.executiveIncumbent(), round)
    }
}
