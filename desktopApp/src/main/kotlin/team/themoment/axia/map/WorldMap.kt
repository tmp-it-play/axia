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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import team.themoment.axia.theme.DossierType
import kotlin.math.exp
import kotlin.math.floor

/** 행정구역 하나가 화면에서 이 크기(dp)를 넘으면 그 나라의 행정구역 경계를 그린다. */
private const val REGION_BORDER_MIN_SIZE_DP = 34f
private const val SCROLL_ZOOM_SPEED = 0.12f

/** 기준 글꼴은 한 번만 측정한다. 실제 글자 높이와 간격은 지도 좌표로 정한다. */
private const val LABEL_BASE_SIZE_SP = 32
private const val LABEL_LENGTH_FILL = 0.82f
private const val LABEL_SPREAD_FILL = 0.84f
private const val LABEL_MAX_TRACKING = 1.5f

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WorldMap(
    model: WorldMapModel,
    fillColor: (CountryFill) -> Color,
    selectedRegionId: String?,
    onRegionClick: (String?) -> Unit,
    modifier: Modifier = Modifier,
    /** 지역 단위로 다시 칠할 나라와 지역 색. 색이 null이면 나라 색을 그대로 둔다. */
    regionOverlay: Pair<String, (String) -> Color?>? = null,
    regionOwners: Map<String, String> = emptyMap(),
    viewport: MapViewport = remember(model) { MapViewport() },
) {
    val borders = remember(model, regionOwners) { model.politicalBorders(regionOwners) }
    val currentOnRegionClick by rememberUpdatedState(onRegionClick)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = DossierType.mapLabel(LABEL_BASE_SIZE_SP.sp)
    val curves = remember(model, regionOwners) { model.labelsFor(regionOwners) }
    val lettering = remember(curves, labelStyle, textMeasurer) { layoutLabels(curves, textMeasurer, labelStyle) }
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
            copies.forEach { shift ->
                drawWorldCopy(model, viewport, shift, fillColor, selectedRegionId, regionOverlay, regionOwners, borders)
            }
            copies.forEach { shift ->
                drawLabels(viewport, shift, lettering)
            }
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
    regionOverlay: Pair<String, (String) -> Color?>?,
    regionOwners: Map<String, String>,
    borders: WorldMapModel.PoliticalBorders,
) {
    val zoom = viewport.zoom
    val pixelsPerDp = density
    val area = viewport.visibleArea.translate(-shift, 0f)
    val coastStroke = Stroke(width = 1.0f * pixelsPerDp / zoom)
    val countryStroke = Stroke(width = 1.1f * pixelsPerDp / zoom)
    val regionStroke = Stroke(width = 0.6f * pixelsPerDp / zoom)
    val selectionStroke = Stroke(width = 1.8f * pixelsPerDp / zoom)
    val regionBorderMinSize = REGION_BORDER_MIN_SIZE_DP * pixelsPerDp / zoom
    val selectedCountryId = selectedRegionId?.let { regionOwners[it] ?: model.countryOf(it) }

    withTransform({
        translate(left = size.width / 2f - (viewport.center.x - shift) * zoom, top = size.height / 2f - viewport.center.y * zoom)
        scale(zoom, zoom, pivot = Offset.Zero)
    }) {
        model.countryFills.forEach { fill ->
            if (fill.bounds.overlaps(area)) {
                drawPath(fill.path, fillColor(fill))
            }
        }
        regionOverlay?.let { (countryId, color) ->
            model.regionsOf(countryId).forEach { shape ->
                if (shape.bounds.overlaps(area)) color(shape.id)?.let { drawPath(model.regionHighlight(shape.id), it) }
            }
        }
        if (selectedCountryId != null) {
            drawPath(model.countryHighlight(selectedCountryId), MapPalette.selectedCountry)
        }
        regionOwners.forEach { (regionId, owner) ->
            val path = model.regionHighlight(regionId)
            val fill = model.countryFills.first { it.countryId == owner }
            val overlay = regionOverlay?.takeIf { it.first == owner }?.second?.invoke(regionId)
            drawPath(path, overlay ?: fillColor(fill))
            if (selectedCountryId == owner) drawPath(path, MapPalette.selectedCountry)
        }
        borders.regions.forEach { region ->
            if (region.typicalRegionSize >= regionBorderMinSize && region.bounds.overlaps(area)) {
                drawPath(region.path, MapPalette.regionBorder, style = regionStroke)
            }
        }
        drawBorderTiles(borders.countries, area, MapPalette.countryBorder, countryStroke)
        drawBorderTiles(model.coasts, area, MapPalette.coast, coastStroke)
        if (selectedRegionId != null) {
            val region = model.regionHighlight(selectedRegionId)
            drawPath(region, MapPalette.selectedRegion)
            drawPath(region, MapPalette.selectedOutline, style = selectionStroke)
        }
    }
}

