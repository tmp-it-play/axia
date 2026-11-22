package team.themoment.axia.selection

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.game.panels.KeyIndicatorRows
import team.themoment.axia.simulation.economy.EconomyIndicators
import team.themoment.axia.simulation.world.CountryKind
import team.themoment.axia.simulation.world.World
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.DossierPanel
import team.themoment.axia.ui.dossier.SectionTitle

/** 지도에서 고른 지역과 그 나라의 경제 지표를 담은 서류철 카드. */
@Composable
fun SelectionPanel(
    world: World,
    regionId: String,
    economy: EconomyIndicators?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val region = world.region(regionId)
    val country = world.country(region.countryId)
    val territory = region.territoryId?.let(world::territory)
    DossierPanel(title = country.name, onClose = onClose, modifier = modifier.width(320.dp)) {
        if (country.kind == CountryKind.UNCLAIMED) {
            Text(text = "무주지", style = DossierType.title(13.sp), color = DossierColors.stamp)
        }
        territory?.let { Text(text = it.name, style = DossierType.number(13.sp), color = DossierColors.inkMuted) }
        Text(text = region.name, style = DossierType.number(14.sp), color = DossierColors.ink)
        if (economy != null) {
            SectionTitle("경제 지표")
            KeyIndicatorRows(economy)
        }
    }
}
