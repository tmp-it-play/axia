package team.themoment.axia.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MapModeBar(
    selected: MapMode,
    onSelect: (MapMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Row(modifier = Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            MapMode.entries.forEach { mode ->
                val colors = MaterialTheme.colorScheme
                val isSelected = mode == selected
                Surface(
                    onClick = { onSelect(mode) },
                    shape = RoundedCornerShape(6.dp),
                    color = if (isSelected) colors.primary else colors.surface,
                    contentColor = if (isSelected) colors.onPrimary else colors.onSurface,
                ) {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun MapLegend(
    mode: MapMode,
    modifier: Modifier = Modifier,
) {
    val scale = mode.scale ?: return
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = mode.label, style = MaterialTheme.typography.labelLarge)
            scale.colors.zip(scale.labels).reversed().forEach { (color, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(14.dp).background(color, RoundedCornerShape(3.dp)))
                    Text(text = label, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
