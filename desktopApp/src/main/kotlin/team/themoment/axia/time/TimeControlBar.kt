package team.themoment.axia.time

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import team.themoment.axia.simulation.ClockState
import team.themoment.axia.simulation.time.GameSpeed
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREAN)

@Composable
fun TimeControlBar(
    clock: ClockState,
    onTogglePause: () -> Unit,
    onSpeedSelect: (GameSpeed) -> Unit,
    onAdvanceDay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.height(56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = DATE_FORMAT.format(clock.date), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            FilledTonalIconButton(
                onClick = onTogglePause,
                modifier = Modifier.semantics { contentDescription = if (clock.paused) "재개" else "일시정지" },
            ) {
                PlayPauseGlyph(paused = clock.paused)
            }
            OutlinedButton(onClick = onAdvanceDay, enabled = clock.paused) {
                Text("하루 넘기기")
            }
            Spacer(Modifier.width(8.dp))
            Text(text = "배속", style = MaterialTheme.typography.labelLarge)
            GameSpeed.all.forEach { speed ->
                SpeedButton(speed = speed, selected = speed == clock.speed, onClick = { onSpeedSelect(speed) })
            }
        }
    }
}

@Composable
private fun SpeedButton(
    speed: GameSpeed,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (selected) colors.primary else colors.surface,
        contentColor = if (selected) colors.onPrimary else colors.onSurface,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = Modifier.size(32.dp).semantics { contentDescription = "${speed.multiplier}배속" },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = speed.multiplier.toString(), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 재생 중이면 일시정지 기호, 일시정지 중이면 재생 기호를 그린다. */
@Composable
private fun PlayPauseGlyph(paused: Boolean) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(14.dp)) {
        if (paused) {
            val triangle =
                Path().apply {
                    moveTo(size.width * 0.15f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(size.width * 0.15f, size.height)
                    close()
                }
            drawPath(triangle, color)
        } else {
            val bar = Size(size.width * 0.32f, size.height)
            drawRect(color, topLeft = Offset(size.width * 0.08f, 0f), size = bar)
            drawRect(color, topLeft = Offset(size.width * 0.6f, 0f), size = bar)
        }
    }
}
