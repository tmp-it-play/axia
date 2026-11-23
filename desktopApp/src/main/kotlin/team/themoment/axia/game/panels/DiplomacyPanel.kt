package team.themoment.axia.game.panels

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import team.themoment.axia.simulation.WorldSnapshot
import team.themoment.axia.simulation.diplomacy.BilateralSnapshot
import team.themoment.axia.simulation.diplomacy.DiplomaticAction
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.Stamp
import team.themoment.axia.ui.dossier.StatRow
import kotlin.math.roundToInt

/** 외교 서류철. 관계, 동맹, 무역협정, 제재와 외교 행위. 행위에는 정치 자본이 든다. */
@Composable
fun DiplomacyPanel(
    world: WorldSnapshot,
    politicalCapital: Double,
    countryName: (String) -> String,
    onAction: (String, DiplomaticAction) -> Unit,
) {
    val diplomacy = world.diplomacy
    SectionTitle("외교 역량")
    StatRow("정치 자본", "${politicalCapital.roundToInt()} / 100")
    Hint("외교 행위에는 정치 자본이 듭니다. 상대가 제안을 거절하면 자본은 돌려받습니다.")
    StatRow(
        "국제 유가 (2024년 1월 = 100)",
        world.trade.oilPrice
            .roundToInt()
            .toString(),
    )
    world.trade.partners[diplomacy.playerId]?.let { partners ->
        SectionTitle("주요 교역 상대 (수출 비중 추정)")
        partners.take(5).forEach { (id, share) -> StatRow(countryName(id), formatPercent(share)) }
        world.trade.tradeChange[diplomacy.playerId]?.let {
            StatRow("제재·협정에 따른 잠재 GDP 변화", formatPercent(it, signed = true))
        }
    }
    SectionTitle("가입한 국제기구")
    if (diplomacy.organizations.isEmpty()) Hint("가입한 기구가 없습니다.")
    diplomacy.organizations.forEach { StatRow(it.name, it.kind.label) }
    SectionTitle("양자 관계 (눌러서 행위 보기)")
    Hint("플레이 가능 국가와 동맹국, 관계가 아주 좋거나 나쁜 나라를 보여 줍니다.")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        diplomacy.partners.forEach { partner -> PartnerCard(partner, politicalCapital, countryName, onAction) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PartnerCard(
    partner: BilateralSnapshot,
    politicalCapital: Double,
    countryName: (String) -> String,
    onAction: (String, DiplomaticAction) -> Unit,
) {
    var open by remember(partner.countryId) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, DossierColors.paperLine)
            .clickable { open = !open }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                countryName(partner.countryId),
                style = DossierType.title(14.sp),
                color = DossierColors.ink,
                modifier = Modifier.weight(1f),
            )
            if (partner.atWar) Stamp("교전", color = DossierColors.negative)
            if (partner.allied) Stamp(partner.alliance ?: "동맹", color = DossierColors.blueInk)
            if (partner.tradeAgreement) Stamp("무역협정", color = DossierColors.positive)
            Text(
                "${partner.relation.roundToInt()} · ${relationLabel(partner.relation)}",
                style = DossierType.number(13.sp),
                color = relationColor(partner.relation),
            )
        }
        if (partner.sanctionedByUs > 0.0) StatRow("우리 제재", "강도 ${(partner.sanctionedByUs * 100).roundToInt()}")
        if (partner.sanctioningUs >
            0.0
        ) {
            StatRow("상대 제재", "강도 ${(partner.sanctioningUs * 100).roundToInt()}", valueColor = DossierColors.negative)
        }
        if (open) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DiplomaticAction.entries.forEach { action ->
                    val label = if (action.cost > 0.0) "${action.label} · ${action.cost.roundToInt()}" else action.label
                    PaperTag(
                        label,
                        enabled = action in partner.available && politicalCapital >= action.cost,
                        onClick = { onAction(partner.countryId, action) },
                    )
                }
            }
            DiplomaticAction.entries.filter { it in partner.available }.forEach { Hint("${it.label}: ${it.description}") }
        }
    }
}
