package team.themoment.axia.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatCompactPopulation
import team.themoment.axia.format.formatPercent
import team.themoment.axia.format.formatRate
import team.themoment.axia.simulation.ClockState
import team.themoment.axia.simulation.economy.CountryHistory
import team.themoment.axia.simulation.economy.EconomyIndicators
import team.themoment.axia.simulation.nation.NationSnapshot
import team.themoment.axia.simulation.time.GameSpeed
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.console.ConsoleBrass
import team.themoment.axia.ui.console.ConsoleKey
import team.themoment.axia.ui.console.ConsoleMuted
import team.themoment.axia.ui.console.ConsoleText
import team.themoment.axia.ui.console.consoleFrame
import team.themoment.axia.ui.dossier.TooltipTrend
import team.themoment.axia.ui.dossier.controlTooltip
import team.themoment.axia.ui.dossier.metricTooltip
import team.themoment.axia.ui.dossier.steelTexture
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREAN)
private val grooveDark = Color(0xFF1A201E)
private val grooveLight = Color(0x887B806F)
private val metricRowHeight = 46.dp

/** 날짜 옆에 시간 조작계를 두고, 그 아래 한 줄에는 아이콘과 수치를 짝지어 표시한다. */
@Composable
fun TopStrip(
    countryName: String,
    nation: NationSnapshot,
    indicators: EconomyIndicators,
    economyHistory: CountryHistory,
    clock: ClockState,
    nextElection: String?,
    headline: String?,
    pendingEventCount: Int,
    onOpenNewspaper: () -> Unit,
    onTogglePause: () -> Unit,
    onSpeedSelect: (GameSpeed) -> Unit,
    onAdvanceDay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .steelTexture()
            .consoleFrame()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ConsoleGlyph(ConsoleSymbol.FLAG, Modifier.size(22.dp))
                Text(countryName, style = DossierType.title(20.sp), color = ConsoleText, maxLines = 1)
            }
            nextElection?.let {
                Groove(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.metricTooltip("다음 선거", "정치 화면의 선거 탭에서 선거 일정과 후보를 확인할 수 있습니다."),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    ConsoleGlyph(ConsoleSymbol.BALLOT_PAPER, Modifier.size(15.dp))
                    Text(it, style = DossierType.number(12.sp), color = ConsoleBrass, maxLines = 1)
                }
            }
            if (pendingEventCount > 0) {
                ConsoleKey(
                    onClick = onOpenNewspaper,
                    description = "사건 신문 다시 열기",
                    modifier = Modifier.height(30.dp).controlTooltip("대기 중인 사건", "신문을 열어 사건에 대응할 수 있습니다."),
                ) { ink ->
                    Text("사건 ${pendingEventCount}건", Modifier.padding(horizontal = 8.dp), style = DossierType.title(13.sp), color = ink)
                }
            }
            Box(Modifier.weight(1f)) {
                headline?.let {
                    Text(it, style = DossierType.number(12.sp), color = ConsoleMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val date = DATE_FORMAT.format(clock.date)
            Row(
                modifier = Modifier.metricTooltip("캠페인 날짜", "매월 1일에 직전 달의 경제를 결산합니다."),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ConsoleGlyph(ConsoleSymbol.CALENDAR, Modifier.size(17.dp))
                Text(date, style = DossierType.number(15.sp, FontWeight.SemiBold), color = ConsoleText)
            }
            ClockControls(clock, onTogglePause, onSpeedSelect, onAdvanceDay)
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(grooveDark))
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(grooveLight))
        Row(
            modifier = Modifier.fillMaxWidth().height(metricRowHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val trends =
                remember(economyHistory, nation.history) {
                    NationalMetric.entries.associateWith { it.trend(economyHistory, nation.history) }
                }
            val politicalCapital = nation.society.politicalCapital
            for (metric in NationalMetric.entries) {
                if (metric != NationalMetric.APPROVAL) Groove()
                val trend = trends.getValue(metric)
                val value =
                    when (metric) {
                        NationalMetric.APPROVAL -> {
                            "${nation.approval.roundToInt()}%"
                        }

                        NationalMetric.ALIGNMENT -> {
                            Column(Modifier.weight(1.4f).fillMaxHeight().metricTooltip(metric.title, detail = null, trend = trend)) {
                                ConstituencyRow("충성파", metric.symbol, nation.loyalists, Modifier.weight(1f))
                                ConstituencyRow("반대파", ConsoleSymbol.PLACARD, nation.opponents, Modifier.weight(1f))
                            }
                            continue
                        }

                        NationalMetric.POLITICAL_CAPITAL -> {
                            politicalCapital.toInt().toString()
                        }

                        NationalMetric.GROWTH -> {
                            formatPercent(indicators.growth, signed = true)
                        }

                        NationalMetric.INFLATION -> {
                            formatPercent(indicators.inflation)
                        }

                        NationalMetric.UNEMPLOYMENT -> {
                            formatPercent(indicators.unemployment)
                        }

                        NationalMetric.BALANCE -> {
                            formatPercent(indicators.budgetBalance, signed = true)
                        }

                        NationalMetric.DEBT -> {
                            formatPercent(indicators.debtRatio)
                        }

                        NationalMetric.RATE -> {
                            formatRate(indicators.policyRate)
                        }
                    }
                ConsoleMetric(metric, value, trend, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ConstituencyRow(
    name: String,
    symbol: ConsoleSymbol,
    count: Long,
    modifier: Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ConsoleGlyph(symbol, Modifier.size(17.dp))
        Text(name, Modifier.weight(1f), color = ConsoleMuted, style = DossierType.number(12.sp), maxLines = 1)
        Text(
            formatCompactPopulation(count.toDouble()),
            color = ConsoleText,
            style = DossierType.number(16.sp, FontWeight.Bold),
            maxLines = 1,
        )
    }
}

@Composable
private fun ConsoleMetric(
    metric: NationalMetric,
    value: String,
    trend: TooltipTrend?,
    modifier: Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            .metricTooltip(metric.title, metric.description, trend)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 5.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(metric.shortName, color = ConsoleMuted, style = DossierType.number(11.sp), maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ConsoleGlyph(metric.symbol, Modifier.size(26.dp))
            Text(value, color = ConsoleText, style = DossierType.number(18.sp, FontWeight.Bold), maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun ClockControls(
    clock: ClockState,
    onTogglePause: () -> Unit,
    onSpeedSelect: (GameSpeed) -> Unit,
    onAdvanceDay: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        ConsoleKey(
            onClick = onTogglePause,
            description = if (clock.paused) "시간 재생" else "일시정지",
            modifier =
                Modifier
                    .width(
                        34.dp,
                    ).height(32.dp)
                    .controlTooltip(if (clock.paused) "시간 재생" else "일시정지", "스페이스바로 시간을 멈추거나 다시 진행합니다."),
        ) { ink ->
            ConsoleGlyph(if (clock.paused) ConsoleSymbol.PLAY else ConsoleSymbol.PAUSE, Modifier.size(15.dp), ink)
        }
        ConsoleKey(
            onClick = onAdvanceDay,
            enabled = clock.paused,
            modifier = Modifier.width(55.dp).height(32.dp).controlTooltip("하루 진행", "일시정지 중에 게임 시간을 하루 진행합니다."),
        ) { ink ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                ConsoleGlyph(ConsoleSymbol.NEXT_DAY, Modifier.size(12.dp), ink)
                Text("하루", style = DossierType.title(12.sp), color = ink)
            }
        }
        ConsoleGlyph(ConsoleSymbol.SPEED, Modifier.padding(start = 4.dp, end = 2.dp).size(13.dp))
        GameSpeed.all.forEach { speed ->
            ConsoleKey(
                onClick = { onSpeedSelect(speed) },
                active = speed == clock.speed,
                description = "${speed.multiplier}배속",
                modifier =
                    Modifier
                        .width(30.dp)
                        .height(32.dp)
                        .controlTooltip("시간 배속", "실제 1초 동안 진행할 게임 속 날짜 수를 정합니다."),
            ) { ink ->
                Text("${speed.multiplier}×", style = DossierType.number(12.sp, FontWeight.SemiBold), color = ink)
            }
        }
    }
}

@Composable
private fun Groove(modifier: Modifier = Modifier.height(29.dp)) {
    Row(modifier) {
        Spacer(Modifier.width(1.dp).fillMaxHeight().background(grooveDark))
        Spacer(Modifier.width(1.dp).fillMaxHeight().background(grooveLight))
    }
}
