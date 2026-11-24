package team.themoment.axia.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import team.themoment.axia.resources.Res
import team.themoment.axia.resources.axia_ribbon_bold
import team.themoment.axia.resources.axia_ribbon_regular
import team.themoment.axia.resources.pretendard_variable

/** 서류철 UI의 색. 책상 위에 놓인 마닐라 폴더와 종이, 잉크, 도장. */
object DossierColors {
    val desk = Color(0xFF1C2024)
    val deskRaised = Color(0xFF2A3036)
    val deskText = Color(0xFFE8DFC9)
    val deskTextMuted = Color(0xFFA9A08C)
    val folder = Color(0xFFCDB37C)
    val folderShade = Color(0xFFB59B63)
    val folderEdge = Color(0xFF8C7547)
    val paper = Color(0xFFF3EAD3)
    val paperShade = Color(0xFFE7DBBE)
    val paperLine = Color(0xFFDCCDA9)
    val ink = Color(0xFF2A241D)
    val inkMuted = Color(0xFF6B5F50)
    val blueInk = Color(0xFF2F4B66)
    val stamp = Color(0xFF9E2B25)
    val positive = Color(0xFF3D6A38)
    val negative = Color(0xFF9E2B25)
    val metal = Color(0xFF8A8F94)
}

private val LocalDossierFont = staticCompositionLocalOf<FontFamily> { FontFamily.SansSerif }
private val LocalMapFont = staticCompositionLocalOf<FontFamily> { FontFamily.SansSerif }

/** 문서와 계기판은 캠페인의 서체를 따르고, 지도 국명은 별도의 지도용 고딕을 쓴다. */
object DossierType {
    @Composable
    @ReadOnlyComposable
    fun title(
        size: TextUnit = 16.sp,
        weight: FontWeight = FontWeight.ExtraBold,
    ): TextStyle = TextStyle(fontFamily = LocalDossierFont.current, fontWeight = weight, fontSize = size)

    /** 지도 국가명. HOI4 지도 글꼴(RGFX White Map Font)처럼 굵은 고딕. */
    @Composable
    @ReadOnlyComposable
    fun mapLabel(size: TextUnit): TextStyle =
        TextStyle(fontFamily = LocalMapFont.current, fontWeight = FontWeight.ExtraBold, fontSize = size)

    @Composable
    @ReadOnlyComposable
    fun number(
        size: TextUnit = 14.sp,
        weight: FontWeight = FontWeight.Medium,
    ): TextStyle =
        TextStyle(
            fontFamily = LocalDossierFont.current,
            fontWeight = weight,
            fontSize = size,
            lineHeight = size * 1.3f,
            fontFeatureSettings = "tnum",
        )
}

private val dossierColorScheme =
    lightColorScheme(
        primary = DossierColors.blueInk,
        onPrimary = DossierColors.paper,
        secondary = DossierColors.folderEdge,
        onSecondary = DossierColors.paper,
        tertiary = DossierColors.stamp,
        background = DossierColors.desk,
        onBackground = DossierColors.deskText,
        surface = DossierColors.paper,
        onSurface = DossierColors.ink,
        surfaceVariant = DossierColors.paperShade,
        onSurfaceVariant = DossierColors.inkMuted,
        surfaceContainer = DossierColors.paper,
        surfaceContainerHigh = DossierColors.paper,
        surfaceContainerHighest = DossierColors.paperShade,
        outline = DossierColors.folderEdge,
        outlineVariant = DossierColors.paperLine,
        error = DossierColors.stamp,
    )

@Composable
fun AxiaTheme(content: @Composable () -> Unit) {
    val pretendard = rememberPretendard()
    val typography = remember(pretendard) { Typography().withFontFamily(pretendard) }
    MaterialTheme(colorScheme = dossierColorScheme, typography = typography) {
        CompositionLocalProvider(LocalDossierFont provides pretendard, LocalMapFont provides pretendard, content = content)
    }
}

@Composable
internal fun CampaignTypography(content: @Composable () -> Unit) {
    val regular = Font(Res.font.axia_ribbon_regular, FontWeight.Normal)
    val bold = Font(Res.font.axia_ribbon_bold, FontWeight.Bold)
    val typewriter = remember(regular, bold) { FontFamily(regular, bold) }
    val typography = remember(typewriter) { Typography().withFontFamily(typewriter) }
    MaterialTheme(typography = typography) {
        CompositionLocalProvider(LocalDossierFont provides typewriter, content = content)
    }
}

@Composable
private fun rememberPretendard(): FontFamily {
    val regular = Font(resource = Res.font.pretendard_variable, weight = FontWeight.Normal)
    val medium = Font(resource = Res.font.pretendard_variable, weight = FontWeight.Medium)
    val semiBold = Font(resource = Res.font.pretendard_variable, weight = FontWeight.SemiBold)
    val bold = Font(resource = Res.font.pretendard_variable, weight = FontWeight.Bold)
    val extraBold = Font(resource = Res.font.pretendard_variable, weight = FontWeight.ExtraBold)
    return remember(regular, medium, semiBold, bold, extraBold) { FontFamily(regular, medium, semiBold, bold, extraBold) }
}

private fun Typography.withFontFamily(fontFamily: FontFamily): Typography {
    fun TextStyle.withFamily() = copy(fontFamily = fontFamily)
    return copy(
        displayLarge = displayLarge.withFamily(),
        displayMedium = displayMedium.withFamily(),
        displaySmall = displaySmall.withFamily(),
        headlineLarge = headlineLarge.withFamily(),
        headlineMedium = headlineMedium.withFamily(),
        headlineSmall = headlineSmall.withFamily(),
        titleLarge = titleLarge.withFamily(),
        titleMedium = titleMedium.withFamily(),
        titleSmall = titleSmall.withFamily(),
        bodyLarge = bodyLarge.withFamily(),
        bodyMedium = bodyMedium.withFamily(),
        bodySmall = bodySmall.withFamily(),
        labelLarge = labelLarge.withFamily(),
        labelMedium = labelMedium.withFamily(),
        labelSmall = labelSmall.withFamily(),
    )
}
