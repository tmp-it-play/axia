package team.themoment.axia.selection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import team.themoment.axia.format.formatDollars
import team.themoment.axia.format.formatPercent
import team.themoment.axia.format.formatPopulation
import team.themoment.axia.format.formatWholeDollars
import team.themoment.axia.simulation.economy.EconomyIndicators
import team.themoment.axia.simulation.world.CountryKind
import team.themoment.axia.simulation.world.World

@Composable
fun SelectionPanel(
    world: World,
    regionId: String,
    economy: EconomyIndicators?,
    modifier: Modifier = Modifier,
) {
    val region = world.region(regionId)
    val country = world.country(region.countryId)
    val territory = region.territoryId?.let(world::territory)
    Card(modifier = modifier.width(300.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = country.name, style = MaterialTheme.typography.titleLarge)
            if (country.kind == CountryKind.UNCLAIMED) {
                Text(text = "무주지", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            territory?.let {
                Text(text = it.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(text = region.name, style = MaterialTheme.typography.bodyLarge)
            if (economy != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                EconomyRows(economy)
            }
        }
    }
}

@Composable
private fun EconomyRows(economy: EconomyIndicators) {
    val estimated = economy.estimated
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        IndicatorRow("인구", formatPopulation(economy.population), "population" in estimated)
        IndicatorRow("GDP", formatDollars(economy.gdp), "gdp" in estimated)
        IndicatorRow("1인당 GDP", formatWholeDollars(economy.gdpPerCapita), "gdp" in estimated || "population" in estimated)
        IndicatorRow("경제성장률", formatPercent(economy.growth, signed = true), "growth" in estimated)
        IndicatorRow("물가상승률", formatPercent(economy.inflation), "inflation" in estimated)
        IndicatorRow("실업률", formatPercent(economy.unemployment), "unemployment" in estimated)
        IndicatorRow(
            "재정수지 (GDP 대비)",
            formatPercent(economy.budgetBalance, signed = true),
            estimated.any { it in FISCAL_FIELDS },
        )
        IndicatorRow("국가부채 (GDP 대비)", formatPercent(economy.debtRatio), "debt" in estimated)
    }
}

private val FISCAL_FIELDS = setOf("revenue", "expense", "interest")

@Composable
private fun IndicatorRow(
    label: String,
    value: String,
    estimated: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        if (estimated) {
            Text(
                text = "추정",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
