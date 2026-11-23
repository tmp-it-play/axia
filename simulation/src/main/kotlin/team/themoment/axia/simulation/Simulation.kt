package team.themoment.axia.simulation

import team.themoment.axia.simulation.country.CountryEffects
import team.themoment.axia.simulation.country.CountryModules
import team.themoment.axia.simulation.diplomacy.Diplomacy
import team.themoment.axia.simulation.diplomacy.DiplomaticAction
import team.themoment.axia.simulation.economy.CentralBank
import team.themoment.axia.simulation.economy.CentralBankData
import team.themoment.axia.simulation.economy.DebtNotice
import team.themoment.axia.simulation.economy.DebtPlan
import team.themoment.axia.simulation.economy.EconomyData
import team.themoment.axia.simulation.economy.EconomySnapshot
import team.themoment.axia.simulation.economy.InternationalState
import team.themoment.axia.simulation.economy.SovereignDebt
import team.themoment.axia.simulation.economy.WorldEconomy
import team.themoment.axia.simulation.events.EventCategory
import team.themoment.axia.simulation.events.EventChoice
import team.themoment.axia.simulation.events.EventContext
import team.themoment.axia.simulation.events.EventEffects
import team.themoment.axia.simulation.events.EventSystem
import team.themoment.axia.simulation.events.GameEvent
import team.themoment.axia.simulation.military.DefenseInputs
import team.themoment.axia.simulation.military.Military
import team.themoment.axia.simulation.military.MilitaryAction
import team.themoment.axia.simulation.military.Mobilization
import team.themoment.axia.simulation.nation.InstitutionItem
import team.themoment.axia.simulation.nation.Nation
import team.themoment.axia.simulation.nation.NationSnapshot
import team.themoment.axia.simulation.nation.PolicyItem
import team.themoment.axia.simulation.nation.RegulationItem
import team.themoment.axia.simulation.nation.SpendingKind
import team.themoment.axia.simulation.people.LeadershipState
import team.themoment.axia.simulation.people.PeopleRegistry
import team.themoment.axia.simulation.people.Person
import team.themoment.axia.simulation.politics.ConstitutionProfile
import team.themoment.axia.simulation.politics.GovernmentSystem
import team.themoment.axia.simulation.politics.InitialApproval
import team.themoment.axia.simulation.politics.InstitutionControl
import team.themoment.axia.simulation.politics.PoliticalProfile
import team.themoment.axia.simulation.politics.Politics
import team.themoment.axia.simulation.politics.PoliticsSnapshot
import team.themoment.axia.simulation.politics.Regimes
import team.themoment.axia.simulation.politics.WorldNews
import team.themoment.axia.simulation.politics.WorldPolitics
import team.themoment.axia.simulation.politics.toWorld
import team.themoment.axia.simulation.society.GovernmentAction
import team.themoment.axia.simulation.society.Identity
import team.themoment.axia.simulation.society.OrganizationData
import team.themoment.axia.simulation.society.SocietyData
import team.themoment.axia.simulation.society.WorldTrends
import team.themoment.axia.simulation.text.josa
import team.themoment.axia.simulation.world.CountryKind
import team.themoment.axia.simulation.world.Geography
import team.themoment.axia.simulation.world.PlayableCountries
import team.themoment.axia.simulation.world.StructureData
import team.themoment.axia.simulation.world.World
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.random.Random

/**
 * 세계 상태와 하루 단위 진행을 담는다.
 *
 * 갱신 주기: 매일 사건 판정·법안·선거, 매주 전선, 매월 경제·여론·외교·군사 자본·AI 정치·세계 사건·장기 추세.
 * [SimulationRunner]의 시뮬레이션 코루틴에서만 변경한다.
 */
