package team.themoment.axia.simulation.politics

import team.themoment.axia.simulation.country.BillCheck
import team.themoment.axia.simulation.country.BillReview
import team.themoment.axia.simulation.country.CountryModule
import team.themoment.axia.simulation.country.CountryNews
import team.themoment.axia.simulation.math.nextGaussian
import team.themoment.axia.simulation.nation.BudgetItem
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.nation.GroupSnapshot
import team.themoment.axia.simulation.nation.InstitutionItem
import team.themoment.axia.simulation.nation.Opinion
import team.themoment.axia.simulation.nation.PlayerBudget
import team.themoment.axia.simulation.nation.PolicyItem
import team.themoment.axia.simulation.nation.PopulationGroup
import team.themoment.axia.simulation.nation.RegulationItem
import team.themoment.axia.simulation.nation.Regulations
import team.themoment.axia.simulation.nation.SpendingKind
import team.themoment.axia.simulation.nation.TaxKind
import team.themoment.axia.simulation.people.Leadership
import team.themoment.axia.simulation.people.LeadershipState
import team.themoment.axia.simulation.society.Identity
import team.themoment.axia.simulation.text.josa
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

data class PolicyChange(
    val item: PolicyItem,
    val from: Double,
    val to: Double,
) {
    val delta: Double get() = to - from
}

data class BillSupport(
    val groupScores: Map<PopulationGroup, Double>,
    val identityScores: Map<Identity, Double>,
    val constituency: Double,
    val executiveBacking: Double,
    val fundingPenalty: Double,
    /** 견제 장치가 강한 나라에서 독립 기관을 정부 아래로 두려 할 때의 저항(사법부, 언론, 시장). */
    val institutionalResistance: Double,
    /** 법안에 입장을 낸 조직과 그 무게(점). 양수면 찬성이다. */
    val organizationPositions: List<OrganizationPosition>,
    val rollCall: RollCall?,
    /** 하원 표결 뒤 거쳐야 하는 상원, 대통령 거부권, 국가별 거부 지점의 예상 판정. */
    val checks: List<BillCheck> = emptyList(),
    /** 법안이 늘리는 적자(GDP 대비). */
    val addedDeficit: Double = 0.0,
) {
    val organizationLobby: Double get() = organizationPositions.sumOf { it.points }

    val score: Double
        get() {
            val total =
                (constituency + executiveBacking - fundingPenalty - institutionalResistance + organizationLobby).coerceIn(0.0, 100.0)
            return (total * 10).roundToInt() / 10.0
        }
}

data class OrganizationPosition(
    val organization: String,
    val points: Double,
)

enum class BillStage(
    val label: String,
) {
    REVIEW("심의 중"),
    STALLED("정체 · 재심의"),
    FINAL_REVIEW("후속 심사"),
    PASSED("통과"),
    REJECTED("좌초"),
}

data class FiscalBill(
    val changes: List<PolicyChange>,
    val introduced: LocalDate,
    val stage: BillStage,
    /** 진행 중에는 다음 심의일, 종결 후에는 결정일. */
    val decisionDate: LocalDate,
    val support: BillSupport,
    val firstReview: BillSupport? = null,
    /** 좌초시킨 기관. 하원에서 부결됐으면 null. */
    val blockedBy: String? = null,
    /** 지연 권한만 있는 기관이 부결해 시행이 늦춰졌는지. */
    val delayed: Boolean = false,
)

/** 다음 선거 일정과 조기 총선 가능 여부. */
data class ElectionSchedule(
    val system: GovernmentSystem,
    val legislative: LocalDate?,
    val executive: LocalDate?,
    val executiveTitle: String?,
    /** 이번 의회 선거가 조기 총선으로 소집됐는지. */
    val earlyCalled: Boolean,
    val canCallEarly: Boolean,
    val suspended: String?,
)

/** 창당해 다음 총선을 기다리는 정당. [support]는 지금 선거를 치르면 얻을 득표율이다. */
data class NewParty(
    val name: String,
    val identity: Identity,
    val founded: LocalDate,
    val support: Double,
)

data class PoliticsSnapshot(
    val profile: PoliticalProfile,
    val reviewBody: String,
    val government: String,
    val leadership: Leadership,
    val constitution: ConstitutionSnapshot,
    val legislature: Legislature?,
    val schedule: ElectionSchedule,
    val projection: List<SeatProjection>?,
    val lastElection: ElectionResult?,
    val draft: Map<PolicyItem, PolicyChange>,
    val draftSupport: BillSupport?,
    val pending: FiscalBill?,
    val lastDecision: FiscalBill?,
    val newParties: List<NewParty>,
)

/**
 * 한 번에 하나의 재정안을 심의한다. 정체된 안은 4주 후 최종 심의하며 통과된 목표만 예산에 전달한다.
 * 선거 일정에 따라 의회 의석과 정부를 다시 정한다. 의회 선거일에는 심의 중인 재정안이 폐기돼 초안으로 돌아간다.
 */
