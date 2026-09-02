package team.themoment.axia.designsystem.foundation

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import axia.app.shared.generated.resources.Res
import axia.app.shared.generated.resources.pretendard_variable
import org.jetbrains.compose.resources.Font

@Immutable
data class AxiaTypography(
    val metadata: TextStyle,
    val secondary: TextStyle,
    val body: TextStyle,
    val bodyEmphasis: TextStyle,
    val sectionTitle: TextStyle,
    val pageTitle: TextStyle,
    val numericMetadata: TextStyle,
    val numericBody: TextStyle,
)

@Composable
internal fun rememberAxiaTypography(): AxiaTypography {
    val normalFont = Font(resource = Res.font.pretendard_variable, weight = FontWeight.Normal)
    val mediumFont = Font(resource = Res.font.pretendard_variable, weight = FontWeight.Medium)
    val semiBoldFont = Font(resource = Res.font.pretendard_variable, weight = FontWeight.SemiBold)
    val boldFont = Font(resource = Res.font.pretendard_variable, weight = FontWeight.Bold)
    val pretendard =
        remember(normalFont, mediumFont, semiBoldFont, boldFont) {
            FontFamily(normalFont, mediumFont, semiBoldFont, boldFont)
        }

    return remember(pretendard) { axiaTypography(pretendard) }
}

private fun axiaTypography(pretendard: FontFamily): AxiaTypography {
    val metadata =
        TextStyle(
            fontFamily = pretendard,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    val body =
        TextStyle(
            fontFamily = pretendard,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )

    return AxiaTypography(
        metadata = metadata,
        secondary =
            TextStyle(
                fontFamily = pretendard,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
        body = body,
        bodyEmphasis =
            TextStyle(
                fontFamily = pretendard,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
        sectionTitle =
            TextStyle(
                fontFamily = pretendard,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 28.sp,
            ),
        pageTitle =
            TextStyle(
                fontFamily = pretendard,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                lineHeight = 36.sp,
            ),
        numericMetadata = metadata.copy(fontFeatureSettings = "tnum"),
        numericBody = body.copy(fontFeatureSettings = "tnum"),
    )
}

internal fun AxiaTypography.toMaterialTypography(): Typography {
    val labelLarge = body.copy(fontWeight = FontWeight.Medium)
    val labelMedium = secondary.copy(fontWeight = FontWeight.Medium)
    val labelSmall = metadata.copy(fontWeight = FontWeight.Medium)

    return Typography(
        fontFamily = body.fontFamily ?: FontFamily.Default,
        displayLarge = pageTitle,
        displayMedium = pageTitle,
        displaySmall = sectionTitle,
        headlineLarge = pageTitle,
        headlineMedium = sectionTitle,
        headlineSmall = sectionTitle,
        titleLarge = sectionTitle,
        titleMedium = bodyEmphasis,
        titleSmall = labelLarge,
        bodyLarge = bodyEmphasis.copy(fontWeight = FontWeight.Normal),
        bodyMedium = body,
        bodySmall = secondary,
        labelLarge = labelLarge,
        labelMedium = labelMedium,
        labelSmall = labelSmall,
    )
}