class Simulation(
    val world: World,
    private val data: EconomyData,
    val playerCountryId: String,
    seed: Long = DEFAULT_SEED,
    startDate: LocalDate = START_DATE,
) {
    private val random = Random(seed)
    private val profile = PoliticalProfile.forCountry(playerCountryId)
    private val constitution = ConstitutionProfile.forCountry(playerCountryId)
    private val people = PeopleRegistry(Random(seed xor PEOPLE_SEED_SALT))
    private val module = CountryModules.create(playerCountryId, startDate, Random(seed xor MODULE_SEED_SALT))
    private val states =
        world.countries.values
            .filter { it.kind == CountryKind.STATE }
            .map { it.id }
            .sorted()
    private val playable = PlayableCountries.loadDefault(world, data).map { it.id }.toSet()
    private val nation =
        Nation(
            data.country(playerCountryId),
            SocietyData.loadDefault().country(playerCountryId),
            profile,
            OrganizationData.loadDefault().country(playerCountryId),
            module,
            playerCountryId in CountryModules.specialized,
            StructureData.default.of(playerCountryId),
            world,
            InitialApproval.forCountry(playerCountryId),
            startDate,
            seed xor SOCIETY_SEED_SALT,
        )
    private val economy =
        WorldEconomy(
            data,
            CentralBankData.loadDefault(data),
            mapOf(playerCountryId to nation.policy),
            playerCountryId,
            YearMonth.from(startDate),
        )
    private val centralBank = economy.monetary.banks.getValue(playerCountryId)
    private val institutions = PlayerInstitutions(centralBank)

    private val politics =
        Politics(
            profile,
            constitution,
            nation.budget,
            nation.regulations,
            institutions,
            module,
            LeadershipState(
                playerCountryId,
                people,
                startDate,
                profile.elections.executive?.title,
                parliamentary = profile.elections.system == GovernmentSystem.PARLIAMENTARY,
            ),
            Random(seed xor POLITICS_SEED_SALT),
        )
    private val worldPolitics =
        WorldPolitics(
            playable.filter { it != playerCountryId },
            people,
            startDate,
            seed xor WORLD_POLITICS_SEED_SALT,
        )
    private val diplomacy =
        Diplomacy(states, playerCountryId, ::democratic, economy::tradeShare, Random(seed xor DIPLOMACY_SEED_SALT))
    private val military =
        Military(
            states,
            playerCountryId,
            ::countryName,
            Random(seed xor MILITARY_SEED_SALT),
            allied = diplomacy::allied,
            relation = diplomacy::relation,
        )
    private val events = EventSystem(Random(seed xor EVENT_SEED_SALT), ::countryName)
    private val trends = WorldTrends()
    private val worldNews = ArrayDeque<WorldNews>()

    /** 플레이어의 답을 기다리는 AI 국가의 외교 제안. */
    private val pendingProposals = HashMap<String, Diplomacy.Proposal>()
    private val international =
        object : InternationalState {
            override fun sanction(
                by: String,
                target: String,
            ) = diplomacy.sanction(by, target)

            override fun tradeAgreement(
                a: String,
                b: String,
            ) = diplomacy.tradeAgreement(a, b)

            override fun warIntensity(country: String) = military.intensityChange(country)

            override fun occupied(country: String) = military.occupiedChange(country)
        }

    var date: LocalDate = startDate
        private set

    init {
        val player = economy.country(playerCountryId)
        nation.start(player)
        politics.updateConstituencies(nation.groupSnapshots(player), nation.opinion(), nation.approval())
        military.advanceMonth(date, defenseInputs())
    }

    /**
     * @property pauseFor 자동 일시정지 설정과 맞춰 볼 사건 분류(플레이어 국가 사건이나 세계적 대유행).
     * @property decisionNeeded 선택지가 있는 사건이 새로 생겼다.
     */
    data class DayUpdate(
        val newMonth: Boolean,
        val budgetPassed: Boolean,
        val electionHeld: Boolean,
        val pauseFor: Set<EventCategory> = emptySet(),
        val decisionNeeded: Boolean = false,
        val decisionExpired: Boolean = false,
    )

    fun advanceDay(): DayUpdate {
        date = date.plusDays(1)
        val newMonth = date.dayOfMonth == 1
        val pauseFor = mutableSetOf<EventCategory>()
        val offered = if (newMonth) advanceMonth(pauseFor) else false
        if (date.dayOfWeek == DayOfWeek.MONDAY) military.advanceWeek(date)

        val player = economy.country(playerCountryId)
        val happened = events.rollPlayer(date, playerCountryId, playerEventContext())
        happened.forEach { event ->
            pauseFor += event.category
            applyPlayerEvent(event)
        }
        val expired = events.expire(date)
        expired.forEach { (event, choice) ->
            applyChoice(choice)
            nation.report(date, event.name, "결정 기한이 지나 '${choice.label}'로 대응했다.", major = false)
        }

        val electionHeld = politics.holdElections(date)
        worldPolitics.holdElections(date).forEach(::addWorldNews)
        val passed = politics.advanceDay(date)
        if (passed) {
            politics.snapshot(date).lastDecision?.let { bill -> nation.onPolicyEnacted(bill.changes.map { it.item to it.delta }, date) }
        }
        if (politics.takeMandateRenewal()) nation.renewMandate()
        politics.drainNews().forEach { nation.report(date, it.source, it.text, it.major) }
        (diplomacy.drainNews() + military.drainNews()).forEach(::addWorldNews)
        return DayUpdate(
            newMonth,
            passed,
            electionHeld,
            pauseFor,
            offered || happened.any { it.choices.isNotEmpty() },
            expired.isNotEmpty(),
        )
    }

    /** @return 플레이어가 결정해야 할 제안이 새로 생겼는지 */
    private fun advanceMonth(pauseFor: MutableSet<EventCategory>): Boolean {
        val debtNotices = economy.advanceMonth(random, international)
        processDebtNotices(debtNotices)
        val debtDecision = debtNotices[playerCountryId] == DebtNotice.DEFAULT
        if (debtDecision) pauseFor += EventCategory.FINANCIAL
        val player = economy.country(playerCountryId)
        nation.advanceMonth(player, institutions.changes(), date)
        nation
            .advanceCountryModule(player, date) { diplomacy.relation(playerCountryId, it) }
            .relationShifts
            .forEach { (other, points) -> diplomacy.nudge(playerCountryId, other, points) }
        applyWarWeariness()
        nation.setExternalSpending(aid = diplomacy.aidSpending + military.aidSpending, mobilization = military.mobilization.cost)

        val indicators = economy.snapshot(date).countries
        worldPolitics.advanceMonth(indicators, date).forEach(::addWorldNews)
        states.forEach { a -> states.filter { it > a }.forEach { b -> diplomacy.setWar(a, b, military.atWar(a, b)) } }
        diplomacy.advanceMonth(date)
        military.advanceMonth(date, defenseInputs())
        military.updateDisputes()
        military.aiWarDecisions(date, ::democratic, diplomacy::allies).forEach { (attacker, defender) ->
            diplomacy.setWar(attacker, defender, true)
            diplomacy.onWarStarted(attacker, defender, date)
        }
        val proposals = diplomacy.aiDecisions(date, military::atWar, playable)
        val worldGdp = states.sumOf { economy.country(it).dollarGdp }
        trends.advanceMonth(
            worldRealGdp = states.sumOf { economy.country(it).realGdpPerCapita * economy.country(it).populationCount },
            playerShare = player.dollarGdp / worldGdp,
            playerCut = ENVIRONMENT_CUT * nation.regulations.change(RegulationItem.ENVIRONMENT).coerceAtLeast(0.0),
        )
        rollWorldEvents(pauseFor)
        politics.updateConstituencies(nation.groupSnapshots(player), nation.opinion(), nation.approval())
        politics.monthlyReview(date)
        proposals.forEach { proposal ->
            pendingProposals[proposal.from] = proposal
            events.custom(
                name = "${proposal.action.label} 제안",
                category = EventCategory.DIPLOMATIC,
                countryId = proposal.from,
                date = date,
                text = "${countryName(proposal.from).josa("이", "가")} 우리나라에 ${proposal.action.label.josa("을", "를")} 제안했다.",
                choices =
                    listOf(
                        EventChoice("정중히 거절한다", "지금의 관계를 유지한다.", EventEffects()),
                        EventChoice("받아들인다", proposal.action.description, EventEffects(), action = "$PROPOSAL_ACTION${proposal.from}"),
                    ),
            )
        }
        val offers = military.peaceOffers(date)
        offers.forEach { (warId, enemy) ->
            events.custom(
                name = "휴전 제안",
                category = EventCategory.MILITARY,
                countryId = enemy,
                date = date,
                text = "${countryName(enemy).josa("이", "가")} ${military.warName(warId)}의 현재 전선에서 휴전하자고 제안했다.",
                choices =
                    listOf(
                        EventChoice("거부하고 계속 싸운다", "전쟁을 이어 간다.", EventEffects(identityMood = mapOf(Identity.PATRIOTS to 1.0))),
                        EventChoice("휴전을 받아들인다", "지금 전선에서 전투를 멈춘다.", EventEffects(), action = "$PEACE_ACTION$warId"),
                    ),
            )
        }
        return debtDecision || offers.isNotEmpty() || proposals.isNotEmpty()
    }

    private fun processDebtNotices(notices: Map<String, DebtNotice>) {
        notices.forEach { (id, notice) ->
            val country = economy.country(id)
            val isPlayer = id == playerCountryId
            val text =
                when (notice) {
                    DebtNotice.DEFAULT -> {
                        val need = country.extensionNeed()
                        val estimate = "현재 예산으로 만기를 늘리면 첫해 조달 필요액은 GDP의 %.1f%%다.".format(Locale.ROOT, need * 100)
                        val event = events.trigger("sovereign_default", id, date, isPlayer, estimate)
                        if (isPlayer) {
                            applyPlayerEvent(event)
                            return@forEach
                        }
                        applyAiEvent(id, event.effects)
                        val canExtend = need <= SovereignDebt.EXTENSION_CAPACITY
                        val plan = if (canExtend) DebtPlan.MATURITY_EXTENSION else DebtPlan.PRINCIPAL_REDUCTION
                        country.debt.negotiate(plan)
                        "${event.text} 채권단과 ${plan.label} 협상을 시작했다."
                    }

                    DebtNotice.COUNTEROFFER -> {
                        "만기를 늘려도 조달 부담이 커서 채권단이 원금 감면을 포함한 교환안을 요구했다."
                    }

                    DebtNotice.SETTLEMENT -> {
                        "채권단과 ${requireNotNull(country.debt.plan).label}에 합의해 국채를 교환했다."
                    }

                    DebtNotice.RECOVERED -> {
                        "채무 재조정에 따른 신규 차입 가산금리가 해소됐다."
                    }
                }
            if (isPlayer) {
                nation.report(date, "국가채무", text, major = notice != DebtNotice.RECOVERED)
            } else {
                addWorldNews(WorldNews(date, id, "국가채무", text, major = notice != DebtNotice.RECOVERED))
            }
        }
    }

    private fun applyChoice(choice: EventChoice) {
        nation.applyEvent(economy.country(playerCountryId), choice.effects)
        choice.action?.let { action ->
            if (action.startsWith(PEACE_ACTION)) military.acceptPeace(action.removePrefix(PEACE_ACTION).toInt(), date)
            if (action.startsWith(PROPOSAL_ACTION)) {
                pendingProposals.remove(action.removePrefix(PROPOSAL_ACTION))?.let { diplomacy.acceptProposal(it, date) }
            }
        }
    }

    /** 전쟁 피로도는 모든 성향의 만족도를 깎는다. 개전 초기에는 안보 성향이 결집한다. */
    private fun applyWarWeariness() {
        val weariness = military.playerWeariness()
        if (weariness <= 0.0) return
        val mood =
            Identity.entries.associateWith { -weariness * WEARINESS_MOOD } +
                (Identity.PATRIOTS to (RALLY - weariness * WEARINESS_MOOD))
        nation.applyEffects(economy.country(playerCountryId), CountryEffects(identityMood = mood), date)
    }

    private fun rollWorldEvents(pauseFor: MutableSet<EventCategory>) {
        val contexts = states.associateWith(::worldEventContext)
        events.rollWorld(date, contexts, playerCountryId).forEach { event ->
            val global = event.definitionId == PANDEMIC
            if (global) {
                pauseFor += event.category
                states.forEach { id -> applyAiEvent(id, event.effects) }
                nation.applyEvent(economy.country(playerCountryId), event.effects)
                nation.report(date, event.name, event.text, major = true)
            } else {
                applyAiEvent(event.countryId, event.effects)
                if (event.definitionId == WORLD_COUP) worldPolitics.country(event.countryId)?.coup(date)
                if (event.definitionId == INSURGENCY) military.startCivilWar(event.countryId, rebelName(event.countryId), date)
            }
            addWorldNews(WorldNews(date, event.countryId, event.name, event.text, event.major))
        }
    }

    /** 새 무장단체 이름. 나라 이름과 무장단체 틀을 섞어 만든다. */
    private fun rebelName(countryId: String): String {
        val country = countryName(countryId)
        val template = REBEL_TEMPLATES[random.nextInt(REBEL_TEMPLATES.size)]
        return template.replace("{country}", country)
    }

    private fun applyAiEvent(
        countryId: String,
        effects: EventEffects,
    ) {
        if (countryId == playerCountryId) return
        val country = economy.country(countryId)
        country.addOutputGapShock(effects.outputGap)
        country.addInflationShock(effects.inflation)
        country.addSpending(effects.spending)
        country.addPotentialGrowth(effects.potentialGrowth)
        effects.sectorShocks.forEach { (sector, shock) -> country.addSectorShock(sector, shock) }
        worldPolitics.country(countryId)?.shiftApproval(effects.mood * AI_MOOD_TO_APPROVAL)
    }

    private fun applyPlayerEvent(event: GameEvent) {
        val player = economy.country(playerCountryId)
        nation.applyEvent(player, event.effects)
        nation.report(date, event.name, event.text, event.major)
        if (event.definitionId == COUP_ATTEMPT) resolveCoup()
    }

    /** 쿠데타 성공 확률은 군부의 위험도와 낮은 지지율에 비례한다. */
    private fun resolveCoup() {
        val risk = nation.eventValues(economy.country(playerCountryId)).getValue("coupRisk")
        val success = random.nextDouble() < (risk * (1.3 - nation.approval() / 100.0)).coerceIn(0.05, 0.9)
        if (success) {
            politics.coup(date)
            nation.suppressLiberties(JUNTA_LIBERTY_CUT)
        } else {
            nation.applyEvent(
                economy.country(playerCountryId),
                EventEffects(identityMood = mapOf(Identity.PATRIOTS to 2.0), politicalCapital = 10.0),
            )
            nation.report(date, "군부", "쿠데타 시도가 진압됐다. 가담 부대 지휘관들이 체포됐다.", major = true)
        }
    }

    private fun playerEventContext(): EventContext {
        val player = economy.country(playerCountryId)
        return EventContext(nation.eventValues(player) + sharedEventValues(playerCountryId))
    }

    private fun worldEventContext(countryId: String): EventContext {
        val country = economy.country(countryId)
        val initial = data.country(countryId)
        val approval = worldPolitics.approval(countryId) ?: 50.0
        val values =
            mapOf(
                "unemploymentGap" to country.unemployment - initial.unemployment,
                "inflation" to (country.inflation - 0.03).coerceAtLeast(0.0),
                "debtRatio" to (country.debtRatio - 0.9).coerceAtLeast(0.0),
                "policyRate" to (country.policyRate - 0.05).coerceAtLeast(0.0),
                "outputGap" to country.outputGap,
                "approval" to (approval - 50.0) / 50.0,
            )
        return EventContext(values + sharedEventValues(countryId))
    }

    /** 플레이어와 AI 국가에 공통으로 쓰는 변수. */
    private fun sharedEventValues(countryId: String): Map<String, Double> {
        val democracy = democratic(countryId)
        val income = data.country(countryId).society.incomeGroup
        // 플레이 가능 국가는 쿠데타 이력으로, 나머지는 저소득·하위중소득 국가를 쿠데타 위험국으로 본다.
        val coupProne =
            if (countryId in playable) ConstitutionProfile.forCountry(countryId).coupProne else income == "LIC" || income == "LMC"
        val neighbors = Geography.default.of(countryId).neighbors
        val conditions = economy.monetary.conditions(countryId)
        return mapOf(
            "climate" to trends.climateAnomaly,
            "warIntensity" to military.intensityChange(countryId),
            "hostility" to (neighbors.minOfOrNull { diplomacy.relation(countryId, it) }?.let { -it / 100.0 } ?: 0.0).coerceIn(0.0, 1.0),
            "judicial" to (if (countryId == playerCountryId) constitution.judicialIndependence else 0.5) - 0.5,
            "credibility" to conditions.credibility - 0.7,
            "floating" to if (conditions.anchorInflation == null) 1.0 else 0.0,
            "democracy" to if (democracy == true) 1.0 else 0.0,
            "coupProne" to if (coupProne) 1.0 else 0.0,
        )
    }

    private fun defenseInputs(): Map<String, DefenseInputs> =
        states.associateWith { id ->
            val country = economy.country(id)
            val share = if (id == playerCountryId) nation.budget.level(SpendingKind.DEFENSE) else data.country(id).budget.defense
            DefenseInputs(
                spending = share * country.dollarGdp,
                gdpPerCapita = country.dollarGdp / country.populationCount,
                approval = if (id == playerCountryId) nation.approval() else worldPolitics.approval(id) ?: 50.0,
            )
        }

    /** 플레이 가능 국가는 정치 자료, 나머지는 V-Dem 체제 분류로 본다. 자료가 없으면 null. */
    private fun democratic(countryId: String): Boolean? {
        val regime = if (countryId in playable) PoliticalProfile.forCountry(countryId).regime else Regimes.of(countryId)
        return regime?.let { !it.autocratic }
    }

    private fun countryName(id: String): String = world.country(id).name

    private fun addWorldNews(news: WorldNews) {
        worldNews.addFirst(news)
        while (worldNews.size > MAX_WORLD_NEWS) worldNews.removeLast()
    }

    /** 조건을 만족하면 6주 뒤 조기 총선을 치른다. */
    fun callEarlyElection() = politics.callEarlyElection(date)

    fun editBudgetDraft(
        item: PolicyItem,
        target: Double,
    ) = politics.edit(item, target)

    /** 정부가 금리를 정하는 나라에서만 받아들인다. 다음 달 1일부터 적용된다. */
    fun directPolicyRate(rate: Double) = centralBank.direct(rate)

    fun negotiateBudgetWith(party: String?) = politics.negotiate(party)

    fun clearBudgetDraft() = politics.clearDraft()

    fun reviseStalledBudget() = politics.reviseStalledBill()

    fun introduceBudget() = politics.introduce(date)

    fun politicsSnapshot(): PoliticsSnapshot = politics.snapshot(date)

    fun economySnapshot(): EconomySnapshot = economy.snapshot(date)

    fun nationSnapshot(): NationSnapshot = nation.snapshot(economy.country(playerCountryId), date)

    fun worldSnapshot(): WorldSnapshot {
        val focus = playable + diplomacy.allies(playerCountryId) + military.hostilePairs().flatMap { listOf(it.first, it.second) }
        return WorldSnapshot(
            date = date,
            politics = worldPolitics.snapshot(),
            diplomacy = diplomacy.snapshot(date, focus),
            military = military.snapshot(),
            militaryActions = states.associateWith(military::available).filterValues { it.isNotEmpty() },
            trade = economy.tradeSnapshot(),
            trends = trends.snapshot(),
            news = worldNews.toList(),
            events = events.history(),
            pendingEvents = events.pending(),
        )
    }

    /** 조직에 대한 정부 행위. 정치 자본이 모자라거나 조건이 맞지 않으면 무시한다. */
    fun actOnOrganization(
        organizationId: Int,
        action: GovernmentAction,
    ) = nation.performAction(organizationId, action, date)

    fun renameOrganization(
        organizationId: Int,
        name: String,
    ) = nation.renameOrganization(organizationId, name)

    /** 실존·생성 인물 모두 이름을 바꿀 수 있다. */
    fun renamePerson(
        personId: Int,
        name: String,
    ): Person? = people.rename(personId, name)

    /** 외교 행위. 정치 자본이 모자라거나 상대가 거절하면 아무 일도 없다(거절되면 자본을 돌려준다). */
    fun diplomaticAction(
        target: String,
        action: DiplomaticAction,
    ): Boolean {
        if (action !in diplomacy.available(target, date) || !nation.spendCapital(action.cost)) return false
        val done = diplomacy.perform(target, action, date)
        if (!done) {
            nation.applyEvent(economy.country(playerCountryId), EventEffects(politicalCapital = action.cost))
            nation.report(date, "외교", "${countryName(target).josa("이", "가")} ${action.label.josa("을", "를")} 거절했다.", major = false)
            return false
        }
        if (action == DiplomaticAction.CONDEMN) {
            nation.applyEvent(economy.country(playerCountryId), EventEffects(identityMood = mapOf(Identity.PATRIOTS to 1.0)))
        }
        nation.setExternalSpending(aid = diplomacy.aidSpending + military.aidSpending, mobilization = military.mobilization.cost)
        return true
    }

    fun militaryAction(
        target: String,
        action: MilitaryAction,
    ): Boolean {
        if (action !in military.available(target) || !nation.spendCapital(action.cost)) return false
        val done = military.perform(target, action, date)
        if (!done) {
            nation.applyEvent(economy.country(playerCountryId), EventEffects(politicalCapital = action.cost))
            nation.report(date, "군사", "${countryName(target).josa("이", "가")} ${action.label.josa("을", "를")} 거절했다.", major = false)
            return false
        }
        if (action == MilitaryAction.DECLARE_WAR) {
            // 선전포고는 민주국가 전체와의 관계를 해친다.
            states
                .filter {
                    democratic(
                        it,
                    ) == true && it != playerCountryId
                }.forEach { diplomacy.nudge(playerCountryId, it, -AGGRESSION_PENALTY) }
            diplomacy.setWar(playerCountryId, target, true)
            diplomacy.onWarStarted(playerCountryId, target, date)
        }
        nation.setExternalSpending(aid = diplomacy.aidSpending + military.aidSpending, mobilization = military.mobilization.cost)
        return true
    }

    fun setMobilization(level: Mobilization) {
        military.setMobilization(level)
        nation.setExternalSpending(aid = diplomacy.aidSpending + military.aidSpending, mobilization = level.cost)
    }

    /** 선택지가 있는 사건에 대응한다. */
    fun chooseEventOption(
        eventId: Int,
        index: Int,
    ) {
        val choice = events.choose(eventId, index) ?: return
        applyChoice(choice)
        military.drainNews().forEach(::addWorldNews)
    }

    companion object {
        val START_DATE: LocalDate = LocalDate.of(2024, 1, 1)
        const val DEFAULT_SEED = 20240101L

        /** 시스템마다 난수 순서가 섞이지 않도록 시드를 바꾼다. */
        private const val POLITICS_SEED_SALT = 0x5EED_E1EC_7105L
        private const val SOCIETY_SEED_SALT = 0x5EED_50C1_E7A1L
        private const val PEOPLE_SEED_SALT = 0x5EED_9E09_1E00L
        private const val MODULE_SEED_SALT = 0x5EED_C0DE_0000L
        private const val WORLD_POLITICS_SEED_SALT = 0x5EED_A1A1_0000L
        private const val DIPLOMACY_SEED_SALT = 0x5EED_D191_0000L
        private const val MILITARY_SEED_SALT = 0x5EED_A2A2_0000L
        private const val EVENT_SEED_SALT = 0x5EED_E7E7_0000L
        private const val MAX_WORLD_NEWS = 200
        private const val ENVIRONMENT_CUT = 0.6
        private const val WEARINESS_MOOD = 0.04
        private const val RALLY = 1.0
        private const val AI_MOOD_TO_APPROVAL = 1.0
        private const val JUNTA_LIBERTY_CUT = 0.3
        private const val AGGRESSION_PENALTY = 15.0
        private const val PANDEMIC = "pandemic"
        private const val WORLD_COUP = "coup_world"
        private const val COUP_ATTEMPT = "coup_attempt"
        private const val PEACE_ACTION = "peace:"
        private const val PROPOSAL_ACTION = "proposal:"
        private const val INSURGENCY = "insurgency"
        private val REBEL_TEMPLATES =
            listOf("{country} 해방전선", "{country} 인민무장대", "{country} 구국연합군", "{country} 민족저항운동", "{country} 혁명수비대", "신{country} 민병연합")
    }
}

