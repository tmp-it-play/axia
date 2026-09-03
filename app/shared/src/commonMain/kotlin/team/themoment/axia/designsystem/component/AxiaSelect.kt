package team.themoment.axia.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import team.themoment.axia.designsystem.theme.AxiaTheme

data class AxiaSelectOption<T>(
    val value: T,
    val label: String,
)

@Composable
fun <T> AxiaSelect(
    label: String,
    value: T,
    options: List<AxiaSelectOption<T>>,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = AxiaTheme.typography.metadata, color = AxiaTheme.semanticColors.textSecondary)
        Box {
            AxiaButton(
                label = options.firstOrNull { it.value == value }?.label ?: "선택",
                onClick = { expanded = true },
                style = AxiaButtonStyle.Secondary,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = label },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = AxiaTheme.semanticColors.surface) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label, style = AxiaTheme.typography.body, color = AxiaTheme.semanticColors.textPrimary) },
                        onClick = {
                            expanded = false
                            onChange(option.value)
                        },
                    )
                }
            }
        }
    }
}
