package team.themoment.axia.simulation.diplomacy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.politics.WorldNews
import team.themoment.axia.simulation.world.Geography
import java.time.LocalDate
import kotlin.random.Random

enum class OrganizationType(
    val label: String,
) {
    POLITICAL("정치"),
    ALLIANCE("군사동맹"),
    TRADE("무역·경제통합"),
    ECONOMIC("경제"),
    FINANCIAL("금융"),
}

@Serializable
data class InternationalOrganization(
    val id: String,
    val name: String,
    val kind: OrganizationType,
    val members: List<String>,
)

@Serializable
internal data class AllianceRecord(
    val name: String,
    val members: List<String>,
)

@Serializable
internal data class SanctionRecord(
    val by: String,
    val target: String,
    val intensity: Double,
)

@Serializable
internal data class RelationRecord(
    val a: String,
    val b: String,
    val value: Double,
)

@Serializable
internal data class DiplomacyFile(
    val sourceNote: String,
    val organizations: List<InternationalOrganization>,
    val alliances: List<AllianceRecord>,
    val sanctions: List<SanctionRecord>,
    val relations: List<RelationRecord>,
) {
    companion object {
        val default: DiplomacyFile by lazy {
            val text =
                requireNotNull(DiplomacyFile::class.java.getResourceAsStream("/world/diplomacy.json")) { "Missing diplomacy.json" }
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            Json.decodeFromString<DiplomacyFile>(text)
        }
    }
}

/** 플레이어의 외교 행위. 비용은 정치 자본이다. */
enum class DiplomaticAction(
    val label: String,
    val cost: Double,
    val description: String,
) {
    SUMMIT("정상회담", 6.0, "관계가 서서히 오른다. 같은 나라와는 6개월에 한 번."),
    AID("경제 원조", 2.0, "자국 GDP의 0.1%를 12개월에 나눠 상대국 정부에 지급한다. 지급할 때마다 관계가 개선된다."),
    CONDEMN("공개 비난", 0.0, "관계가 떨어지고, 국내 안보 성향은 약간 만족한다."),
    SANCTION("제재 부과", 8.0, "교역이 끊기고 관계가 크게 떨어진다. 상대가 보복 제재를 할 수 있다."),
    LIFT_SANCTION("제재 해제", 4.0, "우리가 건 제재를 푼다."),
    TRADE_AGREEMENT("무역협정 제안", 10.0, "관계가 25 이상이면 상대가 받아들인다. 교역이 늘어난다."),
    ALLIANCE("방위조약 제안", 15.0, "관계가 55 이상이고 공동의 적이 있거나 체제가 비슷하면 받아들인다. 전쟁에 함께 휘말린다."),
    TRANSIT_TREATY("상호 통행권", 6.0, "상호 군사 통행권을 맺는다. 관계가 25 이상이어야 하며 상대국과 전쟁이 시작되면 만료된다."),
    END_TRANSIT("통행권 종료", 2.0, "상호 군사 통행권을 종료한다."),
    EXPEL_DIPLOMATS("외교관 추방", 3.0, "관계가 크게 떨어진다."),
}

/** 플레이어와 한 나라의 외교 현황. */
data class BilateralSnapshot(
    val countryId: String,
    val relation: Double,
    val notable: Boolean,
    val allied: Boolean,
    val alliance: String?,
    val tradeAgreement: Boolean,
    val transit: Boolean,
    /** 우리가 건 제재 강도. */
    val sanctionedByUs: Double,
    /** 상대가 우리에게 건 제재 강도. */
    val sanctioningUs: Double,
    val atWar: Boolean,
    val aidMonthsLeft: Int,
    val nextSummitDate: LocalDate?,
    val available: Set<DiplomaticAction>,
)

data class MembershipDecision(
    val organization: InternationalOrganization,
    val member: Boolean,
    val votes: Int,
    val required: Int,
    val atWarWithMember: Boolean,
) {
    val canJoin: Boolean get() = !member && !atWarWithMember && votes >= required
}

