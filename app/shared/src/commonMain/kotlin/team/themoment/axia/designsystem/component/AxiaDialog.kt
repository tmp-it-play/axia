package team.themoment.axia.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import team.themoment.axia.designsystem.theme.AxiaTheme

@Composable
fun AxiaDialog(
    title: String,
    onDismiss: () -> Unit,
    dismissEnabled: Boolean = true,
    footer: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (dismissEnabled) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = dismissEnabled, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .background(AxiaTheme.semanticColors.surface, AxiaTheme.shapes.panel)
                .border(AxiaTheme.dimensions.hairline, AxiaTheme.semanticColors.border, AxiaTheme.shapes.panel)
                .semantics { paneTitle = title },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(AxiaTheme.spacing.space5),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
            ) {
                Text(
                    title,
                    Modifier.weight(1f).semantics {
                        heading()
                    },
                    style = AxiaTheme.typography.sectionTitle,
                    color = AxiaTheme.semanticColors.textPrimary,
                )
                AxiaButton("닫기", onDismiss, style = AxiaButtonStyle.Tertiary, enabled = dismissEnabled)
            }
            AxiaDivider()
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(AxiaTheme.spacing.space5),
                verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4),
                content = content,
            )
            AxiaDivider()
            Row(
                Modifier.fillMaxWidth().padding(AxiaTheme.spacing.space5),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                footer()
            }
        }
    }
}
