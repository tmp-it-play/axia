package team.themoment.axia.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import team.themoment.axia.simulation.world.CountryKind
import team.themoment.axia.simulation.world.World
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

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

/** 한 국가의 행정구역 경계. [typicalRegionSize]는 행정구역 하나의 대략적인 한 변 길이(투영 좌표)다. */
class RegionBorders(
    val bounds: Rect,
    val typicalRegionSize: Float,
    val path: Path,
)

/** 국가 이름과 그 이름을 따라 쓸 곡선. 곡선은 왼쪽에서 오른쪽으로 읽히는 방향이며 호 길이 표를 미리 계산해 둔다. */
class CountryLabel(
    val countryId: String,
    val name: String,
    curve: CountryLabelCurve,
    val maxGlyphHeight: Float,
) {
    private val start = if (curve.start.x <= curve.end.x) curve.start else curve.end
    private val end = if (curve.start.x <= curve.end.x) curve.end else curve.start
    private val control = curve.control
    private val samples = Array(SAMPLES + 1) { pointAt(it / SAMPLES.toFloat()) }
    private val lengths =
        FloatArray(SAMPLES + 1).also { table ->
            for (i in 1..SAMPLES) table[i] = table[i - 1] + (samples[i] - samples[i - 1]).getDistance()
        }

    val thickness = curve.thickness
    val length: Float get() = lengths[SAMPLES]
    val bounds: Rect =
        Rect(samples.minOf { it.x }, samples.minOf { it.y }, samples.maxOf { it.x }, samples.maxOf { it.y })
            .inflate(curve.thickness / 2f)

    /** 호 길이 [distance] 지점의 위치와 접선 각도(라디안). */
    fun placeAt(distance: Float): Pair<Offset, Float> {
        val target = distance.coerceIn(0f, length)
        var index = lengths.indexOfFirst { it >= target }.coerceAtLeast(1)
        if (index > SAMPLES) index = SAMPLES
        val segment = lengths[index] - lengths[index - 1]
        val fraction = if (segment > 0f) (target - lengths[index - 1]) / segment else 0f
        val a = samples[index - 1]
        val b = samples[index]
        val direction = b - a
        return (a + direction * fraction) to atan2(direction.y, direction.x)
    }

    private fun pointAt(t: Float): Offset {
        val u = 1f - t
        return start * (u * u) + control * (2f * u * t) + end * (t * t)
    }

    private companion object {
        const val SAMPLES = 32
    }
}

