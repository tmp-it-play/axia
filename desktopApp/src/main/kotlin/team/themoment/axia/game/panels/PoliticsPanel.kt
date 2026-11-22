package team.themoment.axia.game.panels

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatPercent
import team.themoment.axia.game.formatPoints
import team.themoment.axia.simulation.politics.BillStage
import team.themoment.axia.simulation.politics.BillSupport
import team.themoment.axia.simulation.politics.BudgetChange
import team.themoment.axia.simulation.politics.FiscalBill
import team.themoment.axia.simulation.politics.Legislature
import team.themoment.axia.simulation.politics.PartyRole
import team.themoment.axia.simulation.politics.PoliticsSnapshot
import team.themoment.axia.simulation.politics.RollCall
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow
import java.util.Locale

internal fun billScore(value: Double): String = String.format(Locale.KOREA, "%.1f / 100", value)

internal fun voteSummary(vote: RollCall): String = "${vote.yes}표 · 가결선 ${vote.required}표"

@Composable
fun PoliticsPanel(
    politics: PoliticsSnapshot,
    onBudget: () -> Unit,
    onRevise: () -> Unit,
    onNegotiate: (String?) -> Unit,
    onIntroduce: () -> Unit,
) {
    val profile = politics.profile
    SectionTitle("시작 정부")
    Text(profile.initialGovernment, style = DossierType.title(16.sp), color = DossierColors.ink)
    StatRow("재정 심의 기구", profile.reviewBody)
    profile.legislature?.let { ChamberComposition(it) }

    val pending = politics.pending
    if (pending == null) {
        SectionTitle(if (politics.draft.isEmpty()) "재정안" else "발의 전 초안")
        PaperTag(if (politics.draft.isEmpty()) "재정안 작성" else "작성 중인 재정안 열기", onClick = onBudget)
        politics.draftSupport?.let {
            BillAssessment(politics.draft.values, it, decided = false)
            it.rollCall?.let { vote -> Negotiation(vote, onNegotiate) }
            PaperTag("이 변경안 발의", selected = true, onClick = onIntroduce)
        }
    } else {
        SectionTitle("심의 중인 재정안")
        if (pending.stage == BillStage.STALLED) PaperTag("철회하고 수정", onClick = onRevise)
        BillDetails(pending)
    }
    politics.lastDecision?.let {
        SectionTitle("최근 결정")
        BillDetails(it)
    }

    SectionTitle("정치 제도 · V-Dem 2023")
    StatRow("체제 분류", profile.regime.label)
    StatRow("입법부의 행정부 견제", billScore(profile.legislativeConstraints * 100))
    StatRow("시민사회 참여", billScore(profile.civilParticipation * 100))
}

@Composable
private fun ChamberComposition(chamber: Legislature) {
    var expanded by remember(chamber) { mutableStateOf(false) }
    SectionTitle("${chamber.name} · ${chamber.filledSeats}석")
    val cabinet = chamber.groups.filter { it.role == PartyRole.LEAD || it.role == PartyRole.COALITION }
    if (cabinet.isNotEmpty()) StatRow("정부·연정 의석", "${cabinet.sumOf { it.seats }}석")
    val allies = chamber.groups.filter { it.role == PartyRole.SUPPLY }
    if (allies.isNotEmpty()) StatRow("정부 협력 의석", "${allies.sumOf { it.seats }}석")
    StatRow("표결권 · 가결선", "${chamber.votingSeats}표 · ${chamber.required}표")
    PaperTag(if (expanded) "정당별 의석 접기" else "정당별 의석 보기", onClick = { expanded = !expanded })
    if (expanded) {
        chamber.groups.forEach { party ->
            val excluded = if (party.nonVoting > 0) " · 불참 ${party.nonVoting}" else ""
            StatRow(party.name, "${party.role.label} · ${party.seats}석$excluded")
        }
    }
}