private fun DrawScope.drawBorderTiles(
    tiles: List<BorderTile>,
    area: Rect,
    color: Color,
    stroke: Stroke,
) {
    tiles.forEach { tile ->
        if (tile.bounds.overlaps(area)) {
            drawPath(tile.path, color, style = stroke)
        }
    }
}

/** 위치·방향·크기를 투영 좌표로 저장해 국명도 국경과 같은 배율로 움직인다. */
private class MapLetter(
    val glyph: TextLayoutResult,
    val center: Offset,
    val angle: Float,
)

private class MapLettering(
    val country: CountryLabel,
    val scale: Float,
    val letters: List<MapLetter>,
)

private fun layoutLabels(
    labels: List<CountryLabel>,
    measurer: TextMeasurer,
    style: TextStyle,
): List<MapLettering> {
    val glyphCache = mutableMapOf<Char, TextLayoutResult>()
    return labels.map { country ->
        val glyphs = country.name.map { glyphCache.getOrPut(it) { measurer.measure(it.toString(), style) } }
        val naturalWidth = glyphs.sumOf { it.size.width }
        val height = glyphs.maxOf { it.size.height }
        val scale = minOf(country.maxGlyphHeight / height, country.length * LABEL_LENGTH_FILL / naturalWidth)
        val gaps = (glyphs.size - 1).coerceAtLeast(1)
        val spread = country.length * LABEL_SPREAD_FILL - naturalWidth * scale
        val tracking = (spread / gaps).coerceIn(0f, height * scale * LABEL_MAX_TRACKING)
        var cursor = (country.length - naturalWidth * scale - tracking * (glyphs.size - 1)) / 2f
        val letters =
            glyphs.map { glyph ->
                val width = glyph.size.width * scale
                val (point, radians) = country.placeAt(cursor + width / 2f)
                cursor += width + tracking
                MapLetter(glyph, point, Math.toDegrees(radians.toDouble()).toFloat())
            }
        MapLettering(country, scale, letters)
    }
}

private fun DrawScope.drawLabels(
    viewport: MapViewport,
    shift: Float,
    lettering: List<MapLettering>,
) {
    val zoom = viewport.zoom
    val area = viewport.visibleArea.translate(-shift, 0f)
    withTransform({
        translate(left = size.width / 2f - (viewport.center.x - shift) * zoom, top = size.height / 2f - viewport.center.y * zoom)
        scale(zoom, zoom, pivot = Offset.Zero)
    }) {
        lettering.forEach { label ->
            if (!label.country.bounds.overlaps(area)) return@forEach
            label.letters.forEach { letter ->
                withTransform({
                    translate(letter.center.x, letter.center.y)
                    rotate(letter.angle, pivot = Offset.Zero)
                    scale(label.scale, label.scale, pivot = Offset.Zero)
                }) {
                    drawText(
                        letter.glyph,
                        color = MapPalette.countryName,
                        topLeft = Offset(-letter.glyph.size.width / 2f, -letter.glyph.size.height / 2f),
                    )
                }
            }
        }
    }
}
