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
    AID("경제 원조", 2.0, "1년 동안 GDP의 0.1%를 원조해 관계를 크게 올린다."),
    CONDEMN("공개 비난", 0.0, "관계가 떨어지고, 국내 안보 성향은 약간 만족한다."),
    SANCTION("제재 부과", 8.0, "교역이 끊기고 관계가 크게 떨어진다. 상대가 보복 제재를 할 수 있다."),
    LIFT_SANCTION("제재 해제", 4.0, "우리가 건 제재를 푼다."),
    TRADE_AGREEMENT("무역협정 제안", 10.0, "관계가 25 이상이면 상대가 받아들인다. 교역이 늘어난다."),
    ALLIANCE("방위조약 제안", 15.0, "관계가 55 이상이고 공동의 적이 있거나 체제가 비슷하면 받아들인다. 전쟁에 함께 휘말린다."),
    EXPEL_DIPLOMATS("외교관 추방", 3.0, "관계가 크게 떨어진다."),
}

/** 플레이어와 한 나라의 외교 현황. */
data class BilateralSnapshot(
    val countryId: String,
    val relation: Double,
    val allied: Boolean,
    val alliance: String?,
    val tradeAgreement: Boolean,
    /** 우리가 건 제재 강도. */
    val sanctionedByUs: Double,
    /** 상대가 우리에게 건 제재 강도. */
    val sanctioningUs: Double,
    val atWar: Boolean,
    val available: Set<DiplomaticAction>,
)

data class DiplomacySnapshot(
    val playerId: String,
    val organizations: List<InternationalOrganization>,
    /** 플레이 가능 국가와 플레이어와 관계가 크게 좋거나 나쁜 나라. */
    val partners: List<BilateralSnapshot>,
    val relations: Map<String, Double>,
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
    private val aidUntil = HashMap<String, LocalDate>()
    private val news = mutableListOf<WorldNews>()
    private val membership: Map<String, List<InternationalOrganization>>

    init {
        data.organizations.forEach { org -> org.members.forEach { require(it in index) { "${org.id} has unknown member $it" } } }
        membership = ids.associateWith { id -> data.organizations.filter { id in it.members } }
        data.alliances.forEach { record ->
            record.members.forEach { a -> record.members.filter { it != a }.forEach { b -> alliances[pair(a, b)] = record.name } }
        }
        data.organizations.filter { it.kind == OrganizationType.ALLIANCE }.forEach { org ->
            org.members.forEach { a -> org.members.filter { it > a }.forEach { b -> alliances.putIfAbsent(pair(a, b), org.name) } }
        }
        data.organizations.filter { it.kind == OrganizationType.TRADE }.forEach { org ->
            org.members.forEach { a -> org.members.filter { it > a }.forEach { b -> tradePairs += pair(a, b) } }
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

    fun relation(
        a: String,
        b: String,
    ): Double = if (a == b) 100.0 else relation[index.getValue(a) * n + index.getValue(b)]

    fun allied(
        a: String,
        b: String,
    ): Boolean = pair(a, b) in alliances

    fun allies(id: String): List<String> = ids.filter { it != id && allied(id, it) }

    fun tradeAgreement(
        a: String,
        b: String,
    ): Boolean = pair(a, b) in tradePairs

    /** [by]가 [target]에 건 제재 강도. */
    fun sanction(
        by: String,
        target: String,
    ): Double = sanctions[by to target] ?: 0.0

    fun sanctionsSnapshot(): Map<Pair<String, String>, Double> = sanctions.toMap()

    fun setWar(
        a: String,
        b: String,
        active: Boolean,
    ) {
        if (active) wars += pair(a, b) else wars -= pair(a, b)
    }

    fun organizationsOf(id: String): List<InternationalOrganization> = membership[id].orEmpty()

    /** 구조가 정하는 관계 몫. */
    private fun structural(
        a: String,
        b: String,
    ): Double {
        var value = 0.0
        if (allied(a, b)) value += ALLIANCE_BONUS
        val shared =
            membership.getValue(a).count { org -> org.id != "UN" && org.kind != OrganizationType.ALLIANCE && b in org.members }
        value += (shared * SHARED_ORG_BONUS).coerceAtMost(MAX_SHARED_ORG_BONUS)
        if (tradeAgreement(a, b)) value += TRADE_BONUS
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

    fun advanceMonth(date: LocalDate) {
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val k = i * n + j
                offset[k] *= OFFSET_DECAY
                val target = (natural(i, j) + offset[k]).coerceIn(-100.0, 100.0)
                set(relation, i, j, relation[k] + (target - relation[k]) * RELATION_SMOOTHING)
                set(offset, i, j, offset[k])
            }
        }
        aidUntil.entries.removeIf { (country, until) ->
            if (date < until) {
                shift(playerId, country, AID_MONTHLY)
                false
            } else {
                true
            }
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

    /** 원조가 진행 중이면 GDP 대비 연간 원조액. */
    val aidSpending: Double get() = aidUntil.size * AID_SHARE

    fun available(
        target: String,
        date: LocalDate,
    ): Set<DiplomaticAction> {
        if (target == playerId) return emptySet()
        val atWar = pair(playerId, target) in wars
        return DiplomaticAction.entries.filterTo(mutableSetOf()) { action ->
            when (action) {
                DiplomaticAction.SUMMIT -> !atWar && (summitReady[target]?.let { date >= it } ?: true)
                DiplomaticAction.AID -> !atWar && target !in aidUntil
                DiplomaticAction.CONDEMN, DiplomaticAction.EXPEL_DIPLOMATS -> true
                DiplomaticAction.SANCTION -> sanction(playerId, target) < 1.0
                DiplomaticAction.LIFT_SANCTION -> sanction(playerId, target) > 0.0
                DiplomaticAction.TRADE_AGREEMENT -> !atWar && !tradeAgreement(playerId, target)
                DiplomaticAction.ALLIANCE -> !atWar && !allied(playerId, target)
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
                aidUntil[target] = date.plusYears(1)
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
        val commonRival = ids.any { relation(playerId, it) < RIVAL_RELATION && relation(target, it) < RIVAL_RELATION }
        return commonRival || democracy(playerId) == democracy(target)
    }

    /** 다른 시스템(전쟁, 사건)이 관계를 바꾼다. */
    fun nudge(
        a: String,
        b: String,
        points: Double,
    ) = shift(a, b, points)

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
    ): DiplomacySnapshot {
        val relations = ids.filter { it != playerId }.associateWith { relation(playerId, it) }
        val notable =
            relations
                .filter { (id, value) ->
                    id in focus || kotlin.math.abs(value) >= NOTABLE_RELATION || allied(playerId, id)
                }.keys
        return DiplomacySnapshot(
            playerId = playerId,
            organizations = organizationsOf(playerId),
            partners =
                notable.sortedByDescending { relations.getValue(it) }.map { id ->
                    BilateralSnapshot(
                        countryId = id,
                        relation = relations.getValue(id),
                        allied = allied(playerId, id),
                        alliance = alliances[pair(playerId, id)],
                        tradeAgreement = tradeAgreement(playerId, id),
                        sanctionedByUs = sanction(playerId, id),
                        sanctioningUs = sanction(id, playerId),
                        atWar = pair(playerId, id) in wars,
                        available = available(id, date),
                    )
                },
            relations = relations,
        )
    }

    private companion object {
        const val ALLIANCE_BONUS = 30.0
        const val SHARED_ORG_BONUS = 6.0
        const val MAX_SHARED_ORG_BONUS = 18.0
        const val TRADE_BONUS = 12.0
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
    }
}
