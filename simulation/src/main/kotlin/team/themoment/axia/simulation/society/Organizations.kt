package team.themoment.axia.simulation.society

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.nation.PolicyItem
import team.themoment.axia.simulation.nation.PopulationGroup
import team.themoment.axia.simulation.nation.RegulationItem
import team.themoment.axia.simulation.nation.SpendingKind
import team.themoment.axia.simulation.nation.TaxKind
import team.themoment.axia.simulation.text.josa
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sign
import kotlin.random.Random

/**
 * 조직 종류. 기반은 성향([identity]) 또는 직업 집단([group])이고, 국가기관([state])은 정부 안의 권력 집단이다.
 *
 * @property weight 조직력·규모가 같을 때 정치적 무게(자금, 무력 등).
 * @property promise 정부가 약속할 수 있는 정책과 방향(+1: 낮추지 않음, -1: 올리지 않음).
 */
enum class OrganizationKind(
    val label: String,
    val identity: Identity?,
    val group: PopulationGroup?,
    val state: Boolean,
    val weight: Double,
    val promise: Pair<PolicyItem, Int>?,
) {
    LABOR_UNION("노동조합", Identity.UNIONISTS, null, false, 1.0, RegulationItem.LABOR to 1),
    BUSINESS("경제단체", Identity.FREE_MARKET, null, false, 3.0, TaxKind.CORPORATE to -1),
    ENVIRONMENTAL("환경단체", Identity.ENVIRONMENTALISTS, null, false, 0.8, RegulationItem.ENVIRONMENT to 1),
    RELIGIOUS("종교단체", Identity.RELIGIOUS, null, false, 1.0, RegulationItem.IMMIGRATION to -1),
    VETERANS("보훈·안보단체", Identity.PATRIOTS, null, false, 1.0, SpendingKind.DEFENSE to 1),
    CIVIL_LIBERTIES("시민자유단체", Identity.LIBERTARIANS, null, false, 0.8, RegulationItem.CIVIL_LIBERTIES to 1),
    EGALITARIAN("진보 시민운동", Identity.EGALITARIANS, null, false, 0.8, SpendingKind.SOCIAL to 1),
    YOUTH("청년단체", Identity.YOUTH, null, false, 0.6, SpendingKind.EDUCATION to 1),
    PARENTS("학부모단체", Identity.PARENTS, null, false, 0.6, SpendingKind.EDUCATION to 1),
    FARMERS("농민단체", null, PopulationGroup.FARMERS, false, 1.0, TaxKind.TARIFF to 1),
    MILITARY("군부", Identity.PATRIOTS, null, true, 4.0, SpendingKind.DEFENSE to 1),
    SECURITY("보안기관", Identity.PATRIOTS, null, true, 3.0, RegulationItem.POLICING to 1),
    PARTY_APPARATUS("당 관료", null, null, true, 4.0, null),
    ;

    /** 파업으로 생산을 멈출 수 있는지. */
    val strikes: Boolean get() = this == LABOR_UNION
}

enum class OrganizationOrigin(
    val label: String,
) {
    REAL("실존"),
    GENERATED("신생"),
    UNDERGROUND("지하"),
}

/** 정부가 조직에 할 수 있는 행위. 정부 연합에 넣고 빼는 조작은 없다. */
enum class GovernmentAction(
    val label: String,
    /** 정치 자본 소모. */
    val cost: Double,
) {
    DIALOGUE("대화", 8.0),
    PROMISE("요구 수용 약속", 5.0),
    SUBSIDY("보조금 지원", 0.0),
    CRITICIZE("공개 비판", 0.0),
    INVESTIGATE("수사·감시", 10.0),
    BAN("해산 명령", 25.0),
    CO_OPT("지도부 포섭", 20.0),
}

data class NewsItem(
    val date: LocalDate,
    val organization: String,
    val text: String,
    /** 상단 띠에 띄울 만큼 큰 소식인지. */
    val major: Boolean,
)

data class Promise(
    val item: PolicyItem,
    /** +1: 낮추지 않는다, -1: 올리지 않는다. */
    val direction: Int,
    val until: LocalDate,
)

