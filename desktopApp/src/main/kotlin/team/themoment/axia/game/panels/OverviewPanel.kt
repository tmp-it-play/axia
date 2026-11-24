package team.themoment.axia.game.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.simulation.economy.EconomyIndicators
import team.themoment.axia.simulation.nation.NationSnapshot
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow
import kotlin.math.roundToInt

@Composable
fun OverviewPanel(
    countryName: String,
    nation: NationSnapshot,
    indicators: EconomyIndicators,
) {
    Text(text = countryName, style = DossierType.title(22.sp), color = DossierColors.ink)
    SectionTitle("핵심 지표")
    StatRow("정부 지지율", "${nation.approval.roundToInt()}%")
    KeyIndicatorRows(indicators)
    if (nation.special.indicators.isNotEmpty()) {
        SectionTitle("국가 특수 상황")
        nation.special.indicators.forEach { indicator ->
            StatRow(indicator.label, indicator.value)
            Hint(indicator.description)
        }
    }
    SectionTitle("지지율이 움직인 이유")
    ApprovalBreakdown(nation)
    Text(
        text =
            "기준선 ${nation.baseline.roundToInt()} · 시작 지지율 ${nation.initialApproval.approval.roundToInt()}%" +
                (if (nation.initialApproval.estimated) " (여론조사 없음, 추정)" else " (${nation.initialApproval.source})"),
        style = DossierType.number(11.sp),
        color = DossierColors.inkMuted,
    )
    SectionTitle("집단별 만족도")
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        nation.groups.forEach { SatisfactionRow(it.group.label, it.satisfaction) }
    }
}