internal class Politics(
    private val profile: PoliticalProfile,
    private val constitution: ConstitutionProfile,
    private val budget: PlayerBudget,
    private val regulations: Regulations,
    private val institutions: InstitutionControl,
    private val module: CountryModule,
    private val leadership: LeadershipState,
    private val random: Random,
) {
    private val rules = profile.elections
    private var groups: List<GroupSnapshot> = emptyList()
    private lateinit var opinion: Opinion
    private var approval = 50.0
    private val draft = linkedMapOf<PolicyItem, PolicyChange>()
    private var negotiatingWith: String? = null
    private var pending: FiscalBill? = null
    private var lastDecision: FiscalBill? = null

    private var legislature: Legislature? = profile.legislature
    private val government: String = profile.initialGovernment

    /** 쿠데타로 군사평의회가 들어선 날. 의회와 선거가 정지된다. */
    private var juntaSince: LocalDate? = null

    private var nextLegislative: LocalDate? = rules.legislature?.next
    private var nextExecutive: LocalDate? = rules.executive?.next
    private var lastLegislative: LocalDate? =
        if (legislature == null) null else rules.legislature?.let { it.next.minusYears(it.termYears.toLong()) }
    private var earlyCalled = false
    private var lastElection: ElectionResult? = null
    private var projection: List<SeatProjection>? = null
    private val cases = mutableListOf<CourtCase>()
    private val newParties = mutableListOf<NewParty>()

    /** 정부 출범 때의 지지율과 연정 상대 지지층 만족도. 이탈·불신임은 출범 때보다 나빠졌을 때만 일어난다. */
    private var formationApproval: Double? = null
    private val formationMood = HashMap<String, Double>()

    /** 신생 정당이 대변하는 성향. 의회에 들어간 뒤에도 같은 성향의 정당이 또 생기지 않도록 기억한다. */
    private val partyIdentities = HashMap<String, Identity>()
    private val news = mutableListOf<CountryNews>()

    /** 쌓인 정치 소식을 꺼낸다. 시뮬레이션이 매일 국가 소식으로 옮긴다. */
    fun drainNews(): List<CountryNews> = news.toList().also { news.clear() }

    fun updateConstituencies(
        latest: List<GroupSnapshot>,
        latestOpinion: Opinion,
        latestApproval: Double,
    ) {
        groups = latest
        opinion = latestOpinion
        approval = latestApproval
        pending = pending?.let { it.copy(support = assess(it.changes, it.support.rollCall?.negotiatingWith)) }
        projection = project()
    }

    fun edit(
        item: PolicyItem,
        target: Double,
    ) {
        if (pending != null || !target.isFinite()) return
        if (item is InstitutionItem && !institutions.adjustable(item)) return
        if (item is CountryPolicyItem && item !in module.policies) return
        val from = currentValue(item)
        val to = target.coerceIn(0.0, item.maxTarget)
        // Slider는 Float로 왕복한다. 원래 목표로 돌린 항목을 변경안에 남기지 않는다.
        if (abs(to - from) < 1e-7) draft.remove(item) else draft[item] = PolicyChange(item, from, to)
        if (draft.isEmpty()) negotiatingWith = null
    }

    fun clearDraft() {
        draft.clear()
        negotiatingWith = null
    }

    fun negotiate(party: String?) {
        if (pending != null || draft.isEmpty()) return
        if (party == null || legislature?.groups?.any { it.name == party && it.canNegotiate } == true) {
            negotiatingWith = party
        }
    }

    fun reviseStalledBill() {
        val bill = pending ?: return
        if (bill.stage != BillStage.STALLED) return
        bill.changes.associateByTo(draft) { it.item }
        negotiatingWith = bill.support.rollCall?.negotiatingWith
        pending = null
    }

    fun introduce(date: LocalDate) {
        if (pending != null || draft.isEmpty()) return
        val changes = draft.values.toList()
        val negotiationDays = if (negotiatingWith == null) 0 else 14
        val days = 21 + negotiationDays + (35 * profile.legislativeConstraints).roundToLong()
        pending = FiscalBill(changes, date, BillStage.REVIEW, date.plusDays(days), assess(changes, negotiatingWith))
        clearDraft()
    }

    private fun currentValue(item: PolicyItem): Double =
        when (item) {
            is BudgetItem -> budget.target(item)
            is RegulationItem -> regulations.target(item)
            is InstitutionItem -> institutions.value(item)
            is CountryPolicyItem -> module.policyValue(item)
        }

    private fun enact(change: PolicyChange) {
        when (val item = change.item) {
            is BudgetItem -> budget.enact(item, change.to)
            is RegulationItem -> regulations.enact(item, change.to)
            is InstitutionItem -> institutions.enact(item, change.to)
            is CountryPolicyItem -> module.enact(item, change.to)
        }
    }

    /** 쿠데타 성공. 의회를 해산하고 선거를 무기한 정지하며, 이후 법안은 기관 심의(50점 이상 통과)로 처리한다. */
    fun coup(date: LocalDate) {
        dissolveForElection()
        val leader = leadership.installJunta(date)
        juntaSince = date
        legislature = null
        nextLegislative = null
        nextExecutive = null
        projection = null
        news += CountryNews("군부", "군부가 정권을 장악하고 의회를 해산했다. ${leader.name.josa("이", "가")} 국가재건평의회 의장에 올랐다.", major = true)
    }

    /** 조기 해산이 가능한 나라에서 직전 선거 후 1년이 지났고 정기 선거가 6개월 넘게 남았으면 6주 뒤 총선을 치른다. */
    fun canCallEarlyElection(date: LocalDate): Boolean {
        val rule = rules.legislature ?: return false
        val next = nextLegislative ?: return false
        val last = lastLegislative ?: return false
        return rule.earlyDissolution && !earlyCalled && date >= last.plusYears(1) && next > date.plusMonths(6)
    }

    fun callEarlyElection(date: LocalDate) {
        if (!canCallEarlyElection(date)) return
        nextLegislative = date.plusWeeks(EARLY_ELECTION_WEEKS)
        earlyCalled = true
    }

    /** 오늘 선거가 있으면 치르고 true를 돌려준다. 재정안 심의보다 먼저 호출한다. */
    fun holdElections(date: LocalDate): Boolean {
        val legislativeDue = nextLegislative?.let { it <= date } == true
        val executiveDue = nextExecutive?.let { it <= date } == true
        if (!legislativeDue && !executiveDue) return false
        val before = snapshotGovernment()
        val wasEarly = earlyCalled
        var parties: List<PartyResult>? = null
        var lapsed = false
        if (legislativeDue) {
            lapsed = dissolveForElection()
            parties = electLegislature(date)
            val rule = requireNotNull(rules.legislature)
            lastLegislative = date
            nextLegislative = date.plusYears(rule.termYears.toLong())
            earlyCalled = false
        }
        val executive = if (executiveDue) electExecutive(date) else null
        if (executiveDue) nextExecutive = date.plusYears(requireNotNull(rules.executive).termYears.toLong())
        lastElection = ElectionResult(date, wasEarly && legislativeDue, parties, executive, before, snapshotGovernment(), lapsed)
        if (lastElection?.governmentChanged == true) newGovernment()
        projection = project()
        pending = pending?.let { it.copy(support = assess(it.changes, it.support.rollCall?.negotiatingWith)) }
        return true
    }

    /** 의회 해산으로 심의 중인 재정안은 폐기되고, 같은 내용이 초안으로 돌아간다. */
    private fun dissolveForElection(): Boolean {
        val bill = pending ?: return false
        bill.changes.associateByTo(draft) { it.item }
        negotiatingWith = null
        pending = null
        return true
    }

    /**
     * 성향 집단의 불만이 크고(만족도 35 미만) 그 성향을 대변하는 신생 정당이 없으면 창당한다.
     * 창당한 정당은 불만이 이어지는 동안 지지율이 오르고 다음 총선에서 의석을 다툰다. 경쟁 선거가 있는 나라만 해당한다.
     */
    private fun considerNewParties(date: LocalDate) {
        if (rules.legislature == null || legislature == null || groups.isEmpty()) return
        newParties.replaceAll { party ->
            val mood = opinion.identities.getValue(party.identity)
            val target = partySupport(party.identity, mood)
            party.copy(support = party.support + (target - party.support) * PARTY_SMOOTHING)
        }
        newParties.removeIf { it.support < MIN_PARTY_SUPPORT / 2 }
        Identity.entries.forEach { identity ->
            val mood = opinion.identities.getValue(identity)
            val represented =
                newParties.any { it.identity == identity } ||
                    legislature?.groups.orEmpty().any { partyIdentities[it.name] == identity }
            if (mood >= NEW_PARTY_MOOD || represented) return@forEach
            if (newParties.size >= MAX_NEW_PARTIES || random.nextDouble() >= NEW_PARTY_CHANCE) return@forEach
            val name = partyName(identity)
            newParties += NewParty(name, identity, date, MIN_PARTY_SUPPORT)
            partyIdentities[name] = identity
            news += CountryNews(name, "${identity.label} 성향 유권자들의 불만 속에 ${name.josa("이", "가")} 창당했다.", major = true)
        }
    }

    private fun partySupport(
        identity: Identity,
        mood: Double,
    ): Double =
        (opinion.identityShares.getValue(identity) * ((NEW_PARTY_MOOD - mood) / NEW_PARTY_MOOD).coerceAtLeast(0.0) * PARTY_REACH)
            .coerceIn(0.0, MAX_PARTY_SUPPORT)

    private fun partyName(identity: Identity): String {
        val taken = (legislature?.groups.orEmpty().map { it.name } + newParties.map { it.name }).toSet()
        val bases = PARTY_NAMES.getValue(identity)
        val candidates = bases + PARTY_PREFIXES.flatMap { prefix -> bases.map { "$prefix$it" } }
        return candidates.firstOrNull { it !in taken } ?: "${bases.first()} ${taken.size + 1}"
    }

    /** 신생 정당의 득표를 기존 정당 득표에 섞는다. 신생 정당 몫만큼 기존 정당이 같은 비율로 줄어든다. */
    private fun withNewParties(shares: Map<String, Double>): Map<String, Double> {
        if (newParties.isEmpty()) return shares
        val fresh = newParties.associate { it.name to it.support }
        val rest = 1.0 - fresh.values.sum()
        return shares.mapValues { it.value * rest } + fresh
    }

    private fun provisionalGroups(): List<ParliamentaryGroup> = newParties.map { ParliamentaryGroup(it.name, 1, PartyRole.OPPOSITION) }

    private fun electLegislature(date: LocalDate): List<PartyResult> {
        val rule = requireNotNull(rules.legislature)
        val chamber = legislature
        if (chamber == null) {
            val result = ElectionModel.openingElection(rule, profile.regime) { random.nextGaussian() * VOTE_NOISE }
            legislature = result.legislature
            val lead = result.legislature.groups.firstOrNull { it.role == PartyRole.LEAD }
            if (lead != null) {
                val title = requireNotNull(rule.openingContest).headOfGovernmentTitle
                leadership.onGovernmentFormed(lead.name, date, reelected = false, title = title)
            }
            return result.parties
        }
        val shares = withNewParties(voteShares(chamber.groups) { random.nextGaussian() * VOTE_NOISE })
        val seats = ElectionModel.legislativeSeats(shares, rule)
        val contenders = chamber.groups + provisionalGroups()
        val previousRoles = contenders.associate { it.name to it.role }
        newParties.clear()
        val reseated = chamber.withGroups(ElectionModel.reseat(contenders, seats))
        val governed =
            when (rules.system) {
                GovernmentSystem.PARLIAMENTARY, GovernmentSystem.SEMI_PRESIDENTIAL -> {
                    reseated.withGroups(ElectionModel.formGovernment(reseated.groups, reseated.required, previousRoles))
                }

                GovernmentSystem.PRESIDENTIAL, GovernmentSystem.NON_COMPETITIVE -> {
                    reseated
                }
            }
        val leadBefore = previousRoles.entries.firstOrNull { it.value == PartyRole.LEAD }?.key
        val leadAfter = governed.groups.firstOrNull { it.role == PartyRole.LEAD }?.name
        legislature = governed
        if (leadAfter != null && rules.system != GovernmentSystem.PRESIDENTIAL) {
            leadership.onGovernmentFormed(leadAfter, date, reelected = leadAfter == leadBefore)
        }
        return contenders.map { party ->
            val before = chamber.groups.firstOrNull { it.name == party.name }?.seats ?: 0
            PartyResult(party.name, party.role, shares.getValue(party.name), before, seats.total(party.name))
        }
    }

    private fun electExecutive(date: LocalDate): ExecutiveResult? {
        val chamber = legislature ?: return null
        val rule = requireNotNull(rules.executive)
        // 연임 제한에 걸린 현직 대신 같은 당 새 후보가 나서면 현직 프리미엄을 잃는다.
        val openSeat = leadership.termLimited(constitution.executiveTermLimit)
        val race =
            ElectionModel.executiveRace(
                chamber.groups,
                rule.title,
                profile.regime,
                approval,
                random.nextGaussian() * EXECUTIVE_NOISE - if (openSeat) OPEN_SEAT_PENALTY else 0.0,
            ) ?: return null
        leadership.onExecutiveElection(race.winner, constitution.executiveTermLimit, date)
        // 이원집정제에서는 정부가 의회에서 나오므로 대통령 선거가 정부 구성을 바꾸지 않는다.
        if (race.winner != race.incumbent && rules.system == GovernmentSystem.PRESIDENTIAL) {
            val previousRoles = chamber.groups.associate { it.name to it.role }
            val installed = chamber.withGroups(ElectionModel.installPresident(chamber.groups, race.winner))
            // 비례대표 다당제에서는 새 대통령 정당이 과반 연정을 꾸린다(예: 브라질, 인도네시아).
            val multiparty = requireNotNull(rules.legislature).electoralSystem == ElectoralSystem.PROPORTIONAL
            legislature =
                if (multiparty) {
                    installed.withGroups(
                        ElectionModel.formGovernment(installed.groups, installed.required, previousRoles, president = race.winner),
                    )
                } else {
                    installed
                }
        }
        return race
    }

    private fun voteShares(
        parties: List<ParliamentaryGroup>,
        noise: (ParliamentaryGroup) -> Double,
    ): Map<String, Double> =
        ElectionModel.voteShares(
            groups = parties,
            system = requireNotNull(rules.legislature).electoralSystem,
            regime = profile.regime,
            approval = approval,
            satisfaction = groups.associate { it.group to it.satisfaction },
            noise = noise,
        )

    private fun project(): List<SeatProjection>? {
        val rule = rules.legislature ?: return null
        if (groups.isEmpty() || nextLegislative == null) return null
        val chamber = legislature
        if (chamber == null) {
            val result = ElectionModel.openingElection(rule, profile.regime) { 0.0 }
            return result.parties.map { SeatProjection(it.name, null, it.seatsAfter, it.voteShare) }
        }
        val shares = withNewParties(voteShares(chamber.groups) { 0.0 })
        val seats = ElectionModel.legislativeSeats(shares, rule)
        return (chamber.groups + provisionalGroups()).map {
            val current = chamber.groups.firstOrNull { group -> group.name == it.name }?.seats ?: 0
            SeatProjection(it.name, current, seats.total(it.name), shares.getValue(it.name))
        }
    }

    /**
     * 월초 집행이 끝난 뒤 호출한다. 같은 날 통과해도 실제 집행은 다음 달부터 시작한다.
     * 하원을 통과한 안은 상원·대통령·국가별 거부 지점이 있으면 3주 뒤 후속 심사를 거친다.
     */
    fun advanceDay(date: LocalDate): Boolean {
        ruleOnCases(date)
        val bill = pending ?: return false
        if (date < bill.decisionDate) return false
        if (bill.stage == BillStage.FINAL_REVIEW) return finalReview(bill, date)
        val support = bill.support
        val voted = support.rollCall?.passes ?: (support.score >= 50.0)
        // 표결에서 졌어도 국가별 제도(예: 프랑스 49조 3항)로 강행할 수 있다.
        val forced = if (voted) null else module.forceThrough(review(bill.changes, support))
        forced?.let { news += CountryNews(it, "정부가 ${it.josa("으로", "로")} 표결 없이 법안을 채택했다.", major = true) }
        val passes = voted || forced != null
        val close = support.rollCall?.close ?: (support.score >= 40.0)
        when {
            passes && support.checks.isNotEmpty() -> {
                pending = bill.copy(stage = BillStage.FINAL_REVIEW, decisionDate = date.plusDays(FINAL_REVIEW_DAYS))
                return false
            }

            passes -> {
                return pass(bill, date)
            }

            close && bill.stage == BillStage.REVIEW -> {
                pending = bill.copy(stage = BillStage.STALLED, decisionDate = date.plusWeeks(4), firstReview = support)
            }

            else -> {
                lastDecision = bill.copy(stage = BillStage.REJECTED, decisionDate = date)
                pending = null
            }
        }
        return false
    }

    private fun finalReview(
        bill: FiscalBill,
        date: LocalDate,
    ): Boolean {
        val failed = bill.support.checks.filter { !it.passes }
        val veto = failed.firstOrNull { it.delayDays == 0 }
        if (veto != null) {
            lastDecision = bill.copy(stage = BillStage.REJECTED, decisionDate = date, blockedBy = veto.body)
            pending = null
            news += CountryNews(veto.body, "${veto.body}에서 법안이 좌초됐다. ${veto.detail}", major = true)
            return false
        }
        val delay = failed.maxOfOrNull { it.delayDays } ?: 0
        if (delay > 0 && !bill.delayed) {
            pending = bill.copy(decisionDate = date.plusDays(delay.toLong()), delayed = true)
            news += CountryNews(failed.first().body, "${failed.first().body.josa("이", "가")} 법안을 부결해 시행이 ${delay}일 늦춰졌다.", major = false)
            return false
        }
        return pass(bill, date)
    }

    private fun pass(
        bill: FiscalBill,
        date: LocalDate,
    ): Boolean {
        bill.changes.forEach(::enact)
        lastDecision = bill.copy(stage = BillStage.PASSED, decisionDate = date)
        pending = null
        fileCases(bill.changes, date)
        return true
    }

    /** 위헌 소지가 있는 변경은 헌법기관이 심사한다. 무효 가능성은 사법 견제 지수 × 심각도다. */
    private fun fileCases(
        changes: List<PolicyChange>,
        date: LocalDate,
    ) {
        val court = constitution.court ?: return
        if (!constitution.reviewsLaws) return
        changes.forEach { change ->
            val severity = ConstitutionalReview.severity(change)
            if (severity <= 0.0) return@forEach
            val ruling = date.plusDays(COURT_MIN_DAYS + random.nextLong(COURT_EXTRA_DAYS))
            cases += CourtCase(court, change, date, ruling, struckDown = null)
            news += CountryNews(court, "${court}에 ${change.item.label} 변경에 대한 위헌 심판이 청구됐다.", major = false)
        }
    }

    private fun ruleOnCases(date: LocalDate) {
        cases.replaceAll { case ->
            if (case.struckDown != null || date < case.rulingDate) return@replaceAll case
            val chance = constitution.judicialIndependence * ConstitutionalReview.severity(case.change)
            val struck = random.nextDouble() < chance
            if (struck) {
                enact(PolicyChange(case.change.item, case.change.to, case.change.from))
                news +=
                    CountryNews(
                        case.court,
                        "${case.court.josa("이", "가")} ${case.change.item.label} 변경을 위헌으로 결정해 이전 수준으로 되돌렸다.",
                        major = true,
                    )
            } else {
                news += CountryNews(case.court, "${case.court.josa("이", "가")} ${case.change.item.label} 변경을 합헌으로 결정했다.", major = false)
            }
            case.copy(struckDown = struck)
        }
        while (cases.size > MAX_CASES) cases.removeAt(cases.indexOfFirst { it.struckDown != null }.coerceAtLeast(0))
    }

    /**
     * 매월 정부 신임을 점검한다. 불신임이 가능한 나라에서 연정·협력 정당은 지지 기반의 불만이 크면 정부를 떠난다.
     * 정부가 과반을 잃고 지지율이 낮으면 불신임안이 통과해 선거 없이 정부가 다시 구성된다.
     */
    fun monthlyReview(date: LocalDate) {
        leadership.checkMortality(date, random)?.let { news += CountryNews("정부", it, major = true) }
        considerNewParties(date)
        if (!constitution.noConfidence) return
        val chamber = legislature ?: return
        if (rules.system != GovernmentSystem.PARLIAMENTARY && rules.system != GovernmentSystem.SEMI_PRESIDENTIAL) return
        val satisfaction = groups.associate { it.group to it.satisfaction }
        var groupsNow = chamber.groups
        groupsNow.filter { it.role == PartyRole.COALITION || it.role == PartyRole.SUPPLY }.forEach { partner ->
            val mood = partner.constituency?.let(satisfaction::get) ?: approval
            val atFormation = formationMood.getOrPut(partner.name) { mood }
            if (mood < DEFECTION_MOOD && mood < atFormation - DETERIORATION && random.nextDouble() < DEFECTION_CHANCE) {
                groupsNow = groupsNow.map { if (it.name == partner.name) it.copy(role = PartyRole.OPPOSITION) else it }
                news += CountryNews(partner.name, "${partner.name.josa("이", "가")} 지지층의 불만 속에 정부와 결별했다.", major = true)
            }
        }
        val governing = groupsNow.filter { it.role == PartyRole.LEAD || it.role == PartyRole.COALITION || it.role == PartyRole.SUPPLY }
        val lead = groupsNow.firstOrNull { it.role == PartyRole.LEAD }
        val approvalAtFormation = formationApproval ?: approval.also { formationApproval = it }
        if (lead != null && governing.sumOf { it.votingSeats } < chamber.required && approval < NO_CONFIDENCE_APPROVAL &&
            approval < approvalAtFormation - DETERIORATION &&
            random.nextDouble() < NO_CONFIDENCE_CHANCE
        ) {
            val rule = rules.legislature
            if (rule != null && rule.earlyDissolution && nextLegislative?.let { it > date.plusMonths(6) } == true) {
                // 조기 해산이 가능한 나라는 불신임 뒤 의회를 해산하고 6주 뒤 총선을 치른다.
                nextLegislative = date.plusWeeks(EARLY_ELECTION_WEEKS)
                earlyCalled = true
                news += CountryNews(chamber.name, "${chamber.name}에서 불신임안이 가결돼 의회가 해산됐다. 6주 뒤 조기 총선을 치른다.", major = true)
            } else {
                // 실각한 정당 대신 가장 큰 다른 정당이 과반을 모은다.
                val formateur = groupsNow.filter { it.name != lead.name && it.role != PartyRole.OTHER }.maxByOrNull { it.votingSeats }
                val previousRoles = groupsNow.associate { it.name to PartyRole.OPPOSITION } + (lead.name to PartyRole.LEAD)
                val reformed = ElectionModel.formGovernment(groupsNow, chamber.required, previousRoles, president = formateur?.name)
                val newLead = reformed.firstOrNull { it.role == PartyRole.LEAD }?.name ?: lead.name
                groupsNow = reformed
                if (newLead ==
                    lead.name
                ) {
                    leadership.replaceHead(newLead, date)
                } else {
                    leadership.onGovernmentFormed(newLead, date, reelected = false)
                }
                newGovernment()
                news +=
                    CountryNews(
                        chamber.name,
                        "${chamber.name}에서 불신임안이 가결돼 새 정부가 들어섰다. 새 정부 수반은 ${leadership.current().leader.name.josa("이다", "다")}.",
                        major = true,
                    )
                dissolveForElection()
            }
        }
        if (groupsNow != chamber.groups) {
            legislature = chamber.withGroups(groupsNow)
            projection = project()
            pending = pending?.let { it.copy(support = assess(it.changes, it.support.rollCall?.negotiatingWith)) }
        }
    }

    /** 지금 정부 표기. 지도자나 군사평의회가 바뀌면 함께 바뀐다. */
    private fun snapshotGovernment(): String =
        when {
            juntaSince != null -> "군사평의회 · ${leadership.current().leader.name}"
            else -> leadership.governmentLabel(government, rules.system == GovernmentSystem.SEMI_PRESIDENTIAL)
        }

    /** 정권이 바뀌면 이탈·불신임 기준을 새 정부 출범 시점으로 다시 잡는다. */
    private fun newGovernment() {
        formationApproval = null
        formationMood.clear()
        mandateRenewed = true
    }

    /** 이번에 정권이 바뀌었는지. 시뮬레이션이 꺼내 가며 허니문을 준다. */
    private var mandateRenewed = false

    fun takeMandateRenewal(): Boolean = mandateRenewed.also { mandateRenewed = false }

    fun snapshot(date: LocalDate): PoliticsSnapshot =
        PoliticsSnapshot(
            profile = profile,
            reviewBody =
                when {
                    juntaSince != null -> "국가재건평의회"
                    rules.legislature?.openingContest != null -> legislature?.name ?: profile.reviewBody
                    else -> profile.reviewBody
                },
            government = snapshotGovernment(),
            legislature = legislature,
            schedule =
                ElectionSchedule(
                    system = rules.system,
                    legislative = nextLegislative,
                    executive = nextExecutive,
                    executiveTitle = rules.executive?.title,
                    earlyCalled = earlyCalled,
                    canCallEarly = canCallEarlyElection(date),
                    suspended = if (juntaSince != null) "군사정권 아래 선거 정지" else rules.suspended,
                ),
            projection = projection,
            lastElection = lastElection,
            leadership = leadership.current(),
            newParties = newParties.toList(),
            constitution = ConstitutionSnapshot(constitution, cases.toList()),
            draft = draft.toMap(),
            draftSupport = if (draft.isEmpty()) null else assess(draft.values, negotiatingWith),
            pending = pending,
            lastDecision = lastDecision,
        )

    private fun assess(
        changes: Collection<PolicyChange>,
        partner: String?,
    ): BillSupport {
        val scores =
            groups.associate { state ->
                val group = state.group
                val policyEffect =
                    changes.sumOf { change ->
                        when (val item = change.item) {
                            is TaxKind -> -change.delta * group.taxIncidence.getValue(item) * 160.0
                            is SpendingKind -> change.delta * group.serviceWeights.getValue(item) * 240.0
                            InstitutionItem.CENTRAL_BANK_INDEPENDENCE -> change.delta * INDEPENDENCE_STANCE.getValue(group)
                            is RegulationItem -> change.delta * (REGULATION_STANCE[item]?.get(group) ?: 0.0)
                            is CountryPolicyItem -> change.delta * (item.groupReactions[group] ?: 0.0)
                        }
                    }
                group to (state.satisfaction + policyEffect).coerceIn(0.0, 100.0)
            }
        val identityScores =
            opinion.identities.mapValues { (identity, satisfaction) ->
                (satisfaction + changes.sumOf { identity.reaction(it.item) * it.delta }).coerceIn(0.0, 100.0)
            }
        // 유권자 표본에서 구한 정치적 영향력 계수. 시민사회 참여도가 높을수록 인구 비중, 낮을수록 기존 영향력에 가깝다.
        val weights = opinion.weights
        val constituency =
            scores.entries.sumOf { (group, score) -> score * weights.groups.getValue(group) } +
                identityScores.entries.sumOf { (identity, score) -> score * weights.identities.getValue(identity) }
        val addedDeficit =
            changes.sumOf { change ->
                when (val item = change.item) {
                    is TaxKind -> -change.delta * item.baseShare
                    is SpendingKind -> change.delta
                    is CountryPolicyItem -> change.delta * module.estimatedSpendingPerUnit(item)
                    is InstitutionItem, is RegulationItem -> 0.0
                }
            }
        val executiveBacking = 4.0 + 12.0 * (1.0 - profile.legislativeConstraints)
        val fundingPenalty = addedDeficit.coerceAtLeast(0.0) * 180.0
        val institutionalResistance =
            changes.sumOf { change ->
                if (change.item is InstitutionItem || change.item == RegulationItem.CIVIL_LIBERTIES) {
                    (-change.delta).coerceAtLeast(0.0) * INSTITUTIONAL_RESISTANCE *
                        profile.legislativeConstraints
                } else {
                    0.0
                }
            }
        // 조직은 기반이 이 법안으로 얻거나 잃는 만큼 찬반 입장을 내고, 영향력만큼 표결에 무게를 싣는다.
        val positions =
            opinion.stakes.mapNotNull { stake ->
                val effect =
                    stake.identity?.let { identityScores.getValue(it) - opinion.identities.getValue(it) }
                        ?: stake.group?.let { scores.getValue(it) - opinion.groups.getValue(it) }
                        ?: 0.0
                if (abs(effect) < MIN_LOBBY_EFFECT) {
                    null
                } else {
                    OrganizationPosition(stake.name, effect.sign * (stake.influence * LOBBY_PER_INFLUENCE).coerceAtMost(MAX_LOBBY))
                }
            }
        val lobby = positions.sumOf { it.points }
        val net = constituency + executiveBacking - fundingPenalty - institutionalResistance + lobby
        val rollCall = legislature?.forecast(net, constituency, scores, partner)
        val base =
            BillSupport(
                groupScores = scores,
                identityScores = identityScores,
                constituency = constituency,
                executiveBacking = executiveBacking,
                fundingPenalty = fundingPenalty,
                institutionalResistance = institutionalResistance,
                organizationPositions = positions,
                rollCall = rollCall,
            )
        val withDeficit = base.copy(addedDeficit = addedDeficit)
        return withDeficit.copy(checks = checks(changes.toList(), withDeficit))
    }

    private fun review(
        changes: List<PolicyChange>,
        support: BillSupport,
    ): BillReview {
        val house = constitution.upperHouse
        return BillReview(
            changes = changes,
            score = support.score,
            upperHouseYes = house?.forecast(support.score),
            upperHouseSeats = house?.seats,
            lowerHouseYes = support.rollCall?.yes,
            lowerHouseSeats = legislature?.votingSeats,
            addedDeficit = support.addedDeficit,
        )
    }

    /** 하원 다음에 거칠 거부 지점. 상원, 동거 대통령, 국가별 특화 모듈 순이다. */
    private fun checks(
        changes: List<PolicyChange>,
        support: BillSupport,
    ): List<BillCheck> {
        val house = constitution.upperHouse
        val upper = house?.let { ConstitutionalReview.upperHouseCheck(it, support.score) }
        val veto = constitution.presidentialVeto?.let { ConstitutionalReview.vetoCheck(it, legislature, support) }
        val special = module.billChecks(review(changes, support))
        // 미국처럼 국가 모듈이 상원 규칙을 직접 판정하면 공통 상원 판정은 뺀다.
        val replacesUpper = special.any { it.body.startsWith("상원") }
        return listOfNotNull(upper.takeUnless { replacesUpper }, veto) + special
    }
}

