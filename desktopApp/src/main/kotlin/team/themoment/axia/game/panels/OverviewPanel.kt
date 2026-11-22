package team.themoment.axia.game.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.game.satisfactionColor
import team.themoment.axia.simulation.economy.EconomyIndicators
import team.themoment.axia.simulation.nation.NationSnapshot
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.Stamp
import kotlin.math.roundToInt

@Composable
fun OverviewPanel(
    countryName: String,
    nation: NationSnapshot,
    indicators: EconomyIndicators,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = countryName, style = DossierType.title(22.sp), color = DossierColors.ink)
        Spacer(Modifier.width(12.dp))
        Stamp(text = "지지율 ${nation.approval.roundToInt()}%", color = satisfactionColor(nation.approval))
    }
    SectionTitle("핵심 지표")
    KeyIndicatorRows(indicators)
    SectionTitle("지지율이 움직인 이유")
    FactorBars(nation.approvalFactors)
    Text(
        text = "기준선 ${nation.baseline.roundToInt()} · 여론조사 자료가 없어 시작 지지율이 50%가 되도록 정한 값",
        style = DossierType.number(11.sp),
        color = DossierColors.inkMuted,
    )
    SectionTitle("집단별 만족도")
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        nation.groups.forEach { SatisfactionRow(it.group.label, it.satisfaction) }
    }
}
