package team.themoment.axia.map

import androidx.compose.ui.geometry.Offset
import java.awt.Color
import java.awt.Shape
import java.awt.geom.QuadCurve2D
import java.awt.image.BufferedImage
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 본토의 면적 중심 가까이에서 한글 이름이 들어가는 완만한 호를 찾는다. */
internal fun fitTerritoryCurve(
    shapes: List<RegionShape>,
    glyphs: Int,
): CountryLabelCurve? {
    val body = mainlandOutline(shapes) ?: return null
    val inset = LandGrid(body)
    if (inset.heightLimit == 0.0) return null
    val anchors = inset.anchors()
    val angles = listOf(0.0, inset.axis.coerceIn(-MAX_TILT, MAX_TILT), -12.0, 12.0, -25.0, 25.0, -50.0, 50.0, -75.0, 75.0).distinct()
    val lengths = generateSequence(1.5) { it * 1.25 }.takeWhile { it < GRID * 1.3 }.toList().asReversed()
    var bestScore = 0.0
    var best: CountryLabelCurve? = null
    for (degrees in angles) {
        val angle = Math.toRadians(degrees)
        // 폭이 넓은 나라는 가로쓰기를, 길쭉한 나라는 본토의 장축을 우선한다.
        val horizontal = 1.0 - 0.36 * sin(angle) * sin(angle)
        val tilt = sin(Math.toRadians(degrees - inset.axis))
        val alignment = horizontal + inset.elongation * (1.0 - 0.42 * tilt * tilt - horizontal)
        for (span in lengths) {
            for (bow in listOf(0.065, -0.065)) {
                val arc = LabelArc(angle, span, bow)
                for (anchor in anchors) {
                    val clearance = arc.clearanceAt(anchor.x, anchor.y, inset.clearance)
                    // 한글 한 자의 폭은 줄높이의 약 85%. 실제 글꼴 측정값은 그릴 때 적용한다.
                    val height = minOf(inset.heightLimit, clearance * 1.35, span * 0.82 / (0.85 * glyphs))
                    if (height <= 0.0) continue
                    val spread = minOf(2.5, span / (glyphs * height))
                    val score = height * (1.0 + 0.22 * spread) * anchor.centrality * alignment * (if (bow > 0) 1.02 else 1.0)
                    if (score <= bestScore) continue
                    val candidate = arc.onMap(inset.toMap(anchor.x, anchor.y), inset.cell, height)
                    if (candidate.fitsInside(body)) {
                        best = candidate
                        bestScore = score
                    }
                }
            }
        }
    }
    return best
}

private const val GRID = 92
private const val MAX_TILT = 82.0

private class LabelAnchor(
    val x: Int,
    val y: Int,
    val centrality: Double,
)

/** 본토 하나의 투영 격자. 같은 표본으로 면적 중심, 장축과 해안·구멍까지의 여유를 구한다. */
private class LandGrid(
    body: Shape,
) {
    private val bounds = body.bounds2D
    val cell = maxOf(bounds.width, bounds.height) / (GRID - 4)
    val clearance = FloatArray(GRID * GRID)
    private val center: Offset
    private val meanSquareRadius: Double
    val axis: Double
    val elongation: Double
    val heightLimit: Double

    init {
        val image = BufferedImage(GRID, GRID, BufferedImage.TYPE_BYTE_GRAY)
        val pen = image.createGraphics()
        try {
            pen.color = Color.WHITE
            pen.translate(2.0, 2.0)
            pen.scale(1 / cell, 1 / cell)
            pen.translate(-bounds.minX, -bounds.minY)
            pen.fill(body)
        } finally {
            pen.dispose()
        }
        var count = 0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (y in 1 until GRID - 1) {
            for (x in 1 until GRID - 1) {
                if (image.raster.getSample(x, y, 0) == 0) continue
                clearance[y * GRID + x] = GRID.toFloat()
                count++
                sx += x
                sy += y
                sxx += x * x
                syy += y * y
                sxy += x * y
            }
        }
        val samples = count.coerceAtLeast(1)
        val mx = sx / samples
        val my = sy / samples
        center = Offset(mx.toFloat(), my.toFloat())
        val xx = sxx / samples - mx * mx
        val yy = syy / samples - my * my
        val xy = sxy / samples - mx * my
        meanSquareRadius = maxOf(1.0, xx + yy)
        axis = Math.toDegrees(0.5 * atan2(2 * xy, xx - yy))
        elongation = ((hypot(xx - yy, 2 * xy) / meanSquareRadius - 0.25) / 0.5).coerceIn(0.0, 1.0)
        heightLimit = sqrt(count.toDouble()) * 0.28
        for (i in GRID + 1 until clearance.size - GRID - 1) updateDistance(i, 1)
        for (i in clearance.size - GRID - 2 downTo GRID + 1) updateDistance(i, -1)
    }

    fun anchors(): List<LabelAnchor> {
        val cells = mutableSetOf(clearance.indices.maxBy { clearance[it] })
        for (y in 1 until GRID - 1 step 3) {
            for (x in 1 until GRID - 1 step 3) {
                if (clearance[y * GRID + x] > 0f) cells.add(y * GRID + x)
            }
        }
        return cells
            .map { cell ->
                val x = cell % GRID
                val y = cell / GRID
                val dx = x - center.x
                val dy = y - center.y
                LabelAnchor(x, y, 1.0 / (1.0 + 4.0 * (dx * dx + dy * dy) / meanSquareRadius))
            }.sortedByDescending { it.centrality }
    }

    // 가로·세로 및 대각선의 두 차례 거리 변환. 최종 활자 띠는 원본 벡터 경계로 검사한다.
    private fun updateDistance(
        i: Int,
        step: Int,
    ) {
        if (clearance[i] == 0f) return
        val above = i - step * GRID
        clearance[i] =
            minOf(
                clearance[i],
                1f + minOf(clearance[i - step], clearance[above]),
                1.4142f + minOf(clearance[above - 1], clearance[above + 1]),
            )
    }

    fun toMap(
        x: Int,
        y: Int,
    ) = Offset((bounds.minX + (x - 1.5) * cell).toFloat(), (bounds.minY + (y - 1.5) * cell).toFloat())
}

