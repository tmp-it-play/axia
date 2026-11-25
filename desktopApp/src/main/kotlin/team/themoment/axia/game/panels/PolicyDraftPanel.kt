package team.themoment.axia.game.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatRate
import team.themoment.axia.game.PolicyPage
import team.themoment.axia.simulation.economy.MonetaryFramework
import team.themoment.axia.simulation.economy.MonetaryPolicy
import team.themoment.axia.simulation.nation.BudgetItem
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.nation.InstitutionItem
import team.themoment.axia.simulation.nation.PolicyItem
import team.themoment.axia.simulation.nation.RegulationItem
import team.themoment.axia.simulation.nation.SpendingKind
import team.themoment.axia.simulation.nation.TaxKind
import team.themoment.axia.simulation.politics.PolicyChange
import team.themoment.axia.simulation.politics.PoliticsSnapshot
import team.themoment.axia.simulation.society.Identity
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.DossierSlider
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow
import team.themoment.axia.ui.dossier.metricTooltip
import kotlin.math.roundToInt

@Composable
internal fun PolicyEditor(
    option: DraftOption,
    monetary: MonetaryPolicy,
    politics: PoliticsSnapshot,
    onEdit: (PolicyItem, Double) -> Unit,
) {
    val item = option.item
    val pending = politics.pending
    val change = politics.draft[item]
    val proposal = pending?.changes?.firstOrNull { it.item == item } ?: change
    val value = proposal?.to ?: option.target
    LedgerHeading(item.label)
    SectionTitle("현재 시행안")
    val target = item.printLevel(option.target)
    val inForce = option.inForce?.let(item::printLevel)
    val initial = option.initial?.let(item::printLevel)
    StatRow(if (inForce == target) "시행 수준" else "승인 목표", target)
    if (inForce != null && inForce != target) StatRow("집행 중", inForce)
    if (initial != null && initial != target && initial != inForce) StatRow("시작 기준", initial)
    when (item) {
        RegulationItem.ENVIRONMENT -> Hint("규제를 강화하면 오염이 줄고 잠재성장률이 낮아집니다.")
        RegulationItem.LABOR -> Hint("보호를 강화하면 노동자 소득과 자연실업률이 높아집니다.")
        RegulationItem.IMMIGRATION -> Hint("이민을 더 받아들이면 인구 증가율이 높아집니다.")
        RegulationItem.POLICING -> Hint("치안·감시를 강화하면 범죄 지수가 낮아지고 보안기관의 지지가 높아집니다.")
        RegulationItem.CIVIL_LIBERTIES -> Hint("자유를 확대하면 시민 자유를 중시하는 집단의 지지가 높아집니다.")
        is CountryPolicyItem -> Hint(item.description)
        else -> Unit
    }
    if (item is InstitutionItem && !monetary.framework.adjustableIndependence) {
        val reason =
            if (monetary.framework == MonetaryFramework.MONETARY_UNION) {
                "${monetary.unionBank ?: "공동 중앙은행"}에서 회원국의 금리를 결정합니다."
            } else {
                "고정환율을 유지하는 동안 금리는 기준 통화의 금리를 따릅니다."
            }
        Hint(reason)
        return
    }
    if (pending != null) {
        SectionTitle("법안 심의 중")
        if (proposal != null) StatRow("심의안", item.printLevel(proposal.to))
        Hint("현재 법안의 심의가 끝나면 새 변경안을 작성할 수 있습니다.")
        return
    }
    SectionTitle("입안 목표")
    val ends =
        when (item) {
            is RegulationItem -> item.lowLabel to item.highLabel
            is CountryPolicyItem -> item.lowLabel to item.highLabel
            is InstitutionItem -> "정부 지시" to "독립"
            is BudgetItem -> null
        }
    if (item is InstitutionItem || (item is CountryPolicyItem && item.binary)) {
        val (low, high) = requireNotNull(ends)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PaperTag(low, selected = value < 0.5, onClick = { onEdit(item, 0.0) })
            PaperTag(high, selected = value >= 0.5, onClick = { onEdit(item, 1.0) })
        }
    } else {
        DossierSlider(
            label =
                when (item) {
                    is TaxKind -> "실효세율"
                    is SpendingKind -> "GDP 대비 지출"
                    else -> "정책 강도"
                },
            value = value.toFloat(),
            range = 0f..item.maxTarget.toFloat(),
            valueText = item.printLevel(value),
            tooltip = item.levelDescription,
            onValueChange = { next -> onEdit(item, if (item is BudgetItem) next.toDouble() else (next * 100).roundToInt() / 100.0) },
        )
        ends?.let { (low, high) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Hint(low)
                Hint(high)
            }
        }
    }
    if (change != null) {
        StatRow("초안에 담긴 변경", "${item.printLevel(change.from)} → ${item.printLevel(change.to)}")
        PaperTag("이 변경 취소", onClick = { onEdit(item, option.target) })
    }
    val decreasing = value < option.target || (change == null && option.target >= item.maxTarget)
    val direction = if (decreasing) -1.0 else 1.0
    val reactions =
        Identity.entries
            .map {
                it to it.reaction(
                    item,
                ) * direction
            }.filter { it.second != 0.0 }
            .sortedByDescending { it.second }
    if (reactions.isNotEmpty()) {
        SectionTitle(
            when {
                change != null -> "개정안에 대한 성향별 반응"
                decreasing -> "수준을 낮출 때의 성향별 반응"
                else -> "수준을 높일 때의 성향별 반응"
            },
        )
        val favored = reactions.filter { it.second > 0 }.joinToString(" · ") { it.first.label }
        val opposed = reactions.filter { it.second < 0 }.joinToString(" · ") { it.first.label }
        if (favored.isNotEmpty()) Text("선호   $favored", style = DossierType.number(14.sp), color = DossierColors.positive)
        if (opposed.isNotEmpty()) Text("반대   $opposed", style = DossierType.number(14.sp), color = DossierColors.negative)
    }
}

