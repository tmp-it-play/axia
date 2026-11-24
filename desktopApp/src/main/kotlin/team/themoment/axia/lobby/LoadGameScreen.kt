package team.themoment.axia.lobby

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.CampaignSlot
import team.themoment.axia.SavedCampaign
import team.themoment.axia.simulation.campaign.CampaignScenario
import team.themoment.axia.simulation.world.World
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.DossierPanel
import team.themoment.axia.ui.dossier.PaperTag

@Composable
internal fun LoadGameScreen(
    world: World,
    slots: Map<CampaignSlot, SavedCampaign?>,
    onLoad: (CampaignSlot) -> Unit,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(DossierColors.desk).padding(32.dp), contentAlignment = Alignment.Center) {
        DossierPanel("게임 불러오기", modifier = Modifier.widthIn(max = 540.dp).fillMaxWidth()) {
            CampaignSlot.entries.forEach { slot ->
                val occupied = slot in slots
                val save = slots[slot]
                val detail =
                    when {
                        save != null -> {
                            val country = world.countries[save.countryId]?.name ?: save.countryId
                            val scenario = if (save.scenario == CampaignScenario.HISTORICAL) "" else " · ${save.scenario.label}"
                            "$country · ${save.date}$scenario"
                        }

                        occupied -> {
                            "파일 확인 필요"
                        }

                        else -> {
                            "빈 슬롯"
                        }
                    }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = occupied, role = Role.Button) { onLoad(slot) }
                        .padding(horizontal = 8.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(slot.label, style = DossierType.title(16.sp), color = if (occupied) DossierColors.ink else DossierColors.inkMuted)
                    Spacer(Modifier.weight(1f))
                    Text(detail, style = DossierType.number(14.sp), color = DossierColors.inkMuted)
                }
                HorizontalDivider(color = DossierColors.paperLine)
            }
            Spacer(Modifier.height(20.dp))
            PaperTag("로비로 돌아가기", onClick = onBack)
        }
    }
}
