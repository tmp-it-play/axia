package team.themoment.axia.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import kotlin.math.exp
import kotlin.math.floor

/** 행정구역 하나가 화면에서 이 크기(dp)를 넘으면 그 나라의 행정구역 경계를 그린다. */
private const val REGION_BORDER_MIN_SIZE_DP = 34f
private const val SCROLL_ZOOM_SPEED = 0.12f
private const val LABEL_MIN_SCALE = 0.85f
private const val LABEL_MAX_SCALE = 2.2f
private const val LABEL_WIDTH_FILL = 0.85f
private const val LABEL_HEIGHT_FILL = 0.8f
private const val LABEL_HALO_DP = 2.5f

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WorldMap(
    model: WorldMapModel,
    fillColor: (CountryFill) -> Color,
    selectedRegionId: String?,
    onRegionClick: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewport = remember { MapViewport() }
    val currentOnRegionClick by rememberUpdatedState(onRegionClick)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle =
        MaterialTheme.typography.labelLarge.copy(
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        )
    val labelLayouts = remember(model, labelStyle, textMeasurer) { LabelLayouts(textMeasurer, labelStyle) }
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
            val copies = visibleCopies(viewport)
            copies.forEach { shift -> drawWorldCopy(model, viewport, shift, fillColor, selectedRegionId) }
            drawLabels(model, viewport, copies, labelLayouts)
        }
    }
}

/** 화면에 보이는 세계 사본들의 x 이동량. 좌우 순환 때문에 둘 이상 보일 수 있다. */
private fun visibleCopies(viewport: MapViewport): List<Float> {
    val visible = viewport.visibleArea
    val first = floor((visible.left - MapProjection.WEST_EDGE) / MapProjection.WORLD_WIDTH).toInt()
    val last = floor((visible.right - MapProjection.WEST_EDGE) / MapProjection.WORLD_WIDTH).toInt()
    return (first..last).map { it * MapProjection.WORLD_WIDTH }
}

private fun DrawScope.drawWorldCopy(
    model: WorldMapModel,
    viewport: MapViewport,
    shift: Float,
    fillColor: (CountryFill) -> Color,
    selectedRegionId: String?,
) {
    val zoom = viewport.zoom
    val pixelsPerDp = density
    val area = viewport.visibleArea.translate(-shift, 0f)
    val coastStroke = Stroke(width = 1.0f * pixelsPerDp / zoom)
    val countryStroke = Stroke(width = 1.1f * pixelsPerDp / zoom)
    val regionStroke = Stroke(width = 0.6f * pixelsPerDp / zoom)
    val selectionStroke = Stroke(width = 1.8f * pixelsPerDp / zoom)
    val regionBorderMinSize = REGION_BORDER_MIN_SIZE_DP * pixelsPerDp / zoom
    val selectedCountryId = selectedRegionId?.let(model::countryOf)

    withTransform({
        translate(left = size.width / 2f - (viewport.center.x - shift) * zoom, top = size.height / 2f - viewport.center.y * zoom)
        scale(zoom, zoom, pivot = Offset.Zero)
    }) {
        model.countryFills.forEach { fill ->
            if (fill.bounds.overlaps(area)) {
                drawPath(fill.path, fillColor(fill))
            }
        }
        if (selectedCountryId != null) {
            drawPath(model.countryHighlight(selectedCountryId), MapPalette.selectedCountry)
        }
        model.regionBorders.forEach { borders ->
            if (borders.typicalRegionSize >= regionBorderMinSize && borders.bounds.overlaps(area)) {
                drawPath(borders.path, MapPalette.regionBorder, style = regionStroke)
            }
        }
        drawBorderTiles(model, BorderKind.COUNTRY, area, MapPalette.countryBorder, countryStroke)
        drawBorderTiles(model, BorderKind.COAST, area, MapPalette.coast, coastStroke)
        if (selectedRegionId != null) {
            val region = model.regionHighlight(selectedRegionId)
            drawPath(region, MapPalette.selectedRegion)
            drawPath(region, MapPalette.selectedOutline, style = selectionStroke)
        }
    }
}

private fun DrawScope.drawBorderTiles(
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

/**
 * 국가 이름을 화면 좌표로 그린다. 표기 위치를 지나는 국토 폭에 이름이 들어갈 때만 그리며, 넓은 나라부터 배치하고
 * 이미 배치된 이름과 겹치면 생략한다.
 */
private fun DrawScope.drawLabels(
    model: WorldMapModel,
    viewport: MapViewport,
    copies: List<Float>,
    layouts: LabelLayouts,
) {
    val zoom = viewport.zoom
    val placed = mutableListOf<Rect>()
    val screen = Rect(0f, 0f, size.width, size.height)
    model.labels.forEach { label ->
        val layout = layouts[label]
        val textWidth = layout.size.width.toFloat()
        val textHeight = layout.size.height.toFloat()
        val scale =
            minOf(
                label.space.width * zoom * LABEL_WIDTH_FILL / textWidth,
                label.space.height * zoom * LABEL_HEIGHT_FILL / textHeight,
                LABEL_MAX_SCALE,
            )
        if (scale < LABEL_MIN_SCALE) return@forEach
        copies.forEach { shift ->
            val center =
                Offset(
                    x = (label.space.anchor.x + shift - viewport.center.x) * zoom + size.width / 2f,
                    y = (label.space.anchor.y - viewport.center.y) * zoom + size.height / 2f,
                )
            val bounds = Rect(center = center, radius = 0f).inflateTo(textWidth * scale, textHeight * scale)
            if (bounds.overlaps(screen) && placed.none(bounds::overlaps)) {
                placed += bounds
                withTransform({
                    translate(left = bounds.left, top = bounds.top)
                    scale(scale, scale, pivot = Offset.Zero)
                }) {
                    drawText(layout, color = MapPalette.labelHalo, drawStyle = Stroke(width = LABEL_HALO_DP * density / scale))
                    drawText(layout, color = MapPalette.label)
                }
            }
        }
    }
}

private fun Rect.inflateTo(
    width: Float,
    height: Float,
): Rect = Rect(center.x - width / 2f, center.y - height / 2f, center.x + width / 2f, center.y + height / 2f)

private class LabelLayouts(
    private val measurer: TextMeasurer,
    private val style: TextStyle,
) {
    private val cache = mutableMapOf<String, TextLayoutResult>()

    operator fun get(label: CountryLabel): TextLayoutResult = cache.getOrPut(label.countryId) { measurer.measure(label.name, style) }
}