/** 세계 정의와 지도 형상을 합쳐 그리기와 클릭 판정에 필요한 구조를 만든다. Path는 UI 스레드에서 처음 그릴 때 만든다. */
class WorldMapModel(
    private val world: World,
    private val geometry: MapGeometry,
) {
    private val regions = geometry.regions
    private val regionsById = regions.associateBy(RegionShape::id)
    private val regionsByCountry = regions.groupBy { world.region(it.id).countryId }
    private val highlightPaths = mutableMapOf<String, Path>()

    init {
        val missing = world.regions.keys - regionsById.keys
        require(missing.isEmpty()) { "Map has no shape for regions: ${missing.take(5)}" }
        val unknown = world.countries.keys - geometry.countries.keys
        require(unknown.isEmpty()) { "Map has no entry for countries: ${unknown.take(5)}" }
    }

    val countryFills: List<CountryFill> by lazy {
        regionsByCountry.map { (countryId, shapes) ->
            CountryFill(
                countryId = countryId,
                unclaimed = world.country(countryId).kind == CountryKind.UNCLAIMED,
                colorIndex = geometry.countries.getValue(countryId).colorIndex,
                bounds = shapes.map(RegionShape::bounds).reduce(::unionOf),
                path = Path().apply { shapes.forEach { shape -> shape.rings.forEach(::addRing) } },
            )
        }
    }

    private val initialLabels by lazy {
        regionsByCountry.mapValues { (owner, shapes) -> fitLabel(owner, shapes, geometry.countries.getValue(owner).labelCurve) }
    }

    /** 바뀐 소유권이 있는 나라만 다시 맞춘다. 줌과 화면 위치는 표기 배치에 관여하지 않는다. */
    fun labelsFor(owners: Map<String, String>): List<CountryLabel> {
        if (owners.isEmpty()) return initialLabels.values.filterNotNull()
        val changed = owners.entries.flatMap { (region, owner) -> listOf(countryOf(region), owner) }.toSet()
        val current = regions.groupBy { owners[it.id] ?: countryOf(it.id) }
        return current.mapNotNull { (owner, shapes) ->
            if (owner in changed) fitLabel(owner, shapes, null) else initialLabels[owner]
        }
    }

    private fun fitLabel(
        owner: String,
        shapes: List<RegionShape>,
        original: CountryLabelCurve?,
    ): CountryLabel? {
        val mainland = shapes.filter { world.region(it.id).territoryId == null }.ifEmpty { shapes }
        val name = world.country(owner).name
        val curve = fitTerritoryCurve(mainland, original, name.count { !it.isWhitespace() }) ?: return null
        val area = abs(mainland.sumOf { shape -> shape.rings.sumOf(::signedArea) })
        return CountryLabel(owner, name, curve, sqrt(area).toFloat() * 0.28f)
    }

    class PoliticalBorders(
        val countries: List<BorderTile>,
        val regions: List<RegionBorders>,
    )

    private fun borderTiles(lines: List<BorderLine>): List<BorderTile> =
        lines.groupBy { tileKey(it.bounds.center) }.values.map { tileLines ->
            BorderTile(
                bounds = tileLines.map(BorderLine::bounds).reduce(::unionOf),
                path = Path().apply { tileLines.forEach { addPolyline(it.points) } },
            )
        }

    val coasts: List<BorderTile> by lazy { borderTiles(geometry.borders.filter { it.kind == BorderKind.COAST }) }

    // 지도 생성기가 같은 경계 조각을 양쪽 행정구역의 고리에 넣는다. 첫 변으로 원래 조각의 양쪽 지역을 찾아 둔다.
    private data class Edge(
        val low: Long,
        val high: Long,
    )

    private fun edge(
        points: FloatArray,
        i: Int,
        j: Int,
    ): Edge {
        fun vertex(k: Int) = (points[k].toRawBits().toLong() shl 32) or (points[k + 1].toRawBits().toLong() and 0xffffffffL)
        val a = vertex(i)
        val b = vertex(j)
        return if (a < b) Edge(a, b) else Edge(b, a)
    }

    private val landBorders = geometry.borders.filter { it.kind != BorderKind.COAST }
    private val adjacentRegions by lazy {
        val byEdge = landBorders.associate { edge(it.points, 0, 2) to mutableSetOf<String>() }
        regions.forEach { shape ->
            shape.rings.forEach { ring ->
                for (i in ring.indices step 2) {
                    byEdge[edge(ring, i, (i + 2) % ring.size)]?.add(shape.id)
                }
            }
        }
        landBorders.associateWith { line ->
            byEdge.getValue(edge(line.points, 0, 2)).also { require(it.size >= 2) { "A land boundary needs two regions" } }
        }
    }

    fun politicalBorders(owners: Map<String, String>): PoliticalBorders {
        val domestic = mutableMapOf<String, MutableList<BorderLine>>()
        val international = mutableListOf<BorderLine>()
        landBorders.forEach { line ->
            val owner =
                if (owners.isEmpty()) {
                    line.ownerId
                } else {
                    adjacentRegions
                        .getValue(line)
                        .map { owners[it] ?: countryOf(it) }
                        .distinct()
                        .singleOrNull()
                }
            if (owner == null) international += line else domestic.getOrPut(owner) { mutableListOf() } += line
        }
        val regionalShapes = if (owners.isEmpty()) regionsByCountry else regions.groupBy { owners[it.id] ?: countryOf(it.id) }
        val borders =
            domestic.map { (owner, lines) ->
                val shapes = regionalShapes.getValue(owner)
                val area = abs(shapes.sumOf { shape -> shape.rings.sumOf(::signedArea) })
                RegionBorders(
                    bounds = lines.map(BorderLine::bounds).reduce(::unionOf),
                    typicalRegionSize = sqrt(area / shapes.size).toFloat(),
                    path = Path().apply { lines.forEach { addPolyline(it.points) } },
                )
            }
        return PoliticalBorders(borderTiles(international), borders)
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

    /** 한 나라의 지역과 그 경계 상자. 지역별 색칠에 쓴다. */
    fun regionsOf(countryId: String): List<RegionShape> = regionsByCountry[countryId].orEmpty()

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

private fun signedArea(ring: FloatArray): Double {
    var area = 0.0
    var j = ring.size - 2
    for (i in ring.indices step 2) {
        area += ring[j].toDouble() * ring[i + 1] - ring[i].toDouble() * ring[j + 1]
        j = i
    }
    return area / 2.0
}
