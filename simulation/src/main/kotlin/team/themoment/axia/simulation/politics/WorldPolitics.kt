package team.themoment.axia.simulation.politics

import team.themoment.axia.simulation.country.CountryNews
import team.themoment.axia.simulation.economy.EconomyIndicators
import team.themoment.axia.simulation.math.nextGaussian
import team.themoment.axia.simulation.nation.PopulationGroup
import team.themoment.axia.simulation.people.Leadership
import team.themoment.axia.simulation.people.LeadershipState
import team.themoment.axia.simulation.people.PeopleRegistry
import team.themoment.axia.simulation.people.retires
import team.themoment.axia.simulation.society.Identity
import team.themoment.axia.simulation.text.josa
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** AI 국가 한 곳의 정치 요약. */
data class CountryPoliticsSnapshot(
    val countryId: String,
    val regime: Regime,
    val government: String,
    val leadership: Leadership,
    val approval: Double,
    val legislature: Legislature?,
    val nextElection: LocalDate?,
    val lastElection: ElectionResult?,
    val pendingBill: AiFiscalBill?,
    val lastBill: AiFiscalBill?,
    val executiveCampaign: ExecutiveCampaign? = null,
    val newParties: List<NewParty> = emptyList(),
    val takenPartyNames: Set<String> = emptySet(),
)

/** 세계에서 일어난 일 한 줄. */
data class WorldNews(
    val date: LocalDate,
    val countryId: String,
    val source: String,
    val text: String,
    val major: Boolean,
)

/**
 * 플레이어가 아닌 플레이 가능 국가의 월간 정치. 플레이어 국가와 같은 선거·의석·정부 구성 모델을 쓰고,
 * 지지율은 인구 집단 대신 거시 지표(실업, 물가, 경기)의 시작 대비 변화와 집권 피로로 정한다.
 */
