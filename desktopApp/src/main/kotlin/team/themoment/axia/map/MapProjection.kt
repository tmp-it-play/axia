package team.themoment.axia.map

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/** 밀러 도법. 경도는 그대로, 위도는 도 단위에 맞춘 밀러 좌표로 바꾼다. 화면처럼 아래로 갈수록 y가 커진다. */
object MapProjection {
    const val WORLD_WIDTH = 360f
    const val WEST_EDGE = -180f

    fun x(longitude: Float): Float = longitude

    fun y(latitude: Float): Float {
        val phi = latitude.toDouble() * PI / 180.0
        return (-1.25 * ln(tan(PI / 4.0 + 0.4 * phi)) * 180.0 / PI).toFloat()
    }

    val top: Float = y(90f)
    val bottom: Float = y(-90f)
    val worldHeight: Float = bottom - top

    /** 경도 방향으로 순환하는 x 좌표를 [-180, 180) 범위로 되돌린다. */
    fun wrapX(x: Float): Float = ((x - WEST_EDGE) % WORLD_WIDTH + WORLD_WIDTH) % WORLD_WIDTH + WEST_EDGE
}