data class OrganizationSnapshot(
    val id: Int,
    val name: String,
    val kind: OrganizationKind,
    val origin: OrganizationOrigin,
    val founded: LocalDate,
    /** 성인 대비 회원 비율. */
    val membership: Double,
    val capacity: Double,
    val radicalism: Double,
    /** 정부에 대한 태도, -100~100. */
    val stance: Double,
    val influence: Double,
    val subsidized: Boolean,
    val promise: Promise?,
    val dialogueReadyOn: LocalDate,
    val availableActions: Set<GovernmentAction>,
    val recent: List<NewsItem>,
)

data class CivilSocietySnapshot(
    val organizations: List<OrganizationSnapshot>,
    val politicalCapital: Double,
    /** 사회 불안이 지지율에서 빼는 점수. */
    val unrest: Double,
    /** 군부·보안기관의 쿠데타 위험, 0~1. 실제 쿠데타 사건은 사건사고 단계에서 다룬다. */
    val coupRisk: Double,
    val news: List<NewsItem>,
)

/** 법안 표결에 영향을 주는 조직의 입장 계산 재료. */
internal data class OrganizationStake(
    val name: String,
    val identity: Identity?,
    val group: PopulationGroup?,
    val influence: Double,
)

@Serializable
internal data class OrganizationSeed(
    val name: String,
    val kind: OrganizationKind,
    val membership: Double,
    val capacity: Double,
    val stance: Double,
)

/** 플레이 가능 국가의 시작 조직. */
class OrganizationData private constructor(
    private val countries: Map<String, List<OrganizationSeed>>,
) {
    internal fun country(id: String): List<OrganizationSeed> = countries[id] ?: error("No organizations for $id")

    @Serializable
    private data class OrganizationFile(
        val sourceNote: String,
        val countries: Map<String, List<OrganizationSeed>>,
    )

    companion object {
        private const val DEFAULT_DATA = "/world/organizations.json"

        fun loadDefault(): OrganizationData {
            val text =
                requireNotNull(OrganizationData::class.java.getResourceAsStream(DEFAULT_DATA)) { "Missing $DEFAULT_DATA" }
                    .use { it.readBytes().decodeToString() }
            val file = Json.decodeFromString<OrganizationFile>(text)
            file.countries.values.flatten().forEach { seed ->
                require(seed.membership in 0.0..1.0 && seed.capacity in 0.0..1.0 && seed.stance in -100.0..100.0) { seed.name }
            }
            return OrganizationData(file.countries)
        }
    }
}

/** 한 달 동안 조직이 보는 나라 형편. 만족도는 기준선 대비 편차(점)다. */
internal data class CivilSocietyInputs(
    val identityMood: Map<Identity, Double>,
    val groupMood: Map<PopulationGroup, Double>,
    val approval: Double,
    val civilLiberties: Double,
    val defenseChange: Double,
    val orderChange: Double,
    val policingChange: Double,
)

/** 조직의 행동이 이번 달 나라에 남긴 결과. */
internal data class CivilSocietyEffects(
    val crime: Double,
    val outputGapShock: Double,
)

/**
 * 조직의 태도·규모·과격성 변화와 행동(시위, 파업, 과격 행동), 출현과 소멸, 정치 자본과 정부 행위.
 * 난수는 시드 기반이며 정치·경제 난수와 분리돼 있다.
 */
