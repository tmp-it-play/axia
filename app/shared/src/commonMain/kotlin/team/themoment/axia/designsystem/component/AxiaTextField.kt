package team.themoment.axia.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.isEditable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import team.themoment.axia.designsystem.theme.AxiaTheme

/**
 * A compact text input for Axia forms.
 *
 * The visible control is 36 dp high for desktop density, while the surrounding interactive layout
 * remains at least 44 dp high. When [errorText] is present it replaces [supportingText] and is also
 * exposed through accessibility semantics.
 */
@Composable
fun AxiaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    require(label.isNotBlank()) { "label must not be blank" }

    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val colors = AxiaTheme.semanticColors
    val typography = AxiaTheme.typography
    val shape = AxiaTheme.shapes.control
    val isError = errorText != null
    val supportingContent = errorText ?: supportingText
    val accessibilityDescription = supportingText?.takeIf { !isError && it.isNotBlank() }
    val targetVisual = textFieldVisual(enabled = enabled, focused = isFocused, error = isError)
    val interactions = AxiaTheme.interactionColors
    val animationDuration = AxiaTheme.motion.directFeedbackDurationMillis
    val visual =
        key(enabled, colors, interactions) {
            val contentColor by
                animateColorAsState(
                    targetValue = targetVisual.content,
                    animationSpec = tween(durationMillis = animationDuration),
                    label = "Axia text field content",
                )
            val secondaryContentColor by
                animateColorAsState(
                    targetValue = targetVisual.secondaryContent,
                    animationSpec = tween(durationMillis = animationDuration),
                    label = "Axia text field secondary content",
                )
            val containerColor by
                animateColorAsState(
                    targetValue = targetVisual.container,
                    animationSpec = tween(durationMillis = animationDuration),
                    label = "Axia text field container",
                )
            val borderColor by
                animateColorAsState(
                    targetValue = targetVisual.border,
                    animationSpec = tween(durationMillis = animationDuration),
                    label = "Axia text field border",
                )
            val borderWidth by
                animateDpAsState(
                    targetValue = targetVisual.borderWidth,
                    animationSpec = tween(durationMillis = animationDuration),
                    label = "Axia text field border width",
                )

            targetVisual.copy(
                content = contentColor,
                secondaryContent = secondaryContentColor,
                container = containerColor,
                border = borderColor,
                borderWidth = borderWidth,
            )
        }

    Column(modifier = modifier) {
        Text(
            text = label,
            modifier =
                Modifier
                    .padding(bottom = AxiaTheme.spacing.space1)
                    .clearAndSetSemantics {},
            color = if (isError) colors.danger.content else visual.secondaryContent,
            style = typography.metadata,
        )

        CompositionLocalProvider(
            LocalMinimumInteractiveComponentSize provides AxiaTheme.dimensions.minimumInteractiveSize,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier =
                    Modifier
                        .minimumInteractiveComponentSize()
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = AxiaTheme.dimensions.controlHeight)
                        .clip(shape)
                        .background(visual.container)
                        .border(
                            width = visual.borderWidth,
                            color = visual.border,
                            shape = shape,
                        ).semantics {
                            contentDescription = label
                            if (accessibilityDescription != null) {
                                stateDescription = accessibilityDescription
                            }
                            isEditable = enabled && !readOnly
                            if (!enabled) disabled()
                            if (errorText != null) error(errorText)
                        },
                enabled = enabled,
                readOnly = readOnly,
                textStyle = typography.body.copy(color = visual.content),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                singleLine = singleLine,
                interactionSource = interactionSource,
                cursorBrush = SolidColor(colors.action),
                decorationBox = { innerTextField ->
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = AxiaTheme.spacing.space3,
                                    vertical = AxiaTheme.spacing.space2,
                                ),
                        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                    ) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                modifier = Modifier.clearAndSetSemantics {},
                                color = visual.secondaryContent,
                                style = typography.body,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }

        if (supportingContent != null) {
            Text(
                text = supportingContent,
                modifier =
                    Modifier
                        .padding(
                            top = AxiaTheme.spacing.space1,
                            start = AxiaTheme.spacing.space3,
                            end = AxiaTheme.spacing.space3,
                        ).clearAndSetSemantics {},
                color = if (isError) colors.danger.content else visual.secondaryContent,
                style = typography.metadata,
            )
        }
    }
}

private data class TextFieldVisual(
    val content: Color,
    val secondaryContent: Color,
    val container: Color,
    val border: Color,
    val borderWidth: Dp,
)

@Composable
private fun textFieldVisual(
    enabled: Boolean,
    focused: Boolean,
    error: Boolean,
): TextFieldVisual {
    val colors = AxiaTheme.semanticColors
    val interactions = AxiaTheme.interactionColors
    val borderWidth =
        if (enabled && focused) {
            AxiaTheme.dimensions.focusRing
        } else {
            AxiaTheme.dimensions.hairline
        }
    val border =
        when {
            !enabled -> interactions.disabledContent
            error -> colors.danger.content
            focused -> colors.action
            else -> colors.border
        }

    return TextFieldVisual(
        content = if (enabled) colors.textPrimary else interactions.disabledContent,
        secondaryContent = if (enabled) colors.textSecondary else interactions.disabledContent,
        container = if (enabled) colors.surface else interactions.disabledContainer,
        border = border,
        borderWidth = borderWidth,
    )
}
