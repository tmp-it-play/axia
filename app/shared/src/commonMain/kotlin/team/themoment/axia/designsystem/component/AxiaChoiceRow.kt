package team.themoment.axia.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import team.themoment.axia.designsystem.theme.AxiaTheme

data class AxiaChoiceOption<K : Any>(
    val key: K,
    val label: String,
    val description: String? = null,
    val icon: AxiaIconKind? = null,
    val enabled: Boolean = true,
)

@Composable
fun <K : Any> AxiaChoiceGroup(
    groupLabel: String,
    options: List<AxiaChoiceOption<K>>,
    selectedKey: K?,
    onSelectionChange: (K) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(groupLabel.isNotBlank()) { "AxiaChoiceGroup groupLabel must not be blank." }
    val optionKeys = options.map(AxiaChoiceOption<K>::key)
    require(optionKeys.distinct().size == optionKeys.size) {
        "AxiaChoiceGroup option keys must be unique."
    }

    val focusRequesters = remember(optionKeys) { List(options.size) { FocusRequester() } }
    val enabledOptionKeys = options.filter(AxiaChoiceOption<K>::enabled).map(AxiaChoiceOption<K>::key)
    val firstEnabledKey = options.firstOrNull(AxiaChoiceOption<K>::enabled)?.key
    val selectedEnabledKey = selectedKey?.takeIf { key -> options.any { it.key == key && it.enabled } }
    var rovingKey by
        remember(optionKeys, enabledOptionKeys, selectedKey) {
            mutableStateOf(selectedEnabledKey ?: firstEnabledKey)
        }
    var pendingFocusKey by remember(optionKeys) { mutableStateOf<K?>(null) }

    LaunchedEffect(pendingFocusKey, optionKeys) {
        val keyToFocus = pendingFocusKey ?: return@LaunchedEffect
        val index = options.indexOfFirst { it.key == keyToFocus && it.enabled }
        if (index >= 0) {
            focusRequesters[index].requestFocus()
        }
        pendingFocusKey = null
    }

    fun moveSelection(
        currentIndex: Int,
        offset: Int,
    ) {
        var candidateIndex = currentIndex
        repeat(options.size) {
            candidateIndex = (candidateIndex + offset + options.size) % options.size
            val candidate = options[candidateIndex]
            if (candidate.enabled) {
                rovingKey = candidate.key
                pendingFocusKey = candidate.key
                if (candidate.key != selectedKey) {
                    onSelectionChange(candidate.key)
                }
                return
            }
        }
    }

    Column(
        modifier =
            modifier
                .selectableGroup()
                .semantics { contentDescription = groupLabel },
        verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
    ) {
        options.forEachIndexed { index, option ->
            key(option.key) {
                AxiaChoiceRow(
                    option = option,
                    selected = option.key == selectedKey,
                    canReceiveFocus = option.enabled && option.key == rovingKey,
                    focusRequester = focusRequesters[index],
                    onFocused = { rovingKey = option.key },
                    onSelect = {
                        rovingKey = option.key
                        pendingFocusKey = option.key
                        if (option.key != selectedKey) {
                            onSelectionChange(option.key)
                        }
                    },
                    onMoveBackward = { moveSelection(currentIndex = index, offset = -1) },
                    onMoveForward = { moveSelection(currentIndex = index, offset = 1) },
                )
            }
        }
    }
}

