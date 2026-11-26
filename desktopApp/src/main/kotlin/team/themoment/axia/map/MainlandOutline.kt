package team.themoment.axia.map

import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/** 행정구역끼리 공유하는 변을 먼저 지워 본토와 그 안의 구멍을 복원한다. */
internal fun mainlandOutline(shapes: List<RegionShape>): Path2D.Double? {
    val cut = longitudeCut(shapes)
    val seam = sortedSetOf<Float>()
    if (cut.isFinite()) {
        for (shape in shapes) {
            for (ring in shape.rings) {
                for (i in ring.indices step 2) {
                    if (abs(ring[i]) == -MapProjection.WEST_EDGE) seam.add(ring[i + 1])
                }
            }
        }
    }

    val edges = mutableMapOf<Long, MutableList<Long>>()

    fun vertex(
        x: Float,
        y: Float,
    ): Long {
        // 같은 꼭짓점의 ±0과, 펼쳐 놓은 지도의 ±180°를 한 키로 묶는다.
        val longitude = if (cut.isFinite() && x == MapProjection.WEST_EDGE) -x else x + 0f
        return (longitude.toRawBits().toLong() shl 32) or ((y + 0f).toRawBits().toLong() and 0xffffffffL)
    }

    fun addEdge(
        from: Long,
        to: Long,
    ) {
        if (from == to) return
        val reverse = edges[to]
        if (reverse != null && reverse.remove(from)) {
            if (reverse.isEmpty()) edges.remove(to)
        } else {
            edges.getOrPut(from) { mutableListOf() }.add(to)
        }
    }

    var twiceArea = 0.0
    for (shape in shapes) {
        for (ring in shape.rings) {
            if (ring.size < 6) continue
            var j = ring.size - 2
            for (i in ring.indices step 2) {
                val x0 = ring[j]
                val x1 = ring[i]
                val y0 = ring[j + 1]
                val y1 = ring[i + 1]
                var from = vertex(x0, y0)
                if (seam.isNotEmpty() && x0 == x1 && abs(x0) == -MapProjection.WEST_EDGE && y0 != y1) {
                    // 원자료의 양쪽 절단면은 같은 직선 위에 서로 다른 중간 꼭짓점을 가질 수 있다.
                    val intermediate = seam.subSet(minOf(y0, y1), false, maxOf(y0, y1), false)
                    val ordered = if (y0 < y1) intermediate else intermediate.descendingSet()
                    for (y in ordered) {
                        val next = vertex(x0, y)
                        addEdge(from, next)
                        from = next
                    }
                }
                addEdge(from, vertex(x1, y1))
                twiceArea += x0.toDouble() * y1 - x1.toDouble() * y0
                j = i
            }
        }
    }

    fun x(vertex: Long): Double {
        val longitude = Float.fromBits((vertex ushr 32).toInt()).toDouble()
        return if (longitude < cut) longitude + MapProjection.WORLD_WIDTH else longitude
    }

    fun y(vertex: Long): Double = Float.fromBits(vertex.toInt()).toDouble()

    val contours = mutableListOf<Contour>()
    while (edges.isNotEmpty()) {
        val start = edges.keys.first()
        var current = start
        var previous = start
        val path = Path2D.Double()
        path.moveTo(x(start), y(start))
        var area = 0.0
        do {
            val destinations = edges.getValue(current)
            val index =
                if (destinations.size == 1 || previous == current) {
                    destinations.lastIndex
                } else {
                    // 점에서만 닿은 육지는 들어온 변 바로 옆의 같은 해안을 따라 각각 닫는다.
                    val incoming = atan2(y(previous) - y(current), x(previous) - x(current))
                    destinations.indices.minBy { i ->
                        val next = destinations[i]
                        val outgoing = atan2(y(next) - y(current), x(next) - x(current))
                        val turn = if (twiceArea < 0) outgoing - incoming else incoming - outgoing
                        (turn + 2 * PI) % (2 * PI)
                    }
                }
            val next = destinations.removeAt(index)
            if (destinations.isEmpty()) edges.remove(current)
            area += x(current) * y(next) - x(next) * y(current)
            path.lineTo(x(next), y(next))
            previous = current
            current = next
        } while (current != start)
        path.closePath()
        if (area != 0.0) contours.add(Contour(path, abs(area), x(start), y(start)))
    }

    // 중첩 깊이로 외곽과 구멍을 구분한다. 호수 안 섬은 별도의 육지다.
    val sorted = contours.sortedByDescending { it.twiceArea }
    val shells = mutableListOf<Contour>()
    for ((index, contour) in sorted.withIndex()) {
        val parent =
            (index - 1 downTo 0)
                .firstOrNull { i ->
                    val outside = sorted[i]
                    outside.bounds.contains(contour.bounds) && outside.path.contains(contour.x, contour.y)
                }?.let { sorted[it] }
        if (parent == null || parent.isHole) {
            shells.add(contour)
        } else {
            contour.isHole = true
            parent.holes.add(contour)
        }
    }
    val best = shells.maxByOrNull { shell -> shell.twiceArea - shell.holes.sumOf { it.twiceArea } } ?: return null
    val outline = Path2D.Double(Path2D.WIND_EVEN_ODD)
    outline.append(best.path, false)
    best.holes.forEach { outline.append(it.path, false) }
    if (outline.bounds2D.centerX > -MapProjection.WEST_EDGE) {
        outline.transform(AffineTransform.getTranslateInstance(-MapProjection.WORLD_WIDTH.toDouble(), 0.0))
    }
    return outline
}

/** 날짜변경선 양쪽의 육지는 이어 붙이고, 어떤 고리도 지나지 않는 경도에서 지도를 펼친다. */
private fun longitudeCut(shapes: List<RegionShape>): Double {
    if (shapes.none { it.bounds.left == MapProjection.WEST_EDGE } || shapes.none { it.bounds.right == -MapProjection.WEST_EDGE }) {
        return Double.NEGATIVE_INFINITY
    }
    val intervals =
        shapes
            .flatMap { shape ->
                shape.rings.filter { it.isNotEmpty() }.map { ring ->
                    var left = ring[0]
                    var right = left
                    for (i in ring.indices step 2) {
                        left = minOf(left, ring[i])
                        right = maxOf(right, ring[i])
                    }
                    left to right
                }
            }.sortedBy { it.first }
    var right = MapProjection.WEST_EDGE
    var widest = 0f
    var cut = Double.NEGATIVE_INFINITY
    for ((nextLeft, nextRight) in intervals) {
        val gap = nextLeft - right
        if (gap > widest) {
            widest = gap
            cut = right + gap / 2.0
        }
        right = maxOf(right, nextRight)
    }
    return cut
}

private class Contour(
    val path: Path2D.Double,
    val twiceArea: Double,
    val x: Double,
    val y: Double,
) {
    val bounds = path.bounds2D
    val holes = mutableListOf<Contour>()
    var isHole = false
}
