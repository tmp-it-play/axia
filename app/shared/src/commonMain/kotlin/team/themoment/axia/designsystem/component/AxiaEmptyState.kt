package team.themoment.axia.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import team.themoment.axia.designsystem.theme.AxiaTheme

@Composable
fun AxiaEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier =
            modifier
                .padding(AxiaTheme.spacing.space8)
                .widthIn(max = 560.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
    ) {
        AxiaIcon(
            kind = AxiaIconKind.Inbox,
            contentDescription = null,
            tint = AxiaTheme.semanticColors.textSecondary,
        )
        Text(
            text = title,
            color = AxiaTheme.semanticColors.textPrimary,
            style = AxiaTheme.typography.bodyEmphasis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = description,
            color = AxiaTheme.semanticColors.textSecondary,
            style = AxiaTheme.typography.body,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            AxiaButton(
                label = actionLabel,
                onClick = onAction,
                modifier = Modifier.padding(top = AxiaTheme.spacing.space1),
                style = AxiaButtonStyle.Tertiary,
            )
        }
    }
}
