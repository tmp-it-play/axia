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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatWholeDollars
import team.themoment.axia.game.ALIGNMENT_DESCRIPTION
import team.themoment.axia.game.ALIGNMENT_TITLE
import team.themoment.axia.game.GDP_DESCRIPTION
import team.themoment.axia.game.GDP_PER_CAPITA_DESCRIPTION
import team.themoment.axia.game.NationalMetric
import team.themoment.axia.simulation.economy.CountryHistory
import team.themoment.axia.simulation.economy.EconomySeries
import team.themoment.axia.simulation.nation.NationHistory
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.AlignmentChart
import team.themoment.axia.ui.dossier.AlignmentTrend
import team.themoment.axia.ui.dossier.DossierIndex
import team.themoment.axia.ui.dossier.LineChart
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow
import team.themoment.axia.ui.dossier.metricTooltip
import java.time.YearMonth
import java.util.Locale

private enum class HistoryRange(
    val label: String,
    val months: Int?,
) {
    YEAR("최근 1년", 12),
    FIVE_YEARS("최근 5년", 60),
    ALL("전체 기간", null),
}

private class ChartSpec(
    val title: String,
    val values: List<Double>,
    val start: YearMonth,
    val format: (Double) -> String,
    val description: String,
    val zeroLine: Boolean = false,
    /** 충성파·반대파 항목은 [values]에 충성파, 같은 달의 두 번째 열에 반대파를 담는다. */
    val opponents: List<Double>? = null,
)

private fun CountryHistory.chart(
    series: EconomySeries,
    metric: NationalMetric,
    format: (Double) -> String,
    zeroLine: Boolean = false,
) = ChartSpec(metric.title, this[series], start, format, metric.description, zeroLine)

