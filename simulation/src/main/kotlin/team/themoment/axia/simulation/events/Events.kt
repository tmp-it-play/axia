package team.themoment.axia.simulation.events

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.economy.DebtPlan
import team.themoment.axia.simulation.economy.Sector
import team.themoment.axia.simulation.nation.PopulationGroup
import team.themoment.axia.simulation.society.Identity
import team.themoment.axia.simulation.text.josa
import java.time.LocalDate
import kotlin.math.exp
import kotlin.random.Random

/** 사건 분류. 자동 일시정지 설정의 단위다. */
enum class EventCategory(
    val label: String,
    val pauseByDefault: Boolean,
) {
    DISASTER("자연재해", true),
    ACCIDENT("사고", false),
    EPIDEMIC("전염병", true),
    SCANDAL("스캔들", true),
    UNREST("소요·파업", false),
    TERROR("테러", true),
    FINANCIAL("금융 위기", true),
    COUP("쿠데타", true),
    MILITARY("군사", true),
    DIPLOMATIC("외교", false),
    ECONOMIC("경제", false),
}

/** 사건이 일어날 수 있는 곳. */
enum class EventScope {
    /** 플레이어 국가만. 매일 판정한다. */
    PLAYER,

    /** AI 국가만. 매월 판정한다. */
    WORLD,

    /** 플레이어(매일)와 AI 국가(매월) 모두. */
    BOTH,

    /** 세계 전체에 한 번. 매월 판정하고 모든 나라에 효과가 미친다. */
    GLOBAL,

    /** 시뮬레이션 상태가 발생 시점을 정한다. 월간 확률 추첨에서 제외한다. */
    TRIGGERED,
}

/**
 * 사건의 결과. 만족도 변화는 점수이고 서서히 사라진다.
 * @property mood 모든 인구 집단에 더하는 만족도
 * @property spending 한 번 지출하는 GDP 대비 비율(부채로 쌓인다)
 * @property debtPlan 국가부도 협상에서 제시할 채무 교환안
 */
@Serializable
data class EventEffects(
    val outputGap: Double = 0.0,
    val inflation: Double = 0.0,
    val mood: Double = 0.0,
    val identityMood: Map<Identity, Double> = emptyMap(),
    val groupMood: Map<PopulationGroup, Double> = emptyMap(),
    val crime: Double = 0.0,
    val spending: Double = 0.0,
    val politicalCapital: Double = 0.0,
    val potentialGrowth: Double = 0.0,
    val debtPlan: DebtPlan? = null,
    /** 한 달 동안 산업 부문 경기에 더하는 충격. */
    val sectorShocks: Map<Sector, Double> = emptyMap(),
)

@Serializable
data class EventChoice(
    val label: String,
    val description: String,
    val effects: EventEffects,
    /** 효과 외에 시뮬레이션이 실행할 행위(예: "peace:3" = 3번 전쟁 휴전). */
    val action: String? = null,
)

@Serializable
internal data class EventDefinition(
    val id: String,
    val name: String,
    val category: EventCategory,
    val scope: EventScope,
    val base: Double,
    val texts: List<String>,
    val major: Boolean = true,
    val hazard: String? = null,
    val factors: Map<String, Double> = emptyMap(),
    val requires: Map<String, Double> = emptyMap(),
    val effects: EventEffects = EventEffects(),
    val choices: List<EventChoice> = emptyList(),
) {
    init {
        require(base in 0.0..1.0 && texts.isNotEmpty())
        (factors.keys + requires.keys).forEach { require(it in EventContext.VARIABLES) { "$id uses unknown variable $it" } }
    }
}

@Serializable
internal data class EventFile(
    val sourceNote: String,
    val defaultHazard: Double,
    val hazards: Map<String, Map<String, Double>>,
    val events: List<EventDefinition>,
) {
    companion object {
        val default: EventFile by lazy {
            val text =
                requireNotNull(EventFile::class.java.getResourceAsStream("/world/events.json")) { "Missing events.json" }
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            Json.decodeFromString<EventFile>(text)
        }
    }
}

