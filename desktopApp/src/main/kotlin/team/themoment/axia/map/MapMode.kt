package team.themoment.axia.map

import androidx.compose.ui.graphics.Color
import team.themoment.axia.simulation.economy.EconomyIndicators
import team.themoment.axia.simulation.economy.EconomySnapshot

/** 값을 구간으로 나눠 색칠하는 단계 구분도 척도. [thresholds]보다 하나 많은 [colors]와 [labels]를 가진다. */
class ChoroplethScale(
    val thresholds: List<Double>,
    val colors: List<Color>,
    val labels: List<String>,
    val value: (EconomyIndicators) -> Double,
) {
    init {
        require(colors.size == thresholds.size + 1 && labels.size == colors.size)
    }

    fun colorOf(indicators: EconomyIndicators): Color {
        val measured = value(indicators)
        return colors[thresholds.count { measured >= it }]
    }
}

enum class MapMode(
    val label: String,
    val scale: ChoroplethScale?,
) {
    POLITICAL("정치", null),
    GDP_PER_CAPITA(
        "1인당 GDP",
        ChoroplethScale(
            thresholds = listOf(1_000.0, 4_000.0, 13_000.0, 30_000.0, 60_000.0),
            colors = MapPalette.wealth,
            labels = listOf("1천 달러 미만", "1천~4천 달러", "4천~1.3만 달러", "1.3만~3만 달러", "3만~6만 달러", "6만 달러 이상"),
            value = EconomyIndicators::gdpPerCapita,
        ),
    ),
    GROWTH(
        "경제성장률",
        ChoroplethScale(
            thresholds = listOf(-0.02, 0.0, 0.02, 0.04, 0.06),
            colors = MapPalette.diverging,
            labels = listOf("-2% 미만", "-2~0%", "0~2%", "2~4%", "4~6%", "6% 이상"),
            value = EconomyIndicators::growth,
        ),
    ),
    INFLATION(
        "물가상승률",
        ChoroplethScale(
            thresholds = listOf(0.02, 0.04, 0.07, 0.15, 0.5),
            colors = MapPalette.severity,
            labels = listOf("2% 미만", "2~4%", "4~7%", "7~15%", "15~50%", "50% 이상"),
            value = EconomyIndicators::inflation,
        ),
    ),
    UNEMPLOYMENT(
        "실업률",
        ChoroplethScale(
            thresholds = listOf(0.03, 0.05, 0.08, 0.12, 0.2),
            colors = MapPalette.severity,
            labels = listOf("3% 미만", "3~5%", "5~8%", "8~12%", "12~20%", "20% 이상"),
            value = EconomyIndicators::unemployment,
        ),
    ),
    DEBT(
        "국가부채",
        ChoroplethScale(
            thresholds = listOf(0.3, 0.6, 0.9, 1.2, 2.0),
            colors = MapPalette.severity,
            labels = listOf("GDP의 30% 미만", "30~60%", "60~90%", "90~120%", "120~200%", "200% 이상"),
            value = EconomyIndicators::debtRatio,
        ),
    ),
    ;

    fun colorOf(
        fill: CountryFill,
        economy: EconomySnapshot,
    ): Color {
        if (fill.unclaimed) return MapPalette.unclaimed
        val scale = scale ?: return MapPalette.countries[fill.colorIndex]
        val indicators = economy.countries[fill.countryId] ?: return MapPalette.unclaimed
        return scale.colorOf(indicators)
    }
}
