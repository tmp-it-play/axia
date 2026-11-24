package team.themoment.axia.simulation.military

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.politics.WorldNews
import team.themoment.axia.simulation.text.josa
import team.themoment.axia.simulation.world.Geography
import team.themoment.axia.simulation.world.Region
import team.themoment.axia.simulation.world.StructureData
import java.time.LocalDate
import kotlin.math.pow

/** 전력 구성. 값은 군사 자본(누적 국방비, 달러) × 장비 수준으로 정한 상대 지수다. */
enum class Branch(
    val label: String,
) {
    LAND("지상"),
    NAVAL("해상"),
    AIR("공중"),
    CYBER("사이버"),
}

/** 플레이어가 정하는 동원 태세. 높을수록 준비태세가 오르지만 비용과 노동력 손실이 크다. */
enum class Mobilization(
    val label: String,
    val readiness: Double,
    /** GDP 대비 연간 추가 지출. */
    val cost: Double,
) {
    PEACETIME("평시", 0.5, 0.0),
    PARTIAL("부분 동원", 0.75, 0.005),
    TOTAL("총동원", 0.95, 0.02),
}

enum class MilitaryAction(
    val label: String,
    val cost: Double,
    val description: String,
) {
    DECLARE_WAR("선전포고", 30.0, "인접국이거나 해군력이 있으면 전쟁을 시작한다. 상대 동맹국이 참전하고 국제 관계가 나빠진다."),
    PROPOSE_PEACE("평화 제안", 5.0, "상대가 지쳤거나 불리하면 현재 전선에서 휴전한다."),
    WITHDRAWAL_TREATY("상호 철수 협정", 5.0, "점령지에서 상호 철수하고 전쟁을 종결한다."),
    OFFER_TERRITORY("분쟁지 양도 협정", 5.0, "분쟁지의 관할을 상대에게 넘기고 양측이 나머지 점령지에서 철수한다."),
    REQUEST_TERRITORY("분쟁지 반환 협정", 8.0, "상대에게 분쟁지 관할 이전을 요구한다. 상대가 크게 지치고 불리한 경우에 수락한다."),
    BLOCKADE("해상 봉쇄", 9.0, "교전 상대의 해상 교역로 수송능력을 45%로 줄인다."),
    END_BLOCKADE("해상 봉쇄 해제", 0.0, "해상 교역을 재개한다."),
    MILITARY_AID("군사 지원", 4.0, "1년 동안 GDP의 0.2%를 들여 전쟁 중인 나라의 전력을 보탠다."),
    JOIN_WAR("참전", 20.0, "동맹국이나 관계가 좋은 나라 편으로 전쟁에 들어간다."),
}

@Serializable
internal data class FrontRecord(
    val attacker: String,
    val defender: String,
    val control: Double,
)

@Serializable
internal data class WarRecord(
    val name: String,
    val started: String,
    val sideA: List<String>,
    val sideB: List<String>,
    val supportA: List<String> = emptyList(),
    val supportB: List<String> = emptyList(),
    val fronts: List<FrontRecord>,
    val intensity: Double,
    val note: String? = null,
)

@Serializable
internal data class CivilWarRecord(
    val country: String,
    val rebels: String,
    val started: String,
    val control: Double,
    val strength: Double,
    val intensity: Double,
)

@Serializable
data class Dispute(
    val name: String,
    val claimants: List<String>,
    /** 조약으로 이관할 때 지도에서 전체 경계를 구분할 수 있는 지역. */
    val treatyRegions: List<String> = emptyList(),
    val controller: String,
    val tension: Double,
    val note: String? = null,
)

@Serializable
internal data class MilitaryFile(
    val sourceNote: String,
    val nuclearWarheads: Map<String, Int>,
    val wars: List<WarRecord>,
    val civilWars: List<CivilWarRecord>,
    val disputes: List<Dispute>,
) {
    companion object {
        val default: MilitaryFile by lazy {
            val text =
                requireNotNull(MilitaryFile::class.java.getResourceAsStream("/world/military.json")) { "Missing military.json" }
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            Json.decodeFromString<MilitaryFile>(text)
        }
    }
}

data class ForceSnapshot(
    val countryId: String,
    /** 군사력 지수. 세계 최강 = 100 기준 상대값. */
    val power: Double,
    val branches: Map<Branch, Double>,
    val personnel: Double,
    val readiness: Double,
    val nuclearWarheads: Int,
    /** 국방비(연간 달러). */
    val spending: Double,
)

data class FrontSnapshot(
    val attacker: String,
    val defender: String,
    /** 양수면 공격국이 수비국 국토의 그 비율을, 음수면 수비국이 공격국 국토를 점령. */
    val control: Double,
    /** 최근 한 주의 변화. */
    val trend: Double,
)

data class TreatyRegion(
    val id: String,
    val name: String,
    val owner: String,
)

data class PeaceTreaty(
    val date: LocalDate,
    val territory: String? = null,
    val from: String? = null,
    val to: String? = null,
)

data class WarSnapshot(
    val id: Int,
    val name: String,
    val started: LocalDate,
    val sideA: List<String>,
    val sideB: List<String>,
    val supportA: List<String>,
    val supportB: List<String>,
    val fronts: List<FrontSnapshot>,
    val intensity: Double,
    val weariness: Map<String, Double>,
    val ceasefire: LocalDate?,
    val treaty: PeaceTreaty?,
)

data class CivilWarSnapshot(
    val generatedId: Int?,
    val country: String,
    val rebels: String,
    val started: LocalDate,
    val rebelControl: Double,
    val intensity: Double,
    val ended: LocalDate?,
)

