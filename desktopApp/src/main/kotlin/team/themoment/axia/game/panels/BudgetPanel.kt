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
import team.themoment.axia.simulation.economy.DebtPhase
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
    if (budget.organizationSubsidies > 0.0) {
        StatRow("조직 보조금 (기초지출에 포함)", "${formatPercent(budget.organizationSubsidies)} · ${money(budget.organizationSubsidies)}")
    }
    DebtSection(budget)
    DraftControls(
        politics = politics,
        hint = "세율과 지출, 정책 탭의 규제를 함께 묶어 발의하세요. 통과된 목표는 다음 달부터 집행됩니다.",
        onIntroduce = onIntroduce,
        onViewBill = onViewBill,
        onClear = onClear,
    )

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

@Composable
private fun DebtSection(budget: BudgetSnapshot) {
    val debt = budget.debtWorkout
    SectionTitle("국채 상환과 차환")
    val status =
        when (debt.phase) {
            DebtPhase.NORMAL, DebtPhase.AWAITING_PLAN -> debt.phase.label
            DebtPhase.NEGOTIATING -> "${requireNotNull(debt.plan).label} 협상 · ${debt.monthsRemaining}개월 남음"
            DebtPhase.RECOVERY -> "시장 신뢰 회복 · ${debt.monthsRemaining}개월 남음"
        }
    StatRow("채무 상태", status)
    StatRow("세입 대비 이자", if (budget.revenue > 0.0) formatPercent(budget.interest / budget.revenue) else "세입 없음")
    StatRow("연간 조달 필요액 (GDP 대비)", formatPercent(debt.financingNeed))
    if (debt.stressMonths > 0) {
        Hint("자금 압박 ${debt.stressMonths}개월째. 적자와 상환 압박이 3개월 연속 이어지면 지급 유예에 들어갑니다.")
        val interestLimit = formatPercent(debt.interestThreshold)
        val fundingLimit = formatPercent(debt.fundingThreshold)
        Hint("세입 대비 이자 기준은 ${interestLimit}입니다. 재정적자가 GDP의 4% 이상이면 GDP 대비 조달 필요액 ${fundingLimit}도 기준이 됩니다.")
    }
    if (debt.marketSpread > 0.0) StatRow("신규 국채 가산금리", "${formatPercent(debt.marketSpread)}p")
    if (debt.exchangedDebt > 0.0) {
        StatRow("교환채 잔액 (GDP 대비)", formatPercent(debt.exchangedDebt))
        StatRow("교환채 고정 이율", formatPercent(debt.exchangeCoupon))
    }
    debt.lastWriteDown?.let { StatRow("최근 원금 감면 (합의 당시 GDP 대비)", formatPercent(it)) }
}

/** 재정·정책 탭이 함께 쓰는 법안 초안 조작부. 초안은 하나이고 두 탭의 변경을 함께 담는다. */
@Composable
internal fun DraftControls(
    politics: PoliticsSnapshot,
    hint: String,
    onIntroduce: () -> Unit,
    onViewBill: () -> Unit,
    onClear: () -> Unit,
) {
    val pending = politics.pending
    SectionTitle("변경안")
    if (pending != null) {
        StatRow("처리 상태", pending.stage.label)
        PaperTag("정치에서 심의 보기", onClick = onViewBill)
        return
    }
    Text(text = hint, style = DossierType.number(12.sp), color = DossierColors.inkMuted)
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
