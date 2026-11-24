package team.themoment.axia.simulation.politics

import team.themoment.axia.simulation.country.CountryNews
import team.themoment.axia.simulation.economy.EconomyIndicators
import team.themoment.axia.simulation.math.nextGaussian
import team.themoment.axia.simulation.nation.PopulationGroup
import team.themoment.axia.simulation.people.Leadership
import team.themoment.axia.simulation.people.LeadershipState
import team.themoment.axia.simulation.people.PeopleRegistry
import team.themoment.axia.simulation.people.Person
import team.themoment.axia.simulation.people.retires
import team.themoment.axia.simulation.society.Identity
import team.themoment.axia.simulation.text.josa
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** AI 국가 한 곳의 정치 요약. */
data class CountryPoliticsSnapshot(
    val countryId: String,
    val regime: Regime,
    val government: String,
    val leadership: Leadership,
    val approval: Double,
    val legislature: Legislature?,
    val nextElection: LocalDate?,
    val lastElection: ElectionResult?,
    val pendingBill: AiFiscalBill?,
    val lastBill: AiFiscalBill?,
    val executiveCampaign: ExecutiveCampaign? = null,
    val presidentElect: PresidentElect? = null,
    val legislativeRunoff: LegislativeRunoff? = null,
    val listRegistration: ListRegistration? = null,
    val seimasResolution: SeimasResolution? = null,
    val newHouseOpens: LocalDate? = null,
    val nextUpperRenewal: LocalDate? = null,
    val lastUpperRenewal: UpperRenewalResult? = null,
    val upperHouse: UpperHouse? = null,
    val newParties: List<NewParty> = emptyList(),
    val takenPartyNames: Set<String> = emptySet(),
    val appointments: AppointmentSnapshot? = null,
    val indirectPresident: IndirectPresidentSnapshot? = null,
)

/** 세계에서 일어난 일 한 줄. */
data class WorldNews(
    val date: LocalDate,
    val countryId: String,
    val source: String,
    val text: String,
    val major: Boolean,
)

/**
 * 플레이어가 아닌 플레이 가능 국가의 월간 정치. 플레이어 국가와 같은 선거·의석·정부 구성 모델을 쓰고,
 * 지지율은 인구 집단 대신 거시 지표(실업, 물가, 경기)의 시작 대비 변화와 집권 피로로 정한다.
 */