/** 사건 확률을 정하는 나라 상태. 모두 "보통 상태 = 0" 기준으로 맞춘 값이다. */
data class EventContext(
    val values: Map<String, Double>,
) {
    operator fun get(name: String): Double = values[name] ?: 0.0

    companion object {
        /** 사건 정의가 쓸 수 있는 변수. */
        val VARIABLES =
            setOf(
                "climate",
                "laborProtection",
                "healthSpending",
                "educationSpending",
                "civilLiberties",
                "judicial",
                "unemploymentGap",
                "inflation",
                "crime",
                "unrest",
                "democracy",
                "coupProne",
                "warIntensity",
                "policing",
                "debtRatio",
                "policyRate",
                "outputGap",
                "credibility",
                "floating",
                "coupRisk",
                "approval",
                "hostility",
            )
    }
}

/** 일어난 사건. 선택지가 있으면 플레이어가 고르기 전까지 [chosen]이 null이다. */
data class GameEvent(
    val id: Int,
    val definitionId: String,
    val name: String,
    val category: EventCategory,
    val countryId: String,
    val date: LocalDate,
    val text: String,
    val major: Boolean,
    val effects: EventEffects,
    val choices: List<EventChoice>,
    val chosen: Int?,
    /** 이 날까지 고르지 않으면 첫 번째 선택지를 따른다. */
    val deadline: LocalDate?,
)

/**
 * 조건부 사건. 사건은 무작위로 터지지 않고 상태가 확률을 만든다(5.2절 3).
 * 월간 확률 = 기본 확률 × 재해 위험 × exp(Σ 계수 × 변수). 플레이어 국가는 매일 1/30씩 판정한다.
 * 난수는 다른 시스템과 분리돼 있다.
 */
