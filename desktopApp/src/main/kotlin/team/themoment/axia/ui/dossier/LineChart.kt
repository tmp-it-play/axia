package team.themoment.axia.ui.dossier

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import java.time.YearMonth
import kotlin.math.roundToInt

private val plotLeft = 52.dp
private val plotRight = 4.dp
private val plotTop = 6.dp
private val plotBottom = 16.dp

/**
 * 월간 시계열을 잉크 선으로 그린다. [start]는 첫 값의 연월이다.
 * 왼쪽에 최대, 중간, 최소 눈금을, 아래에 첫 해와 마지막 연월을 표시한다.
 */
@Composable
fun LineChart(
    values: List<Double>,
    start: YearMonth,
    format: (Double) -> String,
    title: String,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    color: Color = DossierColors.blueInk,
    zeroLine: Boolean = false,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = DossierType.number(10.sp).copy(color = DossierColors.inkMuted)
    var hoverFraction by remember { mutableFloatStateOf(-1f) }
    val hoveredIndex by remember(values.size) {
        derivedStateOf { if (hoverFraction < 0f || values.isEmpty()) null else (hoverFraction * values.lastIndex).roundToInt() }
    }
    val index = hoveredIndex
    val month = index?.let { start.plusMonths(it.toLong()) }
    val reading = index?.let { format(values[it]) }.orEmpty()
    val previous = index?.takeIf { it > 0 }?.let { format(values[it - 1]) }
    val hover =
        Modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter, PointerEventType.Move -> {
                                val mouse = event.changes.firstOrNull { it.type == PointerType.Mouse } ?: continue
                                if (event.buttons.areAnyPressed) continue
                                val position = mouse.position
                                val left = plotLeft.toPx()
                                val right = size.width - plotRight.toPx()
                                hoverFraction =
                                    if (right > left && position.x in left..right &&
                                        position.y in plotTop.toPx()..size.height - plotBottom.toPx()
                                    ) {
                                        (position.x - left) / (right - left)
                                    } else {
                                        -1f
                                    }
                            }

                            PointerEventType.Exit, PointerEventType.Press, PointerEventType.Scroll -> {
                                hoverFraction = -1f
                            }
                        }
                    }
                }
            }.metricReadingTooltip(
                if (month == null) title else "${month.year}년 ${month.monthValue}월 · $title",
                reading,
                when (previous) {
                    null -> null
                    reading -> "전월과 동일"
                    else -> "전월  $previous"
                },
            )
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = "$title 월별 추이" }
            .then(hover),
    ) {
        val left = plotLeft.toPx()
        val bottom = plotBottom.toPx()
        val top = plotTop.toPx()
        val plotWidth = size.width - left - plotRight.toPx()
        val plotHeight = size.height - bottom - top
        if (values.isEmpty() || plotWidth <= 0f) return@Canvas

        var low = values.min()
        var high = values.max()
        if (zeroLine) {
            low = minOf(low, 0.0)
            high = maxOf(high, 0.0)
        }
        if (high - low < 1e-9) {
            val pad = maxOf(kotlin.math.abs(high) * 0.05, 1e-3)
            low -= pad
            high += pad
        } else {
            val margin = (high - low) * 0.08
            low -= margin
            high += margin
        }

        fun y(value: Double) = top + ((high - value) / (high - low)).toFloat() * plotHeight

        fun x(index: Int) = left + if (values.size == 1) plotWidth / 2f else index * plotWidth / (values.size - 1)

        val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
        val labeled = mutableSetOf<String>()
        listOf(high, (high + low) / 2.0, low).forEach { tick ->
            val ty = y(tick)
            drawLine(DossierColors.paperLine, Offset(left, ty), Offset(left + plotWidth, ty), strokeWidth = 1f, pathEffect = dash)
            // 범위가 좁으면 반올림한 눈금 라벨이 겹치므로 같은 라벨은 한 번만 쓴다.
            val label = format(tick)
            if (labeled.add(label)) {
                val layout = measurer.measure(label, labelStyle)
                drawText(layout, topLeft = Offset(left - layout.size.width - 6.dp.toPx(), ty - layout.size.height / 2f))
            }
        }
        if (zeroLine && low < 0.0 && high > 0.0) {
            drawLine(DossierColors.inkMuted, Offset(left, y(0.0)), Offset(left + plotWidth, y(0.0)), strokeWidth = 1f)
        }

        val line = Path()
        values.forEachIndexed { index, value ->
            if (index == 0) line.moveTo(x(index), y(value)) else line.lineTo(x(index), y(value))
        }
        drawPath(line, color, style = Stroke(width = 1.8.dp.toPx()))
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(x(values.lastIndex), y(values.last())))
        if (index != null) {
            val point = Offset(x(index), y(values[index]))
            drawLine(DossierColors.inkMuted, Offset(point.x, top), Offset(point.x, top + plotHeight), pathEffect = dash)
            drawCircle(DossierColors.paper, radius = 4.5.dp.toPx(), center = point)
            drawCircle(color, radius = 3.dp.toPx(), center = point)
        }

        val end = start.plusMonths((values.size - 1).toLong())
        val first = measurer.measure("${start.year}.${start.monthValue}", labelStyle)
        val last = measurer.measure("${end.year}.${end.monthValue}", labelStyle)
        drawText(first, topLeft = Offset(left, size.height - first.size.height))
        drawText(last, topLeft = Offset(left + plotWidth - last.size.width, size.height - last.size.height))
    }
}
