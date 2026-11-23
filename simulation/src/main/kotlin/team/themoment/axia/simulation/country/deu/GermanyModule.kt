package team.themoment.axia.simulation.country.deu

import team.themoment.axia.simulation.country.BillCheck
import team.themoment.axia.simulation.country.BillReview
import team.themoment.axia.simulation.country.CountryEffects
import team.themoment.axia.simulation.country.CountryIndicator
import team.themoment.axia.simulation.country.CountryModule
import team.themoment.axia.simulation.country.CountryMonthContext
import team.themoment.axia.simulation.country.CountryNews
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.society.Identity
import java.util.Locale

/**
 * 독일 고유 시스템: 부채 브레이크(기본법 제109·115조).
 *
 * - 연방 구조적 적자는 GDP의 0.35%까지다. 재정수지를 구조적 수지의 대리 지표로 쓴다.
 *   적자를 늘리는 법안이 한도를 넘기면 연방헌법재판소 심사에서 막힌다(2023년 11월 기후·전환기금 판결 참고).
 * - 의회는 "비상 예외 선언"으로 한 해 동안 한도를 풀 수 있다. 시장주의 성향이 싫어한다.
 */
internal class GermanyModule : CountryModule {
    override val countryId: String = ID

    private val emergency =
        CountryPolicyItem(
            binary = true,
            key = "deu.debt_brake_emergency",
            label = "부채 브레이크 비상 예외",
            lowLabel = "한도 준수",
            highLabel = "비상 예외 선언",
            description = "끝까지 올려 통과시키면 다음 1년 동안 부채 브레이크 한도를 적용하지 않는다.",
            identityReactions = mapOf(Identity.FREE_MARKET to -15.0, Identity.EGALITARIANS to 6.0),
        )
    override val policies: List<CountryPolicyItem> = listOf(emergency)
    private var exemptMonths = 0
    private var balance = 0.0

    override fun policyValue(item: CountryPolicyItem): Double = (if (exemptMonths > 0) 1.0 else 0.0).also { require(item == emergency) }

    override fun enact(
        item: CountryPolicyItem,
        target: Double,
    ) {
        require(item == emergency)
        exemptMonths = if (target >= 0.5) EXEMPTION_MONTHS else 0
    }

    override fun billChecks(review: BillReview): List<BillCheck> {
        if (review.addedDeficit <= 0.0) return emptyList()
        val exempt = exemptMonths > 0 || review.changes.any { it.item == emergency && it.to >= 0.5 }
        val after = balance - review.addedDeficit
        val within = exempt || after >= LIMIT
        val detail =
            if (exempt) {
                "비상 예외로 한도 적용 안 함"
            } else {
                String.format(Locale.KOREA, "통과 뒤 재정수지 %.2f%% · 한도 %.2f%%", after * 100, LIMIT * 100)
            }
        return listOf(BillCheck("부채 브레이크(연방헌법재판소)", within, detail))
    }

    override fun advanceMonth(context: CountryMonthContext): CountryEffects {
        balance = context.budgetBalance
        if (exemptMonths > 0) {
            exemptMonths--
            if (exemptMonths == 0) return CountryEffects(news = listOf(CountryNews("연방재무부", "부채 브레이크 비상 예외가 끝났다.", major = false)))
        }
        return CountryEffects.NONE
    }

    override fun indicators(): List<CountryIndicator> =
        listOf(
            CountryIndicator(
                label = "부채 브레이크",
                value =
                    if (exemptMonths > 0) {
                        "비상 예외 · ${exemptMonths}개월 남음"
                    } else {
                        String.format(Locale.KOREA, "재정수지 %.1f%% · 한도 %.2f%%", balance * 100, LIMIT * 100)
                    },
                description = "적자를 늘리는 법안이 한도(GDP의 -0.35%)를 넘기면 연방헌법재판소에서 막힌다. 비상 예외 선언으로 1년 동안 풀 수 있다.",
            ),
        )

    companion object {
        const val ID = "DEU"
        private const val LIMIT = -0.0035
        private const val EXEMPTION_MONTHS = 12
    }
}
