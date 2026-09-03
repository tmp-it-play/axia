package team.themoment.axia.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import team.themoment.axia.designsystem.theme.AxiaTheme

@Composable
fun AxiaCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AxiaTheme.dimensions.minimumInteractiveSize)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = AxiaTheme.spacing.space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
    ) {
        Checkbox(
            checked,
            onCheckedChange = null,
            enabled = enabled,
            colors =
                CheckboxDefaults.colors(
                    checkedColor = AxiaTheme.semanticColors.action,
                    uncheckedColor = AxiaTheme.semanticColors.textSecondary,
                    checkmarkColor = AxiaTheme.semanticColors.onAction,
                ),
        )
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = AxiaTheme.typography.body,
                color = if (enabled) AxiaTheme.semanticColors.textPrimary else AxiaTheme.interactionColors.disabledContent,
            )
            description?.let { Text(it, style = AxiaTheme.typography.metadata, color = AxiaTheme.semanticColors.textSecondary) }
        }
    }
}
