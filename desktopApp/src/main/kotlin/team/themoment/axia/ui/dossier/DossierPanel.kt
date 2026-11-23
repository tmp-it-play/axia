package team.themoment.axia.ui.dossier

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType

private val TAB_SHAPE = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
private val FOLDER_SHAPE = RoundedCornerShape(topEnd = 6.dp, bottomStart = 6.dp, bottomEnd = 6.dp)

/**
 * 탭 라벨이 달린 마닐라 폴더 안에 클립으로 고정한 종이 한 장.
 * [scrollable]이면 높이가 정해진 자리에서 종이 내용을 스크롤한다.
 */
@Composable
fun DossierPanel(
    title: String,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .padding(start = 14.dp)
                    .background(DossierColors.folder, TAB_SHAPE)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, style = DossierType.title(15.sp), color = DossierColors.ink)
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (scrollable) Modifier.weight(1f, fill = false) else Modifier)
                    .shadow(8.dp, FOLDER_SHAPE)
                    .background(DossierColors.folder, FOLDER_SHAPE)
                    .border(1.dp, DossierColors.folderEdge.copy(alpha = 0.5f), FOLDER_SHAPE)
                    .padding(8.dp),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(DossierColors.paper)
                        .border(1.dp, DossierColors.paperLine)
                        .ruledPaper()
                        .then(if (scrollable) Modifier.verticalScroll(key(title) { rememberScrollState() }) else Modifier)
                        .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp),
                content = content,
            )
            PaperClip(Modifier.align(Alignment.TopEnd).offset(x = (-28).dp, y = (-18).dp))
            if (onClose != null) {
                Text(
                    text = "닫기",
                    style = DossierType.title(12.sp),
                    color = DossierColors.inkMuted,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-56).dp, y = 4.dp)
                            .clickable(onClick = onClose)
                            // 스크롤한 내용이 아래로 지나갈 때 글자가 겹치지 않도록 종이를 덧댄다.
                            .background(DossierColors.paper)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** 타자기 문서처럼 옅은 가로줄을 긋는다. */
private fun Modifier.ruledPaper(): Modifier =
    drawBehind {
        val spacing = 26.dp.toPx()
        var y = spacing
        while (y < size.height) {
            drawLine(DossierColors.paperLine.copy(alpha = 0.45f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += spacing
        }
    }

@Composable
fun PaperClip(modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 16.dp, height = 40.dp)) {
        val stroke = Stroke(width = 2.dp.toPx())
        val inset = 3.dp.toPx()
        drawRoundRect(
            color = DossierColors.metal,
            size = Size(size.width, size.height),
            cornerRadius = CornerRadius(size.width / 2f),
            style = stroke,
        )
        drawRoundRect(
            color = DossierColors.metal,
            topLeft = Offset(inset, inset * 2f),
            size = Size(size.width - inset * 2f, size.height * 0.62f),
            cornerRadius = CornerRadius(size.width / 2f),
            style = stroke,
        )
    }
}

/** 패널 안 구획 제목. 명조 제목 아래 가는 잉크 줄. */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(top = 10.dp, bottom = 6.dp)) {
        Text(text = text, style = DossierType.title(13.sp), color = DossierColors.blueInk)
        Spacer(Modifier.height(3.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(DossierColors.blueInk.copy(alpha = 0.5f)))
    }
}