data class MilitarySnapshot(
    val forces: Map<String, ForceSnapshot>,
    val wars: List<WarSnapshot>,
    val civilWars: List<CivilWarSnapshot>,
    val disputes: List<Dispute>,
    val mobilization: Mobilization,
    val regionOwners: Map<String, String>,
    val negotiableRegions: Map<String, List<TreatyRegion>>,
    val recovery: WarRecoverySnapshot,
    val reconstructionEffort: ReconstructionEffort,
    val maritimeRestrictions: List<MaritimeRestriction>,
    /** 플레이어가 지원 중인 나라와 종료일. */
    val aid: Map<String, LocalDate>,
)

/** 나라별 국방 재료. 매월 경제에서 받아 온다. */
internal data class DefenseInputs(
    /** 연간 국방비(달러). */
    val spending: Double,
    val gdpPerCapita: Double,
    val approval: Double,
)

/**
 * 추상형 군사: 나라별 전력, 준비태세, 전쟁과 전선, 내전, 영토 분쟁.
 *
 * - 전력: 국방비가 쌓인 군사 자본(15년 감가) × 장비 수준(1인당 소득) × 병력. 해안이 없는 나라는 해군이 거의 없다.
 * - 전선: 매주 양측 전력비(수비 1.3배 가점), 준비태세, 사기(피로도)로 통제 비율이 움직인다.
 *   지원국은 전력의 일부를 보탠다. 사상자는 군사 자본을 깎고 피로도를 쌓는다.
 * - 피로도가 높고 불리한 쪽은 휴전을 받아들이고, 국토 대부분을 잃으면 항복한다. 휴전선이 그대로 굳는다.
 * - 경제에는 시작 대비 점령 변화와 전쟁 강도만 전한다(2023년 데이터에 이미 진행 중이던 전쟁 효과가 들어 있다).
 */
