package team.themoment.axia.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import team.themoment.axia.resources.Res
import team.themoment.axia.resources.pretendard_variable

@Composable
fun AxiaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        typography = rememberPretendardTypography(),
        content = content,
    )
}

@Composable
private fun rememberPretendardTypography(): Typography {
    val regular = Font(resource = Res.font.pretendard_variable, weight = FontWeight.Normal)
    val medium = Font(resource = Res.font.pretendard_variable, weight = FontWeight.Medium)
    val semiBold = Font(resource = Res.font.pretendard_variable, weight = FontWeight.SemiBold)
    val bold = Font(resource = Res.font.pretendard_variable, weight = FontWeight.Bold)
    return remember(regular, medium, semiBold, bold) {
        val pretendard = FontFamily(regular, medium, semiBold, bold)
        Typography().withFontFamily(pretendard)
    }
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
