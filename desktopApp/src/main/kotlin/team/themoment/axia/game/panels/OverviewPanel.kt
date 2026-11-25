package team.themoment.axia.game.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.simulation.economy.EconomyIndicators
import team.themoment.axia.simulation.nation.NationSnapshot
import team.themoment.axia.simulation.politics.PoliticsSnapshot
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.DossierLinkRow
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
internal fun OverviewPanel(
    nation: NationSnapshot,
    indicators: EconomyIndicators,
    politics: PoliticsSnapshot,
    today: LocalDate,
    onElections: () -> Unit,
    onViewBill: () -> Unit,
) {
    PanelColumns(
        leftWeight = 1.12f,
        left = {
            LedgerHeading("정부")
            Text(politics.government, style = DossierType.title(18.sp), color = DossierColors.ink)
            DossierLinkRow("선거 일정", nextElectionTag(politics.schedule, today) ?: "예정된 선거 없음", onElections)
            val pending = politics.pending
            DossierLinkRow(
                "법안 심의",
                when {
                    pending != null -> "${pending.stage.label} · ${shortDate(pending.decisionDate)}"
                    politics.draft.isNotEmpty() -> "초안 ${politics.draft.size}개 조항"
                    else -> "심의 중인 법안 없음"
                },
                onViewBill,
            )
            SectionTitle("국가 지표")
            KeyIndicatorRows(indicators)
            if (nation.special.indicators.isNotEmpty()) {
                SectionTitle("국가별 현황")
                nation.special.indicators.forEach {
                    StatRow(it.label, it.value)
                    Hint(it.description)
                }
            }
        },
        right = {
            LedgerHeading("정부 지지율")
            Text("${nation.approval.roundToInt()}%", style = DossierType.title(28.sp), color = DossierColors.blueInk)
            StatRow("시작 지지율 · 기준선", "${nation.initialApproval.approval.roundToInt()}% · ${nation.baseline.roundToInt()}")
            SectionTitle("지지율 변동 요인")
            ApprovalBreakdown(nation)
            SectionTitle("집단별 만족도")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                nation.groups.forEach { SatisfactionRow(it.group.label, it.satisfaction) }
            }
        },
    )
}
