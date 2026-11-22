package team.themoment.axia.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import team.themoment.axia.simulation.world.CountryKind
import team.themoment.axia.simulation.world.World
import kotlin.math.floor

class CountryFill(
    val countryId: String,
    val unclaimed: Boolean,
    val colorIndex: Int,
    val bounds: Rect,
    val path: Path,
)

class BorderTile(
    val bounds: Rect,
    val path: Path,
)

/** 세계 정의와 지도 형상을 합쳐 그리기와 클릭 판정에 필요한 구조를 만든다. Path는 UI 스레드에서 처음 그릴 때 만든다. */
class WorldMapModel(
    private val world: World,
    geometry: MapGeometry,
) {
    private val regions = geometry.regions
    private val regionsById = regions.associateBy(RegionShape::id)
    private val regionsByCountry = regions.groupBy { world.region(it.id).countryId }
    private val highlightPaths = mutableMapOf<String, Path>()

    init {
        val missing = world.regions.keys - regionsById.keys
        require(missing.isEmpty()) { "Map has no shape for regions: ${missing.take(5)}" }
        val uncolored = world.countries.keys - geometry.countryColors.keys
        require(uncolored.isEmpty()) { "Map has no color for countries: ${uncolored.take(5)}" }
    }

    val countryFills: List<CountryFill> by lazy {
        regionsByCountry.map { (countryId, shapes) ->
            CountryFill(
                countryId = countryId,
                unclaimed = world.country(countryId).kind == CountryKind.UNCLAIMED,
                colorIndex = geometry.countryColors.getValue(countryId),
                bounds = shapes.map(RegionShape::bounds).reduce(::unionOf),
                path = Path().apply { shapes.forEach { shape -> shape.rings.forEach(::addRing) } },
            )
        }
    }

    val borderTiles: Map<BorderKind, List<BorderTile>> by lazy {
        geometry.borders
            .groupBy(BorderLine::kind)
            .mapValues { (_, lines) ->
                lines
                    .groupBy { tileKey(it.bounds.center) }
                    .values
                    .map { tileLines ->
                        BorderTile(
                            bounds = tileLines.map(BorderLine::bounds).reduce(::unionOf),
                            path = Path().apply { tileLines.forEach { addPolyline(it.points) } },
                        )
                    }
            }
    }

    fun countryHighlight(countryId: String): Path =
        highlightPaths.getOrPut("country:$countryId") {
            Path().apply { regionsByCountry.getValue(countryId).forEach { shape -> shape.rings.forEach(::addRing) } }
        }

    fun regionHighlight(regionId: String): Path =
        highlightPaths.getOrPut("region:$regionId") {
            Path().apply { regionsById.getValue(regionId).rings.forEach(::addRing) }
        }

    fun countryOf(regionId: String): String = world.region(regionId).countryId

    /** 지도 좌표의 지역을 찾는다. 바다면 null. */
    fun regionAt(point: Offset): String? {
        val x = MapProjection.wrapX(point.x)
        return regions
            .firstOrNull { shape -> shape.bounds.contains(Offset(x, point.y)) && containsEvenOdd(shape.rings, x, point.y) }
            ?.id
    }

    private fun containsEvenOdd(
        rings: List<FloatArray>,
        x: Float,
        y: Float,
    ): Boolean {
        var inside = false
        rings.forEach { ring ->
            var j = ring.size - 2
            for (i in ring.indices step 2) {
                val xi = ring[i]
                val yi = ring[i + 1]
                val xj = ring[j]
                val yj = ring[j + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                    inside = !inside
                }
                j = i
            }
        }
        return inside
    }

    private fun tileKey(center: Offset): Pair<Int, Int> = floor(center.x / TILE_SIZE).toInt() to floor(center.y / TILE_SIZE).toInt()

    private companion object {
        const val TILE_SIZE = 30f
    }
}

private fun Path.addRing(ring: FloatArray) {
    moveTo(ring[0], ring[1])
    for (i in 2 until ring.size step 2) {
        lineTo(ring[i], ring[i + 1])
    }
    close()
}

private fun Path.addPolyline(points: FloatArray) {
    moveTo(points[0], points[1])
    for (i in 2 until points.size step 2) {
        lineTo(points[i], points[i + 1])
    }
}

private fun unionOf(
    a: Rect,
    b: Rect,
): Rect = Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