internal class CivilSociety(
    seeds: List<OrganizationSeed>,
    private val baseShares: (OrganizationKind) -> Double,
    private val legislativeConstraints: Double,
    private val autocratic: Boolean,
    private val start: LocalDate,
    private val random: Random,
) {
    private val organizations = mutableListOf<Organization>()
    private val news = ArrayDeque<NewsItem>()
    private var nextId = 1
    var politicalCapital = START_CAPITAL
        private set
    var unrest = 0.0
        private set
    var coupRisk = 0.0
        private set

    /** 정부의 공개 비판·탄압이 성향 만족도에 남긴 일시적 변화(점). 매달 줄어든다. */
    val identityMood = Identity.entries.associateWith { 0.0 }.toMutableMap()

    init {
        seeds.forEach { seed ->
            organizations +=
                Organization(
                    id = nextId++,
                    name = seed.name,
                    kind = seed.kind,
                    origin = OrganizationOrigin.REAL,
                    founded = start,
                    membership = seed.membership,
                    capacity = seed.capacity,
                    normalCapacity = seed.capacity,
                    radicalism = INITIAL_RADICALISM,
                    stance = seed.stance,
                    alignment = seed.stance,
                    dialogueReadyOn = start,
                )
        }
    }

    fun advanceMonth(
        date: LocalDate,
        inputs: CivilSocietyInputs,
    ): CivilSocietyEffects {
        politicalCapital =
            (politicalCapital + ((inputs.approval - 25.0) / 10.0).coerceIn(0.0, 5.0) * (0.7 + 0.6 * (1.0 - legislativeConstraints)))
                .coerceAtMost(MAX_CAPITAL)
        identityMood.keys.forEach { identityMood[it] = identityMood.getValue(it) * MOOD_DECAY }
        unrest *= UNREST_DECAY
        var crime = 0.0
        var gapShock = 0.0
        var risk = 0.0
        organizations.toList().forEach { org ->
            org.promise?.let { if (date >= it.until) org.promise = null }
            val mood = mood(org.kind, inputs)
            org.alignment -= org.alignment / ALIGNMENT_DECAY_MONTHS
            org.relations *= RELATIONS_DECAY
            val target =
                (
                    STANCE_PER_POINT * mood + org.alignment + org.relations + (if (org.promise != null) PROMISE_BONUS else 0.0) +
                        (if (org.subsidized) SUBSIDY_BONUS else 0.0)
                ).coerceIn(-100.0, 100.0)
            org.stance += (target - org.stance) * STANCE_SMOOTHING
            org.radicalism =
                if (org.stance < RADICAL_STANCE) {
                    org.radicalism + RADICALIZATION * (1.0 + 1.5 * (1.0 - inputs.civilLiberties))
                } else {
                    org.radicalism - DERADICALIZATION
                }.coerceIn(0.0, 1.0)
            val capacityGoal = (org.normalCapacity + if (org.subsidized) SUBSIDY_CAPACITY else 0.0).coerceAtMost(1.0)
            org.capacity += (capacityGoal - org.capacity) / CAPACITY_RECOVERY_MONTHS
            if (!org.kind.state) {
                val grievance = (-mood / 30.0).coerceIn(-1.0, 1.0)
                org.membership =
                    (org.membership * (1.0 + MEMBERSHIP_GROWTH * grievance * org.capacity))
                        .coerceAtLeast(MIN_MEMBERSHIP)
                        .coerceAtMost(baseShares(org.kind) * MAX_REACH)
                act(org, date, inputs.civilLiberties)?.let { effect ->
                    crime += effect.crime
                    gapShock += effect.outputGapShock
                }
                org.lowMonths = if (org.membership < DISBAND_MEMBERSHIP) org.lowMonths + 1 else 0
                if (org.lowMonths >= DISBAND_MONTHS) {
                    organizations -= org
                    report(date, org.name, "${org.name.josa("이", "가")} 회원을 잃고 해체됐다.", major = false)
                }
            } else if (org.kind == OrganizationKind.MILITARY || org.kind == OrganizationKind.SECURITY) {
                risk = maxOf(risk, (-org.stance / 100.0).coerceIn(0.0, 1.0) * org.capacity * (1.0 - legislativeConstraints))
            }
        }
        coupRisk = risk
        emerge(date, inputs)
        return CivilSocietyEffects(crime, gapShock)
    }

    /** 조직이 보는 형편(기준선 대비 점수). */
    private fun mood(
        kind: OrganizationKind,
        inputs: CivilSocietyInputs,
    ): Double =
        when (kind) {
            OrganizationKind.MILITARY -> {
                0.5 * inputs.identityMood.getValue(Identity.PATRIOTS) + 600.0 * inputs.defenseChange + 0.3 * (inputs.approval - 50.0)
            }

            OrganizationKind.SECURITY -> {
                30.0 * inputs.policingChange + 30.0 * inputs.orderChange + 0.3 * (inputs.approval - 50.0)
            }

            OrganizationKind.PARTY_APPARATUS -> {
                0.6 * (inputs.approval - 50.0)
            }

            else -> {
                kind.identity?.let(inputs.identityMood::getValue) ?: inputs.groupMood.getValue(requireNotNull(kind.group))
            }
        }

    private fun act(
        org: Organization,
        date: LocalDate,
        civilLiberties: Double,
    ): CivilSocietyEffects? {
        val influence = org.influence
        if (org.kind.strikes && org.stance < STRIKE_STANCE) {
            val chance = STRIKE_CHANCE * org.capacity * ((-org.stance + STRIKE_STANCE) / (100.0 + STRIKE_STANCE))
            if (random.nextDouble() < chance) {
                unrest += influence * STRIKE_UNREST
                report(date, org.name, "${org.name.josa("이", "가")} 파업에 들어갔다.", major = true)
                return CivilSocietyEffects(0.0, -STRIKE_GAP * (org.membership / 0.05).coerceAtMost(1.0))
            }
        }
        if (org.radicalism > RADICAL_THRESHOLD && random.nextDouble() < (org.radicalism - RADICAL_THRESHOLD) * org.capacity) {
            unrest += VIOLENCE_UNREST
            report(date, org.name, "${org.name} 일부 세력이 과격 행동을 벌였다.", major = true)
            return CivilSocietyEffects(VIOLENCE_CRIME, 0.0)
        }
        if (org.stance < PROTEST_STANCE) {
            val chance = org.capacity * ((-org.stance + PROTEST_STANCE) / (100.0 + PROTEST_STANCE)) * (0.25 + 0.75 * civilLiberties) * 0.5
            if (random.nextDouble() < chance) {
                unrest += influence * PROTEST_UNREST
                report(date, org.name, "${org.name.josa("이", "가")} 정부 규탄 집회를 열었다.", major = false)
            }
        }
        return null
    }

    /** 불만은 큰데 대표하는 조직이 약한 기반에서 새 조직이 생긴다. */
    private fun emerge(
        date: LocalDate,
        inputs: CivilSocietyInputs,
    ) {
        EMERGING_KINDS.forEach { kind ->
            val mood = mood(kind, inputs)
            if (mood >= EMERGENCE_MOOD) return@forEach
            val existing = organizations.filter { it.kind == kind }
            val share = baseShares(kind)
            if (share <= 0.0 || existing.size >= MAX_PER_KIND) return@forEach
            val representation = existing.sumOf { it.membership } / share
            if (representation >= EMERGENCE_REPRESENTATION) return@forEach
            if (random.nextDouble() < EMERGENCE_CHANCE * (-mood / 30.0)) {
                val org = found(kind, OrganizationOrigin.GENERATED, date, share * 0.02, NEW_RADICALISM, NEW_STANCE)
                report(date, org.name, "새 ${kind.label} ${org.name.josa("이", "가")} 결성됐다.", major = true)
            }
        }
    }

    private fun found(
        kind: OrganizationKind,
        origin: OrganizationOrigin,
        date: LocalDate,
        membership: Double,
        radicalism: Double,
        stance: Double,
    ): Organization {
        val org =
            Organization(
                id = nextId++,
                name = NameGenerator.organization(kind, origin, random, organizations.map { it.name }.toSet()),
                kind = kind,
                origin = origin,
                founded = date,
                membership = membership.coerceAtLeast(MIN_MEMBERSHIP),
                capacity = NEW_CAPACITY,
                normalCapacity = NEW_CAPACITY,
                radicalism = radicalism,
                stance = stance,
                alignment = 0.0,
                dialogueReadyOn = date,
            )
        organizations += org
        return org
    }

    fun available(
        org: Organization,
        date: LocalDate,
    ): Set<GovernmentAction> =
        GovernmentAction.entries
            .filter { action ->
                politicalCapital >= action.cost &&
                    when (action) {
                        GovernmentAction.DIALOGUE -> date >= org.dialogueReadyOn
                        GovernmentAction.PROMISE -> org.promise == null && org.kind.promise != null
                        GovernmentAction.SUBSIDY -> !org.kind.state
                        GovernmentAction.CRITICIZE -> true
                        GovernmentAction.INVESTIGATE -> !org.kind.state
                        GovernmentAction.BAN -> !org.kind.state && (autocratic || legislativeConstraints < BAN_CONSTRAINT)
                        GovernmentAction.CO_OPT -> autocratic
                    }
            }.toSet()

    /** 사건으로 정치 자본이 늘거나 준다. */
    fun adjustCapital(delta: Double) {
        politicalCapital = (politicalCapital + delta).coerceIn(0.0, MAX_CAPITAL)
    }

    /** 외교·군사 행위에 정치 자본을 쓴다. 모자라면 false다. */
    fun spendCapital(cost: Double): Boolean {
        if (politicalCapital < cost) return false
        politicalCapital -= cost
        return true
    }

    /** 정부 행위. 조건이 맞지 않으면 아무 일도 하지 않고 false를 돌려준다. */
    fun perform(
        id: Int,
        action: GovernmentAction,
        date: LocalDate,
    ): Boolean {
        val org = organizations.firstOrNull { it.id == id } ?: return false
        if (action !in available(org, date)) return false
        politicalCapital -= action.cost
        val base = org.kind.identity
        when (action) {
            GovernmentAction.DIALOGUE -> {
                org.relations += 20.0
                org.radicalism = (org.radicalism - 0.05).coerceAtLeast(0.0)
                org.dialogueReadyOn = date.plusMonths(DIALOGUE_COOLDOWN_MONTHS)
                report(date, org.name, "정부가 ${org.name.josa("과", "와")} 대화했다.", major = false)
            }

            GovernmentAction.PROMISE -> {
                val (item, direction) = requireNotNull(org.kind.promise)
                org.promise = Promise(item, direction, date.plusYears(PROMISE_YEARS))
                report(
                    date,
                    org.name,
                    "정부가 ${org.name}에 ${item.label.josa("을", "를")} ${if (direction > 0) "낮추지" else "올리지"} 않겠다고 약속했다.",
                    major = false,
                )
            }

            GovernmentAction.SUBSIDY -> {
                org.subsidized = !org.subsidized
                report(date, org.name, if (org.subsidized) "정부가 ${org.name}에 보조금을 지원하기 시작했다." else "${org.name} 보조금이 끊겼다.", major = false)
            }

            GovernmentAction.CRITICIZE -> {
                org.relations -= 25.0
                base?.let { shiftMood(it, -CRITICISM_MOOD) }
                base?.let { OPPOSITES[it] }?.let { shiftMood(it, CRITICISM_MOOD) }
                report(date, org.name, "정부가 ${org.name.josa("을", "를")} 공개 비판했다.", major = false)
            }

            GovernmentAction.INVESTIGATE -> {
                org.capacity = (org.capacity - 0.15).coerceAtLeast(0.05)
                org.radicalism = (org.radicalism + 0.1).coerceAtMost(1.0)
                org.relations -= 30.0
                shiftMood(Identity.LIBERTARIANS, -INVESTIGATION_MOOD * (0.3 + legislativeConstraints))
                report(date, org.name, "수사 당국이 ${org.name.josa("을", "를")} 수사하기 시작했다.", major = true)
            }

            GovernmentAction.BAN -> {
                organizations -= org
                shiftMood(Identity.LIBERTARIANS, -BAN_MOOD)
                base?.let { shiftMood(it, -BAN_MOOD * 0.8) }
                report(date, org.name, "정부가 ${org.name}에 해산 명령을 내렸다.", major = true)
                if (random.nextDouble() < org.radicalism + UNDERGROUND_CHANCE) {
                    val underground =
                        found(org.kind, OrganizationOrigin.UNDERGROUND, date, org.membership * 0.2, UNDERGROUND_RADICALISM, -70.0)
                    report(
                        date,
                        underground.name,
                        "해산된 ${org.name}의 강경파가 ${underground.name.josa("으로", "로")} 지하에서 활동을 이어 간다.",
                        major = true,
                    )
                }
            }

            GovernmentAction.CO_OPT -> {
                org.relations += 40.0
                org.radicalism = (org.radicalism - 0.2).coerceAtLeast(0.0)
                report(date, org.name, "정부가 ${org.name} 지도부를 포섭했다.", major = false)
            }
        }
        return true
    }

    fun rename(
        id: Int,
        name: String,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) return
        organizations.firstOrNull { it.id == id }?.name = trimmed
    }

    /** 통과한 법안이 약속을 어겼는지 본다. 어기면 그 조직의 태도가 급락하고 모든 조직의 신뢰가 떨어진다. */
    fun onPolicyEnacted(
        changes: List<Pair<PolicyItem, Double>>,
        date: LocalDate,
    ) {
        val broken =
            organizations.filter { org ->
                val promise = org.promise ?: return@filter false
                changes.any { (item, delta) -> item == promise.item && abs(delta) > 1e-9 && delta.sign.toInt() == -promise.direction }
            }
        if (broken.isEmpty()) return
        broken.forEach { org ->
            org.promise = null
            org.relations -= BROKEN_PROMISE
            report(date, org.name, "정부가 ${org.name.josa("과", "와")}의 약속을 깼다.", major = true)
        }
        organizations.forEach { it.relations -= BROKEN_PROMISE_TRUST }
    }

    /** 보조금 지원 중인 조직 수 × 조직당 비용(GDP 대비 연간). */
    val subsidySpending: Double get() = organizations.count { it.subsidized } * SUBSIDY_COST

    fun stakes(): List<OrganizationStake> =
        organizations
            .filter { it.kind != OrganizationKind.PARTY_APPARATUS }
            .map { OrganizationStake(it.name, it.kind.identity, it.kind.group, it.influence) }

    fun snapshot(date: LocalDate): CivilSocietySnapshot =
        CivilSocietySnapshot(
            organizations =
                organizations.map { org ->
                    OrganizationSnapshot(
                        id = org.id,
                        name = org.name,
                        kind = org.kind,
                        origin = org.origin,
                        founded = org.founded,
                        membership = org.membership,
                        capacity = org.capacity,
                        radicalism = org.radicalism,
                        stance = org.stance,
                        influence = org.influence,
                        subsidized = org.subsidized,
                        promise = org.promise,
                        dialogueReadyOn = org.dialogueReadyOn,
                        availableActions = available(org, date),
                        recent = news.filter { it.organization == org.name }.take(RECENT_PER_ORGANIZATION),
                    )
                },
            politicalCapital = politicalCapital,
            unrest = unrest.coerceAtMost(MAX_UNREST),
            coupRisk = coupRisk,
            news = news.toList(),
        )

    fun shiftMood(
        identity: Identity,
        points: Double,
    ) {
        identityMood[identity] = identityMood.getValue(identity) + points
    }

    fun report(
        date: LocalDate,
        organization: String,
        text: String,
        major: Boolean,
    ) {
        news.addFirst(NewsItem(date, organization, text, major))
        while (news.size > MAX_NEWS) news.removeLast()
    }

    class Organization(
        val id: Int,
        var name: String,
        val kind: OrganizationKind,
        val origin: OrganizationOrigin,
        val founded: LocalDate,
        var membership: Double,
        var capacity: Double,
        val normalCapacity: Double,
        var radicalism: Double,
        var stance: Double,
        /** 시작 시점 정부와의 이념적 거리. 천천히 사라진다. */
        var alignment: Double,
        var dialogueReadyOn: LocalDate,
    ) {
        /** 대화·비판 같은 정부 행위가 남긴 관계. 매달 줄어든다. */
        var relations = 0.0
        var subsidized = false
        var promise: Promise? = null
        var lowMonths = 0

        val influence: Double
            get() = kind.weight * capacity * (0.3 + 0.7 * (membership / INFLUENTIAL_MEMBERSHIP).coerceAtMost(1.0))
    }

    private companion object {
        const val START_CAPITAL = 30.0
        const val MAX_CAPITAL = 100.0
        const val INITIAL_RADICALISM = 0.05
        const val STANCE_PER_POINT = 1.5
        const val STANCE_SMOOTHING = 0.2
        const val ALIGNMENT_DECAY_MONTHS = 120.0
        const val RELATIONS_DECAY = 0.95
        const val PROMISE_BONUS = 30.0
        const val SUBSIDY_BONUS = 15.0
        const val SUBSIDY_CAPACITY = 0.2
        const val SUBSIDY_COST = 0.0003
        const val CAPACITY_RECOVERY_MONTHS = 24.0
        const val RADICAL_STANCE = -40.0
        const val RADICALIZATION = 0.01
        const val DERADICALIZATION = 0.006
        const val MEMBERSHIP_GROWTH = 0.01
        const val MIN_MEMBERSHIP = 0.0001
        const val MAX_REACH = 0.9
        const val DISBAND_MEMBERSHIP = 0.0003
        const val DISBAND_MONTHS = 12
        const val INFLUENTIAL_MEMBERSHIP = 0.02
        const val PROTEST_STANCE = -30.0
        const val PROTEST_UNREST = 3.0
        const val STRIKE_STANCE = -50.0
        const val STRIKE_UNREST = 2.0
        const val STRIKE_GAP = 0.004
        const val STRIKE_CHANCE = 0.2
        const val RADICAL_THRESHOLD = 0.6
        const val VIOLENCE_UNREST = 2.0
        const val VIOLENCE_CRIME = 2.0
        const val UNREST_DECAY = 0.6
        const val MAX_UNREST = 15.0
        const val EMERGENCE_MOOD = -12.0
        const val EMERGENCE_REPRESENTATION = 0.1
        const val EMERGENCE_CHANCE = 0.03
        const val MAX_PER_KIND = 3
        const val NEW_CAPACITY = 0.3
        const val NEW_RADICALISM = 0.15
        const val NEW_STANCE = -30.0
        const val UNDERGROUND_CHANCE = 0.3
        const val UNDERGROUND_RADICALISM = 0.6
        const val DIALOGUE_COOLDOWN_MONTHS = 6L
        const val PROMISE_YEARS = 2L
        const val BROKEN_PROMISE = 60.0
        const val BROKEN_PROMISE_TRUST = 10.0
        const val CRITICISM_MOOD = 4.0
        const val INVESTIGATION_MOOD = 3.0
        const val BAN_MOOD = 10.0
        const val BAN_CONSTRAINT = 0.5
        const val MOOD_DECAY = 0.9
        const val MAX_NEWS = 60
        const val RECENT_PER_ORGANIZATION = 3
        const val MAX_NAME_LENGTH = 40

        val EMERGING_KINDS =
            OrganizationKind.entries.filter { !it.state && it != OrganizationKind.BUSINESS }

        /** 공개 비판하면 반대로 만족하는 성향. */
        val OPPOSITES =
            mapOf(
                Identity.ENVIRONMENTALISTS to Identity.FREE_MARKET,
                Identity.FREE_MARKET to Identity.EGALITARIANS,
                Identity.EGALITARIANS to Identity.FREE_MARKET,
                Identity.UNIONISTS to Identity.FREE_MARKET,
                Identity.PATRIOTS to Identity.LIBERTARIANS,
                Identity.LIBERTARIANS to Identity.PATRIOTS,
                Identity.RELIGIOUS to Identity.LIBERTARIANS,
            )
    }
}

