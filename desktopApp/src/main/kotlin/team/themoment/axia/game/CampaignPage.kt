package team.themoment.axia.game

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.controlTooltip
import team.themoment.axia.ui.dossier.paperTexture

@Composable
internal fun CampaignPage(
    panel: GamePanel,
    desk: CampaignDesk,
    reviewBody: String,
    onReturnToMap: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().paperTexture().background(DossierColors.paper.copy(alpha = 0.72f))) {
        Row(
            Modifier.fillMaxWidth().background(DossierColors.deskRaised).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).selectableGroup()) {
                GamePanel.entries.forEach { destination ->
                    val active = panel == destination
                    val ink = if (active) DossierColors.paper else DossierColors.deskText
                    Row(
                        Modifier
                            .background(if (active) DossierColors.blueInk else DossierColors.deskRaised)
                            .controlTooltip(destination.label, destination.tooltip)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .selectable(selected = active, role = Role.Tab) { desk.panel = destination }
                            .height(36.dp)
                            .padding(horizontal = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        ConsoleGlyph(destination.symbol, Modifier.size(16.dp), if (active) ink else destination.symbol.metal)
                        Text(destination.label, style = DossierType.title(14.sp), color = ink, maxLines = 1)
                    }
                }
            }
            Row(
                Modifier
                    .controlTooltip("지도로 돌아가기", "현재 화면을 닫고 세계 지도로 돌아갑니다. Esc 키로도 돌아갈 수 있습니다.")
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(role = Role.Button, onClick = onReturnToMap)
                    .height(36.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ConsoleGlyph(ConsoleSymbol.GLOBE, Modifier.size(17.dp), DossierColors.folder)
                Text("지도로 돌아가기", style = DossierType.title(13.sp), color = DossierColors.paper)
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val margin = if (maxWidth < 780.dp) 16.dp else 28.dp
            val showReviewBody = panel == GamePanel.POLICY && maxWidth >= 740.dp
            Column(Modifier.fillMaxSize().padding(horizontal = margin, vertical = 10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ConsoleGlyph(panel.symbol, Modifier.size(27.dp), DossierColors.blueInk)
                    val title =
                        if (panel == GamePanel.POLICY &&
                            desk.policy == PolicyPage.HEARING
                        ) {
                            PolicyPage.HEARING.label
                        } else {
                            panel.label
                        }
                    Text(title, style = DossierType.title(27.sp), color = DossierColors.ink)
                    if (showReviewBody) {
                        Text(reviewBody, style = DossierType.number(14.sp), color = DossierColors.inkMuted)
                    }
                    Box(Modifier.weight(1f).padding(start = 10.dp)) { CampaignDeskIndex(desk) }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 5.dp)
                        .height(1.dp)
                        .background(DossierColors.folderEdge),
                )
                key(panel) { Box(Modifier.fillMaxSize().padding(top = 9.dp)) { content() } }
            }
        }
    }
}
