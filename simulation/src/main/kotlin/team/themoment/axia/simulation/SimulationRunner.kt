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
import team.themoment.axia.simulation.economy.EconomySnapshot
import team.themoment.axia.simulation.nation.BudgetItem
import team.themoment.axia.simulation.nation.NationSnapshot
import team.themoment.axia.simulation.politics.PoliticsSnapshot
import team.themoment.axia.simulation.time.GameSpeed
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
        item: BudgetItem,
        target: Double,
    ) = send(Command.EditBudgetDraft(item, target))

    fun negotiateBudgetWith(party: String?) = send(Command.NegotiateBudget(party))

    fun clearBudgetDraft() = send(Command.ClearBudgetDraft)

    fun reviseStalledBudget() = send(Command.ReviseStalledBudget)

    fun introduceBudget() = send(Command.IntroduceBudget)

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
        }
        publish()
    }

    private fun stepDay() {
        val update = simulation.advanceDay()
        if (update.newMonth) mutableEconomy.value = simulation.economySnapshot()
        if (update.newMonth || update.budgetPassed) mutableNation.value = simulation.nationSnapshot()
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
            val item: BudgetItem,
            val target: Double,
        ) : Command

        data class NegotiateBudget(
            val party: String?,
        ) : Command

        data object ClearBudgetDraft : Command

        data object ReviseStalledBudget : Command

        data object IntroduceBudget : Command
    }
}
