package team.themoment.axia.simulation

import team.themoment.axia.simulation.world.World
import java.time.LocalDate

/**
 * 세계 상태와 하루 단위 진행을 담는다.
 *
 * [SimulationRunner]의 시뮬레이션 코루틴에서만 변경한다.
 */
class Simulation(
    val world: World,
    startDate: LocalDate = START_DATE,
) {
    var date: LocalDate = startDate
        private set

    fun advanceDay() {
        date = date.plusDays(1)
    }

    companion object {
        val START_DATE: LocalDate = LocalDate.of(2024, 1, 1)
    }
}
