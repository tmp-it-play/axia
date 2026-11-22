package team.themoment.axia.simulation

import team.themoment.axia.simulation.economy.EconomySnapshot
import team.themoment.axia.simulation.economy.WorldEconomy
import team.themoment.axia.simulation.world.World
import java.time.LocalDate
import kotlin.random.Random

/**
 * 세계 상태와 하루 단위 진행을 담는다. 경제는 매월 1일에 갱신한다.
 *
 * [SimulationRunner]의 시뮬레이션 코루틴에서만 변경한다.
 */
class Simulation(
    val world: World,
    private val economy: WorldEconomy,
    seed: Long = DEFAULT_SEED,
    startDate: LocalDate = START_DATE,
) {
    private val random = Random(seed)

    var date: LocalDate = startDate
        private set

    /** 하루를 진행하고, 경제가 갱신됐으면 true를 돌려준다. */
    fun advanceDay(): Boolean {
        date = date.plusDays(1)
        if (date.dayOfMonth != 1) return false
        economy.advanceMonth(random)
        return true
    }

    fun economySnapshot(): EconomySnapshot = economy.snapshot(date)

    companion object {
        val START_DATE: LocalDate = LocalDate.of(2024, 1, 1)
        const val DEFAULT_SEED = 20240101L
    }
}
