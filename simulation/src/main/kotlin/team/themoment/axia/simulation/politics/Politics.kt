package team.themoment.axia.simulation.politics

import team.themoment.axia.simulation.country.BillCheck
import team.themoment.axia.simulation.country.BillReview
import team.themoment.axia.simulation.country.CountryModule
import team.themoment.axia.simulation.country.CountryNews
import team.themoment.axia.simulation.math.nextGaussian
import team.themoment.axia.simulation.nation.BudgetItem
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.nation.ElectoralRegion
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
    val oppositionBacking: Double,
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
    val conferenceBacking: Double = 0.0,
) {
    val organizationLobby: Double get() = organizationPositions.sumOf { it.points }

    val score: Double
        get() {
            val total =
                (
                    constituency + executiveBacking + oppositionBacking - fundingPenalty - institutionalResistance + organizationLobby +
                        conferenceBacking
                ).coerceIn(
                    0.0,
                    100.0,
                )
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
    CONFERENCE("양원 협의 · 절충안 재표결"),
    SIGNATURE("대통령 공포 대기"),
    OVERRIDE("거부권 재의결"),
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
    val sponsor: String? = null,
    val vetoRequested: Boolean = false,
    val originalChanges: List<PolicyChange>? = null,
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
    val foundation: PartyFoundation,
    val founded: LocalDate,
    val support: Double,
) {
    val identity: Identity get() = foundation.identity
}

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
    val legislatureDissolved: Boolean,
    val nextGovernmentDecision: LocalDate?,
    val pending: FiscalBill?,
    val lastDecision: FiscalBill?,
    val newParties: List<NewParty>,
    val takenPartyNames: Set<String>,
    val executiveCampaign: ExecutiveCampaign? = null,
    val coalitionPreference: CoalitionPreference? = null,
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
    private var regions: List<ElectoralRegion> = emptyList()
    private var lastDistricts: List<DistrictResult> = emptyList()
    private var lastDistrictVote: Map<String, Double> = emptyMap()
    private val draft = linkedMapOf<PolicyItem, PolicyChange>()
    private var negotiatingWith: String? = null
    private var pending: FiscalBill? = null
    private var lastDecision: FiscalBill? = null
    private var signatureNotice = false

    fun takeSignatureNotice(): Boolean = signatureNotice.also { signatureNotice = false }

    private var legislature: Legislature? = profile.legislature
    private val government: String = profile.initialGovernment

    /** 쿠데타로 군사평의회가 들어선 날. 의회와 선거가 정지된다. */
    private var juntaSince: LocalDate? = null
    private var rebelGovernment: String? = null
    val hasInterimGovernment: Boolean get() = rebelGovernment != null || juntaSince != null

    private var nextLegislative: LocalDate? = rules.legislature?.next
    private var nextExecutive: LocalDate? = rules.executive?.next
    private val executiveElection = ExecutiveElection(leadership, profile.regime, constitution.executiveTermLimit, random)
    private var lastLegislative: LocalDate? =
        if (legislature == null) null else rules.legislature?.let { it.next.minusYears(it.termYears.toLong()) }
    private var coalitionPreference: CoalitionPreference? = null
    private var earlyCalled = false
    private var lastElection: ElectionResult? = null
    private var projection: List<SeatProjection>? = null
    private val cases = mutableListOf<CourtCase>()
    private val newParties = EmergingParties(profile)

    private val confidence = constitution.confidence?.let { GovernmentConfidence(profile.id, it, constitution.upperHouse) }
    private var billLapsedOnDissolution = false

    private val news = mutableListOf<CountryNews>()

    /** 쌓인 정치 소식을 꺼낸다. 시뮬레이션이 매일 국가 소식으로 옮긴다. */
    fun drainNews(): List<CountryNews> = news.toList().also { news.clear() }

    fun updateConstituencies(
        latest: List<GroupSnapshot>,
        latestOpinion: Opinion,
        latestApproval: Double,
        electoralRegions: List<ElectoralRegion> = emptyList(),
        monthlyReviewDate: LocalDate? = null,
    ) {
        groups = latest
        opinion = latestOpinion
        approval = latestApproval
        regions = electoralRegions
        pending =
            pending?.let {
                it.copy(
                    support = assess(it.changes, it.support.rollCall?.negotiatingWith, it.sponsor, it.originalChanges != null),
                )
            }
        monthlyReviewDate?.let(::monthlyReview)
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
        if (bill.stage != BillStage.STALLED || bill.sponsor != null) return
        bill.changes.associateByTo(draft) { it.item }
        negotiatingWith = bill.support.rollCall?.negotiatingWith
        pending = null
    }

    fun introduce(date: LocalDate) {
        if (confidence?.dissolved == true || pending != null || draft.isEmpty()) return
        val changes = draft.values.toList()
        val negotiationDays = if (negotiatingWith == null) 0 else 14
        val days = 21 + negotiationDays + (35 * profile.legislativeConstraints).roundToLong()
        pending = FiscalBill(changes, date, BillStage.REVIEW, date.plusDays(days), assess(changes, negotiatingWith))
        clearDraft()
    }

    /** 국회에서 올라온 야당 법안의 공포 기한까지 거부 의사를 설정한다. */
    fun setOppositionVeto(veto: Boolean) {
        val bill = pending ?: return
        if (bill.stage == BillStage.SIGNATURE) pending = bill.copy(vetoRequested = veto)
    }

    private fun proposeOppositionBill(date: LocalDate) {
        if (confidence?.dissolved == true || pending != null || draft.isNotEmpty() || date.monthValue % 3 != 0) return
        val opposition = legislature?.groups?.filter { it.role == PartyRole.OPPOSITION && it.votingSeats > 0 }.orEmpty()
        if (opposition.isEmpty()) return
        val sponsor =
            opposition.sortedByDescending { it.votingSeats }.let { parties ->
                parties[Math.floorMod(date.year + date.monthValue / 3, minOf(3, parties.size))]
            }
        val topics = listOf(RegulationItem.LABOR, RegulationItem.ENVIRONMENT, RegulationItem.CIVIL_LIBERTIES)
        val topic = topics[Math.floorMod(sponsor.modelName.hashCode() + date.year + date.monthValue / 3, topics.size)]
        val from = regulations.target(topic)
        val preferred = if (sponsor.constituency == PopulationGroup.CAPITALISTS) 0.25 else 0.85
        val to = from + (preferred - from).coerceIn(-0.08, 0.08)
        if (abs(to - from) < 0.005) return
        val changes = listOf(PolicyChange(topic, from, to))
        pending =
            FiscalBill(changes, date, BillStage.REVIEW, date.plusDays(28), assess(changes, null, sponsor.name), sponsor = sponsor.name)
        news += CountryNews(sponsor.name, "${topic.label} 조정안을 발의했다.", major = false)
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
        confidence?.resetGovernment()
        billLapsedOnDissolution = false
        rebelGovernment = null
        dissolveForElection()
        val leader = leadership.installJunta(date)
        juntaSince = date
        newParties.clear()
        legislature = null
        nextLegislative = null
        nextExecutive = null
        executiveElection.cancel()
        coalitionPreference = null
        projection = null
        news += CountryNews("군부", "군부가 정권을 장악하고 의회를 해산했다. ${leader.name.josa("이", "가")} 국가재건평의회 의장에 올랐다.", major = true)
    }

    fun installRebelGovernment(
        rebels: String,
        date: LocalDate,
    ) {
        dissolveForElection()
        billLapsedOnDissolution = false
        val leader = leadership.installRebelGovernment(rebels, date)
        rebelGovernment = rebels
        juntaSince = null
        legislature = null
        nextLegislative = null
        nextExecutive = null
        executiveElection.cancel()
        coalitionPreference = null
        earlyCalled = false
        newParties.clear()
        cases.clear()
        projection = null
        newGovernment()
        news += CountryNews(rebels, "과도정부가 출범했다. ${leader.name.josa("이", "가")} 정부 수반이 되고 의회와 선거는 정지됐다.", major = true)
    }

    /** 조기 해산이 가능한 나라에서 직전 선거 후 1년이 지났고 정기 선거가 6개월 넘게 남았으면 6주 뒤 총선을 치른다. */
    fun preferCoalitionPartner(party: String?) {
        if (party == null) {
            coalitionPreference = null
            return
        }
        val chamber = legislature ?: return
        if (!rules.coalitionNegotiations || nextLegislative == null) return
        val lead = chamber.groups.firstOrNull { it.role == PartyRole.LEAD } ?: return
        if (chamber.groups.any { it.name == party && it.name != lead.name && it.role != PartyRole.OTHER && it.votingSeats > 0 }) {
            coalitionPreference = CoalitionPreference(lead.name, party)
        }
    }

    private fun takenPartyNames(): Set<String> = newParties.takenNames(legislature, executiveElection) + lastDistrictVote.keys

    fun renameParty(
        id: Int,
        name: String,
    ) {
        val chamber = legislature ?: return
        val change = newParties.rename(id, name, chamber, takenPartyNames()) ?: return
        legislature = chamber.withGroups(chamber.groups.map(change::group))
        executiveElection.renameParty(change)
        negotiatingWith = negotiatingWith?.let(change::label)
        coalitionPreference = coalitionPreference?.let { CoalitionPreference(change.label(it.formateur), change.label(it.partner)) }
        lastDistrictVote = lastDistrictVote.mapKeys { change.label(it.key) }
        lastDistricts = lastDistricts.map { it.copy(winners = it.winners.map(change::label)) }
        pending =
            pending?.let { bill ->
                val vote = bill.support.rollCall?.let(change::rollCall)
                bill.copy(sponsor = bill.sponsor?.let(change::label), support = bill.support.copy(rollCall = vote))
            }
        projection = project()
    }

    fun canCallEarlyElection(date: LocalDate): Boolean {
        val rule = rules.legislature ?: return false
        val next = nextLegislative ?: return false
        val last = lastLegislative ?: return false
        return rule.earlyDissolution && confidence?.caretaker != true && !earlyCalled && date >= last.plusYears(1) &&
            next > date.plusMonths(6)
    }

    fun callEarlyElection(date: LocalDate) {
        if (!canCallEarlyElection(date)) return
        nextLegislative = date.plusWeeks(EARLY_ELECTION_WEEKS)
        earlyCalled = true
    }

    /** 오늘 선거가 있으면 치르고 true를 돌려준다. 재정안 심의보다 먼저 호출한다. */
    fun holdElections(date: LocalDate): Boolean {
        val nominated = executiveElection.prepare(date, nextExecutive, rules.executive, legislature?.groups.orEmpty(), approval)
        if (nominated && rules.executive?.partyPrimary == true) news += CountryNews("선거", "당내 경선이 끝나 본선 후보가 확정됐다.", major = false)
        val legislativeDue = nextLegislative?.let { it <= date } == true
        val executiveDue = nextExecutive?.let { it <= date } == true
        if (!legislativeDue && !(executiveDue && profile.id == "TWN")) {
            legislature?.let { chamber ->
                confidence?.negotiate(chamber, date, nextLegislative, nextExecutive, leadership.current().headOfState)?.let {
                    applyConfidence(it, date)
                }
            }
        }
        if (!legislativeDue && !executiveDue) return false
        val before = snapshotGovernment()
        val wasEarly = earlyCalled
        var parties: List<PartyResult>? = null
        var lapsed = false
        if (legislativeDue) {
            lapsed = dissolveForElection() || billLapsedOnDissolution
            billLapsedOnDissolution = false
            parties = electLegislature(date)
            if (wasEarly) confidence?.recordDissolutionElection(date)
            val rule = requireNotNull(rules.legislature)
            lastLegislative = date
            nextLegislative = date.plusYears(rule.termYears.toLong())
            earlyCalled = false
        }
        val previousPresident = leadership.current().headOfState.id
        val executive = if (executiveDue) electExecutive(date) else null
        val formation =
            legislature?.let { chamber ->
                val newPresident = executive?.winner?.let { it.id != previousPresident } == true
                confidence?.afterElections(chamber, legislativeDue, newPresident, date)
            }
        formation?.let { applyConfidence(it, date) }
        if (executive !=
            null
        ) {
            nextExecutive =
                executiveElection.campaign?.runoffDate
                    ?: executive.campaign.firstDate.plusYears(requireNotNull(rules.executive).termYears.toLong())
        }
        lastElection =
            ElectionResult(
                date,
                wasEarly && legislativeDue,
                parties,
                executive,
                before,
                snapshotGovernment(),
                lapsed,
                if (legislativeDue) lastDistricts else emptyList(),
                if (legislativeDue) coalitionPreference?.let(::coalitionReport) else null,
            )
        if (legislativeDue) coalitionPreference = null
        if (lastElection?.governmentChanged == true && confidence?.caretaker != true) newGovernment()
        projection = project()
        pending =
            pending?.let {
                it.copy(
                    support = assess(it.changes, it.support.rollCall?.negotiatingWith, it.sponsor, it.originalChanges != null),
                )
            }
        return true
    }

    private fun coalitionReport(offer: CoalitionPreference): String {
        val chamber = requireNotNull(legislature)
        val lead = chamber.groups.firstOrNull { it.role == PartyRole.LEAD }
        val party = chamber.groups.firstOrNull { it.name == offer.partner }
        val result =
            when {
                lead?.name != offer.formateur -> "주도 정당 교체로 교섭 종료"
                lead.votingSeats >= chamber.required -> "주도 정당 단독 과반"
                party == null -> "상대 정당 원내 진입 실패"
                party.role == PartyRole.COALITION || party.role == PartyRole.SUPPLY -> party.role.label + " 합의"
                else -> "협상 결렬"
            }
        return "${offer.partner} · $result"
    }

    /** 의회 해산으로 심의 중인 재정안은 폐기되고, 같은 내용이 초안으로 돌아간다. */
    private fun dissolveForElection(): Boolean {
        val bill = pending ?: return false
        if (bill.sponsor == null) bill.changes.associateByTo(draft) { it.item }
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
        val founded =
            newParties.advance(
                date,
                requireNotNull(legislature).groups,
                opinion.identities,
                opinion.identityShares,
                random,
                ::takenPartyNames,
            )
        founded.forEach { party ->
            news += CountryNews(party.name, "${party.identity.label} 성향 유권자들의 불만 속에 ${party.name.josa("이", "가")} 창당했다.", major = true)
        }
    }

    private fun districtElectorate(): DistrictElectorate {
        val groups = legislature?.groups.orEmpty()
        val modelNames = (groups + newParties.contenders()).associate { it.name to it.modelName }
        return DistrictElectorate(regions, groups.associate { it.name to it.role }, approval, modelNames)
    }

    private fun electLegislature(date: LocalDate): List<PartyResult> {
        val rule = requireNotNull(rules.legislature)
        val chamber = legislature
        if (chamber == null) {
            val result = ElectionModel.openingElection(rule, profile.regime, districtElectorate()) { random.nextGaussian() * VOTE_NOISE }
            lastDistricts = result.districts
            lastDistrictVote = result.parties.associate { it.name to it.voteShare }
            legislature = result.legislature
            val lead = result.legislature.groups.firstOrNull { it.role == PartyRole.LEAD }
            if (lead != null) {
                val title = requireNotNull(rule.openingContest).headOfGovernmentTitle
                leadership.onGovernmentFormed(lead.name, date, reelected = false, title = title)
            }
            return result.parties
        }
        val shares = newParties.voteShares(voteShares(chamber.groups) { random.nextGaussian() * VOTE_NOISE })
        val seats = ElectionModel.legislativeSeats(shares, rule, districtElectorate())
        lastDistricts = seats.districts
        lastDistrictVote = if (lastDistricts.isEmpty()) emptyMap() else shares
        val contenders = chamber.groups + newParties.contenders()
        val previousRoles = contenders.associate { it.name to it.role }
        newParties.clear()
        val reseated = chamber.withGroups(ElectionModel.reseat(contenders, seats))
        val governed =
            if (confidence?.caretaker == true) {
                reseated
            } else {
                ElectionModel.afterLegislativeElection(
                    reseated,
                    rules,
                    previousRoles,
                    leadership.executiveIncumbent().party,
                    coalitionPreference,
                )
            }
        val leadBefore = previousRoles.entries.firstOrNull { it.value == PartyRole.LEAD }?.key
        val leadAfter = governed.groups.firstOrNull { it.role == PartyRole.LEAD }?.name
        legislature = governed
        if (leadAfter != null && rules.system != GovernmentSystem.PRESIDENTIAL && confidence?.caretaker != true) {
            leadership.onGovernmentFormed(leadAfter, date, reelected = leadAfter == leadBefore)
        }
        return contenders.map { party ->
            val before = chamber.groups.firstOrNull { it.name == party.name }?.seats ?: 0
            PartyResult(party.name, party.role, shares.getValue(party.name), before, seats.total(party.name))
        }
    }

    private fun electExecutive(date: LocalDate): ExecutiveResult {
        val rule = requireNotNull(rules.executive)
        val result = executiveElection.vote(date, rule, approval)
        result.winner?.party?.let { winner ->
            legislature = legislature?.let { ElectionModel.presidentialLegislature(it, rules, winner) }
        }
        return result
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
            identityMood = opinion.identities,
            identityShares = opinion.identityShares,
            lastVote = lastDistrictVote,
            noise = noise,
        )

    private fun project(): List<SeatProjection>? {
        val rule = rules.legislature ?: return null
        if (groups.isEmpty() || nextLegislative == null) return null
        val chamber = legislature
        if (chamber == null) {
            val result = ElectionModel.openingElection(rule, profile.regime, districtElectorate()) { 0.0 }
            return result.parties.map { SeatProjection(it.name, null, it.seatsAfter, it.voteShare) }
        }
        val shares = newParties.voteShares(voteShares(chamber.groups) { 0.0 })
        val seats = ElectionModel.legislativeSeats(shares, rule, districtElectorate())
        return (chamber.groups + newParties.contenders()).map {
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
        if (bill.stage == BillStage.SIGNATURE) {
            if (!bill.vetoRequested) return pass(bill, date)
            pending = bill.copy(stage = BillStage.OVERRIDE, decisionDate = date.plusDays(14))
            news += CountryNews("대통령", "${bill.sponsor} 법안의 재의를 요구했다.", major = true)
            return false
        }
        if (bill.stage == BillStage.OVERRIDE) return overrideVote(bill, date)
        if (bill.stage == BillStage.CONFERENCE) {
            if (bill.support.rollCall?.passes != true) {
                pending = null
                lastDecision = bill.copy(stage = BillStage.REJECTED, decisionDate = date, blockedBy = legislature?.name)
                return false
            }
            return finalReview(bill, date)
        }
        if (bill.stage == BillStage.FINAL_REVIEW) return finalReview(bill, date)
        val support = bill.support
        val voted = support.rollCall?.passes ?: (support.score >= 50.0)
        // 표결에서 졌어도 국가별 제도(예: 프랑스 49조 3항)로 강행할 수 있다.
        val forced = if (voted || bill.sponsor != null) null else module.forceThrough(review(bill.changes, support))
        forced?.let { news += CountryNews(it, "정부가 ${it.josa("으로", "로")} 표결 없이 법안을 채택했다.", major = true) }
        val passes = voted || forced != null
        val close = support.rollCall?.close ?: (support.score >= 40.0)
        when {
            passes && support.checks.isNotEmpty() -> {
                pending = bill.copy(stage = BillStage.FINAL_REVIEW, decisionDate = date.plusDays(FINAL_REVIEW_DAYS))
                return false
            }

            passes -> {
                return submitForSignature(bill, date)
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
        val upper = constitution.upperHouse

        fun upperVote(check: BillCheck) = upper != null && (check.body == upper.name || check.body.startsWith("상원"))
        val divisible = bill.changes.filter { it.item !is InstitutionItem && (it.item as? CountryPolicyItem)?.binary != true }
        if (divisible.isNotEmpty() && bill.originalChanges == null && !bill.delayed && failed.any(::upperVote) &&
            failed.none { !upperVote(it) && it.delayDays == 0 }
        ) {
            val compromise = divisible.map { it.copy(to = it.from + it.delta * 0.5) }
            pending =
                bill.copy(
                    changes = compromise,
                    originalChanges = bill.changes,
                    stage = BillStage.CONFERENCE,
                    decisionDate = date.plusDays(14),
                    support = assess(compromise, bill.support.rollCall?.negotiatingWith, bill.sponsor, conference = true),
                )
            news += CountryNews(requireNotNull(upper).name, "양원 협의회가 변경 폭을 절반으로 줄인 절충안을 냈다. 양원이 14일 뒤 재표결한다.", major = true)
            return false
        }
        val veto = failed.firstOrNull { it.delayDays == 0 }
        if (veto != null) {
            lastDecision = bill.copy(stage = BillStage.REJECTED, decisionDate = date, blockedBy = veto.body)
            pending = null
            news += CountryNews(veto.body, "${veto.body}에서 법안이 좌초됐다. ${veto.detail}", major = true)
            return false
        }
        val delay = failed.maxOfOrNull { it.delayDays } ?: 0
        if (delay > 0 && !bill.delayed) {
            pending = bill.copy(stage = BillStage.FINAL_REVIEW, decisionDate = date.plusDays(delay.toLong()), delayed = true)
            news += CountryNews(failed.first().body, "${failed.first().body.josa("이", "가")} 법안을 부결해 시행이 ${delay}일 늦춰졌다.", major = false)
            return false
        }
        return submitForSignature(bill, date)
    }

    private fun submitForSignature(
        bill: FiscalBill,
        date: LocalDate,
    ): Boolean {
        if (bill.sponsor == null || rules.system != GovernmentSystem.PRESIDENTIAL || profile.id in setOf("IDN", "IRN")) {
            return pass(bill, date)
        }
        signatureNotice = true
        pending = bill.copy(stage = BillStage.SIGNATURE, decisionDate = date.plusDays(14))
        news += CountryNews("의회", "${bill.sponsor} 법안이 대통령에게 이송됐다. 14일 안에 재의를 요구할 수 있다.", major = true)
        return false
    }

    private fun overrideVote(
        bill: FiscalBill,
        date: LocalDate,
    ): Boolean {
        val chamber = requireNotNull(legislature)

        // 출석은 표결권 의석 전원으로 간주한다. 대만은 행정원의 재의 요구를 대통령 결정으로 단순화한다.
        fun required(seats: Int): Int = if (profile.id in setOf("BRA", "TUR", "TWN")) seats / 2 + 1 else (2 * seats + 2) / 3
        val vote = requireNotNull(bill.support.rollCall)
        val house = constitution.upperHouse?.takeIf { it.power == ChamberPower.VETO }
        val upperPasses = house == null || house.forecast(bill.support.score, oppositionBill = true) >= required(house.seats)
        if (vote.yes >= required(chamber.votingSeats) && upperPasses) {
            news += CountryNews(chamber.name, "재의결로 대통령 거부권을 넘었다.", major = true)
            return pass(bill, date)
        }
        pending = null
        lastDecision = bill.copy(stage = BillStage.REJECTED, decisionDate = date, blockedBy = "대통령 거부권 · 재의결 미달")
        return false
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

    private fun monthlyReview(date: LocalDate) {
        proposeOppositionBill(date)
        leadership.checkMortality(date, random)?.let { news += CountryNews("정부", it, major = true) }
        considerNewParties(date)
        val chamber = legislature ?: return
        if (earlyCalled) return
        val mood = groups.associate { it.group to it.satisfaction }
        val heads = leadership.current()
        confidence
            ?.review(
                chamber,
                approval,
                { it.supportMood(mood, opinion.identities, approval) },
                (heads.headOfGovernment ?: heads.headOfState).id,
                date,
                random,
            )?.let { applyConfidence(it, date) }
    }

    private fun applyConfidence(
        decision: ConfidenceDecision,
        date: LocalDate,
    ) {
        val changed = decision.chamber != legislature
        legislature = decision.chamber
        val office =
            if (decision.successor ||
                decision.acting
            ) {
                requireNotNull(confidence).appoint(decision.chamber, leadership, date, decision.acting)
            } else {
                null
            }
        if (decision.successor) newGovernment()
        decision.election?.let { poll ->
            billLapsedOnDissolution = dissolveForElection()
            nextLegislative = poll
            earlyCalled = true
        }
        if (changed) {
            projection = project()
            pending =
                pending?.let {
                    it.copy(
                        support =
                            assess(
                                it.changes,
                                it.support.rollCall?.negotiatingWith,
                                it.sponsor,
                                it.originalChanges != null,
                            ),
                    )
                }
        }
        (decision.news + listOfNotNull(office)).forEach { news += CountryNews(decision.chamber.name, it, major = true) }
    }

    /** 지금 정부 표기. 지도자나 군사평의회가 바뀌면 함께 바뀐다. */
    private fun currentConstitution(): ConstitutionProfile {
        val veto = constitution.presidentialVeto ?: return constitution
        val president = leadership.executiveIncumbent()
        val holder = president.party?.let { veto.copy(holder = president.name, party = it) }
        return constitution.copy(presidentialVeto = holder)
    }

    private fun snapshotGovernment(): String =
        when {
            rebelGovernment != null -> {
                "$rebelGovernment 과도정부 · ${leadership.current().leader.name}"
            }

            juntaSince != null -> {
                "군사평의회 · ${leadership.current().leader.name}"
            }

            confidence?.caretaker == true -> {
                "관리정부 · ${(leadership.current().headOfGovernment ?: leadership.current().headOfState).name}"
            }

            else -> {
                leadership.governmentLabel(
                    government,
                    rules.system == GovernmentSystem.SEMI_PRESIDENTIAL || profile.id in setOf("EGY", "TWN"),
                )
            }
        }

    /** 정권이 바뀌면 이탈·불신임 기준을 새 정부 출범 시점으로 다시 잡는다. */
    private fun newGovernment() {
        confidence?.resetGovernment()
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
                    rebelGovernment != null -> "과도정부 평의회"
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
                    suspended =
                        when {
                            rebelGovernment != null -> "과도정부 아래 선거 정지"
                            juntaSince != null -> "군사정권 아래 선거 정지"
                            else -> rules.suspended
                        },
                ),
            projection = projection,
            lastElection = lastElection,
            leadership = leadership.current(),
            newParties = newParties.snapshot(),
            takenPartyNames = takenPartyNames(),
            executiveCampaign = executiveElection.campaign,
            coalitionPreference = coalitionPreference,
            constitution = ConstitutionSnapshot(currentConstitution(), cases.toList()),
            draft = draft.toMap(),
            legislatureDissolved = confidence?.dissolved == true,
            nextGovernmentDecision = confidence?.nextBallot,
            draftSupport = if (draft.isEmpty()) null else assess(draft.values, negotiatingWith),
            pending = pending,
            lastDecision = lastDecision,
        )

    private fun assess(
        changes: Collection<PolicyChange>,
        partner: String?,
        sponsor: String? = null,
        conference: Boolean = false,
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
                    is TaxKind -> -change.delta * budget.taxBase(item)
                    is SpendingKind -> change.delta
                    is CountryPolicyItem -> change.delta * module.estimatedSpendingPerUnit(item)
                    is InstitutionItem, is RegulationItem -> 0.0
                }
            }
        val executiveBacking = if (sponsor != null) 0.0 else 4.0 + 12.0 * (1.0 - profile.legislativeConstraints)
        // 정부에 대한 낮은 지지율은 야당 발의 안건에는 반대 방향의 정치 동원 효과를 낸다.
        val oppositionBacking = if (sponsor == null) 0.0 else 1.2 * (50.0 - approval)
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
        val compromiseBacking = if (conference) 5.0 else 0.0
        val net = compromiseBacking + constituency + executiveBacking + oppositionBacking - fundingPenalty - institutionalResistance + lobby
        val rollCall = legislature?.forecast(net, constituency, scores, partner, sponsor, identityScores, districtOpinion())
        val base =
            BillSupport(
                groupScores = scores,
                identityScores = identityScores,
                constituency = constituency,
                executiveBacking = executiveBacking,
                oppositionBacking = oppositionBacking,
                fundingPenalty = fundingPenalty,
                institutionalResistance = institutionalResistance,
                organizationPositions = positions,
                conferenceBacking = compromiseBacking,
                rollCall = rollCall,
            )
        val withDeficit = base.copy(addedDeficit = addedDeficit)
        return withDeficit.copy(checks = checks(changes.toList(), withDeficit, sponsor != null))
    }

    /** 모형 지역구 당선자는 선거구 여론에 반응하며 비례·유보 의석은 당론 표결을 한다. */
    private fun districtOpinion(): Map<String, List<Double>> {
        if (lastDistricts.isEmpty()) return emptyMap()
        val local = regions.associateBy { it.id }
        val modelNames = legislature?.groups.orEmpty().associate { it.name to it.modelName }
        // 당내 독자투표 성향 순서를 지역명 정렬에서 분리해 특정 지역 의원이 항상 찬성하지 않게 한다.
        val ordered =
            lastDistricts
                .flatMap { district ->
                    val difference = (local[district.regionId]?.approval ?: approval) - approval
                    district.winners.mapIndexed { index, party ->
                        Triple(party, DistrictElectorate.localAffinity(district.regionId, index, modelNames[party] ?: party), difference)
                    }
                }.sortedBy { it.second }
        return ordered.groupBy({ it.first }, { it.third })
    }

    private fun review(
        changes: List<PolicyChange>,
        support: BillSupport,
        oppositionBill: Boolean = false,
    ): BillReview {
        val house = constitution.upperHouse
        return BillReview(
            changes = changes,
            score = support.score,
            upperHouseYes = house?.forecast(support.score, oppositionBill),
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
        oppositionBill: Boolean,
    ): List<BillCheck> {
        if (rebelGovernment != null || juntaSince != null) return emptyList()
        val house = constitution.upperHouse
        val upper = house?.let { ConstitutionalReview.upperHouseCheck(it, support.score, oppositionBill) }
        val veto = currentConstitution().presidentialVeto?.let { ConstitutionalReview.vetoCheck(it, legislature, support) }
        val special = module.billChecks(review(changes, support, oppositionBill))
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
private const val MIN_LOBBY_EFFECT = 1.0
private const val LOBBY_PER_INFLUENCE = 1.5
private const val MAX_LOBBY = 4.0

/** 견제가 가장 강한 나라(입법 제약 1)에서 독립 기관 하나를 정부 통제로 돌릴 때 깎이는 점수. */
private const val INSTITUTIONAL_RESISTANCE = 30.0
private const val VOTE_NOISE = 0.1

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
