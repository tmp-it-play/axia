package team.themoment.axia.map

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

class BorderLine(
    val kind: BorderKind,
    val points: FloatArray,
    val bounds: Rect,
)

class MapGeometry(
    val countryColors: Map<String, Int>,
    val regions: List<RegionShape>,
    val borders: List<BorderLine>,
) {
    companion object {
        private const val RESOURCE = "/map/world-map.bin"
        private const val FORMAT_NAME = "AXIA-MAP"
        private const val FORMAT_VERSION = 1

        fun loadDefault(): MapGeometry {
            val stream = requireNotNull(MapGeometry::class.java.getResourceAsStream(RESOURCE)) { "Missing $RESOURCE" }
            return DataInputStream(BufferedInputStream(stream)).use(::read)
        }

        private fun read(input: DataInputStream): MapGeometry {
            check(input.readUTF() == FORMAT_NAME) { "Not an Axia map file" }
            val version = input.readInt()
            check(version == FORMAT_VERSION) { "Unsupported map format version $version" }
            val countryColors = List(input.readInt()) { input.readUTF() to input.readByte().toInt() }.toMap()
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
                    val points = input.readProjectedPoints()
                    BorderLine(kind, points, boundsOf(listOf(points)))
                }
            return MapGeometry(countryColors, regions, borders)
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
