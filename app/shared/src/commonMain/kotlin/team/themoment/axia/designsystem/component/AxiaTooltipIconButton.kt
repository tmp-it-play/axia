package team.themoment.axia.designsystem.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import team.themoment.axia.designsystem.theme.AxiaTheme

/** A compact icon-only action with a visible tooltip and matching accessibility name. */
@Composable
fun AxiaTooltipIconButton(
    icon: AxiaIconKind,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    require(tooltip.isNotBlank()) { "tooltip must not be blank" }

    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val targetVisual =
        iconButtonVisual(
            enabled = enabled,
            focused = isFocused,
            hovered = isHovered,
            pressed = isPressed,
        )
    val colors = AxiaTheme.semanticColors
    val shape = AxiaTheme.shapes.control
    val animationDuration = AxiaTheme.motion.directFeedbackDurationMillis
    val borderWidth by
        animateDpAsState(
            targetValue = targetVisual.borderWidth,
            animationSpec = tween(durationMillis = animationDuration),
            label = "Axia icon button border width",
        )

    TooltipBox(
        positionProvider =
            TooltipDefaults.rememberTooltipPositionProvider(
                positioning = TooltipAnchorPosition.Above,
                spacingBetweenTooltipAndAnchor = AxiaTheme.spacing.space1,
            ),
        tooltip = {
            PlainTooltip(
                shape = shape,
                containerColor = colors.textPrimary,
                contentColor = colors.surface,
            ) {
                Text(
                    text = tooltip,
                    color = colors.surface,
                    style = AxiaTheme.typography.metadata,
                )
            }
        },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        Box(
            modifier =
                Modifier
                    .sizeIn(
                        minWidth = AxiaTheme.dimensions.minimumInteractiveSize,
                        minHeight = AxiaTheme.dimensions.minimumInteractiveSize,
                    ).border(
                        width = if (isFocused) AxiaTheme.dimensions.focusRing else 0.dp,
                        color = if (isFocused) colors.action else Color.Transparent,
                        shape = shape,
                    ).padding(AxiaTheme.spacing.space1)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                    ).semantics {
                        contentDescription = tooltip
                        role = Role.Button
                        if (!enabled) disabled()
                    },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(AxiaTheme.dimensions.controlHeight)
                        .clip(shape)
                        .background(targetVisual.container)
                        .border(
                            width = borderWidth,
                            color = targetVisual.border,
                            shape = shape,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                AxiaIcon(
                    kind = icon,
                    contentDescription = null,
                    tint = targetVisual.content,
                )
            }
        }
    }
}

private data class IconButtonVisual(
    val content: Color,
    val container: Color,
    val border: Color,
    val borderWidth: Dp,
)

@Composable
private fun iconButtonVisual(
    enabled: Boolean,
    focused: Boolean,
    hovered: Boolean,
    pressed: Boolean,
): IconButtonVisual {
    val colors = AxiaTheme.semanticColors
    val interactions = AxiaTheme.interactionColors
    if (!enabled) {
        return IconButtonVisual(
            content = interactions.disabledContent,
            container = interactions.disabledContainer,
            border = colors.border,
            borderWidth = AxiaTheme.dimensions.hairline,
        )
    }

    return when {
        pressed -> {
            IconButtonVisual(
                content = colors.onAction,
                container = interactions.actionPressed,
                border = colors.onAction,
                borderWidth = AxiaTheme.dimensions.pressedBorder,
            )
        }

        focused -> {
            IconButtonVisual(
                content = colors.action,
                container = colors.surface,
                border = colors.action,
                borderWidth = AxiaTheme.dimensions.focusRing,
            )
        }

        hovered -> {
            IconButtonVisual(
                content = colors.action,
                container = interactions.neutralHover,
                border = colors.action,
                borderWidth = AxiaTheme.dimensions.hairline,
            )
        }

        else -> {
            IconButtonVisual(
                content = colors.textSecondary,
                container = colors.surface,
                border = colors.border,
                borderWidth = AxiaTheme.dimensions.hairline,
            )
        }
    }
}
