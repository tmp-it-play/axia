package team.themoment.axia.simulation

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
import team.themoment.axia.simulation.diplomacy.DiplomaticAction
import team.themoment.axia.simulation.economy.EconomySnapshot
import team.themoment.axia.simulation.events.EventCategory
import team.themoment.axia.simulation.military.MilitaryAction
import team.themoment.axia.simulation.military.Mobilization
import team.themoment.axia.simulation.nation.NationSnapshot
import team.themoment.axia.simulation.nation.PolicyItem
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
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var paused = true
    private var speed = GameSpeed.NORMAL
    private val mutableClock = MutableStateFlow(currentClock())
    private val mutableEconomy = MutableStateFlow(simulation.economySnapshot())
    private val mutableNation = MutableStateFlow(simulation.nationSnapshot())

    private val mutableWorld = MutableStateFlow(simulation.worldSnapshot())

    /** 세계 정치·외교·군사·사건. 매월과 플레이어 행위, 새 소식이 있을 때 갱신된다. */
    val world: StateFlow<WorldSnapshot> = mutableWorld.asStateFlow()

    /** 자동 일시정지할 사건 분류. 선거와 선택지가 있는 사건은 항상 멈춘다. */
    private var pauseCategories: Set<EventCategory> = EventCategory.entries.filter { it.pauseByDefault }.toSet()
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
        scope.launch(dispatcher) { run() }
    }

    fun togglePause() = send(Command.TogglePause)

    fun setSpeed(speed: GameSpeed) = send(Command.SetSpeed(speed))

    /** 일시정지 상태에서만 하루를 진행한다. */
    fun advanceDay() = send(Command.AdvanceDay)

    fun editBudgetDraft(
        item: PolicyItem,
        target: Double,
    ) = send(Command.EditBudgetDraft(item, target))

    fun negotiateBudgetWith(party: String?) = send(Command.NegotiateBudget(party))

    fun clearBudgetDraft() = send(Command.ClearBudgetDraft)

    fun reviseStalledBudget() = send(Command.ReviseStalledBudget)

    fun introduceBudget() = send(Command.IntroduceBudget)

    /** 조건을 만족하면 6주 뒤 조기 총선을 치른다. */
    fun callEarlyElection() = send(Command.CallEarlyElection)

    fun directPolicyRate(rate: Double) = send(Command.DirectPolicyRate(rate))

    fun actOnOrganization(
        organizationId: Int,
        action: GovernmentAction,
    ) = send(Command.ActOnOrganization(organizationId, action))

    fun renameOrganization(
        organizationId: Int,
        name: String,
    ) = send(Command.RenameOrganization(organizationId, name))

    fun renamePerson(
        personId: Int,
        name: String,
    ) = send(Command.RenamePerson(personId, name))

    fun diplomaticAction(
        target: String,
        action: DiplomaticAction,
    ) = send(Command.Diplomatic(target, action))

    fun militaryAction(
        target: String,
        action: MilitaryAction,
    ) = send(Command.Military(target, action))

    fun setMobilization(level: Mobilization) = send(Command.SetMobilization(level))

    fun chooseEventOption(
        eventId: Int,
        index: Int,
    ) = send(Command.ChooseEvent(eventId, index))

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

    private fun apply(command: Command) {
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

            is Command.EditBudgetDraft -> {
                simulation.editBudgetDraft(command.item, command.target)
            }

            is Command.NegotiateBudget -> {
                simulation.negotiateBudgetWith(command.party)
            }

            Command.ClearBudgetDraft -> {
                simulation.clearBudgetDraft()
            }

            Command.ReviseStalledBudget -> {
                simulation.reviseStalledBudget()
            }

            Command.IntroduceBudget -> {
                simulation.introduceBudget()
            }

            Command.CallEarlyElection -> {
                simulation.callEarlyElection()
            }

            is Command.DirectPolicyRate -> {
                simulation.directPolicyRate(command.rate)
            }

            is Command.ActOnOrganization -> {
                simulation.actOnOrganization(command.organizationId, command.action)
                mutableNation.value = simulation.nationSnapshot()
            }

            is Command.RenameOrganization -> {
                simulation.renameOrganization(command.organizationId, command.name)
                mutableNation.value = simulation.nationSnapshot()
            }

            is Command.RenamePerson -> {
                simulation.renamePerson(command.personId, command.name)
                mutableWorld.value = simulation.worldSnapshot()
            }

            is Command.Diplomatic -> {
                simulation.diplomaticAction(command.target, command.action)
                refreshAll()
            }

            is Command.Military -> {
                simulation.militaryAction(command.target, command.action)
                refreshAll()
            }

            is Command.SetMobilization -> {
                simulation.setMobilization(command.level)
                refreshAll()
            }

            is Command.ChooseEvent -> {
                simulation.chooseEventOption(command.eventId, command.index)
                refreshAll()
            }

            is Command.SetAutoPause -> {
                pauseCategories = if (command.enabled) pauseCategories + command.category else pauseCategories - command.category
                mutablePauseSettings.value = pauseCategories
            }
        }
        publish()
    }

    private fun stepDay() {
        val update = simulation.advanceDay()
        if (update.newMonth) mutableEconomy.value = simulation.economySnapshot()
        val eventful = update.pauseFor.isNotEmpty() || update.decisionNeeded
        if (update.newMonth || update.budgetPassed || eventful || update.electionHeld) mutableNation.value = simulation.nationSnapshot()
        // 세계 소식은 매일 쌓일 수 있으므로 월초, 사건, 일주일마다 갱신한다.
        if (update.newMonth || eventful || simulation.date.dayOfWeek == DayOfWeek.MONDAY) mutableWorld.value = simulation.worldSnapshot()
        // 선거 결과와 선택지가 있는 사건은 플레이어가 놓치지 않도록 자동으로 일시정지한다.
        if (update.electionHeld || update.decisionNeeded || update.pauseFor.any { it in pauseCategories }) paused = true
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

        data class EditBudgetDraft(
            val item: PolicyItem,
            val target: Double,
        ) : Command

        data class NegotiateBudget(
            val party: String?,
        ) : Command

        data object ClearBudgetDraft : Command

        data object ReviseStalledBudget : Command

        data object IntroduceBudget : Command

        data object CallEarlyElection : Command

        data class DirectPolicyRate(
            val rate: Double,
        ) : Command

        data class ActOnOrganization(
            val organizationId: Int,
            val action: GovernmentAction,
        ) : Command

        data class RenameOrganization(
            val organizationId: Int,
            val name: String,
        ) : Command

        data class RenamePerson(
            val personId: Int,
            val name: String,
        ) : Command

        data class Diplomatic(
            val target: String,
            val action: DiplomaticAction,
        ) : Command

        data class Military(
            val target: String,
            val action: MilitaryAction,
        ) : Command

        data class SetMobilization(
            val level: Mobilization,
        ) : Command

        data class ChooseEvent(
            val eventId: Int,
            val index: Int,
        ) : Command

        data class SetAutoPause(
            val category: EventCategory,
            val enabled: Boolean,
        ) : Command
    }
}
