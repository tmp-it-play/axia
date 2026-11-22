package team.themoment.axia.simulation.nation

import team.themoment.axia.simulation.economy.CountryEconomy
import team.themoment.axia.simulation.economy.InitialEconomy
import team.themoment.axia.simulation.history.MonthlySeries
import java.time.YearMonth
import kotlin.math.pow

data class GroupSnapshot(
    val group: PopulationGroup,
    val population: Double,
    val share: Double,
    /** 1인당 연간 소득(현재 달러). */
    val income: Double,
    val satisfaction: Double,
    val factors: Map<SatisfactionFactor, Double>,
)

/** 재정 현황. 비율은 모두 GDP 대비(연율)다. */
data class BudgetSnapshot(
    val taxes: List<TaxLine>,
    val otherRevenue: Double,
    val spending: List<SpendingLine>,
    val revenue: Double,
    val primarySpending: Double,
    val interest: Double,
    val balance: Double,
    val debtRatio: Double,
    /** 달러 기준 명목 GDP. 비율을 금액으로 바꿀 때 쓴다. */
    val gdp: Double,
)

data class NationHistory(
    val start: YearMonth,
    val approval: List<Double>,
    val satisfaction: Map<PopulationGroup, List<Double>>,
)

data class NationSnapshot(
    val countryId: String,
    val approval: Double,
    /** 여론조사 자료가 없어 시작 지지율이 50%가 되도록 정한 기준선. */
    val baseline: Double,
    val approvalFactors: Map<SatisfactionFactor, Double>,
    val groups: List<GroupSnapshot>,
    val budget: BudgetSnapshot,
    val history: NationHistory,
)

/**
 * 플레이어 국가의 인구 집단, 예산, 지지율. 1c에서는 국가 단위로 계산한다(지역 분포는 이후).
 *
 * 집단 만족도 = 기준선 + 요인별 기여도. 기여도는 매달 목표값으로 천천히 움직인다(지연).
 * 지지율 = 집단 만족도를 인구 × 영향력으로 가중 평균한 값.
 */
