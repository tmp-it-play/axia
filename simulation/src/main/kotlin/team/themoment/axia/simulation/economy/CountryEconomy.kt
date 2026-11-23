package team.themoment.axia.simulation.economy

import team.themoment.axia.simulation.world.StructureData
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 한 국가의 거시경제 상태. 시작값은 2023년 실제 데이터에 고정하고, 매월 그 값에서 벗어나는 변화를 계산한다.
 *
 * 연결 구조: 충격과 재정 충격 → 산출 갭 → 물가, 실업, 재정 → 부채 → 금리, 잠재성장률, 정부의 재정 대응.
 * 세입과 기초지출 비중은 [fiscalPolicy]가 정한다(AI 국가는 자동 대응, 플레이어 국가는 플레이어 예산).
 * 재정과 부채는 자국 통화 기준으로 계산하고, 달러 GDP는 인플레이션 격차만큼 통화가 절하된다고 보고(상대 구매력 평가) 환산한다.
 */
internal class CountryEconomy(
    val id: String,
    initial: InitialEconomy,
    private val fiscalPolicy: FiscalPolicy,
    initialPolicyRate: Double,
    policyRateEstimated: Boolean,
) {
    private var population = initial.population
    private var populationGrowth = initial.populationGrowth
    private var potentialGrowth = initial.potentialGrowth
    private val growthVolatility = initial.growthVolatility
    private val structure = StructureData.default.of(id)
    private val sectors =
        SectorEconomy(
            structure,
            initial.society.incomeGroup,
            initial.society.agricultureEmployment,
            initial.society.industryEmployment,
            initial.society.servicesEmployment,
            structure.oilRents,
            structure.exportShare,
        )
    private val pendingSectorShocks = Sector.entries.associateWith { 0.0 }.toMutableMap()
    private val initialRealGdpPerCapita = initial.gdp / initial.population

    /** 다음 달 한 부문 경기에 더할 충격(가뭄 → 농업 등). */
    fun addSectorShock(
        sector: Sector,
        shock: Double,
    ) {
        pendingSectorShocks[sector] = pendingSectorShocks.getValue(sector) + shock
    }

    /** 산업 부문의 상대 수준(전체 경제 대비, 시작 = 1). */
    fun sectorLevel(sector: Sector): Double = sectors.relativeLevel(sector) / (1.0 + outputGap)

    fun sectorLines(): List<SectorLine> = sectors.lines(realGdp / realGdpHistory[historyIndex] - 1.0)

    val naturalUnemployment = initial.unemployment
    private val initialDebtPremium = debtPremium(initial.debt)
    private val initialInterestRate = initial.interestRate
    private val estimated = if (policyRateEstimated) initial.estimated + POLICY_RATE_FIELD else initial.estimated

    private var potentialGdp = initial.gdp
    private var realGdp = initial.gdp
    private var priceLevel = 1.0
    private var dollarPriceLevel = 1.0
    private var lastPrimaryBalanceShare = initial.revenue - initial.primarySpending
    private var debt = initial.debt * initial.gdp
    private var interestRate = initial.interestRate
    var expectedInflation = initial.inflation
        private set

    /** 통화 긴축이 수요에 닿기까지의 지연을 반영한 실질금리 갭. */
    private var laggedRealRateGap = 0.0

    /** 통화동맹·고정환율 국가의 기준 통화권 대비 물가 수준(로그). 높으면 수출 경쟁력이 떨어져 수요가 준다. */
    private var relativePrice = 0.0

    var policyRate = initialPolicyRate
        private set

    /**
     * 달러 대비 실질환율의 로그 편차(양수 = 시작보다 통화 가치가 높다). 변동환율 국가는 미국과의 실질금리 차가
     * 시작보다 벌어진 만큼 절상되고 3년쯤에 걸쳐 되돌아온다. 고정환율 국가는 기준 통화를 따른다.
     */
    var realExchange = 0.0
        private set
    private val exchangeHistory = ArrayDeque(List(12) { 0.0 })
    private var startRealRateGap: Double? = null

    /** 전쟁으로 생긴 구조적 실업 변화. 목표로 1년쯤에 걸쳐 따라간다. */
    private var warUnemployment = 0.0

    /** 교역·제재·점령으로 정해진 잠재 GDP 수준 배율. */
    private var externalLevel = 1.0

    /** 국가별 특화 시스템이 국채 금리 목표에 더하는 위험 프리미엄(예: 미국 부채한도 위기). 매월 다시 정한다. */
    var riskPremium = 0.0

    /** 다음 달 산출 갭에 한 번 더할 충격(파업 등). */
    private var pendingGapShock = 0.0

    fun addOutputGapShock(shock: Double) {
        pendingGapShock += shock
    }

    /** 다음 달 연율 물가에 한 번 더할 충격(가뭄, 통화 위기 등). */
    private var pendingInflation = 0.0

    fun addInflationShock(shock: Double) {
        pendingInflation += shock
    }

    /** 사건으로 생긴 영구적인 잠재성장률 변화(기술 혁신, 자원 발견). */
    private var growthBonus = 0.0

    fun addPotentialGrowth(delta: Double) {
        growthBonus += delta
    }

    /** 한 번 지출해 부채로 쌓는다(GDP 대비 비율). */
    fun addSpending(share: Double) {
        debt += share * nominalGdp
    }

    /** 채무 재조정으로 부채 일부를 탕감한다. */
    fun relieveDebt(fraction: Double) {
        if (debt > 0.0) debt *= 1.0 - fraction.coerceIn(0.0, 1.0)
    }

    var outputGap = 0.0
        private set
    var inflation = initial.inflation
        private set
    var unemployment = initial.unemployment
        private set

    /** 최근 한 달 재정 흐름(자국 통화, 월간). */
    var monthlyRevenue = initial.revenue * initial.gdp / 12.0
        private set
    var monthlyPrimarySpending = initial.primarySpending * initial.gdp / 12.0
        private set
    var monthlyInterest = initial.debt * initial.interestRate * initial.gdp / 12.0
        private set
    private val monthlyBalance get() = monthlyRevenue - monthlyPrimarySpending - monthlyInterest

    /** 최근 12개월 실질 GDP. [historyIndex]가 가장 오래된 값을 가리킨다. 시작 시점은 2023년 성장률로 거슬러 채운다. */
    private val realGdpHistory = DoubleArray(12) { month -> initial.gdp / (1.0 + initial.growth).pow((12 - month) / 12.0) }
    private var historyIndex = 0

    /** 자국 통화 기준 명목 GDP. 시작 시점의 달러 가치로 표시된 단위다. */
    val nominalGdp get() = realGdp * priceLevel

    /** 달러 기준 명목 GDP. */
    val dollarGdp get() = realGdp * dollarPriceLevel * exp(realExchange)

    /** 1인당 실질 GDP(2023년 달러). */
    val realGdpPerCapita get() = realGdp / population

    val populationCount get() = population

    val debtRatio get() = debt / nominalGdp

    /**
     * @param shock 표준정규분포 충격
     * @param dollarInflation 이번 달 달러 물가 상승률. 달러 GDP 환산에 쓴다.
     * @param monetary 이번 달 중앙은행이 정한 통화 여건
     */
    fun advanceMonth(
        shock: Double,
        dollarInflation: Double,
        monetary: MonetaryConditions,
        external: ExternalConditions = ExternalConditions.NONE,
        exchange: ExchangeInputs = ExchangeInputs.FIXED_TO_DOLLAR,
    ) {
        policyRate = monetary.policyRate
        updateExchange(monetary, exchange)
        warUnemployment += (external.warUnemployment.coerceAtLeast(-naturalUnemployment / 2) - warUnemployment) / WAR_ADJUST_MONTHS
        potentialGdp *= external.potentialLevel / externalLevel
        externalLevel = external.potentialLevel
        val startDebtRatio = debtRatio
        val debtDrag = (max(0.0, startDebtRatio - HIGH_DEBT) * DEBT_DRAG).coerceAtMost(MAX_DEBT_DRAG)
        potentialGrowth += (structuralGrowth() - potentialGrowth) / GROWTH_CONVERGENCE_MONTHS
        potentialGdp *= monthly(potentialGrowth + fiscalPolicy.potentialGrowthEffect + growthBonus - debtDrag)

        // 기초재정수지가 나빠지면(지출 증가, 감세) 수요가 늘어 산출 갭이 커진다.
        val shares = fiscalPolicy.advanceMonth(startDebtRatio, outputGap)
        val primaryBalanceShare = shares.revenue - shares.primarySpending
        val fiscalImpulse = FISCAL_MULTIPLIER * (lastPrimaryBalanceShare - primaryBalanceShare)
        lastPrimaryBalanceShare = primaryBalanceShare

        // 실질금리가 중립 수준보다 높으면(긴축) 지연을 두고 수요가 준다.
        val realRateGap = monetary.effectiveRate - expectedInflation - monetary.neutralRealRate
        laggedRealRateGap += (realRateGap - laggedRealRateGap) / MONETARY_LAG_MONTHS
        val monetaryImpulse = -MONETARY_IMPACT * laggedRealRateGap.coerceIn(-MAX_REAL_RATE_GAP, MAX_REAL_RATE_GAP)
        val competitiveness =
            monetary.anchorInflation?.let { anchor ->
                relativePrice += (inflation - anchor) / 12.0
                -COMPETITIVENESS * relativePrice
            } ?: 0.0
        outputGap =
            (
                GAP_PERSISTENCE * outputGap + shock * growthVolatility / sqrt(12.0) + fiscalImpulse + monetaryImpulse +
                    competitiveness + pendingGapShock + external.gapShock - EXCHANGE_DRAG * realExchange
            ).coerceIn(MIN_GAP, MAX_GAP)
        pendingGapShock = 0.0

        realGdpHistory[historyIndex] = realGdp
        historyIndex = (historyIndex + 1) % realGdpHistory.size
        realGdp = potentialGdp * (1.0 + outputGap)

        sectors.advanceMonth(
            SectorInputs(
                outputGap = outputGap,
                realRateGap = laggedRealRateGap,
                partnerGap = external.partnerGap,
                oilChange = external.oilChange,
                incomeIndex = realGdpPerCapita / initialRealGdpPerCapita,
                policy = fiscalPolicy.sectorPolicy,
                shocks = pendingSectorShocks.toMap(),
            ),
        )
        pendingSectorShocks.keys.forEach { pendingSectorShocks[it] = 0.0 }
        val importPrices = external.inflation - EXCHANGE_PASS_THROUGH * (realExchange - exchangeHistory.first()) + pendingInflation
        pendingInflation = 0.0
        inflation =
            (expectedInflation + PHILLIPS_SLOPE * outputGap + fiscalPolicy.inflationImpulse + importPrices)
                .coerceIn(MIN_INFLATION, MAX_INFLATION)
        // 기대 물가는 신뢰도만큼 기준점에 묶인다. 환율이 고정된 나라는 교역재 가격 때문에 훨씬 강하게 묶인다.
        val anchorPull = if (monetary.anchorInflation == null) ANCHOR_PULL else FIXED_RATE_ANCHOR_PULL
        expectedInflation +=
            EXPECTATION_ADAPTATION * (inflation - expectedInflation) +
            monetary.credibility * anchorPull * (monetary.expectationAnchor - expectedInflation) +
            monetary.expectationShock
        priceLevel *= monthly(inflation)
        dollarPriceLevel *= monthly(dollarInflation)

        unemployment =
            (naturalUnemployment + fiscalPolicy.naturalUnemploymentShift + warUnemployment - OKUN_COEFFICIENT * outputGap)
                .coerceIn(MIN_UNEMPLOYMENT, MAX_UNEMPLOYMENT)
        populationGrowth += (LONG_RUN_POPULATION_GROWTH - populationGrowth) / DEMOGRAPHIC_TRANSITION_MONTHS
        population *= monthly(populationGrowth + fiscalPolicy.populationGrowthShift)

        // 국채 금리는 기준금리 변화의 일부를 따르고, 부채와 통화 신뢰 하락에 위험 프리미엄이 붙는다.
        val interestTarget =
            initialInterestRate +
                RATE_PASS_THROUGH * (monetary.effectiveRate - monetary.initialPolicyRate) +
                debtPremium(startDebtRatio) - initialDebtPremium +
                monetary.credibilityPremium + riskPremium
        interestRate += (interestTarget.coerceIn(MIN_RATE, MAX_RATE) - interestRate) / DEBT_ROLLOVER_MONTHS

        val gdp = nominalGdp
        monthlyRevenue = shares.revenue * gdp / 12.0
        monthlyPrimarySpending = shares.primarySpending * (1.0 - STABILIZER * outputGap) * gdp / 12.0
        monthlyInterest = debt * (if (debt >= 0.0) interestRate else minOf(interestRate, MAX_ASSET_RETURN)) / 12.0
        debt -= monthlyBalance
    }

    private fun updateExchange(
        monetary: MonetaryConditions,
        exchange: ExchangeInputs,
    ) {
        exchangeHistory.removeFirst()
        exchangeHistory.addLast(realExchange)
        val anchor = exchange.anchor
        if (anchor != null) {
            realExchange = anchor
            return
        }
        val gap = (monetary.effectiveRate - expectedInflation) - exchange.usRealRate
        val start = startRealRateGap ?: gap.also { startRealRateGap = it }
        // 초고물가국은 기대 물가가 내려가며 실질금리가 수십 %p 움직이므로, 환율에 전하는 금리 차는 ±5%p로 묶는다.
        realExchange +=
            CARRY * (gap - start).coerceIn(-MAX_CARRY_GAP, MAX_CARRY_GAP) / 12.0 - realExchange / EXCHANGE_REVERSION_MONTHS +
            exchange.noise * EXCHANGE_VOLATILITY
        realExchange = realExchange.coerceIn(-MAX_EXCHANGE, MAX_EXCHANGE)
    }

    fun indicators(): EconomyIndicators {
        val gdp = nominalGdp
        return EconomyIndicators(
            population = population,
            gdp = dollarGdp,
            growth = realGdp / realGdpHistory[historyIndex] - 1.0,
            inflation = inflation,
            unemployment = unemployment,
            budgetBalance = monthlyBalance * 12.0 / gdp,
            debtRatio = debt / gdp,
            policyRate = policyRate,
            realExchange = realExchange,
            outputGap = outputGap,
            estimated = estimated,
        )
    }

    /** 장기 생산성 증가 + 인구 증가 + 선진국 소득 수준과의 격차만큼의 따라잡기 성장. */
    private fun structuralGrowth(): Double {
        val realIncomePerPerson = potentialGdp / population
        val catchUp = (CATCH_UP * ln(FRONTIER_INCOME / realIncomePerPerson)).coerceIn(0.0, MAX_CATCH_UP)
        return LONG_RUN_PRODUCTIVITY + populationGrowth + fiscalPolicy.populationGrowthShift + catchUp
    }

    private fun monthly(annualRate: Double) = (1.0 + annualRate).pow(1.0 / 12.0)

    private fun debtPremium(debtRatio: Double) = max(0.0, debtRatio - HIGH_DEBT) * DEBT_PREMIUM

    private companion object {
        const val GAP_PERSISTENCE = 0.94

        /** 미국과의 실질금리 차 1%p가 1년에 올리는 실질환율(로그). */
        const val CARRY = 2.0
        const val MAX_CARRY_GAP = 0.05
        const val WAR_ADJUST_MONTHS = 12.0
        const val EXCHANGE_REVERSION_MONTHS = 36.0
        const val EXCHANGE_VOLATILITY = 0.01
        const val MAX_EXCHANGE = 1.0

        /** 실질환율이 10% 고평가되면 매월 산출 갭에서 0.04%p를 뺀다. */
        const val EXCHANGE_DRAG = 0.004

        /** 실질환율의 전년 대비 변화가 연율 물가로 전가되는 비율. */
        const val EXCHANGE_PASS_THROUGH = 0.1
        const val PHILLIPS_SLOPE = 0.25
        const val EXPECTATION_ADAPTATION = 0.08

        /** 신뢰도 1일 때 기대 물가가 매월 물가 목표 쪽으로 끌리는 비율. */
        const val ANCHOR_PULL = 0.012
        const val FIXED_RATE_ANCHOR_PULL = 0.06

        /** 실질금리 갭 1%p가 매월 산출 갭에서 빼는 비율. 유지하면 장기적으로 갭이 약 0.75%p 준다. */
        const val MONETARY_IMPACT = 0.045
        const val MONETARY_LAG_MONTHS = 6.0
        const val MAX_REAL_RATE_GAP = 0.2

        /** 기준 통화권보다 물가 수준이 10% 높으면 매월 산출 갭에서 0.1%p를 뺀다. */
        const val COMPETITIVENESS = 0.01
        const val RATE_PASS_THROUGH = 0.7
        const val LONG_RUN_PRODUCTIVITY = 0.012
        const val CATCH_UP = 0.008
        const val MAX_CATCH_UP = 0.02

        /** 따라잡기 성장의 기준이 되는 선진국 1인당 실질 소득(2023년 달러). */
        const val FRONTIER_INCOME = 80_000.0
        const val GROWTH_CONVERGENCE_MONTHS = 300.0
        const val LONG_RUN_POPULATION_GROWTH = 0.0
        const val DEMOGRAPHIC_TRANSITION_MONTHS = 480.0
        const val OKUN_COEFFICIENT = 0.4
        const val STABILIZER = 0.4
        const val HIGH_DEBT = 0.9
        const val DEBT_DRAG = 0.01
        const val MAX_DEBT_DRAG = 0.03
        const val FISCAL_MULTIPLIER = 0.6
        const val MIN_GAP = -0.3
        const val MAX_GAP = 0.3

        /** 순자산(부채 < 0)이 버는 연 수익률 상한. */
        const val MAX_ASSET_RETURN = 0.05
        const val DEBT_PREMIUM = 0.04
        const val DEBT_ROLLOVER_MONTHS = 60.0
        const val MIN_RATE = 0.002
        const val MAX_RATE = 0.4
        const val MIN_INFLATION = -0.1
        const val MAX_INFLATION = 5.0
        const val MIN_UNEMPLOYMENT = 0.005
        const val MAX_UNEMPLOYMENT = 0.5
    }
}

/** 환율 결정 재료. [anchor]가 있으면 그 값(기준 통화권의 실질환율)을 그대로 따른다. */
internal data class ExchangeInputs(
    val usRealRate: Double,
    val anchor: Double?,
    /** 표준정규분포 충격. */
    val noise: Double,
) {
    companion object {
        val FIXED_TO_DOLLAR = ExchangeInputs(0.0, 0.0, 0.0)
    }
}
