package team.themoment.axia.simulation.nation

import team.themoment.axia.simulation.economy.FiscalPolicy
import team.themoment.axia.simulation.economy.FiscalShares
import team.themoment.axia.simulation.economy.InitialEconomy

sealed interface BudgetItem {
    val label: String
    val maxTarget: Double
}

/**
 * 세목. 세입 = 실효세율 × 과세 기반(GDP 대비 [baseShare]).
 * 과세 기반은 경기에 [cyclicality]만큼 반응하고, 세율을 시작보다 올리면 [behavior]만큼 줄어든다(회피, 위축).
 */
enum class TaxKind(
    override val label: String,
    val baseShare: Double,
    val cyclicality: Double,
    val behavior: Double,
    override val maxTarget: Double,
) : BudgetItem {
    INCOME("소득세", 0.6, 1.2, 0.3, 0.5),
    CORPORATE("법인세", 0.15, 2.5, 0.4, 0.5),
    CONSUMPTION("부가가치세·소비세", 0.6, 1.0, 0.1, 0.3),
    SOCIAL("사회보험료", 0.5, 1.0, 0.2, 0.4),
    TARIFF("관세", 0.3, 1.3, 0.5, 0.3),
}

enum class SpendingKind(
    override val label: String,
) : BudgetItem {
    DEFENSE("국방"),
    EDUCATION("교육"),
    HEALTH("보건"),
    SOCIAL("사회보장"),
    ORDER("치안·행정"),
    ECONOMY("경제·인프라"),
    ;

    override val maxTarget: Double get() = 0.4
}

data class TaxLine(
    val kind: TaxKind,
    val initialRate: Double,
    val targetRate: Double,
    val rate: Double,
    /** 최근 한 달 세입의 GDP 대비 비중(연율). */
    val revenue: Double,
)

data class SpendingLine(
    val kind: SpendingKind,
    val initialLevel: Double,
    val targetLevel: Double,
    /** 실제 집행 중인 GDP 대비 비중. */
    val level: Double,
)

/**
 * 플레이어 예산. 플레이어가 정한 목표 세율과 지출은 다음 달부터 [PHASE_IN_MONTHS]에 걸쳐 실제값이 따라간다.
 *
 * 경제 효과: 교육, 경제·인프라 증액은 지연 후 잠재성장률을 높이고, 법인세 인상은 낮춘다.
 * 부가가치세 변경은 1년쯤에 걸쳐 물가에 반영된다. 재정 충격에 따른 수요 효과는 거시 모델이 계산한다.
 */
