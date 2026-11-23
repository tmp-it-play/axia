package team.themoment.axia.game.panels

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.simulation.events.GameEvent
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.DossierPanel
import team.themoment.axia.ui.dossier.Stamp

/** 결정을 기다리는 사건. 전보처럼 서류철 한 장에 사건과 선택지를 보여 준다. */
@Composable
fun EventDialog(
    event: GameEvent,
    remaining: Int,
    countryName: (String) -> String,
    onChoose: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    DossierPanel(title = "긴급 전보", scrollable = true, modifier = modifier.width(460.dp)) {
        Stamp(event.category.label, color = DossierColors.negative)
        Text(event.name, style = DossierType.title(18.sp), color = DossierColors.ink, modifier = Modifier.padding(top = 6.dp))
        Text(
            "${shortDate(event.date)} · ${countryName(event.countryId)}",
            style = DossierType.number(11.sp),
            color = DossierColors.inkMuted,
        )
        Text(event.text, style = DossierType.number(14.sp), color = DossierColors.ink, modifier = Modifier.padding(vertical = 8.dp))
        event.deadline?.let { Hint("${shortDate(it)}까지 고르지 않으면 첫 번째 대응을 따릅니다.") }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            event.choices.forEachIndexed { index, choice ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, DossierColors.inkMuted)
                        .clickable { onChoose(event.id, index) }
                        .padding(8.dp),
                ) {
                    Text(choice.label, style = DossierType.title(14.sp), color = DossierColors.blueInk)
                    Text(choice.description, style = DossierType.number(12.sp), color = DossierColors.ink)
                }
            }
        }
        if (remaining > 1) Hint("결정을 기다리는 사건이 ${remaining - 1}건 더 있습니다.")
    }
}
