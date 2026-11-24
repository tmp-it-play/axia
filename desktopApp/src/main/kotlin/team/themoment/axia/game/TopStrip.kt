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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatPercent
import team.themoment.axia.format.formatRate
import team.themoment.axia.simulation.ClockState
import team.themoment.axia.simulation.economy.EconomyIndicators
import team.themoment.axia.simulation.time.GameSpeed
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.console.ConsoleBrass
import team.themoment.axia.ui.console.ConsoleKey
import team.themoment.axia.ui.console.ConsoleMuted
import team.themoment.axia.ui.console.ConsoleText
import team.themoment.axia.ui.console.consoleFrame
import team.themoment.axia.ui.dossier.steelTexture
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREAN)
private val grooveDark = Color(0xFF1A201E)
private val grooveLight = Color(0x887B806F)

/** 날짜 옆에 시간 조작계를 두고, 그 아래 한 줄에는 아이콘과 수치를 짝지어 표시한다. */
@Composable
fun TopStrip(
    countryName: String,
    approval: Double,
    indicators: EconomyIndicators,
    clock: ClockState,
    nextElection: String?,
    politicalCapital: Double,
    headline: String?,
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    ConsoleGlyph(ConsoleSymbol.BALLOT_PAPER, Modifier.size(15.dp))
                    Text(it, style = DossierType.number(12.sp), color = ConsoleBrass, maxLines = 1)
                }
            }
            Box(Modifier.weight(1f)) {
                headline?.let {
                    Text(it, style = DossierType.number(12.sp), color = ConsoleMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ConsoleGlyph(ConsoleSymbol.CALENDAR, Modifier.size(17.dp))
                Text(DATE_FORMAT.format(clock.date), style = DossierType.number(15.sp, FontWeight.SemiBold), color = ConsoleText)
            }
            ClockControls(clock, onTogglePause, onSpeedSelect, onAdvanceDay)
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(grooveDark))
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(grooveLight))
        Row(
            modifier = Modifier.fillMaxWidth().height(46.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val cell = Modifier.weight(1f)
            ConsoleMetric(ConsoleSymbol.BALLOT_BOX, "지지율", "${approval.roundToInt()}%", cell)
            Groove()
            ConsoleMetric(ConsoleSymbol.LECTERN, "정치 자본", politicalCapital.roundToInt().toString(), cell)
            Groove()
            ConsoleMetric(ConsoleSymbol.GROWTH, "성장", formatPercent(indicators.growth, signed = true), cell)
            Groove()
            ConsoleMetric(ConsoleSymbol.PRICE_TAG, "물가", formatPercent(indicators.inflation), cell)
            Groove()
            ConsoleMetric(ConsoleSymbol.BRIEFCASE, "실업", formatPercent(indicators.unemployment), cell)
            Groove()
            ConsoleMetric(ConsoleSymbol.SCALES, "재정", formatPercent(indicators.budgetBalance, signed = true), cell)
            Groove()
            ConsoleMetric(ConsoleSymbol.BOND, "부채", formatPercent(indicators.debtRatio), cell)
            Groove()
            ConsoleMetric(ConsoleSymbol.INTEREST, "금리", formatRate(indicators.policyRate), cell)
        }
    }
}

@Composable
private fun ConsoleMetric(
    symbol: ConsoleSymbol,
    label: String,
    value: String,
    modifier: Modifier,
) {
    Column(modifier.semantics(mergeDescendants = true) {}.padding(horizontal = 6.dp)) {
        Text(label, color = ConsoleMuted, style = DossierType.number(11.sp), maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ConsoleGlyph(symbol, Modifier.size(26.dp))
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
            modifier = Modifier.width(34.dp).height(32.dp),
        ) { ink ->
            ConsoleGlyph(if (clock.paused) ConsoleSymbol.PLAY else ConsoleSymbol.PAUSE, Modifier.size(15.dp), ink)
        }
        ConsoleKey(onClick = onAdvanceDay, enabled = clock.paused, modifier = Modifier.width(55.dp).height(32.dp)) { ink ->
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
                modifier = Modifier.width(30.dp).height(32.dp),
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