internal class AiCountryPolitics(
    val countryId: String,
    private val profile: PoliticalProfile,
    private val constitution: ConstitutionProfile,
    people: PeopleRegistry,
    start: LocalDate,
    private val random: Random,
) {
    private val rules = profile.elections
    private val leadership =
        LeadershipState(countryId, people, start, rules.executive?.title, parliamentary = rules.system == GovernmentSystem.PARLIAMENTARY)
    private var legislature: Legislature? = profile.legislature
    private var nextLegislative: LocalDate? = rules.legislature?.next
    private var nextExecutive: LocalDate? = rules.executive?.next
    private val executiveElection = ExecutiveElection(leadership, profile.regime, constitution.executiveTermLimit, random)
    private var lastElection: ElectionResult? = null
    private val legislation = AiLegislation()
    private val confidence = constitution.confidence?.let { GovernmentConfidence(countryId, it, constitution.upperHouse) }
    private var earlyCalled = false
    private var billLapsedOnDissolution = false
    private val newParties = EmergingParties(profile)
    private var identityMood = Identity.entries.associateWith { 50.0 }
    private val identityShares = identityMood.keys.associateWith { 0.25 }

    private var monthsInOffice = 0

    /** 지지율이 돌아가는 기준. 시작 정부는 2024년 1월 여론조사, 새 정부는 50이다. */
    private var base = InitialApproval.forCountry(countryId).approval
    var approval = base
        private set
    private var initial: EconomyIndicators? = null

    /** 사건이 지지율을 바꾼다. */
    fun shiftApproval(points: Double) {
        approval = (approval + points).coerceIn(5.0, 95.0)
    }

    /** 쿠데타로 군부가 정권을 잡는다. 선거가 정지된다. */
    fun coup(date: LocalDate) {
        rebelGovernment = null
        legislation.dissolve()
        confidence?.resetGovernment()
        earlyCalled = false
        billLapsedOnDissolution = false
        leadership.installJunta(date)
        juntaSince = date
        legislature = null
        nextLegislative = null
        nextExecutive = null
        executiveElection.cancel()
        newParties.clear()
    }

    fun installRebelGovernment(
        rebels: String,
        date: LocalDate,
    ) {
        confidence?.resetGovernment()
        earlyCalled = false
        billLapsedOnDissolution = false
        leadership.installRebelGovernment(rebels, date)
        rebelGovernment = rebels
        juntaSince = null
        legislature = null
        nextLegislative = null
        nextExecutive = null
        executiveElection.cancel()
        newParties.clear()
        legislation.dissolve()
        monthsInOffice = 0
        base = BASELINE
        approval = BASELINE
    }

    private var juntaSince: LocalDate? = null
    private var rebelGovernment: String? = null
    val hasInterimGovernment: Boolean get() = rebelGovernment != null || juntaSince != null

    fun advanceMonth(
        indicators: EconomyIndicators,
        date: LocalDate,
        enact: (String, Double, Double) -> Unit,
    ): List<WorldNews> {
        val departure = leadership.checkMortality(date, random)
        val start = initial ?: indicators.also { initial = it }
        monthsInOffice++
        val damping = if (profile.regime.autocratic) AUTOCRATIC_DAMPING else 1.0
        val jobs = (-JOB_WEIGHT * (indicators.unemployment - start.unemployment)).coerceIn(-25.0, 8.0)
        val comfort = maxOf(start.inflation, INFLATION_COMFORT)
        val prices = (-PRICE_WEIGHT * (indicators.inflation - comfort)).coerceIn(-20.0, 3.0)
        val growth = (GROWTH_WEIGHT * (indicators.growth - start.growth)).coerceIn(-12.0, 8.0)
        val fatigue = -(monthsInOffice * FATIGUE_PER_MONTH).coerceAtMost(MAX_FATIGUE)
        val target = base + damping * (jobs + prices + growth + fatigue)
        approval = (approval + (target - approval) * SMOOTHING + random.nextGaussian() * APPROVAL_NOISE).coerceIn(5.0, 95.0)
        // AI 국가에는 유권자 개별 표본이 없어 경기·물가·고용 노출로 성향별 여론을 추정한다.
        identityMood =
            identityMood.keys.associateWith { identity ->
                val pressure =
                    when (identity) {
                        Identity.YOUTH, Identity.UNIONISTS -> jobs
                        Identity.PARENTS, Identity.EGALITARIANS -> prices
                        Identity.FREE_MARKET -> growth
                        else -> 0.0
                    }
                (approval + pressure * 0.45).coerceIn(0.0, 100.0)
            }
        val fresh =
            legislature
                ?.takeIf {
                    nextLegislative != null
                }?.let { newParties.advance(date, it.groups, identityMood, identityShares, random, ::takenPartyNames) }
                .orEmpty()
        val chamber = legislature
        val heads = leadership.current()
        val change =
            if (chamber == null) {
                null
            } else {
                confidence?.review(
                    chamber,
                    approval,
                    { it.supportMood(emptyMap(), identityMood, approval) },
                    (heads.headOfGovernment ?: heads.headOfState).id,
                    date,
                    random,
                )
            }
        val confidenceNews = change?.let { applyConfidence(it, date) }.orEmpty()
        return confidenceNews + fresh.map { WorldNews(date, countryId, it.name, "${it.identity.label} 성향의 신당 ${it.name} 창당", true) } +
            listOfNotNull(
                departure?.let { WorldNews(date, countryId, "지도자 교체", it, major = true) },
                if (confidence?.dissolved ==
                    true
                ) {
                    null
                } else {
                    legislation.advance(
                        date,
                        countryId,
                        government(),
                        legislature,
                        constitution.upperHouse,
                        indicators,
                        approval,
                        enact,
                    )
                },
            )
    }

    private fun takenPartyNames(): Set<String> = newParties.takenNames(legislature, executiveElection)

    fun renameParty(
        id: Int,
        name: String,
    ) {
        val chamber = legislature ?: return
        val change = newParties.rename(id, name, chamber, takenPartyNames()) ?: return
        legislature = chamber.withGroups(chamber.groups.map(change::group))
        executiveElection.renameParty(change)
        legislation.renameParty(change)
    }

    private fun applyConfidence(
        decision: ConfidenceDecision,
        date: LocalDate,
    ): List<WorldNews> {
        legislature = decision.chamber
        val office =
            if (decision.successor ||
                decision.acting
            ) {
                requireNotNull(confidence).appoint(decision.chamber, leadership, date, decision.acting)
            } else {
                null
            }
        if (decision.successor) renewMandate()
        decision.election?.let { poll ->
            billLapsedOnDissolution = legislation.dissolve()
            nextLegislative = poll
            earlyCalled = true
        }
        return (decision.news + listOfNotNull(office)).map { WorldNews(date, countryId, decision.chamber.name, it, major = true) }
    }

    private fun renewMandate() {
        monthsInOffice = 0
        base = BASELINE
        approval = BASELINE + HONEYMOON
    }

    /** 오늘 선거가 있으면 치르고 소식을 돌려준다. */
    fun holdElections(date: LocalDate): List<WorldNews> {
        val nominated = executiveElection.prepare(date, nextExecutive, rules.executive, legislature?.groups.orEmpty(), approval)
        val nominations =
            if (nominated &&
                rules.executive?.partyPrimary == true
            ) {
                listOf(WorldNews(date, countryId, "선거", "당내 경선에서 본선 후보가 확정됐다.", false))
            } else {
                emptyList()
            }
        val legislativeDue = nextLegislative?.let { it <= date } == true
        val executiveDue = nextExecutive?.let { it <= date } == true
        val transition =
            if (legislativeDue || (executiveDue && countryId == "TWN")) {
                emptyList()
            } else {
                legislature
                    ?.let { chamber ->
                        confidence?.negotiate(chamber, date, nextLegislative, nextExecutive, leadership.current().headOfState)?.let {
                            applyConfidence(it, date)
                        }
                    }.orEmpty()
            }
        if (!legislativeDue && !executiveDue) return nominations + transition
        val before = government()
        val wasEarly = earlyCalled && legislativeDue
        val billLapsed = legislativeDue && (legislation.dissolve() || billLapsedOnDissolution)
        var parties: List<PartyResult>? = null
        if (legislativeDue) {
            parties = electLegislature(date)
            if (earlyCalled) confidence?.recordDissolutionElection(date)
            billLapsedOnDissolution = false
            earlyCalled = false
            nextLegislative = date.plusYears(requireNotNull(rules.legislature).termYears.toLong())
        }
        val previousPresident = leadership.current().headOfState.id
        val executive = if (executiveDue) electExecutive(date) else null
        val formation =
            legislature?.let { chamber ->
                val newPresident = executive?.winner?.let { it.id != previousPresident } == true
                confidence?.afterElections(chamber, legislativeDue, newPresident, date)
            }
        val formationNews = formation?.let { applyConfidence(it, date) }.orEmpty()
        if (executive !=
            null
        ) {
            nextExecutive =
                executiveElection.campaign?.runoffDate
                    ?: executive.campaign.firstDate.plusYears(requireNotNull(rules.executive).termYears.toLong())
        }
        val after = government()
        lastElection = ElectionResult(date, early = wasEarly, parties, executive, before, after, billLapsed = billLapsed)
        val kind = if (executiveDue) "${rules.executive?.title} 선거" else "총선"
        val text =
            if (executive != null && executive.winner == null) {
                "$kind 1차 투표에서 과반 후보가 없어 ${executive.campaign.runoffDate}에 결선을 치른다."
            } else if (legislativeDue && confidence?.caretaker == true) {
                "$kind 의석이 확정됐다. 새 의회에서 후임 정부를 구성한다."
            } else if (before != after) {
                "$kind 결과 정권이 바뀌었다. 새 정부: $after"
            } else {
                "$kind 결과 ${after.josa("이", "가")} 정권을 지켰다."
            }
        if (before != after) {
            if (confidence?.caretaker != true) confidence?.resetGovernment()
            renewMandate()
        }
        return nominations + transition + formationNews + listOf(WorldNews(date, countryId, "선거", text, major = before != after))
    }

    private fun government(): String =
        when {
            rebelGovernment != null -> {
                "$rebelGovernment 과도정부 · ${leadership.current().leader.name}"
            }

            juntaSince != null -> {
                "군사평의회 · ${leadership.current().leader.name}"
            }

            confidence?.caretaker == true -> {
                "관리정부 · ${(leadership.current().headOfGovernment ?: leadership.current().headOfState).name}"
            }

            else -> {
                leadership.governmentLabel(
                    profile.initialGovernment,
                    rules.system == GovernmentSystem.SEMI_PRESIDENTIAL || countryId in setOf("EGY", "TWN"),
                )
            }
        }

    private fun electLegislature(date: LocalDate): List<PartyResult> {
        val rule = requireNotNull(rules.legislature)
        val chamber = legislature
        if (chamber == null) {
            val contest = requireNotNull(rule.openingContest)
            val result = ElectionModel.openingElection(rule, profile.regime) { random.nextGaussian() * VOTE_NOISE }
            legislature = result.legislature
            result.legislature.groups.firstOrNull { it.role == PartyRole.LEAD }?.let { lead ->
                leadership.onGovernmentFormed(lead.name, date, reelected = false, title = contest.headOfGovernmentTitle)
            }
            return result.parties
        }
        val shares = newParties.voteShares(voteShares(chamber.groups, rule.electoralSystem))
        val contenders = chamber.groups + newParties.contenders()
        newParties.clear()
        val seats = ElectionModel.legislativeSeats(shares, rule)
        val previousRoles = contenders.associate { it.name to it.role }
        val reseated = chamber.withGroups(ElectionModel.reseat(contenders, seats))
        val governed =
            if (confidence?.caretaker ==
                true
            ) {
                reseated
            } else {
                ElectionModel.afterLegislativeElection(reseated, rules, previousRoles, leadership.executiveIncumbent().party)
            }
        legislature = governed
        val leadBefore = previousRoles.entries.firstOrNull { it.value == PartyRole.LEAD }?.key
        val leadAfter = governed.groups.firstOrNull { it.role == PartyRole.LEAD }?.name
        if (leadAfter != null && rules.system != GovernmentSystem.PRESIDENTIAL && confidence?.caretaker != true) {
            leadership.onGovernmentFormed(leadAfter, date, reelected = leadAfter == leadBefore)
        }
        return contenders.map { party ->
            PartyResult(
                party.name,
                party.role,
                shares.getValue(party.name),
                chamber.groups
                    .find {
                        it.name == party.name
                    }?.seats ?: 0,
                seats.total(party.name),
            )
        }
    }

    private fun electExecutive(date: LocalDate): ExecutiveResult {
        val rule = requireNotNull(rules.executive)
        val result = executiveElection.vote(date, rule, approval)
        result.winner?.party?.let { winner ->
            legislature = legislature?.let { ElectionModel.presidentialLegislature(it, rules, winner) }
        }
        return result
    }

    private fun voteShares(
        parties: List<ParliamentaryGroup>,
        system: ElectoralSystem,
    ): Map<String, Double> =
        ElectionModel.voteShares(
            groups = parties,
            system = system,
            regime = profile.regime,
            approval = approval,
            satisfaction = PopulationGroup.entries.associateWith { approval },
            identityMood = identityMood,
            identityShares = identityShares,
            noise = { random.nextGaussian() * VOTE_NOISE },
        )

    fun snapshot(): CountryPoliticsSnapshot =
        CountryPoliticsSnapshot(
            countryId = countryId,
            regime = if (hasInterimGovernment) Regime.CLOSED_AUTOCRACY else profile.regime,
            government = government(),
            leadership = leadership.current(),
            approval = approval,
            legislature = legislature,
            nextElection = listOfNotNull(nextLegislative, nextExecutive).minOrNull(),
            lastElection = lastElection,
            pendingBill = legislation.pending,
            lastBill = legislation.lastDecision,
            executiveCampaign = executiveElection.campaign,
            newParties = newParties.snapshot(),
            takenPartyNames = takenPartyNames(),
        )

    private companion object {
        const val BASELINE = 50.0
        const val HONEYMOON = 8.0
        const val SMOOTHING = 0.3
        const val APPROVAL_NOISE = 1.2
        const val JOB_WEIGHT = 250.0
        const val PRICE_WEIGHT = 80.0
        const val GROWTH_WEIGHT = 120.0
        const val INFLATION_COMFORT = 0.02
        const val FATIGUE_PER_MONTH = 0.12
        const val MAX_FATIGUE = 10.0
        const val AUTOCRATIC_DAMPING = 0.5
        const val VOTE_NOISE = 0.1
    }
}

