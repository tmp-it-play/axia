package team.themoment.axia.designsystem.foundation

import androidx.compose.runtime.Immutable

/**
 * Durations for user-triggered motion. Compose applies the platform motion-duration scale to
 * finite animations, including reducing them to the next frame when the scale is zero.
 */
@Immutable
data class AxiaMotion(
    val directFeedbackDurationMillis: Int,
    val panelTransitionDurationMillis: Int,
    val reduceMotion: Boolean,
)

val defaultAxiaMotion =
    AxiaMotion(
        directFeedbackDurationMillis = 140,
        panelTransitionDurationMillis = 200,
        reduceMotion = false,
    )

val reducedAxiaMotion =
    AxiaMotion(
        directFeedbackDurationMillis = 0,
        panelTransitionDurationMillis = 0,
        reduceMotion = true,
    )