enum class DiplomaticOutcome { COMPLETED, DECLINED, UNAVAILABLE, INSUFFICIENT_CAPITAL }

data class DiplomaticDispatch(
    val date: LocalDate,
    val countryId: String,
    val action: DiplomaticAction,
    val outcome: DiplomaticOutcome,
)

enum class MembershipOutcome { COMPLETED, UNCHANGED, AT_WAR, INSUFFICIENT_VOTES, UNKNOWN_ORGANIZATION }

data class MembershipDispatch(
    val date: LocalDate,
    val organizationId: String,
    val join: Boolean,
    val outcome: MembershipOutcome,
)

data class DiplomacySnapshot(
    val playerId: String,
    val organizations: List<InternationalOrganization>,
    val membershipDecisions: List<MembershipDecision>,
    /** 플레이어를 제외한 모든 외교 상대. 주요 상대는 [BilateralSnapshot.notable]로 구분한다. */
    val partners: List<BilateralSnapshot>,
    val relations: Map<String, Double>,
    val lastDispatch: DiplomaticDispatch?,
    val lastMembershipDispatch: MembershipDispatch?,
)

/**
 * 국가 간 관계(-100~100), 동맹, 무역협정, 제재.
 *
 * 관계는 구조(동맹, 공동 기구, 무역협정, 제재, 전쟁, 체제 유사성)가 정하는 "자연 관계"로 매달 천천히 움직인다.
 * 시작 관계는 데이터의 양국 보정값이 있으면 그 값이고, 이후 구조가 바뀌면 그만큼 자연 관계가 바뀐다.
 * 외교 행위는 서서히 사라지는 보정값을 더한다.
 */