private fun macroCharts(history: CountryHistory) =
    listOf(
        ChartSpec("GDP", history[EconomySeries.GDP], history.start, dollarFormat, GDP_DESCRIPTION),
        history.chart(EconomySeries.GROWTH, NationalMetric.GROWTH, signedPercentFormat, zeroLine = true),
        history.chart(EconomySeries.INFLATION, NationalMetric.INFLATION, percentFormat),
        history.chart(EconomySeries.UNEMPLOYMENT, NationalMetric.UNEMPLOYMENT, percentFormat),
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
    val perCapita =
        ChartSpec("1인당 GDP", history[EconomySeries.GDP_PER_CAPITA], history.start, ::formatWholeDollars, GDP_PER_CAPITA_DESCRIPTION)
    val groups =
        mapOf(
            "경제·고용" to macroCharts(history) + perCapita,
            "재정·통화" to
                listOf(
                    history.chart(EconomySeries.BUDGET_BALANCE, NationalMetric.BALANCE, signedPercentFormat, zeroLine = true),
                    history.chart(EconomySeries.DEBT, NationalMetric.DEBT, percentFormat),
                    history.chart(EconomySeries.POLICY_RATE, NationalMetric.RATE, rateFormat),
                ),
            "정치·사회" to
                listOf(
                    ChartSpec("정부 지지율", nation.approval, nation.start, { "${approvalFormat(it)}%" }, NationalMetric.APPROVAL.description),
                    ChartSpec(
                        ALIGNMENT_TITLE,
                        nation.loyalists,
                        nation.start,
                        { "%,d명".format(Locale.KOREA, it.toLong()) },
                        ALIGNMENT_DESCRIPTION,
                        opponents = nation.opponents,
                    ),
                    ChartSpec(
                        NationalMetric.POLITICAL_CAPITAL.title,
                        nation.politicalCapital,
                        nation.start,
                        { "%.1f점".format(Locale.KOREA, it) },
                        NationalMetric.POLITICAL_CAPITAL.description,
                    ),
                    ChartSpec("오염 지수", nation.pollution, nation.start, approvalFormat, "게임 시작 때의 오염 수준을 100으로 삼습니다."),
                    ChartSpec("범죄 지수", nation.crime, nation.start, approvalFormat, "게임 시작 때의 범죄 수준을 100으로 삼습니다."),
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
                                        .metricTooltip(spec.title, spec.description)
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .selectable(chosen, role = Role.RadioButton, onClick = { selected = spec.title })
                                        .padding(horizontal = 9.dp, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val ink = if (chosen) DossierColors.paper else DossierColors.ink
                                    Text(spec.title, Modifier.weight(1f), style = DossierType.title(14.sp), color = ink)
                                    if (spec.opponents == null) {
                                        Text(
                                            spec.values.lastOrNull()?.let(spec.format) ?: "—",
                                            style = DossierType.number(13.sp),
                                            color = ink,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            right = {
                LedgerHeading(active.title)
                HistoryDetail(active, statistics = true)
            },
        )
    }
}

@Composable
private fun HistoryDetail(
    spec: ChartSpec,
    statistics: Boolean = false,
) {
    var range by remember(statistics) { mutableStateOf(if (statistics) HistoryRange.ALL else HistoryRange.YEAR) }
    if (statistics || spec.values.size > 12) {
        val ranges = if (statistics) HistoryRange.entries else listOf(HistoryRange.YEAR, HistoryRange.ALL)
        Row(Modifier.selectableGroup().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ranges.forEach { option ->
                PaperTag(
                    option.label,
                    modifier = Modifier.semantics { selected = range == option },
                    selected = range == option,
                    onClick = { range = option },
                )
            }
        }
    }
    val latest = spec.values.lastOrNull()
    if (latest == null) {
        Hint("월별 기록은 첫 월 결산 후 표시됩니다.")
        return
    }
    val end = spec.start.plusMonths(spec.values.lastIndex.toLong())
    val count = minOf(range.months ?: spec.values.size, spec.values.size)
    val first = spec.values.size - count
    val values = spec.values.subList(first, spec.values.size)
    val opponents = spec.opponents?.subList(first, spec.values.size)
    val start = end.minusMonths(values.lastIndex.toLong())
    if (opponents == null) {
        Text(
            spec.format(latest),
            modifier = Modifier.metricTooltip(spec.title, spec.description),
            style = DossierType.number(32.sp),
            color = DossierColors.blueInk,
        )
        Hint(if (statistics && start != end) "${start.koreanMonth()} ~ ${end.koreanMonth()}" else end.koreanMonth())
    }
    if (opponents != null) {
        Spacer(Modifier.height(14.dp))
        AlignmentChart(AlignmentTrend(start, values, opponents), interactive = true)
    } else if (values.size == 1) {
        Spacer(Modifier.height(10.dp))
        Hint("다음 달부터 월별 변화를 비교할 수 있습니다.")
        if (!statistics) return
    } else {
        Spacer(Modifier.height(14.dp))
        LineChart(values, start, spec.format, title = spec.title, height = 175.dp, zeroLine = spec.zeroLine)
    }
    SectionTitle(if (statistics) "월별 기록" else "최근 월별 기록")
    var requestedPage by remember(spec.title, range) { mutableIntStateOf(0) }
    val rows = if (statistics) 12 else 6
    val lastPage = values.lastIndex / rows
    val page = requestedPage.coerceAtMost(lastPage)
    if (statistics && lastPage > 0) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperTag("더 최근 기록", enabled = page > 0, onClick = { requestedPage = page - 1 })
            Text("${page + 1} / ${lastPage + 1}", style = DossierType.number(12.sp), color = DossierColors.inkMuted)
            PaperTag("이전 기록", enabled = page < lastPage, onClick = { requestedPage = page + 1 })
        }
    }
    val newest = values.lastIndex - page * rows
    if (opponents != null) AlignmentRecord("월", "충성파", "반대파")
    for (index in newest downTo maxOf(0, newest - rows + 1)) {
        val month = start.plusMonths(index.toLong()).koreanMonth()
        if (opponents == null) {
            StatRow(month, spec.format(values[index]), tooltip = spec.description)
        } else {
            AlignmentRecord(month, spec.format(values[index]), spec.format(opponents[index]))
        }
    }
}

@Composable
private fun AlignmentRecord(
    month: String,
    loyalists: String,
    opponents: String,
) {
    Row(
        Modifier.fillMaxWidth().metricTooltip(ALIGNMENT_TITLE, ALIGNMENT_DESCRIPTION).padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(month, Modifier.weight(1f), style = DossierType.number(13.sp), color = DossierColors.inkMuted)
        for (text in listOf(loyalists, opponents)) {
            Text(
                text,
                Modifier.weight(1f),
                style = DossierType.number(13.sp),
                color = DossierColors.ink,
                textAlign = TextAlign.End,
            )
        }
    }
}

private fun YearMonth.koreanMonth() = "${year}년 ${monthValue}월"
