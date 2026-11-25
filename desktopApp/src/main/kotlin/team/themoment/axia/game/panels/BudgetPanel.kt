package team.themoment.axia.game.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatCompactDollars
import team.themoment.axia.format.formatPercent
import team.themoment.axia.game.BudgetPage
import team.themoment.axia.simulation.economy.Creditor
import team.themoment.axia.simulation.economy.DebtPhase
import team.themoment.axia.simulation.economy.DebtPlan
import team.themoment.axia.simulation.economy.DebtWorkoutSnapshot
import team.themoment.axia.simulation.nation.BudgetItem
import team.themoment.axia.simulation.nation.BudgetSnapshot
import team.themoment.axia.simulation.politics.PoliticsSnapshot
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.DossierLinkRow
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow

@Composable
internal fun BudgetPanel(
    budget: BudgetSnapshot,
    politics: PoliticsSnapshot,
    page: BudgetPage,
    onEdit: (BudgetItem?) -> Unit,
    onReview: () -> Unit,
    onViewBill: () -> Unit,
) {
    val pending = politics.pending
    Column(Modifier.fillMaxSize()) {
        if (page != BudgetPage.DEBT) {
            if (pending != null) {
                DossierLinkRow("심의 중인 법안", pending.stage.label, onViewBill)
            } else if (politics.draft.isNotEmpty()) {
                DossierLinkRow("초안 검토", "변경 ${politics.draft.size}건", onReview)
            }
        }
        when (page) {
            BudgetPage.ACCOUNTS -> {
                PanelColumns(
                    left = {
                        LedgerHeading("세입")
                        BudgetAmount(budget.revenue, budget.gdp)
                        SectionTitle("세목별 실효세율 · 연간 세입")
                        budget.taxes.forEach { line ->
                            val detail = "${formatPercent(line.rate)} · ${formatCompactDollars(line.revenue * budget.gdp)}"
                            DossierLinkRow(line.kind.label, detail) { if (pending == null) onEdit(line.kind) else onViewBill() }
                        }
                        StatRow("기타 세입", formatCompactDollars(budget.otherRevenue * budget.gdp))
                        val transfers = budget.revenue - budget.otherRevenue - budget.taxes.sumOf { it.revenue }
                        if (transfers * budget.gdp >= 1.0) StatRow("이전 수입", formatCompactDollars(transfers * budget.gdp))
                    },
                    right = {
                        LedgerHeading("세출")
                        BudgetAmount(budget.primarySpending + budget.interest, budget.gdp)
                        SectionTitle("분야별 GDP 비중 · 연간 지출")
                        budget.spending.forEach { line ->
                            val detail = "${formatPercent(line.level)} · ${formatCompactDollars(line.level * budget.gdp)}"
                            DossierLinkRow(line.kind.label, detail) { if (pending == null) onEdit(line.kind) else onViewBill() }
                        }
                        if (kotlin.math.abs(budget.otherSpending * budget.gdp) >= 1.0) {
                            StatRow("기타 지출·경기 조정", formatCompactDollars(budget.otherSpending * budget.gdp))
                        }
                        StatRow("순이자 지출", formatCompactDollars(budget.interest * budget.gdp))
                    },
                )
            }

            BudgetPage.BALANCE -> {
                PanelColumns(left = { BalanceSheet(budget) }, right = { SpendingComposition(budget) })
            }

            BudgetPage.DEBT -> {
                PanelColumns(
                    left = { DebtSection(budget) },
                    right = { CreditorBalances(budget.debtWorkout) },
                )
            }
        }
    }
}

@Composable
private fun BudgetAmount(
    share: Double,
    gdp: Double,
) {
    Text(formatCompactDollars(share * gdp), style = DossierType.number(27.sp), color = DossierColors.blueInk)
    Hint("연간 GDP의 ${formatPercent(share)}")
}

private val BudgetSnapshot.otherSpending: Double get() = primarySpending - spending.sumOf { it.level }

@Composable
private fun SpendingComposition(budget: BudgetSnapshot) {
    LedgerHeading("분야별 지출")
    val lines =
        buildList {
            budget.spending.forEach { add(it.kind.label to it.level) }
            if (kotlin.math.abs(budget.otherSpending * budget.gdp) >= 1.0) add("기타 지출·경기 조정" to budget.otherSpending)
            add("순이자 지출" to budget.interest)
        }
    val low = minOf(0.0, lines.minOf { it.second })
    val range = maxOf(0.0, lines.maxOf { it.second }) - low
    lines.sortedByDescending { it.second }.forEach { (label, share) ->
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            StatRow(label, "GDP의 ${formatPercent(share)}")
            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                drawRect(DossierColors.paperShade)
                if (range > 0.0) {
                    val zero = (-low / range).toFloat() * size.width
                    val end = ((share - low) / range).toFloat() * size.width
                    drawRect(
                        color = DossierColors.blueInk,
                        topLeft = Offset(minOf(zero, end), 0f),
                        size = Size(kotlin.math.abs(end - zero), size.height),
                    )
                    if (low < 0.0) drawLine(DossierColors.ink, Offset(zero, 0f), Offset(zero, size.height))
                }
            }
        }
    }
}