internal class EventSystem(
    private val random: Random,
    private val name: (String) -> String,
    private val file: EventFile = EventFile.default,
) {
    private var nextId = 1
    private val pending = mutableListOf<GameEvent>()
    private val history = ArrayDeque<GameEvent>()

    private fun hazard(
        countryId: String,
        key: String?,
    ): Double = if (key == null) 1.0 else file.hazards[countryId]?.get(key) ?: file.defaultHazard

    private fun monthlyChance(
        definition: EventDefinition,
        countryId: String,
        context: EventContext,
    ): Double {
        if (definition.requires.any { (variable, minimum) -> context[variable] < minimum }) return 0.0
        val exponent = definition.factors.entries.sumOf { (variable, coefficient) -> coefficient * context[variable] }
        return (definition.base * hazard(countryId, definition.hazard) * exp(exponent.coerceIn(-5.0, 3.0))).coerceAtMost(MAX_MONTHLY)
    }

    /** 플레이어 국가의 하루 판정. */
    fun rollPlayer(
        date: LocalDate,
        countryId: String,
        context: EventContext,
    ): List<GameEvent> =
        file.events
            .filter { it.scope == EventScope.PLAYER || it.scope == EventScope.BOTH }
            .filter { random.nextDouble() < monthlyChance(it, countryId, context) / DAYS_PER_MONTH }
            .map { create(it, countryId, date, player = true) }

    /** AI 국가와 세계 전체의 월간 판정. */
    fun rollWorld(
        date: LocalDate,
        contexts: Map<String, EventContext>,
        playerId: String,
    ): List<GameEvent> {
        val events = mutableListOf<GameEvent>()
        file.events.filter { it.scope == EventScope.WORLD || it.scope == EventScope.BOTH }.forEach { definition ->
            contexts.keys.sorted().filter { it != playerId }.forEach { id ->
                val chance = monthlyChance(definition, id, contexts.getValue(id)) * WORLD_SCALE
                if (random.nextDouble() < chance) events += create(definition, id, date, player = false)
            }
        }
        file.events.filter { it.scope == EventScope.GLOBAL }.forEach { definition ->
            if (random.nextDouble() < definition.base) events += create(definition, playerId, date, player = true)
        }
        return events
    }

    /** 경제 모델이 판정한 사건도 일반 전보와 같은 선택·기한·기록을 사용한다. */
    fun trigger(
        id: String,
        countryId: String,
        date: LocalDate,
        player: Boolean,
        detail: String,
    ): GameEvent = create(file.events.single { it.id == id && it.scope == EventScope.TRIGGERED }, countryId, date, player, detail)

    private fun create(
        definition: EventDefinition,
        countryId: String,
        date: LocalDate,
        player: Boolean,
        detail: String = "",
    ): GameEvent {
        val country = name(countryId)
        val text =
            definition.texts
                .random(random)
                .replace("{country}을(를)", country.josa("을", "를"))
                .replace("{country}", country)
        val decide = player && definition.choices.isNotEmpty()
        val event =
            GameEvent(
                id = nextId++,
                definitionId = definition.id,
                name = definition.name,
                category = definition.category,
                countryId = countryId,
                date = date,
                text = if (detail.isEmpty()) text else "$text $detail",
                major = definition.major,
                effects = definition.effects,
                choices = if (decide) definition.choices else emptyList(),
                chosen = null,
                deadline = if (decide) date.plusDays(DECISION_DAYS) else null,
            )
        if (decide) pending += event
        history.addFirst(event)
        while (history.size > MAX_HISTORY) history.removeLast()
        return event
    }

    /** 데이터 정의가 아닌 시스템 사건(예: 상대국의 휴전 제안). 선택지가 있으면 플레이어 결정을 기다린다. */
    fun custom(
        name: String,
        category: EventCategory,
        countryId: String,
        date: LocalDate,
        text: String,
        choices: List<EventChoice>,
    ): GameEvent {
        val event =
            GameEvent(
                id = nextId++,
                definitionId = "system",
                name = name,
                category = category,
                countryId = countryId,
                date = date,
                text = text,
                major = true,
                effects = EventEffects(),
                choices = choices,
                chosen = null,
                deadline = if (choices.isEmpty()) null else date.plusDays(DECISION_DAYS),
            )
        if (choices.isNotEmpty()) pending += event
        history.addFirst(event)
        while (history.size > MAX_HISTORY) history.removeLast()
        return event
    }

    /** 플레이어가 선택지를 고른다. 고른 선택지의 효과를 돌려준다. */
    fun choose(
        eventId: Int,
        index: Int,
    ): EventChoice? {
        val event = pending.firstOrNull { it.id == eventId } ?: return null
        val choice = event.choices.getOrNull(index) ?: return null
        pending -= event
        replace(event.copy(chosen = index))
        return choice
    }

    /** 기한이 지난 결정은 첫 번째 선택지를 따른다. */
    fun expire(date: LocalDate): List<Pair<GameEvent, EventChoice>> =
        pending.filter { date >= requireNotNull(it.deadline) }.map { event ->
            pending -= event
            replace(event.copy(chosen = 0))
            event to event.choices.first()
        }

    private fun replace(event: GameEvent) {
        val index = history.indexOfFirst { it.id == event.id }
        if (index >= 0) history[index] = event
    }

    fun pending(): List<GameEvent> = pending.toList()

    fun history(): List<GameEvent> = history.toList()

    private companion object {
        const val DAYS_PER_MONTH = 30.0
        const val MAX_MONTHLY = 0.5
        const val DECISION_DAYS = 14L
        const val MAX_HISTORY = 80

        /** AI 국가는 국가 단위로 뭉뚱그려 계산하므로 사건 빈도를 절반으로 본다(세계 소식 과잉 방지). */
        const val WORLD_SCALE = 0.5
    }
}
