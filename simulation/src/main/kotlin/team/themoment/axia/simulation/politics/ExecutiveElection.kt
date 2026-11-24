package team.themoment.axia.simulation.politics

import team.themoment.axia.simulation.nation.ElectoralRegion
import team.themoment.axia.simulation.people.LeaderRecord
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
    val runningMate: Person? = null,
    val endorsers: List<String> = emptyList(),
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
    val primaryStandings: List<PrimaryStanding> = emptyList(),
    val houseDate: LocalDate? = null,
    val regionalCount: RegionalCount? = null,
    val houseRound: Int = 0,
)

data class ExecutiveResult(
    val title: String,
    val campaign: ExecutiveCampaign,
    val ballot: List<CandidateVote>,
    val winner: Person?,
    val round: Int,
    val regionalCount: RegionalCount? = null,
)

data class PresidentElect(
    val person: Person,
    val inauguration: LocalDate,
    val governingParty: String?,
    val deputy: Person? = null,
    val separateDeputy: Boolean = false,
)

/** 경선으로 확정한 인물을 본선·결선·취임까지 유지한다. 총선이 중간에 있어도 후보를 다시 뽑지 않는다. */
internal class ExecutiveElection(
    private val country: String,
    private val leadership: LeadershipState,
    private val regime: Regime,
    private val termLimit: Int?,
    private val random: Random,
) {
    var campaign: ExecutiveCampaign? = null
        private set
    var presidentElect: PresidentElect? = null
        private set
    private var endorsedIncumbent: Pair<Int, String?>? = null
    val governingParty: String? get() =
        leadership.executiveIncumbent().let { president ->
            president.party ?: endorsedIncumbent?.takeIf { it.first == president.id }?.second
        }
    private val vice = if (country == "USA" || country == "IDN") VicePresidency(country, leadership, random) else null

    private var bothElectsVacant: LocalDate? = null
    private var indonesianCaretakerUntil: LocalDate? = null
    private var senateWinnerSucceeds = false
    private var lastFinalists = listOf<Pair<String?, String?>>("투쟁민주당" to null, "그린드라" to null)
    private var incomingFinalists = emptyList<Pair<String?, String?>>()

    fun advanceDeputy(
        date: LocalDate,
        chamber: Legislature?,
        upper: UpperHouse?,
        approval: Double,
    ): String? {
        val beforeTerm = bothElectsVacant
        if (beforeTerm != null && date >= beforeTerm) {
            bothElectsVacant = null
            leadership.seatVicePresident(null, date)
            if (country == "USA") {
                // 하원 선출과 별도로 상원이 선출한 부통령이 있으면 그 사람이 우선한다.
                val senateWinner = vice?.senateChoice()
                if (senateWinner != null) {
                    leadership.seatPresident(senateWinner, date)
                    vice?.cancel()
                    return "대통령 당선인의 유고로 상원이 선출한 ${senateWinner.name} 부통령 당선인이 대통령에 취임했다."
                }
                senateWinnerSucceeds = vice?.awaitingSenate() == true
                val speaker = leadership.nominate(chamber?.groups?.maxByOrNull { it.seats }?.name, date)
                leadership.actingPresident(speaker, date)
                return "양 당선인의 유고로 하원의장이 사임한 뒤 새 임기의 대통령 권한을 대행한다."
            }
            if (incomingFinalists.isNotEmpty()) lastFinalists = incomingFinalists
            indonesianCaretakerUntil = null
            electIndonesianPair(date)
            return "대통령·부통령 당선인이 모두 유고가 되어 국민협의회가 득표 상위 두 진영의 후보조에서 새 정·부통령을 선출했다."
        }
        if (leadership.doubleVacancy) return fillDoubleVacancy(date, chamber)
        if (indonesianCaretakerUntil?.let { date >= it } == true) {
            indonesianCaretakerUntil = null
            electIndonesianPair(date)
            return "국민협의회가 직전 대선 상위 두 진영이 지명한 두 후보조에서 잔여 임기의 정·부통령을 선출했다."
        }
        val notice = vice?.advance(date, chamber, upper, approval, campaign?.houseDate != null)
        val deputy = leadership.current().vicePresident
        if (senateWinnerSucceeds && deputy != null) {
            senateWinnerSucceeds = false
            leadership.seatPresident(deputy, date)
            return "상원이 선출한 ${deputy.name} 부통령이 유고인 대통령 당선인을 승계했다."
        }
        return notice
    }

    private fun fillDoubleVacancy(
        date: LocalDate,
        chamber: Legislature?,
    ): String {
        if (country == "USA") {
            leadership.actingPresident(leadership.nominate(chamber?.groups?.maxByOrNull { it.seats }?.name, date), date)
            return "대통령과 부통령의 동시 궐위로 하원의장이 사임한 뒤 대통령 권한을 대행한다."
        }
        leadership.installIndonesianCouncil(date)
        indonesianCaretakerUntil = date.plusDays(21)
        return "외교·내무·국방장관이 공동으로 대통령 직무를 대행한다. 국민협의회는 ${indonesianCaretakerUntil}에 후임 정·부통령을 선출한다."
    }

    private fun electIndonesianPair(date: LocalDate) {
        // 2019년 조코위-마루프와 프라보워-산디아가를 지명한 진영. 후속 선거부터 모의 개표 결과를 사용한다.
        val selected = lastFinalists.random(random)
        val president = leadership.nominate(selected.first, date)
        leadership.seatPresident(president, date)
        leadership.seatVicePresident(leadership.nominate(selected.second, date), date)
        endorsedIncumbent = president.id to selected.first
    }

    fun checkMortality(
        date: LocalDate,
        risks: Random,
        chamber: Legislature?,
    ): String? {
        val elect = presidentElect
        val pending = if (vice == null) emptyList() else listOfNotNull(elect?.person, elect?.deputy) + vice.officersAwaitingOffice()
        val notice = leadership.checkMortality(date, risks, pending) ?: return null
        val notices = mutableListOf(notice)
        if (leadership.doubleVacancy) notices += fillDoubleVacancy(date, chamber)
        if (elect != null && vice != null) {
            val deputy = elect.deputy?.takeIf(leadership::available)
            val beforeElectors =
                country == "USA" && date <
                    LocalDate
                        .of(elect.inauguration.year - 1, 12, 1)
                        .with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.WEDNESDAY))
                        .with(TemporalAdjusters.next(DayOfWeek.TUESDAY))
            if (beforeElectors && (!leadership.available(elect.person) || elect.deputy != deputy)) {
                val nominee = elect.person.takeIf(leadership::available) ?: deputy ?: leadership.nominate(elect.governingParty, date)
                val mate = deputy?.takeUnless { it.id == nominee.id } ?: leadership.nominate(elect.governingParty, date)
                presidentElect = elect.copy(person = nominee, deputy = mate)
                notices += "선거인단 투표 전에 정당이 새 정·부통령 후보조를 구성했다."
            } else if (!leadership.available(elect.person)) {
                if (deputy != null) {
                    presidentElect = elect.copy(person = deputy, governingParty = deputy.party ?: elect.governingParty, deputy = null)
                    notices += "${deputy.name} 부통령 당선인이 새 임기 시작일에 대통령에 취임한다."
                } else {
                    presidentElect = null
                    bothElectsVacant = elect.inauguration
                    notices += "취임일에 적용할 승계 절차를 개시했다."
                }
            } else if (deputy != elect.deputy) {
                presidentElect = elect.copy(deputy = null)
                notices += "차기 부통령직은 결원 보충 절차로 채운다."
            }
        }
        return notices.joinToString(" ")
    }

    fun renamePerson(person: Person) {
        fun rename(old: Person): Person = if (old.id == person.id) old.copy(name = person.name) else old

        fun ballot(rows: List<CandidateVote>) = rows.map { it.copy(person = rename(it.person)) }
        presidentElect = presidentElect?.let { it.copy(person = rename(it.person), deputy = it.deputy?.let(::rename)) }
        vice?.renamePerson(person)
        campaign =
            campaign?.let { c ->
                c.copy(
                    nominations =
                        c.nominations.map {
                            it.copy(
                                nominee = rename(it.nominee),
                                rival = it.rival?.let(::rename),
                                runningMate = it.runningMate?.let(::rename),
                            )
                        },
                    firstBallot = ballot(c.firstBallot),
                    regionalCount = c.regionalCount?.let { it.copy(popular = ballot(it.popular)) },
                )
            }
    }

    private var primaries: UsPrimaries? = null
    private var incumbentId = 0
    private var base = emptyMap<Int, Double>()
    private var identities = emptyMap<Int, Identity?>()

    fun cancel() {
        campaign = null
        primaries = null
        presidentElect = null
        vice?.cancel()
        bothElectsVacant = null
        indonesianCaretakerUntil = null
        senateWinnerSucceeds = false
    }

    fun partyNamesInOfficeOrCampaign(): Set<String> =
        buildSet {
            presidentElect?.governingParty?.let(::add)
            leadership.current().all().mapNotNullTo(this) { it.party }
            campaign?.nominations?.forEach {
                it.nominee.party?.let(::add)
                it.rival?.party?.let(::add)
                it.runningMate?.party?.let(::add)
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
        vice?.renameParty(change, ::update)
        presidentElect =
            presidentElect?.let {
                it.copy(
                    person = update(it.person),
                    governingParty = it.governingParty?.let(change::label),
                    deputy = it.deputy?.let(::update),
                )
            }
        endorsedIncumbent = endorsedIncumbent?.let { it.first to it.second?.let(change::label) }
        campaign =
            campaign?.let { slate ->
                slate.copy(
                    nominations =
                        slate.nominations.map {
                            it.copy(
                                nominee = update(it.nominee),
                                rival = it.rival?.let(::update),
                                runningMate = it.runningMate?.let(::update),
                            )
                        },
                    firstBallot = slate.firstBallot.map { it.copy(person = update(it.person)) },
                    regionalCount =
                        slate.regionalCount?.let { count ->
                            count.copy(popular = count.popular.map { it.copy(person = update(it.person)) })
                        },
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
        if (election == null || rule == null) return false
        val underway = campaign
        if (underway != null) {
            val updated = primaries?.advance(underway, date, incumbentId, approval, random) ?: return false
            campaign = updated
            return updated.primaryStandings.isNotEmpty() && updated.primaryStandings.all { it.nextDate == null } &&
                underway.primaryStandings.any { it.nextDate != null }
        }
        val statePrimaries = country == "USA"
        val start = if (statePrimaries) LocalDate.of(election.year, 1, 1) else election.minusDays(42)
        if (date < start) return false
        primaries = if (statePrimaries) UsPrimaries(election.year) else null
        val incumbent = leadership.executiveIncumbent()
        incumbentId = incumbent.id
        val eligible = leadership.eligibleIncumbent(termLimit)
        val parties = groups.filter { it.role != PartyRole.OTHER && it.seats > 0 }.sortedByDescending { it.seats }
        val candidates = mutableListOf<ExecutiveNomination>()
        val strengths = mutableMapOf<Int, Double>()
        val positions = mutableMapOf<Int, Identity?>()

        fun nominate(
            party: String?,
            officeBloc: Boolean,
            strength: Double,
            identity: Identity?,
            ticket: IndonesianTicket? = null,
        ) {
            val sitting = eligible?.takeIf { officeBloc }
            val first =
                if (ticket ==
                    null
                ) {
                    sitting ?: leadership.nominate(party, date)
                } else {
                    leadership.nominate(LeaderRecord(ticket.name, "후보", party, 0, ticket.born), date)
                }
            val rival =
                if (rule.partyPrimary && party != null &&
                    (!statePrimaries || party in setOf("민주당", "공화당"))
                ) {
                    leadership.nominate(party, date)
                } else {
                    null
                }
            val nomination =
                if (rival == null || statePrimaries) {
                    ExecutiveNomination(
                        first,
                        rival,
                        runningMate =
                            when {
                                ticket != null -> {
                                    leadership.nominate(
                                        LeaderRecord(ticket.runningMate, "부통령 후보", ticket.mateParty, 0, ticket.mateBorn),
                                        date,
                                    )
                                }

                                vice != null -> {
                                    leadership.current().vicePresident?.takeIf { sitting != null && it.party == party }
                                        ?: leadership.nominate(party, date)
                                }

                                else -> {
                                    null
                                }
                            },
                        endorsers = ticket?.endorsers.orEmpty(),
                    )
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
            if (rival != null && statePrimaries) {
                strengths[rival.id] = strengths.getValue(id)
                positions[rival.id] = identity
            }
        }
        if (country == "IDN" && election == LocalDate.of(2024, 2, 14)) {
            for (ticket in ElectoralAreas.default.indonesianTickets) {
                val sponsors = parties.filter { it.name in ticket.endorsers }
                nominate(
                    ticket.party,
                    incumbent.party in ticket.endorsers,
                    sqrt(
                        sponsors
                            .sumOf {
                                it.seats
                            }.coerceAtLeast(1)
                            .toDouble(),
                    ),
                    sponsors.firstOrNull()?.identity,
                    ticket,
                )
            }
        } else if (rule.nonpartisan || parties.isEmpty()) {
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
        val slate = ExecutiveCampaign(election, candidates.toList())
        campaign = primaries?.advance(slate, date, incumbentId, approval, random) ?: slate
        base = strengths
        identities = positions
        return !statePrimaries
    }

    fun vote(
        date: LocalDate,
        rule: ExecutiveElectionRule,
        approval: Double,
        regions: List<ElectoralRegion> = emptyList(),
        house: List<DistrictResult> = emptyList(),
    ): ExecutiveResult {
        var slate = requireNotNull(campaign)
        if (vice != null && slate.houseDate == null) {
            slate =
                slate.copy(
                    nominations =
                        slate.nominations.map { ticket ->
                            val mate = ticket.runningMate
                            if (mate != null &&
                                !leadership.available(mate)
                            ) {
                                ticket.copy(runningMate = leadership.nominate(mate.party, date))
                            } else {
                                ticket
                            }
                        },
                )
            campaign = slate
        }
        if (slate.houseDate != null) {
            val previous = requireNotNull(slate.regionalCount)
            val finalists =
                previous.popular
                    .sortedByDescending {
                        previous.electors.getValue(it.person.id)
                    }.take(3)
                    .filter { leadership.available(it.person) }
            val delegates = if (finalists.isEmpty()) emptyMap() else RegionalElections.houseBallot(finalists, house, slate.houseRound + 1)
            val count = previous.copy(stateDelegations = delegates)
            val selected = delegates.entries.firstOrNull { it.value >= 26 }?.key
            if (selected !=
                null
            ) {
                return install(date, rule, slate, previous.popular, finalists.first { it.person.id == selected }.person, 1, count)
            }
            val updated = slate.copy(houseDate = date.plusDays(1), regionalCount = count, houseRound = slate.houseRound + 1)
            campaign = updated
            return ExecutiveResult(rule.title, updated, previous.popular, null, 1, count)
        }
        val incumbent = leadership.executiveIncumbent()
        val round = if (slate.runoffDate == null) 1 else 2
        val first = slate.firstBallot
        val contenders = if (round == 1) slate.nominations.map { it.nominee } else first.take(2).map { it.person }
        val entrants =
            contenders.associateWith { candidate ->
                // 경선 뒤 퇴임한 현직은 현직 지위를 잃었으므로 대체 후보가 같은 표 기반을 이어받는다.
                if (!leadership.available(candidate)) leadership.nominate(candidate.party, date) else candidate
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
        if (entrants.any { (original, replacement) -> original.id != replacement.id }) {
            slate =
                slate.copy(
                    nominations =
                        slate.nominations.map { ticket ->
                            entrants[ticket.nominee]?.let { ticket.copy(nominee = it) } ?: ticket
                        },
                )
        }
        val total = weights.values.sum()
        val national = weights.map { CandidateVote(it.key, it.value / total) }.sortedByDescending { it.share }
        val count =
            if (country == "USA" ||
                country == "IDN"
            ) {
                RegionalElections.count(country, national, regions, approval, incumbent.party)
            } else {
                null
            }
        val ballot = count?.popular ?: national
        if (country == "USA") {
            val votes = requireNotNull(count).electors
            val elected = ballot.firstOrNull { votes.getValue(it.person.id) >= 270 }
            if (elected != null) return install(date, rule, slate, ballot, elected.person, round, count)
            val ranked = ballot.sortedByDescending { votes.getValue(it.person.id) }
            requireNotNull(vice).beginSenateBallot(
                slate.firstDate,
                ranked.take(2).map { v ->
                    requireNotNull(
                        slate.nominations
                            .find {
                                it.nominee.party ==
                                    v.person.party
                            }?.runningMate,
                    )
                },
            )
            val updated = slate.copy(firstBallot = ballot, regionalCount = count, houseDate = LocalDate.of(date.year + 1, 1, 6))
            campaign = updated
            return ExecutiveResult(rule.title, updated, ballot, null, round, count)
        }
        val regionalTest = country == "IDN" && ballot.size > 2 && requireNotNull(count?.qualifyingProvinces) < 20
        val runoff =
            round == 1 && rule.runoffDays > 0 && ballot.size >= 2 && (ballot.first().share <= 0.5 || regionalTest) &&
                (country != "IDN" || ballot.size > 2)
        if (runoff) {
            val next =
                if (rule.lastSundayRunoff) {
                    date.with(
                        TemporalAdjusters.lastInMonth(DayOfWeek.SUNDAY),
                    )
                } else {
                    date.plusDays(rule.runoffDays.toLong())
                }
            val updated = slate.copy(firstBallot = ballot, runoffDate = next, regionalCount = count)
            campaign = updated
            return ExecutiveResult(rule.title, updated, ballot, null, round, count)
        }
        return install(date, rule, slate, ballot, ballot.first().person, round, count)
    }

    private fun install(
        date: LocalDate,
        rule: ExecutiveElectionRule,
        slate: ExecutiveCampaign,
        ballot: List<CandidateVote>,
        winner: Person,
        round: Int,
        count: RegionalCount?,
    ): ExecutiveResult {
        val inauguration =
            when (country) {
                "USA" -> LocalDate.of(slate.firstDate.year + 1, 1, 20)
                "IDN" -> LocalDate.of(slate.firstDate.year, 10, 20)
                "BRA" -> LocalDate.of(slate.firstDate.year + 1, 1, 5)
                "TWN" -> LocalDate.of(slate.firstDate.year, 5, 20)
                "KOR" -> LocalDate.of(slate.firstDate.year, 5, 10)
                "FIN" -> date.plusMonths(1).withDayOfMonth(1)
                else -> date
            }
        val backing =
            winner.party ?: slate.nominations
                .find { it.nominee.id == winner.id }
                ?.endorsers
                ?.firstOrNull()
        val nomination =
            slate.nominations.find { it.nominee.id == winner.id } ?: slate.nominations.find { it.nominee.party == winner.party }
        val deputy = if (slate.houseDate == null) nomination?.runningMate else null
        if (country == "IDN") {
            incomingFinalists =
                ballot.take(2).map { voted ->
                    val ticket = slate.nominations.find { it.nominee.id == voted.person.id }
                    (voted.person.party ?: ticket?.endorsers?.firstOrNull()) to ticket?.runningMate?.party
                }
        }
        campaign = null
        primaries = null
        presidentElect = PresidentElect(winner, inauguration, backing, deputy, separateDeputy = slate.houseDate != null)
        val installed = inaugurate(date)
        return ExecutiveResult(rule.title, slate, ballot, installed?.person ?: winner, round, count)
    }

    fun inaugurate(date: LocalDate): PresidentElect? {
        val elected = presidentElect?.takeIf { it.inauguration <= date } ?: return null
        leadership.seatPresident(elected.person, date)
        if (vice != null && !elected.separateDeputy) leadership.seatVicePresident(elected.deputy, date, newTerm = true)
        if (country == "IDN" && incomingFinalists.isNotEmpty()) lastFinalists = incomingFinalists
        indonesianCaretakerUntil = null
        endorsedIncumbent = elected.person.id to elected.governingParty
        presidentElect = null
        return elected.copy(person = leadership.executiveIncumbent())
    }
}
