package team.themoment.axia.game.panels

import androidx.compose.runtime.Composable
import team.themoment.axia.simulation.economy.CountryHistory
import team.themoment.axia.simulation.economy.EconomyIndicators
import team.themoment.axia.simulation.economy.EconomySeries
import team.themoment.axia.ui.dossier.LineChart
import team.themoment.axia.ui.dossier.SectionTitle

@Composable
fun EconomyPanel(
    indicators: EconomyIndicators,
    history: CountryHistory,
) {
    SectionTitle("현재 지표")
    KeyIndicatorRows(indicators)
    SectionTitle("경제성장률")
    LineChart(history[EconomySeries.GROWTH], history.start, signedPercentFormat, zeroLine = true)
    SectionTitle("물가상승률")
    LineChart(history[EconomySeries.INFLATION], history.start, percentFormat)
    SectionTitle("실업률")
    LineChart(history[EconomySeries.UNEMPLOYMENT], history.start, percentFormat)
    SectionTitle("GDP")
    LineChart(history[EconomySeries.GDP], history.start, dollarFormat)
}
