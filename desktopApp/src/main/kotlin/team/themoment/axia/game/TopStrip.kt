package team.themoment.axia.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatPercent
import team.themoment.axia.format.formatRate
import team.themoment.axia.simulation.ClockState
import team.themoment.axia.simulation.economy.EconomyIndicators
import team.themoment.axia.simulation.time.GameSpeed
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.Stamp
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREAN)

/** 책상 위 상단 띠. 국가명, 지지율 도장, 핵심 지표 꼬리표, 날짜와 시간 조작. */
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
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(DossierColors.desk)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = countryName, style = DossierType.title(20.sp), color = DossierColors.deskText)
        Stamp(text = "지지율 ${approval.roundToInt()}%", color = stampColor(approval))
        Spacer(Modifier.width(8.dp))
        PaperTag("성장 ${formatPercent(indicators.growth, signed = true)}")
        PaperTag("물가 ${formatPercent(indicators.inflation)}")
        PaperTag("실업 ${formatPercent(indicators.unemployment)}")
        PaperTag("재정 ${formatPercent(indicators.budgetBalance, signed = true)}")
        PaperTag("부채 ${formatPercent(indicators.debtRatio)}")
        PaperTag("금리 ${formatRate(indicators.policyRate)}")
        nextElection?.let { PaperTag(it, selected = true) }
        PaperTag("정치 자본 ${politicalCapital.roundToInt()}")
        Spacer(Modifier.weight(1f))
        headline?.let {
            Text(
                text = it,
                style = DossierType.number(12.sp),
                color = DossierColors.deskText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(2f, fill = false),
            )
        }
        Text(text = DATE_FORMAT.format(clock.date), style = DossierType.number(15.sp), color = DossierColors.deskText)
        PaperTag(text = if (clock.paused) "▶" else "Ⅱ", onClick = onTogglePause)
        PaperTag(text = "하루", enabled = clock.paused, onClick = onAdvanceDay)
        GameSpeed.all.forEach { speed ->
            PaperTag(text = speed.multiplier.toString(), selected = speed == clock.speed, onClick = { onSpeedSelect(speed) })
        }
    }
}

private fun stampColor(approval: Double): Color =
    when {
        approval >= 55.0 -> Color(0xFF8FC48A)
        approval < 40.0 -> Color(0xFFE0675E)
        else -> DossierColors.folder
    }
