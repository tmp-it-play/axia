package team.themoment.axia.ui.dossier

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import java.time.YearMonth

/**
 * 월간 시계열을 잉크 선으로 그린다. [start]는 첫 값의 연월이다.
 * 왼쪽에 최대, 중간, 최소 눈금을, 아래에 첫 해와 마지막 연월을 표시한다.
 */
@Composable
fun LineChart(
    values: List<Double>,
    start: YearMonth,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    color: Color = DossierColors.blueInk,
    zeroLine: Boolean = false,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = DossierType.number(10.sp).copy(color = DossierColors.inkMuted)
    Canvas(modifier.fillMaxWidth().height(height)) {
        val left = 52.dp.toPx()
        val bottom = 16.dp.toPx()
        val top = 6.dp.toPx()
        val plotWidth = size.width - left - 4.dp.toPx()
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

        val end = start.plusMonths((values.size - 1).toLong())
        val first = measurer.measure("${start.year}.${start.monthValue}", labelStyle)
        val last = measurer.measure("${end.year}.${end.monthValue}", labelStyle)
        drawText(first, topLeft = Offset(left, size.height - first.size.height))
        drawText(last, topLeft = Offset(left + plotWidth - last.size.width, size.height - last.size.height))
    }
}
