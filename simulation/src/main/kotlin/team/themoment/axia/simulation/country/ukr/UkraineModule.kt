package team.themoment.axia.simulation.country.ukr

import team.themoment.axia.simulation.country.CountryEffects
import team.themoment.axia.simulation.country.CountryIndicator
import team.themoment.axia.simulation.country.CountryModule
import team.themoment.axia.simulation.country.CountryMonthContext
import team.themoment.axia.simulation.country.CountryNews
import kotlin.math.roundToInt

/**
 * 우크라이나 고유 시스템: 서방 지원 의존.
 *
 * - 재정과 경기는 미국·유럽 지원에 크게 기댄다. 지원 수준은 주요 지원국(미국, 독일, 영국, 폴란드, 프랑스)과의 평균 관계가 정한다.
 * - 지원이 시작 수준보다 줄면 산출 갭과 재정이 나빠진다(국채 위험 프리미엄). 크게 줄면 소식으로 알린다.
 */
internal class UkraineModule : CountryModule {
    override val countryId: String = ID
    private var support = 1.0
    private var warned = false

    override fun advanceMonth(context: CountryMonthContext): CountryEffects {
        val average = DONORS.map(context.relations).average()
        val start = startRelation ?: average.also { startRelation = it }
        val target = (1.0 + (average - start) / RELATION_SCALE).coerceIn(0.2, 1.5)
        support += (target - support) / SUPPORT_MONTHS
        val shortfall = (1.0 - support).coerceAtLeast(0.0)
        val news =
            if (support < WARNING_LEVEL && !warned) {
                warned = true
                listOf(CountryNews("재무부", "서방 지원이 크게 줄어 예산 집행에 차질이 우려된다.", major = true))
            } else {
                if (support >= WARNING_LEVEL) warned = false
                emptyList()
            }
        return CountryEffects(outputGapShock = -AID_GAP * shortfall, interestPremium = AID_PREMIUM * shortfall, news = news)
    }

    private var startRelation: Double? = null

    override fun indicators(): List<CountryIndicator> =
        listOf(
            CountryIndicator(
                label = "서방 지원",
                value = "${(support * 100).roundToInt()}% (2024년 초 = 100)",
                description = "미국·독일·영국·폴란드·프랑스와의 관계가 나빠지면 줄어 경기와 재정이 흔들린다.",
                level = (support / 1.5).coerceIn(0.0, 1.0),
            ),
        )

    companion object {
        const val ID = "UKR"
        private val DONORS = listOf("USA", "DEU", "GBR", "POL", "FRA")
        private const val RELATION_SCALE = 60.0
        private const val SUPPORT_MONTHS = 6.0
        private const val WARNING_LEVEL = 0.7
        private const val AID_GAP = 0.004
        private const val AID_PREMIUM = 0.03
    }
}