class Nation internal constructor(
    initial: InitialEconomy,
    private val startMonth: YearMonth,
) {
    val countryId = initial.id
    internal val budget = PlayerBudget(initial)

    private val composition = composeGroups(initial.society, initial.gdp, initial.population)
    private val adultShare = 1.0 - initial.society.childShare
    private val groups =
        PopulationGroup.entries.map { group ->
            GroupState(group, composition.shares.getValue(group), composition.incomes.getValue(group), initial.growth)
        }
    private var initialRealGdpPerCapita = 0.0
    private var baseline = BASELINE
    private val approvalSeries = MonthlySeries()
    private val satisfactionSeries = PopulationGroup.entries.associateWith { MonthlySeries() }

    internal fun start(economy: CountryEconomy) {
        initialRealGdpPerCapita = economy.realGdpPerCapita
        groups.forEach { it.start(burdenOf(it.group)) }
        update(economy, smoothing = 1.0)
        baseline = BASELINE + (BASELINE - weighted { it.satisfaction(BASELINE) })
        record()
    }

    internal fun advanceMonth(economy: CountryEconomy) {
        update(economy, smoothing = FACTOR_SMOOTHING)
        record()
    }

    internal fun snapshot(economy: CountryEconomy): NationSnapshot {
        val adults = economy.populationCount * adultShare
        val dollarPerReal = economy.dollarGdp / (economy.realGdpPerCapita * economy.populationCount)
        val gdp = economy.nominalGdp
        return NationSnapshot(
            countryId = countryId,
            approval = approval(),
            baseline = baseline,
            approvalFactors = SatisfactionFactor.entries.associateWith { factor -> weighted { it.factor(factor) } },
            groups =
                groups.map { state ->
                    GroupSnapshot(
                        group = state.group,
                        population = adults * state.share,
                        share = state.share,
                        income = state.realIncome * dollarPerReal,
                        satisfaction = state.satisfaction(baseline),
                        factors = SatisfactionFactor.entries.associateWith(state::factor),
                    )
                },
            budget =
                BudgetSnapshot(
                    taxes = budget.taxLines(),
                    otherRevenue = budget.otherRevenue,
                    spending = budget.spendingLines(),
                    revenue = economy.monthlyRevenue * 12.0 / gdp,
                    primarySpending = economy.monthlyPrimarySpending * 12.0 / gdp,
                    interest = economy.monthlyInterest * 12.0 / gdp,
                    balance = (economy.monthlyRevenue - economy.monthlyPrimarySpending - economy.monthlyInterest) * 12.0 / gdp,
                    debtRatio = economy.debtRatio,
                    gdp = economy.dollarGdp,
                ),
            history =
                NationHistory(
                    start = startMonth.plusMonths(approvalSeries.dropped.toLong()),
                    approval = approvalSeries.toList(),
                    satisfaction = satisfactionSeries.mapValues { it.value.toList() },
                ),
        )
    }

    private fun update(
        economy: CountryEconomy,
        smoothing: Double,
    ) {
        val incomeIndex = economy.realGdpPerCapita / initialRealGdpPerCapita
        val socialChange = budget.level(SpendingKind.SOCIAL) - budget.initialLevel(SpendingKind.SOCIAL)
        groups.forEach { state ->
            val group = state.group
            val burden = burdenOf(group)
            state.updateIncome(incomeIndex, burden, 1.0 + group.transferWeight * socialChange)
            val excessInflation = economy.inflation - INFLATION_COMFORT
            val targets =
                mapOf(
                    SatisfactionFactor.PRICES to
                        group.priceSensitivity *
                        if (excessInflation > 0.0) -PRICE_WEIGHT * excessInflation else -DEFLATION_BONUS * PRICE_WEIGHT * excessInflation,
                    SatisfactionFactor.JOBS to -JOB_WEIGHT * group.jobSensitivity * (economy.unemployment - economy.naturalUnemployment),
                    SatisfactionFactor.INCOME to INCOME_WEIGHT * state.yearOverYearIncomeGrowth(),
                    SatisfactionFactor.TAXES to -TAX_WEIGHT * (burden - state.initialBurden),
                    SatisfactionFactor.SERVICES to SERVICE_WEIGHT * serviceChange(group),
                    SatisfactionFactor.ECONOMY to ECONOMY_WEIGHT * group.economySensitivity * economy.outputGap,
                ).mapValues { (factor, value) -> value.coerceIn(FACTOR_LIMITS.getValue(factor)) }
            state.moveFactors(targets, smoothing)
        }
    }

    /** 세목별 실효세율 × 집단의 부담 정도 × 소득 대비 노출 정도. */
    private fun burdenOf(group: PopulationGroup): Double =
        TaxKind.entries.sumOf { kind -> group.taxIncidence.getValue(kind) * budget.rate(kind) * TAX_EXPOSURE.getValue(kind) }

    /** 지출 항목별 시작 대비 변화율을 집단의 관심도로 가중 평균한다. */
    private fun serviceChange(group: PopulationGroup): Double {
        val totalWeight = group.serviceWeights.values.sum()
        return group.serviceWeights.entries.sumOf { (kind, weight) ->
            val initialLevel = budget.initialLevel(kind)
            val change = if (initialLevel > 0.0) budget.level(kind) / initialLevel - 1.0 else 0.0
            weight * change.coerceIn(-1.0, 1.5)
        } / totalWeight
    }

    private fun approval(): Double = weighted { it.satisfaction(baseline) }

    private fun weighted(value: (GroupState) -> Double): Double {
        val weights = groups.map { it.share * it.group.influence }
        return groups.zip(weights).sumOf { (state, weight) -> value(state) * weight } / weights.sum()
    }

    private fun record() {
        approvalSeries.record(approval())
        groups.forEach { satisfactionSeries.getValue(it.group).record(it.satisfaction(baseline)) }
    }

    private class GroupState(
        val group: PopulationGroup,
        val share: Double,
        private val initialIncome: Double,
        initialGrowth: Double,
    ) {
        var initialBurden = 0.0
            private set
        var realIncome = initialIncome
            private set
        private var preTaxIncome = initialIncome
        private val factors = SatisfactionFactor.entries.associateWith { 0.0 }.toMutableMap()

        /** 최근 12개월 실질소득. 시작 시점은 2023년 성장률로 거슬러 채운다. */
        private val incomeHistory = DoubleArray(12) { month -> initialIncome / (1.0 + initialGrowth).pow((12 - month) / 12.0) }
        private var historyIndex = 0

        fun start(burden: Double) {
            initialBurden = burden
        }

        fun updateIncome(
            incomeIndex: Double,
            burden: Double,
            transfer: Double,
        ) {
            incomeHistory[historyIndex] = preTaxIncome
            historyIndex = (historyIndex + 1) % incomeHistory.size
            preTaxIncome = initialIncome * incomeIndex * transfer
            realIncome = preTaxIncome * (1.0 - burden) / (1.0 - initialBurden)
        }

        /** 세전 실질소득의 전년 대비 증가율. 세금 변화는 세금 요인에서 따로 반영한다. */
        fun yearOverYearIncomeGrowth(): Double = preTaxIncome / incomeHistory[historyIndex] - 1.0

        fun moveFactors(
            targets: Map<SatisfactionFactor, Double>,
            smoothing: Double,
        ) {
            targets.forEach { (factor, target) ->
                factors[factor] =
                    factors.getValue(factor) + (target - factors.getValue(factor)) * smoothing
            }
        }

        fun factor(factor: SatisfactionFactor): Double = factors.getValue(factor)

        fun satisfaction(baseline: Double): Double = (baseline + factors.values.sum()).coerceIn(0.0, 100.0)
    }

    private companion object {
        const val BASELINE = 50.0
        const val FACTOR_SMOOTHING = 0.3
        const val INFLATION_COMFORT = 0.02
        const val DEFLATION_BONUS = 0.2
        const val PRICE_WEIGHT = 100.0
        const val JOB_WEIGHT = 300.0
        const val INCOME_WEIGHT = 150.0
        const val TAX_WEIGHT = 120.0
        const val SERVICE_WEIGHT = 20.0
        const val ECONOMY_WEIGHT = 150.0
        val FACTOR_LIMITS =
            mapOf(
                SatisfactionFactor.PRICES to -20.0..3.0,
                SatisfactionFactor.JOBS to -30.0..10.0,
                SatisfactionFactor.INCOME to -25.0..15.0,
                SatisfactionFactor.TAXES to -25.0..15.0,
                SatisfactionFactor.SERVICES to -20.0..15.0,
                SatisfactionFactor.ECONOMY to -15.0..10.0,
            )

        /** 세목별로 세율이 집단 소득에 닿는 정도. */
        val TAX_EXPOSURE =
            mapOf(
                TaxKind.INCOME to 1.0,
                TaxKind.CORPORATE to 0.25,
                TaxKind.CONSUMPTION to 0.8,
                TaxKind.SOCIAL to 0.85,
                TaxKind.TARIFF to 0.25,
            )
    }
}
