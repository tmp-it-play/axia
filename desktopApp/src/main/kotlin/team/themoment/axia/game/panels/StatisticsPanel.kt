package team.themoment.axia.game.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatWholeDollars
import team.themoment.axia.simulation.economy.CountryHistory
import team.themoment.axia.simulation.economy.EconomySeries
import team.themoment.axia.simulation.nation.NationHistory
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.DossierIndex
import team.themoment.axia.ui.dossier.LineChart
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow
import java.time.YearMonth

private class ChartSpec(
    val title: String,
    val values: List<Double>,
    val start: YearMonth,
    val format: (Double) -> String,
    val zeroLine: Boolean = false,
)

private fun macroCharts(history: CountryHistory) =
    listOf(
        ChartSpec("GDP", history[EconomySeries.GDP], history.start, dollarFormat),
        ChartSpec("경제성장률", history[EconomySeries.GROWTH], history.start, signedPercentFormat, zeroLine = true),
        ChartSpec("물가상승률", history[EconomySeries.INFLATION], history.start, percentFormat),
        ChartSpec("실업률", history[EconomySeries.UNEMPLOYMENT], history.start, percentFormat),
    )

@Composable
internal fun EconomicTrends(history: CountryHistory) {
    val charts = macroCharts(history)
    var selected by remember { mutableStateOf("경제성장률") }
    LedgerHeading("월별 추이")
    DossierIndex(charts.map { it.title }, selected, onSelect = { selected = it })
    HistoryDetail(charts.first { it.title == selected })
}

@Composable
fun StatisticsPanel(
    history: CountryHistory,
    nation: NationHistory,
) {
    val perCapita = ChartSpec("1인당 GDP", history[EconomySeries.GDP_PER_CAPITA], history.start, ::formatWholeDollars)
    val groups =
        mapOf(
            "경제·고용" to macroCharts(history) + perCapita,
            "재정·통화" to
                listOf(
                    ChartSpec("재정수지 (GDP 대비)", history[EconomySeries.BUDGET_BALANCE], history.start, signedPercentFormat, zeroLine = true),
                    ChartSpec("국가부채 (GDP 대비)", history[EconomySeries.DEBT], history.start, percentFormat),
                    ChartSpec("기준금리", history[EconomySeries.POLICY_RATE], history.start, rateFormat),
                ),
            "정치·사회" to
                listOf(
                    ChartSpec("정부 지지율", nation.approval, nation.start, { "${approvalFormat(it)}%" }),
                    ChartSpec("오염 지수", nation.pollution, nation.start, approvalFormat),
                    ChartSpec("범죄 지수", nation.crime, nation.start, approvalFormat),
                ),
        )
    var selected by remember { mutableStateOf("GDP") }
    val active = groups.values.flatten().first { it.title == selected }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 720.dp
        LedgerSpread(
            stacked = compact,
            leftWeight = 0.62f,
            left = {
                LedgerHeading("지표 선택")
                if (compact) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        groups.values.flatten().forEach { spec ->
                            PaperTag(spec.title, selected = spec == active, onClick = { selected = spec.title })
                        }
                    }
                } else {
                    groups.forEach { (label, charts) ->
                        SectionTitle(label)
                        Column(Modifier.selectableGroup()) {
                            charts.forEach { spec ->
                                val chosen = spec == active
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(if (chosen) DossierColors.blueInk else Color.Transparent)
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .selectable(chosen, role = Role.RadioButton, onClick = { selected = spec.title })
                                        .padding(horizontal = 9.dp, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val ink = if (chosen) DossierColors.paper else DossierColors.ink
                                    Text(spec.title, Modifier.weight(1f), style = DossierType.title(14.sp), color = ink)
                                    Text(spec.values.lastOrNull()?.let(spec.format) ?: "—", style = DossierType.number(13.sp), color = ink)
                                }
                            }
                        }
                    }
                }
            },
            right = {
                LedgerHeading(active.title)
                HistoryDetail(active)
            },
        )
    }
}

@Composable
private fun HistoryDetail(spec: ChartSpec) {
    val latest = spec.values.lastOrNull()
    if (latest == null) {
        Hint("월별 기록은 첫 월 결산 후 표시됩니다.")
        return
    }
    val end = spec.start.plusMonths(spec.values.lastIndex.toLong())
    Text(spec.format(latest), style = DossierType.number(32.sp), color = DossierColors.blueInk)
    Hint(end.koreanMonth())
    if (spec.values.size == 1) {
        Spacer(Modifier.height(10.dp))
        Hint("다음 달부터 월별 변화를 비교할 수 있습니다.")
        return
    }
    var recentYear by remember { mutableStateOf(true) }
    if (spec.values.size > 12) {
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PaperTag("최근 12개월", selected = recentYear, onClick = { recentYear = true })
            PaperTag("전체 기간", selected = !recentYear, onClick = { recentYear = false })
        }
    }
    val values = if (recentYear) spec.values.takeLast(12) else spec.values
    val start = end.minusMonths(values.lastIndex.toLong())
    Spacer(Modifier.height(14.dp))
    LineChart(values, start, spec.format, height = 175.dp, zeroLine = spec.zeroLine)
    SectionTitle("최근 월별 기록")
    spec.values.takeLast(6).asReversed().forEachIndexed { index, value ->
        StatRow(end.minusMonths(index.toLong()).koreanMonth(), spec.format(value))
    }
}

private fun YearMonth.koreanMonth() = "${year}년 ${monthValue}월"
