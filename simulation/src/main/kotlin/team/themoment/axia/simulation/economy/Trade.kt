package team.themoment.axia.simulation.economy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.world.Geography
import team.themoment.axia.simulation.world.StructureData
import kotlin.math.ln
import kotlin.math.pow

/** 무역·제재·전쟁 상태. 외교와 군사 시스템이 구현한다. */
internal interface InternationalState {
    /** [by]가 [target]에 건 제재 강도(0~1). */
    fun sanction(
        by: String,
        target: String,
    ): Double

    fun tradeAgreement(
        a: String,
        b: String,
    ): Boolean

    /** 시작 대비 전쟁 강도 변화(-1~1). 내전 포함. 시작 때 치르던 전쟁이 끝나면 음수다. */
    fun warIntensity(country: String): Double

    /** 시작 대비 늘어난 점령 비율(되찾으면 음수). */
    fun occupied(country: String): Double

    companion object {
        val PEACEFUL =
            object : InternationalState {
                override fun sanction(
                    by: String,
                    target: String,
                ) = 0.0

                override fun tradeAgreement(
                    a: String,
                    b: String,
                ) = false

                override fun warIntensity(country: String) = 0.0

                override fun occupied(country: String) = 0.0
            }
    }
}

/** 한 나라가 이번 달 국제 환경에서 받는 영향. 거시 모델이 한 달 동안 쓴다. */
internal data class ExternalConditions(
    /** 산출 갭에 더하는 충격(교역 상대 경기, 제재, 유가, 전쟁). */
    val gapShock: Double,
    /** 연율 물가에 더하는 몫(유가, 제재로 인한 수입 물가). */
    val inflation: Double,
    /** 잠재 GDP 수준의 목표 배율(제재·무역협정에 따른 교역 이득·손실, 점령). 시작 = 1. */
    val potentialLevel: Double,
    /** 수출 비중으로 가중한 교역 상대의 산출 갭. 수출 부문 경기에 쓴다. */
    val partnerGap: Double = 0.0,
    /** 유가의 전년 대비 로그 변화. */
    val oilChange: Double = 0.0,
    /** 전쟁 동원·피란으로 생긴 구조적 실업 변화의 목표(시작 대비). */
    val warUnemployment: Double = 0.0,
) {
    companion object {
        val NONE = ExternalConditions(0.0, 0.0, 1.0)
    }
}

data class TradeSnapshot(
    /** 유가 지수(2023년 평균 = 100). */
    val oilPrice: Double,
    /** 나라별 주요 교역 상대와 수출 비중. */
    val partners: Map<String, List<Pair<String, Double>>>,
    /** 시작 대비 제재·무역협정으로 바뀐 교역 비율(-1~). */
    val tradeChange: Map<String, Double>,
)

/**
 * 국가 간 교역(중력 모형)과 국제 유가.
 *
 * - 교역 비중: 상대 GDP^0.9 ÷ 거리, 인접국과 무역협정 상대는 가중, 제재는 강도만큼 끊는다.
 *   총교역은 World Bank 교역 개방도(GDP 대비)로 정한다.
 * - 2023년 데이터에는 이미 기존 제재와 협정의 효과가 들어 있으므로, 시작 대비 바뀐 만큼만 경제에 반영한다.
 * - 교역 상대의 경기는 수출 비중만큼 전해진다.
 * - 유가는 세계 경기와 산유국의 전쟁·제재로 움직이고, 수입국 물가와 산유국 경기에 영향을 준다.
 */
