package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import team.themoment.axia.simulation.people.LeadershipState
import team.themoment.axia.simulation.people.Person
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.random.Random

enum class ConfidenceProcedure(
    val label: String,
) {
    CONSTRUCTIVE("후임 정부 동시 선출"),
    DUMA_CONFIDENCE("두마 재표결 · 대통령의 내각 해임 또는 해산"),
    CABINET_CENSURE("하원 3분의 2 내각 불신임 · 대통령의 총리 임명"),
    PARLIAMENTARY("내각 사임 · 후계 정부 협의"),
    OPEN_BALLOT("총리 후보 표결 · 공개 선거"),
    ASSEMBLY_PRESIDENT("대통령 사임 · 의회 선출"),
    PRESIDENT_APPOINTS("행정원장 사임 · 총통 임명"),
    TWO_NOMINATIONS("대통령 지명 · 원내 다수 지명"),
}

enum class ConfidenceMajority {
    MEMBERS,
    TWO_THIRDS,
    PRESENT,
    VOTES_CAST,
}

/** 협의일·선거일은 각국의 기한 안에서 정한 게임 일정이다. */
@Serializable
data class ConfidenceRule(
    val procedure: ConfidenceProcedure,
    val majority: ConfidenceMajority,
    val consultationDays: Int,
    val electionDays: Int? = null,
    val sourceNote: String,
) {
    init {
        require((consultationDays == 0) == (procedure == ConfidenceProcedure.CONSTRUCTIVE))
        require(consultationDays >= 0 && (electionDays == null || electionDays > 0) && sourceNote.isNotBlank())
    }
}

internal data class ConfidenceDecision(
    val chamber: Legislature,
    val news: List<String>,
    val successor: Boolean = false,
    val election: LocalDate? = null,
    val acting: Boolean = false,
    val early: Boolean = true,
)

private val ParliamentaryGroup.backsCabinet: Boolean
    get() = role == PartyRole.LEAD || role == PartyRole.COALITION || role == PartyRole.SUPPLY

