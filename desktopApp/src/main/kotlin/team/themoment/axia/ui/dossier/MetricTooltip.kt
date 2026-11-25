package team.themoment.axia.ui.dossier

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import team.themoment.axia.theme.DossierType

private const val HOVER_DELAY_MS = 320L
private val LocalMetricTooltip = staticCompositionLocalOf<TooltipState?> { null }

private class TooltipState {
    var coordinates: LayoutCoordinates? = null
    var hovered by mutableStateOf<MetricAnchor?>(null)
    var cursor by mutableStateOf(IntOffset.Zero)
}

/** 같은 화면의 지표들은 툴팁 한 개를 공유한다. 입력은 원래 수치 행과 그 버튼이 처리한다. */
@Composable
internal fun MetricTooltipHost(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = remember(enabled) { if (enabled) TooltipState() else null }
    CompositionLocalProvider(LocalMetricTooltip provides state) {
        Box(modifier.fillMaxSize().onGloballyPositioned { state?.coordinates = it }) {
            content()
            if (state != null) TooltipOverlay(state)
        }
    }
}

internal fun Modifier.metricTooltip(
    title: String,
    detail: String?,
): Modifier = if (detail.isNullOrBlank()) this else then(MetricHint(title, null, detail))

/** 그래프의 관측값처럼 커서를 올려야 읽을 수 있는 수치를 표시한다. */
internal fun Modifier.metricReadingTooltip(
    title: String,
    reading: String,
    detail: String? = null,
): Modifier = then(MetricHint(title, reading, detail))

private data class MetricHint(
    val title: String,
    val reading: String?,
    val detail: String?,
) : ModifierNodeElement<MetricAnchor>() {
    override fun create() = MetricAnchor(this)

    override fun update(node: MetricAnchor) {
        node.hint = this
    }
}

private class MetricAnchor(
    initial: MetricHint,
) : Modifier.Node(),
    PointerInputModifierNode,
    CompositionLocalConsumerModifierNode {
    var hint by mutableStateOf(initial)
    private var host: TooltipState? = null

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) {
        if (pass != PointerEventPass.Initial) return
        when (pointerEvent.type) {
            PointerEventType.Enter, PointerEventType.Move -> {
                val mouse = pointerEvent.changes.firstOrNull { it.type == PointerType.Mouse } ?: return
                if (pointerEvent.buttons.areAnyPressed) return
                val state = currentValueOf(LocalMetricTooltip) ?: return
                val coordinates = state.coordinates ?: return
                state.cursor = coordinates.localPositionOf(requireLayoutCoordinates(), mouse.position).round()
                host = state
                state.hovered = this
            }

            PointerEventType.Exit, PointerEventType.Press, PointerEventType.Scroll -> {
                dismiss()
            }
        }
    }

    private fun dismiss() {
        host?.let { if (it.hovered === this) it.hovered = null }
        host = null
    }

    override fun onCancelPointerInput() = dismiss()

    override fun onDetach() = dismiss()
}

@Composable
private fun TooltipOverlay(state: TooltipState) {
    val anchor = state.hovered ?: return
    val hint = anchor.hint
    if (hint.reading.isNullOrBlank() && hint.detail.isNullOrBlank()) return
    var ready by remember(anchor) { mutableStateOf(false) }
    LaunchedEffect(anchor) {
        delay(HOVER_DELAY_MS)
        ready = true
    }
    if (!ready) return
    Layout(content = { TooltipCard(hint) }, modifier = Modifier.fillMaxSize()) { children, limits ->
        val margin = 7.dp.roundToPx()
        val gap = 14.dp.roundToPx()
        val card =
            children.single().measure(
                Constraints(
                    maxWidth = minOf(330.dp.roundToPx(), (limits.maxWidth - margin * 2).coerceAtLeast(0)),
                    maxHeight = (limits.maxHeight - margin * 2).coerceAtLeast(0),
                ),
            )

        fun placeAxis(
            pointer: Int,
            length: Int,
            extent: Int,
        ): Int {
            val forward = pointer + gap
            val position = if (forward + length <= extent - margin) forward else pointer - gap - length
            return position.coerceIn(margin, (extent - margin - length).coerceAtLeast(margin))
        }
        layout(limits.maxWidth, limits.maxHeight) {
            val cursor = state.cursor
            card.place(placeAxis(cursor.x, card.width, limits.maxWidth), placeAxis(cursor.y, card.height, limits.maxHeight))
        }
    }
}

@Composable
private fun TooltipCard(hint: MetricHint) {
    val brass = Color(0xFF95764B)
    Column(
        Modifier
            .widthIn(min = 142.dp)
            .width(IntrinsicSize.Max)
            .shadow(5.dp)
            .background(Color(0xFF202421))
            .border(1.dp, brass)
            .padding(1.dp),
    ) {
        Text(
            hint.title,
            modifier = Modifier.fillMaxWidth().background(Color(0xFF393225)).padding(horizontal = 11.dp, vertical = 6.dp),
            style = DossierType.title(15.sp),
            color = Color(0xFFFFCD76),
        )
        HorizontalDivider(color = brass.copy(alpha = 0.65f))
        Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            hint.reading?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = DossierType.number(15.sp, FontWeight.Bold), color = Color.White)
            }
            hint.detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = DossierType.number(13.sp, FontWeight.Normal).copy(lineHeight = 18.sp), color = Color(0xFFF2F2ED))
            }
        }
    }
}