internal class TradeNetwork(
    initialGdp: Map<String, Double>,
    data: TradeData = TradeData.default,
) {
    private val ids = initialGdp.keys.sorted()
    private val n = ids.size
    private val index = ids.withIndex().associate { it.value to it.index }
    private val structure = StructureData.default
    private val geography = Geography.default
    private val openness = DoubleArray(n) { structure.of(ids[it]).tradeOpenness }
    private val exportShare = DoubleArray(n) { structure.of(ids[it]).exportShare }
    private val oilRents = DoubleArray(n) { structure.of(ids[it]).oilRents }
    private val startGdp = DoubleArray(n) { initialGdp.getValue(ids[it]) }

    /** 시작 시점 상대별 수출 비중(행 합 1). 실측(UN Comtrade)이 있으면 그 값, 빈 곳은 중력 모형으로 채운다. */
    private val baseShare = DoubleArray(n * n)

    /** 현재 상대별 수출 비중(행 합 1). */
    private val shares = DoubleArray(n * n)
    private val tradeLevel = DoubleArray(n) { 1.0 }
    private var oilPrice = 100.0
    private val oilHistory = ArrayDeque(List(12) { 100.0 })
    private var startFactor: DoubleArray? = null

    init {
        for (i in 0 until n) {
            val observed =
                data.countries[ids[i]]
                    ?.partners
                    .orEmpty()
                    .filterKeys { it in index && it != ids[i] }
            val gravity = DoubleArray(n)
            for (j in 0 until n) {
                if (i == j || ids[j] in observed) continue
                val distance = geography.distanceKm(ids[i], ids[j]).coerceAtLeast(MIN_DISTANCE_KM)
                val neighbor = if (geography.neighbors(ids[i], ids[j])) NEIGHBOR_BONUS else 1.0
                gravity[j] = neighbor * startGdp[j].pow(GDP_ELASTICITY) / distance.pow(DISTANCE_ELASTICITY)
            }
            val residual = (1.0 - observed.values.sum()).coerceAtLeast(MIN_RESIDUAL)
            val gravitySum = gravity.sum()
            for (j in 0 until n) baseShare[i * n + j] = if (gravitySum > 0.0) residual * gravity[j] / gravitySum else 0.0
            observed.forEach { (partner, share) -> baseShare[i * n + index.getValue(partner)] = share }
            val total = (0 until n).sumOf { baseShare[i * n + it] }
            for (j in 0 until n) baseShare[i * n + j] /= total
        }
        baseShare.copyInto(shares)
    }

    /** [a]의 수출 중 [b]로 가는 시작 시점 비중. 외교 관계의 경제적 연결로도 쓴다. */
    fun baseShare(
        a: String,
        b: String,
    ): Double = baseShare[index.getValue(a) * n + index.getValue(b)]

    /** 협정·제재에 따른 교역 배율. 시작 대비 비율로만 쓴다. */
    private fun factor(
        state: InternationalState,
        i: Int,
        j: Int,
    ): Double {
        val a = ids[i]
        val b = ids[j]
        val agreement = if (state.tradeAgreement(a, b)) AGREEMENT_BONUS else 1.0
        return agreement * (1.0 - SANCTION_CUT * maxOf(state.sanction(a, b), state.sanction(b, a)))
    }

    /**
     * 이번 달 나라별 외부 여건을 계산한다.
     * @param gaps 지난달 산출 갭
     * @param gdp 달러 GDP
     */
    fun advanceMonth(
        state: InternationalState,
        gaps: Map<String, Double>,
        gdp: Map<String, Double>,
    ): Map<String, ExternalConditions> {
        val gdpNow = DoubleArray(n) { gdp.getValue(ids[it]) }
        val now = DoubleArray(n * n)
        for (i in 0 until n) for (j in 0 until n) if (i != j) now[i * n + j] = factor(state, i, j)
        val start = startFactor ?: now.copyOf().also { startFactor = it }
        // 정책 교역 강도: 시작 비중 × (협정·제재 배율의 시작 대비 변화). 상대 경제 성장 효과는 넣지 않는다.
        val policy = DoubleArray(n)
        for (i in 0 until n) {
            var sum = 0.0
            var policySum = 0.0
            for (j in 0 until n) {
                if (i == j) continue
                val k = i * n + j
                val change = now[k] / start[k]
                policySum += baseShare[k] * change
                shares[k] = baseShare[k] * change * (gdpNow[j] / startGdp[j]).pow(GDP_ELASTICITY)
                sum += shares[k]
            }
            for (j in 0 until n) shares[i * n + j] = if (sum > 0.0) shares[i * n + j] / sum else 0.0
            policy[i] = policySum
        }

        val worldGdp = gdpNow.sum()
        val worldGap = ids.indices.sumOf { gdpNow[it] * gaps.getValue(ids[it]) } / worldGdp
        // 산유국 차질: 석유 지대 × 경제 규모로 본 공급 몫 × (전쟁 강도 + 제재로 끊긴 교역).
        val oilSupply = ids.indices.sumOf { oilRents[it] * gdpNow[it] }
        val disruption =
            ids.indices.sumOf { i ->
                val sanctioned = (1.0 - policy[i]).coerceAtLeast(0.0)
                oilRents[i] * gdpNow[i] * (state.warIntensity(ids[i]) * WAR_OIL_LOSS + sanctioned)
            } / oilSupply
        val disruptionStart = startDisruption ?: disruption.also { startDisruption = it }
        val target = BASE_OIL * (1.0 + OIL_GAP_ELASTICITY * worldGap) * (1.0 + OIL_DISRUPTION_ELASTICITY * (disruption - disruptionStart))
        oilPrice += (target.coerceIn(MIN_OIL, MAX_OIL) - oilPrice) * OIL_SMOOTHING
        val oilYearAgo = oilHistory.removeFirst()
        oilHistory.addLast(oilPrice)
        val oilInflation = ln(oilPrice / oilYearAgo)

        return ids.indices.associate { i ->
            val id = ids[i]
            val change = policy[i] - 1.0
            val levelTarget = 1.0 + openness[i].coerceAtMost(MAX_OPENNESS) * TRADE_GAIN * change
            tradeLevel[i] += (levelTarget - tradeLevel[i]) / TRADE_ADJUST_MONTHS
            val partnerGap = ids.indices.sumOf { j -> shares[i * n + j] * gaps.getValue(ids[j]) }
            // 산유국은 유가가 오르면 경기가 좋아지고, 수입국은 나빠진다.
            val oilExposure = 1.0 - oilRents[i] * OIL_EXPORTER_GAIN
            id to
                ExternalConditions(
                    gapShock =
                        SPILLOVER * exportShare[i].coerceAtMost(1.0) * partnerGap - oilExposure * OIL_GAP * oilInflation -
                            WAR_GAP * state.warIntensity(id),
                    inflation = OIL_PASS_THROUGH * oilInflation * (1.0 - oilRents[i] * 2.0).coerceIn(0.2, 1.0),
                    potentialLevel = tradeLevel[i] * (1.0 - state.occupied(id) * OCCUPATION_OUTPUT),
                    partnerGap = partnerGap,
                    oilChange = oilInflation,
                    warUnemployment = WAR_UNEMPLOYMENT * state.warIntensity(id),
                )
        }
    }

    private var startDisruption: Double? = null

    fun snapshot(): TradeSnapshot =
        TradeSnapshot(
            oilPrice = oilPrice,
            partners =
                ids.indices.associate { i ->
                    ids[i] to
                        ids.indices
                            .filter { it != i }
                            .sortedByDescending { shares[i * n + it] }
                            .take(TOP_PARTNERS)
                            .map { ids[it] to shares[i * n + it] }
                },
            tradeChange = ids.indices.associate { ids[it] to tradeLevel[it] - 1.0 },
        )

    private companion object {
        const val MIN_DISTANCE_KM = 1000.0

        /** 실측 상대가 비중을 거의 다 차지해도 중력 모형 몫을 조금 남긴다(새 협정 상대가 생길 수 있도록). */
        const val MIN_RESIDUAL = 0.02

        /** 중심점 거리는 이웃 나라 사이를 과장하므로 표준값(1)보다 낮게 둔다. */
        const val DISTANCE_ELASTICITY = 0.8
        const val NEIGHBOR_BONUS = 3.0
        const val GDP_ELASTICITY = 0.9
        const val AGREEMENT_BONUS = 1.4
        const val SANCTION_CUT = 0.85
        const val MAX_OPENNESS = 1.5

        /** 교역 1% 변화가 개방도 1인 나라의 잠재 GDP 수준을 바꾸는 비율. */
        const val TRADE_GAIN = 0.15
        const val TRADE_ADJUST_MONTHS = 18.0
        const val SPILLOVER = 0.015
        const val BASE_OIL = 100.0
        const val MIN_OIL = 30.0
        const val MAX_OIL = 300.0
        const val OIL_GAP_ELASTICITY = 3.0
        const val OIL_DISRUPTION_ELASTICITY = 4.0
        const val WAR_OIL_LOSS = 0.3
        const val OIL_SMOOTHING = 0.2
        const val OIL_PASS_THROUGH = 0.04
        const val OIL_EXPORTER_GAIN = 6.0
        const val OIL_GAP = 0.001
        const val OCCUPATION_OUTPUT = 0.8
        const val TOP_PARTNERS = 8

        /** 새 전쟁 강도 1이 매월 산출 갭에서 빼는 비율. 유지되면 갭이 약 -3%까지 내려간다. */
        const val WAR_GAP = 0.002

        /** 전쟁 강도 1이 구조적 실업률에 더하는 몫. 2023년 전시 국가는 휴전하면 그만큼 내려간다. */
        const val WAR_UNEMPLOYMENT = 0.1
    }
}

@Serializable
internal data class TradePartners(
    val source: String,
    val partners: Map<String, Double>,
)

/** 국가별 상품 수출 상대 비중(trade.json, UN Comtrade). */
@Serializable
internal data class TradeData(
    val sourceNote: String,
    val countries: Map<String, TradePartners>,
) {
    companion object {
        val default: TradeData by lazy {
            val text =
                requireNotNull(TradeData::class.java.getResourceAsStream("/world/trade.json")) { "Missing trade.json" }
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            Json.decodeFromString<TradeData>(text)
        }
    }
}