private const val EARLY_ELECTION_WEEKS = 6L
private const val FINAL_REVIEW_DAYS = 21L
private const val COURT_MIN_DAYS = 60L
private const val COURT_EXTRA_DAYS = 120L
private const val MAX_CASES = 12
private const val OPEN_SEAT_PENALTY = 0.02
private const val DEFECTION_MOOD = 35.0

/** 이탈·불신임에 필요한, 정부 출범 이후의 만족도·지지율 하락 폭. */
private const val DETERIORATION = 8.0
private const val DEFECTION_CHANCE = 0.12
private const val NO_CONFIDENCE_APPROVAL = 35.0
private const val NO_CONFIDENCE_CHANCE = 0.25
private const val NEW_PARTY_MOOD = 35.0
private const val NEW_PARTY_CHANCE = 0.08
private const val MAX_NEW_PARTIES = 3
private const val MIN_PARTY_SUPPORT = 0.01
private const val MAX_PARTY_SUPPORT = 0.15
private const val PARTY_REACH = 0.5
private const val PARTY_SMOOTHING = 0.1
private val PARTY_PREFIXES = listOf("새", "미래", "국민")
private val PARTY_NAMES =
    mapOf(
        Identity.ENVIRONMENTALISTS to listOf("녹색당", "기후정의당"),
        Identity.PATRIOTS to listOf("국민수호당", "애국연합"),
        Identity.RELIGIOUS to listOf("가치연합", "전통가정당"),
        Identity.LIBERTARIANS to listOf("자유시민당", "시민자유연대"),
        Identity.EGALITARIANS to listOf("정의평등당", "평등사회당"),
        Identity.FREE_MARKET to listOf("시장개혁당", "자유경제당"),
        Identity.UNIONISTS to listOf("노동자연대당", "일하는사람들당"),
        Identity.YOUTH to listOf("청년미래당", "다음세대당"),
        Identity.PARENTS to listOf("가족우선당", "아이키움당"),
    )
