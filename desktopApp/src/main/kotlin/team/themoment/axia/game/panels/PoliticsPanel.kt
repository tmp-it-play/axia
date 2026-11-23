package team.themoment.axia.game.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatPercent
import team.themoment.axia.game.formatPoints
import team.themoment.axia.simulation.country.BillCheck
import team.themoment.axia.simulation.nation.BudgetItem
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.nation.InstitutionItem
import team.themoment.axia.simulation.nation.RegulationItem
import team.themoment.axia.simulation.politics.BillStage
import team.themoment.axia.simulation.politics.BillSupport
import team.themoment.axia.simulation.politics.ConstitutionSnapshot
import team.themoment.axia.simulation.politics.ElectionResult
import team.themoment.axia.simulation.politics.ElectionSchedule
import team.themoment.axia.simulation.politics.FiscalBill
import team.themoment.axia.simulation.politics.Legislature
import team.themoment.axia.simulation.politics.PartyRole
import team.themoment.axia.simulation.politics.PolicyChange
import team.themoment.axia.simulation.politics.PoliticsSnapshot
import team.themoment.axia.simulation.politics.RollCall
import team.themoment.axia.simulation.politics.SeatProjection
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.Stamp
import team.themoment.axia.ui.dossier.StatRow
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

internal fun billScore(value: Double): String = String.format(Locale.KOREA, "%.1f / 100", value)

internal fun voteSummary(vote: RollCall): String = "${vote.yes}표 · 가결선 ${vote.required}표"

@Composable
fun PoliticsPanel(
    politics: PoliticsSnapshot,
    today: LocalDate,
    onBudget: () -> Unit,
    onRevise: () -> Unit,
    onNegotiate: (String?) -> Unit,
    onIntroduce: () -> Unit,
    onCallEarlyElection: () -> Unit,
    onRenamePerson: (Int, String) -> Unit,
) {
    val profile = politics.profile
    SectionTitle("현 정부")
    Text(politics.government, style = DossierType.title(16.sp), color = DossierColors.ink)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        politics.leadership.all().forEach { PersonCard(it, today, onRenamePerson) }
    }
    StatRow("재정 심의 기구", profile.reviewBody)
    politics.legislature?.let { ChamberComposition(it) }
    ElectionSection(politics, today, onCallEarlyElection)

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
    ConstitutionSection(politics.constitution)
}

@Composable
private fun ConstitutionSection(constitution: ConstitutionSnapshot) {
    val profile = constitution.profile
    SectionTitle("헌법기관")
    StatRow("헌법 심사 기관", profile.court ?: "없음")
    StatRow("사법 견제 지수 · V-Dem 2023", billScore(profile.judicialIndependence * 100))
    if (profile.court != null && !profile.reviewsLaws) Hint("법원이 의회 제정법을 무효로 할 수 없습니다.")
    profile.upperHouse?.let { house ->
        StatRow(house.name, "${house.power.label} · 정부 ${house.governmentSeats} · 야당 ${house.oppositionSeats} · 기타 ${house.otherSeats}")
    }
    StatRow("행정부 수반 연임 제한", profile.executiveTermLimit?.let { "${it}회" } ?: "없음")
    StatRow("의회의 정부 불신임", if (profile.noConfidence) "가능" else "없음")
    profile.presidentialVeto?.let { StatRow("대통령 거부권", "${it.holder} (${it.party}) · 재의결 ${(it.override * 100).roundToInt()}%") }
    if (constitution.cases.isNotEmpty()) {
        SectionTitle("위헌 심판")
        constitution.cases.forEach { case ->
            val status =
                when (case.struckDown) {
                    null -> "${shortDate(case.rulingDate)} 선고 예정"
                    true -> "위헌 · 변경 취소"
                    false -> "합헌"
                }
            StatRow("${case.change.item.label} (${case.change.describe()})", status)
        }
    }
}

