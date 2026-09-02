package team.themoment.axia.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import team.themoment.axia.designsystem.foundation.AxiaColorPair
import team.themoment.axia.designsystem.theme.AxiaTheme

enum class AxiaBannerStyle {
    Info,
    Warning,
    Danger,
    Success,
}

@Composable
fun AxiaBanner(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    style: AxiaBannerStyle = AxiaBannerStyle.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    announceChanges: Boolean = false,
) {
    val visual = bannerVisual(style)
    val announcementModifier =
        if (announceChanges) {
            Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        } else {
            Modifier
        }
    AxiaPanel(
        modifier = modifier.then(announcementModifier),
        backgroundColor = visual.colors.container,
        contentColor = visual.colors.content,
        borderColor = visual.colors.content.copy(alpha = 0.42f),
        contentPadding =
            PaddingValues(
                horizontal = AxiaTheme.spacing.space4,
                vertical = AxiaTheme.spacing.space3,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
        ) {
            AxiaIcon(
                kind = visual.icon,
                contentDescription = null,
                tint = visual.colors.content,
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1),
            ) {
                Text(
                    text = title,
                    color = visual.colors.content,
                    style = AxiaTheme.typography.bodyEmphasis,
                )
                if (description != null) {
                    Text(
                        text = description,
                        color = visual.colors.content,
                        style = AxiaTheme.typography.secondary,
                    )
                }
            }
            if (actionLabel != null && onAction != null) {
                AxiaButton(
                    label = actionLabel,
                    onClick = onAction,
                    style =
                        if (style == AxiaBannerStyle.Danger) {
                            AxiaButtonStyle.Danger
                        } else {
                            AxiaButtonStyle.Secondary
                        },
                )
            }
        }
    }
}

private data class BannerVisual(
    val colors: AxiaColorPair,
    val icon: AxiaIconKind,
)

@Composable
private fun bannerVisual(style: AxiaBannerStyle): BannerVisual {
    val colors = AxiaTheme.semanticColors
    return when (style) {
        AxiaBannerStyle.Info -> BannerVisual(colors.info, AxiaIconKind.Info)
        AxiaBannerStyle.Warning -> BannerVisual(colors.warning, AxiaIconKind.Warning)
        AxiaBannerStyle.Danger -> BannerVisual(colors.danger, AxiaIconKind.Warning)
        AxiaBannerStyle.Success -> BannerVisual(colors.success, AxiaIconKind.Success)
    }
}
