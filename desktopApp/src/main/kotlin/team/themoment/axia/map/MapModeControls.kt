package team.themoment.axia.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.PaperTag

/** 책상 위 띠에 꽂힌 꼬리표로 지도 모드를 고른다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapModeBar(
    selected: MapMode,
    onSelect: (MapMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier =
            modifier
                .widthIn(max = 560.dp)
                .shadow(4.dp, RoundedCornerShape(4.dp))
                .background(DossierColors.desk, RoundedCornerShape(4.dp))
                .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MapMode.entries.forEach { mode -> PaperTag(text = mode.label, selected = mode == selected, onClick = { onSelect(mode) }) }
    }
}

@Composable
fun MapLegend(
    mode: MapMode,
    modifier: Modifier = Modifier,
) {
    val scale = mode.scale ?: return
    Column(
        modifier =
            modifier
                .shadow(4.dp, RoundedCornerShape(3.dp))
                .background(DossierColors.paper, RoundedCornerShape(3.dp))
                .border(1.dp, DossierColors.paperLine, RoundedCornerShape(3.dp))
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = mode.label, style = DossierType.title(13.sp), color = DossierColors.blueInk)
        scale.colors.zip(scale.labels).reversed().forEach { (color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .size(
                            14.dp,
                        ).background(color, RoundedCornerShape(2.dp))
                        .border(1.dp, DossierColors.inkMuted.copy(alpha = 0.4f)),
                )
                Text(text = label, style = DossierType.number(12.sp, weight = FontWeight.Normal), color = DossierColors.ink)
            }
        }
    }
}
