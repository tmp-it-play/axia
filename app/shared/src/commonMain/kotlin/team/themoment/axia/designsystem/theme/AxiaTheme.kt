package team.themoment.axia.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import team.themoment.axia.designsystem.foundation.AxiaDimensions
import team.themoment.axia.designsystem.foundation.AxiaInteractionColors
import team.themoment.axia.designsystem.foundation.AxiaMotion
import team.themoment.axia.designsystem.foundation.AxiaPositionColors
import team.themoment.axia.designsystem.foundation.AxiaSemanticColors
import team.themoment.axia.designsystem.foundation.AxiaShapes
import team.themoment.axia.designsystem.foundation.AxiaSpacing
import team.themoment.axia.designsystem.foundation.AxiaTypography
import team.themoment.axia.designsystem.foundation.darkAxiaInteractionColors
import team.themoment.axia.designsystem.foundation.darkAxiaPositionColors
import team.themoment.axia.designsystem.foundation.darkAxiaSemanticColors
import team.themoment.axia.designsystem.foundation.defaultAxiaDimensions
import team.themoment.axia.designsystem.foundation.defaultAxiaMotion
import team.themoment.axia.designsystem.foundation.defaultAxiaShapes
import team.themoment.axia.designsystem.foundation.defaultAxiaSpacing
import team.themoment.axia.designsystem.foundation.lightAxiaInteractionColors
import team.themoment.axia.designsystem.foundation.lightAxiaPositionColors
import team.themoment.axia.designsystem.foundation.lightAxiaSemanticColors
import team.themoment.axia.designsystem.foundation.reducedAxiaMotion
import team.themoment.axia.designsystem.foundation.rememberAxiaTypography
import team.themoment.axia.designsystem.foundation.toMaterialTypography

private val LocalSemanticColors = staticCompositionLocalOf<AxiaSemanticColors> { missingAxiaTheme() }
private val LocalPositionColors = staticCompositionLocalOf<AxiaPositionColors> { missingAxiaTheme() }
private val LocalInteractionColors = staticCompositionLocalOf<AxiaInteractionColors> { missingAxiaTheme() }
private val LocalAxiaTypography = staticCompositionLocalOf<AxiaTypography> { missingAxiaTheme() }
private val LocalSpacing = staticCompositionLocalOf<AxiaSpacing> { missingAxiaTheme() }
private val LocalShapes = staticCompositionLocalOf<AxiaShapes> { missingAxiaTheme() }
private val LocalDimensions = staticCompositionLocalOf<AxiaDimensions> { missingAxiaTheme() }
private val LocalMotion = staticCompositionLocalOf<AxiaMotion> { missingAxiaTheme() }

/** The platform host provides its live accessibility preference, inherited by nested themes. */
val LocalAxiaReduceMotion = staticCompositionLocalOf { false }

private fun missingAxiaTheme(): Nothing = error("AxiaTheme is not present")

object AxiaTheme {
    val semanticColors: AxiaSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalSemanticColors.current

    val positionColors: AxiaPositionColors
        @Composable
        @ReadOnlyComposable
        get() = LocalPositionColors.current

    val interactionColors: AxiaInteractionColors
        @Composable
        @ReadOnlyComposable
        get() = LocalInteractionColors.current

    val typography: AxiaTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalAxiaTypography.current

    val spacing: AxiaSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalSpacing.current

    val shapes: AxiaShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalShapes.current

    val dimensions: AxiaDimensions
        @Composable
        @ReadOnlyComposable
        get() = LocalDimensions.current

    val motion: AxiaMotion
        @Composable
        @ReadOnlyComposable
        get() = LocalMotion.current
}

@Composable
fun AxiaTheme(
    themeMode: AxiaThemeMode = AxiaThemeMode.System,
    reduceMotion: Boolean = LocalAxiaReduceMotion.current,
    content: @Composable () -> Unit,
) {
    val systemInDarkTheme = isSystemInDarkTheme()
    val useDarkTheme =
        when (themeMode) {
            AxiaThemeMode.System -> systemInDarkTheme
            AxiaThemeMode.Light -> false
            AxiaThemeMode.Dark -> true
        }
    val semanticColors = if (useDarkTheme) darkAxiaSemanticColors else lightAxiaSemanticColors
    val positionColors = if (useDarkTheme) darkAxiaPositionColors else lightAxiaPositionColors
    val interactionColors = if (useDarkTheme) darkAxiaInteractionColors else lightAxiaInteractionColors
    val typography = rememberAxiaTypography()
    val materialTypography = remember(typography) { typography.toMaterialTypography() }
    val materialColorScheme = remember(semanticColors, useDarkTheme) { semanticColors.toMaterialColorScheme(useDarkTheme) }
    val motion = if (reduceMotion) reducedAxiaMotion else defaultAxiaMotion
    val materialShapes =
        remember {
            Shapes(
                extraSmall = defaultAxiaShapes.control,
                small = defaultAxiaShapes.control,
                medium = defaultAxiaShapes.panel,
                large = defaultAxiaShapes.panel,
                extraLarge = defaultAxiaShapes.panel,
            )
        }

    CompositionLocalProvider(
        LocalSemanticColors provides semanticColors,
        LocalPositionColors provides positionColors,
        LocalInteractionColors provides interactionColors,
        LocalAxiaTypography provides typography,
        LocalSpacing provides defaultAxiaSpacing,
        LocalShapes provides defaultAxiaShapes,
        LocalDimensions provides defaultAxiaDimensions,
        LocalMotion provides motion,
        LocalAxiaReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = materialTypography,
            shapes = materialShapes,
            content = content,
        )
    }
}

