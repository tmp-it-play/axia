package team.themoment.axia.simulation.country.isr

import team.themoment.axia.simulation.country.CountryEffects
import team.themoment.axia.simulation.country.CountryIndicator
import team.themoment.axia.simulation.country.CountryModule
import team.themoment.axia.simulation.country.CountryMonthContext
import team.themoment.axia.simulation.country.CountryNews
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.society.Identity
import kotlin.random.Random

/**
 * 이스라엘 고유 시스템: 사법 개혁과 하레디 병역.
 *
 * - 사법 개혁(대법원 권한 축소)은 종교·안보 성향이 반기고 시민자유·진보 성향이 강하게 반발한다.
 *   수위가 높으면 2023년 같은 대규모 반대 시위가 일어나 경기와 여론을 흔든다. 시작값 0.4는 2023년 합리성 기준 개정을 반영한 게임 가정이다.
 * - 하레디(초정통파) 병역 면제 축소는 시민자유·안보 성향이 반기고 종교 성향이 반발한다.
 */
internal class IsraelModule(
    private val random: Random,
) : CountryModule {
    override val countryId: String = ID

    private val judicial =
        CountryPolicyItem(
            key = "isr.judicial_overhaul",
            label = "사법 개혁",
            lowLabel = "대법원 권한 유지",
            highLabel = "대법원 권한 축소",
            description = "높을수록 대규모 반대 시위 위험이 크다.",
            identityReactions =
                mapOf(Identity.RELIGIOUS to 15.0, Identity.PATRIOTS to 8.0, Identity.LIBERTARIANS to -30.0, Identity.EGALITARIANS to -20.0),
        )
    private val draft =
        CountryPolicyItem(
            key = "isr.haredi_draft",
            label = "하레디 징병",
            lowLabel = "면제 유지",
            highLabel = "전면 징병",
            description = "초정통파 신학생의 병역 면제를 줄인다. 연정의 종교 정당이 반발한다.",
            identityReactions = mapOf(Identity.LIBERTARIANS to 10.0, Identity.PATRIOTS to 10.0, Identity.RELIGIOUS to -25.0),
        )
    override val policies: List<CountryPolicyItem> = listOf(judicial, draft)
    private var judicialLevel = INITIAL_OVERHAUL
    private var draftLevel = INITIAL_DRAFT
    private var protestMonths = 0

    override fun policyValue(item: CountryPolicyItem): Double =
        when (item) {
            judicial -> judicialLevel
            draft -> draftLevel
            else -> error("${item.key} is not a policy of $countryId")
        }

    override fun enact(
        item: CountryPolicyItem,
        target: Double,
    ) {
        when (item) {
            judicial -> judicialLevel = target
            draft -> draftLevel = target
            else -> error("${item.key} is not a policy of $countryId")
        }
    }

    override fun advanceMonth(context: CountryMonthContext): CountryEffects {
        if (protestMonths > 0) protestMonths--
        val chance = (judicialLevel - SAFE_LEVEL).coerceAtLeast(0.0) * PROTEST_CHANCE
        if (protestMonths == 0 && random.nextDouble() < chance) {
            protestMonths = PROTEST_MONTHS
            return CountryEffects(
                outputGapShock = -PROTEST_SHOCK,
                identityMood = mapOf(Identity.LIBERTARIANS to -4.0, Identity.EGALITARIANS to -3.0),
                news = listOf(CountryNews("시민사회", "사법 개혁에 반대하는 수십만 명이 텔아비브 거리를 메웠다. 예비군 일부가 복무 거부를 선언했다.", major = true)),
            )
        }
        return CountryEffects.NONE
    }

    override fun indicators(): List<CountryIndicator> =
        listOf(
            CountryIndicator(
                label = "사법 개혁",
                value = "${(judicialLevel * 100).toInt()} / 100" + if (protestMonths > 0) " · 대규모 시위 중" else "",
                description = "${(SAFE_LEVEL * 100).toInt()}을 넘으면 대규모 반대 시위 위험이 생긴다.",
                level = judicialLevel,
            ),
            CountryIndicator(
                label = "하레디 징병",
                value = "${(draftLevel * 100).toInt()} / 100",
                description = "초정통파 병역 면제 축소 정도.",
                level = draftLevel,
            ),
        )

    companion object {
        const val ID = "ISR"
        private const val INITIAL_OVERHAUL = 0.4
        private const val INITIAL_DRAFT = 0.1
        private const val SAFE_LEVEL = 0.3
        private const val PROTEST_CHANCE = 0.08
        private const val PROTEST_MONTHS = 4
        private const val PROTEST_SHOCK = 0.003
    }
}
