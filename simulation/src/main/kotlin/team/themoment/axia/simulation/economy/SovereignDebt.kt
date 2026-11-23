package team.themoment.axia.simulation.economy

import kotlinx.serialization.Serializable

/**
 * 채권단과 협상할 교환 조건(게임 가정). 감면율은 교환 원금 대비, 가산금리는 연율이다.
 * 두 안 모두 기존 채권의 이율을 낮추고, 만기가 돌아오는 몫부터 시장금리로 차환한다.
 */
@Serializable
enum class DebtPlan(
    val label: String,
    val negotiationMonths: Int,
    val initialSpread: Double,
    val recoveryMonths: Int,
) {
    PRINCIPAL_REDUCTION("원금 감면", 3, 0.04, 36),
    MATURITY_EXTENSION("만기 연장", 2, 0.015, 24),
}

/** 공공 채권은 일반 양자·공적 국채를 뜻한다. 별도 외환계정의 IMF 대출은 포함하지 않는다. */
enum class Creditor(
    val label: String,
    val reduction: Double,
    val reductionMonths: Int,
    val extensionMonths: Int,
    internal val couponFactor: Double,
    internal val financingLimit: Double,
) {
    DOMESTIC("국내 채권자", 0.25, 60, 96, 0.65, 0.28),
    MARKET("해외 민간 채권자", 0.50, 48, 108, 0.75, 0.22),
    OFFICIAL("공적 채권자", 0.15, 96, 180, 0.40, 0.32),
    ;

    fun haircut(plan: DebtPlan) = if (plan == DebtPlan.PRINCIPAL_REDUCTION) reduction else 0.0

    fun months(plan: DebtPlan) = if (plan == DebtPlan.PRINCIPAL_REDUCTION) reductionMonths else extensionMonths
}

data class CreditorSnapshot(
    val creditor: Creditor,
    val outstanding: Double,
    val eligible: Double,
    val proposal: DebtPlan?,
    val exchanged: Double,
    val coupon: Double,
    val monthsToMaturity: Int,
    val countered: Boolean,
    val lastWriteDown: Double?,
)

enum class DebtPhase(
    val label: String,
) {
    NORMAL("정상"),
    AWAITING_PLAN("지급 유예 · 협상안 선택"),
    NEGOTIATING("채권단 협상 중"),
    RECOVERY("시장 신뢰 회복 중"),
}

/** 재정 화면의 국가채무 현황. 조달 필요액·교환채 잔액은 현재 GDP 대비, 감면액은 합의 당시 GDP 대비다. */
data class DebtWorkoutSnapshot(
    val phase: DebtPhase,
    val plan: DebtPlan?,
    val monthsRemaining: Int,
    val stressMonths: Int,
    val financingNeed: Double,
    val interestThreshold: Double,
    val fundingThreshold: Double,
    val marketSpread: Double,
    val exchangedDebt: Double,
    val exchangeCoupon: Double,
    val lastWriteDown: Double?,
    val creditors: List<CreditorSnapshot>,
)

internal enum class DebtNotice { DEFAULT, COUNTEROFFER, SETTLEMENT, RECOVERED }

