package team.themoment.axia.game.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import team.themoment.axia.simulation.economy.CountryHistory
import team.themoment.axia.simulation.economy.EconomySeries
import team.themoment.axia.simulation.nation.NationHistory
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.ui.dossier.LineChart
import team.themoment.axia.ui.dossier.SectionTitle
import java.time.YearMonth

private class ChartSpec(
    val title: String,
    val values: List<Double>,
    val start: YearMonth,
    val format: (Double) -> String,
    val zeroLine: Boolean = false,
)

/** 빅토리아 3식 통계 모음. 주요 시계열을 두 열로 펼친다. */
@Composable
fun StatisticsPanel(
    history: CountryHistory,
    nation: NationHistory,
) {
    val charts =
        listOf(
            ChartSpec("GDP", history[EconomySeries.GDP], history.start, dollarFormat),
            ChartSpec("1인당 GDP", history[EconomySeries.GDP_PER_CAPITA], history.start, wholeDollarFormat),
            ChartSpec("경제성장률", history[EconomySeries.GROWTH], history.start, signedPercentFormat, zeroLine = true),
            ChartSpec("물가상승률", history[EconomySeries.INFLATION], history.start, percentFormat),
            ChartSpec("실업률", history[EconomySeries.UNEMPLOYMENT], history.start, percentFormat),
            ChartSpec("재정수지 (GDP 대비)", history[EconomySeries.BUDGET_BALANCE], history.start, signedPercentFormat, zeroLine = true),
            ChartSpec("국가부채 (GDP 대비)", history[EconomySeries.DEBT], history.start, percentFormat),
            ChartSpec("기준금리", history[EconomySeries.POLICY_RATE], history.start, rateFormat),
            ChartSpec("정부 지지율", nation.approval, nation.start, approvalFormat),
            ChartSpec("오염 지수", nation.pollution, nation.start, approvalFormat),
            ChartSpec("범죄 지수", nation.crime, nation.start, approvalFormat),
        )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        charts.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { spec ->
                    Column(Modifier.weight(1f)) {
                        SectionTitle(spec.title)
                        LineChart(
                            spec.values,
                            spec.start,
                            spec.format,
                            zeroLine = spec.zeroLine,
                            color = if (spec.title == "정부 지지율") DossierColors.stamp else DossierColors.blueInk,
                        )
                    }
                }
            }
        }
    }
}
