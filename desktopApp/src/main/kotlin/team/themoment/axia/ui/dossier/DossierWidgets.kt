package team.themoment.axia.ui.dossier

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import kotlin.math.abs

/** 비스듬히 찍힌 고무 도장. */
@Composable
fun Stamp(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = DossierColors.stamp,
) {
    Box(
        modifier =
            modifier
                .rotate(-6f)
                .border(2.dp, color.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text = text, style = DossierType.title(14.sp), color = color.copy(alpha = 0.9f))
    }
}

/** 라벨과 값을 점선으로 잇고, [note]가 있으면 값 앞에 덧붙인다. */
@Composable
fun StatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = DossierColors.ink,
    note: String? = null,
) {
    Row(modifier = modifier.fillMaxWidth().heightIn(min = 19.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = DossierType.number(13.sp, weight = FontWeight.Normal), color = DossierColors.inkMuted)
        DottedLeader(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 4.dp))
        if (note != null) {
            Text(
                text = note,
                style = DossierType.title(10.sp),
                color = DossierColors.stamp,
                modifier = Modifier.padding(end = 4.dp, bottom = 1.dp),
            )
        }
        Text(text = value, style = DossierType.number(13.sp), color = valueColor)
    }
}

@Composable
private fun DottedLeader(modifier: Modifier) {
    Canvas(modifier.height(1.dp)) {
        drawLine(
            color = DossierColors.inkMuted.copy(alpha = 0.5f),
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5f.dp.toPx(), 3.dp.toPx())),
        )
    }
}

data class Contribution(
    val label: String,
    val value: Double,
)

/** 빅토리아 3식 기여도 막대. 0을 가운데 두고 양수는 오른쪽 초록, 음수는 왼쪽 빨강. */
@Composable
fun ContributionBars(
    contributions: List<Contribution>,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    scale: Double = contributions.maxOfOrNull { abs(it.value) }?.coerceAtLeast(1e-9) ?: 1.0,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        contributions.forEach { contribution ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = contribution.label,
                    style = DossierType.number(12.sp, weight = FontWeight.Normal),
                    color = DossierColors.inkMuted,
                    modifier = Modifier.width(92.dp),
                )
                Canvas(Modifier.weight(1f).height(10.dp)) {
                    val center = size.width / 2f
                    drawLine(DossierColors.inkMuted.copy(alpha = 0.6f), Offset(center, 0f), Offset(center, size.height), strokeWidth = 1f)
                    val length = (abs(contribution.value) / scale).coerceAtMost(1.0).toFloat() * center
                    val color = if (contribution.value >= 0) DossierColors.positive else DossierColors.negative
                    val left = if (contribution.value >= 0) center else center - length
                    drawRect(color.copy(alpha = 0.8f), topLeft = Offset(left, 1f), size = Size(length, size.height - 2f))
                }
                Text(
                    text = format(contribution.value),
                    style = DossierType.number(12.sp),
                    color = if (contribution.value >= 0) DossierColors.positive else DossierColors.negative,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(56.dp),
                )
            }
        }
    }
}

data class Segment(
    val label: String,
    val fraction: Double,
    val color: Color,
)

/** 구성비 가로 막대. */
@Composable
fun CompositionBar(
    segments: List<Segment>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(
                    14.dp,
                ).clip(RoundedCornerShape(2.dp))
                .border(1.dp, DossierColors.inkMuted.copy(alpha = 0.4f)),
    ) {
        segments.filter { it.fraction > 0.0 }.forEach { segment ->
            Box(Modifier.weight(segment.fraction.toFloat()).height(14.dp).background(segment.color))
        }
    }
}

/** 값 표시가 붙은 서류철 슬라이더. [secondary]에는 실제 반영값처럼 보조 정보를 넣는다. */
@Composable
fun DossierSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = label, style = DossierType.number(13.sp, weight = FontWeight.Normal), color = DossierColors.ink)
            Box(Modifier.weight(1f))
            if (secondary != null) {
                Text(
                    text = secondary,
                    style = DossierType.number(11.sp),
                    color = DossierColors.inkMuted,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(text = valueText, style = DossierType.number(13.sp, weight = FontWeight.SemiBold), color = DossierColors.blueInk)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.height(24.dp),
            colors =
                SliderDefaults.colors(
                    thumbColor = DossierColors.ink,
                    activeTrackColor = DossierColors.blueInk,
                    inactiveTrackColor = DossierColors.paperLine,
                ),
        )
    }
}

/** 어두운 띠 위의 종이 꼬리표. 지표 요약이나 버튼에 쓴다. */
@Composable
fun PaperTag(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val background =
        when {
            !enabled -> DossierColors.paperLine
            selected -> DossierColors.folder
            else -> DossierColors.paper
        }
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(3.dp))
                .background(background)
                .then(if (onClick != null && enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = DossierType.number(13.sp, weight = if (selected) FontWeight.Bold else FontWeight.Medium),
            color = if (enabled) DossierColors.ink else DossierColors.inkMuted,
        )
    }
}

/** 그룹이나 범례 색 표시가 붙은 작은 라벨. */
@Composable
fun Swatch(
    color: Color,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            Modifier
                .width(10.dp)
                .height(10.dp)
                .background(color)
                .border(1.dp, DossierColors.inkMuted.copy(alpha = 0.4f)),
        )
        Text(text = label, style = DossierType.number(11.sp, weight = FontWeight.Normal), color = DossierColors.inkMuted)
    }
}
