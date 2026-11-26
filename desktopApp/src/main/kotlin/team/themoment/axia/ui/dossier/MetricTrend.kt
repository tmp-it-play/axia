package team.themoment.axia.ui.dossier

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.theme.DossierType
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** 캠페인 전체의 월별 기록. 소수로 저장한 비율은 [scale]로 백분율 눈금에 맞춘다. */
internal data class MetricTrend(
    val start: YearMonth,
    val values: List<Double>,
    val unit: String,
    val scale: Double = 1.0,
    val decimals: Int = 1,
)

private val monthFormat = DateTimeFormatter.ofPattern("yyyy.MM")
private val plotInk = Color(0xFFEEC375)
private val plotLabel = Color(0xFFC9C8B8)
private val plotGrid = Color(0xFF4B5049)

@Composable
internal fun TooltipTrendChart(trend: MetricTrend) {
    if (trend.values.isEmpty()) return
    val values = remember(trend) { if (trend.scale == 1.0) trend.values else trend.values.map { it * trend.scale } }
    val labelStyle = DossierType.number(11.sp).copy(color = plotLabel)
    val firstMonth = monthFormat.format(trend.start)
    val lastMonth = monthFormat.format(trend.start.plusMonths(values.lastIndex.toLong()))
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val measurer = rememberTextMeasurer()
        val min = values.min()
        val max = values.max()
        val range = max - min
        val precision = 10.0.pow(-trend.decimals)
        val tick = 10.0.pow(floor(log10(maxOf(precision, range / 2))))
        val step =
            when {
                range <= 2 * tick -> tick
                range <= 4 * tick -> 2 * tick
                else -> 5 * tick
            }
        val low = if (range == 0.0) min else floor(min / step) * step
        val high = if (range == 0.0) max else ceil(max / step) * step

        fun label(value: Double): String {
            if (abs(value) < precision / 2) return "0"
            return String.format(Locale.ROOT, "%.${trend.decimals}f", value).trimEnd('0').trimEnd('.')
        }
        val description =
            if (values.size == 1) {
                "$firstMonth ${label(values.first())}${trend.unit}"
            } else {
                "$firstMonth ${label(values.first())}${trend.unit}, $lastMonth ${label(values.last())}${trend.unit}"
            }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(Color(0xFF191E1B))
                .semantics { contentDescription = description },
        ) {
            val pad = 7.dp.toPx()
            val bottom = size.height - pad
            val ticks =
                when {
                    values.size == 1 -> emptyList()
                    high == low -> listOf(high)
                    low < 0.0 && high > 0.0 -> listOf(high, 0.0, low)
                    else -> listOf(high, low)
                }
            val labels = ticks.map { measurer.measure(label(it), labelStyle) }
            val left = labels.maxOfOrNull { it.size.width }?.let { pad * 2 + it } ?: pad
            val right = size.width - pad
            if (right <= left) return@Canvas

            fun y(v: Double): Float = if (high > low) bottom - ((v - low) / (high - low)).toFloat() * (bottom - pad) else size.height / 2

            fun x(i: Int): Float = if (values.size == 1) (left + right) / 2 else left + i * (right - left) / values.lastIndex

            val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
            ticks.forEachIndexed { i, v ->
                val at = y(v)
                val text = labels[i]
                val textY = (at - text.size.height / 2f).coerceIn(0f, (size.height - text.size.height).coerceAtLeast(0f))
                drawText(text, topLeft = Offset(left - pad - text.size.width, textY))
                val gridDash = if (v == 0.0) null else dash
                drawLine(plotGrid, Offset(left, at), Offset(right, at), strokeWidth = 1.dp.toPx(), pathEffect = gridDash)
            }
            val path = Path()
            values.forEachIndexed { i, v ->
                if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
            }
            drawPath(path, plotInk, style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(plotInk, 2.7.dp.toPx(), Offset(x(values.lastIndex), y(values.last())))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (values.size == 1) Arrangement.Center else Arrangement.SpaceBetween) {
            Text(firstMonth, style = labelStyle)
            if (values.size > 1) Text(lastMonth, style = labelStyle)
        }
    }
}