internal class Military(
    states: List<String>,
    private val playerId: String,
    private val name: (String) -> String,
    private val random: kotlin.random.Random,
    private val allied: (String, String) -> Boolean,
    private val relation: (String, String) -> Double,
    private val regions: Map<String, Region>,
    private val transit: (String, String) -> Boolean = { _, _ -> false },
    data: MilitaryFile = MilitaryFile.default,
) {
    private val ids = states.sorted()
    private val structure = StructureData.default
    private val geography = Geography.default
    private val capital = HashMap<String, Double>()
    private val personnel = HashMap<String, Double>()
    private val quality = HashMap<String, Double>()
    private val spending = HashMap<String, Double>()
    private val readiness = HashMap<String, Double>()
    private val nuclear = data.nuclearWarheads
    private var maxPower = 1.0
    var mobilization = Mobilization.PEACETIME
        private set
    private val aid = HashMap<String, LocalDate>()
    val maritime = MaritimeTrade()
    private val wars = mutableListOf<War>()
    private val civilWars = mutableListOf<CivilWar>()
    private val disputes = data.disputes.toMutableList()
    private val bundledRegions = disputes.flatMap { it.treatyRegions }.toSet()
    private val regionCount =
        regions.values
            .groupingBy(Region::countryId)
            .eachCount()
            .toMutableMap()
    var regionOwners: Map<String, String> = emptyMap()
        private set
    private val civilVictories = mutableListOf<Pair<String, String>>()
    private val recovery = WarRecovery(playerId)
    private val startOccupied = HashMap<String, Double>()
    private val startIntensity = HashMap<String, Double>()
    private val news = mutableListOf<WorldNews>()
    private var nextWarId = 1

    private class Front(
        val attacker: String,
        val defender: String,
        var control: Double,
        var trend: Double = 0.0,
    ) {
        /** 시작 시점 통제 비율. 오래된 점령지(예: 서안 C구역)를 이번 전쟁의 패배로 세지 않도록 항복 기준에 쓴다. */
        val startControl: Double = control

        /** 국토를 이만큼 잃으면 항복한다. 이미 점령당한 몫이 있으면 그보다 더 잃어야 한다. */
        val capitulation: Double get() = maxOf(CAPITULATION, kotlin.math.abs(startControl) + CAPITULATION_MARGIN).coerceAtMost(MAX_CONTROL)
    }

    private class War(
        val id: Int,
        val name: String,
        val started: LocalDate,
        val sideA: MutableSet<String>,
        val sideB: MutableSet<String>,
        val supportA: MutableSet<String>,
        val supportB: MutableSet<String>,
        val fronts: MutableList<Front>,
        var intensity: Double,
        val weariness: HashMap<String, Double> = HashMap(),
        var ceasefire: LocalDate? = null,
        var treaty: PeaceTreaty? = null,
    ) {
        val active: Boolean get() = ceasefire == null

        val principalA = sideA.first()
        val principalB = sideB.first()

        fun oppositePrincipal(country: String): String? =
            when (country) {
                principalA -> principalB
                principalB -> principalA
                else -> null
            }

        fun side(country: String): Int =
            when (country) {
                in sideA -> 1
                in sideB -> -1
                else -> 0
            }
    }

    private class CivilWar(
        val country: String,
        var rebels: String,
        val started: LocalDate,
        var control: Double,
        var strength: Double,
        var intensity: Double,
        val generatedId: Int? = null,
        var ended: LocalDate? = null,
    )

    init {
        val all = ids.toSet()
        disputes.forEach { dispute ->
            require(
                dispute.treatyRegions.all { regions[it]?.countryId == dispute.controller },
            ) { "Invalid treaty area for ${dispute.name}" }
        }
        data.wars.forEach { record ->
            (record.sideA + record.sideB + record.supportA + record.supportB).forEach {
                require(
                    it in all,
                ) { "Unknown country $it in ${record.name}" }
            }
            wars +=
                War(
                    id = nextWarId++,
                    name = record.name,
                    started = LocalDate.parse(record.started),
                    sideA = record.sideA.toMutableSet(),
                    sideB = record.sideB.toMutableSet(),
                    supportA = record.supportA.toMutableSet(),
                    supportB = record.supportB.toMutableSet(),
                    fronts = record.fronts.map { Front(it.attacker, it.defender, it.control) }.toMutableList(),
                    intensity = record.intensity,
                )
        }
        data.civilWars.forEach { record ->
            require(record.country in all)
            civilWars +=
                CivilWar(record.country, record.rebels, LocalDate.parse(record.started), record.control, record.strength, record.intensity)
        }
        ids.forEach {
            startOccupied[it] = occupiedAbsolute(it)
            startIntensity[it] = intensityAbsolute(it)
        }
    }

    /** 매월 국방비와 장비 수준으로 군사 자본을 갱신한다. 첫 호출에서 초기 자본을 정한다. */
    fun advanceMonth(
        date: LocalDate,
        inputs: Map<String, DefenseInputs>,
    ) {
        ids.forEach { id ->
            val input = inputs.getValue(id)
            spending[id] = input.spending
            quality[id] = (input.gdpPerCapita / QUALITY_REFERENCE).pow(QUALITY_EXPONENT).coerceIn(MIN_QUALITY, MAX_QUALITY)
            val stock = capital[id]
            capital[id] =
                if (stock == null) {
                    input.spending * INITIAL_CAPITAL_YEARS
                } else {
                    stock + input.spending / 12.0 - stock / DEPRECIATION_MONTHS
                }
            if (id !in personnel) {
                val measured = structure.of(id).armedForces
                personnel[id] = if (measured > 0.0) measured else input.spending / (input.gdpPerCapita.coerceAtLeast(500.0) * 4.0)
            }
            val atWar = wars.any { it.active && it.side(id) != 0 }
            val target =
                when {
                    id == playerId -> mobilization.readiness
                    atWar -> WAR_READINESS
                    else -> PEACE_READINESS
                }
            readiness[id] = (readiness[id] ?: target) + (target - (readiness[id] ?: target)) * READINESS_SMOOTHING
        }
        maxPower = ids.maxOf(::rawPower).coerceAtLeast(1e-9)
        aid.entries.removeIf { date >= it.value }
        reviewPeace(date, inputs)
        advanceCivilWars(date)
        if (date.monthValue % 3 == 0) {
            wars.filter { it.active && date >= it.started.plusMonths(3) }.forEach { war ->
                listOf(war.principalA to war.principalB, war.principalB to war.principalA).forEach { (issuer, target) ->
                    val order = MaritimeRestriction(issuer, target, null)
                    if (issuer != playerId && !maritime.has(order) && geography.of(issuer).coastal && geography.of(target).coastal &&
                        (war.weariness[issuer] ?: 0.0) < PEACE_WEARINESS && random.nextDouble() < 0.20
                    ) {
                        maritime.set(order, true)
                        news += WorldNews(date, issuer, "해상 봉쇄", "${name(issuer)} 정부가 ${name(target)}의 해상 교역을 제한했다.", major = false)
                    }
                }
            }
        }
    }

    private fun rawPower(id: String): Double {
        val k = (capital[id] ?: 0.0) * (quality[id] ?: 1.0)
        return k.pow(CAPITAL_WEIGHT) * (personnel[id] ?: 0.0).pow(1.0 - CAPITAL_WEIGHT)
    }

    fun power(id: String): Double = rawPower(id) / maxPower * 100.0

    /** 전선에 투입되는 실전력. 여러 전선에 나눠 싸우면 그만큼 약하다. */
    private fun frontPower(
        id: String,
        war: War,
    ): Double {
        val fronts = wars.filter { it.active }.sumOf { w -> w.fronts.count { it.attacker == id || it.defender == id } }.coerceAtLeast(1)
        val morale = 1.0 - (war.weariness[id] ?: 0.0) / 200.0
        return rawPower(id) * (readiness[id] ?: PEACE_READINESS) * morale / fronts
    }

    /** 매주 전선을 움직인다. */
    fun advanceWeek(date: LocalDate) {
        wars.filter { it.active }.forEach { war ->
            war.fronts.forEach { front ->
                val attackerSide = war.side(front.attacker)
                val supportFor = { side: Int ->
                    val supporters = if (side == 1) war.supportA else war.supportB
                    val allies = (if (side == 1) war.sideA else war.sideB) - setOf(front.attacker, front.defender)
                    supporters.sumOf { rawPower(it) * SUPPORT_SHARE * (if (it == playerId || it in aid) 1.5 else 1.0) } +
                        allies.sumOf { frontPower(it, war) * ALLY_SHARE }
                }
                val attack = frontPower(front.attacker, war) + supportFor(attackerSide)
                val defense = (frontPower(front.defender, war) + supportFor(-attackerSide)) * DEFENSE_BONUS
                val ratio = attack / (attack + defense).coerceAtLeast(1e-9)
                val move = FRONT_SPEED * war.intensity * (ratio - 0.5) + random.nextDouble(-1.0, 1.0) * FRONT_NOISE * war.intensity
                // 통제 비율은 그 나라 국토 대비다. 원래 공격국 영토로 밀고 들어갈 때는 면적 차이만큼 비율이 덜 움직인다.
                val areaRatio = geography.of(front.defender).areaKm2 / geography.of(front.attacker).areaKm2
                val scaled = if (front.control + move < 0.0) move * areaRatio.coerceAtMost(1.0) else move
                front.trend = scaled
                front.control = (front.control + scaled).coerceIn(-MAX_CONTROL, MAX_CONTROL)
                listOf(front.attacker, front.defender).forEach { id ->
                    capital[id] = (capital[id] ?: 0.0) * (1.0 - CASUALTY_RATE * war.intensity)
                    war.weariness[id] = ((war.weariness[id] ?: 0.0) + WEARINESS_PER_WEEK * war.intensity).coerceAtMost(100.0)
                }
            }
        }
    }

    private fun reviewPeace(
        date: LocalDate,
        inputs: Map<String, DefenseInputs>,
    ) {
        wars.filter { it.active }.forEach { war ->
            // 각 진영의 주전국만 전체 진영을 대표한다. 동맹국의 단독 항복이나 국내 여론은 종전 결정이 될 수 없다.
            val capitulating =
                war.fronts.firstNotNullOfOrNull { front ->
                    val loser = if (front.control > 0.0) front.defender else front.attacker
                    loser.takeIf { kotlin.math.abs(front.control) >= front.capitulation && war.oppositePrincipal(it) != null }
                }
            if (capitulating != null) {
                val winner = requireNotNull(war.oppositePrincipal(capitulating))
                ceasefire(war, date, "${name(capitulating)}의 항복으로 ${war.name}이 끝났다.")
                signTreaty(war, date, cedable(capitulating, winner), winner)
                return@forEach
            }
            if (war.oppositePrincipal(playerId) != null) return@forEach
            val tiredA = war.weariness[war.principalA] ?: 0.0
            val tiredB = war.weariness[war.principalB] ?: 0.0
            val unpopular = listOf(war.principalA, war.principalB).any { inputs.getValue(it).approval < 25.0 }
            if (((tiredA > PEACE_WEARINESS && tiredB > PEACE_WEARINESS) || unpopular) && random.nextDouble() < PEACE_CHANCE) {
                ceasefire(war, date, "${war.name}의 교전 당사국들이 휴전에 합의했다.")
            }
        }
        wars
            .filter {
                it.ceasefire?.plusYears(1)?.let { review -> date >= review } == true && it.treaty == null &&
                    it.oppositePrincipal(playerId) == null
            }.forEach { war ->
                // 재개전으로 옛 휴전선을 승계한 상대가 있으면 이전 협정으로 그 전선을 바꿀 수 없다.
                val resumed =
                    wars.any { later ->
                        later.id > war.id &&
                            war.sideA.any { a -> war.sideB.any { b -> later.side(a) != 0 && later.side(a) == -later.side(b) } }
                    }
                if (!resumed) signTreaty(war, date, null, war.sideA.first())
            }
    }

    private fun ceasefire(
        war: War,
        date: LocalDate,
        text: String,
    ) {
        war.ceasefire = date
        maritime.clearInactive(::atWar)
        pendingOffers.remove(war.id)
        news += WorldNews(date, war.sideA.first(), war.name, text, major = true)
    }

    private fun advanceCivilWars(date: LocalDate) {
        civilWars.filter { it.ended == null }.forEach { war ->
            val state = (readiness[war.country] ?: PEACE_READINESS) * STATE_ADVANTAGE
            val move = CIVIL_SPEED * war.intensity * (war.strength - state) + random.nextDouble(-1.0, 1.0) * CIVIL_NOISE
            war.control = (war.control + move).coerceIn(0.0, 1.0)
            war.strength = (war.strength + random.nextDouble(-0.02, 0.02)).coerceIn(0.05, 1.0)
            when {
                war.control < CIVIL_END -> {
                    war.ended = date
                    news += WorldNews(date, war.country, "내전", "정부군이 ${war.rebels} 세력을 사실상 진압했다.", major = true)
                }

                war.control > CIVIL_VICTORY -> {
                    war.ended = date
                    civilVictories += war.country to war.rebels
                    news += WorldNews(date, war.country, "내전", "${war.rebels} 세력이 수도를 장악하고 정권을 무너뜨렸다.", major = true)
                }
            }
        }
    }

    /** 나라 안팎의 전쟁 강도(0~1). 내전 포함, 가장 큰 값. */
    private fun intensityAbsolute(id: String): Double =
        maxOf(
            wars.filter { it.active && it.side(id) != 0 }.maxOfOrNull { it.intensity } ?: 0.0,
            civilWars.filter { it.ended == null && it.country == id }.maxOfOrNull { it.intensity * 0.6 } ?: 0.0,
        )

    /** 적에게 점령된 국토 비율과 내전 반군 통제 비율의 합. */
    private fun occupiedAbsolute(id: String): Double {
        val foreign =
            wars.sumOf { war ->
                war.fronts.sumOf { front ->
                    when (id) {
                        front.defender -> front.control.coerceAtLeast(0.0)
                        front.attacker -> (-front.control).coerceAtLeast(0.0)
                        else -> 0.0
                    }
                }
            }
        val rebels = civilWars.filter { it.ended == null && it.country == id }.sumOf { it.control }
        return (foreign + rebels).coerceAtMost(1.0)
    }

    /** 시작 대비 전쟁 강도 변화. 경제에는 이것만 전한다. 시작 때 치르던 전쟁이 끝나면 음수(평화 배당)다. */
    fun intensityChange(id: String): Double = intensityAbsolute(id) - (startIntensity[id] ?: 0.0)

    /** 시작 대비 점령 비율 변화(되찾으면 음수). */
    fun occupiedChange(id: String): Double = occupiedAbsolute(id) - (startOccupied[id] ?: 0.0)

    /** 플레이어가 치르고 있는 전쟁의 최대 피로도. */
    fun playerWeariness(): Double = wars.filter { it.active && it.side(playerId) != 0 }.maxOfOrNull { it.weariness[playerId] ?: 0.0 } ?: 0.0

    fun atWar(
        a: String,
        b: String,
    ): Boolean = wars.any { it.active && it.side(a) != 0 && it.side(b) != 0 && it.side(a) != it.side(b) }

    /** 교전 쌍(외교 관계 계산용). */
    fun hostilePairs(): List<Pair<String, String>> =
        wars.filter { it.active }.flatMap { war -> war.sideA.flatMap { a -> war.sideB.map { b -> a to b } } }

    private val offerCooldown = HashMap<Int, LocalDate>()
    private val pendingOffers = HashMap<Int, LocalDate>()

    /** 플레이어와 싸우는 상대가 지쳤으면 휴전을 제안한다. (전쟁 번호, 제안국) 목록. */
    fun peaceOffers(date: LocalDate): List<Pair<Int, String>> =
        wars.filter { it.active }.mapNotNull { war ->
            val enemy = war.oppositePrincipal(playerId) ?: return@mapNotNull null
            val ready = offerCooldown[war.id]?.let { date >= it } ?: true
            if (ready && (war.weariness[enemy] ?: 0.0) > OFFER_WEARINESS && random.nextDouble() < OFFER_CHANCE) {
                offerCooldown[war.id] = date.plusMonths(OFFER_COOLDOWN_MONTHS)
                pendingOffers[war.id] = date
                war.id to enemy
            } else {
                null
            }
        }

    fun acceptPeace(
        warId: Int,
        date: LocalDate,
    ) {
        val war = wars.firstOrNull { it.id == warId && it.active && it.oppositePrincipal(playerId) != null } ?: return
        val offered = pendingOffers.remove(warId) ?: return
        if (date < offered || date > offered.plusDays(14)) return
        ceasefire(war, date, "${war.name}에서 휴전이 성립했다.")
    }

    fun warName(warId: Int): String? = wars.firstOrNull { it.id == warId }?.name

    /** 사건으로 새 내전이 일어난다. 이미 내전 중이면 무시한다. */
    fun startCivilWar(
        country: String,
        rebels: String,
        date: LocalDate,
    ): Boolean {
        if (civilWars.any { it.country == country && it.ended == null }) return false
        val used = civilWars.filter { it.country == country }.mapTo(mutableSetOf()) { it.rebels }
        var name = rebels
        var suffix = 2
        while (name in used) name = "$rebels ${suffix++}"
        civilWars += CivilWar(country, name, date, NEW_REBEL_CONTROL, random.nextDouble(0.2, 0.5), NEW_CIVIL_INTENSITY, civilWars.size + 1)
        return true
    }

    fun renameRebels(
        id: Int,
        requested: String,
    ) {
        val war = civilWars.firstOrNull { it.generatedId == id && it.ended == null } ?: return
        val name = requested.trim()
        if (name.length in 1..40 && name.none(Char::isISOControl) &&
            civilWars.none { it.country == war.country && it !== war && it.rebels == name }
        ) {
            war.rebels = name
        }
    }

    fun drainCivilVictories(): List<Pair<String, String>> = civilVictories.toList().also { civilVictories.clear() }

    fun setReconstructionEffort(effort: ReconstructionEffort) {
        recovery.effort = effort
    }

    fun advanceRecovery(population: Map<String, Double>): RecoveryMonth =
        recovery.advance(population, ids.associateWith(::intensityAbsolute), ::atWar)

    fun setMobilization(level: Mobilization) {
        mobilization = level
    }

    val aidSpending: Double get() = aid.size * AID_SHARE

    fun routeChangeAllowed(
        target: String,
        partner: String,
        impose: Boolean,
    ): Boolean =
        target in startOccupied && partner in startOccupied && atWar(playerId, target) && geography.of(playerId).coastal &&
            MaritimeTrade.seaRoute(target, partner) && maritime.has(MaritimeRestriction(playerId, target, partner)) != impose

    fun available(target: String): Set<MilitaryAction> {
        if (target == playerId || target !in startOccupied) return emptySet()
        val enemies = atWar(playerId, target)
        val targetAtWar = wars.any { it.active && it.side(target) != 0 }
        val reachable =
            geography.neighbors(playerId, target) || (geography.of(playerId).coastal && geography.of(target).coastal) ||
                geography.of(playerId).neighbors.any { host -> transit(playerId, host) && geography.neighbors(host, target) }
        val negotiating = negotiatingWar(target)
        return buildSet {
            if (!enemies && reachable && !allied(playerId, target)) add(MilitaryAction.DECLARE_WAR)
            if (enemies && geography.of(playerId).coastal && geography.of(target).coastal) {
                add(if (maritime.has(MaritimeRestriction(playerId, target, null))) MilitaryAction.END_BLOCKADE else MilitaryAction.BLOCKADE)
            }
            if (negotiating?.active == true) add(MilitaryAction.PROPOSE_PEACE)
            if (negotiating != null) {
                add(MilitaryAction.WITHDRAWAL_TREATY)
                if (cedable(playerId, target) != null) add(MilitaryAction.OFFER_TERRITORY)
                if (cedable(target, playerId) != null) add(MilitaryAction.REQUEST_TERRITORY)
            }
            if (targetAtWar && !enemies && target !in aid) add(MilitaryAction.MILITARY_AID)
            if (targetAtWar && !enemies && wars.none { it.active && it.side(playerId) != 0 && it.side(target) != 0 } &&
                (allied(playerId, target) || relation(playerId, target) >= JOIN_RELATION)
            ) {
                add(MilitaryAction.JOIN_WAR)
            }
        }
    }

    fun perform(
        target: String,
        action: MilitaryAction,
        date: LocalDate,
    ): Boolean {
        if (action !in available(target)) return false
        when (action) {
            MilitaryAction.BLOCKADE, MilitaryAction.END_BLOCKADE -> {
                maritime.set(MaritimeRestriction(playerId, target, null), action == MilitaryAction.BLOCKADE)
                news += WorldNews(date, playerId, action.label, "${name(target)} 해상 교역 · ${action.label}", major = false)
            }

            MilitaryAction.DECLARE_WAR -> {
                declareWar(playerId, target, date)
            }

            MilitaryAction.PROPOSE_PEACE -> {
                val war = requireNotNull(negotiatingWar(target))
                val theirs = war.weariness[target] ?: 0.0
                val losing =
                    war.fronts.any { (it.defender == target && it.control > 0.1) || (it.attacker == target && it.control < -0.1) }
                if (theirs < PEACE_ACCEPT_WEARINESS && !losing) return false
                ceasefire(war, date, "${war.name}에서 휴전이 성립했다.")
            }

            MilitaryAction.WITHDRAWAL_TREATY, MilitaryAction.OFFER_TERRITORY, MilitaryAction.REQUEST_TERRITORY -> {
                val war = requireNotNull(negotiatingWar(target))
                val offered = action == MilitaryAction.OFFER_TERRITORY
                val winner = if (offered) target else playerId
                val loser = if (offered) playerId else target
                val area = if (action == MilitaryAction.WITHDRAWAL_TREATY) null else cedable(loser, winner) ?: return false
                val pressure = occupationPressure(war, target)
                val weary = war.weariness[target] ?: 0.0
                if (action == MilitaryAction.REQUEST_TERRITORY && (pressure < 0.15 || weary < PEACE_ACCEPT_WEARINESS)) return false
                if (action == MilitaryAction.WITHDRAWAL_TREATY && war.active && weary < PEACE_ACCEPT_WEARINESS &&
                    pressure < 0.1
                ) {
                    return false
                }
                signTreaty(war, date, area, winner)
            }

            MilitaryAction.MILITARY_AID -> {
                aid[target] = date.plusYears(1)
                wars.filter { it.active && it.side(target) != 0 }.forEach { war ->
                    (if (war.side(target) == 1) war.supportA else war.supportB) += playerId
                }
            }

            MilitaryAction.JOIN_WAR -> {
                val war = wars.first { it.active && it.side(target) != 0 }
                val side = war.side(target)
                (if (side == 1) war.sideA else war.sideB) += playerId
                val enemy = (if (side == 1) war.sideB else war.sideA).first()
                war.fronts += Front(playerId, enemy, 0.0)
                news += WorldNews(date, playerId, war.name, "우리나라가 ${war.name}에 참전했다.", major = true)
            }
        }
        return true
    }

    private fun occupationPressure(
        war: War,
        target: String,
    ): Double =
        war.fronts.maxOfOrNull { front ->
            when (target) {
                front.defender -> front.control
                front.attacker -> -front.control
                else -> 0.0
            }
        } ?: 0.0

    private fun owner(region: Region): String = regionOwners[region.id] ?: region.countryId

    fun regionTreatyCost(
        target: String,
        regionId: String,
    ): Double? {
        val region = regions[regionId] ?: return null
        val holder = owner(region)
        if (regionId in bundledRegions || regionCount.getOrDefault(holder, 0) <= 1 || negotiatingWar(target) == null) return null
        return when (holder) {
            playerId -> MilitaryAction.OFFER_TERRITORY.cost
            target -> MilitaryAction.REQUEST_TERRITORY.cost
            else -> null
        }
    }

    fun negotiateRegion(
        target: String,
        regionId: String,
        date: LocalDate,
    ): Boolean {
        if (regionTreatyCost(target, regionId) == null) return false
        val war = requireNotNull(negotiatingWar(target))
        val region = regions.getValue(regionId)
        val winner = if (owner(region) == playerId) target else playerId
        if (winner == playerId &&
            (occupationPressure(war, target) < 0.15 || (war.weariness[target] ?: 0.0) < PEACE_ACCEPT_WEARINESS)
        ) {
            return false
        }
        signTreaty(war, date, null, winner, region)
        return true
    }

    private fun negotiableRegions(): Map<String, List<TreatyRegion>> {
        val partners = ids.filter { negotiatingWar(it) != null }
        if (partners.isEmpty()) return emptyMap()
        val participants = partners.toSet() + playerId
        return regions.values
            .mapNotNull { region ->
                val holder = owner(region)
                if (holder in participants && region.id !in bundledRegions && regionCount.getValue(holder) > 1) {
                    TreatyRegion(region.id, region.name, holder)
                } else {
                    null
                }
            }.sortedWith(compareBy(TreatyRegion::name, TreatyRegion::id))
            .groupBy(TreatyRegion::owner)
    }

    private fun negotiatingWar(target: String): War? =
        wars.lastOrNull { it.side(playerId) != 0 && it.side(playerId) == -it.side(target) }?.takeIf {
            it.treaty == null &&
                it.oppositePrincipal(playerId) == target
        }

    private fun cedable(
        from: String,
        to: String,
    ): Dispute? =
        disputes.firstOrNull {
            it.controller == from && to in it.claimants && it.claimants.size == 2 && it.treatyRegions.isNotEmpty() &&
                it.treatyRegions.size < regionCount.getOrDefault(from, 0)
        }

    private fun signTreaty(
        war: War,
        date: LocalDate,
        area: Dispute?,
        winner: String,
        region: Region? = null,
    ) {
        check(war.treaty == null)
        if (war.active) war.ceasefire = date
        maritime.clearInactive(::atWar)
        pendingOffers.remove(war.id)
        val transferred = area?.treatyRegions ?: listOfNotNull(region?.id)
        val from = area?.controller ?: region?.let(::owner)
        val title = area?.name ?: region?.name
        war.treaty = PeaceTreaty(date, title, from, from?.let { winner })
        war.fronts.forEach { front ->
            front.control = 0.0
            front.trend = 0.0
        }
        val terms =
            if (from == null) {
                "교전국들이 점령지에서 상호 철수하는 평화협정을 체결했다."
            } else {
                if (area != null) disputes[disputes.indexOf(area)] = area.copy(controller = winner, tension = area.tension * 0.25)
                regionCount[from] = regionCount.getValue(from) - transferred.size
                regionCount[winner] = regionCount.getOrDefault(winner, 0) + transferred.size
                regionOwners =
                    regionOwners.toMutableMap().apply {
                        transferred.forEach { id ->
                            if (regions.getValue(id).countryId == winner) remove(id) else put(id, winner)
                        }
                    }
                "$title 관할이 ${name(winner)}에 귀속됐다. 나머지 점령지에서는 철수한다."
            }
        news += WorldNews(date, winner, war.name, terms, major = true)
    }

    /**
     * 영토 분쟁의 긴장도는 주장국 사이의 관계가 정하는 목표로 서서히 움직이고, 주장국끼리 교전 중이면 치솟는다.
     */
    fun updateDisputes() {
        disputes.replaceAll { dispute ->
            val pairs =
                dispute.claimants.flatMap { a ->
                    (dispute.claimants + dispute.controller).distinct().filter { it > a }.map { a to it }
                }
            val worst = pairs.minOfOrNull { (a, b) -> relation(a, b) } ?: 0.0
            val fighting = pairs.any { (a, b) -> atWar(a, b) }
            val target = (DISPUTE_BASE - worst / 2.0 + if (fighting) DISPUTE_WAR_BONUS else 0.0).coerceIn(0.0, 100.0)
            dispute.copy(tension = dispute.tension + (target - dispute.tension) * DISPUTE_SMOOTHING)
        }
    }

    /**
     * AI 국가의 개전 결정. 인접한 적대국(관계 -70 미만)을 비민주국가가 압도할 때(동맹 몫을 더한 상대 전력의 2.5배)만,
     * 월 0.2%(같은 영토 분쟁의 긴장이 70을 넘으면 3배) 확률로 일어난다. 핵보유국이나 핵보유 동맹국이 지키는 나라는 침공하지 않는다.
     * @return 새로 시작된 (공격국, 수비국)
     */
    fun aiWarDecisions(
        date: LocalDate,
        democratic: (String) -> Boolean?,
        allies: (String) -> List<String>,
    ): List<Pair<String, String>> {
        val busy = wars.filter { it.active }.flatMap { it.sideA + it.sideB }.toSet()
        val started = mutableListOf<Pair<String, String>>()
        ids.forEach { a ->
            // 체제 자료가 없는 소국과 민주국가는 먼저 침공하지 않는다.
            if (a == playerId || a in busy || democratic(a) != false) return@forEach
            geography.of(a).neighbors.sorted().forEach neighbor@{ b ->
                if (b in busy || relation(a, b) >= AI_WAR_RELATION || started.any { a in it.toList() || b in it.toList() }) return@neighbor
                if (recentlyAtWar(a, b, date)) return@neighbor
                val defenders = listOf(b) + allies(b)
                if (defenders.any { (nuclear[it] ?: 0) > 0 }) return@neighbor
                val defense = rawPower(b) + allies(b).sumOf { rawPower(it) * ALLY_SHARE }
                if (rawPower(a) < AI_WAR_POWER_RATIO * defense) return@neighbor
                val disputed =
                    disputes.any {
                        a in it.claimants + it.controller && b in it.claimants + it.controller &&
                            it.tension > DISPUTE_FLASHPOINT
                    }
                val chance = AI_WAR_CHANCE * if (disputed) DISPUTE_WAR_MULTIPLIER else 1.0
                if (random.nextDouble() < chance) {
                    declareWar(a, b, date)
                    started += a to b
                }
            }
        }
        return started
    }

    /**
     * 같은 두 나라 사이에 휴전으로 굳은 전선이 있으면 새 전쟁은 그 휴전선에서 시작한다. 옛 전선의 통제는 새 전선으로 옮겨
     * 점령지가 두 번 세어지지 않게 한다.
     */
    private fun inheritFrozenLine(
        attacker: String,
        defender: String,
    ): Double {
        var control = 0.0
        wars.filter { !it.active }.forEach { war ->
            war.fronts.forEach { front ->
                when {
                    front.attacker == attacker && front.defender == defender -> control += front.control
                    front.attacker == defender && front.defender == attacker -> control -= front.control
                    else -> return@forEach
                }
                front.control = 0.0
            }
        }
        return control.coerceIn(-MAX_CONTROL, MAX_CONTROL)
    }

    /** 휴전 뒤 [REMATCH_YEARS] 안에는 AI가 같은 상대를 다시 침공하지 않는다. */
    private fun recentlyAtWar(
        a: String,
        b: String,
        date: LocalDate,
    ): Boolean =
        wars.any { war ->
            val ended = war.ceasefire ?: return@any false
            war.side(a) != 0 && war.side(b) != 0 && war.side(a) != war.side(b) && ended.plusYears(REMATCH_YEARS) > date
        }

    /** 새 전쟁. 수비국의 동맹국은 관계에 따라 참전한다. */
    fun declareWar(
        attacker: String,
        defender: String,
        date: LocalDate,
    ) {
        val war =
            War(
                id = nextWarId++,
                name = "${name(attacker)}-${name(defender)} 전쟁",
                started = date,
                sideA = mutableSetOf(attacker),
                sideB = mutableSetOf(defender),
                supportA = mutableSetOf(),
                supportB = mutableSetOf(),
                fronts = mutableListOf(Front(attacker, defender, inheritFrozenLine(attacker, defender))),
                intensity = NEW_WAR_INTENSITY,
            )
        ids.filter { it != defender && it != attacker && allied(it, defender) }.forEach { ally ->
            if (relation(ally, attacker) < ALLY_JOIN_RELATION || random.nextDouble() < ALLY_JOIN_CHANCE) {
                war.sideB += ally
                war.fronts += Front(attacker, ally, 0.0)
            } else {
                war.supportB += ally
            }
        }
        wars += war
        news +=
            WorldNews(date, attacker, "전쟁", "${name(attacker).josa("이", "가")} ${name(defender)}에 선전포고해 ${war.name}이 시작됐다.", major = true)
    }

    fun drainNews(): List<WorldNews> = news.toList().also { news.clear() }

    fun snapshot(): MilitarySnapshot =
        MilitarySnapshot(
            maritimeRestrictions = maritime.snapshot(),
            forces =
                ids.associateWith { id ->
                    val total = rawPower(id) / maxPower * 100.0
                    val coastal = geography.of(id).coastal
                    val shares =
                        mapOf(
                            Branch.LAND to 0.45,
                            Branch.NAVAL to if (coastal) 0.25 else 0.02,
                            Branch.AIR to 0.25,
                            Branch.CYBER to 0.05 * (quality[id] ?: 1.0),
                        )
                    val sum = shares.values.sum()
                    ForceSnapshot(
                        countryId = id,
                        power = total,
                        branches = shares.mapValues { it.value / sum * total },
                        personnel = personnel[id] ?: 0.0,
                        readiness = readiness[id] ?: PEACE_READINESS,
                        nuclearWarheads = nuclear[id] ?: 0,
                        spending = spending[id] ?: 0.0,
                    )
                },
            wars =
                wars.map { war ->
                    WarSnapshot(
                        id = war.id,
                        name = war.name,
                        started = war.started,
                        sideA = war.sideA.toList(),
                        sideB = war.sideB.toList(),
                        supportA = war.supportA.toList(),
                        supportB = war.supportB.toList(),
                        fronts = war.fronts.map { FrontSnapshot(it.attacker, it.defender, it.control, it.trend) },
                        intensity = war.intensity,
                        weariness = war.weariness.toMap(),
                        ceasefire = war.ceasefire,
                        treaty = war.treaty,
                    )
                },
            civilWars =
                civilWars.map { war ->
                    CivilWarSnapshot(war.generatedId, war.country, war.rebels, war.started, war.control, war.intensity, war.ended)
                },
            disputes = disputes.toList(),
            mobilization = mobilization,
            regionOwners = regionOwners,
            negotiableRegions = negotiableRegions(),
            recovery = recovery.snapshot,
            reconstructionEffort = recovery.effort,
            aid = aid.toMap(),
        )

    private companion object {
        const val QUALITY_REFERENCE = 20_000.0
        const val QUALITY_EXPONENT = 0.25
        const val MIN_QUALITY = 0.3
        const val MAX_QUALITY = 1.6
        const val INITIAL_CAPITAL_YEARS = 8.0
        const val DEPRECIATION_MONTHS = 180.0
        const val CAPITAL_WEIGHT = 0.7
        const val PEACE_READINESS = 0.5
        const val WAR_READINESS = 0.9
        const val READINESS_SMOOTHING = 0.25
        const val SUPPORT_SHARE = 0.03
        const val ALLY_SHARE = 0.5
        const val DEFENSE_BONUS = 1.3
        const val FRONT_SPEED = 0.01
        const val FRONT_NOISE = 0.002
        const val MAX_CONTROL = 0.95
        const val CASUALTY_RATE = 0.0015
        const val WEARINESS_PER_WEEK = 0.25
        const val CAPITULATION = 0.75
        const val CAPITULATION_MARGIN = 0.3
        const val PEACE_WEARINESS = 60.0
        const val PEACE_CHANCE = 0.1
        const val PEACE_ACCEPT_WEARINESS = 45.0
        const val STATE_ADVANTAGE = 1.1
        const val CIVIL_SPEED = 0.01
        const val CIVIL_NOISE = 0.004
        const val CIVIL_END = 0.02
        const val CIVIL_VICTORY = 0.85
        const val AID_SHARE = 0.002
        const val JOIN_RELATION = 50.0
        const val NEW_WAR_INTENSITY = 0.7
        const val ALLY_JOIN_RELATION = -30.0
        const val ALLY_JOIN_CHANCE = 0.5
        const val DISPUTE_BASE = 30.0
        const val DISPUTE_WAR_BONUS = 25.0
        const val DISPUTE_SMOOTHING = 0.05
        const val DISPUTE_FLASHPOINT = 70.0
        const val DISPUTE_WAR_MULTIPLIER = 3.0
        const val AI_WAR_RELATION = -70.0
        const val AI_WAR_POWER_RATIO = 2.5
        const val AI_WAR_CHANCE = 0.002
        const val REMATCH_YEARS = 5L
        const val NEW_REBEL_CONTROL = 0.05
        const val NEW_CIVIL_INTENSITY = 0.5
        const val OFFER_WEARINESS = 55.0
        const val OFFER_CHANCE = 0.15
        const val OFFER_COOLDOWN_MONTHS = 6L
    }
}