internal class AiCountryPolitics(
    val countryId: String,
    private val profile: PoliticalProfile,
    private var constitution: ConstitutionProfile,
    people: PeopleRegistry,
    start: LocalDate,
    private val random: Random,
) {
    private var sittingDate = start
    private val rules = profile.elections
    private val leadership =
        LeadershipState(countryId, people, start, rules.executive?.title, parliamentary = rules.system == GovernmentSystem.PARLIAMENTARY)
    private var legislature: Legislature? = profile.legislature
    private var seimasResolution: SeimasResolution? = null
    private var lithuanianCount: LithuanianSeimas.Count? = null
    private var iranianCount: IranianAssembly.Count? = null
    private var frenchCount: FrenchAssembly.Count? = null
    private var legislativeRunoff: LegislativeRunoff? = null
    private val frenchAssemblyDissolved: Boolean get() =
        rules.legislature?.frenchAssembly == true &&
            (legislativeRunoff != null || earlyCalled)
    private val listFiling = ListFiling.forSystem(rules.legislature?.mixedSystem)
    private val firstPollingDay get() = legislativeRunoff?.firstRound ?: nextLegislative
    private val filingDeadline get() = listFiling?.deadline(firstPollingDay)

    private fun listContenders() = electoralParties(legislature?.groups.orEmpty()) + newParties.contenders(filingDeadline)

    private var nextLegislative: LocalDate? = rules.legislature?.next
    private var nextExecutive: LocalDate? = rules.executive?.next
    private val executiveElection = ExecutiveElection(countryId, leadership, profile.regime, constitution.executiveTermLimit, random)
    private var lastElection: ElectionResult? = null
    private val upperRenewals = UpperRenewals.forCountry(countryId)
    private val senate = if (countryId == "USA") UsSenate() else null
    private var outgoingDistricts: List<DistrictResult>? = null
    private var canadianElectionMap = PluralityMap.CAN
    private var incomingHouse: Pair<LocalDate, Legislature>? = null
    private var houseDistricts: List<DistrictResult> = emptyList()
    private var lastListSeats = emptyMap<String, Map<String, Int>>()
    private var lastListVoteShares = emptyMap<String, Double>()
    private var lastDistrictVote =
        when (countryId) {
            "ITA" -> JointListNominations.italianBaseline
            "DEU" -> GermanBundestag.listBaseline
            "GBR" -> PluralityDistricts.baseline(PluralityMap.GBR)
            "CAN" -> PluralityDistricts.baseline(PluralityMap.CAN_2013)
            "IND" -> PluralityDistricts.baseline(PluralityMap.IND)
            "AUS" -> AustralianHouse.baseline
            "TWN" -> TaiwanBallots.baseline
            else -> ProportionalElections.historicalVote(rules.legislature?.proportionalSystem)
        }
    private val legislation = AiLegislation()
    private val mandates = ParliamentaryMandates.forCountry(countryId)
    private val pakistanReturns =
        rules.legislature?.takeIf { it.pakistanAssembly }?.let { rule ->
            PakistanReturns(requireNotNull(rule.openingContest).parties.filter { it.other }.mapTo(mutableSetOf()) { it.name })
        }
    private val indirectPresident =
        constitution.indirectPresident?.let {
            IndirectPresidency(it, leadership, random) { upperRenewals?.electedDelegation() }
        }
    private val appointments = constitution.appointments?.let { InstitutionAppointments(it, leadership, random) }
    private val confidence =
        constitution.confidence?.let {
            GovernmentConfidence(
                countryId,
                it,
                constitution.upperHouse,
                if (countryId ==
                    "PAK"
                ) {
                    PakistanSuccession(leadership, random)
                } else {
                    null
                },
            )
        }
    private var earlyCalled = false
    private var billLapsedOnDissolution = false
    private val newParties = EmergingParties(profile)
    private var identityMood = Identity.entries.associateWith { 50.0 }
    private val identityShares = identityMood.keys.associateWith { 0.25 }

    private var monthsInOffice = 0

    /** 지지율이 돌아가는 기준. 시작 정부는 2024년 1월 여론조사, 새 정부는 50이다. */
    private var base = InitialApproval.forCountry(countryId).approval
    var approval = base
        private set
    private var initial: EconomyIndicators? = null

    init {
        renewUpper(sittingDate, false)
    }

    /** 사건이 지지율을 바꾼다. */
    fun shiftApproval(points: Double) {
        approval = (approval + points).coerceIn(5.0, 95.0)
    }

    /** 쿠데타로 군부가 정권을 잡는다. 선거가 정지된다. */
    fun coup(date: LocalDate) {
        rebelGovernment = null
        legislation.dissolve()
        confidence?.resetGovernment()
        earlyCalled = false
        billLapsedOnDissolution = false
        leadership.installJunta(date)
        juntaSince = date
        legislature = null
        listFiling?.finish()
        nextLegislative = null
        nextExecutive = null
        executiveElection.cancel()
        incomingHouse = null
        outgoingDistricts = null
        frenchCount = null
        iranianCount = null
        lithuanianCount = null
        legislativeRunoff = null
        newParties.clear()
    }

    fun installRebelGovernment(
        rebels: String,
        date: LocalDate,
    ) {
        confidence?.resetGovernment()
        earlyCalled = false
        billLapsedOnDissolution = false
        leadership.installRebelGovernment(rebels, date)
        rebelGovernment = rebels
        juntaSince = null
        legislature = null
        listFiling?.finish()
        nextLegislative = null
        nextExecutive = null
        executiveElection.cancel()
        incomingHouse = null
        outgoingDistricts = null
        frenchCount = null
        iranianCount = null
        lithuanianCount = null
        legislativeRunoff = null
        newParties.clear()
        legislation.dissolve()
        monthsInOffice = 0
        base = BASELINE
        approval = BASELINE
    }

    private var juntaSince: LocalDate? = null
    private var rebelGovernment: String? = null
    val hasInterimGovernment: Boolean get() = rebelGovernment != null || juntaSince != null

    fun advanceMonth(
        indicators: EconomyIndicators,
        date: LocalDate,
        enact: (String, Double, Double) -> Unit,
    ): List<WorldNews> {
        val departure = executiveElection.checkMortality(date, random, legislature)
        nextExecutive = executiveElection.scheduledPoll(nextExecutive)
        val start = initial ?: indicators.also { initial = it }
        monthsInOffice++
        val damping = if (profile.regime.autocratic) AUTOCRATIC_DAMPING else 1.0
        val jobs = (-JOB_WEIGHT * (indicators.unemployment - start.unemployment)).coerceIn(-25.0, 8.0)
        val comfort = maxOf(start.inflation, INFLATION_COMFORT)
        val prices = (-PRICE_WEIGHT * (indicators.inflation - comfort)).coerceIn(-20.0, 3.0)
        val growth = (GROWTH_WEIGHT * (indicators.growth - start.growth)).coerceIn(-12.0, 8.0)
        val fatigue = -(monthsInOffice * FATIGUE_PER_MONTH).coerceAtMost(MAX_FATIGUE)
        val target = base + damping * (jobs + prices + growth + fatigue)
        approval = (approval + (target - approval) * SMOOTHING + random.nextGaussian() * APPROVAL_NOISE).coerceIn(5.0, 95.0)
        // AI 국가에는 유권자 개별 표본이 없어 경기·물가·고용 노출로 성향별 여론을 추정한다.
        identityMood =
            identityMood.keys.associateWith { identity ->
                val pressure =
                    when (identity) {
                        Identity.YOUTH, Identity.UNIONISTS -> jobs
                        Identity.PARENTS, Identity.EGALITARIANS -> prices
                        Identity.FREE_MARKET -> growth
                        else -> 0.0
                    }
                (approval + pressure * 0.45).coerceIn(0.0, 100.0)
            }
        val fresh =
            legislature
                ?.takeIf {
                    nextLegislative != null && legislativeRunoff == null && !frenchAssemblyDissolved
                }?.let { newParties.advance(date, it.groups, identityMood, identityShares, random, ::takenPartyNames) }
                .orEmpty()
        val mandateNews =
            if (frenchAssemblyDissolved || earlyCalled ||
                confidence?.legislationSuspended == true
            ) {
                emptyList()
            } else {
                legislature
                    ?.let { chamber ->
                        mandates
                            ?.review(
                                chamber,
                                date,
                                incomingHouse?.first ?: nextLegislative,
                                approval,
                                rules.legislature,
                                random,
                                outgoingDistricts ?: houseDistricts,
                            )?.let { applyMandates(it, date) }
                    }.orEmpty()
            }
        val chamber = legislature
        val heads = leadership.current()
        val change =
            if (chamber == null || frenchAssemblyDissolved) {
                null
            } else {
                confidence?.review(
                    chamber,
                    approval,
                    { it.supportMood(emptyMap(), identityMood, approval) },
                    (heads.headOfGovernment ?: heads.headOfState).id,
                    date,
                    random,
                    nextExecutive,
                )
            }
        val confidenceNews = change?.let { applyConfidence(it, date) }.orEmpty()
        val seimasVote =
            if (countryId == "LTU" && chamber != null && !earlyCalled && legislativeRunoff == null && incomingHouse == null &&
                confidence?.caretaker != true && monthsInOffice >= 12 && (approval <= 35 || approval >= 65) &&
                nextLegislative?.let { it > date.plusMonths(6) } == true &&
                seimasResolution?.date?.let { date < it.plusMonths(6) } != true && random.nextDouble() < 0.15
            ) {
                LithuanianSeimas.resolution(chamber, approval, date).also { resolution ->
                    seimasResolution = resolution
                    if (resolution.vote.passes) {
                        nextLegislative = earlyHousePoll(countryId, date, requireNotNull(constitution.confidence?.electionDays))
                        earlyCalled = true
                    }
                }
            } else {
                null
            }
        return mandateNews + confidenceNews +
            fresh.map { WorldNews(date, countryId, it.name, "${it.identity.label} 성향의 신당 ${it.name} 창당", true) } +
            listOfNotNull(
                seimasVote?.let { WorldNews(date, countryId, "세이마스", it.report, true) },
                departure?.let { WorldNews(date, countryId, "지도자 교체", it, major = true) },
                if (frenchAssemblyDissolved || confidence?.legislationSuspended == true ||
                    (confidence?.routineBusiness == true && legislation.pending == null)
                ) {
                    null
                } else {
                    legislation.advance(
                        date,
                        countryId,
                        government(),
                        legislature,
                        constitution.upperHouse?.session(legislature, date),
                        indicators,
                        approval,
                        enact,
                    )
                },
            )
    }

    private fun takenPartyNames(): Set<String> =
        newParties.takenNames(legislature, executiveElection) + lastDistrictVote.keys +
            (if (countryId == "KOR") KoreanListNominations.protectedNames else emptySet()) +
            listFiling
                ?.filed(firstPollingDay)
                ?.lists
                .orEmpty()
                .map { it.name } +
            constitution.upperHouse
                ?.groups
                .orEmpty()
                .map { it.name }

    fun renamePerson(person: Person) {
        executiveElection.renamePerson(person)
        indirectPresident?.renamePerson(person)
    }

    fun renameParty(
        id: Int,
        name: String,
    ) {
        val chamber = legislature ?: return
        val change = newParties.rename(id, name, chamber, takenPartyNames()) ?: return
        legislature = chamber.renamed(change)
        listFiling?.rename(change)
        executiveElection.renameParty(change)
        senate?.renameParty(change)
        upperRenewals?.renameParty(change)
        constitution.upperHouse?.takeIf { upper -> upper.groups.any { it.name == change.old } }?.let { upper ->
            val renamed = change.upper(upper, requireNotNull(legislature))
            constitution = constitution.copy(upperHouse = renamed)
            confidence?.upper = renamed
        }
        indirectPresident?.renameParty(change)
        frenchCount = frenchCount?.renamed(change)
        iranianCount = iranianCount?.renamed(change)
        lithuanianCount = lithuanianCount?.renamed(change)
        legislativeRunoff?.firstRound?.let { first -> lithuanianCount?.let { legislativeRunoff = it.runoff(first) } }
        incomingHouse = incomingHouse?.let { (whenSeated, chamber) -> whenSeated to chamber.renamed(change) }
        legislation.renameParty(change)
        houseDistricts = houseDistricts.map { it.copy(winners = it.winners.map(change::label)) }
        outgoingDistricts = outgoingDistricts?.map { it.copy(winners = it.winners.map(change::label)) }
        lastDistrictVote = lastDistrictVote.mapKeys { change.label(it.key) }
    }

    private fun applyMandates(
        change: MandateChange,
        date: LocalDate,
    ): List<WorldNews> {
        legislature = change.chamber
        change.districts?.let { if (incomingHouse == null) houseDistricts = it else outgoingDistricts = it }
        return change.news.map { WorldNews(date, countryId, change.chamber.name, it, false) }
    }

    private fun applyConfidence(
        decision: ConfidenceDecision,
        date: LocalDate,
    ): List<WorldNews> {
        legislature = decision.chamber
        val office =
            if (decision.successor ||
                decision.acting
            ) {
                requireNotNull(confidence).appoint(decision.chamber, leadership, date, decision.acting)
            } else {
                null
            }
        if (decision.successor) renewMandate()
        decision.election?.let { poll ->
            if (countryId != "LTU") billLapsedOnDissolution = legislation.dissolve()
            if (countryId == "CAN") canadianElectionMap = PluralityDistricts.canadaOrder(date)
            nextLegislative = poll
            earlyCalled = decision.early
        }
        return (decision.news + listOfNotNull(office)).map { WorldNews(date, countryId, decision.chamber.name, it, major = true) }
    }

    private fun renewMandate() {
        monthsInOffice = 0
        base = BASELINE
        approval = BASELINE + HONEYMOON
    }

    private fun renewUpper(
        date: LocalDate,
        lowerElected: Boolean,
    ): WorldNews? {
        val renewal = upperRenewals ?: return null
        val previous = constitution.upperHouse ?: return null
        if (hasInterimGovernment) return null
        val changed = renewal.advance(date, previous, legislature, lowerElected, approval) ?: return null
        constitution = constitution.copy(upperHouse = changed)
        confidence?.upper = changed
        val result = renewal.last?.takeIf { it.date == date } ?: return null
        return WorldNews(date, countryId, changed.name, "${result.method} · 재적 ${changed.filledSeats}명", true)
    }

    /** 오늘 선거가 있으면 치르고 소식을 돌려준다. */
    fun holdElections(date: LocalDate): List<WorldNews> {
        sittingDate = date
        if (!hasInterimGovernment) {
            senate?.seat(date, constitution.upperHouse)?.let { upper -> constitution = constitution.copy(upperHouse = upper) }
        }
        val appointmentsNews = mutableListOf<WorldNews>()
        incomingHouse?.takeIf { date >= it.first }?.let {
            if (legislation.dissolve()) {
                appointmentsNews +=
                    WorldNews(date, countryId, it.second.name, "새 의회 임기가 시작돼 전기 의회의 미결 법안이 폐기됐다.", false)
            }
            val newHouse = it.second
            legislature =
                executiveElection.governingParty?.let { party -> ElectionModel.presidentialLegislature(newHouse, rules, party) } ?: newHouse
            incomingHouse = null
            outgoingDistricts = null
            val seated = requireNotNull(legislature)
            confidence?.afterElections(seated, true, false, date)?.let { decision -> appointmentsNews += applyConfidence(decision, date) }
        }
        if (!hasInterimGovernment && pakistanReturns != null) {
            legislature?.let { chamber ->
                pakistanReturns.advance(date, chamber, random)?.let { filing ->
                    legislature = filing.chamber
                    lastElection = lastElection?.copy(pakistanReturn = filing.report)
                    appointmentsNews += WorldNews(date, countryId, "선거관리위원회", filing.news, true)
                }
            }
        }

        fun holdPresidentialCollege() {
            if (hasInterimGovernment) return
            indirectPresident
                ?.advance(
                    date,
                    legislature,
                    constitution.upperHouse?.session(legislature, date),
                    incomingHouse?.first ?: nextLegislative,
                    confidence?.dissolved == true || earlyCalled || incomingHouse != null,
                )?.let {
                    appointmentsNews += WorldNews(date, countryId, "대통령 간선", it, true)
                }
        }
        if (!hasInterimGovernment) {
            appointments?.callIranianElection(date)?.let { poll ->
                executiveElection.cancel()
                nextExecutive = poll
                appointmentsNews += WorldNews(date, countryId, "대통령 보궐선거", "의회의장·사법부 수장·권한대행이 대통령 보궐선거를 ${poll}로 정했다.", true)
            }
            appointments?.advance(date, legislature, approval)?.let { appointmentsNews += it.toWorld(date, countryId) }
        }
        val oldPresident = leadership.executiveIncumbent().id
        val inauguration = executiveElection.inaugurate(date)
        if (!hasInterimGovernment) {
            executiveElection.advanceDeputy(date, legislature, constitution.upperHouse, approval)?.let {
                appointmentsNews += WorldNews(date, countryId, "부통령", it, true)
            }
        }
        inauguration?.let { elected ->
            elected.governingParty?.let { party ->
                legislature =
                    legislature?.let { ElectionModel.presidentialLegislature(it, rules, party) }
            }
            if (oldPresident != elected.person.id && rules.system == GovernmentSystem.PRESIDENTIAL) renewMandate()
            legislature
                ?.let { chamber -> confidence?.afterElections(chamber, false, oldPresident != elected.person.id, date, true) }
                ?.let { decision -> appointmentsNews += applyConfidence(decision, date) }
        }
        nextExecutive = executiveElection.scheduledPoll(nextExecutive)
        val nominated = executiveElection.prepare(date, nextExecutive, rules.executive, legislature?.groups.orEmpty(), approval)
        val nominations =
            if (nominated &&
                rules.executive?.partyPrimary == true
            ) {
                listOf(WorldNews(date, countryId, "선거", "당내 경선에서 본선 후보가 확정됐다.", false))
            } else {
                emptyList()
            }
        val inaugurationNews =
            inauguration
                ?.let {
                    listOf(
                        WorldNews(date, countryId, "취임", "${it.person.name} 대통령이 취임했다.", true),
                    )
                }.orEmpty()
        val registered = listFiling?.advance(date, firstPollingDay, ::listContenders)
        if (registered != null) appointmentsNews += WorldNews(date, countryId, "총선", "후보 명부 ${registered.lists.size}건의 등록이 확정됐다.", false)
        var legislativeDue = nextLegislative?.let { it <= date } == true
        var firstRoundNews: String? = null
        rules.legislature?.takeIf { it.mixedSystem == MixedSystem.LTU && legislativeDue && lithuanianCount == null }?.let { rule ->
            val chamber = requireNotNull(legislature)
            val contenders = listContenders()
            val votes = newParties.voteShares(voteShares(chamber.groups, rule.electoralSystem), filingDeadline)
            val round =
                LithuanianSeimas.count(
                    MixedVotes(votes, approval = approval, modelNames = contenders.associate { it.name to it.modelName }),
                    listFiling?.filed(firstPollingDay),
                )
            lithuanianCount = round
            round.runoff(date)?.let { runoff ->
                legislativeRunoff = runoff
                nextLegislative = runoff.nextRound
                legislativeDue = false
                firstRoundNews = "전국 명부와 지역구에서 ${runoff.settled}석이 확정됐다. ${runoff.contested}개 선거구는 ${runoff.nextRound}에 결선 투표한다."
            }
        }
        rules.legislature?.takeIf { it.iranianAssembly && legislativeDue && iranianCount == null }?.let { rule ->
            val chamber = requireNotNull(legislature)
            val round = IranianAssembly.count(MixedVotes(newParties.voteShares(voteShares(chamber.groups, rule.electoralSystem))), approval)
            iranianCount = round
            if (round.settled < rule.seats) {
                val runoff = IranianAssembly.secondRound(date)
                legislativeRunoff = LegislativeRunoff(date, runoff, round.settled, rule.seats - round.settled)
                nextLegislative = runoff
                legislativeDue = false
                firstRoundNews = "총선에서 ${round.settled}석이 확정됐다. ${rule.seats - round.settled}석은 $runoff 결선에서 선출한다."
            }
        }
        rules.legislature?.takeIf { it.frenchAssembly && legislativeDue && frenchCount == null }?.let {
            val chamber = requireNotNull(legislature)
            val contenders = listContenders()
            val round =
                FrenchAssembly.count(
                    newParties.voteShares(voteShares(chamber.groups, it.electoralSystem)),
                    roles =
                        chamber.groups.associate { party ->
                            party.name to
                                party.role
                        },
                    approval = approval,
                    modelNames = contenders.associate { party -> party.name to party.modelName },
                )
            frenchCount = round
            if (round.settled < it.seats) {
                val runoff = date.plusWeeks(1)
                legislativeRunoff = LegislativeRunoff(date, runoff, round.settled, it.seats - round.settled)
                nextLegislative = runoff
                billLapsedOnDissolution = legislation.dissolve() || billLapsedOnDissolution
                legislativeDue = false
                firstRoundNews = "총선 1차 투표에서 ${round.settled}석이 확정됐다. 나머지 ${it.seats - round.settled}곳은 ${runoff}에 다시 투표한다."
            }
        }
        firstRoundNews?.let { appointmentsNews += WorldNews(date, countryId, "총선", it, true) }
        val mandateNews =
            if (frenchAssemblyDissolved || legislativeDue || earlyCalled ||
                confidence?.legislationSuspended == true
            ) {
                emptyList()
            } else {
                legislature
                    ?.let { chamber ->
                        mandates
                            ?.fill(
                                chamber,
                                date,
                                { voteShares(chamber.groups, requireNotNull(rules.legislature).electoralSystem) },
                                random,
                                outgoingDistricts ?: houseDistricts,
                                date == nextExecutive,
                            )?.let { applyMandates(it, date) }
                    }.orEmpty()
            }
        if (!legislativeDue) {
            renewUpper(date, false)?.let(appointmentsNews::add)
            holdPresidentialCollege()
        }
        val executiveDue = nextExecutive?.let { it <= date } == true
        val transition =
            if (frenchAssemblyDissolved || legislativeDue || (executiveDue && countryId in setOf("TWN", "RUS", "RWA"))) {
                emptyList()
            } else {
                legislature
                    ?.let { chamber ->
                        confidence
                            ?.negotiate(
                                chamber,
                                date,
                                nextLegislative,
                                nextExecutive,
                                leadership.current().headOfState,
                                leadership.current().headOfGovernment?.id,
                            )?.let {
                                applyConfidence(it, date)
                            }
                    }.orEmpty()
            }
        if (!legislativeDue && !executiveDue) return mandateNews + nominations + transition + inaugurationNews + appointmentsNews
        val before = government()
        val wasEarly = earlyCalled && legislativeDue
        val billLapsed =
            legislativeDue && ((newHouseTerm(countryId, date, earlyCalled) == date && legislation.dissolve()) || billLapsedOnDissolution)
        val registeredLists = listFiling?.filed(firstPollingDay)?.takeIf { legislativeDue }
        var parties: List<PartyResult>? = null
        if (legislativeDue) {
            parties = electLegislature(date)
            listFiling?.finish()
            renewUpper(date, true)?.let(appointmentsNews::add)
            val firstPollingDate = legislativeRunoff?.firstRound ?: date
            legislativeRunoff = null
            frenchCount = null
            iranianCount = null
            lithuanianCount = null
            if (earlyCalled) confidence?.recordDissolutionElection(date)
            billLapsedOnDissolution = false
            earlyCalled = false
            nextLegislative = requireNotNull(rules.legislature).nextRegular(firstPollingDate, countryId)
        }
        val previousPresident = leadership.current().headOfState.id
        val executive = if (executiveDue) electExecutive(date) else null
        if (executive?.winner != null) appointments?.presidentSeated(date)?.let { appointmentsNews += it.toWorld(date, countryId) }
        val formation =
            legislature?.let { chamber ->
                val seated = executive?.winner?.takeIf { executiveElection.presidentElect == null }
                confidence?.afterElections(
                    chamber,
                    legislativeDue && incomingHouse == null,
                    seated?.let { it.id != previousPresident } == true,
                    date,
                    seated != null,
                )
            }
        val formationNews = formation?.let { applyConfidence(it, date) }.orEmpty()
        if (legislativeDue) holdPresidentialCollege()
        if (executive != null) {
            nextExecutive =
                executiveElection.nextPoll(executive, requireNotNull(rules.executive))
        }
        val after = government()
        lastElection =
            ElectionResult(
                date,
                early = wasEarly,
                parties,
                executive,
                before,
                after,
                billLapsed = billLapsed,
                districts = if (legislativeDue) houseDistricts else emptyList(),
                listSeats = if (legislativeDue) lastListSeats else emptyMap(),
                listVoteShares = if (legislativeDue) lastListVoteShares else emptyMap(),
                listRegistration = registeredLists,
                senate = if (legislativeDue) senate?.lastElection.orEmpty() else emptyList(),
                pakistanReturn = if (legislativeDue) pakistanReturns?.begin(date, houseDistricts, requireNotNull(legislature)) else null,
            )
        val kind = if (executiveDue) "${rules.executive?.title} 선거" else "총선"
        val text =
            if (executive?.campaign?.houseDate != null && executive.winner == null) {
                "선거인단 과반 당선자가 없어 하원이 주별로 대통령을 선출한다."
            } else if (executive != null && executive.winner == null) {
                "$kind 1차 투표에서 당선 요건을 충족한 후보가 없어 ${executive.campaign.runoffDate}에 결선을 치른다."
            } else if (executive?.winner != null && executiveElection.presidentElect != null) {
                "${executive.winner.name} 후보가 당선됐다. 취임일은 ${executiveElection.presidentElect?.inauguration}이다."
            } else if (legislativeDue && confidence?.caretaker == true) {
                "$kind 의석이 확정됐다. 새 의회에서 후임 정부를 구성한다."
            } else if (before != after) {
                "$kind 결과 정권이 바뀌었다. 새 정부: $after"
            } else {
                "$kind 결과 ${after.josa("이", "가")} 정권을 지켰다."
            }
        if (before != after) {
            if (confidence?.caretaker != true) confidence?.resetGovernment()
            renewMandate()
        }
        return mandateNews + nominations + transition + inaugurationNews + formationNews + appointmentsNews +
            listOf(WorldNews(date, countryId, "선거", text, major = before != after))
    }

    private fun government(): String =
        when {
            rebelGovernment != null -> {
                "$rebelGovernment 과도정부 · ${leadership.current().leader.name}"
            }

            juntaSince != null -> {
                "군사평의회 · ${leadership.current().leader.name}"
            }

            countryId == "PAK" && leadership.current().leader.title == "과도 총리" -> {
                "과도정부 · ${leadership.current().leader.name}"
            }

            confidence?.caretaker == true -> {
                "관리정부 · ${(leadership.current().headOfGovernment ?: leadership.current().headOfState).name}"
            }

            else -> {
                leadership.governmentLabel(
                    profile.initialGovernment,
                    rules.system == GovernmentSystem.SEMI_PRESIDENTIAL || countryId in setOf("EGY", "TWN", "RUS", "RWA"),
                )
            }
        }

    private fun electLegislature(date: LocalDate): List<PartyResult> {
        val rule = electionMap(requireNotNull(rules.legislature))
        val chamber = legislature
        if (chamber == null) {
            val contest = requireNotNull(rule.openingContest)
            val result = ElectionModel.openingElection(rule, profile.regime) { random.nextGaussian() * VOTE_NOISE }
            legislature = result.legislature
            if (rule.pakistanAssembly) {
                houseDistricts = result.districts
                lastDistrictVote = result.parties.associate { it.name to it.voteShare }
            }
            result.legislature.groups.firstOrNull { countryId != "PAK" && it.role == PartyRole.LEAD }?.let { lead ->
                leadership.onGovernmentFormed(lead.name, date, reelected = false, title = contest.headOfGovernmentTitle)
            }
            return result.parties
        }
        val shares =
            lithuanianCount?.vote ?: iranianCount?.vote ?: frenchCount?.vote
                ?: newParties.voteShares(voteShares(chamber.groups, rule.electoralSystem), filingDeadline)
        val contenders = listContenders()
        newParties.clear(filingDeadline)
        val seats =
            lithuanianCount?.result(
                if (legislativeRunoff ==
                    null
                ) {
                    0
                } else {
                    random.nextInt()
                },
                chamber.groups.find { it.role == PartyRole.LEAD }?.name,
                approval,
            )
                ?: iranianCount?.result(approval, chamber.groups.find { it.role == PartyRole.LEAD }?.name, random.nextInt())
                ?: frenchCount?.let {
                    if (legislativeRunoff ==
                        null
                    ) {
                        it.seats
                    } else {
                        it.secondBallot(approval, emptyList(), random.nextInt())
                    }
                }
                ?: if (rule.pakistanAssembly) {
                    PakistanAssembly.elect(
                        shares,
                        requireNotNull(rule.openingContest),
                        modelNames = contenders.associate { it.name to it.modelName },
                    )
                } else {
                    ElectionModel.legislativeSeats(
                        shares,
                        rule,
                        DistrictElectorate(
                            emptyList(),
                            emptyMap(),
                            approval,
                            contenders.associate {
                                it.name to
                                    it.modelName
                            },
                        ),
                        listFiling?.filed(firstPollingDay),
                    )
                }
        val startOfTerm = newHouseTerm(countryId, date, earlyCalled)
        if (startOfTerm > date) outgoingDistricts = houseDistricts
        lastListSeats = seats.listSeats
        lastListVoteShares = seats.listVoteShares
        if (rule.iranianAssembly || rule.frenchAssembly || rule.usHouse || rule.pluralityMap != null || rule.australianHouse ||
            rule.pakistanAssembly ||
            rule.mixedSystem != null
        ) {
            houseDistricts = seats.districts
        }
        if (rule.frenchAssembly || rule.australianHouse || rule.pakistanAssembly || rule.mixedSystem != null ||
            rule.proportionalSystem != null
        ) {
            lastDistrictVote = shares
        }
        senate?.elect(date, shares, contenders, executiveElection.governingParty, emptyList(), approval)
        val previousRoles = contenders.associate { it.name to it.role }
        val reseated =
            chamber.withNewTerm(
                ElectionModel.reseat(contenders, seats),
                unassigned = if (rule.mixedSystem == MixedSystem.KOR) rule.seats - seats.direct.values.sum() else 0,
                vacancies = lithuanianCount?.vacancies(date).orEmpty(),
                rankedLists = seats.rankedLists.map { it.withNominees(contenders) },
            )
        val governed =
            if (lithuanianCount != null || confidence?.caretaker ==
                true
            ) {
                reseated
            } else {
                ElectionModel.afterLegislativeElection(reseated, rules, previousRoles, executiveElection.governingParty)
            }
        if (startOfTerm > date) incomingHouse = startOfTerm to governed else legislature = governed
        val leadBefore = previousRoles.entries.firstOrNull { it.value == PartyRole.LEAD }?.key
        val leadAfter = governed.groups.firstOrNull { it.role == PartyRole.LEAD }?.name
        if (leadAfter != null && rules.system != GovernmentSystem.PRESIDENTIAL && confidence?.caretaker != true &&
            lithuanianCount == null
        ) {
            leadership.onGovernmentFormed(leadAfter, date, reelected = leadAfter == leadBefore)
        }
        return contenders.map { party ->
            PartyResult(
                party.name,
                party.role,
                shares.getValue(party.name),
                chamber.groups
                    .find {
                        it.name == party.name
                    }?.let { if (rule.pakistanAssembly) it.directSeats else it.seats } ?: 0,
                seats.total(party.name),
            )
        }
    }

    private fun electExecutive(date: LocalDate): ExecutiveResult {
        val rule = requireNotNull(rules.executive)
        val result = executiveElection.vote(date, rule, approval, house = houseDistricts)
        if (executiveElection.presidentElect != null || countryId in setOf("RUS", "RWA")) return result
        result.winner?.party?.let { winner ->
            legislature = legislature?.let { ElectionModel.presidentialLegislature(it, rules, winner) }
        }
        return result
    }

    private fun electionMap(rule: LegislativeElectionRule): LegislativeElectionRule =
        if (rule.pluralityMap == PluralityMap.CAN && earlyCalled && canadianElectionMap == PluralityMap.CAN_2013) {
            rule.copy(pluralityMap = canadianElectionMap, seats = 338)
        } else {
            rule
        }

    private fun electoralParties(parties: List<ParliamentaryGroup>): List<ParliamentaryGroup> =
        rules.legislature?.pluralityMap?.let { PluralityDistricts.contenders(it, parties) } ?: when (countryId) {
            "IRN" -> IranianAssembly.contenders(parties)
            "KOR" -> KoreanListNominations.contenders(parties, firstPollingDay?.takeIf { sittingDate >= it.minusDays(19) })
            "AUS" -> AustralianHouse.contenders(parties)
            else -> parties
        }

    private fun voteShares(
        parties: List<ParliamentaryGroup>,
        system: ElectoralSystem,
    ): Map<String, Double> =
        ElectionModel.voteShares(
            groups = electoralParties(parties),
            lastVote =
                if (countryId ==
                    "KOR"
                ) {
                    KoreanListNominations.campaignVote(firstPollingDay, sittingDate, lastDistrictVote)
                } else {
                    lastDistrictVote
                },
            system = system,
            regime = profile.regime,
            approval = approval,
            satisfaction = PopulationGroup.entries.associateWith { approval },
            identityMood = identityMood,
            identityShares = identityShares,
            noise = { random.nextGaussian() * VOTE_NOISE },
        )

    fun snapshot(): CountryPoliticsSnapshot =
        CountryPoliticsSnapshot(
            countryId = countryId,
            regime = if (hasInterimGovernment) Regime.CLOSED_AUTOCRACY else profile.regime,
            government = government(),
            leadership = leadership.current(),
            approval = approval,
            legislature = legislature,
            nextElection = listOfNotNull(nextLegislative, nextExecutive).minOrNull(),
            lastElection = lastElection,
            pendingBill = legislation.pending,
            lastBill = legislation.lastDecision,
            executiveCampaign = executiveElection.campaign,
            presidentElect = executiveElection.presidentElect,
            legislativeRunoff = legislativeRunoff,
            listRegistration = listFiling?.filed(firstPollingDay),
            seimasResolution = seimasResolution,
            newHouseOpens = incomingHouse?.first,
            nextUpperRenewal = upperRenewals?.next?.takeUnless { hasInterimGovernment },
            lastUpperRenewal = upperRenewals?.last?.takeUnless { hasInterimGovernment },
            upperHouse = constitution.upperHouse?.session(legislature, sittingDate)?.takeUnless { hasInterimGovernment },
            newParties = newParties.snapshot(),
            takenPartyNames = takenPartyNames(),
            indirectPresident = indirectPresident?.takeUnless { hasInterimGovernment }?.snapshot(),
            appointments = appointments?.takeUnless { hasInterimGovernment }?.snapshot(),
        )

    private companion object {
        const val BASELINE = 50.0
        const val HONEYMOON = 8.0
        const val SMOOTHING = 0.3
        const val APPROVAL_NOISE = 1.2
        const val JOB_WEIGHT = 250.0
        const val PRICE_WEIGHT = 80.0
        const val GROWTH_WEIGHT = 120.0
        const val INFLATION_COMFORT = 0.02
        const val FATIGUE_PER_MONTH = 0.12
        const val MAX_FATIGUE = 10.0
        const val AUTOCRATIC_DAMPING = 0.5
        const val VOTE_NOISE = 0.1
    }
}

