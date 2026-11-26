package team.themoment.axia.ui.dossier

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatCompactPopulation
import team.themoment.axia.theme.DossierType
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class AlignmentTrend(
    val start: YearMonth,
    val loyalists: List<Double>,
    val opponents: List<Double>,
) : TooltipTrend {
    init {
        require(loyalists.size == opponents.size)
    }

    fun reading(index: Int): String =
        String.format(Locale.KOREA, "충성파 %,d명 · 반대파 %,d명", loyalists[index].roundToLong(), opponents[index].roundToLong())
}

private val monthLabel = DateTimeFormatter.ofPattern("yyyy.MM")
private val loyalistInk = Color(0xFF86BF73)
private val opponentInk = Color(0xFFEA776D)
private val axisInk = Color(0xFFD5D3C2)
private val gridInk = Color(0xFF383D36)
private val plotMargin = 7.dp

/** 양쪽 인원을 같은 0명 기준선 위에 올려 절대 규모와 교차 시점을 함께 읽는다. */
@Composable
internal fun AlignmentChart(
    trend: AlignmentTrend,
    interactive: Boolean = false,
) {
    if (trend.loyalists.isEmpty()) return
    val last = trend.loyalists.lastIndex
    val maximum = remember(trend) { maxOf(1.0, trend.loyalists.max(), trend.opponents.max()) }
    val firstMonth = monthLabel.format(trend.start)
    val lastMonth = monthLabel.format(trend.start.plusMonths(last.toLong()))
    val labelStyle = DossierType.number(11.sp).copy(color = axisInk)
    val measurer = rememberTextMeasurer()
    val maxLabel = measurer.measure(formatCompactPopulation(maximum), labelStyle)
    val zeroLabel = measurer.measure("0", labelStyle)
    val left = with(LocalDensity.current) { maxLabel.size.width + 6.dp.toPx() }
    val description =
        if (last == 0) {
            "$firstMonth ${trend.reading(0)}"
        } else {
            "$firstMonth ${trend.reading(0)}, $lastMonth ${trend.reading(last)}"
        }
    var hovered by remember(trend) { mutableIntStateOf(-1) }
    val hover =
        if (interactive) {
            Modifier
                .pointerInput(left, trend) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val mouse = event.changes.firstOrNull { it.type == PointerType.Mouse } ?: continue
                            val right = size.width - plotMargin.toPx()
                            hovered =
                                if ((event.type == PointerEventType.Move || event.type == PointerEventType.Enter) &&
                                    !event.buttons.areAnyPressed && right > left && mouse.position.x in left..right &&
                                    mouse.position.y in plotMargin.toPx()..size.height - plotMargin.toPx()
                                ) {
                                    ((mouse.position.x - left) / (right - left) * last).roundToInt()
                                } else {
                                    -1
                                }
                        }
                    }
                }.metricReadingTooltip(
                    title = if (hovered >= 0) monthLabel.format(trend.start.plusMonths(hovered.toLong())) else "",
                    reading = if (hovered >= 0) trend.reading(hovered) else "",
                )
        } else {
            Modifier
        }
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF1D211D)).padding(horizontal = 6.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AlignmentKey("충성파", loyalistInk)
            AlignmentKey("반대파", opponentInk)
        }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(if (interactive) 166.dp else 100.dp)
                .semantics { contentDescription = description }
                .then(hover)
                .drawWithCache {
                    val margin = plotMargin.toPx()
                    val right = (size.width - margin).coerceAtLeast(left)
                    val bottom = size.height - margin
                    val width = right - left
                    val height = bottom - margin

                    fun x(i: Int) = if (last == 0) left + width / 2 else left + width * (i.toFloat() / last)

                    fun y(count: Double) = bottom - (count / maximum).toFloat() * height

                    fun line(counts: List<Double>) =
                        Path().apply {
                            moveTo(x(0), y(counts.first()))
                            for (i in 1..last) lineTo(x(i), y(counts[i]))
                        }

                    val loyalistLine = line(trend.loyalists)
                    val opponentLine = line(trend.opponents)
                    val overlap = Path()
                    var wasEqual = false
                    for (i in 0..last) {
                        val equal = trend.loyalists[i] == trend.opponents[i]
                        if (equal) {
                            if (wasEqual) overlap.lineTo(x(i), y(trend.loyalists[i])) else overlap.moveTo(x(i), y(trend.loyalists[i]))
                        }
                        wasEqual = equal
                    }
                    val lineStyle = Stroke(width = 1.8.dp.toPx())
                    val overlapStyle =
                        Stroke(lineStyle.width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
                    val columns = (width / 14.dp.toPx()).roundToInt().coerceAtLeast(1)
                    val rows = (height / 14.dp.toPx()).roundToInt().coerceAtLeast(1)
                    onDrawBehind {
                        if (width <= 0f) return@onDrawBehind
                        for (i in 0..columns) {
                            val at = left + width * i / columns
                            drawLine(gridInk, Offset(at, margin), Offset(at, bottom), 1.dp.toPx())
                        }
                        for (i in 0..rows) {
                            val at = margin + height * i / rows
                            drawLine(gridInk, Offset(left, at), Offset(right, at), 1.dp.toPx())
                        }
                        drawText(maxLabel, topLeft = Offset(0f, margin - maxLabel.size.height / 2f))
                        drawText(
                            zeroLabel,
                            topLeft =
                                Offset(
                                    (maxLabel.size.width - zeroLabel.size.width).toFloat(),
                                    bottom - zeroLabel.size.height / 2f,
                                ),
                        )
                        drawPath(loyalistLine, loyalistInk, style = lineStyle)
                        drawPath(opponentLine, opponentInk, style = lineStyle)
                        drawPath(overlap, loyalistInk, style = overlapStyle)
                        val marker = if (hovered >= 0) hovered else last
                        val markerX = x(marker)
                        if (hovered >= 0) drawLine(axisInk, Offset(markerX, margin), Offset(markerX, bottom), 1.dp.toPx())
                        drawCircle(loyalistInk, 3.6.dp.toPx(), Offset(markerX, y(trend.loyalists[marker])))
                        val side = 4.dp.toPx()
                        drawRect(opponentInk, Offset(markerX - side / 2, y(trend.opponents[marker]) - side / 2), Size(side, side))
                    }
                },
        )
        Row(
            Modifier.fillMaxWidth().padding(start = with(LocalDensity.current) { left.toDp() }, end = plotMargin),
            horizontalArrangement = if (last == 0) Arrangement.Center else Arrangement.SpaceBetween,
        ) {
            Text(firstMonth, style = labelStyle)
            if (last > 0) Text(lastMonth, style = labelStyle)
        }
    }
}

@Composable
private fun AlignmentKey(
    label: String,
    ink: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(14.dp, 8.dp)) {
            drawLine(ink, Offset(0f, center.y), Offset(size.width, center.y), 2.dp.toPx())
        }
        Text(label, style = DossierType.number(11.sp), color = ink)
    }
}