@Composable
private fun BalanceSheet(budget: BudgetSnapshot) {
    fun money(share: Double) = formatCompactDollars(share * budget.gdp)

    LedgerHeading("연간 재정수지")
    val balanceInk = if (budget.balance >= 0.0) DossierColors.positive else DossierColors.negative
    Text(money(budget.balance), style = DossierType.number(27.sp), color = balanceInk)
    Hint("GDP 대비 ${formatPercent(budget.balance, signed = true)}")
    SectionTitle("세입과 지출")
    StatRow("세입", "${formatPercent(budget.revenue)} · ${money(budget.revenue)}")
    StatRow("기초지출", "${formatPercent(budget.primarySpending)} · ${money(budget.primarySpending)}")
    StatRow("순이자 지출", "${formatPercent(budget.interest)} · ${money(budget.interest)}")
    StatRow("국가부채", formatPercent(budget.debtRatio))
    if (budget.reconstructionSpending > 0.0) {
        StatRow("전후 재건 집행액", "${formatPercent(budget.reconstructionSpending)} · ${money(budget.reconstructionSpending)}")
    }
    if (budget.regionalSpending > 0.0) {
        StatRow("지역 사업 예산", "${formatPercent(budget.regionalSpending)} · ${money(budget.regionalSpending)}")
    }
    if (budget.organizationSubsidies > 0.0) {
        StatRow("조직 보조금 (기초지출에 포함)", "${formatPercent(budget.organizationSubsidies)} · ${money(budget.organizationSubsidies)}")
    }
    if (budget.countrySpendingChange != 0.0) {
        val share = String.format(java.util.Locale.KOREA, "%+.3f%%", budget.countrySpendingChange * 100.0)
        StatRow("국가별 정책 지출 증감 (경기 보정 전)", "$share · ${money(budget.countrySpendingChange)}")
    }
    budget.grainProducerPayment?.let { payment ->
        val share = String.format(java.util.Locale.KOREA, "%.4f%%", payment * 100.0)
        StatRow("곡물 농가 보조금 (실제 집행 · 연율)", "$share · ${money(payment)}")
    }
}

@Composable
private fun DebtSection(budget: BudgetSnapshot) {
    val debt = budget.debtWorkout
    LedgerHeading("국채 상환과 차환")
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

@Composable
private fun CreditorBalances(debt: DebtWorkoutSnapshot) {
    LedgerHeading("채권자별 잔액")
    debt.creditors.forEach { line ->
        StatRow(line.creditor.label + " (GDP 대비)", formatPercent(line.outstanding))
        if (debt.phase == DebtPhase.NEGOTIATING) {
            line.proposal?.let { terms ->
                val label = if (line.countered) "수정 요구" else "제안"
                Hint("$label · ${terms.label} · 감면 ${formatPercent(line.creditor.haircut(terms))} · 만기 ${line.creditor.months(terms)}개월")
            }
        }
        if (line.exchanged > 0.0) {
            Hint("교환채 GDP의 ${formatPercent(line.exchanged)} · 고정 이율 ${formatPercent(line.coupon)} · 잔여 ${line.monthsToMaturity}개월")
        }
        line.lastWriteDown?.let { Hint("최근 확정 감면 · 합의 당시 GDP의 ${formatPercent(it)}") }
    }
}

@Composable
internal fun CreditorTermsForm(
    debt: DebtWorkoutSnapshot,
    onChange: (Creditor, DebtPlan?) -> Unit,
) {
    Hint("채권자별 조건을 선택한 뒤 아래에서 기본안을 제출하세요. 기본안을 따르는 채권자는 제출한 조건을 적용합니다.")
    debt.creditors.forEach { line ->
        StatRow(line.creditor.label, "대상 원금 GDP의 ${formatPercent(line.eligible)}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PaperTag("기본안", selected = line.proposal == null, onClick = { onChange(line.creditor, null) })
            DebtPlan.entries.forEach { terms ->
                val cut = line.creditor.haircut(terms)
                val label = if (cut > 0.0) "${formatPercent(cut)} 감면" else "원금 보전"
                PaperTag(
                    "$label · ${line.creditor.months(terms)}개월",
                    selected = line.proposal == terms,
                    onClick = { onChange(line.creditor, terms) },
                )
            }
        }
    }
}