/** 플레이어 국가를 뺀 플레이 가능 국가의 정치. 국가 순서와 난수 순서가 고정돼 결정론을 지킨다. */
internal class WorldPolitics(
    countryIds: List<String>,
    private val people: PeopleRegistry,
    start: LocalDate,
    seed: Long,
) {
    private val countries =
        countryIds.sorted().map { id ->
            AiCountryPolitics(
                id,
                PoliticalProfile.forCountry(id),
                ConstitutionProfile.forCountry(id),
                people,
                start,
                Random(seed xor id.hashCode().toLong()),
            )
        }
    private val byId = countries.associateBy { it.countryId }

    private data class InterimState(
        val faction: String,
        var leaderId: Int,
        val legislation: AiLegislation = AiLegislation(),
    )

    private val interim = sortedMapOf<String, InterimState>()
    private val interimRandom = Random(seed xor 0x40726cL)

    fun installRebelGovernment(
        country: String,
        rebels: String,
        date: LocalDate,
    ) {
        val existing = byId[country]
        if (existing != null) {
            existing.installRebelGovernment(rebels, date)
        } else {
            val leader = people.generate(country, "과도정부 수반", rebels, date)
            interim[country] = InterimState(rebels, leader.id)
        }
    }

    fun hasInterimGovernment(id: String): Boolean = id in interim || byId[id]?.hasInterimGovernment == true

    fun advanceMonth(
        indicators: Map<String, EconomyIndicators>,
        date: LocalDate,
        enact: (String, Double, Double) -> Unit,
    ): List<WorldNews> =
        buildList {
            countries.forEach { addAll(it.advanceMonth(indicators.getValue(it.countryId), date, enact)) }
            interim.forEach { (id, state) ->
                val leader = requireNotNull(people.get(state.leaderId))
                if (leader.retires(date, interimRandom)) {
                    val successor = people.generate(id, leader.title, state.faction, date)
                    state.leaderId = successor.id
                    add(WorldNews(date, id, state.faction, "${successor.name} 과도정부 수반 취임", major = true))
                }
                state.legislation.advance(date, id, state.faction, null, null, indicators.getValue(id), 50.0, enact)?.let(::add)
            }
        }

    fun holdElections(date: LocalDate): List<WorldNews> = countries.flatMap { it.holdElections(date) }

    fun approval(countryId: String): Double? = byId[countryId]?.approval ?: interim[countryId]?.let { 50.0 }

    fun country(countryId: String): AiCountryPolitics? = byId[countryId]

    fun snapshot(): Map<String, CountryPoliticsSnapshot> =
        countries.associate { it.countryId to it.snapshot() } +
            interim.mapValues { (id, state) ->
                val leader = requireNotNull(people.get(state.leaderId))
                CountryPoliticsSnapshot(
                    id,
                    Regime.CLOSED_AUTOCRACY,
                    "${state.faction} 과도정부 · ${leader.name}",
                    Leadership(leader, null, false),
                    50.0,
                    null,
                    null,
                    null,
                    state.legislation.pending,
                    state.legislation.lastDecision,
                )
            }
}

/** 국가 모듈과 정치 계산의 소식을 세계 소식으로 바꾼다. */
internal fun CountryNews.toWorld(
    date: LocalDate,
    countryId: String,
): WorldNews = WorldNews(date, countryId, source, text, major)
