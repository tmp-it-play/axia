package team.themoment.axia.game.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatCompactDollars
import team.themoment.axia.format.formatPercent
import team.themoment.axia.simulation.nation.BudgetSnapshot
import team.themoment.axia.simulation.nation.SpendingKind
import team.themoment.axia.simulation.nation.TaxKind
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.DossierSlider
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow

private const val MAX_SPENDING = 0.4f

@Composable
fun BudgetPanel(
    budget: BudgetSnapshot,
    onTaxRate: (TaxKind, Double) -> Unit,
    onSpending: (SpendingKind, Double) -> Unit,
) {
    fun money(share: Double) = formatCompactDollars(share * budget.gdp)

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
    Text(
        text = "목표를 바꾸면 다음 달부터 6개월에 걸쳐 실제 세율과 지출이 따라갑니다.",
        style = DossierType.number(11.sp),
        color = DossierColors.inkMuted,
    )

    SectionTitle("세입 · 실효세율")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        budget.taxes.forEach { line ->
            DossierSlider(
                label = line.kind.label,
                value = line.targetRate.toFloat(),
                range = 0f..line.kind.maxRate.toFloat(),
                valueText = formatPercent(line.targetRate),
                secondary = "현재 ${formatPercent(line.rate)} · 세입 ${money(line.revenue)}",
                onValueChange = { onTaxRate(line.kind, it.toDouble()) },
            )
        }
        StatRow("기타 세입 (자원, 공기업 등)", "${formatPercent(budget.otherRevenue)} · ${money(budget.otherRevenue)}")
    }

    SectionTitle("세출 · GDP 대비")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier) {
        budget.spending.forEach { line ->
            DossierSlider(
                label = line.kind.label,
                value = line.targetLevel.toFloat(),
                range = 0f..MAX_SPENDING,
                valueText = formatPercent(line.targetLevel),
                secondary = "현재 ${formatPercent(line.level)} · ${money(line.level)}",
                onValueChange = { onSpending(line.kind, it.toDouble()) },
            )
        }
    }
}