private const val MIN_LOBBY_EFFECT = 1.0
private const val LOBBY_PER_INFLUENCE = 1.5
private const val MAX_LOBBY = 4.0

/** 견제가 가장 강한 나라(입법 제약 1)에서 독립 기관 하나를 정부 통제로 돌릴 때 깎이는 점수. */
private const val INSTITUTIONAL_RESISTANCE = 30.0
private const val VOTE_NOISE = 0.1
private const val EXECUTIVE_NOISE = 0.04

/** 예산이 아닌 제도 항목의 현재 값과 집행. 플레이어 국가의 중앙은행 등이 구현한다. */
internal interface InstitutionControl {
    fun value(item: InstitutionItem): Double

    /** 이 나라에서 법안으로 바꿀 수 있는 제도인지(예: 통화동맹 회원국은 중앙은행 독립성을 바꿀 수 없다). */
    fun adjustable(item: InstitutionItem): Boolean

    fun enact(
        item: InstitutionItem,
        target: Double,
    )
}

/** 규제 1단위(0 → 1) 변화에 대한 직업 집단의 물질적 이해. 성향 반응은 [Identity.reaction]이 따로 더한다. */
private val REGULATION_STANCE =
    mapOf(
        RegulationItem.LABOR to
            mapOf(
                PopulationGroup.WORKERS to 20.0,
                PopulationGroup.OFFICE to 10.0,
                PopulationGroup.PUBLIC_SECTOR to 5.0,
                PopulationGroup.SELF_EMPLOYED to -20.0,
                PopulationGroup.CAPITALISTS to -30.0,
            ),
        RegulationItem.ENVIRONMENT to
            mapOf(
                PopulationGroup.CAPITALISTS to -15.0,
                PopulationGroup.WORKERS to -8.0,
                PopulationGroup.FARMERS to -8.0,
                PopulationGroup.PROFESSIONALS to 5.0,
            ),
        RegulationItem.IMMIGRATION to
            mapOf(PopulationGroup.CAPITALISTS to 10.0, PopulationGroup.WORKERS to -8.0, PopulationGroup.FARMERS to 5.0),
    )

/** 중앙은행 독립성에 대한 집단별 입장. 양수면 독립성을 지지한다(시장 신뢰, 물가 우려), 음수면 저금리를 기대해 정부 통제를 반긴다. */
private val INDEPENDENCE_STANCE =
    mapOf(
        PopulationGroup.CAPITALISTS to 12.0,
        PopulationGroup.PROFESSIONALS to 8.0,
        PopulationGroup.OFFICE to 2.0,
        PopulationGroup.SELF_EMPLOYED to -2.0,
        PopulationGroup.WORKERS to -4.0,
        PopulationGroup.FARMERS to -4.0,
        PopulationGroup.PUBLIC_SECTOR to 0.0,
        PopulationGroup.RETIREES to 4.0,
    )
