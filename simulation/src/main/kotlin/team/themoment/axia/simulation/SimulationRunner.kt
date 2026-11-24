package team.themoment.axia.simulation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import team.themoment.axia.simulation.campaign.CampaignAction
import team.themoment.axia.simulation.campaign.CampaignSave
import team.themoment.axia.simulation.campaign.DatedAction
import team.themoment.axia.simulation.campaign.campaignKey
import team.themoment.axia.simulation.diplomacy.DiplomaticAction
import team.themoment.axia.simulation.economy.BankPressure
import team.themoment.axia.simulation.economy.Creditor
import team.themoment.axia.simulation.economy.DebtPlan
import team.themoment.axia.simulation.economy.EconomySnapshot
import team.themoment.axia.simulation.economy.GovernorMandate
import team.themoment.axia.simulation.events.EventCategory
import team.themoment.axia.simulation.military.MilitaryAction
import team.themoment.axia.simulation.military.Mobilization
import team.themoment.axia.simulation.military.ReconstructionEffort
import team.themoment.axia.simulation.nation.NationSnapshot
import team.themoment.axia.simulation.nation.PolicyItem
import team.themoment.axia.simulation.nation.RegionalProgram
import team.themoment.axia.simulation.politics.PoliticsSnapshot
import team.themoment.axia.simulation.society.GovernmentAction
import team.themoment.axia.simulation.time.GameSpeed
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.time.TimeSource

data class ClockState(
    val date: LocalDate,
    val paused: Boolean,
    val speed: GameSpeed,
)

/**
 * 시뮬레이션을 전용 코루틴에서 실제 시간에 맞춰 하루씩 진행한다.
 *
 * 모든 상태 변경은 명령 채널을 거쳐 이 코루틴 안에서만 일어난다. 계산이 배속을 따라가지 못하면
 * 밀린 날을 몰아서 계산하지 않고 가능한 속도로 진행한다.
 */