@Composable
private fun <K : Any> AxiaChoiceRow(
    option: AxiaChoiceOption<K>,
    selected: Boolean,
    canReceiveFocus: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onSelect: () -> Unit,
    onMoveBackward: () -> Unit,
    onMoveForward: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val colors = AxiaTheme.semanticColors
    val interactions = AxiaTheme.interactionColors
    val targetContainerColor =
        when {
            !option.enabled -> interactions.disabledContainer
            isPressed -> interactions.neutralPressed
            isHovered || selected -> interactions.neutralHover
            else -> colors.surface
        }
    val targetBorderColor =
        when {
            !option.enabled -> colors.border
            isFocused || selected -> colors.action
            else -> colors.border
        }
    val targetBorderWidth =
        when {
            !option.enabled -> AxiaTheme.dimensions.hairline
            isFocused -> AxiaTheme.dimensions.focusRing
            isPressed -> AxiaTheme.dimensions.pressedBorder
            else -> AxiaTheme.dimensions.hairline
        }
    val animationDuration = AxiaTheme.motion.directFeedbackDurationMillis
    val visual =
        key(option.enabled, colors, interactions) {
            val containerColor by
                animateColorAsState(
                    targetValue = targetContainerColor,
                    animationSpec = tween(durationMillis = animationDuration),
                    label = "Axia choice container",
                )
            val borderColor by
                animateColorAsState(
                    targetValue = targetBorderColor,
                    animationSpec = tween(durationMillis = animationDuration),
                    label = "Axia choice border",
                )
            val borderWidth by
                animateDpAsState(
                    targetValue = targetBorderWidth,
                    animationSpec = tween(durationMillis = animationDuration),
                    label = "Axia choice border width",
                )

            ChoiceRowVisual(
                container = containerColor,
                border = borderColor,
                borderWidth = borderWidth,
            )
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = AxiaTheme.dimensions.minimumInteractiveSize)
                .border(
                    width = visual.borderWidth,
                    color = visual.border,
                    shape = AxiaTheme.shapes.control,
                ).background(
                    color = visual.container,
                    shape = AxiaTheme.shapes.control,
                ).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        return@onPreviewKeyEvent false
                    }
                    when (event.key) {
                        Key.DirectionLeft,
                        Key.DirectionUp,
                            -> {
                                onMoveBackward()
                                true
                            }

                        Key.DirectionRight,
                        Key.DirectionDown,
                            -> {
                                onMoveForward()
                                true
                            }

                        else -> {
                            false
                        }
                    }
                }.focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        onFocused()
                    }
                }.focusProperties {
                    canFocus = canReceiveFocus
                }.selectable(
                    selected = selected,
                    enabled = option.enabled,
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.RadioButton,
                    onClick = onSelect,
                ).semantics(mergeDescendants = true) {
                    stateDescription = if (selected) "선택됨" else "선택 안 됨"
                    if (!option.enabled) disabled()
                }.padding(
                    horizontal = AxiaTheme.spacing.space4,
                    vertical = AxiaTheme.spacing.space3,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
    ) {
        if (option.icon != null) {
            AxiaIcon(
                kind = option.icon,
                contentDescription = null,
                tint =
                    when {
                        !option.enabled -> interactions.disabledContent
                        selected -> colors.action
                        else -> colors.textSecondary
                    },
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1),
        ) {
            Text(
                text = option.label,
                color = if (option.enabled) colors.textPrimary else interactions.disabledContent,
                style = AxiaTheme.typography.bodyEmphasis,
            )
            if (option.description != null) {
                Text(
                    text = option.description,
                    color = if (option.enabled) colors.textSecondary else interactions.disabledContent,
                    style = AxiaTheme.typography.secondary,
                )
            }
        }
        ChoiceIndicator(selected = selected, enabled = option.enabled)
    }
}

@Composable
private fun ChoiceIndicator(
    selected: Boolean,
    enabled: Boolean,
) {
    val colors = AxiaTheme.semanticColors
    val interactions = AxiaTheme.interactionColors
    val targetIndicatorColor =
        when {
            !enabled -> interactions.disabledContent
            selected -> colors.action
            else -> colors.textSecondary
        }
    val indicatorColor =
        key(enabled, colors, interactions) {
            val animatedColor by
                animateColorAsState(
                    targetValue = targetIndicatorColor,
                    animationSpec = tween(durationMillis = AxiaTheme.motion.directFeedbackDurationMillis),
                    label = "Axia choice indicator",
                )
            animatedColor
        }

    Canvas(modifier = Modifier.size(20.dp)) {
        drawCircle(
            color = indicatorColor,
            radius = size.minDimension / 2f - 1.dp.toPx(),
            style = Stroke(width = 2.dp.toPx()),
        )
        if (selected) {
            drawCircle(
                color = indicatorColor,
                radius = size.minDimension / 4f,
            )
        }
    }
}

private data class ChoiceRowVisual(
    val container: Color,
    val border: Color,
    val borderWidth: androidx.compose.ui.unit.Dp,
)
