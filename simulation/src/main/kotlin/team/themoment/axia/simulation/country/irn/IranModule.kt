package team.themoment.axia.simulation.country.irn

import team.themoment.axia.simulation.country.BillCheck
import team.themoment.axia.simulation.country.BillReview
import team.themoment.axia.simulation.country.CountryEffects
import team.themoment.axia.simulation.country.CountryIndicator
import team.themoment.axia.simulation.country.CountryModule
import team.themoment.axia.simulation.country.CountryMonthContext
import team.themoment.axia.simulation.country.CountryNews
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.nation.RegulationItem
import team.themoment.axia.simulation.society.Identity
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 이란 고유 시스템: 헌법수호위원회 심사와 히잡 단속.
 *
 * - 헌법수호위원회는 의회를 통과한 모든 법률을 이슬람 율법·헌법 합치 여부로 심사한다(헌법 제94조).
 *   시민 자유를 넓히거나 치안·감시를 크게 줄이는 법안은 거부한다.
 * - 히잡 단속 강도는 종교 성향과 청년·시민자유 성향을 갈라놓는다. 단속이 강하고 청년 불만이 크면
 *   2022년 같은 전국 시위가 일어날 수 있다. 시작값 0.8은 게임 가정이다.
 */
internal class IranModule(
    private val random: Random,
) : CountryModule {
    override val countryId: String = ID

    private val hijab =
        CountryPolicyItem(
            key = "irn.hijab_enforcement",
            label = "히잡 단속 강도",
            lowLabel = "사실상 중단",
            highLabel = "엄격 단속",
            description = "종교·전통 성향은 엄격한 단속을, 청년·시민자유 성향은 완화를 원한다. 단속이 강할수록 전국 시위 위험이 커진다.",
            identityReactions =
                mapOf(Identity.RELIGIOUS to 30.0, Identity.LIBERTARIANS to -40.0, Identity.YOUTH to -25.0, Identity.EGALITARIANS to -12.0),
        )
    override val policies: List<CountryPolicyItem> = listOf(hijab)
    private var enforcement = INITIAL_ENFORCEMENT
    private var protestMonths = 0

    override fun policyValue(item: CountryPolicyItem): Double = enforcement.also { require(item == hijab) }

    override fun enact(
        item: CountryPolicyItem,
        target: Double,
    ) {
        require(item == hijab)
        enforcement = target.coerceIn(0.0, 1.0)
    }

    override fun billChecks(review: BillReview): List<BillCheck> {
        val liberties = review.changes.firstOrNull { it.item == RegulationItem.CIVIL_LIBERTIES }?.delta ?: 0.0
        val policing = review.changes.firstOrNull { it.item == RegulationItem.POLICING }?.delta ?: 0.0
        val hijabCut = review.changes.firstOrNull { it.item == hijab }?.delta ?: 0.0
        val violations =
            buildList {
                if (liberties > LIBERTY_TOLERANCE) add("시민 자유 확대")
                if (policing < -POLICING_TOLERANCE) add("치안·감시 축소")
                if (hijabCut < -HIJAB_TOLERANCE) add("히잡 단속 대폭 완화")
            }
        val detail = if (violations.isEmpty()) "이슬람 율법·헌법 합치" else "율법 위반 판단: ${violations.joinToString(", ")}"
        return listOf(BillCheck("헌법수호위원회", violations.isEmpty(), detail))
    }

    override fun advanceMonth(context: CountryMonthContext): CountryEffects {
        val chance =
            (enforcement - SAFE_ENFORCEMENT).coerceAtLeast(0.0) * PROTEST_CHANCE *
                (1.0 + (50.0 - context.approval) / 25.0).coerceAtLeast(0.2)
        if (protestMonths > 0) protestMonths--
        if (protestMonths == 0 && random.nextDouble() < chance) {
            protestMonths = PROTEST_MONTHS
            return CountryEffects(
                outputGapShock = -PROTEST_SHOCK,
                identityMood = mapOf(Identity.YOUTH to -4.0, Identity.LIBERTARIANS to -4.0, Identity.RELIGIOUS to 1.0),
                news = listOf(CountryNews("시민사회", "도덕경찰 단속 중 숨진 시민을 계기로 전국 시위가 번지고 있다.", major = true)),
            )
        }
        return CountryEffects.NONE
    }

    override fun indicators(): List<CountryIndicator> =
        listOf(
            CountryIndicator(
                label = "히잡 단속",
                value = "${(enforcement * 100).roundToInt()} / 100" + if (protestMonths > 0) " · 전국 시위 중" else "",
                description = "단속 강도가 ${(SAFE_ENFORCEMENT * 100).roundToInt()}을 넘으면 전국 시위 위험이 생기고, 지지율이 낮을수록 커진다.",
                level = enforcement,
            ),
            CountryIndicator(
                label = "헌법수호위원회",
                value = "모든 법률 심사",
                description = "시민 자유 확대, 치안·감시의 큰 축소, 히잡 단속의 큰 완화를 담은 법안은 거부한다.",
            ),
        )

    companion object {
        const val ID = "IRN"
        private const val INITIAL_ENFORCEMENT = 0.8
        private const val SAFE_ENFORCEMENT = 0.45
        private const val PROTEST_CHANCE = 0.06
        private const val PROTEST_MONTHS = 6
        private const val PROTEST_SHOCK = 0.004
        private const val LIBERTY_TOLERANCE = 0.05
        private const val POLICING_TOLERANCE = 0.1
        private const val HIJAB_TOLERANCE = 0.3
    }
}
