package team.themoment.axia.game.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.PaperTag

@Composable
internal fun SocietyRegister(
    backLabel: String,
    list: @Composable (openDetail: () -> Unit) -> Unit,
    detail: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 720.dp) {
            LedgerSpread(stacked = false, leftWeight = 1f, left = { list {} }, right = detail)
        } else {
            var showDetail by remember { mutableStateOf(false) }
            if (showDetail) {
                Column(Modifier.fillMaxSize()) {
                    PaperTag(backLabel, onClick = { showDetail = false })
                    LedgerColumn(Modifier.weight(1f)) { detail() }
                }
            } else {
                LedgerColumn(Modifier.fillMaxSize()) { list { showDetail = true } }
            }
        }
    }
}

@Composable
internal fun SocietyRegisterHeader(
    name: String,
    middle: String,
    end: String,
) {
    Row(
        Modifier.fillMaxWidth().background(DossierColors.blueInk).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(name, Modifier.weight(1f), style = DossierType.title(13.sp), color = DossierColors.paper)
        Text(middle, Modifier.width(65.dp), style = DossierType.number(12.sp), color = DossierColors.paper, textAlign = TextAlign.End)
        Text(end, Modifier.width(54.dp), style = DossierType.number(12.sp), color = DossierColors.paper, textAlign = TextAlign.End)
    }
}

@Composable
internal fun SocietyRegisterRow(
    name: String,
    middle: String,
    end: String,
    endColor: Color,
    selected: Boolean,
    swatch: Color? = null,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) DossierColors.paperLine else Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand)
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 36.dp)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        swatch?.let { Box(Modifier.size(10.dp).background(it)) }
        Text(name, Modifier.weight(1f), style = DossierType.title(14.sp), color = DossierColors.ink)
        Text(middle, Modifier.width(65.dp), style = DossierType.number(13.sp), color = DossierColors.inkMuted, textAlign = TextAlign.End)
        Text(end, Modifier.width(54.dp), style = DossierType.number(14.sp), color = endColor, textAlign = TextAlign.End)
    }
    HorizontalDivider(color = DossierColors.folderEdge.copy(alpha = 0.33f))
}
