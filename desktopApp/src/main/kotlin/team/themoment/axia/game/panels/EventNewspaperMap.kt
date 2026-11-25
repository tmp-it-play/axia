package team.themoment.axia.game.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.map.MapProjection
import team.themoment.axia.map.WorldMapModel
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType

private val seaInk = Color(0xFFD5D0BC)
private val landInks = listOf(Color(0xFFECE4CF), Color(0xFFE0D9C3), Color(0xFFE7DDC4))
private val locatorInk = Color(0xFF80463D)

/** 기사 발생 국가와 주변 지역을 캠페인과 같은 지도 형상으로 인쇄한다. */
@Composable
internal fun EventLocationMap(
    model: WorldMapModel,
    owners: Map<String, String>,
    countryId: String,
    country: String,
    modifier: Modifier = Modifier,
) {
    val label = remember(model, owners, countryId) { model.labelsFor(owners).firstOrNull { it.countryId == countryId } }
    val fill = remember(model, countryId) { model.countryFills.firstOrNull { it.countryId == countryId } } ?: return
    val center = label?.placeAt(label.length / 2f)?.first ?: fill.bounds.center
    val extent = ((label?.length ?: fill.bounds.width) * 4f).coerceIn(22f, 200f)
    val ownershipChanges =
        remember(model, owners) { owners.map { (region, owner) -> model.regionHighlight(region) to owner } }
    Box(modifier.clipToBounds().background(seaInk)) {
        Canvas(Modifier.fillMaxSize()) {
            val zoom = size.width / extent
            val halfHeight = size.height / (2f * zoom)
            val coastStroke = Stroke(0.65.dp.toPx() / zoom)
            for (wrap in -1..1) {
                val x = center.x + wrap * MapProjection.WORLD_WIDTH
                val frame = Rect(x - extent / 2f, center.y - halfHeight, x + extent / 2f, center.y + halfHeight)
                withTransform({
                    scale(zoom, zoom, Offset.Zero)
                    translate(-frame.left, -frame.top)
                }) {
                    model.countryFills.forEach { fill ->
                        if (fill.bounds.overlaps(frame)) {
                            val ink = if (fill.countryId == countryId) locatorInk else landInks[fill.colorIndex.mod(landInks.size)]
                            drawPath(fill.path, ink)
                        }
                    }
                    ownershipChanges.forEach { (path, owner) ->
                        if (path.getBounds().overlaps(frame)) drawPath(path, if (owner == countryId) locatorInk else landInks.first())
                    }
                    model.coasts.forEach { coast ->
                        if (coast.bounds.overlaps(frame)) {
                            drawPath(coast.path, DossierColors.inkMuted.copy(alpha = 0.65f), style = coastStroke)
                        }
                    }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).background(DossierColors.paper).padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.size(8.dp).background(locatorInk))
            Text("발생 국가 · $country", Modifier.padding(start = 5.dp), color = DossierColors.ink, style = DossierType.number(11.sp))
        }
    }
}