/** 플레이어 국가 밖의 세계 상태. 매월과 사건·외교·군사 행위 때마다 갱신된다. */
data class WorldSnapshot(
    val date: LocalDate,
    val politics: Map<String, team.themoment.axia.simulation.politics.CountryPoliticsSnapshot>,
    val diplomacy: team.themoment.axia.simulation.diplomacy.DiplomacySnapshot,
    val military: team.themoment.axia.simulation.military.MilitarySnapshot,
    val militaryActions: Map<String, Set<MilitaryAction>>,
    val trade: team.themoment.axia.simulation.economy.TradeSnapshot,
    val trends: team.themoment.axia.simulation.society.TrendsSnapshot,
    val news: List<WorldNews>,
    val events: List<GameEvent>,
    val pendingEvents: List<GameEvent>,
)

/** 플레이어 국가의 제도 항목. 법안이 통과하면 중앙은행 독립성을 바꾼다. */
private class PlayerInstitutions(
    private val centralBank: CentralBank,
) : InstitutionControl {
    private val initial = InstitutionItem.entries.associateWith(::value)

    /** 제도 항목의 시작 대비 변화. */
    fun changes(): Map<InstitutionItem, Double> = InstitutionItem.entries.associateWith { value(it) - initial.getValue(it) }

    override fun value(item: InstitutionItem): Double =
        when (item) {
            InstitutionItem.CENTRAL_BANK_INDEPENDENCE -> if (centralBank.independent) 1.0 else 0.0
        }

    override fun adjustable(item: InstitutionItem): Boolean =
        when (item) {
            InstitutionItem.CENTRAL_BANK_INDEPENDENCE -> centralBank.adjustableIndependence
        }

    override fun enact(
        item: InstitutionItem,
        target: Double,
    ) {
        when (item) {
            InstitutionItem.CENTRAL_BANK_INDEPENDENCE -> {
                if (target >= 0.5) centralBank.grantIndependence() else centralBank.revokeIndependence()
            }
        }
    }
}
