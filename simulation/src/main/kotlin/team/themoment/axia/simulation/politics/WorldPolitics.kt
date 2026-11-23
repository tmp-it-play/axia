package team.themoment.axia.simulation.politics

import team.themoment.axia.simulation.country.CountryNews
import team.themoment.axia.simulation.economy.EconomyIndicators
import team.themoment.axia.simulation.math.nextGaussian
import team.themoment.axia.simulation.nation.PopulationGroup
import team.themoment.axia.simulation.people.Leadership
import team.themoment.axia.simulation.people.LeadershipState
import team.themoment.axia.simulation.people.PeopleRegistry
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
    private var lastElection: ElectionResult? = null
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
        leadership.installJunta(date)
        juntaSince = date
        legislature = null
        nextLegislative = null
        nextExecutive = null
    }

    private var juntaSince: LocalDate? = null

    fun advanceMonth(
        indicators: EconomyIndicators,
        date: LocalDate,
    ): WorldNews? {
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
        return departure?.let { WorldNews(date, countryId, "지도자 교체", it, major = true) }
    }

    /** 오늘 선거가 있으면 치르고 소식을 돌려준다. */
    fun holdElections(date: LocalDate): List<WorldNews> {
        val legislativeDue = nextLegislative?.let { it <= date } == true
        val executiveDue = nextExecutive?.let { it <= date } == true
        if (!legislativeDue && !executiveDue) return emptyList()
        val before = government()
        var parties: List<PartyResult>? = null
        if (legislativeDue) {
            parties = electLegislature(date)
            nextLegislative = date.plusYears(requireNotNull(rules.legislature).termYears.toLong())
        }
        val executive = if (executiveDue) electExecutive(date) else null
        if (executiveDue) nextExecutive = date.plusYears(requireNotNull(rules.executive).termYears.toLong())
        val after = government()
        lastElection = ElectionResult(date, early = false, parties, executive, before, after, billLapsed = false)
        val kind = if (executiveDue) "${rules.executive?.title} 선거" else "총선"
        val text =
            if (before != after) {
                "$kind 결과 정권이 바뀌었다. 새 정부: $after"
            } else {
                "$kind 결과 ${after.josa("이", "가")} 정권을 지켰다."
            }
        if (before != after) {
            monthsInOffice = 0
            base = BASELINE
            approval = BASELINE + HONEYMOON
        }
        return listOf(WorldNews(date, countryId, "선거", text, major = before != after))
    }

    private fun government(): String =
        when {
            juntaSince != null -> "군사평의회 · ${leadership.current().leader.name}"
            else -> leadership.governmentLabel(profile.initialGovernment, rules.system == GovernmentSystem.SEMI_PRESIDENTIAL)
        }

    private fun electLegislature(date: LocalDate): List<PartyResult>? {
        val chamber = legislature ?: return null
        val rule = requireNotNull(rules.legislature)
        val shares = voteShares(chamber.groups, rule.electoralSystem)
        val seats = ElectionModel.allocateSeats(shares, rule.electoralSystem, rule.seats)
        val previousRoles = chamber.groups.associate { it.name to it.role }
        val reseated = chamber.withGroups(ElectionModel.reseat(chamber.groups, seats))
        val governed =
            when (rules.system) {
                GovernmentSystem.PARLIAMENTARY, GovernmentSystem.SEMI_PRESIDENTIAL -> {
                    reseated.withGroups(ElectionModel.formGovernment(reseated.groups, reseated.required, previousRoles))
                }

                GovernmentSystem.PRESIDENTIAL, GovernmentSystem.NON_COMPETITIVE -> {
                    reseated
                }
            }
        legislature = governed
        val leadBefore = previousRoles.entries.firstOrNull { it.value == PartyRole.LEAD }?.key
        val leadAfter = governed.groups.firstOrNull { it.role == PartyRole.LEAD }?.name
        if (leadAfter != null && rules.system != GovernmentSystem.PRESIDENTIAL) {
            leadership.onGovernmentFormed(leadAfter, date, reelected = leadAfter == leadBefore)
        }
        return chamber.groups.map { party ->
            PartyResult(party.name, party.role, shares.getValue(party.name), party.seats, seats.getValue(party.name))
        }
    }

    private fun electExecutive(date: LocalDate): ExecutiveResult? {
        val chamber = legislature ?: return null
        val rule = requireNotNull(rules.executive)
        val openSeat = leadership.termLimited(constitution.executiveTermLimit)
        val race =
            ElectionModel.executiveRace(
                chamber.groups,
                rule.title,
                profile.regime,
                approval,
                random.nextGaussian() * EXECUTIVE_NOISE - if (openSeat) OPEN_SEAT_PENALTY else 0.0,
            ) ?: return null
        leadership.onExecutiveElection(race.winner, constitution.executiveTermLimit, date)
        if (race.winner != race.incumbent && rules.system == GovernmentSystem.PRESIDENTIAL) {
            legislature = chamber.withGroups(ElectionModel.installPresident(chamber.groups, race.winner))
        }
        return race
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
            noise = { random.nextGaussian() * VOTE_NOISE },
        )

    fun snapshot(): CountryPoliticsSnapshot =
        CountryPoliticsSnapshot(
            countryId = countryId,
            regime = profile.regime,
            government = government(),
            leadership = leadership.current(),
            approval = approval,
            legislature = legislature,
            nextElection = listOfNotNull(nextLegislative, nextExecutive).minOrNull(),
            lastElection = lastElection,
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
        const val EXECUTIVE_NOISE = 0.04
        const val OPEN_SEAT_PENALTY = 0.02
    }
}

/** 플레이어 국가를 뺀 플레이 가능 국가의 정치. 국가 순서와 난수 순서가 고정돼 결정론을 지킨다. */
internal class WorldPolitics(
    countryIds: List<String>,
    people: PeopleRegistry,
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

    fun advanceMonth(
        indicators: Map<String, EconomyIndicators>,
        date: LocalDate,
    ): List<WorldNews> = countries.mapNotNull { it.advanceMonth(indicators.getValue(it.countryId), date) }

    fun holdElections(date: LocalDate): List<WorldNews> = countries.flatMap { it.holdElections(date) }

    fun approval(countryId: String): Double? = byId[countryId]?.approval

    fun country(countryId: String): AiCountryPolitics? = byId[countryId]

    fun snapshot(): Map<String, CountryPoliticsSnapshot> = countries.associate { it.countryId to it.snapshot() }
}

/** 국가 모듈과 정치 계산의 소식을 세계 소식으로 바꾼다. */
internal fun CountryNews.toWorld(
    date: LocalDate,
    countryId: String,
): WorldNews = WorldNews(date, countryId, source, text, major)