@Composable
private fun ChecksSection(
    checks: List<BillCheck>,
    decided: Boolean,
) {
    if (checks.isEmpty()) return
    SectionTitle(if (decided) "후속 심사" else "후속 심사 예상")
    checks.forEach { check ->
        val verdict =
            when {
                check.passes -> "통과"
                check.delayDays > 0 -> "부결 · ${check.delayDays}일 지연"
                else -> "거부"
            }
        StatRow(
            check.body,
            verdict,
            valueColor = if (check.passes) DossierColors.positive else DossierColors.negative,
        )
        Hint(check.detail)
    }
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
    val open = bill.stage == BillStage.REVIEW || bill.stage == BillStage.STALLED || bill.stage == BillStage.FINAL_REVIEW
    StatRow("처리 상태", bill.stage.label + if (bill.delayed) " · 지연됨" else "")
    bill.blockedBy?.let { StatRow("좌초시킨 기관", it, valueColor = DossierColors.negative) }
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
                bill.stage == BillStage.FINAL_REVIEW -> "하원을 통과해 상원·대통령 등 후속 심사를 기다립니다."
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
    changes: Collection<PolicyChange>,
    support: BillSupport,
    decided: Boolean,
) {
    changes.forEach { StatRow(it.item.label, it.describe()) }
    ChecksSection(support.checks, decided)
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
    if (support.organizationPositions.isNotEmpty()) StatRow("조직 입장", formatPoints(support.organizationLobby))
    if (support.institutionalResistance > 0.0) StatRow("제도 변경 저항 (견제 장치)", formatPoints(0.0 - support.institutionalResistance))
    if (vote == null) StatRow("합계 · 50점 이상 통과", billScore(support.score))
    var groupDetails by remember { mutableStateOf(false) }
    PaperTag(if (groupDetails) "집단별 지지 접기" else "집단별 지지 보기", onClick = { groupDetails = !groupDetails })
    if (groupDetails) {
        support.groupScores.forEach { (group, score) -> SatisfactionRow(group.label, score) }
        SectionTitle("성향별 지지")
        support.identityScores.forEach { (identity, score) -> SatisfactionRow(identity.label, score) }
        if (support.organizationPositions.isNotEmpty()) {
            SectionTitle("조직 입장")
            support.organizationPositions.forEach {
                StatRow(
                    it.organization,
                    (
                        if (it.points >
                            0
                        ) {
                            "찬성 "
                        } else {
                            "반대 "
                        }
                    ) + formatPoints(it.points),
                )
            }
        }
    }
}

private fun dDay(
    today: LocalDate,
    date: LocalDate,
): String {
    val days = ChronoUnit.DAYS.between(today, date)
    return if (days == 0L) "D-day" else "D-$days"
}

/** 가장 가까운 선거를 "총선 D-99"처럼 짧게. 예정된 선거가 없으면 null. */
fun nextElectionTag(
    schedule: ElectionSchedule,
    today: LocalDate,
): String? {
    val legislative = schedule.legislative?.let { "총선" to it }
    val executive = schedule.executive?.let { "${schedule.executiveTitle} 선거" to it }
    val (label, date) = listOfNotNull(legislative, executive).minByOrNull { it.second } ?: return null
    val sameDay = legislative != null && executive != null && legislative.second == executive.second
    // 먼 선거는 D-day 대신 연월로 짧게 쓴다.
    val whenText = if (ChronoUnit.DAYS.between(today, date) > D_DAY_HORIZON) "${date.year}.${date.monthValue}" else dDay(today, date)
    return "${if (sameDay) "동시 선거" else label} $whenText"
}

