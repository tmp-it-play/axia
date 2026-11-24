package team.themoment.axia.ui.console

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal val ConsoleText = Color(0xFFF0EBD9)
internal val ConsoleMuted = Color(0xFFB8BCAC)
internal val ConsoleBrass = Color(0xFFE6CC8E)
private val seam = Color(0xFF151B1A)
private val edge = Color(0xFF9B9C84)

/** 판의 모서리와 매립 나사. 바닥 철판 질감은 테마가 그린다. */
internal fun Modifier.consoleFrame(): Modifier =
    drawWithCache {
        val line = 1.dp.toPx()
        val inset = 5.dp.toPx()
        val radius = 1.9.dp.toPx()
        val screws =
            listOf(
                Offset(inset, inset),
                Offset(size.width - inset, inset),
                Offset(inset, size.height - inset),
                Offset(size.width - inset, size.height - inset),
            )
        onDrawBehind {
            drawLine(edge, Offset.Zero, Offset(size.width, 0f), line * 2)
            drawLine(edge.copy(alpha = 0.55f), Offset.Zero, Offset(0f, size.height), line * 2)
            drawLine(seam, Offset(0f, size.height), Offset(size.width, size.height), line * 4)
            drawLine(seam, Offset(size.width, 0f), Offset(size.width, size.height), line * 3)
            screws.forEach { center ->
                drawCircle(seam, radius + line * 0.5f, center)
                drawCircle(edge, radius, center)
                val slot = Offset(radius * 0.70f, -radius * 0.32f)
                drawLine(seam, center - slot, center + slot, line)
            }
        }
    }

@Composable
internal fun ConsoleKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean? = null,
    enabled: Boolean = true,
    description: String? = null,
    content: @Composable BoxScope.(Color) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val lit = enabled && (focused || hovered)
    val ink =
        when {
            !enabled -> ConsoleMuted.copy(alpha = 0.55f)
            active == true -> ConsoleBrass
            else -> ConsoleText
        }
    Box(
        modifier =
            modifier
                .plateFace(recessed = pressed || active == true, lit = lit, focused = focused)
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                .semantics {
                    active?.let { selected = it }
                    description?.let { contentDescription = it }
                }.clickable(
                    enabled = enabled,
                    role = Role.Button,
                    interactionSource = interactions,
                    indication = null,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) { content(ink) }
}

private fun Modifier.plateFace(
    recessed: Boolean,
    lit: Boolean,
    focused: Boolean,
): Modifier =
    drawWithCache {
        val pixel = 1.dp.toPx()
        val bevel = 3.dp.toPx()
        val w = size.width
        val h = size.height
        val outline =
            Path().apply {
                moveTo(bevel, 0f)
                lineTo(w - bevel, 0f)
                lineTo(w, bevel)
                lineTo(w, h - bevel)
                lineTo(w - bevel, h)
                lineTo(bevel, h)
                lineTo(0f, h - bevel)
                lineTo(0f, bevel)
                close()
            }
        val highlight = if (recessed) seam else edge.copy(alpha = if (lit) 0.92f else 0.70f)
        val lower = if (recessed) edge.copy(alpha = 0.55f) else seam
        val face =
            when {
                recessed -> listOf(Color(0xEA242925), Color(0x9E535442))
                lit -> listOf(Color(0xBA747969), Color(0xA339413B))
                else -> listOf(Color(0xAE5B645C), Color(0xA4313936))
            }
        val wash = Brush.verticalGradient(face)
        onDrawBehind {
            drawPath(outline, wash)
            drawLine(highlight, Offset(bevel, pixel), Offset(w - bevel, pixel), pixel)
            drawLine(highlight, Offset(pixel, bevel), Offset(pixel, h - bevel), pixel)
            drawLine(lower, Offset(bevel, h - pixel), Offset(w - bevel, h - pixel), pixel * 2)
            drawLine(lower, Offset(w - pixel, bevel), Offset(w - pixel, h - bevel), pixel)
            if (focused) drawPath(outline, ConsoleBrass, style = Stroke(width = pixel * 2))
        }
    }
