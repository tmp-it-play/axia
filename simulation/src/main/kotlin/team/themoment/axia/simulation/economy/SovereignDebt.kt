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
    val haircut: Double,
    val maturityMonths: Int,
    val initialSpread: Double,
    val recoveryMonths: Int,
) {
    PRINCIPAL_REDUCTION("원금 감면", 3, 0.40, 60, 0.04, 36),
    MATURITY_EXTENSION("만기 연장", 2, 0.0, 120, 0.015, 24),
}

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
    private var eligiblePrincipal = 0.0
    private var exchanged = 0.0
    private var coupon = 0.0
    private var maturityMonthsLeft = 0
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
        balance += amount
        exchanged = minOf(exchanged, balance.coerceAtLeast(0.0))
        if (phase == DebtPhase.AWAITING_PLAN || phase == DebtPhase.NEGOTIATING) {
            eligiblePrincipal = minOf(eligiblePrincipal, balance.coerceAtLeast(0.0))
        }
    }

    /** 교환채에는 합의한 고정 이율, 나머지에는 국채 실효금리(순자산이면 자산 수익률)를 적용한다. */
    fun annualInterest(marketRate: Double): Double = (balance - exchanged) * marketRate + exchanged * coupon

    private fun fundingNeed(gdp: Double): Double {
        val due = if (maturityMonthsLeft > 0) exchanged * minOf(12, maturityMonthsLeft) / maturityMonthsLeft else 0.0
        val rollover = (balance.coerceAtLeast(0.0) - exchanged) / MARKET_MATURITY_YEARS + due
        return (rollover / gdp + deficit).coerceAtLeast(0.0)
    }

    fun negotiate(proposal: DebtPlan) {
        check(phase == DebtPhase.AWAITING_PLAN)
        startNegotiation(proposal)
    }

    private fun startNegotiation(proposal: DebtPlan) {
        phase = DebtPhase.NEGOTIATING
        plan = proposal
        monthsRemaining = proposal.negotiationMonths
    }

    /** 현재 재정으로 원금 보전안에 합의했을 때 첫해 조달 필요액(GDP 대비). */
    fun extensionNeed(
        gdp: Double,
        annualPrimaryDeficit: Double,
        marketRate: Double,
    ): Double {
        val eligible = minOf(eligiblePrincipal, balance.coerceAtLeast(0.0))
        val other = balance.coerceAtLeast(0.0) - eligible
        val extension = DebtPlan.MATURITY_EXTENSION
        val financing =
            annualPrimaryDeficit + eligible * (12.0 / extension.maturityMonths + exchangeRate(marketRate)) +
                other * (1.0 / MARKET_MATURITY_YEARS + marketRate)
        return (financing / gdp).coerceAtLeast(0.0)
    }

    private fun exchangeRate(marketRate: Double): Double = if (balance > 0.0) annualInterest(marketRate) / balance * COUPON_FACTOR else 0.0

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
        if (maturityMonthsLeft > 0) {
            exchanged -= exchanged / maturityMonthsLeft
            maturityMonthsLeft--
        }
        borrow(shortfall)
        if (phase == DebtPhase.AWAITING_PLAN) return null
        if (phase == DebtPhase.NEGOTIATING) {
            if (--monthsRemaining > 0) return null
            if (plan == DebtPlan.MATURITY_EXTENSION &&
                extensionNeed(gdp, (primarySpending - revenue) * 12, marketRate) > EXTENSION_CAPACITY
            ) {
                startNegotiation(DebtPlan.PRINCIPAL_REDUCTION)
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
            eligiblePrincipal = balance
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
        val eligible = minOf(eligiblePrincipal, balance.coerceAtLeast(0.0))
        val relief = eligible * agreement.haircut
        coupon = exchangeRate(marketRate)
        balance -= relief
        exchanged = eligible - relief
        maturityMonthsLeft = agreement.maturityMonths
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
            exchangedDebt = exchanged / gdp,
            exchangeCoupon = coupon,
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

        /** 협상 중 신규 차입 가산금리, 교환채의 기존 평균 이율 대비 배율, 연장안의 GDP 대비 조달 여력. */
        private const val DEFAULT_SPREAD = 0.06
        private const val COUPON_FACTOR = 0.60
        const val EXTENSION_CAPACITY = 0.25
    }
}
