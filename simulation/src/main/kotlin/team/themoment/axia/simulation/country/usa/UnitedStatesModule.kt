package team.themoment.axia.simulation.country.usa

import team.themoment.axia.simulation.country.BillCheck
import team.themoment.axia.simulation.country.BillReview
import team.themoment.axia.simulation.country.CountryEffects
import team.themoment.axia.simulation.country.CountryIndicator
import team.themoment.axia.simulation.country.CountryModule
import team.themoment.axia.simulation.country.CountryMonthContext
import team.themoment.axia.simulation.country.CountryNews
import team.themoment.axia.simulation.nation.BudgetItem
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.society.Identity
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 미국 고유 시스템: 상원 필리버스터와 연방 부채한도.
 *
 * - 필리버스터: 세율·지출만 담은 법안은 예산조정(reconciliation)으로 상원 과반이면 되지만,
 *   규제·제도처럼 예산이 아닌 항목이 하나라도 있으면 토론 종결에 60표가 필요하다.
 * - 부채한도: 2023년 재정책임법으로 2025년 1월 1일까지 유예돼 있다. 유예가 끝나면 그날의 부채가 한도가 되고,
 *   부채가 한도를 넘으면 재무부 특별 조치로 6개월을 버틴 뒤 채무불이행 위기가 온다.
 *   의회는 "부채한도 유예" 법안으로 2년씩 유예할 수 있다.
 */
internal class UnitedStatesModule(
    start: LocalDate,
) : CountryModule {
    override val countryId: String = ID

    private val suspension =
        CountryPolicyItem(
            binary = true,
            key = "usa.debt_limit_suspension",
            label = "부채한도 유예",
            lowLabel = "한도 유지",
            highLabel = "2년 유예",
            description = "값을 끝까지 올려 통과시키면 그날부터 2년 동안 부채한도를 적용하지 않는다. 유예가 끝나면 그날의 부채가 새 한도다.",
            identityReactions = mapOf(Identity.FREE_MARKET to -8.0, Identity.PATRIOTS to -2.0),
        )
    override val policies: List<CountryPolicyItem> = listOf(suspension)

    private var suspendedUntil: LocalDate? = INITIAL_SUSPENSION.takeIf { it > start }
    private var ceiling: Double? = null
    private var extraordinaryMonths = 0
    private var crisis = false
    private var lastDebt = 0.0
    private var lastGdp = 1.0

    override fun policyValue(item: CountryPolicyItem): Double =
        (
            if (suspendedUntil !=
                null
            ) {
                1.0
            } else {
                0.0
            }
        ).also { require(item == suspension) }

    override fun enact(
        item: CountryPolicyItem,
        target: Double,
    ) {
        require(item == suspension)
        pendingSuspension = target >= 0.5
    }

    private var pendingSuspension = false

    override fun billChecks(review: BillReview): List<BillCheck> {
        val needsCloture = review.changes.any { it.item !is BudgetItem }
        val yes = review.upperHouseYes ?: return emptyList()
        if (!needsCloture) {
            return listOf(BillCheck("상원 예산조정", yes * 2 > (review.upperHouseSeats ?: 100), "세율·지출만 담아 과반($yes / 51표 필요)으로 처리"))
        }
        return listOf(BillCheck("상원 필리버스터", yes >= CLOTURE, "예산 외 항목이 있어 토론 종결에 ${CLOTURE}표 필요(예상 ${yes}표)"))
    }

    override fun advanceMonth(context: CountryMonthContext): CountryEffects {
        val date = context.date
        lastDebt = context.debt
        lastGdp = if (context.debtRatio > 0.0) context.debt / context.debtRatio else lastGdp
        val news = mutableListOf<CountryNews>()
        if (pendingSuspension) {
            pendingSuspension = false
            suspendedUntil = date.plusYears(SUSPENSION_YEARS)
            ceiling = null
            extraordinaryMonths = 0
            if (crisis) news += CountryNews("재무부", "부채한도 유예법이 발효돼 채무불이행 위기가 해소됐다.", major = true)
            crisis = false
        }
        suspendedUntil?.let { until ->
            if (date >= until) {
                suspendedUntil = null
                ceiling = context.debt
                news += CountryNews("재무부", "부채한도 유예가 끝났다. 현재 부채(GDP의 ${(context.debtRatio * 100).roundToInt()}%)가 새 한도다.", major = false)
            }
        }
        val limit = ceiling
        var shock = 0.0
        var premium = 0.0
        val mood = mutableMapOf<Identity, Double>()
        if (limit != null && context.debt > limit) {
            extraordinaryMonths++
            if (extraordinaryMonths == 1) news += CountryNews("재무부", "부채가 한도에 닿아 재무부가 특별 조치에 들어갔다.", major = false)
            if (extraordinaryMonths > EXTRAORDINARY_MONTHS) {
                if (!crisis) {
                    crisis = true
                    shock -= DEFAULT_SHOCK
                    news += CountryNews("재무부", "특별 조치가 바닥나 연방정부 채무불이행 위기가 닥쳤다. 국채 금리가 치솟았다.", major = true)
                }
                premium = DEFAULT_PREMIUM
                Identity.entries.forEach { mood[it] = -CRISIS_MOOD }
            }
        }
        return CountryEffects(outputGapShock = shock, identityMood = mood, interestPremium = premium, news = news)
    }

    override fun indicators(): List<CountryIndicator> {
        val limit = ceiling
        val status =
            when {
                suspendedUntil != null -> "유예 · ${suspendedUntil}까지"
                crisis -> "채무불이행 위기"
                limit != null && extraordinaryMonths > 0 -> "특별 조치 ${extraordinaryMonths}개월째"
                limit != null -> "한도까지 GDP의 ${((limit - lastDebt) / lastGdp * 100).coerceAtLeast(0.0).roundToInt()}%p"
                else -> "-"
            }
        return listOf(
            CountryIndicator(
                label = "연방 부채한도",
                value = status,
                description = "부채가 한도를 넘으면 특별 조치로 ${EXTRAORDINARY_MONTHS}개월을 버틴 뒤 채무불이행 위기가 온다. 정책 화면에서 유예 법안을 낼 수 있다.",
            ),
            CountryIndicator(
                label = "상원 필리버스터",
                value = "토론 종결 ${CLOTURE}표",
                description = "세율·지출만 담은 법안은 예산조정으로 과반이면 된다. 예산 외 항목이 있으면 60표가 필요하다.",
            ),
        )
    }

    companion object {
        const val ID = "USA"
        private const val CLOTURE = 60
        private val INITIAL_SUSPENSION: LocalDate = LocalDate.of(2025, 1, 1)
        private const val SUSPENSION_YEARS = 2L
        private const val EXTRAORDINARY_MONTHS = 6
        private const val DEFAULT_SHOCK = 0.02
        private const val DEFAULT_PREMIUM = 0.01
        private const val CRISIS_MOOD = 1.5
    }
}