@Composable
private fun ElectionSection(
    politics: PoliticsSnapshot,
    today: LocalDate,
    onCallEarlyElection: () -> Unit,
) {
    val schedule = politics.schedule
    val suspended = schedule.suspended
    SectionTitle("선거 일정 · ${schedule.system.label}")
    when {
        suspended != null -> {
            StatRow("선거", suspended)
        }

        schedule.legislative == null && schedule.executive == null -> {
            StatRow("선거", "권력 구성을 바꾸는 선거 없음")
        }

        else -> {
            schedule.legislative?.let { date ->
                StatRow(if (schedule.earlyCalled) "조기 총선" else "다음 총선", "$date · ${dDay(today, date)}")
            }
            schedule.executive?.let { date -> StatRow("다음 ${schedule.executiveTitle} 선거", "$date · ${dDay(today, date)}") }
        }
    }
    if (schedule.canCallEarly) {
        Text(
            "조기 총선을 소집하면 6주 뒤 선거를 치릅니다. 심의 중인 재정안은 선거일에 폐기되고 초안으로 돌아갑니다.",
            style = DossierType.number(12.sp),
            color = DossierColors.inkMuted,
        )
        PaperTag("조기 총선 소집", onClick = onCallEarlyElection)
    }
    politics.projection?.let { Projection(it) }
    politics.lastElection?.let { LastElection(it) }
}

@Composable
private fun Projection(projection: List<SeatProjection>) {
    var expanded by remember { mutableStateOf(false) }
    SectionTitle("여론조사 · 지금 총선을 치르면")
    val ranked = projection.sortedByDescending { it.projected }
    (if (expanded) ranked else ranked.take(PROJECTION_PREVIEW)).forEach { row ->
        val change = row.projected - row.current
        val sign = if (change > 0) "+" else ""
        StatRow(row.name, "득표 ${formatPercent(row.voteShare)} · ${row.projected}석 (${sign}$change)")
    }
    if (ranked.size > PROJECTION_PREVIEW) {
        PaperTag(if (expanded) "접기" else "전체 정당 보기", onClick = { expanded = !expanded })
    }
}

@Composable
private fun LastElection(result: ElectionResult) {
    var expanded by remember(result) { mutableStateOf(false) }
    SectionTitle("최근 선거 · ${result.date}${if (result.early) " (조기 총선)" else ""}")
    Stamp(
        text = if (result.governmentChanged) "정권 교체" else "정부 유지",
        color = if (result.governmentChanged) DossierColors.stamp else DossierColors.blueInk,
    )
    if (result.governmentChanged) StatRow("새 정부", result.governmentAfter)
    result.executive?.let { race ->
        val challenger = race.challenger?.let { " vs $it ${formatPercent(1.0 - race.incumbentShare)}" }.orEmpty()
        StatRow("${race.title} 선거", "${race.incumbent} ${formatPercent(race.incumbentShare)}$challenger")
        StatRow("당선", race.winner)
    }
    if (result.billLapsed) {
        Text("의회 해산으로 심의 중이던 재정안이 폐기돼 초안으로 돌아갔습니다.", style = DossierType.number(12.sp), color = DossierColors.stamp)
    }
    result.parties?.let { parties ->
        val ranked = parties.sortedByDescending { it.seatsAfter }
        (if (expanded) ranked else ranked.take(PROJECTION_PREVIEW)).forEach { row ->
            val change = row.seatsAfter - row.seatsBefore
            val sign = if (change > 0) "+" else ""
            StatRow(row.name, "득표 ${formatPercent(row.voteShare)} · ${row.seatsBefore}→${row.seatsAfter}석 (${sign}$change)")
        }
        if (ranked.size > PROJECTION_PREVIEW) {
            PaperTag(if (expanded) "접기" else "전체 결과 보기", onClick = { expanded = !expanded })
        }
    }
}

private const val PROJECTION_PREVIEW = 6
private const val D_DAY_HORIZON = 180L

/** "12.0% → 14.0%" 또는 제도 항목이면 "독립 → 정부 지시". */
internal fun PolicyChange.describe(): String =
    when (val item = item) {
        is BudgetItem -> "${formatPercent(from)} → ${formatPercent(to)}"
        is InstitutionItem -> "${item.stateLabel(from)} → ${item.stateLabel(to)}"
        is RegulationItem -> "${formatPercent(from)} → ${formatPercent(to)}"
        is CountryPolicyItem -> "${(from * 100).roundToInt()} → ${(to * 100).roundToInt()}"
    }

private fun InstitutionItem.stateLabel(value: Double): String =
    when (this) {
        InstitutionItem.CENTRAL_BANK_INDEPENDENCE -> if (value >= 0.5) "독립" else "정부 지시"
    }