@Composable
internal fun BillFolio(
    politics: PoliticsSnapshot,
    onEdit: (PolicyItem?) -> Unit,
    onReview: () -> Unit,
    onHearing: () -> Unit,
    page: PolicyPage,
    onClear: () -> Unit,
    onIntroduce: () -> Unit,
) {
    val pending = politics.pending
    val changes = pending?.changes ?: politics.draft.values
    val heading =
        when {
            pending?.sponsor != null -> "심의 중인 야당 법안"
            pending != null -> "심의 중인 정부 법안"
            else -> "정부 법안 초안"
        }
    LedgerHeading(heading)
    if (pending != null) {
        StatRow("처리 상태", pending.stage.label)
        StatRow("다음 심의일", shortDate(pending.decisionDate))
    }
    if (changes.isEmpty()) {
        Text("아직 작성한 조항이 없습니다.", style = DossierType.number(15.sp), color = DossierColors.inkMuted)
    } else {
        ClauseTable(changes, if (pending == null) onEdit else null)
    }
    Spacer(Modifier.height(12.dp))
    if (pending != null) {
        LedgerCommand("심의 보기", onClick = onHearing)
    } else if (page == PolicyPage.REVIEW) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PaperTag("조항 추가", onClick = { onEdit(null) })
            if (changes.isNotEmpty()) PaperTag("초안 비우기", onClick = onClear)
        }
        if (changes.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            LedgerCommand("법안 발의", enabled = !politics.governmentBillsSuspended, onClick = onIntroduce)
        }
    } else if (changes.isNotEmpty()) {
        LedgerCommand("초안 검토 · ${changes.size}건", onClick = onReview)
    } else if (page == PolicyPage.CURRENT) {
        LedgerCommand("정책 입안하기", onClick = { onEdit(null) })
    }
    if (pending == null && politics.governmentBillsSuspended) {
        Hint("정부 법안 발의가 중단된 동안 변경안은 초안으로 보관됩니다.")
    }
}

@Composable
internal fun ClauseTable(
    changes: Collection<PolicyChange>,
    onEdit: ((PolicyItem) -> Unit)?,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("조항", Modifier.weight(1.4f), style = DossierType.title(12.sp), color = DossierColors.inkMuted)
        Text("변경 전", Modifier.weight(1f), style = DossierType.title(12.sp), color = DossierColors.inkMuted, textAlign = TextAlign.End)
        Text("개정안", Modifier.weight(1f), style = DossierType.title(12.sp), color = DossierColors.blueInk, textAlign = TextAlign.End)
    }
    changes.forEach { change ->
        val before = change.item.printLevel(change.from)
        val after = change.item.printLevel(change.to)
        val action =
            if (onEdit !=
                null
            ) {
                Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button) { onEdit(change.item) }
            } else {
                Modifier
            }
        Row(
            Modifier
                .fillMaxWidth()
                .metricTooltip(change.item.label, change.item.levelDescription)
                .then(action)
                .heightIn(min = 35.dp)
                .padding(horizontal = 3.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(change.item.label, Modifier.weight(1.4f), style = DossierType.title(14.sp), color = DossierColors.ink)
            Text(
                before,
                Modifier.weight(1f),
                style = DossierType.number(13.sp),
                color = DossierColors.ink,
                textAlign = TextAlign.End,
            )
            Text(
                after,
                Modifier.weight(1f),
                style = DossierType.title(13.sp),
                color = DossierColors.blueInk,
                textAlign = TextAlign.End,
            )
        }
        HorizontalDivider(color = DossierColors.folderEdge.copy(alpha = 0.35f))
    }
}

