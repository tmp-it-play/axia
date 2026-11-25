package team.themoment.axia.ui.dossier

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType

/** 서류철 속지 상단의 색인. 같은 위치에서 다른 장을 펼칠 수 있다. */
@Composable
internal fun <T> DossierIndex(
    tabs: List<T>,
    selected: T,
    label: (T) -> String = { it.toString() },
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            tabs.forEach { tab ->
                val active = tab == selected
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .background(
                                if (active) DossierColors.blueInk else DossierColors.folder.copy(alpha = 0.66f),
                                CutCornerShape(topEnd = 5.dp),
                            ).pointerHoverIcon(PointerIcon.Hand)
                            .selectable(selected = active, role = Role.Tab) { if (!active) onSelect(tab) }
                            .heightIn(min = 29.dp)
                            .padding(horizontal = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(tab),
                        style = DossierType.number(13.sp, if (active) FontWeight.Bold else FontWeight.Normal),
                        color = if (active) DossierColors.paper else DossierColors.ink,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(DossierColors.blueInk))
    }
}

@Composable
internal fun DossierLinkRow(
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 34.dp)
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, Modifier.weight(1f), style = DossierType.title(13.sp), color = DossierColors.blueInk)
        Text(detail, style = DossierType.number(12.sp), color = DossierColors.ink)
        Text("›", style = DossierType.title(15.sp), color = DossierColors.blueInk)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(DossierColors.paperLine))
}
