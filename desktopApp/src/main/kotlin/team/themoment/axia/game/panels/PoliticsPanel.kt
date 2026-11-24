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
import team.themoment.axia.simulation.politics.AppointmentProcedure
import team.themoment.axia.simulation.politics.BillStage
import team.themoment.axia.simulation.politics.BillSupport
import team.themoment.axia.simulation.politics.ConstitutionSnapshot
import team.themoment.axia.simulation.politics.ElectionResult
import team.themoment.axia.simulation.politics.ElectionSchedule
import team.themoment.axia.simulation.politics.FiscalBill
import team.themoment.axia.simulation.politics.Legislature
import team.themoment.axia.simulation.politics.ListRegistration
import team.themoment.axia.simulation.politics.PartyRole
import team.themoment.axia.simulation.politics.PolicyChange
import team.themoment.axia.simulation.politics.PoliticsSnapshot
import team.themoment.axia.simulation.politics.Replacement
import team.themoment.axia.simulation.politics.RollCall
import team.themoment.axia.simulation.politics.SeatProjection
import team.themoment.axia.simulation.text.josa
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
    onCoalitionPartner: (String?) -> Unit,
    onRenamePerson: (Int, String) -> Unit,
    onRenameParty: (Int, String) -> Unit,
    onOppositionVeto: (Boolean) -> Unit,
    onChooseElectionList: (String?) -> Unit,
) {
    val profile = politics.profile
    SectionTitle("현 정부")
    Text(politics.government, style = DossierType.title(16.sp), color = DossierColors.ink)
    politics.nextGovernmentDecision?.let { StatRow("다음 정부·의회 결정일", shortDate(it)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        politics.leadership.all().forEach { PersonCard(it, today, onRenamePerson) }
    }
    val openingContest = profile.elections.legislature?.openingContest
    val chamber = politics.legislature
    StatRow("재정 심의 기구", politics.reviewBody)
    if (openingContest != null && chamber == null && politics.schedule.legislative != null) {
        Hint("${openingContest.chamberName} 해산으로 과도 내각이 심의합니다. 총선 후에는 새 의회가 법안을 표결합니다.")
    }
    chamber?.let { ChamberComposition(it, politics.takenPartyNames, onRenameParty) }
    ElectionSection(politics, today, onCallEarlyElection, onChooseElectionList)
    if (chamber != null && politics.schedule.legislative != null && profile.elections.coalitionNegotiations &&
        chamber.groups.any { it.role == PartyRole.LEAD }
    ) {
        val eligible = chamber.groups.filter { it.role != PartyRole.LEAD && it.role != PartyRole.OTHER && it.votingSeats > 0 }
        if (eligible.isNotEmpty()) {
            var choosing by remember { mutableStateOf(false) }
            SectionTitle("다음 총선 연정 교섭")
            StatRow("우선 교섭 상대", politics.coalitionPreference?.partner ?: "자동 협상")
            PaperTag(if (choosing) "목록 접기" else "우선 상대 선택", onClick = { choosing = !choosing })
            if (choosing) {
                Hint("현 정부가 선거 뒤 연정을 구성하면 선택한 정당에 먼저 제안합니다. 이념 차이가 크면 협상이 결렬될 수 있습니다.")
                PaperTag("자동 협상", selected = politics.coalitionPreference == null, onClick = { onCoalitionPartner(null) })
                eligible.forEach { party ->
                    PaperTag(
                        party.name,
                        selected = politics.coalitionPreference?.partner == party.name,
                        onClick = { onCoalitionPartner(party.name) },
                    )
                }
            }
        }
    }
    if (politics.newParties.isNotEmpty()) {
        SectionTitle("신생 정당 (다음 총선 출마)")
        politics.newParties.forEach { party ->
            StatRow(party.name, "지지 ${formatPercent(party.support)} · ${party.identity.label} 성향")
            Hint("${shortDate(party.founded)} 창당")
            RenameControl(party.foundation.id, party.name, politics.takenPartyNames) { onRenameParty(party.foundation.id, it) }
        }
    }

    val pending = politics.pending
    if (pending == null) {
        SectionTitle(if (politics.draft.isEmpty()) "재정안" else "발의 전 초안")
        PaperTag(if (politics.draft.isEmpty()) "재정안 작성" else "작성 중인 재정안 열기", onClick = onBudget)
        politics.draftSupport?.let {
            BillAssessment(politics.draft.values, it, decided = false)
            it.rollCall?.let { vote -> Negotiation(vote, onNegotiate) }
            if (politics.governmentBillsSuspended) {
                Hint("현재 정부 법안 발의가 중단되어 초안으로 보관합니다.")
            } else {
                PaperTag("이 변경안 발의", selected = true, onClick = onIntroduce)
            }
        }
    } else {
        SectionTitle(if (pending.sponsor == null) "심의 중인 재정안" else "야당 발의 법안")
        if (pending.sponsor == null && pending.stage == BillStage.STALLED) PaperTag("철회하고 수정", onClick = onRevise)
        BillDetails(pending)
        if (pending.stage == BillStage.SIGNATURE) {
            PaperTag("공포", selected = !pending.vetoRequested, onClick = { onOppositionVeto(false) })
            PaperTag("재의 요구", selected = pending.vetoRequested, onClick = { onOppositionVeto(true) })
        }
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
        constitution.nextUpperRenewal?.let { StatRow("다음 상원 갱신 예정", it.toString()) }
        constitution.lastUpperRenewal?.let { StatRow("최근 상원 갱신", "${it.date} · ${it.method} · ${it.renewed}석") }
        if (house.groups.isNotEmpty()) {
            var expanded by remember(house.name) { mutableStateOf(false) }
            PaperTag(if (expanded) "상원 구성 접기" else "상원 교섭단체 보기", onClick = { expanded = !expanded })
            if (expanded) {
                StatRow("재적 / 정원", "${house.filledSeats} / ${house.seats}석 · 표결권 ${house.votingSeats}명")
                house.groups.forEach { group -> StatRow(group.name, "${group.seats}석 · ${group.role.label}") }
            }
        }
    }
    StatRow("행정부 수반 연임 제한", profile.executiveTermLimit?.let { "${it}회" } ?: "없음")
    val confidence =
        when (constitution.appointments?.procedure) {
            AppointmentProcedure.IRANIAN_MAJLES -> "각료 문책·신임 표결"
            AppointmentProcedure.VIETNAMESE_ASSEMBLY -> "국가주석·총리 신임 평가"
            else -> profile.confidence?.procedure?.label ?: "게임 미적용"
        }
    StatRow("의회의 정부 불신임", confidence)
    constitution.indirectPresident?.let { election ->
        StatRow("대통령 간선", election.procedure.body)
        election.nextBallot?.let { StatRow("다음 투표", it.toString()) }
        election.presidentElect?.let { StatRow("대통령 당선인", "${it.name} · 취임 ${election.accession}") }
        election.deputyBallot?.let { StatRow("부통령 간선", it.toString()) }
        election.deputyElect?.let { StatRow("부통령 당선인", "${it.name} · 취임 ${election.deputyAccession}") }
        election.lastDeputyBallot?.let { ballot ->
            StatRow("최근 부통령 선거", "${ballot.date} · 연방 양원 ${ballot.college.sumOf { it.voters }}명")
        }
        election.divisions.lastOrNull()?.let { ballot ->
            StatRow("최근 간선 ${ballot.round}차", "${ballot.date} · ${ballot.required?.let { "가결 ${it}표" } ?: "최다 득표"}")
            StatRow("선거인단", "${ballot.college.sumOf { it.voters }}명 · 총 ${ballot.college.sumOf { it.votes }}표")
            var collegeOpen by remember(ballot.date) { mutableStateOf(false) }
            PaperTag(if (collegeOpen) "선거인단 구성 접기" else "선거인단 구성 보기", onClick = { collegeOpen = !collegeOpen })
            if (collegeOpen) ballot.college.forEach { StatRow(it.name, "${it.voters}명 · ${it.votes}표") }
            ballot.votes.entries
                .sortedByDescending { it.value }
                .forEach { (person, count) -> StatRow(person.name, "${count}표") }
        }
    }
    constitution.appointments?.let { procedure ->
        StatRow(procedure.procedure.body, procedure.procedure.label)
        procedure.nextSession?.let { StatRow("다음 인선·신임 심의", shortDate(it)) }
        if (procedure.unfilledPortfolios.isNotEmpty()) Hint("인준 대기 · ${procedure.unfilledPortfolios.joinToString(" · ")}")
        procedure.decision?.let { Hint("${procedure.lastSession} · $it") }
        procedure.ministerVotes.forEach { vote ->
            StatRow("${vote.portfolio} · ${vote.nominee}", "${vote.yes} / ${vote.required}표 · ${if (vote.confirmed) "인준" else "부결"}")
        }
        procedure.confirmedMinisters
            .filterKeys { office ->
                procedure.ministerVotes.none { it.portfolio == office }
            }.forEach { (office, name) ->
                StatRow("$office 장관", name)
            }
    }
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
private fun ChamberComposition(
    chamber: Legislature,
    takenNames: Set<String>,
    onRenameParty: (Int, String) -> Unit,
) {
    var expanded by remember(chamber.name) { mutableStateOf(false) }
    SectionTitle("${chamber.name} · ${chamber.filledSeats}석")
    if (chamber.vacancies.isNotEmpty() || chamber.unassignedVacancies > 0) {
        StatRow("공석", "${chamber.vacancies.size + chamber.unassignedVacancies}석")
        if (chamber.unassignedVacancies > 0) StatRow("충원 예정이 없는 의석", "${chamber.unassignedVacancies}석")
        chamber.vacancies.forEach { seat ->
            val party = chamber.groups.find { (it.foundation?.originalName ?: it.name) == seat.partyKey }?.name
            StatRow(
                listOfNotNull(seat.region, party, seat.method.label).joinToString(" · "),
                seat.due?.let(::shortDate)
                    ?: if (seat.method == Replacement.NATIONAL_POLL || seat.method == Replacement.COMMUNITY_POLL) "다음 전국선거" else "보충 유보",
            )
        }
    }
    val reserved = chamber.groups.sumOf { it.reservedSeats }
    if (reserved > 0) StatRow("지역구 · 유보 의석", "${chamber.filledSeats - reserved}석 · ${reserved}석")
    val cabinet = chamber.groups.filter { it.role == PartyRole.LEAD || it.role == PartyRole.COALITION }
    if (cabinet.isNotEmpty()) StatRow("정부·연정 의석", "${cabinet.sumOf { it.seats }}석")
    val allies = chamber.groups.filter { it.role == PartyRole.SUPPLY }
    if (allies.isNotEmpty()) StatRow("정부 협력 의석", "${allies.sumOf { it.seats }}석")
    StatRow("표결권 · 가결선", "${chamber.votingSeats}표 · ${chamber.required}표")
    PaperTag(if (expanded) "정당별 의석 접기" else "정당별 의석 보기", onClick = { expanded = !expanded })
    if (expanded) {
        chamber.groups.forEach { party ->
            val excluded = if (party.nonVoting > 0) " · 불참 ${party.nonVoting}" else ""
            val allocation = if (party.reservedSeats > 0) " (유보 ${party.reservedSeats})" else ""
            StatRow(
                party.name,
                "${party.role.label} · ${party.seats}석$allocation$excluded" + party.identity?.let { " · ${it.label}" }.orEmpty(),
            )
            party.foundation?.let { origin ->
                RenameControl(origin.id, party.name, takenNames) { onRenameParty(origin.id, it) }
            }
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
    val open = bill.stage != BillStage.PASSED && bill.stage != BillStage.REJECTED
    bill.sponsor?.let { StatRow("발의 정당", it) }
    StatRow("처리 상태", bill.stage.label + if (bill.delayed) " · 지연됨" else "")
    bill.originalChanges?.let { original ->
        StatRow("양원 협의회", "연속 항목 변경 폭 50% · 가부 결정 조항 제외")
        original.forEach { StatRow("원안 · ${it.item.label}", it.describe()) }
    }
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
                bill.stage == BillStage.CONFERENCE -> "양원이 같은 절충안을 재표결합니다."
                bill.stage == BillStage.SIGNATURE -> "기한까지 재의를 요구하지 않으면 법안이 공포됩니다."
                bill.stage == BillStage.OVERRIDE -> "의회가 원안을 재표결합니다."
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
    if (support.upperVotes.isNotEmpty()) {
        var showUpper by remember { mutableStateOf(false) }
        PaperTag(if (showUpper) "상원 표결 접기" else "상원 교섭단체별 표결", onClick = { showUpper = !showUpper })
        if (showUpper) support.upperVotes.forEach { row -> StatRow(row.group, "찬성 ${row.yes} · 반대 ${row.no}") }
    }
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
    if (support.executiveBacking != 0.0) StatRow("행정부 추진력", formatPoints(support.executiveBacking))
    if (support.oppositionBacking != 0.0) StatRow("야당 발의와 정부 평가", formatPoints(support.oppositionBacking))
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
    val executive = schedule.executive?.let { (schedule.executiveBallotLabel ?: "선거") to it }
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
    onChooseElectionList: (String?) -> Unit,
) {
    val schedule = politics.schedule
    val suspended = schedule.suspended
    SectionTitle("선거 일정 · ${schedule.system.label}")
    when {
        suspended != null -> {
            StatRow("선거", suspended)
        }

        schedule.legislative == null && schedule.executive == null -> {
            StatRow("전국 경쟁 선거", "예정 없음")
        }

        else -> {
            schedule.legislative?.let { date ->
                StatRow(if (schedule.earlyCalled) "조기 총선" else "다음 총선", "$date · ${dDay(today, date)}")
            }
            schedule.executive?.let { date -> StatRow("다음 ${schedule.executiveBallotLabel}", "$date · ${dDay(today, date)}") }
        }
    }
    politics.listFilingOptions?.let { options ->
        SectionTitle("총선 후보 명부")
        StatRow("등록 마감", options.deadline.toString())
        StatRow(options.lead, options.choice?.ifEmpty { "독자 등록" } ?: "자동 공천")
        var choosing by remember(options.deadline) { mutableStateOf(false) }
        PaperTag(if (choosing) "선택 접기" else "명부 등록 방식 선택", onClick = { choosing = !choosing })
        if (choosing) {
            Hint("등록 마감일에 명부를 확정합니다. 그 전까지 공천 방식을 바꿀 수 있습니다.")
            PaperTag("자동 공천", selected = options.choice == null, onClick = { onChooseElectionList(null) })
            PaperTag("독자 등록", selected = options.choice == "", onClick = { onChooseElectionList("") })
            options.partners.forEach { partner ->
                val label = if (politics.profile.id == "KOR") "비례 위성정당 공천" else "${partner.josa("과", "와")} 공동 등록"
                PaperTag(label, selected = options.choice == partner, onClick = { onChooseElectionList(partner) })
            }
        }
    }
    politics.listRegistration?.let { RegisteredLists(it, politics.profile.id == "KOR") }
    politics.newHouseOpens?.let { StatRow("새 의회 임기 시작", it.toString()) }
    politics.legislativeRunoff?.let { runoff ->
        StatRow("총선 1차 확정 ${runoff.settled}석", "${runoff.contested}개 선거구 · ${runoff.nextRound} 결선")
        if (runoff.listSeats.isNotEmpty()) {
            var show by remember(runoff.firstRound) { mutableStateOf(false) }
            PaperTag("1차 명부·지역구 개표", selected = show, onClick = { show = !show })
            if (show) {
                runoff.listSeats.filterValues { it > 0 }.forEach { (party, seats) -> StatRow(party, "전국 명부 ${seats}석") }
                runoff.districtBallots.forEach { (district, parties) ->
                    val status =
                        when (parties.size) {
                            0 -> "재선거 예정"
                            1 -> "${parties.first()} 당선"
                            else -> "${parties.joinToString(" · ")} 결선"
                        }
                    StatRow(district, status)
                }
            }
        }
    }
    politics.presidentElect?.let { StatRow("대통령 당선인", "${it.person.name} · ${it.inauguration} 취임") }
    politics.executiveCampaign?.let { campaign ->
        when {
            campaign.houseDate != null -> {
                StatRow("하원 주 대표단 표결", "${campaign.houseDate} · 당선선 26개 주")
            }

            campaign.runoffDate != null -> {
                campaign.firstBallot.take(2).forEach { vote ->
                    StatRow(vote.person.party ?: "무소속 후보", "${vote.person.name} · 1차 ${formatPercent(vote.share)}")
                }
            }

            else -> {
                campaign.nominations.forEach { entry ->
                    val standing = campaign.primaryStandings.find { it.party == entry.nominee.party }
                    if (standing != null) {
                        val candidates = listOfNotNull(entry.nominee, entry.rival).associateBy { it.id }
                        StatRow(
                            "${standing.party} 경선",
                            "${candidates.getValue(
                                standing.firstId,
                            ).name} ${standing.firstDelegates}명 · ${candidates.getValue(
                                standing.secondId,
                            ).name} ${standing.secondDelegates}명",
                        )
                        StatRow("대의원 집계", standing.nextDate?.let { "다음 경선 $it" } ?: "후보 확정 · ${entry.nominee.name}")
                    } else {
                        val primary = entry.primaryShare?.let { " · 경선 ${formatPercent(it)}" }.orEmpty()
                        val ticket = entry.runningMate?.let { " · 부통령 후보 ${it.name}" }.orEmpty()
                        StatRow(entry.nominee.party ?: "무소속 후보", entry.nominee.name + ticket + primary)
                        if (entry.endorsers.isNotEmpty()) StatRow("추천 정당", entry.endorsers.joinToString(" · "))
                    }
                }
            }
        }
    }
    politics.seimasResolution?.let { StatRow("세이마스 · ${it.date}", it.report) }
    if (schedule.canCallEarly) {
        Text(
            if (politics.profile.id == "PAK") {
                "국민의회를 해산해 6주 뒤 총선을 치릅니다. 심의 중인 법안은 해산과 함께 폐기되며 과도 총리 선정 절차가 시작됩니다."
            } else if (politics.profile.id == "ITA") {
                "해산 후 8주가 지난 첫 일요일에 투표합니다. 선거연합은 투표 42일 전에 등록을 마감합니다."
            } else if (politics.profile.id == "LTU") {
                "조기 총선 결의는 141명 중 85명 이상이 찬성해야 합니다. 가결되면 3개월 안에 투표하며, 현 의회는 새 의원이 선서하는 날까지 직무를 수행합니다."
            } else if (politics.profile.id == "FRA") {
                "해산 후 20~40일 안에 첫 일요일 투표를 치르고, 1차에서 결정되지 않은 선거구는 다음 주에 결선을 치릅니다."
            } else {
                "조기 총선을 소집하면 6주 뒤 선거를 치릅니다. 심의 중인 재정안은 선거일에 폐기되고 초안으로 돌아갑니다."
            },
            style = DossierType.number(12.sp),
            color = DossierColors.inkMuted,
        )
        PaperTag(if (politics.profile.id == "LTU") "조기 총선 결의 표결" else "조기 총선 소집", onClick = onCallEarlyElection)
    }
    politics.projection?.let {
        Projection(
            it,
            politics.profile.elections.legislature
                ?.pakistanAssembly == true,
        )
    }
    politics.lastElection?.let { LastElection(it, politics.profile.id == "KOR") }
}

@Composable
private fun RegisteredLists(
    registration: ListRegistration,
    showCandidateCounts: Boolean,
) {
    var expanded by remember(registration.election) { mutableStateOf(false) }
    StatRow("총선 명부 등록", "${registration.filed} · ${registration.lists.size}건")
    PaperTag(if (expanded) "등록 명부 접기" else "등록 명부 보기", selected = expanded, onClick = { expanded = !expanded })
    if (expanded) {
        registration.lists.forEach { slate ->
            val parties =
                when {
                    slate.members != listOf(slate.name) -> slate.members.joinToString(" · ")
                    slate.joint -> "다른 정당 소속 후보를 포함한 공동명부"
                    else -> "독자 등록"
                }
            val details = if (showCandidateCounts) "후보 ${slate.candidateOrder.size}명 · $parties" else parties
            StatRow(slate.name, details)
        }
    }
}

@Composable
private fun Projection(
    projection: List<SeatProjection>,
    directOnly: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    SectionTitle(if (projection.firstOrNull()?.current == null) "첫 총선 전망 · 직전 총선 득표 기준" else "여론조사 · 지금 총선을 치르면")
    if (directOnly) Hint("지역구 266석의 당선 당시 소속을 예측합니다. 유보석은 무소속 당선인의 입당 마감 뒤 배분합니다.")
    val ranked = projection.sortedByDescending { it.projected }
    (if (expanded) ranked else ranked.take(PROJECTION_PREVIEW)).forEach { row ->
        val current = row.current
        val seats = if (current == null) "${row.projected}석" else "${row.projected}석 (${seatChange(current, row.projected)})"
        StatRow(row.name, "득표 ${formatPercent(row.voteShare)} · $seats")
    }
    if (ranked.size > PROJECTION_PREVIEW) {
        PaperTag(if (expanded) "접기" else "전체 정당 보기", onClick = { expanded = !expanded })
    }
}

@Composable
private fun LastElection(
    result: ElectionResult,
    showCandidateCounts: Boolean,
) {
    var expanded by remember(result) { mutableStateOf(false) }
    SectionTitle("최근 선거 · ${result.date}${if (result.early) " (조기 총선)" else ""}")
    Stamp(
        text =
            when {
                result.executive?.winner == null && result.executive?.campaign?.houseDate != null -> "하원 선출"
                result.executive?.winner == null && result.executive?.campaign?.runoffDate != null -> "결선 확정"
                result.governmentChanged -> "정권 교체"
                result.executive?.winner != null -> "당선 확정"
                else -> "정부 유지"
            },
        color = if (result.governmentChanged) DossierColors.stamp else DossierColors.blueInk,
    )
    if (result.governmentChanged) StatRow("새 정부", result.governmentAfter)
    result.executive?.let { race ->
        race.ballot.forEach { vote ->
            StatRow("${race.title} ${race.round}차 · ${vote.person.name}", "${vote.person.party ?: "무소속"} · ${formatPercent(vote.share)}")
        }
        race.winner?.let { StatRow("당선", it.name) }
        if (race.winner == null) {
            race.campaign.houseDate?.let { StatRow("하원 표결 예정", it.toString()) }
            race.campaign.runoffDate?.let { StatRow("결선 예정", it.toString()) }
        }
        race.regionalCount?.let { count ->
            val names = race.ballot.associate { it.person.id to it.person.name }
            count.electors.entries.sortedByDescending { it.value }.forEach { (id, votes) ->
                StatRow("선거인단 · ${names.getValue(id)}", "$votes / 538")
            }
            count.stateDelegations.forEach { (id, votes) -> StatRow("하원 · ${names.getValue(id)}", "$votes / 50개 주") }
            count.qualifyingProvinces?.let { StatRow("최다득표 후보가 20% 이상 득표한 주", "$it / 38개 주") }
            var showAreas by remember(result) { mutableStateOf(false) }
            PaperTag("지역별 대통령 득표", selected = showAreas, onClick = { showAreas = !showAreas })
            if (showAreas) {
                count.areas.forEach { area ->
                    StatRow(
                        area.name,
                        area.shares.entries.sortedByDescending { it.value }.joinToString(" · ") { (id, share) ->
                            val electors = area.electors[id]?.let { " (${it}명)" }.orEmpty()
                            "${names.getValue(id)} ${formatPercent(share)}$electors"
                        },
                    )
                }
            }
        }
    }
    if (result.senate.isNotEmpty()) {
        var showSenate by remember(result) { mutableStateOf(false) }
        PaperTag("연방 상원 선거 결과", selected = showSenate, onClick = { showSenate = !showSenate })
        if (showSenate) {
            result.senate.forEach { race ->
                StatRow("${race.state} · ${race.cohort}군${if (race.special) " 보궐" else ""}", "${race.winner} · ${race.seating} 취임")
            }
        }
    }
    result.listRegistration?.let { RegisteredLists(it, showCandidateCounts) }
    result.pakistanReturn?.let { filing ->
        StatRow("관보 공고 · 입당 마감", "${filing.gazette} · ${filing.deadline}")
        StatRow(
            "유보석",
            if (filing.complete) {
                "배분 완료 · 공석 ${filing.unfilledReserved}석"
            } else if (filing.published) {
                "입당 접수 중"
            } else {
                "관보 공고 대기"
            },
        )
        if (filing.affiliations.isNotEmpty()) {
            var showFilings by remember(filing.deadline) { mutableStateOf(false) }
            PaperTag("무소속 입당 ${filing.affiliations.size}건", selected = showFilings, onClick = { showFilings = !showFilings })
            if (showFilings) filing.affiliations.forEach { StatRow(it.district, "${it.filed} · ${it.party}") }
        }
        if (filing.complete) {
            var showSeats by remember(filing.deadline) { mutableStateOf(false) }
            PaperTag("입당·유보석 반영 의석", selected = showSeats, onClick = { showSeats = !showSeats })
            if (showSeats) {
                filing.allocation.sortedByDescending { it.total }.forEach {
                    StatRow(it.name, "지역구 ${it.won}→${it.direct} · 여성 ${it.women} · 비무슬림 ${it.nonMuslim} · 총 ${it.total}석")
                }
            }
        }
    }
    result.coalitionReport?.let { StatRow("우선 교섭 결과", it) }
    if (result.billLapsed) {
        Text("총선으로 심의가 종료되어 재정안이 초안으로 돌아갔습니다.", style = DossierType.number(12.sp), color = DossierColors.stamp)
    }
    if (result.listVoteShares.isNotEmpty()) {
        var showVotes by remember(result) { mutableStateOf(false) }
        PaperTag("비례대표 정당 득표율", selected = showVotes, onClick = { showVotes = !showVotes })
        if (showVotes) {
            result.listVoteShares.entries.sortedByDescending { it.value }.forEach { (name, share) ->
                StatRow(name, formatPercent(share))
            }
        }
    }
    if (result.listSeats.isNotEmpty()) {
        var showLists by remember(result) { mutableStateOf(false) }
        PaperTag("명부·임명 의석", selected = showLists, onClick = { showLists = !showLists })
        if (showLists) {
            result.listSeats.forEach { (area, counts) ->
                StatRow(
                    area,
                    counts
                        .filterValues { it > 0 }
                        .entries
                        .sortedByDescending { it.value }
                        .joinToString(" · ") { "${it.key} ${it.value}석" },
                )
            }
        }
    }
    if (result.districts.isNotEmpty()) {
        var showDistricts by remember(result) { mutableStateOf(false) }
        PaperTag("지역구별 당선 결과", selected = showDistricts, onClick = { showDistricts = !showDistricts })
        if (showDistricts) {
            result.districts.forEach { region ->
                val counts =
                    region.winners
                        .groupingBy { it }
                        .eachCount()
                        .entries
                        .sortedByDescending { it.value }
                StatRow(region.regionName, counts.joinToString(" · ") { "${it.key} ${it.value}석" })
                if (region.constituencies.isNotEmpty() || region.uncovered.isNotEmpty()) {
                    var showConstituencies by remember(result, region.regionId) { mutableStateOf(false) }
                    PaperTag(if (showConstituencies) "선거구 접기" else "${region.regionName} 선거구 보기", onClick = {
                        showConstituencies =
                            !showConstituencies
                    })
                    if (showConstituencies) {
                        region.constituencies.zip(region.winners).forEach { (name, winner) -> StatRow(name, winner) }
                        region.uncovered.forEach { (name, party) -> StatRow(name, "$party · 제2표 의석 미확보") }
                    }
                }
            }
        }
    }
    result.parties?.let { parties ->
        if (result.pakistanReturn != null) SectionTitle("개표일 지역구 당선 의석")
        val ranked = parties.sortedByDescending { it.seatsAfter }
        (if (expanded) ranked else ranked.take(PROJECTION_PREVIEW)).forEach { row ->
            val before = row.seatsBefore
            val seats = if (before == null) "${row.seatsAfter}석" else "$before→${row.seatsAfter}석 (${seatChange(before, row.seatsAfter)})"
            val label = if (result.listVoteShares.isEmpty()) "득표" else "정당 지지"
            StatRow(row.name, "$label ${formatPercent(row.voteShare)} · $seats")
        }
        if (ranked.size > PROJECTION_PREVIEW) {
            PaperTag(if (expanded) "접기" else "전체 결과 보기", onClick = { expanded = !expanded })
        }
    }
}

private fun seatChange(
    before: Int,
    after: Int,
): String = if (after > before) "+${after - before}" else (after - before).toString()

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
