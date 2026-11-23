package team.themoment.axia.simulation.country.prk

import team.themoment.axia.simulation.country.CountryEffects
import team.themoment.axia.simulation.country.CountryIndicator
import team.themoment.axia.simulation.country.CountryModule
import team.themoment.axia.simulation.country.CountryMonthContext
import team.themoment.axia.simulation.country.CountryNews
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.society.Identity
import kotlin.random.Random

/**
 * 북한 고유 시스템: 핵·미사일 개발과 대외 고립.
 *
 * - 핵·미사일 개발 수위(시작 0.9)가 높을수록 시험 발사가 잦고, 한국·미국·일본과의 관계가 떨어진다. 안보 성향은 반긴다.
 * - 수위를 낮추면 관계가 회복될 여지가 생기고, 군수에 묶인 자원이 민수로 풀려 경기가 조금 나아진다.
 */
internal class NorthKoreaModule(
    private val random: Random,
) : CountryModule {
    override val countryId: String = ID

    private val program =
        CountryPolicyItem(
            key = "prk.nuclear_program",
            label = "핵·미사일 개발",
            lowLabel = "동결",
            highLabel = "전력 질주",
            description = "높을수록 시험 발사가 잦아 한국·미국·일본과의 관계가 나빠진다. 낮추면 민수 경제에 자원이 돌아간다.",
            identityReactions = mapOf(Identity.PATRIOTS to 20.0, Identity.PARENTS to -5.0),
        )
    override val policies: List<CountryPolicyItem> = listOf(program)
    private var level = INITIAL_LEVEL
    private var tests = 0

    override fun policyValue(item: CountryPolicyItem): Double = level.also { require(item == program) }

    override fun enact(
        item: CountryPolicyItem,
        target: Double,
    ) {
        require(item == program)
        level = target
    }

    override fun advanceMonth(context: CountryMonthContext): CountryEffects {
        val gap = CIVILIAN_DIVIDEND * (INITIAL_LEVEL - level)
        if (random.nextDouble() < level * TEST_CHANCE) {
            tests++
            return CountryEffects(
                outputGapShock = gap,
                identityMood = mapOf(Identity.PATRIOTS to 1.0),
                relationShifts = mapOf("KOR" to -3.0, "USA" to -3.0, "JPN" to -3.0, "CHN" to -0.5),
                news = listOf(CountryNews("국방과학원", "신형 탄도미사일 시험 발사에 성공했다고 발표했다.", major = false)),
            )
        }
        return CountryEffects(outputGapShock = gap)
    }

    override fun indicators(): List<CountryIndicator> =
        listOf(
            CountryIndicator(
                label = "핵·미사일 개발",
                value = "${(level * 100).toInt()} / 100 · 시험 발사 ${tests}회",
                description = "수위가 높을수록 시험 발사가 잦다.",
                level = level,
            ),
        )

    companion object {
        const val ID = "PRK"
        private const val INITIAL_LEVEL = 0.9
        private const val TEST_CHANCE = 0.4
        private const val CIVILIAN_DIVIDEND = 0.0005
    }
}
