package team.themoment.axia.selection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import team.themoment.axia.simulation.world.CountryKind
import team.themoment.axia.simulation.world.World

@Composable
fun SelectionPanel(
    world: World,
    regionId: String,
    modifier: Modifier = Modifier,
) {
    val region = world.region(regionId)
    val country = world.country(region.countryId)
    val territory = region.territoryId?.let(world::territory)
    Card(modifier = modifier.widthIn(min = 240.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = country.name, style = MaterialTheme.typography.titleLarge)
            if (country.kind == CountryKind.UNCLAIMED) {
                Text(text = "무주지", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            territory?.let {
                Text(text = it.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(text = region.name, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