/** t=0은 국명 한가운데, 양 끝은 t=±1이다. 정중앙을 고정한 채 휘는 방향만 바꿀 수 있다. */
private class LabelArc(
    angle: Double,
    span: Double,
    bow: Double,
) {
    private val half = Offset((cos(angle) * span / 2).toFloat(), (sin(angle) * span / 2).toFloat())
    private val rise = Offset((-sin(angle) * span * bow).toFloat(), (cos(angle) * span * bow).toFloat())
    private val steps = 2 * ceil(span / 6).toInt()
    private val samples =
        Array(steps + 1) { i ->
            val t = 2f * i / steps - 1f
            half * t + rise * (t * t)
        }
    private val dx = IntArray(samples.size) { samples[it].x.roundToInt() }
    private val dy = IntArray(samples.size) { samples[it].y.roundToInt() }
    private val offsets = IntArray(samples.size) { dy[it] * GRID + dx[it] }
    private val xRange = -dx.min() until GRID - dx.max()
    private val yRange = -dy.min() until GRID - dy.max()

    fun clearanceAt(
        x: Int,
        y: Int,
        land: FloatArray,
    ): Double {
        if (x !in xRange || y !in yRange) return 0.0
        val center = y * GRID + x
        var smallest = land[center]
        for (offset in offsets) {
            smallest = minOf(smallest, land[center + offset])
            if (smallest == 0f) break
        }
        return (smallest - 0.5) * 0.92
    }

    fun onMap(
        center: Offset,
        cell: Double,
        height: Double,
    ): CountryLabelCurve {
        val along = half * cell.toFloat()
        val bend = rise * cell.toFloat()
        return CountryLabelCurve(center - along + bend, center - bend, center + along + bend, (height * cell).toFloat())
    }
}

private fun CountryLabelCurve.fitsInside(body: Shape): Boolean {
    // 각 부분 호의 제어 삼각형과 활자 반높이를 포함하는 상자가 육지 안이면 띠 전체가 안전하다.
    fun containsArc(
        arc: QuadCurve2D.Double,
        depth: Int,
    ): Boolean {
        val bounds = arc.bounds2D
        val margin = glyphHeight / 2.0
        if (body.contains(bounds.x - margin, bounds.y - margin, bounds.width + glyphHeight, bounds.height + glyphHeight)) return true
        if (depth == 0) return false
        val left = QuadCurve2D.Double()
        val right = QuadCurve2D.Double()
        arc.subdivide(left, right)
        return containsArc(left, depth - 1) && containsArc(right, depth - 1)
    }

    val arc =
        QuadCurve2D.Double(
            start.x.toDouble(),
            start.y.toDouble(),
            control.x.toDouble(),
            control.y.toDouble(),
            end.x.toDouble(),
            end.y.toDouble(),
        )
    return containsArc(arc, 5)
}
