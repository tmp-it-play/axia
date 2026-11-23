package team.themoment.axia.simulation.math

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** 표준정규분포 표본(Box-Muller). */
internal fun Random.nextGaussian(): Double {
    val u1 = 1.0 - nextDouble()
    val u2 = nextDouble()
    return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
}
