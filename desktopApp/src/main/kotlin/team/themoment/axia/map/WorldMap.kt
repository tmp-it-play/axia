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
import androidx.compose.ui.graphics.Shadow
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

/** 국가명 글자 크기 한계(dp). 이보다 작아야 하면 그리지 않는다. */
private const val LABEL_MIN_SIZE_DP = 10f
private const val LABEL_MAX_SIZE_DP = 44f
private const val LABEL_BASE_SIZE_SP = 20f
private const val LABEL_THICKNESS_FILL = 0.6f
private const val LABEL_LENGTH_FILL = 0.92f
private const val LABEL_SPREAD_FILL = 0.85f

/** 글자 사이를 벌릴 수 있는 최대 간격(글자 크기 대비). HOI4처럼 긴 국토에서는 글자를 넓게 펼친다. */
private const val LABEL_MAX_TRACKING = 1.1f
private const val LABEL_OUTLINE_DP = 1.2f
private const val LABEL_SHADOW_OFFSET_DP = 1.2f
private const val LABEL_SHADOW_BLUR_DP = 3.5f

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
    val labelStyle = DossierType.mapLabel(LABEL_BASE_SIZE_SP.sp)
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
 * 국가 이름을 국토 모양을 따라 휜 곡선 위에 한 글자씩 그린다(HOI4 방식). 글자 크기는 국토 두께와 곡선 길이에 맞추고,
 * 공간이 남으면 글자 사이를 벌린다. 넓은 나라부터 배치하고 이미 배치된 이름과 겹치면 생략한다.
 * RGFX White Map Font처럼 옅은 흰 글자에 부드러운 그림자를 깔고, 밝은 바탕에서도 읽히도록 아주 옅은 외곽선을 더한다.
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
    val outline = LABEL_OUTLINE_DP * density
    val shadowOffset = LABEL_SHADOW_OFFSET_DP * density
    val shadowBlur = LABEL_SHADOW_BLUR_DP * density
    model.labels.forEach { label ->
        val glyphs = layouts.glyphs(label)
        if (glyphs.isEmpty()) return@forEach
        val naturalWidth = glyphs.sumOf { it.size.width.toDouble() }.toFloat()
        val glyphHeight = glyphs.maxOf { it.size.height }.toFloat()
        val lengthPx = label.length * zoom
        val scale =
            minOf(
                label.thickness * zoom * LABEL_THICKNESS_FILL / glyphHeight,
                lengthPx * LABEL_LENGTH_FILL / naturalWidth,
                LABEL_MAX_SIZE_DP * density / glyphHeight,
            )
        if (scale * glyphHeight < LABEL_MIN_SIZE_DP * density) return@forEach
        val textWidth = naturalWidth * scale
        val gaps = (glyphs.size - 1).coerceAtLeast(1)
        val tracking =
            if (glyphs.size >
                1
            ) {
                ((lengthPx * LABEL_SPREAD_FILL - textWidth) / gaps).coerceIn(0f, glyphHeight * scale * LABEL_MAX_TRACKING)
            } else {
                0f
            }
        val totalWidth = textWidth + tracking * (glyphs.size - 1)
        copies.forEach { shift ->
            val origin = Offset(size.width / 2f - (viewport.center.x - shift) * zoom, size.height / 2f - viewport.center.y * zoom)
            val screenBounds =
                Rect(
                    origin.x + label.bounds.left * zoom,
                    origin.y + label.bounds.top * zoom,
                    origin.x + label.bounds.right * zoom,
                    origin.y + label.bounds.bottom * zoom,
                )
            if (!screenBounds.overlaps(screen)) return@forEach
            val placements = mutableListOf<Pair<Offset, Float>>()
            var cursor = (lengthPx - totalWidth) / 2f
            glyphs.forEach { glyph ->
                val advance = glyph.size.width * scale
                val (point, angle) = label.placeAt((cursor + advance / 2f) / zoom)
                placements += Offset(origin.x + point.x * zoom, origin.y + point.y * zoom) to angle
                cursor += advance + tracking
            }
            val half = glyphHeight * scale / 2f
            val footprint =
                Rect(
                    placements.minOf { it.first.x } - half,
                    placements.minOf { it.first.y } - half,
                    placements.maxOf { it.first.x } + half,
                    placements.maxOf { it.first.y } + half,
                )
            if (placed.any(footprint::overlaps)) return@forEach
            placed += footprint
            glyphs.zip(placements).forEach { (glyph, placement) ->
                withTransform({
                    translate(left = placement.first.x, top = placement.first.y)
                    rotate(degrees = Math.toDegrees(placement.second.toDouble()).toFloat(), pivot = Offset.Zero)
                    scale(scale, scale, pivot = Offset.Zero)
                }) {
                    val topLeft = Offset(-glyph.size.width / 2f, -glyph.size.height / 2f)
                    val shadow = Shadow(MapPalette.labelShadow, Offset(shadowOffset / scale, shadowOffset / scale), shadowBlur / scale)
                    drawText(glyph, color = MapPalette.labelOutline, topLeft = topLeft, drawStyle = Stroke(width = outline / scale))
                    drawText(glyph, color = MapPalette.label, topLeft = topLeft, shadow = shadow)
                }
            }
        }
    }
}

/** 국가명을 글자 단위로 측정해 둔다. 곡선 위에 한 글자씩 배치하기 위함이다. */
private class LabelLayouts(
    private val measurer: TextMeasurer,
    private val style: TextStyle,
) {
    private val cache = mutableMapOf<Char, TextLayoutResult>()

    fun glyphs(label: CountryLabel): List<TextLayoutResult> =
        label.name.filterNot(Char::isWhitespace).map { char -> cache.getOrPut(char) { measurer.measure(char.toString(), style) } }
}
