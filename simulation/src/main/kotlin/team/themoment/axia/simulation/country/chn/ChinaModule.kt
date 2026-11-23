package team.themoment.axia.simulation.country.chn

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
 * 중국 고유 시스템: 부동산 부채 조정과 대만 압박.
 *
 * - 부동산 부채 지수(시작 100)는 2021년 이후 부동산 침체를 반영한다. 지수가 높을수록 매달 건설·금융 부문 경기를 깎고,
 *   "부동산 부양" 정책을 올리면 빨리 내려가지만 지방정부 부채(국가부채)가 늘어난다.
 * - 대만 압박 수위가 높으면 대만해협 군사훈련이 잦아지고, 대만·미국·일본과의 관계가 떨어진다. 안보 성향은 반긴다.
 */
internal class ChinaModule(
    private val random: Random,
) : CountryModule {
    override val countryId: String = ID

    private val stimulus =
        CountryPolicyItem(
            key = "chn.property_stimulus",
            label = "부동산 부양",
            lowLabel = "구조조정 방치",
            highLabel = "대규모 부양",
            description = "올릴수록 부동산 부채 지수가 빨리 내려가 건설·금융 부문 부담이 줄지만, 지방정부 부채가 늘어난다.",
            identityReactions = mapOf(Identity.FREE_MARKET to -5.0, Identity.PARENTS to 5.0),
        )
    private val pressure =
        CountryPolicyItem(
            key = "chn.taiwan_pressure",
            label = "대만 압박 수위",
            lowLabel = "현상 유지",
            highLabel = "무력 시위",
            description = "높을수록 대만해협 군사훈련이 잦아지고 대만·미국·일본과의 관계가 떨어진다.",
            identityReactions = mapOf(Identity.PATRIOTS to 15.0),
        )
    override val policies: List<CountryPolicyItem> = listOf(stimulus, pressure)
    private var stimulusLevel = INITIAL_STIMULUS
    private var pressureLevel = INITIAL_PRESSURE
    private var propertyDebt = 100.0
    private var drills = 0

    override fun policyValue(item: CountryPolicyItem): Double =
        when (item) {
            stimulus -> stimulusLevel
            pressure -> pressureLevel
            else -> error("${item.key} is not a policy of $countryId")
        }

    override fun enact(
        item: CountryPolicyItem,
        target: Double,
    ) {
        when (item) {
            stimulus -> stimulusLevel = target
            pressure -> pressureLevel = target
            else -> error("${item.key} is not a policy of $countryId")
        }
    }

    override fun advanceMonth(context: CountryMonthContext): CountryEffects {
        propertyDebt = (propertyDebt * (1.0 - (BASE_DELEVERAGING + STIMULUS_DELEVERAGING * stimulusLevel) / 12.0)).coerceAtLeast(MIN_DEBT)
        // 부채 부담(시작 1 → 바닥 0). 산출 갭 충격은 갭 지속성 때문에 누적되므로 작게 준다.
        val burden = (propertyDebt - MIN_DEBT) / (100.0 - MIN_DEBT)
        val news = mutableListOf<CountryNews>()
        val shifts = mutableMapOf<String, Double>()
        if (random.nextDouble() < pressureLevel * DRILL_CHANCE) {
            drills++
            news += CountryNews("인민해방군", "인민해방군이 대만 주변에서 대규모 합동 군사훈련을 벌였다.", major = true)
            shifts["TWN"] = -6.0
            shifts["USA"] = -3.0
            shifts["JPN"] = -2.0
        }
        return CountryEffects(
            outputGapShock = -PROPERTY_DRAG * burden * (1.0 - STIMULUS_RELIEF * stimulusLevel),
            sectorShocks = mapOf(Sector.CONSTRUCTION to -CONSTRUCTION_DRAG * burden, Sector.FINANCE to -FINANCE_DRAG * burden),
            interestPremium = stimulusLevel * LOCAL_DEBT_PREMIUM,
            relationShifts = shifts,
            news = news,
        )
    }

    override fun indicators(): List<CountryIndicator> =
        listOf(
            CountryIndicator(
                label = "부동산 부채 지수",
                value = "${propertyDebt.roundToInt()} (2024년 = 100)",
                description = "높을수록 건설·금융 부문 경기를 깎는다. 부동산 부양으로 빨리 줄일 수 있지만 지방정부 부채 위험이 국채 금리에 붙는다.",
                level = propertyDebt / 100.0,
            ),
            CountryIndicator(
                label = "대만해협 군사훈련",
                value = "${drills}회",
                description = "대만 압박 수위가 높을수록 잦다. 훈련마다 대만·미국·일본과의 관계가 떨어진다.",
            ),
        )

    companion object {
        const val ID = "CHN"
        private const val INITIAL_STIMULUS = 0.3
        private const val INITIAL_PRESSURE = 0.5
        private const val BASE_DELEVERAGING = 0.06
        private const val STIMULUS_DELEVERAGING = 0.12
        private const val MIN_DEBT = 40.0
        private const val PROPERTY_DRAG = 0.0006
        private const val STIMULUS_RELIEF = 0.5
        private const val CONSTRUCTION_DRAG = 0.06
        private const val FINANCE_DRAG = 0.03
        private const val LOCAL_DEBT_PREMIUM = 0.004
        private const val DRILL_CHANCE = 0.12
    }
}
