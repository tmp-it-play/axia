package team.themoment.axia.game.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatPercent
import team.themoment.axia.format.formatPopulation
import team.themoment.axia.format.formatWholeDollars
import team.themoment.axia.game.color
import team.themoment.axia.simulation.nation.NationSnapshot
import team.themoment.axia.simulation.nation.PopulationGroup
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.CompositionBar
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.Segment
import team.themoment.axia.ui.dossier.Swatch

@Composable
fun SocietyPanel(nation: NationSnapshot) {
    var expanded by remember { mutableStateOf<PopulationGroup?>(null) }
    SectionTitle("성인 인구 구성")
    CompositionBar(nation.groups.map { Segment(it.group.label, it.share, it.group.color()) })
    Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        nation.groups.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { Swatch(it.group.color(), "${it.group.label} ${formatPercent(it.share)}", Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    SectionTitle("지지율이 움직인 이유")
    FactorBars(nation.approvalFactors)
    SectionTitle("집단 (눌러서 이유 보기)")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        nation.groups.forEach { group ->
            Column(Modifier.clickable { expanded = if (expanded == group.group) null else group.group }) {
                SatisfactionRow(group.group.label, group.satisfaction)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(104.dp))
                    Text(
                        text = "${formatPopulation(group.population)} · 1인당 소득 ${formatWholeDollars(group.income)}",
                        style = DossierType.number(11.sp),
                        color = DossierColors.inkMuted,
                    )
                }
                if (expanded == group.group) {
                    Column(Modifier.padding(start = 8.dp, top = 4.dp)) { FactorBars(group.factors) }
                }
            }
        }
    }
}
