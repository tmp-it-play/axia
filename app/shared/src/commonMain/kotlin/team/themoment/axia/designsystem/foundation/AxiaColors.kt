package team.themoment.axia.designsystem.foundation

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** A foreground color and the container color it is designed to be rendered on. */
@Immutable
data class AxiaColorPair(
    val content: Color,
    val container: Color,
)

/**
 * Colors that communicate application structure and status.
 *
 * Status colors are intentionally separate from [AxiaPositionColors]. A position color must not be
 * reused for errors, deadlines, or completion states.
 */
@Immutable
data class AxiaSemanticColors(
    val background: Color,
    val surface: Color,
    val surfaceSubtle: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val border: Color,
    val action: Color,
    val onAction: Color,
    val danger: AxiaColorPair,
    val warning: AxiaColorPair,
    val success: AxiaColorPair,
    val info: AxiaColorPair,
)

/** Opaque interaction-state colors shared by controls and navigation. */
@Immutable
data class AxiaInteractionColors(
    val actionHover: Color,
    val actionPressed: Color,
    val dangerHover: Color,
    val dangerPressed: Color,
    val neutralHover: Color,
    val neutralPressed: Color,
    val disabledContent: Color,
    val disabledContainer: Color,
)

/** Colors for a technical position badge or its narrow workflow rail. */
@Immutable
data class AxiaPositionColor(
    val representative: Color,
    val content: Color,
    val container: Color,
)

/**
 * Technical-position colors.
 *
 * These tokens are reserved for narrow workflow rails and compact position badges. Position text
 * or an icon must accompany them so that color is never the only carrier of meaning.
 */
@Immutable
data class AxiaPositionColors(
    val server: AxiaPositionColor,
    val frontend: AxiaPositionColor,
    val cloud: AxiaPositionColor,
    val design: AxiaPositionColor,
)

val lightAxiaSemanticColors =
    AxiaSemanticColors(
        background = Color(0xFFF5F7FB),
        surface = Color(0xFFFFFFFF),
        surfaceSubtle = Color(0xFFECEFF5),
        textPrimary = Color(0xFF172033),
        textSecondary = Color(0xFF5D687A),
        border = Color(0xFFD8DEE9),
        action = Color(0xFF3659D9),
        onAction = Color(0xFFFFFFFF),
        danger = AxiaColorPair(content = Color(0xFFB42318), container = Color(0xFFFEE4E2)),
        warning = AxiaColorPair(content = Color(0xFF9A5B13), container = Color(0xFFFFF1CC)),
        success = AxiaColorPair(content = Color(0xFF067647), container = Color(0xFFD1FADF)),
        info = AxiaColorPair(content = Color(0xFF175CD3), container = Color(0xFFD1E9FF)),
    )

val darkAxiaSemanticColors =
    AxiaSemanticColors(
        background = Color(0xFF15182A),
        surface = Color(0xFF1E2435),
        surfaceSubtle = Color(0xFF252C40),
        textPrimary = Color(0xFFF4F6FB),
        textSecondary = Color(0xFFAAB3C5),
        border = Color(0xFF343D55),
        action = Color(0xFF89A4FF),
        onAction = Color(0xFF15182A),
        danger = AxiaColorPair(content = Color(0xFFFDA29B), container = Color(0xFF55160C)),
        warning = AxiaColorPair(content = Color(0xFFFEC84B), container = Color(0xFF4E2F07)),
        success = AxiaColorPair(content = Color(0xFF75E0A7), container = Color(0xFF053321)),
        info = AxiaColorPair(content = Color(0xFF84ADFF), container = Color(0xFF102A56)),
    )

val lightAxiaInteractionColors =
    AxiaInteractionColors(
        actionHover = Color(0xFF2F4FC4),
        actionPressed = Color(0xFF2944AA),
        dangerHover = Color(0xFF9F1F16),
        dangerPressed = Color(0xFF881A12),
        neutralHover = Color(0xFFF0F3F8),
        neutralPressed = Color(0xFFE2E7F0),
        disabledContent = Color(0xFF5D687A),
        disabledContainer = Color(0xFFECEFF5),
    )

val darkAxiaInteractionColors =
    AxiaInteractionColors(
        actionHover = Color(0xFFA0B5FF),
        actionPressed = Color(0xFF7692EA),
        dangerHover = Color(0xFFFFB1AA),
        dangerPressed = Color(0xFFDE8580),
        neutralHover = Color(0xFF2A3248),
        neutralPressed = Color(0xFF303A52),
        disabledContent = Color(0xFFAAB3C5),
        disabledContainer = Color(0xFF252C40),
    )

val lightAxiaPositionColors =
    AxiaPositionColors(
        server =
            AxiaPositionColor(
                representative = Color(0xFF2563EB),
                content = Color(0xFF1D4ED8),
                container = Color(0xFFDBEAFE),
            ),
        frontend =
            AxiaPositionColor(
                representative = Color(0xFF7C3AED),
                content = Color(0xFF6D28D9),
                container = Color(0xFFEDE9FE),
            ),
        cloud =
            AxiaPositionColor(
                representative = Color(0xFF0F8F83),
                content = Color(0xFF0F766E),
                container = Color(0xFFCCFBF1),
            ),
        design =
            AxiaPositionColor(
                representative = Color(0xFFC02678),
                content = Color(0xFFBE185D),
                container = Color(0xFFFCE7F3),
            ),
    )

val darkAxiaPositionColors =
    AxiaPositionColors(
        server =
            AxiaPositionColor(
                representative = Color(0xFF2563EB),
                content = Color(0xFFBFDBFE),
                container = Color(0xFF1E3A8A),
            ),
        frontend =
            AxiaPositionColor(
                representative = Color(0xFF7C3AED),
                content = Color(0xFFDDD6FE),
                container = Color(0xFF4C1D95),
            ),
        cloud =
            AxiaPositionColor(
                representative = Color(0xFF0F8F83),
                content = Color(0xFF99F6E4),
                container = Color(0xFF134E4A),
            ),
        design =
            AxiaPositionColor(
                representative = Color(0xFFC02678),
                content = Color(0xFFFBCFE8),
                container = Color(0xFF831843),
            ),
    )
