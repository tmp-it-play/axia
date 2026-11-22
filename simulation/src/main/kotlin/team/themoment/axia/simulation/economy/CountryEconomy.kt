package team.themoment.axia.simulation.economy

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 한 국가의 거시경제 상태. 시작값은 2023년 실제 데이터에 고정하고, 매월 그 값에서 벗어나는 변화를 계산한다.
 *
 * 연결 구조: 충격 → 산출 갭 → 물가, 실업, 재정 → 부채 → 금리, 잠재성장률, 정부의 재정 대응.
 * 재정과 부채는 자국 통화 기준으로 계산하고, 달러 GDP는 인플레이션 격차만큼 통화가 절하된다고 보고(상대 구매력 평가) 환산한다.
 */
internal class CountryEconomy(
    val id: String,
    initial: InitialEconomy,
) {
    private var population = initial.population
    private var populationGrowth = initial.populationGrowth
    private var potentialGrowth = initial.potentialGrowth
    private val growthVolatility = initial.growthVolatility
    private val naturalUnemployment = initial.unemployment
    private val initialRevenueShare = initial.revenue
    private var revenueShare = initial.revenue
    private val initialPrimarySpendingShare = initial.primarySpending
    private val initialDebtRatio = initial.debt
    private val inflationAnchor =
        if (initial.inflation <= MODERATE_INFLATION) INFLATION_TARGET else (initial.inflation * 0.3).coerceIn(0.03, MAX_ANCHOR)
    private val initialExpectedInflation = initial.inflation
    private val initialDebtPremium = debtPremium(initial.debt)
    private val initialInterestRate = initial.interestRate
    private val estimated = initial.estimated

    private var potentialGdp = initial.gdp
    private var realGdp = initial.gdp
    private var priceLevel = 1.0
    private var dollarPriceLevel = 1.0
    private var primarySpendingShare = initial.primarySpending
    private var outputGap = 0.0
    private var inflation = initial.inflation
    private var expectedInflation = initial.inflation
    private var unemployment = initial.unemployment
    private var debt = initial.debt * initial.gdp
    private var interestRate = initial.interestRate
    private var monthlyBalance = (initial.revenue - initial.primarySpending - initial.debt * initial.interestRate) * initial.gdp / 12.0

    /** 최근 12개월 실질 GDP. [historyIndex]가 가장 오래된 값을 가리킨다. 시작 시점은 2023년 성장률로 거슬러 채운다. */
    private val realGdpHistory = DoubleArray(12) { month -> initial.gdp / (1.0 + initial.growth).pow((12 - month) / 12.0) }
    private var historyIndex = 0

    /** 자국 통화 기준 명목 GDP. 시작 시점의 달러 가치로 표시된 단위다. */
    private val nominalGdp get() = realGdp * priceLevel

    val inflationRate: Double get() = inflation

    /**
     * @param shock 표준정규분포 충격
     * @param dollarInflation 이번 달 달러 물가 상승률. 달러 GDP 환산에 쓴다.
     */
    fun advanceMonth(
        shock: Double,
        dollarInflation: Double,
    ) {
        val debtRatio = debt / nominalGdp
        val debtDrag = (max(0.0, debtRatio - HIGH_DEBT) * DEBT_DRAG).coerceAtMost(MAX_DEBT_DRAG)
        potentialGrowth += (structuralGrowth() - potentialGrowth) / GROWTH_CONVERGENCE_MONTHS
        potentialGdp *= monthly(potentialGrowth - debtDrag)
        outputGap = GAP_PERSISTENCE * outputGap + shock * growthVolatility / sqrt(12.0)

        realGdpHistory[historyIndex] = realGdp
        historyIndex = (historyIndex + 1) % realGdpHistory.size
        realGdp = potentialGdp * (1.0 + outputGap)

        inflation = (expectedInflation + PHILLIPS_SLOPE * outputGap).coerceIn(MIN_INFLATION, MAX_INFLATION)
        expectedInflation += EXPECTATION_ADAPTATION * (inflation - expectedInflation) + ANCHOR_PULL * (inflationAnchor - expectedInflation)
        priceLevel *= monthly(inflation)
        dollarPriceLevel *= monthly(dollarInflation)

        unemployment = (naturalUnemployment - OKUN_COEFFICIENT * outputGap).coerceIn(MIN_UNEMPLOYMENT, MAX_UNEMPLOYMENT)
        populationGrowth += (LONG_RUN_POPULATION_GROWTH - populationGrowth) / DEMOGRAPHIC_TRANSITION_MONTHS
        population *= monthly(populationGrowth)

        val interestTarget =
            initialInterestRate +
                (expectedInflation - initialExpectedInflation) +
                debtPremium(debtRatio) - initialDebtPremium
        interestRate += (interestTarget.coerceIn(MIN_RATE, MAX_RATE) - interestRate) / DEBT_ROLLOVER_MONTHS

        // 정부의 재정 대응: 부채가 시작 수준보다 늘면 지출을 줄이고 세입을 늘린다. 줄면 약하게 지출을 늘리고,
        // 순자산 상태(부채 < 0)면 쌓인 자산의 수익을 적극적으로 쓴다.
        val debtGap = debtRatio - initialDebtRatio
        val easing = if (debtRatio < 0.0) NET_ASSET_EASING else FISCAL_EASING
        val spendingTarget = initialPrimarySpendingShare - debtGap * if (debtGap > 0.0) FISCAL_RESPONSE else easing
        val revenueTarget = initialRevenueShare + max(0.0, debtGap) * TAX_RESPONSE
        primarySpendingShare += (spendingTarget.coerceAtLeast(MIN_SPENDING_SHARE) - primarySpendingShare) / FISCAL_ADJUSTMENT_MONTHS
        revenueShare += (revenueTarget.coerceAtMost(max(initialRevenueShare, MAX_REVENUE_SHARE)) - revenueShare) / FISCAL_ADJUSTMENT_MONTHS

        val gdp = nominalGdp
        val revenue = revenueShare * gdp / 12.0
        val primarySpending = primarySpendingShare * (1.0 - STABILIZER * outputGap) * gdp / 12.0
        val interest = debt * (if (debt >= 0.0) interestRate else minOf(interestRate, MAX_ASSET_RETURN)) / 12.0
        monthlyBalance = revenue - primarySpending - interest
        debt -= monthlyBalance
    }

    fun indicators(): EconomyIndicators {
        val gdp = nominalGdp
        return EconomyIndicators(
            population = population,
            gdp = realGdp * dollarPriceLevel,
            growth = realGdp / realGdpHistory[historyIndex] - 1.0,
            inflation = inflation,
            unemployment = unemployment,
            budgetBalance = monthlyBalance * 12.0 / gdp,
            debtRatio = debt / gdp,
            estimated = estimated,
        )
    }

    /** 장기 생산성 증가 + 인구 증가 + 선진국 소득 수준과의 격차만큼의 따라잡기 성장. */
    private fun structuralGrowth(): Double {
        val realIncomePerPerson = potentialGdp / population
        val catchUp = (CATCH_UP * ln(FRONTIER_INCOME / realIncomePerPerson)).coerceIn(0.0, MAX_CATCH_UP)
        return LONG_RUN_PRODUCTIVITY + populationGrowth + catchUp
    }

    private fun monthly(annualRate: Double) = (1.0 + annualRate).pow(1.0 / 12.0)

    private fun debtPremium(debtRatio: Double) = max(0.0, debtRatio - HIGH_DEBT) * DEBT_PREMIUM

    private companion object {
        const val GAP_PERSISTENCE = 0.94
        const val PHILLIPS_SLOPE = 0.25
        const val EXPECTATION_ADAPTATION = 0.08
        const val ANCHOR_PULL = 0.01
        const val INFLATION_TARGET = 0.02
        const val MODERATE_INFLATION = 0.06
        const val MAX_ANCHOR = 0.08
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
        const val FISCAL_RESPONSE = 0.1
        const val FISCAL_EASING = 0.03
        const val NET_ASSET_EASING = 0.1

        /** 순자산(부채 < 0)이 버는 연 수익률 상한. */
        const val MAX_ASSET_RETURN = 0.05
        const val TAX_RESPONSE = 0.05
        const val MAX_REVENUE_SHARE = 0.6
        const val FISCAL_ADJUSTMENT_MONTHS = 24.0
        const val MIN_SPENDING_SHARE = 0.03
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