@Composable
private fun LedgerCommand(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (enabled) DossierColors.blueInk else DossierColors.paperLine)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 38.dp)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = DossierType.title(15.sp), color = if (enabled) DossierColors.paper else DossierColors.inkMuted)
    }
}

@Composable
internal fun VoteForecast(
    politics: PoliticsSnapshot,
    details: Boolean,
    onHearing: () -> Unit,
) {
    val pending = politics.pending
    val support = pending?.support ?: politics.draftSupport
    val legislature = politics.legislature
    val vote = support?.rollCall
    val required = vote?.required ?: legislature?.required
    val total = if (vote != null) vote.yes + vote.no else legislature?.votingSeats
    SectionTitle("심의 전망")
    Text(politics.reviewBody, style = DossierType.title(21.sp), color = DossierColors.ink)
    if (vote != null) {
        VoteScale(
            vote.yes.toDouble(),
            vote.required.toDouble(),
            (vote.yes + vote.no).toDouble(),
            Modifier.metricTooltip("표결 전망", "예상 찬성 비중을 나타내는 막대입니다. 초록색 막대가 세로선에 닿으면 가결 전망입니다."),
        )
        StatRow("예상 찬성", "${vote.yes}표", valueColor = DossierColors.positive)
        StatRow("예상 반대", "${vote.no}표", valueColor = DossierColors.negative)
    } else if (support != null) {
        VoteScale(
            support.score,
            50.0,
            100.0,
            Modifier.metricTooltip("기관 심의 전망", "예상 심의 점수를 나타내는 막대입니다. 초록색 막대가 세로선에 닿으면 통과 전망입니다."),
        )
        StatRow("예상 심의 점수", billScore(support.score))
    }
    if ((support != null && vote == null) || required == null) {
        StatRow("통과 기준", "심의 점수 50점")
    } else {
        StatRow("가결선", "${required}표", tooltip = "이 법안의 표결을 통과하는 데 필요한 최소 찬성표입니다.")
        if (vote == null && total != null) StatRow("표결권 있는 의석", "${total}석")
    }
    if (support != null) {
        StatRow(
            "재정적자 증감 (GDP 대비)",
            formatRate(support.addedDeficit, signed = true),
            tooltip = "이 법안의 지출 증가분에서 세입 증가분을 뺀 값입니다. 양수이면 적자가 커집니다.",
        )
        if (details) {
            if (vote != null) {
                SectionTitle("정당별 표결 전망")
                vote.rows.forEach { row -> StatRow(row.party.name, "찬성 ${row.yes} · 반대 ${row.no}") }
            } else {
                SectionTitle("집단별 법안 지지")
                support.groupScores.forEach { (group, score) -> SatisfactionRow(group.label, score, billSupport = true) }
            }
            if (support.checks.isNotEmpty()) {
                SectionTitle("후속 심사")
                support.checks.forEach { check -> StatRow(check.body, if (check.passes) "통과 전망" else "반대 전망") }
            }
            if (pending == null) PaperTag("심의 상세·협의", onClick = onHearing)
        }
    }
}

@Composable
private fun VoteScale(
    yes: Double,
    required: Double,
    total: Double,
    modifier: Modifier,
) {
    Canvas(modifier.fillMaxWidth().height(24.dp)) {
        if (total <= 0.0) return@Canvas
        val track = 8.dp.toPx()
        val y = (size.height - track) / 2
        val fill = size.width * (yes / total).coerceIn(0.0, 1.0).toFloat()
        drawRect(DossierColors.paperLine, Offset(0f, y), Size(size.width, track))
        drawRect(DossierColors.positive, Offset(0f, y), Size(fill, track))
        val mark = size.width * (required / total).coerceIn(0.0, 1.0).toFloat()
        drawLine(DossierColors.ink, Offset(mark, y - 3.dp.toPx()), Offset(mark, y + track + 3.dp.toPx()), 2.dp.toPx())
    }
}
