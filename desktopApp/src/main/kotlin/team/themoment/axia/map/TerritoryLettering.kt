package team.themoment.axia.map

import androidx.compose.ui.geometry.Offset
import java.awt.Color
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.geom.PathIterator
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 국토에서 벗어난 곡선 또는 새 국경에만 내부 표기선을 구한다. */
internal fun fitTerritoryCurve(
    shapes: List<RegionShape>,
    original: CountryLabelCurve?,
    glyphs: Int,
): CountryLabelCurve? {
    val land = Path2D.Float(Path2D.WIND_EVEN_ODD)
    shapes.forEach { shape ->
        shape.rings.forEach { ring ->
            land.moveTo(ring[0], ring[1])
            for (i in 2 until ring.size step 2) land.lineTo(ring[i], ring[i + 1])
            land.closePath()
        }
    }
    if (original != null && followsLand(original, land)) return original
    val body = largestLandmass(Area(land)) ?: return null
    return fitInscription(body, glyphs)
}

private fun followsLand(
    curve: CountryLabelCurve,
    land: Path2D,
): Boolean {
    for (i in 0..48) {
        val t = i / 48f
        val u = 1f - t
        val p = curve.start * (u * u) + curve.control * (2 * u * t) + curve.end * (t * t)
        val tangent = (curve.control - curve.start) * u + (curve.end - curve.control) * t
        val length = tangent.getDistance()
        if (length == 0f) return false
        val normal = Offset(-tangent.y, tangent.x) * (curve.thickness * 0.34f / length)
        for (side in -2..2) {
            val edge = p + normal * (side / 2f)
            if (!land.contains(edge.x.toDouble(), edge.y.toDouble())) return false
        }
    }
    return true
}

/** 합쳐진 경계에서 가장 넓은 연결 육지를 고르고 호수와 월경지는 비워 둔다. */
private fun largestLandmass(land: Area): Area? {
    val iterator = land.getPathIterator(null)
    val p = DoubleArray(6)
    var outline = Path2D.Double()
    var previousX = 0.0
    var previousY = 0.0
    var firstX = 0.0
    var firstY = 0.0
    var twiceArea = 0.0
    var largest = 0.0
    var best: Path2D? = null
    while (!iterator.isDone) {
        when (iterator.currentSegment(p)) {
            PathIterator.SEG_MOVETO -> {
                outline = Path2D.Double()
                outline.moveTo(p[0], p[1])
                firstX = p[0]
                firstY = p[1]
                twiceArea = 0.0
            }

            PathIterator.SEG_LINETO -> {
                outline.lineTo(p[0], p[1])
                twiceArea += previousX * p[1] - p[0] * previousY
            }

            PathIterator.SEG_CLOSE -> {
                outline.closePath()
                val area = abs(twiceArea + previousX * firstY - firstX * previousY)
                if (area > largest) {
                    largest = area
                    best = outline
                }
            }
        }
        previousX = p[0]
        previousY = p[1]
        iterator.next()
    }
    return best?.let { Area(it).apply { intersect(land) } }
}

private const val GRID = 80

/** 여러 방향에서 본토 안에 들어가는 직사각형을 찾고, 한글 이름의 길이에 맞는 폭·높이를 고른다. */
private fun fitInscription(
    body: Area,
    glyphs: Int,
): CountryLabelCurve? {
    var bestScore = 0.0
    var best: CountryLabelCurve? = null
    val orientations =
        listOf(0, -25, 25, -50, 50, -75, 75, 90).associateWith { degrees ->
            AffineTransform.getRotateInstance(-Math.toRadians(degrees.toDouble())).createTransformedShape(body)
        }
    val axis = orientations.maxBy { (_, shape) -> shape.bounds2D.run { width / height } }
    val narrow = axis.value.bounds2D.run { width > 2.0 * height }
    for ((degrees, rotated) in orientations) {
        val angle = Math.toRadians(degrees.toDouble())
        // 둥근 국토에는 가로쓰기를, 길고 좁은 국토에는 긴 축을 우선한다.
        val alignment =
            if (narrow) {
                val alongAxis = cos(Math.toRadians((degrees - axis.key).toDouble()))
                0.42 + 0.58 * alongAxis * alongAxis
            } else {
                1.0 - abs(degrees) / 175.0
            }
        val bounds = rotated.bounds2D
        val cell = maxOf(bounds.width, bounds.height) / (GRID - 2)
        val image = BufferedImage(GRID, GRID, BufferedImage.TYPE_BYTE_GRAY)
        val pen = image.createGraphics()
        try {
            pen.color = Color.WHITE
            pen.translate(1.0, 1.0)
            pen.scale(1 / cell, 1 / cell)
            pen.translate(-bounds.minX, -bounds.minY)
            pen.fill(rotated)
        } finally {
            pen.dispose()
        }
        val heights = IntArray(GRID + 1)
        val stack = IntArray(GRID + 1)

        fun toMap(
            x: Double,
            y: Double,
        ): Offset {
            val u = bounds.minX + (x - 1) * cell
            val v = bounds.minY + (y - 1) * cell
            return Offset((u * cos(angle) - v * sin(angle)).toFloat(), (u * sin(angle) + v * cos(angle)).toFloat())
        }

        // 누적 높이 히스토그램에서 행마다 내부 직사각형 후보를 선형 시간에 열거한다.
        for (y in 0 until GRID) {
            var top = -1
            for (x in 0..GRID) {
                heights[x] = if (x < GRID && image.raster.getSample(x, y, 0) != 0) heights[x] + 1 else 0
                while (top >= 0 && heights[stack[top]] > heights[x]) {
                    val height = heights[stack[top--]]
                    val left = if (top < 0) 0 else stack[top] + 1
                    val width = x - left
                    val readable = minOf(height * 0.50, width * 0.80 / glyphs)
                    val spread = minOf(4.0, width / (glyphs * readable))
                    val score = cell * readable * alignment * (1.0 + 0.10 * spread)
                    if (score > bestScore) {
                        val midY = y + 1 - height / 2.0
                        val safeLeft = left + width * 0.08
                        val safeRight = x - width * 0.08
                        // 픽셀 하나보다 얇은 해안·국경도 실제 벡터 형상에서 다시 확인한다.
                        if (!rotated.contains(
                                bounds.minX + (safeLeft - 1) * cell,
                                bounds.minY + (midY - height * 0.28 - 1) * cell,
                                (safeRight - safeLeft) * cell,
                                height * 0.56 * cell,
                            )
                        ) {
                            continue
                        }
                        val start = toMap(safeLeft, midY)
                        val end = toMap(safeRight, midY)
                        val candidate = CountryLabelCurve(start, (start + end) / 2f, end, (height * cell * 0.82).toFloat())
                        best = candidate
                        bestScore = score
                    }
                }
                stack[++top] = x
            }
        }
    }
    return best
}