/** 월간 여론은 발의 기회에만 영향을 준다. 가결·새 내각의 신임은 해당 표결에 참여한 원내 정당 의석으로 정한다. */
internal class GovernmentConfidence(
    private val country: String,
    private val rule: ConfidenceRule,
    internal var upper: UpperHouse?,
    private val pakistan: PakistanSuccession? = null,
) {
    private val presidential =
        when (rule.procedure) {
            ConfidenceProcedure.DUMA_CONFIDENCE, ConfidenceProcedure.CABINET_CENSURE -> PresidentialCabinet(rule)
            else -> null
        }
    private var baseline: Double? = null
    private val partnerMood = mutableMapOf<String, Double>()
    var nextBallot: LocalDate? = null
        get() = presidential?.nextDecision ?: field
        private set
    private var crisisStarted: LocalDate? = null
    private var newlyElected = false
    private var lastDissolutionElection: LocalDate? = null
    private var barredPremier: Int? = null
    private var barredUntil: LocalDate? = null
    val caretaker: Boolean get() = presidential?.caretaker ?: (crisisStarted != null)
    val routineBusiness: Boolean get() = rule.procedure == ConfidenceProcedure.CABINET_CENSURE && caretaker

    var dissolved: Boolean = false
        get() = presidential?.dissolved ?: field
        private set

    val governmentBillsSuspended: Boolean get() = routineBusiness || legislationSuspended

    val legislationSuspended: Boolean get() = (dissolved && country != "LTU") || pakistan?.awaitingElectionOfPremier == true

    fun italianDissolutionAllowed(
        date: LocalDate,
        president: Person,
        nextLegislative: LocalDate?,
    ): Boolean {
        // 2024 시나리오 첫 대통령은 2022-02-03 취임, 이후 새 인물은 실제 게임 취임일부터 계산한다.
        var end = (if (president.generated) president.since else LocalDate.of(2022, 2, 3)).plusYears(7)
        while (end <= date) end = end.plusYears(7)
        return date < end.minusMonths(6) || nextLegislative?.let { it.minusMonths(6) <= end } == true
    }

    fun dissolveOnAdvice(
        chamber: Legislature,
        date: LocalDate,
        poll: LocalDate,
    ): ConfidenceDecision? = pakistan?.let { dissolvePakistan(it, chamber, date, poll, true) }

    private fun dissolvePakistan(
        procedure: PakistanSuccession,
        chamber: Legislature,
        date: LocalDate,
        poll: LocalDate,
        early: Boolean,
    ): ConfidenceDecision {
        val decision = procedure.dissolve(chamber, date, poll, early)
        crisisStarted = date
        dissolved = true
        nextBallot = procedure.next
        return decision
    }

    fun recordDissolutionElection(date: LocalDate) {
        lastDissolutionElection = date
    }

    /** 대통령을 따로 선출하는 나라에서는 의회 불신임으로 그 직위를 바꾸지 않는다. */
    fun appoint(
        chamber: Legislature,
        leadership: LeadershipState,
        date: LocalDate,
        acting: Boolean = false,
    ): String {
        val lead = chamber.groups.find { it.role == PartyRole.LEAD }?.name
        val offices = leadership.current()
        val outgoingParty = (offices.headOfGovernment ?: offices.headOfState).party
        val party =
            when {
                country == "RWA" -> null
                country == "RUS" -> offices.headOfState.party
                country == "TWN" -> offices.headOfState.party ?: outgoingParty
                country == "PAK" -> lead
                else -> lead ?: outgoingParty
            }
        val title =
            when (country) {
                "ZAF" -> if (acting) "대통령 권한대행" else "대통령"
                "TWN" -> if (acting) "행정원장 권한대행" else "행정원장"
                "PAK" -> "총리"
                else -> null
            }
        val person = leadership.replaceHead(party, date, title)
        return "${person.title} ${person.name} 취임"
    }

    fun resetGovernment() {
        presidential?.reset()
        pakistan?.reset()
        baseline = null
        partnerMood.clear()
        nextBallot = null
        crisisStarted = null
        dissolved = false
        newlyElected = false
    }

    fun afterElections(
        chamber: Legislature,
        legislativeDue: Boolean,
        presidentChanged: Boolean,
        date: LocalDate,
        presidentElected: Boolean = presidentChanged,
    ): ConfidenceDecision? {
        presidential?.let { return it.afterElections(chamber, legislativeDue, presidentElected, presidentChanged, date) }
        if (legislativeDue && pakistan != null) {
            val decision = pakistan.afterElection(chamber, date)
            crisisStarted = date
            dissolved = false
            nextBallot = pakistan.next
            return decision
        }
        if (country == "LTU" && legislativeDue) {
            val formation = afterCaretakerElection(chamber, date)
            return formation.copy(news = listOf("새 세이마스 개원으로 내각이 대통령에게 권한을 반납했다. 의회가 새 정부에 신임을 부여할 때까지 현 내각이 직무를 수행한다."))
        }
        if (legislativeDue && caretaker) return afterCaretakerElection(chamber, date)
        if (rule.procedure != ConfidenceProcedure.PRESIDENT_APPOINTS || !presidentChanged) return null
        if (dissolved) return ConfidenceDecision(chamber, listOf("신임 총통이 총선까지 행정원장 권한대행을 지명했다."), acting = true)
        return seat(chamber, "신임 총통이 행정원장을 임명했다.")
    }

    /** 총선으로 의석이 바뀌면 새 의회가 후임을 결정한다. 대통령 지명권과 양원 신임 요건도 유지한다. */
    private fun afterCaretakerElection(
        chamber: Legislature,
        date: LocalDate,
    ): ConfidenceDecision {
        if (rule.procedure == ConfidenceProcedure.PRESIDENT_APPOINTS) return seat(chamber, "총통이 새 입법원 개원에 맞춰 행정원장을 임명했다.")
        if (rule.procedure == ConfidenceProcedure.TWO_NOMINATIONS && dissolved) {
            val largest = chamber.groups.filter { it.role != PartyRole.OTHER }.maxByOrNull { it.votingSeats }
            if (largest != null) {
                val cabinet = ElectionModel.formGovernment(chamber.groups, chamber.required, emptyMap(), largest.name)
                return seat(chamber.withGroups(cabinet), "대통령이 총선에서 선출된 최대 원내 세력의 지명자를 총리로 임명했다.")
            }
        }
        newlyElected = true
        dissolved = false
        crisisStarted = date
        nextBallot = date.plusDays(rule.consultationDays.toLong())
        return ConfidenceDecision(chamber, listOf("새 의회의 후임 정부 구성 절차가 시작됐다."))
    }

    fun review(
        chamber: Legislature,
        approval: Double,
        mood: (ParliamentaryGroup) -> Double,
        premier: Int,
        date: LocalDate,
        random: Random,
        nextPresidential: LocalDate? = null,
    ): ConfidenceDecision? {
        if (caretaker || dissolved) return null
        val news = mutableListOf<String>()
        val atFormation = baseline ?: approval.also { baseline = it }
        val parties =
            chamber.groups.map { p ->
                if (p.role != PartyRole.COALITION && p.role != PartyRole.SUPPLY) return@map p
                val current = mood(p)
                val previous = partnerMood.getOrPut(p.modelName) { current }
                if (current < 35 && current < previous - 8 && random.nextDouble() < 0.12) {
                    news += "${p.name} 정부 지지 철회"
                    p.copy(role = PartyRole.OPPOSITION)
                } else {
                    p
                }
            }
        val updated = if (news.isEmpty()) chamber else chamber.withGroups(parties)
        if (presidential != null) {
            val filed =
                if (approval < 35 && approval < atFormation - 8 && random.nextDouble() < 0.25) {
                    presidential.file(updated, premier, date, approval, nextPresidential)
                } else {
                    null
                }
            if (filed != null) return filed.copy(news = news + filed.news)
            return if (news.isEmpty()) null else ConfidenceDecision(updated, news)
        }
        val votes = parties.filter { it.role == PartyRole.OPPOSITION }.sumOf { it.votingSeats }
        if (votes > 0 && parties.filter { it.backsCabinet }.sumOf { it.votingSeats } < chamber.required &&
            approval < 35 && approval < atFormation - 8 && random.nextDouble() < 0.25 &&
            !(premier == barredPremier && barredUntil?.let { date < it } == true)
        ) {
            val result = motion(updated, premier, date)
            return result.copy(news = news + result.news)
        }
        return if (news.isEmpty()) null else ConfidenceDecision(updated, news)
    }

    /** 명시적으로 지지를 철회한 야당만 찬성하고 기타·불참 의원은 기권한다. */
    internal fun motion(
        chamber: Legislature,
        premier: Int,
        date: LocalDate,
    ): ConfidenceDecision {
        check(!caretaker && presidential == null)
        val votes = chamber.groups.filter { it.role == PartyRole.OPPOSITION }.sumOf { it.votingSeats }
        val against = chamber.groups.filter { it.backsCabinet }.sumOf { it.votingSeats }
        val members =
            when (country) {
                "UKR" -> 450
                "POL" -> 460
                "ISR" -> 120
                "PAK" -> 336
                "TWN" -> 113
                "LTU" -> 141
                "EST" -> 101
                "DEU" -> chamber.filledSeats + chamber.vacancies.size
                else -> chamber.filledSeats
            }
        val required =
            when (rule.majority) {
                ConfidenceMajority.TWO_THIRDS -> error("내각 전체 비밀투표는 르완다 절차로 처리한다.")
                ConfidenceMajority.MEMBERS -> members
                ConfidenceMajority.PRESENT -> chamber.votingSeats
                ConfidenceMajority.VOTES_CAST -> votes + against
            } / 2 + 1
        val alternative =
            if (rule.procedure == ConfidenceProcedure.CONSTRUCTIVE) {
                // 찬성하는 정당들끼리 후임자와 내각에 합의해야 한다. 현 정부의 표를 재사용하지 않는다.
                val pool = chamber.groups.filter { it.role == PartyRole.OPPOSITION }
                cabinet(chamber, pool, members / 2 + 1, date)
            } else {
                null
            }
        val yes =
            when {
                alternative != null -> alternative.groups.filter { it.backsCabinet }.sumOf { it.votingSeats }
                rule.procedure == ConfidenceProcedure.CONSTRUCTIVE -> 0
                else -> votes
            }
        if (yes < required) {
            if (country == "TWN") {
                barredPremier = premier
                barredUntil = date.plusYears(1)
            }
            return ConfidenceDecision(chamber, listOf("불신임안 부결 · 찬성 ${yes}표 / 가결 ${required}표"))
        }
        val result = "불신임안 가결 · 찬성 ${yes}표 / 가결 ${required}표"
        if (alternative != null) {
            resetGovernment()
            return ConfidenceDecision(alternative, listOf(result, "의회가 후임 정부에 신임을 부여했다."), successor = true)
        }
        crisisStarted = date
        nextBallot = date.plusDays(rule.consultationDays.toLong())
        val interim =
            if (country == "ZAF") {
                "대통령과 내각이 사임했다. 권한대행 아래 후임 대통령을 선출한다."
            } else {
                "후임 내각이 결정될 때까지 정부가 임시로 직무를 수행한다."
            }
        return ConfidenceDecision(chamber, listOf(result, interim), acting = country == "ZAF")
    }

    /** 기한은 일 단위로 확인한다. 총선 공고 뒤에는 같은 불신임으로 일정을 다시 잡지 않는다. */
    fun negotiate(
        chamber: Legislature,
        date: LocalDate,
        nextLegislative: LocalDate?,
        nextPresidential: LocalDate?,
        president: Person,
        premier: Int? = null,
    ): ConfidenceDecision? {
        presidential?.let { return it.advance(chamber, date, nextLegislative, nextPresidential, president, premier) }
        if (pakistan != null) {
            if (!dissolved && pakistan.termEnd == date && nextLegislative != null) {
                return dissolvePakistan(pakistan, chamber, date, nextLegislative, false)
            }
            if (pakistan.next != null) {
                val decision = pakistan.advance(chamber, date)
                nextBallot = pakistan.next
                if (decision?.successor == true) resetGovernment()
                return decision
            }
        }
        if (nextBallot?.let { date >= it } != true) return null
        val age = ChronoUnit.DAYS.between(requireNotNull(crisisStarted), date)
        val outgoing = chamber.groups.firstOrNull { it.role == PartyRole.LEAD }?.name
        val parties = chamber.groups.filter { it.role != PartyRole.OTHER && it.votingSeats > 0 }
        val candidates =
            parties.sortedWith(
                compareBy<ParliamentaryGroup> {
                    !newlyElected && it.name == outgoing
                }.thenByDescending { it.votingSeats },
            )
        if (rule.procedure == ConfidenceProcedure.ASSEMBLY_PRESIDENT) {
            val winner = electPresident(candidates)
            if (winner != null) {
                val government = ElectionModel.formGovernment(chamber.groups, chamber.required, emptyMap(), winner.name)
                return seat(chamber.withGroups(government), "국민의회가 ${winner.name} 후보를 대통령으로 선출했다.")
            }
            if (age >= 30 && nextLegislative != null) return dissolve(chamber, date, nextLegislative)
            nextBallot = minOf(date.plusDays(7), requireNotNull(crisisStarted).plusDays(30))
            return ConfidenceDecision(chamber, listOf("국민의회 대통령 선거에서 당선자가 없어 ${nextBallot}에 다시 투표한다."))
        }
        val dissolutionAllowed =
            when (country) {
                "FRA" -> {
                    lastDissolutionElection?.let { date >= it.plusYears(1) } ?: true
                }

                "LTU" -> {
                    lastDissolutionElection?.let { date >= it.plusMonths(6) } != false &&
                        nextPresidential?.let { date < it.minusMonths(6) } == true
                }

                "ITA" -> {
                    italianDissolutionAllowed(date, president, nextLegislative)
                }

                "EGY" -> {
                    age >= 60
                }

                else -> {
                    true
                }
            }
        val candidateOrder =
            if (rule.procedure == ConfidenceProcedure.TWO_NOMINATIONS) {
                val first =
                    if (age <=
                        30
                    ) {
                        parties.find { it.name == president.party } ?: parties.filter { it.backsCabinet }.maxByOrNull { it.votingSeats }
                    } else {
                        null
                    }
                listOfNotNull(first ?: parties.maxByOrNull { it.votingSeats })
            } else if (rule.procedure == ConfidenceProcedure.OPEN_BALLOT && age < 21) {
                listOfNotNull(candidates.getOrNull(if (age <= 7) 0 else 1))
            } else {
                candidates
            }
        val required =
            when (country) {
                "UKR" -> 226
                "PAK" -> 169
                else -> if (rule.majority == ConfidenceMajority.VOTES_CAST) parties.sumOf { it.votingSeats } / 2 + 1 else chamber.required
            }
        // 해산 없이 거듭하는 협상에서는 이념이 먼 정당도 내각 밖에서 신임과 예산을 지원할 수 있다.
        val failedRounds = (age / rule.consultationDays - 1).coerceAtLeast(0)
        val limit =
            if (rule.procedure == ConfidenceProcedure.PARLIAMENTARY && (rule.electionDays == null || !dissolutionAllowed)) {
                (0.58 + 0.08 * failedRounds).coerceAtMost(1.0)
            } else {
                0.58
            }
        val alternative =
            if (rule.procedure == ConfidenceProcedure.OPEN_BALLOT &&
                age >= 21
            ) {
                null
            } else {
                cabinet(chamber, parties, required, date, candidateOrder, limit)
            }
        if (rule.procedure == ConfidenceProcedure.PRESIDENT_APPOINTS) {
            // 총통은 행정원장 임명에 의회의 신임 투표가 필요 없다. 교착 시에는 해산 요청을 수용하는 경로를 쓴다.
            if (alternative == null && nextLegislative != null) {
                val decision = dissolve(chamber, date, nextLegislative)
                if (decision.election == null) return decision
                return decision.copy(news = listOf("행정원장이 사직하며 입법원 해산을 요청했다. 총통은 입법원장과 협의해 해산을 결정했다.") + decision.news)
            }
            return seat(chamber, "총통이 후임 행정원장을 임명했다.")
        }
        if (alternative != null) return seat(alternative, "현 의회에서 후임 정부가 필요한 지지를 확보했다.")
        if (rule.procedure == ConfidenceProcedure.OPEN_BALLOT && age >= 21) {
            // 후보가 과반을 모으지 못한 다자 투표를 결선·최종 순위 투표로 집계한다.
            val winner = candidates.maxByOrNull { it.votingSeats }
            if (winner != null) {
                val result =
                    chamber.withGroups(
                        chamber.groups.map { p ->
                            when {
                                p.name == winner.name -> p.copy(role = PartyRole.LEAD)
                                p.role == PartyRole.OTHER -> p
                                else -> p.copy(role = PartyRole.OPPOSITION)
                            }
                        },
                    )
                return seat(result, "두 후보의 과반 확보 실패 후 공개 선거에서 최다득표 총리가 선출됐다.")
            }
        }
        if (rule.electionDays != null && nextLegislative != null && dissolutionAllowed) return dissolve(chamber, date, nextLegislative)
        if (country == "FRA" && candidates.isNotEmpty()) {
            val nominee = candidates.first()
            return seat(
                chamber.withGroups(ElectionModel.formGovernment(chamber.groups, required, emptyMap(), nominee.name)),
                "대통령이 현 의회에서 소수 내각을 임명했다.",
            )
        }
        nextBallot = date.plusDays(rule.consultationDays.toLong())
        return ConfidenceDecision(chamber, listOf("후임 정부 협의가 결렬됐다. 다음 의회 협의일은 ${nextBallot}이다."))
    }

    /** 남아공 헌법 부표 3. 탈락 정당 의원의 다음 표는 잔여 후보와의 이념 거리로 추계한다. */
    private fun electPresident(parties: List<ParliamentaryGroup>): ParliamentaryGroup? {
        val candidates = parties.toMutableList()

        fun ballot(nominees: List<ParliamentaryGroup>): Map<ParliamentaryGroup, Int> {
            val votes = nominees.associateWith { 0 }.toMutableMap()
            for (party in parties) {
                val choice = nominees.firstOrNull { it == party } ?: nominees.minBy { ideologicalDistance(it.identity, party.identity) }
                votes[choice] = votes.getValue(choice) + party.votingSeats
            }
            return votes
        }
        while (candidates.size > 1) {
            val votes = ballot(candidates)
            val best = votes.maxBy { it.value }
            if (best.value > votes.values.sum() / 2) return best.key
            if (candidates.size == 2) return null
            val lowest = votes.filterValues { it == votes.values.min() }.keys.toList()
            // 동률 최하위 후보 간 별도 투표도 가르지 못하면 다음 회의로 넘긴다.
            val runoff = ballot(lowest)
            val eliminated = runoff.filterValues { it == runoff.values.min() }.keys.singleOrNull() ?: return null
            candidates.remove(eliminated)
        }
        return candidates.singleOrNull()
    }

    private fun cabinet(
        chamber: Legislature,
        parties: List<ParliamentaryGroup>,
        required: Int,
        date: LocalDate,
        candidates: List<ParliamentaryGroup> = parties.sortedByDescending { it.votingSeats },
        maxPartnerDistance: Double = 0.58,
    ): Legislature? {
        val previous = parties.associate { it.name to it.role }.takeIf { roles -> PartyRole.LEAD in roles.values }.orEmpty()
        var strongestProposal: Legislature? = null
        var strongestSupport = 0
        for (candidate in candidates) {
            val agreed = ElectionModel.formGovernment(parties, required, previous, candidate.name, maxPartnerDistance = maxPartnerDistance)
            val backing = agreed.filter { it.backsCabinet }.associateBy { it.name }
            val yes = backing.values.sumOf { it.votingSeats }
            val joint = country == "THA" && date < LocalDate.of(2024, 5, 11)
            val lowerApproved = if (joint) yes * 10 >= chamber.filledSeats else yes >= required
            val elected = lowerApproved && upperSupports(chamber, backing.keys, yes, date)
            if (!elected && (rule.procedure != ConfidenceProcedure.CONSTRUCTIVE || yes <= strongestSupport)) continue
            val proposal =
                chamber.withGroups(
                    chamber.groups.map { p ->
                        backing[p.name] ?: if (p.role == PartyRole.OTHER) p else p.copy(role = PartyRole.OPPOSITION)
                    },
                )
            if (elected) return proposal
            strongestProposal = proposal
            strongestSupport = yes
        }
        return strongestProposal
    }

    /** 하원 협상에 참여한 교섭단체의 상원 동료 표. 구성 미상인 상원은 여야 블록별로 추계한다. */
    private fun upperSupports(
        chamber: Legislature,
        backing: Set<String>,
        lowerYes: Int,
        date: LocalDate,
    ): Boolean {
        val house = upper ?: return true
        if (country != "ITA" && !(country == "THA" && date < LocalDate.of(2024, 5, 11))) return true

        fun share(block: List<ParliamentaryGroup>): Double {
            val total = block.sumOf { it.votingSeats }
            return if (total == 0) 0.0 else block.filter { it.name in backing }.sumOf { it.votingSeats }.toDouble() / total
        }
        if (house.groups.isNotEmpty()) {
            val aligned =
                chamber.groups
                    .filter { it.name in backing }
                    .map { it.modelName }
                    .toSet()
            val vote = house.session(chamber, date)
            val yes =
                vote.groups.sumOf { group ->
                    when (group.alignWith) {
                        in aligned -> group.votingSeats
                        null -> (group.votingSeats * lowerYes.toDouble() / chamber.filledSeats).roundToInt()
                        else -> 0
                    }
                }
            return if (country == "ITA") yes > vote.votingSeats / 2 else yes + lowerYes > (vote.votingSeats + chamber.filledSeats) / 2
        }
        val senate =
            (
                share(chamber.groups.filter { it.backsCabinet }) * house.governmentSeats +
                    share(chamber.groups.filter { it.role == PartyRole.OPPOSITION }) * house.oppositionSeats +
                    lowerYes.toDouble() / chamber.filledSeats * house.otherSeats
            ).roundToInt()
        return if (country == "ITA") senate > house.seats / 2 else senate + lowerYes > (house.seats + chamber.filledSeats) / 2
    }

    private fun dissolve(
        chamber: Legislature,
        date: LocalDate,
        regular: LocalDate,
    ): ConfidenceDecision {
        val earlyPoll = earlyHousePoll(country, date, requireNotNull(rule.electionDays))
        val election = minOf(earlyPoll, regular)
        nextBallot = null
        if (election == regular && country != "ZAF" && country != "EGY" && country != "PAK") {
            return ConfidenceDecision(chamber, listOf("예정된 $regular 총선까지 관리정부가 직무를 수행한다."), acting = country == "TWN")
        }
        dissolved = true
        if (pakistan != null) return dissolvePakistan(pakistan, chamber, date, election, true)
        return ConfidenceDecision(chamber, listOf("후임 정부가 구성되지 않아 총선을 ${election}에 치른다."), election = election, acting = country == "TWN")
    }

    private fun seat(
        chamber: Legislature,
        text: String,
    ): ConfidenceDecision {
        resetGovernment()
        return ConfidenceDecision(chamber, listOf(text), successor = true)
    }
}