class SimulationRunner(
    private val simulation: Simulation,
    scope: CoroutineScope,
    initial: CampaignSave,
    private val autosave: suspend (CampaignSave) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val commands =
        Channel<Command>(Channel.UNLIMITED) { command ->
            if (command is Command.Checkpoint) command.result.completeExceptionally(IllegalStateException("종료된 캠페인입니다."))
        }
    private val start = initial.start
    private val journal = initial.actions.toMutableList()
    private var paused = true
    private var speed = GameSpeed(initial.speed)
    private val mutableClock = MutableStateFlow(currentClock())
    private val mutableEconomy = MutableStateFlow(simulation.economySnapshot())
    private val mutableNation = MutableStateFlow(simulation.nationSnapshot())

    private val mutableWorld = MutableStateFlow(simulation.worldSnapshot())

    /** 세계 정치·외교·군사·사건. 매월과 플레이어 행위, 새 소식이 있을 때 갱신된다. */
    val world: StateFlow<WorldSnapshot> = mutableWorld.asStateFlow()

    /** 자동 일시정지할 사건 분류. 선거와 선택지가 있는 사건은 항상 멈춘다. */
    private var pauseCategories: Set<EventCategory> = initial.pauseCategories
    private val mutablePauseSettings = MutableStateFlow(pauseCategories)
    val pauseSettings: StateFlow<Set<EventCategory>> = mutablePauseSettings.asStateFlow()

    private val mutablePolitics = MutableStateFlow(simulation.politicsSnapshot())
    val politics: StateFlow<PoliticsSnapshot> = mutablePolitics.asStateFlow()

    val clock: StateFlow<ClockState> = mutableClock.asStateFlow()

    /** 경제가 갱신될 때(매월 1일)마다 바뀐다. */
    val economy: StateFlow<EconomySnapshot> = mutableEconomy.asStateFlow()

    /** 플레이어 국가. 매월 갱신되고, 재정안이 통과되면 바로 갱신된다. */
    val nation: StateFlow<NationSnapshot> = mutableNation.asStateFlow()

    init {
        require(initial.date == simulation.date && start.countryId == simulation.playerCountryId)
        scope.launch(dispatcher) { run() }.invokeOnCompletion { commands.cancel() }
    }

    fun togglePause() = send(Command.TogglePause)

    fun setSpeed(speed: GameSpeed) = send(Command.SetSpeed(speed))

    /** 일시정지 상태에서만 하루를 진행한다. */
    fun advanceDay() = send(Command.AdvanceDay)

    fun perform(action: CampaignAction) = send(Command.Perform(action))

    fun editBudgetDraft(
        item: PolicyItem,
        target: Double,
    ) = perform(CampaignAction.EditBudget(item.campaignKey(), target))

    fun negotiateBudgetWith(party: String?) = perform(CampaignAction.NegotiateBudget(party))

    fun clearBudgetDraft() = perform(CampaignAction.ClearBudget)

    fun reviseStalledBudget() = perform(CampaignAction.ReviseBudget)

    fun introduceBudget() = perform(CampaignAction.IntroduceBudget)

    fun callEarlyElection() = perform(CampaignAction.EarlyElection)

    fun preferCoalitionPartner(party: String?) = perform(CampaignAction.PreferCoalition(party))

    fun appointGovernor(mandate: GovernorMandate) = perform(CampaignAction.AppointGovernor(mandate))

    fun pressureBank(direction: BankPressure) = perform(CampaignAction.PressureBank(direction))

    fun setCreditorTerms(
        creditor: Creditor,
        terms: DebtPlan?,
    ) = perform(CampaignAction.CreditorTerms(creditor, terms))

    fun setTradeRoute(
        target: String,
        partner: String,
        restrict: Boolean,
    ) = perform(CampaignAction.SetTradeRoute(target, partner, restrict))

    fun directPolicyRate(rate: Double) = perform(CampaignAction.DirectRate(rate))

    fun actOnOrganization(
        organizationId: Int,
        action: GovernmentAction,
    ) = perform(CampaignAction.ActOnOrganization(organizationId, action))

    fun renameOrganization(
        organizationId: Int,
        name: String,
    ) = perform(CampaignAction.RenameOrganization(organizationId, name))

    fun renameParty(
        country: String,
        id: Int,
        name: String,
    ) = perform(CampaignAction.RenameParty(country, id, name))

    fun renameRebels(
        id: Int,
        name: String,
    ) = perform(CampaignAction.RenameRebels(id, name))

    fun renamePerson(
        personId: Int,
        name: String,
    ) = perform(CampaignAction.RenamePerson(personId, name))

    fun diplomaticAction(
        target: String,
        action: DiplomaticAction,
    ) = perform(CampaignAction.Diplomatic(target, action))

    fun militaryAction(
        target: String,
        action: MilitaryAction,
    ) = perform(CampaignAction.Military(target, action))

    fun setMobilization(level: Mobilization) = perform(CampaignAction.Mobilize(level))

    fun chooseEventOption(
        eventId: Int,
        index: Int,
    ) = perform(CampaignAction.ChooseEvent(eventId, index))

    fun setRegionalProgram(
        regionId: String,
        program: RegionalProgram,
    ) = perform(CampaignAction.SetRegionalProgram(regionId, program))

    fun setOppositionVeto(veto: Boolean) = perform(CampaignAction.SetOppositionVeto(veto))

    fun requestImfProgram() = perform(CampaignAction.RequestImf)

    fun renameCompany(
        id: Int,
        name: String,
    ) = perform(CampaignAction.RenameCompany(id, name))

    fun negotiateRegion(
        target: String,
        regionId: String,
    ) = perform(CampaignAction.NegotiateRegion(target, regionId))

    fun setReconstructionEffort(effort: ReconstructionEffort) = perform(CampaignAction.SetReconstructionEffort(effort))

    fun changeMembership(
        id: String,
        join: Boolean,
    ) = perform(CampaignAction.ChangeMembership(id, join))

    /** 앞서 큐에 들어온 모든 조작까지 포함한 일관된 저장 시점을 얻는다. */
    suspend fun checkpoint(pause: Boolean = false): CampaignSave {
        val result = CompletableDeferred<CampaignSave>()
        send(Command.Checkpoint(pause, result))
        return result.await()
    }

    private fun capture() =
        CampaignSave(CampaignSave.CURRENT_VERSION, start, simulation.date, journal.toList(), speed.multiplier, pauseCategories)

    fun setAutoPause(
        category: EventCategory,
        enabled: Boolean,
    ) = send(Command.SetAutoPause(category, enabled))

    private fun send(command: Command) {
        check(commands.trySend(command).isSuccess) { "Simulation runner is closed" }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun run() {
        var dayStartedAt = TimeSource.Monotonic.markNow()
        while (true) {
            if (paused) {
                apply(commands.receive())
                dayStartedAt = TimeSource.Monotonic.markNow()
                continue
            }
            val dayDuration = speed.dayDuration
            val remaining = dayDuration - dayStartedAt.elapsedNow()
            val command =
                select {
                    commands.onReceive { it }
                    onTimeout(remaining) { null }
                }
            if (command != null) {
                apply(command)
                continue
            }
            stepDay()
            val nextDayStart = dayStartedAt + dayDuration
            dayStartedAt = if (nextDayStart.elapsedNow() > dayDuration) TimeSource.Monotonic.markNow() else nextDayStart
            publish()
        }
    }

    private suspend fun apply(command: Command) {
        when (command) {
            Command.TogglePause -> {
                paused = !paused
            }

            is Command.SetSpeed -> {
                speed = command.speed
            }

            Command.AdvanceDay -> {
                if (paused) stepDay()
            }

            is Command.Perform -> {
                val action = command.action
                action.applyTo(simulation)
                journal += DatedAction(simulation.date, action)
                when (action) {
                    is CampaignAction.EditBudget, is CampaignAction.NegotiateBudget, CampaignAction.ClearBudget,
                    CampaignAction.ReviseBudget, CampaignAction.IntroduceBudget, CampaignAction.EarlyElection,
                    is CampaignAction.SetOppositionVeto, is CampaignAction.PreferCoalition,
                        -> {
                            // 예산 초안과 표결 상태는 아래 publish()가 갱신한다.
                        }

                    is CampaignAction.RenameOrganization, is CampaignAction.ActOnOrganization -> {
                        mutableNation.value = simulation.nationSnapshot()
                    }

                    is CampaignAction.RenamePerson, is CampaignAction.RenameParty, is CampaignAction.RenameRebels -> {
                        mutableWorld.value = simulation.worldSnapshot()
                    }

                    else -> {
                        refreshAll()
                    }
                }
            }

            is Command.Checkpoint -> {
                if (command.pause) paused = true
                command.result.complete(capture())
            }

            is Command.SetAutoPause -> {
                pauseCategories = if (command.enabled) pauseCategories + command.category else pauseCategories - command.category
                mutablePauseSettings.value = pauseCategories
            }
        }
        publish()
    }

    private suspend fun stepDay() {
        val update = simulation.advanceDay()
        if (update.newMonth || update.decisionExpired) mutableEconomy.value = simulation.economySnapshot()
        val eventful = update.pauseFor.isNotEmpty() || update.decisionNeeded || update.decisionExpired
        if (update.newMonth || update.budgetPassed || eventful || update.electionHeld) mutableNation.value = simulation.nationSnapshot()
        // 세계 소식은 매일 쌓일 수 있으므로 월초, 사건, 일주일마다 갱신한다.
        val electionNews = update.worldElectionNews || update.electionHeld
        if (update.newMonth || eventful || electionNews || simulation.date.dayOfWeek == DayOfWeek.MONDAY) {
            mutableWorld.value = simulation.worldSnapshot()
        }
        // 선거 결과와 선택지가 있는 사건은 플레이어가 놓치지 않도록 자동으로 일시정지한다.
        if (update.electionHeld || update.decisionNeeded || update.pauseFor.any { it in pauseCategories }) paused = true
        if (update.newMonth) autosave(capture())
    }

    private fun refreshAll() {
        mutableEconomy.value = simulation.economySnapshot()
        mutableNation.value = simulation.nationSnapshot()
        mutableWorld.value = simulation.worldSnapshot()
    }

    private fun publish() {
        mutablePolitics.value = simulation.politicsSnapshot()
        mutableClock.value = currentClock()
    }

    private fun currentClock() = ClockState(simulation.date, paused, speed)

    private sealed interface Command {
        data object TogglePause : Command

        data class SetSpeed(
            val speed: GameSpeed,
        ) : Command

        data object AdvanceDay : Command

        data class Perform(
            val action: CampaignAction,
        ) : Command

        data class Checkpoint(
            val pause: Boolean,
            val result: CompletableDeferred<CampaignSave>,
        ) : Command

        data class SetAutoPause(
            val category: EventCategory,
            val enabled: Boolean,
        ) : Command
    }
}
