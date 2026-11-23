package team.themoment.axia.simulation.economy

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

    /** 시작 대비 늘어난 전쟁 강도(0~1). 내전 포함. */
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
    ids: List<String>,
) {
    private val ids = ids.sorted()
    private val n = this.ids.size
    private val structure = StructureData.default
    private val geography = Geography.default
    private val openness = DoubleArray(n) { structure.of(this.ids[it]).tradeOpenness }
    private val exportShare = DoubleArray(n) { structure.of(this.ids[it]).exportShare }
    private val oilRents = DoubleArray(n) { structure.of(this.ids[it]).oilRents }

    /** 거리·인접만 반영한 기본 가중치. */
    private val baseWeight = DoubleArray(n * n)

    /** 현재 상대별 수출 비중(행 합 1). */
    private val shares = DoubleArray(n * n)
    private val tradeLevel = DoubleArray(n) { 1.0 }
    private var oilPrice = 100.0
    private val oilHistory = ArrayDeque(List(12) { 100.0 })

    init {
        for (i in 0 until n) {
            for (j in 0 until n) {
                if (i == j) continue
                val distance = geography.distanceKm(this.ids[i], this.ids[j]).coerceAtLeast(MIN_DISTANCE_KM)
                val neighbor = if (geography.neighbors(this.ids[i], this.ids[j])) NEIGHBOR_BONUS else 1.0
                baseWeight[i * n + j] = neighbor / distance.pow(DISTANCE_ELASTICITY)
            }
        }
    }

    /** 두 나라 사이 교역 가중치. 무역협정은 늘리고 제재는 강도만큼 끊는다. */
    private fun weight(
        state: InternationalState,
        i: Int,
        j: Int,
        gdp: DoubleArray,
    ): Double {
        val a = ids[i]
        val b = ids[j]
        val agreement = if (state.tradeAgreement(a, b)) AGREEMENT_BONUS else 1.0
        val cut = 1.0 - SANCTION_CUT * maxOf(state.sanction(a, b), state.sanction(b, a))
        return baseWeight[i * n + j] * gdp[j].pow(GDP_ELASTICITY) * agreement * cut
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
        val gdpStart = startGdp ?: gdpNow.copyOf().also { startGdp = it }
        // 정책 교역 강도는 시작 GDP로 고정해 계산한다. 상대 경제가 커진 효과와 제재·협정 효과를 섞지 않기 위해서다.
        val policy = DoubleArray(n)
        for (i in 0 until n) {
            var sum = 0.0
            var policySum = 0.0
            for (j in 0 until n) {
                if (i == j) continue
                sum += weight(state, i, j, gdpNow).also { shares[i * n + j] = it }
                policySum += weight(state, i, j, gdpStart)
            }
            for (j in 0 until n) shares[i * n + j] = if (sum > 0.0) shares[i * n + j] / sum else 0.0
            policy[i] = policySum
        }
        val policyStart = startPolicy ?: policy.copyOf().also { startPolicy = it }

        val worldGdp = gdpNow.sum()
        val worldGap = ids.indices.sumOf { gdpNow[it] * gaps.getValue(ids[it]) } / worldGdp
        // 산유국 차질: 석유 지대 × 경제 규모로 본 공급 몫 × (전쟁 강도 + 제재로 끊긴 교역).
        val oilSupply = ids.indices.sumOf { oilRents[it] * gdpNow[it] }
        val disruption =
            ids.indices.sumOf { i ->
                val sanctioned = (1.0 - policy[i] / policyStart[i]).coerceAtLeast(0.0)
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
            val change = policy[i] / policyStart[i] - 1.0
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
                )
        }
    }

    private var startGdp: DoubleArray? = null
    private var startPolicy: DoubleArray? = null
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
    }
}
