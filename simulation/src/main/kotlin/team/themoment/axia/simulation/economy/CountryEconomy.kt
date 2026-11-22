package team.themoment.axia.simulation.economy

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
) {
    private var population = initial.population
    private var populationGrowth = initial.populationGrowth
    private var potentialGrowth = initial.potentialGrowth
    private val growthVolatility = initial.growthVolatility
    val naturalUnemployment = initial.unemployment
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
    private var lastPrimaryBalanceShare = initial.revenue - initial.primarySpending
    private var debt = initial.debt * initial.gdp
    private var interestRate = initial.interestRate
    private var expectedInflation = initial.inflation

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
    val dollarGdp get() = realGdp * dollarPriceLevel

    /** 1인당 실질 GDP(2023년 달러). */
    val realGdpPerCapita get() = realGdp / population

    val populationCount get() = population

    val debtRatio get() = debt / nominalGdp

    /**
     * @param shock 표준정규분포 충격
     * @param dollarInflation 이번 달 달러 물가 상승률. 달러 GDP 환산에 쓴다.
     */
    fun advanceMonth(
        shock: Double,
        dollarInflation: Double,
    ) {
        val startDebtRatio = debtRatio
        val debtDrag = (max(0.0, startDebtRatio - HIGH_DEBT) * DEBT_DRAG).coerceAtMost(MAX_DEBT_DRAG)
        potentialGrowth += (structuralGrowth() - potentialGrowth) / GROWTH_CONVERGENCE_MONTHS
        potentialGdp *= monthly(potentialGrowth + fiscalPolicy.potentialGrowthEffect - debtDrag)

        // 기초재정수지가 나빠지면(지출 증가, 감세) 수요가 늘어 산출 갭이 커진다.
        val shares = fiscalPolicy.advanceMonth(startDebtRatio, outputGap)
        val primaryBalanceShare = shares.revenue - shares.primarySpending
        val fiscalImpulse = FISCAL_MULTIPLIER * (lastPrimaryBalanceShare - primaryBalanceShare)
        lastPrimaryBalanceShare = primaryBalanceShare
        outputGap = (GAP_PERSISTENCE * outputGap + shock * growthVolatility / sqrt(12.0) + fiscalImpulse).coerceIn(MIN_GAP, MAX_GAP)

        realGdpHistory[historyIndex] = realGdp
        historyIndex = (historyIndex + 1) % realGdpHistory.size
        realGdp = potentialGdp * (1.0 + outputGap)

        inflation = (expectedInflation + PHILLIPS_SLOPE * outputGap + fiscalPolicy.inflationImpulse).coerceIn(MIN_INFLATION, MAX_INFLATION)
        expectedInflation += EXPECTATION_ADAPTATION * (inflation - expectedInflation) + ANCHOR_PULL * (inflationAnchor - expectedInflation)
        priceLevel *= monthly(inflation)
        dollarPriceLevel *= monthly(dollarInflation)

        unemployment = (naturalUnemployment - OKUN_COEFFICIENT * outputGap).coerceIn(MIN_UNEMPLOYMENT, MAX_UNEMPLOYMENT)
        populationGrowth += (LONG_RUN_POPULATION_GROWTH - populationGrowth) / DEMOGRAPHIC_TRANSITION_MONTHS
        population *= monthly(populationGrowth)

        val interestTarget =
            initialInterestRate +
                (expectedInflation - initialExpectedInflation) +
                debtPremium(startDebtRatio) - initialDebtPremium
        interestRate += (interestTarget.coerceIn(MIN_RATE, MAX_RATE) - interestRate) / DEBT_ROLLOVER_MONTHS

        val gdp = nominalGdp
        monthlyRevenue = shares.revenue * gdp / 12.0
        monthlyPrimarySpending = shares.primarySpending * (1.0 - STABILIZER * outputGap) * gdp / 12.0
        monthlyInterest = debt * (if (debt >= 0.0) interestRate else minOf(interestRate, MAX_ASSET_RETURN)) / 12.0
        debt -= monthlyBalance
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