/** 플레이어 국가를 뺀 플레이 가능 국가의 정치. 국가 순서와 난수 순서가 고정돼 결정론을 지킨다. */
internal class WorldPolitics(
    countryIds: List<String>,
    private val people: PeopleRegistry,
    start: LocalDate,
    seed: Long,
) {
    private val countries =
        countryIds.sorted().map { id ->
            AiCountryPolitics(
                id,
                PoliticalProfile.forCountry(id),
                ConstitutionProfile.forCountry(id),
                people,
                start,
                Random(seed xor id.hashCode().toLong()),
            )
        }
    private val byId = countries.associateBy { it.countryId }

    private data class InterimState(
        val faction: String,
        var leaderId: Int,
        val legislation: AiLegislation = AiLegislation(),
    )

    private val interim = sortedMapOf<String, InterimState>()
    private val interimRandom = Random(seed xor 0x40726cL)

    fun installRebelGovernment(
        country: String,
        rebels: String,
        date: LocalDate,
    ) {
        val existing = byId[country]
        if (existing != null) {
            existing.installRebelGovernment(rebels, date)
        } else {
            val leader = people.generate(country, "과도정부 수반", rebels, date)
            interim[country] = InterimState(rebels, leader.id)
        }
    }

    fun hasInterimGovernment(id: String): Boolean = id in interim || byId[id]?.hasInterimGovernment == true

    fun advanceMonth(
        indicators: Map<String, EconomyIndicators>,
        date: LocalDate,
        enact: (String, Double, Double) -> Unit,
    ): List<WorldNews> =
        buildList {
            countries.forEach { addAll(it.advanceMonth(indicators.getValue(it.countryId), date, enact)) }
            interim.forEach { (id, state) ->
                val leader = requireNotNull(people.get(state.leaderId))
                if (leader.retires(date, interimRandom)) {
                    val successor = people.generate(id, leader.title, state.faction, date)
                    state.leaderId = successor.id
                    add(WorldNews(date, id, state.faction, "${successor.name} 과도정부 수반 취임", major = true))
                }
                state.legislation.advance(date, id, state.faction, null, null, indicators.getValue(id), 50.0, enact)?.let(::add)
            }
        }

    fun holdElections(date: LocalDate): List<WorldNews> = countries.flatMap { it.holdElections(date) }

    fun approval(countryId: String): Double? = byId[countryId]?.approval ?: interim[countryId]?.let { 50.0 }

    fun renamePerson(person: Person) = byId.values.forEach { it.renamePerson(person) }

    fun country(countryId: String): AiCountryPolitics? = byId[countryId]

    fun snapshot(): Map<String, CountryPoliticsSnapshot> =
        countries.associate { it.countryId to it.snapshot() } +
            interim.mapValues { (id, state) ->
                val leader = requireNotNull(people.get(state.leaderId))
                CountryPoliticsSnapshot(
                    id,
                    Regime.CLOSED_AUTOCRACY,
                    "${state.faction} 과도정부 · ${leader.name}",
                    Leadership(leader, null, false),
                    50.0,
                    null,
                    null,
                    null,
                    state.legislation.pending,
                    state.legislation.lastDecision,
                )
            }
}

/** 국가 모듈과 정치 계산의 소식을 세계 소식으로 바꾼다. */
internal fun CountryNews.toWorld(
    date: LocalDate,
    countryId: String,
): WorldNews = WorldNews(date, countryId, source, text, major)
