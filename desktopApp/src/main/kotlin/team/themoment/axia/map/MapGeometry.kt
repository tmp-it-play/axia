package team.themoment.axia.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.io.BufferedInputStream
import java.io.DataInputStream

enum class BorderKind {
    COAST,
    COUNTRY,
    REGION,
}

/** 투영된 좌표의 지역 형상. 각 고리는 [x0, y0, x1, y1, ...] 형태다. */
class RegionShape(
    val id: String,
    val rings: List<FloatArray>,
    val bounds: Rect,
)

/** 행정구역 경계는 [ownerId] 국가 안쪽 경계다. 해안선과 국경은 null. */
class BorderLine(
    val kind: BorderKind,
    val ownerId: String?,
    val points: FloatArray,
    val bounds: Rect,
)

/** 국가 이름을 따라 쓸 이차 베지어 곡선과 허용 활자 높이. 모두 투영 좌표다. */
class CountryLabelCurve(
    val start: Offset,
    val control: Offset,
    val end: Offset,
    val glyphHeight: Float,
)

class CountryMapInfo(
    val id: String,
    val colorIndex: Int,
)

class MapGeometry(
    val countries: Map<String, CountryMapInfo>,
    val regions: List<RegionShape>,
    val borders: List<BorderLine>,
) {
    companion object {
        private const val RESOURCE = "/map/world-map.bin"
        private const val FORMAT_NAME = "AXIA-MAP"
        private const val FORMAT_VERSION = 4

        fun loadDefault(): MapGeometry {
            val stream = requireNotNull(MapGeometry::class.java.getResourceAsStream(RESOURCE)) { "Missing $RESOURCE" }
            return DataInputStream(BufferedInputStream(stream)).use(::read)
        }

        private fun read(input: DataInputStream): MapGeometry {
            check(input.readUTF() == FORMAT_NAME) { "Not an Axia map file" }
            val version = input.readInt()
            check(version == FORMAT_VERSION) { "Unsupported map format version $version" }
            val countries =
                List(input.readInt()) { CountryMapInfo(input.readUTF(), input.readByte().toInt()) }.associateBy(CountryMapInfo::id)
            val regions =
                List(input.readInt()) {
                    val id = input.readUTF()
                    val rings = List(input.readInt()) { input.readProjectedPoints() }
                    RegionShape(id, rings, boundsOf(rings))
                }
            val kinds = BorderKind.entries
            val borders =
                List(input.readInt()) {
                    val kind = kinds[input.readByte().toInt()]
                    val ownerId = if (kind == BorderKind.REGION) input.readUTF() else null
                    val points = input.readProjectedPoints()
                    BorderLine(kind, ownerId, points, boundsOf(listOf(points)))
                }
            return MapGeometry(countries, regions, borders)
        }

        private fun DataInputStream.readProjectedPoints(): FloatArray {
            val count = readInt()
            val points = FloatArray(count * 2)
            for (i in 0 until count) {
                points[i * 2] = MapProjection.x(readFloat())
                points[i * 2 + 1] = MapProjection.y(readFloat())
            }
            return points
        }

        private fun boundsOf(lines: List<FloatArray>): Rect {
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            lines.forEach { points ->
                for (i in points.indices step 2) {
                    left = minOf(left, points[i])
                    right = maxOf(right, points[i])
                    top = minOf(top, points[i + 1])
                    bottom = maxOf(bottom, points[i + 1])
                }
            }
            return Rect(left, top, right, bottom)
        }
    }
}