@Composable
private fun Negotiation(
    vote: RollCall,
    onSelect: (String?) -> Unit,
) {
    val eligible = vote.rows.filter { it.party.canNegotiate }
    if (eligible.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    SectionTitle("법안 협의")
    StatRow("협의 상대", vote.negotiatingWith ?: "없음")
    vote.rows.firstOrNull { it.party.name == vote.negotiatingWith }?.let {
        StatRow("협의 예상 효과", "찬성 +${it.gainedByNegotiation}표 · 심의 +14일")
    }
    PaperTag(if (expanded) "목록 접기" else "협의 상대 선택", onClick = { expanded = !expanded })
    if (expanded) {
        Text(
            "한 정당과 협의하면 첫 표결이 14일 늦어집니다. 항목을 선택하면 예상 표수가 바뀝니다.",
            style = DossierType.number(12.sp),
            color = DossierColors.inkMuted,
        )
        PaperTag("협의 없이 발의 준비", selected = vote.negotiatingWith == null, onClick = { onSelect(null) })
        eligible.forEach { (party, _) ->
            PaperTag("${party.name} · ${party.seats}석", selected = party.name == vote.negotiatingWith, onClick = { onSelect(party.name) })
        }
    }
}

@Composable
private fun BillDetails(bill: FiscalBill) {
    val open = bill.stage == BillStage.REVIEW || bill.stage == BillStage.STALLED
    StatRow("처리 상태", bill.stage.label)
    StatRow("발의일", bill.introduced.toString())
    StatRow(if (open) "다음 심의일" else "결정일", bill.decisionDate.toString())
    bill.support.rollCall
        ?.negotiatingWith
        ?.let { StatRow("법안 협의", it) }
    bill.firstReview?.let {
        StatRow("첫 심의 · 정체", it.rollCall?.let(::voteSummary) ?: billScore(it.score))
    }
    if (open) {
        val vote = bill.support.rollCall
        val rule =
            when {
                bill.stage == BillStage.STALLED -> "4주 뒤 재심의에서도 가결선에 미달하면 좌초됩니다."
                vote != null -> "가결선까지 부족한 표가 전체 표결권의 10% 이내이면 4주 뒤 재심의합니다."
                else -> "첫 심의에서 40점 이상 50점 미만이면 4주 뒤 재심의합니다."
            }
        Text(rule, style = DossierType.number(12.sp), color = DossierColors.inkMuted)
    }
    BillAssessment(bill.changes, bill.support, decided = !open)
}

@Composable
private fun BillAssessment(
    changes: Collection<BudgetChange>,
    support: BillSupport,
    decided: Boolean,
) {
    changes.forEach { StatRow(it.item.label, "${formatPercent(it.from)} → ${formatPercent(it.to)}") }
    val vote = support.rollCall
    if (vote != null) {
        SectionTitle(if (decided) "표결 결과" else "예상 표결")
        StatRow("찬성", voteSummary(vote))
        StatRow("반대", "${vote.no}표")
        if (!decided && !vote.passes) StatRow("가결까지 부족한 표", "${vote.required - vote.yes}표")
        var expanded by remember { mutableStateOf(false) }
        PaperTag(if (expanded) "정당별 표결 접기" else "정당별 표결 보기", onClick = { expanded = !expanded })
        if (expanded) {
            vote.rows.forEach { row ->
                StatRow(row.party.name, "찬성 ${row.yes} · 반대 ${row.no}")
                val base =
                    row.party.constituency
                        ?.let { " · 모형 지지 기반 ${it.label}" }
                        .orEmpty()
                Text(row.party.role.label + base, style = DossierType.number(11.sp), color = DossierColors.inkMuted)
            }
        }
    }
    SectionTitle(if (vote == null) "기관 심의 점수" else "법안 지지 기반")
    StatRow("집단별 지지의 가중 평균", billScore(support.constituency))
    StatRow("행정부 추진력", formatPoints(support.executiveBacking))
    StatRow("추가 적자 부담", formatPoints(0.0 - support.fundingPenalty))
    if (vote == null) StatRow("합계 · 50점 이상 통과", billScore(support.score))
    var groupDetails by remember { mutableStateOf(false) }
    PaperTag(if (groupDetails) "집단별 지지 접기" else "집단별 지지 보기", onClick = { groupDetails = !groupDetails })
    if (groupDetails) {
        support.groupScores.forEach { (group, score) -> SatisfactionRow(group.label, score) }
    }
}
