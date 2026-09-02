package team.themoment.axia.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import team.themoment.axia.designsystem.theme.AxiaTheme

enum class AxiaButtonStyle {
    Primary,
    Secondary,
    Tertiary,
    Danger,
}

@Composable
fun AxiaButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AxiaButtonStyle = AxiaButtonStyle.Primary,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val targetVisual =
        buttonVisual(
            style = style,
            enabled = enabled,
            hovered = isHovered,
            pressed = isPressed,
        )
    val semanticPalette = AxiaTheme.semanticColors
    val interactionPalette = AxiaTheme.interactionColors
    val animationDuration = AxiaTheme.motion.directFeedbackDurationMillis
    // Re-keying on enabled, style, or palette changes makes foreground/background
    // pairs switch atomically. Hover and press still animate because their foreground
    // is stable, while inverse color pairs can never pass through 1:1.
    val visual =
        key(enabled, style, semanticPalette, interactionPalette) {
            val containerColor by
                animateColorAsState(
                    targetValue = targetVisual.container,
                    animationSpec = tween(durationMillis = animationDuration),
                    label = "Axia button container",
                )
            val borderColor by
                animateColorAsState(
                    targetValue = targetVisual.border,
                    animationSpec = tween(durationMillis = animationDuration),
                    label = "Axia button border",
                )
            val borderWidth by
                animateDpAsState(
                    targetValue = targetVisual.borderWidth,
                    animationSpec = tween(durationMillis = animationDuration),
                    label = "Axia button border width",
                )

            targetVisual.copy(
                container = containerColor,
                border = borderColor,
                borderWidth = borderWidth,
            )
        }
    val shape = AxiaTheme.shapes.control

    Box(
        modifier =
            modifier
                .defaultMinSize(minHeight = AxiaTheme.dimensions.minimumInteractiveSize)
                .border(
                    width = if (isFocused) AxiaTheme.dimensions.focusRing else 0.dp,
                    color = if (isFocused) AxiaTheme.semanticColors.action else Color.Transparent,
                    shape = shape,
                ).padding(horizontal = AxiaTheme.spacing.space1)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics {
                    role = Role.Button
                    if (!enabled) disabled()
                },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .height(AxiaTheme.dimensions.controlHeight)
                    .clip(shape)
                    .background(visual.container)
                    .border(
                        width = visual.borderWidth,
                        color = visual.border,
                        shape = shape,
                    ).padding(horizontal = AxiaTheme.spacing.space4),
            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides visual.content) {
                leadingIcon?.invoke()
                Text(
                    text = label,
                    color = visual.content,
                    style = visual.textStyle,
                    maxLines = 1,
                )
            }
        }
    }
}

private data class ButtonVisual(
    val content: Color,
    val container: Color,
    val border: Color,
    val borderWidth: Dp,
    val textStyle: TextStyle,
)

@Composable
private fun buttonVisual(
    style: AxiaButtonStyle,
    enabled: Boolean,
    hovered: Boolean,
    pressed: Boolean,
): ButtonVisual {
    val colors = AxiaTheme.semanticColors
    val interactions = AxiaTheme.interactionColors
    val typography = AxiaTheme.typography
    if (!enabled) {
        return ButtonVisual(
            content = interactions.disabledContent,
            container = interactions.disabledContainer,
            border = colors.border,
            borderWidth = AxiaTheme.dimensions.hairline,
            textStyle = typography.body,
        )
    }

    return when (style) {
        AxiaButtonStyle.Primary -> {
            ButtonVisual(
                content = colors.onAction,
                container =
                    when {
                        pressed -> interactions.actionPressed
                        hovered -> interactions.actionHover
                        else -> colors.action
                    },
                border = Color.Transparent,
                borderWidth = 0.dp,
                textStyle = typography.bodyEmphasis,
            )
        }

        AxiaButtonStyle.Secondary -> {
            ButtonVisual(
                content = colors.textPrimary,
                container =
                    when {
                        pressed -> interactions.neutralPressed
                        hovered -> interactions.neutralHover
                        else -> colors.surface
                    },
                border = if (hovered || pressed) colors.action else colors.border,
                borderWidth =
                    if (pressed) {
                        AxiaTheme.dimensions.pressedBorder
                    } else {
                        AxiaTheme.dimensions.hairline
                    },
                textStyle = typography.bodyEmphasis,
            )
        }

        AxiaButtonStyle.Tertiary -> {
            ButtonVisual(
                content = colors.action,
                container =
                    when {
                        pressed -> interactions.neutralPressed
                        hovered -> interactions.neutralHover
                        else -> Color.Transparent
                    },
                border = if (hovered || pressed) colors.action else Color.Transparent,
                borderWidth =
                    when {
                        pressed -> AxiaTheme.dimensions.pressedBorder
                        hovered -> AxiaTheme.dimensions.hairline
                        else -> 0.dp
                    },
                textStyle = typography.bodyEmphasis,
            )
        }

        AxiaButtonStyle.Danger -> {
            ButtonVisual(
                content = colors.onAction,
                container =
                    when {
                        pressed -> interactions.dangerPressed
                        hovered -> interactions.dangerHover
                        else -> colors.danger.content
                    },
                border = Color.Transparent,
                borderWidth = 0.dp,
                textStyle = typography.bodyEmphasis,
            )
        }
    }
}
