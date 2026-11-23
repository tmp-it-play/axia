package team.themoment.axia.simulation.country.twn

import team.themoment.axia.simulation.country.CountryEffects
import team.themoment.axia.simulation.country.CountryIndicator
import team.themoment.axia.simulation.country.CountryModule
import team.themoment.axia.simulation.country.CountryMonthContext
import team.themoment.axia.simulation.country.CountryNews
import team.themoment.axia.simulation.economy.Sector
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.society.Identity
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 대만 고유 시스템: 양안 관계와 반도체.
 *
 * - 양안 정책(0 독립 지향 ~ 1 교류 확대)이 중국 관계와 해협 긴장도를 움직인다. 긴장도가 높으면 중국의 군사훈련과
 *   무역 보복(농수산물 수입 중단)이 일어난다. 미국과의 관계는 독립 지향일수록 좋다.
 * - 첨단 제조(반도체)는 세계 경기에 민감하다. 해협 긴장도가 높으면 반도체 공급망 우려로 첨단 부문 경기가 흔들린다.
 */
internal class TaiwanModule(
    private val random: Random,
) : CountryModule {
    override val countryId: String = ID

    private val crossStrait =
        CountryPolicyItem(
            key = "twn.cross_strait",
            label = "양안 정책",
            lowLabel = "독립 지향",
            highLabel = "교류 확대",
            description = "교류를 늘리면 해협 긴장이 낮아지고 중국과의 관계가 좋아지지만 미국과의 관계와 안보 성향의 지지를 잃는다.",
            identityReactions = mapOf(Identity.PATRIOTS to -20.0, Identity.FREE_MARKET to 8.0, Identity.YOUTH to -10.0),
        )
    override val policies: List<CountryPolicyItem> = listOf(crossStrait)
    private var policy = INITIAL_POLICY
    private var tension = INITIAL_TENSION

    override fun policyValue(item: CountryPolicyItem): Double = policy.also { require(item == crossStrait) }

    override fun enact(
        item: CountryPolicyItem,
        target: Double,
    ) {
        require(item == crossStrait)
        policy = target
    }

    override fun advanceMonth(context: CountryMonthContext): CountryEffects {
        val target = (BASE_TENSION + POLICY_TENSION * (1.0 - policy) - context.relations(CHINA) / 4.0).coerceIn(5.0, 95.0)
        tension = (tension + (target - tension) * TENSION_SMOOTHING + random.nextDouble(-2.0, 2.0)).coerceIn(0.0, 100.0)
        val news = mutableListOf<CountryNews>()
        val sectors = mutableMapOf(Sector.HIGH_TECH to -TECH_RISK * (tension - INITIAL_TENSION).coerceAtLeast(0.0) / 100.0)
        if (random.nextDouble() < tension / 100.0 * RETALIATION_CHANCE) {
            news += CountryNews("양안관계", "중국이 대만산 농수산물 수입을 중단했다.", major = false)
            sectors[Sector.AGRICULTURE] = -RETALIATION_SHOCK
        }
        return CountryEffects(
            sectorShocks = sectors,
            relationShifts = mapOf(CHINA to (policy - INITIAL_POLICY) * DRIFT, "USA" to (INITIAL_POLICY - policy) * DRIFT),
            news = news,
        )
    }

    override fun indicators(): List<CountryIndicator> =
        listOf(
            CountryIndicator(
                label = "해협 긴장도",
                value = "${tension.roundToInt()} / 100",
                description = "높으면 중국의 무역 보복이 잦아지고 반도체 공급망 우려로 첨단 제조 경기가 흔들린다.",
                level = tension / 100.0,
            ),
        )

    companion object {
        const val ID = "TWN"
        private const val CHINA = "CHN"
        private const val INITIAL_POLICY = 0.3
        private const val INITIAL_TENSION = 65.0
        private const val BASE_TENSION = 20.0
        private const val POLICY_TENSION = 50.0
        private const val TENSION_SMOOTHING = 0.08
        private const val TECH_RISK = 0.1
        private const val RETALIATION_CHANCE = 0.05
        private const val RETALIATION_SHOCK = 0.1
        private const val DRIFT = 2.0
    }
}