internal class PlayerBudget(
    initial: InitialEconomy,
) : FiscalPolicy {
    private val budget = initial.budget
    private val initialRates: Map<TaxKind, Double> =
        mapOf(
            TaxKind.INCOME to budget.incomeTaxShare,
            TaxKind.CORPORATE to budget.corporateTaxShare,
            TaxKind.CONSUMPTION to budget.consumptionTaxShare,
            TaxKind.SOCIAL to budget.socialContributionShare,
            TaxKind.TARIFF to budget.tradeTaxShare,
        ).mapValues { (kind, share) -> (share * initial.revenue / kind.baseShare).coerceIn(0.0, kind.maxTarget) }

    /** 조정할 수 없는 기타 세입(자원 수입, 공기업 이익 등)의 GDP 대비 비중. */
    val otherRevenue = budget.otherRevenueShare * initial.revenue

    val initialLevels: Map<SpendingKind, Double>

    init {
        val core = budget.defense + budget.education + budget.health
        val remainder = (initial.primarySpending - core).coerceAtLeast(0.0)
        initialLevels =
            mapOf(
                SpendingKind.DEFENSE to budget.defense,
                SpendingKind.EDUCATION to budget.education,
                SpendingKind.HEALTH to budget.health,
                SpendingKind.SOCIAL to remainder * 0.5,
                SpendingKind.ORDER to remainder * 0.25,
                SpendingKind.ECONOMY to remainder * 0.25,
            )
    }

    private val targetRates = initialRates.toMutableMap()
    private val rates = initialRates.toMutableMap()
    private val targetLevels = initialLevels.toMutableMap()
    private val levels = initialLevels.toMutableMap()
    private val revenues = TaxKind.entries.associateWith { 0.0 }.toMutableMap()

    init {
        updateRevenues(outputGap = 0.0)
    }

    private var educationLagged = initialLevels.getValue(SpendingKind.EDUCATION)
    private var infrastructureLagged = initialLevels.getValue(SpendingKind.ECONOMY)
    private var corporateLagged = initialRates.getValue(TaxKind.CORPORATE)
    private var consumptionLagged = initialRates.getValue(TaxKind.CONSUMPTION)

    fun target(item: BudgetItem): Double =
        when (item) {
            is TaxKind -> targetRates.getValue(item)
            is SpendingKind -> targetLevels.getValue(item)
        }

    fun enact(
        item: BudgetItem,
        target: Double,
    ) {
        require(target in 0.0..item.maxTarget)
        when (item) {
            is TaxKind -> targetRates[item] = target
            is SpendingKind -> targetLevels[item] = target
        }
    }

    fun rate(kind: TaxKind): Double = rates.getValue(kind)

    fun initialRate(kind: TaxKind): Double = initialRates.getValue(kind)

    fun level(kind: SpendingKind): Double = levels.getValue(kind)

    fun initialLevel(kind: SpendingKind): Double = initialLevels.getValue(kind)

    override fun advanceMonth(
        debtRatio: Double,
        outputGap: Double,
    ): FiscalShares {
        TaxKind.entries.forEach { rates[it] = rates.getValue(it) + (targetRates.getValue(it) - rates.getValue(it)) / PHASE_IN_MONTHS }
        SpendingKind.entries.forEach {
            levels[it] =
                levels.getValue(it) + (targetLevels.getValue(it) - levels.getValue(it)) / PHASE_IN_MONTHS
        }

        educationLagged += (levels.getValue(SpendingKind.EDUCATION) - educationLagged) / EDUCATION_LAG_MONTHS
        infrastructureLagged += (levels.getValue(SpendingKind.ECONOMY) - infrastructureLagged) / INFRASTRUCTURE_LAG_MONTHS
        corporateLagged += (rates.getValue(TaxKind.CORPORATE) - corporateLagged) / CORPORATE_LAG_MONTHS
        consumptionLagged += (rates.getValue(TaxKind.CONSUMPTION) - consumptionLagged) / PRICE_PASS_THROUGH_MONTHS

        updateRevenues(outputGap)
        return FiscalShares(revenue = revenues.values.sum() + otherRevenue, primarySpending = levels.values.sum())
    }

    private fun updateRevenues(outputGap: Double) {
        TaxKind.entries.forEach { kind ->
            val rate = rates.getValue(kind)
            val base =
                kind.baseShare * (1.0 + kind.cyclicality * outputGap) *
                    (1.0 - kind.behavior * (rate - initialRates.getValue(kind))).coerceAtLeast(MIN_BASE_FACTOR)
            revenues[kind] = rate * base.coerceAtLeast(0.0)
        }
    }

    override val potentialGrowthEffect: Double
        get() =
            EDUCATION_GROWTH * (educationLagged - initialLevels.getValue(SpendingKind.EDUCATION)) +
                INFRASTRUCTURE_GROWTH * (infrastructureLagged - initialLevels.getValue(SpendingKind.ECONOMY)) -
                CORPORATE_TAX_DRAG * (corporateLagged - initialRates.getValue(TaxKind.CORPORATE))

    override val inflationImpulse: Double
        get() = CONSUMPTION_TAX_PASS_THROUGH * (rates.getValue(TaxKind.CONSUMPTION) - consumptionLagged)

    fun taxLines(): List<TaxLine> =
        TaxKind.entries.map { TaxLine(it, initialRates.getValue(it), targetRates.getValue(it), rates.getValue(it), revenues.getValue(it)) }

    fun spendingLines(): List<SpendingLine> =
        SpendingKind.entries.map { SpendingLine(it, initialLevels.getValue(it), targetLevels.getValue(it), levels.getValue(it)) }

    companion object {
        const val PHASE_IN_MONTHS = 6.0
        private const val MIN_BASE_FACTOR = 0.3
        private const val EDUCATION_LAG_MONTHS = 60.0
        private const val INFRASTRUCTURE_LAG_MONTHS = 36.0
        private const val CORPORATE_LAG_MONTHS = 12.0
        private const val PRICE_PASS_THROUGH_MONTHS = 12.0

        /** GDP 대비 1%p 증액이 장기 잠재성장률에 더하는 비율. */
        private const val EDUCATION_GROWTH = 0.08
        private const val INFRASTRUCTURE_GROWTH = 0.12

        /** 법인세 실효세율 1%p 인상이 잠재성장률에서 빼는 비율. */
        private const val CORPORATE_TAX_DRAG = 0.05
        private const val CONSUMPTION_TAX_PASS_THROUGH = 0.7
    }
}
