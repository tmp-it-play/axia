package team.themoment.axia.simulation.economy

import kotlin.math.max

/** 이번 달 세입과 기초지출(이자 제외)의 GDP 대비 비중. */
data class FiscalShares(
    val revenue: Double,
    val primarySpending: Double,
)

/**
 * 한 국가의 재정과 구조 정책을 정하는 주체. AI 국가는 [AutomaticFiscalPolicy], 플레이어 국가는 플레이어 예산과 규제를 쓴다.
 * 매월 거시 모델이 [advanceMonth]를 한 번 호출한다.
 */
interface FiscalPolicy {
    fun advanceMonth(
        debtRatio: Double,
        outputGap: Double,
    ): FiscalShares

    /** 정책이 잠재성장률에 더하는 연간 비율. */
    val potentialGrowthEffect: Double get() = 0.0

    /** 정책이 이번 달 물가 상승률(연율)에 더하는 값. */
    val inflationImpulse: Double get() = 0.0

    /** 정책이 실업률에 더하는 구조적 변화(노동 규제 등). 경기와 무관하게 실업률을 옮긴다. */
    val naturalUnemploymentShift: Double get() = 0.0

    /** 정책이 인구 증가율(연율)에 더하는 값(이민 등). */
    val populationGrowthShift: Double get() = 0.0

    /** 정책(규제, 관세)이 산업 구조 목표에 곱하는 부문별 배율. 없으면 1. */
    val sectorPolicy: Map<Sector, Double> get() = emptyMap()
}

/**
 * AI 정부의 재정 대응. 부채가 시작 수준보다 늘면 지출을 줄이고 세입을 늘린다. 줄면 약하게 지출을 늘리고,
 * 순자산 상태(부채 < 0)면 쌓인 자산의 수익을 적극적으로 쓴다.
 */
internal class AutomaticFiscalPolicy(
    initial: InitialEconomy,
) : FiscalPolicy {
    private val initialRevenueShare = initial.revenue
    private val initialPrimarySpendingShare = initial.primarySpending
    private val initialDebtRatio = initial.debt
    private var revenueShare = initial.revenue
    private var primarySpendingShare = initial.primarySpending

    override fun advanceMonth(
        debtRatio: Double,
        outputGap: Double,
    ): FiscalShares {
        val debtGap = debtRatio - initialDebtRatio
        val easing = if (debtRatio < 0.0) NET_ASSET_EASING else FISCAL_EASING
        val spendingTarget = initialPrimarySpendingShare - debtGap * if (debtGap > 0.0) FISCAL_RESPONSE else easing
        val revenueTarget = initialRevenueShare + max(0.0, debtGap) * TAX_RESPONSE
        primarySpendingShare += (spendingTarget.coerceAtLeast(MIN_SPENDING_SHARE) - primarySpendingShare) / ADJUSTMENT_MONTHS
        revenueShare += (revenueTarget.coerceAtMost(max(initialRevenueShare, MAX_REVENUE_SHARE)) - revenueShare) / ADJUSTMENT_MONTHS
        return FiscalShares(revenueShare, primarySpendingShare)
    }

    private companion object {
        const val FISCAL_RESPONSE = 0.1
        const val FISCAL_EASING = 0.03
        const val NET_ASSET_EASING = 0.1
        const val TAX_RESPONSE = 0.05
        const val MAX_REVENUE_SHARE = 0.6
        const val ADJUSTMENT_MONTHS = 24.0
        const val MIN_SPENDING_SHARE = 0.03
    }
}
