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
import team.themoment.axia.simulation.nation.NationSnapshot
import team.themoment.axia.simulation.nation.SpendingKind
import team.themoment.axia.simulation.nation.TaxKind
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

    val clock: StateFlow<ClockState> = mutableClock.asStateFlow()

    /** 경제가 갱신될 때(매월 1일)마다 바뀐다. */
    val economy: StateFlow<EconomySnapshot> = mutableEconomy.asStateFlow()

    /** 플레이어 국가. 매월 갱신되고, 예산 목표를 바꾸면 바로 갱신된다. */
    val nation: StateFlow<NationSnapshot> = mutableNation.asStateFlow()

    init {
        scope.launch(dispatcher) { run() }
    }

    fun togglePause() = send(Command.TogglePause)

    fun setSpeed(speed: GameSpeed) = send(Command.SetSpeed(speed))

    /** 일시정지 상태에서만 하루를 진행한다. */
    fun advanceDay() = send(Command.AdvanceDay)

    /** 목표 세율을 정한다. 다음 달부터 서서히 반영된다. */
    fun setTaxRate(
        kind: TaxKind,
        rate: Double,
    ) = send(Command.SetTaxRate(kind, rate))

    /** 목표 지출(GDP 대비)을 정한다. 다음 달부터 서서히 반영된다. */
    fun setSpending(
        kind: SpendingKind,
        level: Double,
    ) = send(Command.SetSpending(kind, level))

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

            is Command.SetTaxRate -> {
                simulation.setTaxRate(command.kind, command.rate)
                mutableNation.value = simulation.nationSnapshot()
            }

            is Command.SetSpending -> {
                simulation.setSpending(command.kind, command.level)
                mutableNation.value = simulation.nationSnapshot()
            }
        }
        publish()
    }

    private fun stepDay() {
        if (simulation.advanceDay()) {
            mutableEconomy.value = simulation.economySnapshot()
            mutableNation.value = simulation.nationSnapshot()
        }
    }

    private fun publish() {
        mutableClock.value = currentClock()
    }

    private fun currentClock() = ClockState(simulation.date, paused, speed)

    private sealed interface Command {
        data object TogglePause : Command

        data class SetSpeed(
            val speed: GameSpeed,
        ) : Command

        data object AdvanceDay : Command

        data class SetTaxRate(
            val kind: TaxKind,
            val rate: Double,
        ) : Command

        data class SetSpending(
            val kind: SpendingKind,
            val level: Double,
        ) : Command
    }
}
