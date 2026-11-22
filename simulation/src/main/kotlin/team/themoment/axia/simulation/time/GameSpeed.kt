package team.themoment.axia.simulation.time

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@JvmInline
value class GameSpeed(
    val multiplier: Int,
) {
    init {
        require(multiplier in MIN..MAX) { "Unsupported speed: $multiplier" }
    }

    /** 이 배속에서 하루가 지나는 데 걸리는 실제 시간. */
    val dayDuration: Duration get() = DAY_AT_NORMAL_SPEED / multiplier

    companion object {
        const val MIN = 1
        const val MAX = 5
        private val DAY_AT_NORMAL_SPEED = 1.seconds

        val NORMAL = GameSpeed(MIN)
        val all: List<GameSpeed> = (MIN..MAX).map(::GameSpeed)
    }
}
