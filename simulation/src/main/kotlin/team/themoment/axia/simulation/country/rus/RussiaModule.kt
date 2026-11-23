package team.themoment.axia.simulation.country.rus

import team.themoment.axia.simulation.country.CountryEffects
import team.themoment.axia.simulation.country.CountryIndicator
import team.themoment.axia.simulation.country.CountryModule
import team.themoment.axia.simulation.country.CountryMonthContext
import team.themoment.axia.simulation.economy.Sector
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.society.Identity
import kotlin.math.roundToInt

/**
 * 러시아 고유 시스템: 전시 경제.
 *
 * - 전시 경제 수위(시작 0.8)는 방산 주문으로 중화학 부문과 경기를 떠받치지만, 노동력 부족으로 물가를 올린다.
 *   수위를 낮추면 물가 압력은 줄지만 경기와 안보 성향 지지가 떨어진다.
 * - 노동력 부족 지수는 전시 경제 수위가 오래 높을수록 쌓인다.
 */
internal class RussiaModule : CountryModule {
    override val countryId: String = ID

    private val warEconomy =
        CountryPolicyItem(
            key = "rus.war_economy",
            label = "전시 경제 수위",
            lowLabel = "민수 전환",
            highLabel = "총력 방산",
            description = "높을수록 중화학 부문과 경기가 살아나지만 노동력 부족으로 물가가 오른다.",
            identityReactions = mapOf(Identity.PATRIOTS to 15.0, Identity.FREE_MARKET to -10.0, Identity.PARENTS to -5.0),
        )
    override val policies: List<CountryPolicyItem> = listOf(warEconomy)
    private var level = INITIAL_LEVEL
    private var shortage = INITIAL_SHORTAGE

    override fun policyValue(item: CountryPolicyItem): Double = level.also { require(item == warEconomy) }

    override fun enact(
        item: CountryPolicyItem,
        target: Double,
    ) {
        require(item == warEconomy)
        level = target
    }

    override fun advanceMonth(context: CountryMonthContext): CountryEffects {
        shortage += (level * MAX_SHORTAGE - shortage) / SHORTAGE_MONTHS
        val change = level - INITIAL_LEVEL
        return CountryEffects(
            outputGapShock = GAP_SUPPORT * change,
            sectorShocks = mapOf(Sector.HEAVY_INDUSTRY to HEAVY_BOOST * change),
            inflationShock = INFLATION_PRESSURE * (shortage - INITIAL_SHORTAGE),
        )
    }

    override fun indicators(): List<CountryIndicator> =
        listOf(
            CountryIndicator(
                label = "노동력 부족",
                value = "${(shortage * 100).roundToInt()} / 100",
                description = "전시 경제 수위가 오래 높을수록 쌓이고 물가를 올린다.",
                level = shortage,
            ),
        )

    companion object {
        const val ID = "RUS"
        private const val INITIAL_LEVEL = 0.8
        private const val INITIAL_SHORTAGE = 0.6
        private const val MAX_SHORTAGE = 0.9
        private const val SHORTAGE_MONTHS = 24.0
        private const val GAP_SUPPORT = 0.0008
        private const val HEAVY_BOOST = 0.08
        private const val INFLATION_PRESSURE = 0.1
    }
}