/** 시간이 지나며 생기는 조직의 이름. 플레이어가 바꿀 수 있다. */
internal object NameGenerator {
    private val TEMPLATES =
        mapOf(
            OrganizationKind.LABOR_UNION to listOf("전국%s노동조합연맹" to listOf("금속", "공공", "서비스", "운수", "보건의료", "건설", "플랫폼")),
            OrganizationKind.ENVIRONMENTAL to listOf("%s을 위한 시민행동" to listOf("기후", "숲", "맑은 공기", "푸른 강", "미래 세대")),
            OrganizationKind.RELIGIOUS to listOf("%s 신앙인 연합" to listOf("전통", "생명", "가정", "믿음")),
            OrganizationKind.VETERANS to listOf("%s안보연대" to listOf("자유", "조국", "호국", "국민")),
            OrganizationKind.CIVIL_LIBERTIES to listOf("%s시민연대" to listOf("열린", "자유", "투명사회", "표현의 자유")),
            OrganizationKind.EGALITARIAN to listOf("%s을 위한 연대" to listOf("평등", "공정", "기본소득", "모두의 복지")),
            OrganizationKind.YOUTH to listOf("%s청년행동" to listOf("미래", "2030", "내일", "새세대")),
            OrganizationKind.PARENTS to listOf("%s학부모연대" to listOf("참", "우리 아이", "바른 교육", "함께")),
            OrganizationKind.FARMERS to listOf("%s농민회" to listOf("전국", "가족농", "쌀생산자", "새")),
            OrganizationKind.BUSINESS to listOf("%s기업인협의회" to listOf("중소", "혁신", "수출", "벤처")),
        )
    private val UNDERGROUND = listOf("저항전선", "비밀연대", "해방동맹", "결사대")

    fun organization(
        kind: OrganizationKind,
        origin: OrganizationOrigin,
        random: Random,
        taken: Set<String>,
    ): String {
        val (template, words) = (TEMPLATES[kind] ?: listOf("%s 모임" to listOf(kind.label))).random(random)
        val base =
            if (origin == OrganizationOrigin.UNDERGROUND) {
                "${words.random(random)} ${UNDERGROUND.random(random)}"
            } else {
                template.format(words.random(random))
            }
        if (base !in taken) return base
        return generateSequence(2) { it + 1 }.map { "$base $it" }.first { it !in taken }
    }
}
