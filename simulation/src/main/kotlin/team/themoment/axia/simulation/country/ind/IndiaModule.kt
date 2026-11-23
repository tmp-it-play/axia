package team.themoment.axia.simulation.country.ind

import team.themoment.axia.simulation.country.CountryEffects
import team.themoment.axia.simulation.country.CountryIndicator
import team.themoment.axia.simulation.country.CountryModule
import team.themoment.axia.simulation.country.CountryMonthContext
import team.themoment.axia.simulation.country.CountryNews
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.society.Identity
import kotlin.random.Random

/**
 * 인도 고유 시스템: 힌두 민족주의 정책.
 *
 * - 시민권 개정법(CAA)·통일민법 같은 힌두 민족주의 정책 수위(시작 0.6)는 종교·안보 성향이 반기고 시민자유·진보 성향이 반발한다.
 * - 수위가 높을수록 종교 공동체 간 폭력 사태가 잦아 범죄와 여론에 부담을 준다. 파키스탄·방글라데시와의 관계도 떨어진다.
 */
internal class IndiaModule(
    private val random: Random,
) : CountryModule {
    override val countryId: String = ID

    private val hindutva =
        CountryPolicyItem(
            key = "ind.hindu_nationalism",
            label = "힌두 민족주의 정책",
            lowLabel = "세속주의",
            highLabel = "강경 추진",
            description = "높을수록 종교 공동체 간 충돌 위험이 크고 파키스탄·방글라데시와의 관계가 나빠진다.",
            identityReactions =
                mapOf(
                    Identity.RELIGIOUS to 20.0,
                    Identity.PATRIOTS to 10.0,
                    Identity.LIBERTARIANS to -20.0,
                    Identity.EGALITARIANS to -15.0,
                ),
        )
    override val policies: List<CountryPolicyItem> = listOf(hindutva)
    private var level = INITIAL_LEVEL
    private var riots = 0

    override fun policyValue(item: CountryPolicyItem): Double = level.also { require(item == hindutva) }

    override fun enact(
        item: CountryPolicyItem,
        target: Double,
    ) {
        require(item == hindutva)
        level = target
    }

    override fun advanceMonth(context: CountryMonthContext): CountryEffects {
        val shifts = mapOf("PAK" to (INITIAL_LEVEL - level) * DRIFT, "BGD" to (INITIAL_LEVEL - level) * DRIFT)
        if (random.nextDouble() < level * RIOT_CHANCE) {
            riots++
            return CountryEffects(
                identityMood = mapOf(Identity.EGALITARIANS to -3.0, Identity.LIBERTARIANS to -2.0, Identity.RELIGIOUS to 1.0),
                relationShifts = shifts,
                news = listOf(CountryNews("내무부", "종교 공동체 간 충돌이 번져 통행금지령이 내려졌다.", major = true)),
            )
        }
        return CountryEffects(relationShifts = shifts)
    }

    override fun indicators(): List<CountryIndicator> =
        listOf(
            CountryIndicator(
                label = "힌두 민족주의 정책",
                value = "${(level * 100).toInt()} / 100 · 공동체 충돌 ${riots}회",
                description = "수위가 높을수록 공동체 간 충돌이 잦다.",
                level = level,
            ),
        )

    companion object {
        const val ID = "IND"
        private const val INITIAL_LEVEL = 0.6
        private const val RIOT_CHANCE = 0.04
        private const val DRIFT = 1.0
    }
}
