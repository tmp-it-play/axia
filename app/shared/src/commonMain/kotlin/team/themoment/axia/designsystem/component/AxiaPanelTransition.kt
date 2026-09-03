package team.themoment.axia.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import team.themoment.axia.designsystem.theme.AxiaTheme

/** Reveals newly selected content without retaining the previous panel's interactive controls. */
@Composable
fun AxiaPanelTransition(
    contentKey: Any?,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val duration = AxiaTheme.motion.panelTransitionDurationMillis
    val opacity = remember(contentKey) { Animatable(if (animate && duration > 0) 0f else 1f) }
    LaunchedEffect(opacity, animate, duration) {
        if (animate && duration > 0) {
            opacity.animateTo(1f, tween(duration))
        } else {
            opacity.snapTo(1f)
        }
    }
    Box(modifier.graphicsLayer { alpha = opacity.value }, content = content)
}
