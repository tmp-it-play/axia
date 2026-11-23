package team.themoment.axia.simulation.country.fra

import team.themoment.axia.simulation.country.BillReview
import team.themoment.axia.simulation.country.CountryEffects
import team.themoment.axia.simulation.country.CountryIndicator
import team.themoment.axia.simulation.country.CountryModule
import team.themoment.axia.simulation.country.CountryMonthContext
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.society.Identity

/**
 * 프랑스 고유 시스템: 헌법 49조 3항.
 *
 * - "49조 3항 강행 처리"를 켜 두면, 국민의회에서 부결될 법안을 정부가 표결 없이 채택한다.
 *   대가로 다음 달 모든 성향의 만족도가 떨어진다(강행 처리 반발). 재정 법안 외에는 회기당 한 번이 원칙이라 1년에 한 번만 쓸 수 있다.
 * - 의회의 불신임 동의 자체는 공통 불신임 모델이 다룬다.
 */
internal class FranceModule : CountryModule {
    override val countryId: String = ID

    private val forceItem =
        CountryPolicyItem(
            key = "fra.article_49_3",
            label = "49조 3항 강행 처리",
            lowLabel = "쓰지 않음",
            highLabel = "필요하면 사용",
            description = "켜 두면 국민의회에서 부결될 법안을 표결 없이 채택한다. 1년에 한 번만 쓸 수 있고, 쓰면 여론이 나빠진다.",
            identityReactions = mapOf(Identity.LIBERTARIANS to -6.0, Identity.EGALITARIANS to -4.0),
        )
    override val policies: List<CountryPolicyItem> = listOf(forceItem)
    private var enabled = 0.0
    private var cooldownMonths = 0
    private var uses = 0
    private var backlash = false

    override fun policyValue(item: CountryPolicyItem): Double = enabled.also { require(item == forceItem) }

    override fun enact(
        item: CountryPolicyItem,
        target: Double,
    ) {
        require(item == forceItem)
        enabled = target
    }

    override fun forceThrough(review: BillReview): String? {
        if (enabled < 0.5 || cooldownMonths > 0) return null
        uses++
        cooldownMonths = COOLDOWN_MONTHS
        backlash = true
        return "헌법 49조 3항"
    }

    override fun advanceMonth(context: CountryMonthContext): CountryEffects {
        if (cooldownMonths > 0) cooldownMonths--
        if (!backlash) return CountryEffects.NONE
        backlash = false
        return CountryEffects(identityMood = Identity.entries.associateWith { -BACKLASH })
    }

    override fun indicators(): List<CountryIndicator> =
        listOf(
            CountryIndicator(
                label = "헌법 49조 3항",
                value =
                    (if (enabled >= 0.5) "사용 준비" else "쓰지 않음") + " · 사용 ${uses}회" +
                        if (cooldownMonths > 0) " · ${cooldownMonths}개월 뒤 다시 사용 가능" else "",
                description = "국민의회 표결에서 지는 법안을 정부 책임으로 채택한다. 쓰면 다음 달 모든 성향의 만족도가 2점 떨어진다.",
            ),
        )

    companion object {
        const val ID = "FRA"
        private const val COOLDOWN_MONTHS = 12
        private const val BACKLASH = 2.0
    }
}
