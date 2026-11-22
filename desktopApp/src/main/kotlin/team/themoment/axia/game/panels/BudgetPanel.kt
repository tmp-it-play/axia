package team.themoment.axia.game.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatCompactDollars
import team.themoment.axia.format.formatPercent
import team.themoment.axia.simulation.nation.BudgetItem
import team.themoment.axia.simulation.nation.BudgetSnapshot
import team.themoment.axia.simulation.politics.PoliticsSnapshot
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.DossierSlider
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow

@Composable
fun BudgetPanel(
    budget: BudgetSnapshot,
    politics: PoliticsSnapshot,
    onEdit: (BudgetItem, Double) -> Unit,
    onClear: () -> Unit,
    onIntroduce: () -> Unit,
    onViewBill: () -> Unit,
) {
    fun money(share: Double) = formatCompactDollars(share * budget.gdp)

    val pending = politics.pending
    val editable = pending == null

    SectionTitle("재정 현황 (연간, GDP 대비)")
    StatRow("세입", "${formatPercent(budget.revenue)} · ${money(budget.revenue)}")
    StatRow("기초지출", "${formatPercent(budget.primarySpending)} · ${money(budget.primarySpending)}")
    StatRow("이자", "${formatPercent(budget.interest)} · ${money(budget.interest)}")
    StatRow(
        "재정수지",
        "${formatPercent(budget.balance, signed = true)} · ${money(budget.balance)}",
        valueColor = if (budget.balance >= 0.0) DossierColors.positive else DossierColors.negative,
    )
    StatRow("국가부채", formatPercent(budget.debtRatio))
    SectionTitle("재정 변경안")
    if (pending != null) {
        StatRow("처리 상태", pending.stage.label)
        PaperTag("정치에서 심의 보기", onClick = onViewBill)
    } else {
        Text(
            text = "세율과 지출을 조정해 함께 발의하세요. 통과된 목표는 다음 달부터 집행됩니다.",
            style = DossierType.number(12.sp),
            color = DossierColors.inkMuted,
        )
        politics.draftSupport?.let { support ->
            val vote = support.rollCall
            if (vote == null) StatRow("예상 심의 점수", billScore(support.score)) else StatRow("예상 찬성", voteSummary(vote))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperTag("변경안 발의 · ${politics.draft.size}건", selected = true, enabled = politics.draft.isNotEmpty(), onClick = onIntroduce)
            if (politics.draft.isNotEmpty()) PaperTag("심의 근거", onClick = onViewBill)
            PaperTag("초안 초기화", enabled = politics.draft.isNotEmpty(), onClick = onClear)
        }
    }

    fun proposed(
        item: BudgetItem,
        enacted: Double,
    ): Double = pending?.changes?.firstOrNull { it.item == item }?.to ?: politics.draft[item]?.to ?: enacted

    SectionTitle("세입 · 실효세율")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        budget.taxes.forEach { line ->
            val proposed = proposed(line.kind, line.targetRate)
            DossierSlider(
                enabled = editable,
                label = line.kind.label,
                value = proposed.toFloat(),
                range = 0f..line.kind.maxTarget.toFloat(),
                valueText = formatPercent(proposed),
                secondary = "집행 ${formatPercent(line.rate)} · 승인 ${formatPercent(line.targetRate)}",
                onValueChange = { onEdit(line.kind, it.toDouble()) },
            )
            StatRow("연간 환산 세입", money(line.revenue))
        }
        StatRow("기타 세입 (자원, 공기업 등)", "${formatPercent(budget.otherRevenue)} · ${money(budget.otherRevenue)}")
    }

    SectionTitle("세출 · GDP 대비")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier) {
        budget.spending.forEach { line ->
            val proposed = proposed(line.kind, line.targetLevel)
            DossierSlider(
                enabled = editable,
                label = line.kind.label,
                value = proposed.toFloat(),
                range = 0f..line.kind.maxTarget.toFloat(),
                valueText = formatPercent(proposed),
                secondary = "집행 ${formatPercent(line.level)} · 승인 ${formatPercent(line.targetLevel)} · ${money(line.level)}",
                onValueChange = { onEdit(line.kind, it.toDouble()) },
            )
        }
    }
}