/** 국채 잔액과 교환채 상환 일정. 지급 유예 중에도 발생한 이자는 부채에 계상한다. */
internal class SovereignDebt(
    initial: InitialEconomy,
) {
    var balance = initial.debt * initial.gdp
        private set
    var phase = DebtPhase.NORMAL
        private set
    var plan: DebtPlan? = null
        private set
    private var monthsRemaining = 0
    private var stressMonths = 0

    private class Holding(
        val creditor: Creditor,
        val issueShare: Double,
        var principal: Double,
    ) {
        var eligible = 0.0
        var exchanged = 0.0
        var coupon = 0.0
        var months = 0
        var proposal: DebtPlan? = null
        var countered = false
        var lastWriteDown: Double? = null
    }

    // 채권자 보유 통계가 없는 나라에도 같은 규칙을 적용한다. 공적 채권 비중만 소득군에 따라 달라진다.
    private val holdings =
        run {
            val official =
                when (initial.society.incomeGroup) {
                    "HIC" -> 0.05
                    "UMC" -> 0.15
                    "LMC" -> 0.30
                    else -> 0.45
                }
            Creditor.entries.map { group ->
                val share =
                    when (group) {
                        Creditor.DOMESTIC -> (1 - official) * 0.6
                        Creditor.MARKET -> (1 - official) * 0.4
                        Creditor.OFFICIAL -> official
                    }
                Holding(group, share, balance.coerceAtLeast(0.0) * share)
            }
        }
    private var lastWriteDown: Double? = null

    private val initialInterest = initial.debt * initial.interestRate
    private var deficit = initial.primarySpending + initialInterest - initial.revenue
    private val initialInterestShare = if (initial.revenue > 0.0) initialInterest / initial.revenue else 0.0
    private val interestThreshold = maxOf(HIGH_INTEREST_SHARE, initialInterestShare + INTEREST_MARGIN)
    private val fundingThreshold =
        maxOf(HIGH_FUNDING_NEED, initial.revenue, initial.debt / MARKET_MATURITY_YEARS + deficit + FUNDING_MARGIN)

    val marketSpread: Double
        get() =
            when (phase) {
                DebtPhase.NORMAL -> 0.0
                DebtPhase.AWAITING_PLAN, DebtPhase.NEGOTIATING -> DEFAULT_SPREAD
                DebtPhase.RECOVERY -> requireNotNull(plan).let { it.initialSpread * monthsRemaining / it.recoveryMonths }
            }

    fun borrow(amount: Double) {
        val previous = balance.coerceAtLeast(0.0)
        balance += amount
        val change = balance.coerceAtLeast(0.0) - previous
        if (change == 0.0) return
        holdings.forEach { holding ->
            holding.principal =
                if (change > 0.0) {
                    holding.principal + change * holding.issueShare
                } else {
                    holding.principal * (balance.coerceAtLeast(0.0) / previous)
                }
            holding.exchanged = minOf(holding.exchanged, holding.principal)
            holding.eligible = minOf(holding.eligible, holding.principal)
        }
    }

    fun annualInterest(marketRate: Double): Double = balance * marketRate + holdings.sumOf { it.exchanged * (it.coupon - marketRate) }

    private fun fundingNeed(gdp: Double): Double {
        val rollover =
            holdings.sumOf {
                val due = if (it.months > 0) it.exchanged * minOf(12, it.months) / it.months else 0.0
                (it.principal - it.exchanged) / MARKET_MATURITY_YEARS + due
            }
        return (rollover / gdp + deficit).coerceAtLeast(0.0)
    }

    fun setTerms(
        creditor: Creditor,
        proposal: DebtPlan?,
    ): Boolean {
        if (phase != DebtPhase.AWAITING_PLAN) return false
        holdings[creditor.ordinal].proposal = proposal
        return true
    }

    fun negotiateByCapacity(extensionNeed: Double) {
        check(phase == DebtPhase.AWAITING_PLAN)
        holdings.forEach {
            it.proposal = if (extensionNeed <= it.creditor.financingLimit) DebtPlan.MATURITY_EXTENSION else DebtPlan.PRINCIPAL_REDUCTION
        }
        startNegotiation()
    }

    fun negotiate(proposal: DebtPlan) {
        check(phase == DebtPhase.AWAITING_PLAN)
        holdings.forEach { if (it.proposal == null) it.proposal = proposal }
        startNegotiation()
    }

    private fun startNegotiation() {
        phase = DebtPhase.NEGOTIATING
        plan =
            if (holdings.any { it.eligible > 0 && it.proposal == DebtPlan.PRINCIPAL_REDUCTION }) {
                DebtPlan.PRINCIPAL_REDUCTION
            } else {
                DebtPlan.MATURITY_EXTENSION
            }
        monthsRemaining = requireNotNull(plan).negotiationMonths
    }

    fun extensionNeed(
        gdp: Double,
        annualPrimaryDeficit: Double,
        marketRate: Double,
    ): Double {
        val averageRate = averageRate(marketRate)
        val financing =
            holdings.sumOf { holding ->
                val terms = holding.proposal ?: DebtPlan.MATURITY_EXTENSION
                val creditor = holding.creditor
                val converted = holding.eligible * (1 - creditor.haircut(terms))
                converted * (12.0 / creditor.months(terms) + averageRate * creditor.couponFactor) +
                    (holding.principal - holding.eligible) * (1.0 / MARKET_MATURITY_YEARS + marketRate)
            }
        return ((annualPrimaryDeficit + financing) / gdp).coerceAtLeast(0.0)
    }

    private fun averageRate(marketRate: Double) = if (balance > 0.0) annualInterest(marketRate) / balance else 0.0

    /** 이달 이자와 기초수지를 한 번 결산한 뒤 연속 자금 압박 또는 협상 기한을 판정한다. */
    fun closeMonth(
        gdp: Double,
        revenue: Double,
        primarySpending: Double,
        interest: Double,
        marketRate: Double,
    ): DebtNotice? {
        val shortfall = primarySpending + interest - revenue
        deficit = shortfall * 12 / gdp
        // 만기 원금은 시장 차환으로 재분류한다. 실제 부채 증감은 재정수지로 한 번만 기록한다.
        holdings.forEach {
            if (it.months > 0) {
                it.exchanged -= it.exchanged / it.months
                it.months--
            }
        }
        borrow(shortfall)
        if (phase == DebtPhase.AWAITING_PLAN) return null
        if (phase == DebtPhase.NEGOTIATING) {
            if (--monthsRemaining > 0) return null
            val need = extensionNeed(gdp, (primarySpending - revenue) * 12, marketRate)
            val objections =
                holdings.filter {
                    it.eligible > 0.0 && it.proposal == DebtPlan.MATURITY_EXTENSION && need > it.creditor.financingLimit
                }
            if (objections.isNotEmpty()) {
                objections.forEach {
                    it.proposal = DebtPlan.PRINCIPAL_REDUCTION
                    it.countered = true
                }
                startNegotiation()
                return DebtNotice.COUNTEROFFER
            }
            settle(gdp, marketRate)
            return DebtNotice.SETTLEMENT
        }

        val highInterest = interest > 0.0 && interest >= revenue * interestThreshold
        val fundingGap = deficit >= LARGE_DEFICIT && fundingNeed(gdp) >= fundingThreshold
        stressMonths = if (balance > 0.0 && shortfall > 0.0 && (highInterest || fundingGap)) stressMonths + 1 else 0
        if (stressMonths == STRESS_MONTHS) {
            phase = DebtPhase.AWAITING_PLAN
            plan = null
            monthsRemaining = 0
            stressMonths = 0
            holdings.forEach {
                it.eligible = it.principal
                it.proposal = null
                it.countered = false
            }
            return DebtNotice.DEFAULT
        }
        if (phase == DebtPhase.RECOVERY && --monthsRemaining == 0) {
            phase = DebtPhase.NORMAL
            return DebtNotice.RECOVERED
        }
        return null
    }

    private fun settle(
        gdp: Double,
        marketRate: Double,
    ) {
        val agreement = requireNotNull(plan)
        val previousRate = averageRate(marketRate)
        val relief =
            holdings.sumOf {
                val terms = requireNotNull(it.proposal)
                val cut = it.eligible * it.creditor.haircut(terms)
                it.coupon = previousRate * it.creditor.couponFactor
                it.principal -= cut
                it.lastWriteDown = cut / gdp
                it.exchanged = it.eligible - cut
                it.months = it.creditor.months(terms)
                it.eligible = 0.0
                cut
            }
        balance -= relief
        lastWriteDown = relief / gdp
        monthsRemaining = agreement.recoveryMonths
        phase = DebtPhase.RECOVERY
    }

    fun snapshot(gdp: Double): DebtWorkoutSnapshot =
        DebtWorkoutSnapshot(
            phase = phase,
            plan = plan,
            monthsRemaining = monthsRemaining,
            stressMonths = stressMonths,
            financingNeed = fundingNeed(gdp),
            interestThreshold = interestThreshold,
            fundingThreshold = fundingThreshold,
            marketSpread = marketSpread,
            exchangedDebt = holdings.sumOf { it.exchanged } / gdp,
            exchangeCoupon =
                holdings.sumOf { it.exchanged }.let { total ->
                    if (total > 0.0) holdings.sumOf { it.exchanged * it.coupon } / total else 0.0
                },
            creditors =
                holdings.map {
                    CreditorSnapshot(
                        it.creditor,
                        it.principal / gdp,
                        it.eligible / gdp,
                        it.proposal,
                        it.exchanged / gdp,
                        it.coupon,
                        it.months,
                        it.countered,
                        it.lastWriteDown,
                    )
                },
            lastWriteDown = lastWriteDown,
        )

    companion object {
        /** 연속 3개월 적자와 자금 압박이 겹치면 채무불이행에 들어간다(이하 수치는 게임 가정). */
        const val STRESS_MONTHS = 3

        /** 기존 일반 국채가 평균 5년에 걸쳐 차환된다. */
        const val MARKET_MATURITY_YEARS = 5.0

        /** 세입 대비 이자가 25% 이상이고 시작 때보다 8%p 이상 높으면 상환 압박이다. */
        private const val HIGH_INTEREST_SHARE = 0.25
        private const val INTEREST_MARGIN = 0.08

        /** 조달 필요액이 GDP의 30%, 시작 세입 비중, 시작 조달 필요액 + 10%p를 모두 넘으면 큰 적자와 함께 차환 압박이 된다. */
        private const val HIGH_FUNDING_NEED = 0.30
        private const val FUNDING_MARGIN = 0.10
        private const val LARGE_DEFICIT = 0.04

        /** 협상 중 신규 차입 가산금리. */
        private const val DEFAULT_SPREAD = 0.06
    }
}
