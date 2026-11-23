package team.themoment.axia.simulation.country.kor

import team.themoment.axia.simulation.country.CountryEffects
import team.themoment.axia.simulation.country.CountryIndicator
import team.themoment.axia.simulation.country.CountryModule
import team.themoment.axia.simulation.country.CountryMonthContext
import team.themoment.axia.simulation.country.CountryNews
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.nation.PopulationGroup
import team.themoment.axia.simulation.society.Identity
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 한국 고유 시스템: 남북 긴장도와 대북 정책 기조.
 *
 * - 긴장도(0~100)는 대북 기조와 북한과의 관계가 정하는 목표로 매달 천천히 움직이고, 북한의 도발이 끌어올린다.
 * - 시작값 70은 2023년 11월 9·19 군사합의 효력 정지 이후의 고조 상태를 반영한 게임 가정이다.
 * - 도발 확률은 긴장도에 비례한다. 도발은 안보 성향을 결집시키고 시장에 작은 충격을 준다.
 *   긴장도가 매우 높으면 국지 무력 충돌이 일어날 수 있다.
 */
internal class KoreaModule(
    private val random: Random,
) : CountryModule {
    override val countryId: String = ID

    private val northPolicy =
        CountryPolicyItem(
            key = "kor.north_policy",
            label = "대북 정책 기조",
            lowLabel = "강경 압박",
            highLabel = "대화·교류",
            description = "북한 도발 빈도와 남북 긴장도의 목표를 바꾼다. 안보 성향은 강경 기조를, 진보 성향은 대화를 선호한다.",
            identityReactions = mapOf(Identity.PATRIOTS to -30.0, Identity.EGALITARIANS to 20.0, Identity.YOUTH to 4.0),
            groupReactions = mapOf(PopulationGroup.RETIREES to -6.0),
        )
    override val policies: List<CountryPolicyItem> = listOf(northPolicy)

    private var policy = INITIAL_POLICY
    private var tension = INITIAL_TENSION
    private var provocations = 0
    private var lastProvocation: LocalDate? = null
    private var lastClash: LocalDate? = null

    override fun policyValue(item: CountryPolicyItem): Double = policy.also { require(item == northPolicy) }

    override fun enact(
        item: CountryPolicyItem,
        target: Double,
    ) {
        require(item == northPolicy)
        policy = target.coerceIn(0.0, 1.0)
    }

    override fun advanceMonth(context: CountryMonthContext): CountryEffects {
        val relation = context.relations(NORTH_KOREA)
        val target = (BASE_TENSION + POLICY_TENSION * (1.0 - policy) - RELATION_TENSION * relation / 100.0).coerceIn(5.0, 95.0)
        tension += (target - tension) * TENSION_SMOOTHING + random.nextDouble(-2.0, 2.0)
        val news = mutableListOf<CountryNews>()
        val mood = mutableMapOf<Identity, Double>()
        var gapShock = 0.0
        if (random.nextDouble() < tension / 100.0 * PROVOCATION_CHANCE) {
            provocations++
            lastProvocation = context.date
            tension += PROVOCATION_TENSION
            mood[Identity.PATRIOTS] = RALLY
            gapShock -= PROVOCATION_SHOCK
            news += CountryNews("남북관계", PROVOCATIONS.random(random), major = false)
        }
        if (tension > CLASH_TENSION && random.nextDouble() < (tension - CLASH_TENSION) / 100.0 * CLASH_CHANCE) {
            lastClash = context.date
            tension += CLASH_TENSION_JUMP
            mood[Identity.PATRIOTS] = (mood[Identity.PATRIOTS] ?: 0.0) + RALLY * 2
            mood[Identity.PARENTS] = -RALLY
            gapShock -= CLASH_SHOCK
            news += CountryNews("남북관계", "서해 접경 수역에서 남북 간 무력 충돌이 발생했다. 금융시장이 출렁였다.", major = true)
        }
        tension = tension.coerceIn(0.0, 100.0)
        return CountryEffects(outputGapShock = gapShock, identityMood = mood, news = news)
    }

    override fun indicators(): List<CountryIndicator> =
        listOf(
            CountryIndicator(
                label = "남북 긴장도",
                value = "${tension.roundToInt()} / 100",
                description = "높을수록 북한의 도발과 무력 충돌 가능성이 크다. 대북 기조와 북한과의 관계가 목표를 정한다.",
                level = tension / 100.0,
            ),
            CountryIndicator(
                label = "북한 도발",
                value = if (provocations == 0) "없음" else "${provocations}회 · 최근 $lastProvocation",
                description = "게임 시작 이후 탄도미사일 발사, 정찰위성 발사, 접경 도발 횟수.",
            ),
        )

    companion object {
        const val ID = "KOR"
        private const val NORTH_KOREA = "PRK"
        private const val INITIAL_POLICY = 0.2
        private const val INITIAL_TENSION = 70.0
        private const val BASE_TENSION = 25.0
        private const val POLICY_TENSION = 35.0
        private const val RELATION_TENSION = 20.0
        private const val TENSION_SMOOTHING = 0.08
        private const val PROVOCATION_CHANCE = 0.45
        private const val PROVOCATION_TENSION = 3.0
        private const val PROVOCATION_SHOCK = 0.0005
        private const val RALLY = 1.5
        private const val CLASH_TENSION = 80.0
        private const val CLASH_CHANCE = 0.3
        private const val CLASH_TENSION_JUMP = 8.0
        private const val CLASH_SHOCK = 0.004
        private val PROVOCATIONS =
            listOf(
                "북한이 동해상으로 탄도미사일을 발사했다.",
                "북한이 군사정찰위성 발사를 시도했다.",
                "북한이 접경 지역에 대남 풍선을 날려 보냈다.",
                "북한이 서해 완충구역 일대에서 해안포 사격을 했다.",
                "북한이 순항미사일 시험 발사를 발표했다.",
            )
    }
}
