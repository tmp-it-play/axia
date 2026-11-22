package team.themoment.axia.map

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/** 지도 화면의 중심(지도 좌표)과 배율(지도 1단위당 픽셀). 좌우는 순환하고 위아래는 지도 끝에서 멈춘다. */
@Stable
class MapViewport {
    var center by mutableStateOf(Offset(MapProjection.x(INITIAL_LONGITUDE), MapProjection.y(INITIAL_LATITUDE)))
        private set
    var zoom by mutableFloatStateOf(0f)
        private set
    private var size = Size.Zero

    val visibleArea: Rect
        get() {
            val halfWidth = size.width / 2f / zoom
            val halfHeight = size.height / 2f / zoom
            return Rect(center.x - halfWidth, center.y - halfHeight, center.x + halfWidth, center.y + halfHeight)
        }

    fun resize(newSize: Size) {
        if (newSize.width <= 0f || newSize.height <= 0f) return
        size = newSize
        zoom = if (zoom == 0f) maxOf(minZoom(), newSize.width / MapProjection.WORLD_WIDTH) else zoom.coerceIn(minZoom(), maxZoom())
        center = clamped(center)
    }

    fun pan(screenDelta: Offset) {
        if (zoom == 0f) return
        center = clamped(center - screenDelta / zoom)
    }

    fun zoomAt(
        factor: Float,
        screenAnchor: Offset,
    ) {
        if (zoom == 0f) return
        val anchor = toMap(screenAnchor)
        zoom = (zoom * factor).coerceIn(minZoom(), maxZoom())
        center = clamped(anchor - (screenAnchor - size.center) / zoom)
    }

    fun toMap(screen: Offset): Offset = center + (screen - size.center) / zoom

    private fun minZoom() = size.height / MapProjection.worldHeight

    private fun maxZoom() = minZoom() * MAX_ZOOM_RATIO

    private fun clamped(point: Offset): Offset {
        val halfHeight = size.height / 2f / zoom
        val y =
            if (halfHeight * 2f >= MapProjection.worldHeight) {
                (MapProjection.top + MapProjection.bottom) / 2f
            } else {
                point.y.coerceIn(MapProjection.top + halfHeight, MapProjection.bottom - halfHeight)
            }
        return Offset(MapProjection.wrapX(point.x), y)
    }

    private val Size.center get() = Offset(width / 2f, height / 2f)

    private companion object {
        const val INITIAL_LONGITUDE = 15f
        const val INITIAL_LATITUDE = 25f
        const val MAX_ZOOM_RATIO = 20f
    }
}
