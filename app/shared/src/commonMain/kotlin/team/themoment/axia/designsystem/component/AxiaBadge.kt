package team.themoment.axia.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import team.themoment.axia.designsystem.theme.AxiaTheme

enum class AxiaBadgeStyle {
    Metadata,
    Product,
    Danger,
    Warning,
    Success,
    Info,
    Server,
    Frontend,
    Cloud,
    Design,
}

@Composable
fun AxiaBadge(
    label: String,
    style: AxiaBadgeStyle,
    modifier: Modifier = Modifier,
) {
    val visual = badgeVisual(style)
    Box(
        modifier =
            modifier
                .clip(visual.shape)
                .background(visual.container)
                .padding(
                    horizontal = AxiaTheme.spacing.space2,
                    vertical = AxiaTheme.spacing.space1,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = visual.content,
            style = AxiaTheme.typography.metadata,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class BadgeVisual(
    val content: Color,
    val container: Color,
    val shape: Shape,
)

@Composable
private fun badgeVisual(style: AxiaBadgeStyle): BadgeVisual {
    val semantic = AxiaTheme.semanticColors
    val positions = AxiaTheme.positionColors
    val shapes = AxiaTheme.shapes

    return when (style) {
        AxiaBadgeStyle.Metadata -> BadgeVisual(semantic.textSecondary, semantic.surfaceSubtle, shapes.badge)
        AxiaBadgeStyle.Product -> BadgeVisual(semantic.textPrimary, semantic.surfaceSubtle, shapes.badge)
        AxiaBadgeStyle.Danger -> BadgeVisual(semantic.danger.content, semantic.danger.container, shapes.badge)
        AxiaBadgeStyle.Warning -> BadgeVisual(semantic.warning.content, semantic.warning.container, shapes.badge)
        AxiaBadgeStyle.Success -> BadgeVisual(semantic.success.content, semantic.success.container, shapes.badge)
        AxiaBadgeStyle.Info -> BadgeVisual(semantic.info.content, semantic.info.container, shapes.badge)
        AxiaBadgeStyle.Server -> BadgeVisual(positions.server.content, positions.server.container, shapes.pill)
        AxiaBadgeStyle.Frontend -> BadgeVisual(positions.frontend.content, positions.frontend.container, shapes.pill)
        AxiaBadgeStyle.Cloud -> BadgeVisual(positions.cloud.content, positions.cloud.container, shapes.pill)
        AxiaBadgeStyle.Design -> BadgeVisual(positions.design.content, positions.design.container, shapes.pill)
    }
}