private fun AxiaSemanticColors.toMaterialColorScheme(useDarkTheme: Boolean) =
    if (useDarkTheme) {
        darkColorScheme(
            primary = action,
            onPrimary = onAction,
            primaryContainer = surfaceSubtle,
            onPrimaryContainer = action,
            inversePrimary = lightAxiaSemanticColors.action,
            secondary = textSecondary,
            onSecondary = surface,
            secondaryContainer = surfaceSubtle,
            onSecondaryContainer = textPrimary,
            tertiary = info.content,
            onTertiary = onAction,
            tertiaryContainer = info.container,
            onTertiaryContainer = info.content,
            background = background,
            onBackground = textPrimary,
            surface = surface,
            onSurface = textPrimary,
            surfaceVariant = surfaceSubtle,
            onSurfaceVariant = textSecondary,
            surfaceTint = action,
            inverseSurface = textPrimary,
            inverseOnSurface = background,
            error = danger.content,
            onError = onAction,
            errorContainer = danger.container,
            onErrorContainer = danger.content,
            outline = border,
            outlineVariant = border,
            scrim = Color.Black,
            surfaceBright = surfaceSubtle,
            surfaceContainer = surface,
            surfaceContainerHigh = surfaceSubtle,
            surfaceContainerHighest = surfaceSubtle,
            surfaceContainerLow = surface,
            surfaceContainerLowest = background,
            surfaceDim = background,
            primaryFixed = lightAxiaSemanticColors.surfaceSubtle,
            primaryFixedDim = lightAxiaSemanticColors.surfaceSubtle,
            onPrimaryFixed = lightAxiaSemanticColors.textPrimary,
            onPrimaryFixedVariant = lightAxiaSemanticColors.action,
            secondaryFixed = lightAxiaSemanticColors.surfaceSubtle,
            secondaryFixedDim = lightAxiaSemanticColors.surfaceSubtle,
            onSecondaryFixed = lightAxiaSemanticColors.textPrimary,
            onSecondaryFixedVariant = lightAxiaSemanticColors.textSecondary,
            tertiaryFixed = lightAxiaSemanticColors.info.container,
            tertiaryFixedDim = lightAxiaSemanticColors.info.container,
            onTertiaryFixed = lightAxiaSemanticColors.textPrimary,
            onTertiaryFixedVariant = lightAxiaSemanticColors.info.content,
        )
    } else {
        lightColorScheme(
            primary = action,
            onPrimary = onAction,
            primaryContainer = surfaceSubtle,
            onPrimaryContainer = action,
            inversePrimary = darkAxiaSemanticColors.action,
            secondary = textSecondary,
            onSecondary = surface,
            secondaryContainer = surfaceSubtle,
            onSecondaryContainer = textPrimary,
            tertiary = info.content,
            onTertiary = onAction,
            tertiaryContainer = info.container,
            onTertiaryContainer = info.content,
            background = background,
            onBackground = textPrimary,
            surface = surface,
            onSurface = textPrimary,
            surfaceVariant = surfaceSubtle,
            onSurfaceVariant = textSecondary,
            surfaceTint = action,
            inverseSurface = textPrimary,
            inverseOnSurface = surface,
            error = danger.content,
            onError = onAction,
            errorContainer = danger.container,
            onErrorContainer = danger.content,
            outline = border,
            outlineVariant = border,
            scrim = Color.Black,
            surfaceBright = surface,
            surfaceContainer = surface,
            surfaceContainerHigh = surfaceSubtle,
            surfaceContainerHighest = surfaceSubtle,
            surfaceContainerLow = background,
            surfaceContainerLowest = surface,
            surfaceDim = surfaceSubtle,
            primaryFixed = lightAxiaSemanticColors.surfaceSubtle,
            primaryFixedDim = lightAxiaSemanticColors.surfaceSubtle,
            onPrimaryFixed = lightAxiaSemanticColors.textPrimary,
            onPrimaryFixedVariant = lightAxiaSemanticColors.action,
            secondaryFixed = lightAxiaSemanticColors.surfaceSubtle,
            secondaryFixedDim = lightAxiaSemanticColors.surfaceSubtle,
            onSecondaryFixed = lightAxiaSemanticColors.textPrimary,
            onSecondaryFixedVariant = lightAxiaSemanticColors.textSecondary,
            tertiaryFixed = lightAxiaSemanticColors.info.container,
            tertiaryFixedDim = lightAxiaSemanticColors.info.container,
            onTertiaryFixed = lightAxiaSemanticColors.textPrimary,
            onTertiaryFixedVariant = lightAxiaSemanticColors.info.content,
        )
    }
