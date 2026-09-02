package team.themoment.axia.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import team.themoment.axia.designsystem.theme.AxiaTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class AxiaIconKind {
    Dashboard,
    Tasks,
    Products,
    Members,
    Calendar,
    Analytics,
    Notifications,
    Management,
    Settings,
    Inbox,
    Warning,
    Info,
    Success,
    SystemTheme,
    LightTheme,
    DarkTheme,
}

@Composable
fun AxiaIcon(
    kind: AxiaIconKind,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val semanticsModifier =
        if (contentDescription == null) {
            Modifier
        } else {
            Modifier.semantics { this.contentDescription = contentDescription }
        }

    Canvas(modifier = modifier.then(semanticsModifier).size(20.dp)) {
        val stroke =
            Stroke(
                width = 1.6.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
        when (kind) {
            AxiaIconKind.Dashboard -> drawDashboard(tint, stroke)
            AxiaIconKind.Tasks -> drawTasks(tint, stroke)
            AxiaIconKind.Products -> drawProducts(tint, stroke)
            AxiaIconKind.Members -> drawMembers(tint, stroke)
            AxiaIconKind.Calendar -> drawCalendar(tint, stroke)
            AxiaIconKind.Analytics -> drawAnalytics(tint, stroke)
            AxiaIconKind.Notifications -> drawNotifications(tint, stroke)
            AxiaIconKind.Management -> drawManagement(tint, stroke)
            AxiaIconKind.Settings -> drawSettings(tint, stroke)
            AxiaIconKind.Inbox -> drawInbox(tint, stroke)
            AxiaIconKind.Warning -> drawWarning(tint, stroke)
            AxiaIconKind.Info -> drawInfo(tint, stroke)
            AxiaIconKind.Success -> drawSuccess(tint, stroke)
            AxiaIconKind.SystemTheme -> drawSystemTheme(tint, stroke)
            AxiaIconKind.LightTheme -> drawLightTheme(tint, stroke)
            AxiaIconKind.DarkTheme -> drawDarkTheme(tint, stroke)
        }
    }
}

private fun DrawScope.point(
    x: Float,
    y: Float,
) = Offset(size.width * x, size.height * y)

private fun DrawScope.drawDashboard(
    color: Color,
    stroke: Stroke,
) {
    listOf(0.16f to 0.16f, 0.56f to 0.16f, 0.16f to 0.56f, 0.56f to 0.56f).forEach { (x, y) ->
        drawRoundRect(
            color = color,
            topLeft = point(x, y),
            size = Size(size.width * 0.28f, size.height * 0.28f),
            cornerRadius = CornerRadius(size.minDimension * 0.06f),
            style = stroke,
        )
    }
}

private fun DrawScope.drawTasks(
    color: Color,
    stroke: Stroke,
) {
    listOf(0.25f, 0.5f, 0.75f).forEach { y ->
        drawCircle(color, size.minDimension * 0.04f, point(0.2f, y), style = stroke)
        drawLine(color, point(0.36f, y), point(0.82f, y), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawProducts(
    color: Color,
    stroke: Stroke,
) {
    drawRoundRect(
        color = color,
        topLeft = point(0.16f, 0.2f),
        size = Size(size.width * 0.68f, size.height * 0.6f),
        cornerRadius = CornerRadius(size.minDimension * 0.08f),
        style = stroke,
    )
    drawLine(color, point(0.16f, 0.4f), point(0.84f, 0.4f), stroke.width, StrokeCap.Round)
    drawLine(color, point(0.5f, 0.4f), point(0.5f, 0.8f), stroke.width, StrokeCap.Round)
}

private fun DrawScope.drawMembers(
    color: Color,
    stroke: Stroke,
) {
    drawCircle(color, size.minDimension * 0.13f, point(0.38f, 0.35f), style = stroke)
    drawCircle(color, size.minDimension * 0.1f, point(0.68f, 0.4f), style = stroke)
    val primaryBodyBounds = Rect(point(0.17f, 0.48f), point(0.59f, 0.86f))
    val secondaryBodyBounds = Rect(point(0.52f, 0.54f), point(0.86f, 0.83f))
    drawArc(
        color = color,
        startAngle = 198f,
        sweepAngle = 144f,
        useCenter = false,
        topLeft = primaryBodyBounds.topLeft,
        size = primaryBodyBounds.size,
        style = stroke,
    )
    drawArc(
        color = color,
        startAngle = 198f,
        sweepAngle = 144f,
        useCenter = false,
        topLeft = secondaryBodyBounds.topLeft,
        size = secondaryBodyBounds.size,
        style = stroke,
    )
}

private fun DrawScope.drawCalendar(
    color: Color,
    stroke: Stroke,
) {
    drawRoundRect(
        color = color,
        topLeft = point(0.15f, 0.2f),
        size = Size(size.width * 0.7f, size.height * 0.65f),
        cornerRadius = CornerRadius(size.minDimension * 0.08f),
        style = stroke,
    )
    drawLine(color, point(0.15f, 0.4f), point(0.85f, 0.4f), stroke.width, StrokeCap.Round)
    drawLine(color, point(0.32f, 0.13f), point(0.32f, 0.29f), stroke.width, StrokeCap.Round)
    drawLine(color, point(0.68f, 0.13f), point(0.68f, 0.29f), stroke.width, StrokeCap.Round)
}

private fun DrawScope.drawAnalytics(
    color: Color,
    stroke: Stroke,
) {
    drawLine(color, point(0.18f, 0.82f), point(0.82f, 0.82f), stroke.width, StrokeCap.Round)
    val path =
        Path().apply {
            moveTo(size.width * 0.2f, size.height * 0.68f)
            lineTo(size.width * 0.4f, size.height * 0.5f)
            lineTo(size.width * 0.57f, size.height * 0.62f)
            lineTo(size.width * 0.8f, size.height * 0.25f)
        }
    drawPath(path, color, style = stroke)
}

private fun DrawScope.drawNotifications(
    color: Color,
    stroke: Stroke,
) {
    val path =
        Path().apply {
            moveTo(size.width * 0.25f, size.height * 0.66f)
            quadraticTo(size.width * 0.32f, size.height * 0.57f, size.width * 0.32f, size.height * 0.39f)
            quadraticTo(size.width * 0.32f, size.height * 0.18f, size.width * 0.5f, size.height * 0.18f)
            quadraticTo(size.width * 0.68f, size.height * 0.18f, size.width * 0.68f, size.height * 0.39f)
            quadraticTo(size.width * 0.68f, size.height * 0.57f, size.width * 0.75f, size.height * 0.66f)
            close()
        }
    drawPath(path, color, style = stroke)
    val bellBounds = Rect(point(0.4f, 0.65f), point(0.6f, 0.84f))
    drawArc(
        color = color,
        startAngle = 20f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = bellBounds.topLeft,
        size = bellBounds.size,
        style = stroke,
    )
}

private fun DrawScope.drawManagement(
    color: Color,
    stroke: Stroke,
) {
    drawCircle(color, size.minDimension * 0.23f, point(0.5f, 0.5f), style = stroke)
    drawCircle(color, size.minDimension * 0.07f, point(0.5f, 0.5f), style = stroke)
    repeat(8) { index ->
        val angle = (PI * 2.0 * index / 8.0).toFloat()
        drawLine(
            color,
            point(0.5f + cos(angle) * 0.3f, 0.5f + sin(angle) * 0.3f),
            point(0.5f + cos(angle) * 0.4f, 0.5f + sin(angle) * 0.4f),
            stroke.width,
            StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawSettings(
    color: Color,
    stroke: Stroke,
) {
    val knobPositions = listOf(0.36f, 0.66f, 0.45f)
    listOf(0.25f, 0.5f, 0.75f).forEachIndexed { index, y ->
        drawLine(color, point(0.17f, y), point(0.83f, y), stroke.width, StrokeCap.Round)
        drawCircle(color, size.minDimension * 0.07f, point(knobPositions[index], y), style = stroke)
    }
}

private fun DrawScope.drawInbox(
    color: Color,
    stroke: Stroke,
) {
    drawRoundRect(
        color = color,
        topLeft = point(0.15f, 0.25f),
        size = Size(size.width * 0.7f, size.height * 0.55f),
        cornerRadius = CornerRadius(size.minDimension * 0.08f),
        style = stroke,
    )
    val path =
        Path().apply {
            moveTo(size.width * 0.15f, size.height * 0.58f)
            lineTo(size.width * 0.36f, size.height * 0.58f)
            quadraticTo(size.width * 0.39f, size.height * 0.7f, size.width * 0.5f, size.height * 0.7f)
            quadraticTo(size.width * 0.61f, size.height * 0.7f, size.width * 0.64f, size.height * 0.58f)
            lineTo(size.width * 0.85f, size.height * 0.58f)
        }
    drawPath(path, color, style = stroke)
}

private fun DrawScope.drawWarning(
    color: Color,
    stroke: Stroke,
) {
    val path =
        Path().apply {
            moveTo(size.width * 0.5f, size.height * 0.14f)
            lineTo(size.width * 0.87f, size.height * 0.82f)
            lineTo(size.width * 0.13f, size.height * 0.82f)
            close()
        }
    drawPath(path, color, style = stroke)
    drawLine(color, point(0.5f, 0.37f), point(0.5f, 0.59f), stroke.width, StrokeCap.Round)
    drawCircle(color, size.minDimension * 0.02f, point(0.5f, 0.7f))
}

private fun DrawScope.drawInfo(
    color: Color,
    stroke: Stroke,
) {
    drawCircle(color, size.minDimension * 0.34f, point(0.5f, 0.5f), style = stroke)
    drawLine(color, point(0.5f, 0.45f), point(0.5f, 0.68f), stroke.width, StrokeCap.Round)
    drawCircle(color, size.minDimension * 0.025f, point(0.5f, 0.32f))
}

private fun DrawScope.drawSuccess(
    color: Color,
    stroke: Stroke,
) {
    drawCircle(color, size.minDimension * 0.34f, point(0.5f, 0.5f), style = stroke)
    val path =
        Path().apply {
            moveTo(size.width * 0.31f, size.height * 0.51f)
            lineTo(size.width * 0.44f, size.height * 0.64f)
            lineTo(size.width * 0.7f, size.height * 0.36f)
        }
    drawPath(path, color, style = stroke)
}

private fun DrawScope.drawSystemTheme(
    color: Color,
    stroke: Stroke,
) {
    drawRoundRect(
        color = color,
        topLeft = point(0.13f, 0.18f),
        size = Size(size.width * 0.74f, size.height * 0.52f),
        cornerRadius = CornerRadius(size.minDimension * 0.07f),
        style = stroke,
    )
    drawLine(color, point(0.5f, 0.7f), point(0.5f, 0.84f), stroke.width, StrokeCap.Round)
    drawLine(color, point(0.34f, 0.84f), point(0.66f, 0.84f), stroke.width, StrokeCap.Round)
}

private fun DrawScope.drawLightTheme(
    color: Color,
    stroke: Stroke,
) {
    drawCircle(color, size.minDimension * 0.18f, point(0.5f, 0.5f), style = stroke)
    repeat(8) { index ->
        val angle = (PI * 2.0 * index / 8.0).toFloat()
        drawLine(
            color,
            point(0.5f + cos(angle) * 0.3f, 0.5f + sin(angle) * 0.3f),
            point(0.5f + cos(angle) * 0.4f, 0.5f + sin(angle) * 0.4f),
            stroke.width,
            StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawDarkTheme(
    color: Color,
    stroke: Stroke,
) {
    val path =
        Path().apply {
            moveTo(size.width * 0.7f, size.height * 0.18f)
            quadraticTo(size.width * 0.43f, size.height * 0.24f, size.width * 0.4f, size.height * 0.48f)
            quadraticTo(size.width * 0.37f, size.height * 0.72f, size.width * 0.66f, size.height * 0.81f)
            quadraticTo(size.width * 0.26f, size.height * 0.88f, size.width * 0.19f, size.height * 0.52f)
            quadraticTo(size.width * 0.14f, size.height * 0.23f, size.width * 0.7f, size.height * 0.18f)
            close()
        }
    drawPath(path, color, style = stroke)
}
