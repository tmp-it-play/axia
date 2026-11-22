package team.themoment.axia.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize
import kotlin.math.exp
import kotlin.math.floor

/** 행정구역 경계를 그리기 시작하는 배율. 지도 1단위(경도 1도)당 dp. */
private const val REGION_BORDER_MIN_ZOOM_DP = 9f
private const val SCROLL_ZOOM_SPEED = 0.12f

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WorldMap(
    model: WorldMapModel,
    selectedRegionId: String?,
    onRegionClick: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewport = remember { MapViewport() }
    val currentOnRegionClick by rememberUpdatedState(onRegionClick)
    Canvas(
        modifier =
            modifier
                .clipToBounds()
                .onSizeChanged { viewport.resize(it.toSize()) }
                .pointerInput(viewport) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        viewport.pan(dragAmount)
                    }
                }.pointerInput(viewport, model) {
                    detectTapGestures { position -> currentOnRegionClick(model.regionAt(viewport.toMap(position))) }
                }.onPointerEvent(PointerEventType.Scroll) { event ->
                    val change = event.changes.first()
                    viewport.zoomAt(exp(-change.scrollDelta.y * SCROLL_ZOOM_SPEED), change.position)
                    change.consume()
                },
    ) {
        drawRect(MapPalette.ocean)
        if (viewport.zoom > 0f) {
            drawWorld(model, viewport, selectedRegionId)
        }
    }
}

private fun DrawScope.drawWorld(
    model: WorldMapModel,
    viewport: MapViewport,
    selectedRegionId: String?,
) {
    val zoom = viewport.zoom
    val pixelsPerDp = density
    val visible = viewport.visibleArea
    val firstCopy = floor((visible.left - MapProjection.WEST_EDGE) / MapProjection.WORLD_WIDTH).toInt()
    val lastCopy = floor((visible.right - MapProjection.WEST_EDGE) / MapProjection.WORLD_WIDTH).toInt()
    val coastStroke = Stroke(width = 1.0f * pixelsPerDp / zoom)
    val countryStroke = Stroke(width = 1.1f * pixelsPerDp / zoom)
    val regionStroke = Stroke(width = 0.6f * pixelsPerDp / zoom)
    val selectionStroke = Stroke(width = 1.8f * pixelsPerDp / zoom)
    val selectedCountryId = selectedRegionId?.let(model::countryOf)

    for (copy in firstCopy..lastCopy) {
        val shift = copy * MapProjection.WORLD_WIDTH
        val area = visible.translate(-shift, 0f)
        withTransform({
            translate(left = size.width / 2f - (viewport.center.x - shift) * zoom, top = size.height / 2f - viewport.center.y * zoom)
            scale(zoom, zoom, pivot = Offset.Zero)
        }) {
            model.countryFills.forEach { fill ->
                if (fill.bounds.overlaps(area)) {
                    val color = if (fill.unclaimed) MapPalette.unclaimed else MapPalette.countries[fill.colorIndex]
                    drawPath(fill.path, color)
                }
            }
            if (selectedCountryId != null) {
                drawPath(model.countryHighlight(selectedCountryId), MapPalette.selectedCountry)
            }
            if (zoom / pixelsPerDp >= REGION_BORDER_MIN_ZOOM_DP) {
                drawBorders(model, BorderKind.REGION, area, MapPalette.regionBorder, regionStroke)
            }
            drawBorders(model, BorderKind.COUNTRY, area, MapPalette.countryBorder, countryStroke)
            drawBorders(model, BorderKind.COAST, area, MapPalette.coast, coastStroke)
            if (selectedRegionId != null) {
                val region = model.regionHighlight(selectedRegionId)
                drawPath(region, MapPalette.selectedRegion)
                drawPath(region, MapPalette.selectedOutline, style = selectionStroke)
            }
        }
    }
}

private fun DrawScope.drawBorders(
    model: WorldMapModel,
    kind: BorderKind,
    area: Rect,
    color: Color,
    stroke: Stroke,
) {
    model.borderTiles[kind].orEmpty().forEach { tile ->
        if (tile.bounds.overlaps(area)) {
            drawPath(tile.path, color, style = stroke)
        }
    }
}