internal class Diplomacy(
    states: List<String>,
    private val playerId: String,
    private val democracy: (String) -> Boolean?,
    /** [a]의 수출 중 [b]로 가는 시작 시점 비중. 경제적 연결이 깊을수록 관계가 좋다. */
    private val tradeShare: (String, String) -> Double,
    private val random: Random,
    private val data: DiplomacyFile = DiplomacyFile.default,
) {
    private val ids = states.sorted()
    private val index = ids.withIndex().associate { it.value to it.index }
    private val n = ids.size
    private val alliances = HashMap<Pair<String, String>, String>()
    private val tradePairs = HashSet<Pair<String, String>>()
    private val sanctions = HashMap<Pair<String, String>, Double>()
    val initialSanctions: Map<Pair<String, String>, Double>
    private val wars = HashSet<Pair<String, String>>()
    private val relation = DoubleArray(n * n)
    private val offset = DoubleArray(n * n)
    private val startStructure = DoubleArray(n * n)
    private val startValue = DoubleArray(n * n)
    private val summitReady = HashMap<String, LocalDate>()
    private val aidMonthsLeft = mutableMapOf<String, Int>()
    private val news = mutableListOf<WorldNews>()
    private val organizations = data.organizations.associateBy { it.id }.toMutableMap()
    private var lastMembershipDispatch: MembershipDispatch? = null
    private var membership = emptyMap<String, List<InternationalOrganization>>()
    private val organizationAlliances = HashMap<Pair<String, String>, String>()
    private val organizationTrade = HashSet<Pair<String, String>>()
    private val transitTreaties = HashSet<Pair<String, String>>()

    init {
        data.organizations.forEach { org -> org.members.forEach { require(it in index) { "${org.id} has unknown member $it" } } }
        indexOrganizations()
        data.alliances.forEach { record ->
            record.members.forEach { a -> record.members.filter { it != a }.forEach { b -> alliances[pair(a, b)] = record.name } }
        }
        data.sanctions.forEach {
            require(it.intensity in 0.0..1.0)
            sanctions[it.by to it.target] = it.intensity
        }
        initialSanctions = sanctions.toMap()
        val overrides = HashMap<Pair<String, String>, Double>()
        data.relations.forEach { require(overrides.put(pair(it.a, it.b), it.value) == null) { "Duplicate relation ${it.a}-${it.b}" } }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val a = ids[i]
                val b = ids[j]
                val structure = structural(a, b)
                val value = overrides[pair(a, b)] ?: structure
                set(startStructure, i, j, structure)
                set(startValue, i, j, value)
                set(relation, i, j, value)
            }
        }
    }

    private fun pair(
        a: String,
        b: String,
    ) = if (a < b) a to b else b to a

    private fun set(
        array: DoubleArray,
        i: Int,
        j: Int,
        value: Double,
    ) {
        array[i * n + j] = value
        array[j * n + i] = value
    }

    private fun indexOrganizations() {
        membership = ids.associateWith { id -> organizations.values.filter { id in it.members } }
        organizationAlliances.clear()
        organizationTrade.clear()
        organizations.values.filter { it.kind == OrganizationType.ALLIANCE || it.kind == OrganizationType.TRADE }.forEach { org ->
            org.members.forEach { a ->
                org.members.filter { it > a }.forEach { b ->
                    if (org.kind == OrganizationType.ALLIANCE) organizationAlliances[a to b] = org.name else organizationTrade += a to b
                }
            }
        }
    }

    // 가입 심사는 회원국과의 관계를 이용한 게임 규칙이다. 군사동맹은 만장일치, 다른 기구는 2/3 찬성을 요구한다.
    private fun membershipDecision(org: InternationalOrganization): MembershipDecision {
        val members = org.members.filter { it != playerId }
        val militaryAlliance = org.kind == OrganizationType.ALLIANCE
        val threshold =
            when {
                militaryAlliance -> 25.0
                org.id == "UN" || org.kind == OrganizationType.FINANCIAL -> -10.0
                else -> 5.0
            }
        return MembershipDecision(
            organization = org,
            member = playerId in org.members,
            votes = members.count { relation(playerId, it) >= threshold },
            required = if (militaryAlliance) members.size else (2 * members.size + 2) / 3,
            atWarWithMember = members.any { pair(playerId, it) in wars },
        )
    }

    fun changeMembership(
        id: String,
        join: Boolean,
        date: LocalDate,
    ): Boolean {
        val decision = organizations[id]?.let(::membershipDecision)
        val outcome =
            when {
                decision == null -> MembershipOutcome.UNKNOWN_ORGANIZATION
                join == decision.member -> MembershipOutcome.UNCHANGED
                join && decision.atWarWithMember -> MembershipOutcome.AT_WAR
                join && !decision.canJoin -> MembershipOutcome.INSUFFICIENT_VOTES
                else -> MembershipOutcome.COMPLETED
            }
        lastMembershipDispatch = MembershipDispatch(date, id, join, outcome)
        if (decision == null || outcome != MembershipOutcome.COMPLETED) return false
        val org = decision.organization
        val members = if (join) (org.members + playerId).sorted() else org.members - playerId
        organizations[id] = org.copy(members = members)
        indexOrganizations()
        val action = if (join) "가입" else "탈퇴"
        report(date, playerId, org.name, "${org.name} $action 결정이 발효됐다.", major = true)
        return true
    }

    fun hasTransitAccess(
        a: String,
        b: String,
    ): Boolean = pair(a, b) in transitTreaties

    fun relation(
        a: String,
        b: String,
    ): Double = if (a == b) 100.0 else relation[index.getValue(a) * n + index.getValue(b)]

    fun allied(
        a: String,
        b: String,
    ): Boolean = pair(a, b).let { it in alliances || it in organizationAlliances }

    fun allies(id: String): List<String> = ids.filter { it != id && allied(id, it) }

    fun tradeAgreement(
        a: String,
        b: String,
    ): Boolean = pair(a, b).let { it in tradePairs || it in organizationTrade }

    /** [by]가 [target]에 건 제재 강도. */
    fun sanction(
        by: String,
        target: String,
    ): Double = sanctions[by to target] ?: 0.0

    fun sanctionsSnapshot(): Map<Pair<String, String>, Double> = sanctions.toMap()

    fun syncWars(hostilePairs: List<Pair<String, String>>) {
        wars.clear()
        hostilePairs.forEach { (a, b) -> setWar(a, b, active = true) }
    }

    fun setWar(
        a: String,
        b: String,
        active: Boolean,
    ) {
        val key = pair(a, b)
        if (active) {
            wars += key
            transitTreaties.remove(key)
            if (a == playerId) aidMonthsLeft.remove(b)
            if (b == playerId) aidMonthsLeft.remove(a)
        } else {
            wars -= key
        }
    }

    fun isMember(
        country: String,
        organization: String,
    ): Boolean = membership[country].orEmpty().any { it.id == organization }

    fun organizationsOf(id: String): List<InternationalOrganization> = membership[id].orEmpty()

    /** 구조가 정하는 관계 몫. */
    private fun structural(
        a: String,
        b: String,
    ): Double {
        var value = 0.0
        if (allied(a, b)) value += ALLIANCE_BONUS
        val shared =
            membership.getValue(a).count { org ->
                org.id != "UN" && org.kind != OrganizationType.ALLIANCE && org.kind != OrganizationType.FINANCIAL && b in org.members
            }
        value += (shared * SHARED_ORG_BONUS).coerceAtMost(MAX_SHARED_ORG_BONUS)
        if (tradeAgreement(a, b)) value += TRADE_BONUS
        if (hasTransitAccess(a, b)) value += 6.0
        value += (TRADE_TIES * (tradeShare(a, b) + tradeShare(b, a))).coerceAtMost(MAX_TRADE_TIES)
        value -= SANCTION_PENALTY * maxOf(sanction(a, b), sanction(b, a))
        if (pair(a, b) in wars) value -= WAR_PENALTY
        val da = democracy(a)
        val db = democracy(b)
        if (da != null && db != null) value += if (da == db) (if (da) DEMOCRACY_BONUS else AUTOCRACY_BONUS) else -REGIME_GAP
        if (Geography.default.neighbors(a, b)) value -= NEIGHBOR_FRICTION
        return value
    }

    private fun natural(
        i: Int,
        j: Int,
    ): Double = startValue[i * n + j] + structural(ids[i], ids[j]) - startStructure[i * n + j]

    fun advanceMonth() {
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val k = i * n + j
                offset[k] *= OFFSET_DECAY
                val target = (natural(i, j) + offset[k]).coerceIn(-100.0, 100.0)
                set(relation, i, j, relation[k] + (target - relation[k]) * RELATION_SMOOTHING)
                set(offset, i, j, offset[k])
            }
        }
        val grants = aidMonthsLeft.entries.iterator()
        for (grant in grants) {
            shift(playerId, grant.key, AID_MONTHLY)
            if (grant.value == 1) grants.remove() else grant.setValue(grant.value - 1)
        }
    }

    private fun shift(
        a: String,
        b: String,
        points: Double,
    ) {
        val i = index.getValue(a)
        val j = index.getValue(b)
        set(offset, i, j, offset[i * n + j] + points)
    }

    /** 약정 한 건당 공여국 월초 달러 GDP의 연 0.1%를 열두 달에 걸쳐 결제한다. */
    fun monthlyGrants(donorGdp: Double): Map<String, Double> = aidMonthsLeft.mapValues { donorGdp * AID_SHARE / 12 }

    fun available(
        target: String,
        date: LocalDate,
    ): Set<DiplomaticAction> {
        if (target == playerId || target !in index) return emptySet()
        val atWar = pair(playerId, target) in wars
        return DiplomaticAction.entries.filterTo(mutableSetOf()) { action ->
            when (action) {
                DiplomaticAction.SUMMIT -> !atWar && (summitReady[target]?.let { date >= it } ?: true)
                DiplomaticAction.AID -> !atWar && target !in aidMonthsLeft
                DiplomaticAction.CONDEMN, DiplomaticAction.EXPEL_DIPLOMATS -> true
                DiplomaticAction.SANCTION -> sanction(playerId, target) < 1.0
                DiplomaticAction.LIFT_SANCTION -> sanction(playerId, target) > 0.0
                DiplomaticAction.TRADE_AGREEMENT -> !atWar && !tradeAgreement(playerId, target)
                DiplomaticAction.ALLIANCE -> !atWar && !allied(playerId, target)
                DiplomaticAction.TRANSIT_TREATY -> !atWar && !hasTransitAccess(playerId, target)
                DiplomaticAction.END_TRANSIT -> hasTransitAccess(playerId, target)
            }
        }
    }

    /**
     * 플레이어 행위. 정치 자본은 호출자가 먼저 확인한다. 제안이 거절되면 false를 돌려주고 비용은 쓰지 않는다.
     * @return 실행했는지(거절·불가면 false)
     */
    fun perform(
        target: String,
        action: DiplomaticAction,
        date: LocalDate,
    ): Boolean {
        if (action !in available(target, date)) return false
        val current = relation(playerId, target)
        when (action) {
            DiplomaticAction.SUMMIT -> {
                shift(playerId, target, SUMMIT_BOOST)
                summitReady[target] = date.plusMonths(SUMMIT_COOLDOWN_MONTHS)
            }

            DiplomaticAction.AID -> {
                aidMonthsLeft[target] = 12
            }

            DiplomaticAction.CONDEMN -> {
                shift(playerId, target, -CONDEMN_DROP)
            }

            DiplomaticAction.EXPEL_DIPLOMATS -> {
                shift(playerId, target, -EXPEL_DROP)
            }

            DiplomaticAction.SANCTION -> {
                sanctions[playerId to target] = (sanction(playerId, target) + SANCTION_STEP).coerceAtMost(1.0)
                // 관계가 나쁜 나라는 보복 제재로 맞선다.
                if (current < RETALIATION_RELATION && random.nextDouble() < RETALIATION_CHANCE) {
                    sanctions[target to playerId] = (sanction(target, playerId) + SANCTION_STEP).coerceAtMost(1.0)
                    report(date, target, "보복 제재", "제재에 맞서 보복 제재를 발표했다.", major = true)
                }
            }

            DiplomaticAction.LIFT_SANCTION -> {
                sanctions.remove(playerId to target)
            }

            DiplomaticAction.TRADE_AGREEMENT -> {
                if (current < TRADE_ACCEPT) return false
                tradePairs += pair(playerId, target)
                report(date, target, "무역협정", "우리나라와 무역협정에 서명했다.", major = true)
            }

            DiplomaticAction.TRANSIT_TREATY -> {
                if (current < TRADE_ACCEPT) return false
                transitTreaties += pair(playerId, target)
                report(date, target, "상호 통행권", "우리나라와 상호 군사 통행권을 맺었다.", major = false)
            }

            DiplomaticAction.END_TRANSIT -> {
                transitTreaties -= pair(playerId, target)
                report(date, target, "통행권 종료", "우리나라와 맺었던 상호 군사 통행권이 종료됐다.", major = false)
            }

            DiplomaticAction.ALLIANCE -> {
                if (!acceptsAlliance(target, current)) return false
                alliances[pair(playerId, target)] = "방위조약"
                report(date, target, "방위조약", "우리나라와 상호방위조약을 맺었다.", major = true)
            }
        }
        return true
    }

    private fun acceptsAlliance(
        target: String,
        current: Double,
    ): Boolean {
        if (current < ALLIANCE_ACCEPT) return false
        return hasCommonRival(playerId, target) || democracy(playerId) == democracy(target)
    }

    /** 다른 시스템(전쟁, 사건)이 관계를 바꾼다. */
    fun nudge(
        a: String,
        b: String,
        points: Double,
    ) = shift(a, b, points)

    /**
     * 새 전쟁에 대한 국제 반응. 민주국가 중 피침국과 가깝고 침공국과 멀지 않은 나라는 침공국을 제재한다.
     * 플레이어는 직접 고르므로 제외한다.
     */
    fun onWarStarted(
        attacker: String,
        defender: String,
        date: LocalDate,
    ) {
        val responders =
            ids.filter { id ->
                id != playerId && id != attacker && id != defender && democracy(id) == true &&
                    relation(id, defender) > AGGRESSION_RESPONSE_RELATION && relation(id, attacker) < ALLIANCE_ACCEPT
            }
        responders.forEach { id ->
            if (random.nextDouble() < AGGRESSION_SANCTION_CHANCE) {
                sanctions[id to attacker] = (sanction(id, attacker) + SANCTION_STEP).coerceAtMost(1.0)
                shift(id, attacker, -CONDEMN_DROP)
            }
        }
        if (responders.isNotEmpty()) report(date, attacker, "국제 제재", "침공에 맞서 여러 나라가 제재를 발표했다.", major = true)
    }

    /** AI 국가가 플레이어에게 보내는 제안(무역협정·방위조약). 시뮬레이션이 선택지 사건으로 바꾼다. */
    data class Proposal(
        val from: String,
        val action: DiplomaticAction,
    )

    /**
     * AI 국가의 월간 외교 결정. 관계가 회복되면 제재를 풀고, 가까운 나라끼리 무역협정과 동맹을 맺는다.
     * 관계가 좋은 나라는 플레이어에게 협정을 제안한다.
     * @param atWar 두 나라가 교전 중인지
     * @param decisionMakers 스스로 외교 결정을 내리는 나라(정치 모델이 있는 플레이 가능 국가)
     */
    fun aiDecisions(
        date: LocalDate,
        atWar: (String, String) -> Boolean,
        decisionMakers: Collection<String>,
    ): List<Proposal> {
        sanctions.keys
            .filter { (by, target) -> by != playerId && !atWar(by, target) && relation(by, target) > LIFT_RELATION }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .forEach { key ->
                if (random.nextDouble() < LIFT_CHANCE) {
                    sanctions.remove(key)
                    report(date, key.first, "제재 해제", "관계 개선에 따라 대상국에 대한 제재를 풀었다.", major = false)
                }
            }
        val makers = decisionMakers.filter { it != playerId }.sorted()
        makers.forEach { a ->
            makers.filter { it > a }.forEach { b ->
                val value = relation(a, b)
                if (!tradeAgreement(a, b) && value >= AI_TRADE_RELATION && random.nextDouble() < AI_TRADE_CHANCE) {
                    tradePairs += pair(a, b)
                    report(date, a, "무역협정", "양국이 새 무역협정에 서명했다.", major = false)
                }
                if (!allied(a, b) && value >= AI_ALLIANCE_RELATION && hasCommonRival(a, b) && random.nextDouble() < AI_ALLIANCE_CHANCE) {
                    alliances[pair(a, b)] = "방위조약"
                    report(date, a, "방위조약", "양국이 상호방위조약을 맺었다.", major = true)
                }
            }
        }
        return makers.mapNotNull { id ->
            val value = relation(playerId, id)
            when {
                !allied(playerId, id) && value >= AI_ALLIANCE_RELATION && hasCommonRival(playerId, id) &&
                    random.nextDouble() < PROPOSAL_CHANCE -> {
                    Proposal(id, DiplomaticAction.ALLIANCE)
                }

                !tradeAgreement(playerId, id) && value >= PROPOSE_TRADE_RELATION && random.nextDouble() < PROPOSAL_CHANCE -> {
                    Proposal(id, DiplomaticAction.TRADE_AGREEMENT)
                }

                else -> {
                    null
                }
            }
        }
    }

    /** 플레이어가 AI의 제안을 받아들인다. */
    fun acceptProposal(
        proposal: Proposal,
        date: LocalDate,
    ) {
        when (proposal.action) {
            DiplomaticAction.TRADE_AGREEMENT -> tradePairs += pair(playerId, proposal.from)
            DiplomaticAction.ALLIANCE -> alliances[pair(playerId, proposal.from)] = "방위조약"
            else -> return
        }
        shift(playerId, proposal.from, SUMMIT_BOOST)
        report(date, proposal.from, proposal.action.label, "우리나라가 ${proposal.action.label} 제안을 받아들였다.", major = true)
    }

    private fun hasCommonRival(
        a: String,
        b: String,
    ): Boolean = ids.any { relation(a, it) < RIVAL_RELATION && relation(b, it) < RIVAL_RELATION }

    private fun report(
        date: LocalDate,
        country: String,
        source: String,
        text: String,
        major: Boolean,
    ) {
        news += WorldNews(date, country, source, text, major)
    }

    fun drainNews(): List<WorldNews> = news.toList().also { news.clear() }

    fun snapshot(
        date: LocalDate,
        focus: Collection<String>,
        lastDispatch: DiplomaticDispatch?,
    ): DiplomacySnapshot {
        val relations = ids.filter { it != playerId }.associateWith { relation(playerId, it) }
        return DiplomacySnapshot(
            playerId = playerId,
            organizations = organizationsOf(playerId),
            membershipDecisions = organizations.values.map(::membershipDecision),
            partners =
                relations.entries.sortedByDescending { it.value }.map { (id, value) ->
                    val allied = allied(playerId, id)
                    BilateralSnapshot(
                        countryId = id,
                        relation = value,
                        notable = id in focus || id in aidMonthsLeft || kotlin.math.abs(value) >= NOTABLE_RELATION || allied,
                        allied = allied,
                        alliance = alliances[pair(playerId, id)] ?: organizationAlliances[pair(playerId, id)],
                        transit = hasTransitAccess(playerId, id),
                        tradeAgreement = tradeAgreement(playerId, id),
                        sanctionedByUs = sanction(playerId, id),
                        sanctioningUs = sanction(id, playerId),
                        atWar = pair(playerId, id) in wars,
                        aidMonthsLeft = aidMonthsLeft[id] ?: 0,
                        nextSummitDate = summitReady[id]?.takeIf { it > date },
                        available = available(id, date),
                    )
                },
            relations = relations,
            lastDispatch = lastDispatch,
            lastMembershipDispatch = lastMembershipDispatch,
        )
    }

    private companion object {
        const val ALLIANCE_BONUS = 30.0
        const val SHARED_ORG_BONUS = 6.0
        const val MAX_SHARED_ORG_BONUS = 18.0
        const val TRADE_BONUS = 12.0

        /** 양방향 수출 비중 합 1%p당 관계 +0.4(최대 15). */
        const val TRADE_TIES = 40.0
        const val MAX_TRADE_TIES = 15.0
        const val SANCTION_PENALTY = 40.0
        const val WAR_PENALTY = 100.0
        const val DEMOCRACY_BONUS = 8.0
        const val AUTOCRACY_BONUS = 5.0
        const val REGIME_GAP = 5.0
        const val NEIGHBOR_FRICTION = 3.0
        const val OFFSET_DECAY = 0.97
        const val RELATION_SMOOTHING = 0.1
        const val SUMMIT_BOOST = 12.0
        const val SUMMIT_COOLDOWN_MONTHS = 6L
        const val AID_MONTHLY = 2.0
        const val AID_SHARE = 0.001
        const val CONDEMN_DROP = 10.0
        const val EXPEL_DROP = 25.0
        const val SANCTION_STEP = 0.5
        const val RETALIATION_RELATION = -10.0
        const val RETALIATION_CHANCE = 0.6
        const val TRADE_ACCEPT = 25.0
        const val ALLIANCE_ACCEPT = 55.0
        const val RIVAL_RELATION = -40.0
        const val NOTABLE_RELATION = 40.0
        const val AGGRESSION_RESPONSE_RELATION = 20.0
        const val AGGRESSION_SANCTION_CHANCE = 0.6
        const val LIFT_RELATION = -10.0
        const val LIFT_CHANCE = 0.03
        const val AI_TRADE_RELATION = 45.0
        const val AI_TRADE_CHANCE = 0.01
        const val AI_ALLIANCE_RELATION = 65.0
        const val AI_ALLIANCE_CHANCE = 0.005
        const val PROPOSE_TRADE_RELATION = 35.0
        const val PROPOSAL_CHANCE = 0.01
    }
}
