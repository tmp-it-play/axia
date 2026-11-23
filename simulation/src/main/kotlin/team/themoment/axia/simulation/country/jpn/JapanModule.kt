package team.themoment.axia.simulation.country.jpn

import team.themoment.axia.simulation.country.BillCheck
import team.themoment.axia.simulation.country.BillReview
import team.themoment.axia.simulation.country.CountryEffects
import team.themoment.axia.simulation.country.CountryIndicator
import team.themoment.axia.simulation.country.CountryModule
import team.themoment.axia.simulation.country.CountryMonthContext
import team.themoment.axia.simulation.country.CountryNews
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.society.Identity
import kotlin.math.roundToInt

/**
 * 일본 고유 시스템: 헌법 9조 개정.
 *
 * - 개헌은 중의원·참의원 각 3분의 2 발의와 국민투표 과반이 필요하다(헌법 제96조). 개헌 항목을 담은 법안은
 *   하원·상원 예상 찬성표가 각 3분의 2를 넘고, 국민투표(공통 지지 기반 55점 이상)를 통과해야 한다.
 * - 개헌하면 안보 성향은 반기고 진보 성향은 반발한다. 중국·북한·한국과의 관계가 떨어진다.
 */
internal class JapanModule : CountryModule {
    override val countryId: String = ID

    private val article9 =
        CountryPolicyItem(
            key = "jpn.article_9",
            label = "헌법 9조 개정",
            lowLabel = "현행 유지",
            highLabel = "자위대 명기·개정",
            description = "중의원·참의원 3분의 2와 국민투표 과반이 필요하다. 개정하면 주변국 관계가 나빠진다.",
            identityReactions = mapOf(Identity.PATRIOTS to 25.0, Identity.EGALITARIANS to -20.0, Identity.LIBERTARIANS to -8.0),
        )
    override val policies: List<CountryPolicyItem> = listOf(article9)
    private var amended = 0.0
    private var pendingFallout = false

    override fun policyValue(item: CountryPolicyItem): Double = amended.also { require(item == article9) }

    override fun enact(
        item: CountryPolicyItem,
        target: Double,
    ) {
        require(item == article9)
        if (target > amended) pendingFallout = true
        amended = target
    }

    override fun billChecks(review: BillReview): List<BillCheck> {
        val change = review.changes.firstOrNull { it.item == article9 } ?: return emptyList()
        if (change.delta <= 0.0) return emptyList()
        val lower = review.lowerHouseYes ?: 0
        val lowerNeeded = ((review.lowerHouseSeats ?: 0) * 2 + 2) / 3
        val upper = review.upperHouseYes ?: 0
        val upperNeeded = ((review.upperHouseSeats ?: 0) * 2 + 2) / 3
        return listOf(
            BillCheck("개헌 발의(양원 3분의 2)", lower >= lowerNeeded && upper >= upperNeeded, "중의원 $lower/$lowerNeeded · 참의원 $upper/$upperNeeded"),
            BillCheck(
                "국민투표",
                review.score >= REFERENDUM_SCORE,
                "공통 지지 기반 ${review.score.roundToInt()}점 · ${REFERENDUM_SCORE.roundToInt()}점 이상이면 가결",
            ),
        )
    }

    override fun advanceMonth(context: CountryMonthContext): CountryEffects {
        if (!pendingFallout) return CountryEffects.NONE
        pendingFallout = false
        return CountryEffects(
            relationShifts = mapOf("CHN" to -15.0, "PRK" to -10.0, "KOR" to -8.0),
            news = listOf(CountryNews("내각", "헌법 9조 개정이 공포됐다. 중국과 한국이 강하게 반발했다.", major = true)),
        )
    }

    override fun indicators(): List<CountryIndicator> =
        listOf(
            CountryIndicator(
                label = "헌법 9조",
                value = if (amended >= 0.5) "개정됨" else "현행",
                description = "개헌 법안은 양원 3분의 2 찬성과 국민투표를 거친다.",
            ),
        )

    companion object {
        const val ID = "JPN"
        private const val REFERENDUM_SCORE = 55.0
    }
}
