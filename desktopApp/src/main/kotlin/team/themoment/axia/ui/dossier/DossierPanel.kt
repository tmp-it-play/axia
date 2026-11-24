package team.themoment.axia.ui.dossier

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType

private val TabShape = CutCornerShape(topStart = 3.dp, topEnd = 12.dp)
private val CoverShape = RoundedCornerShape(3.dp)
private val Fold = 15.dp
private val SheetShape = CutCornerShape(topEnd = Fold)

/** 접힌 속지와 클립을 감싼 서류철. 색인과 닫기 버튼은 내용이 스크롤돼도 제자리에 남는다. */
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
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.weight(1f)) {
                Text(
                    text = title,
                    modifier =
                        Modifier
                            .widthIn(min = 96.dp)
                            .clip(TabShape)
                            .folderTexture()
                            .padding(start = 14.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
                    style = DossierType.title(15.sp),
                    color = DossierColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onClose != null) {
                Text(
                    text = "닫기",
                    modifier =
                        Modifier
                            .clip(CutCornerShape(topStart = 8.dp, topEnd = 2.dp))
                            .folderTexture()
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(role = Role.Button, onClick = onClose)
                            .padding(horizontal = 13.dp, vertical = 6.dp),
                    style = DossierType.number(12.sp),
                    color = DossierColors.ink,
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (scrollable) Modifier.weight(1f, fill = false) else Modifier)
                    .shadow(7.dp, CoverShape)
                    .folderTexture()
                    .border(1.dp, DossierColors.folderEdge.copy(alpha = 0.72f), CoverShape),
        ) {
            Box(Modifier.fillMaxWidth().padding(start = 13.dp, top = 9.dp, end = 13.dp, bottom = 13.dp)) {
                Spacer(Modifier.matchParentSize().offset(3.dp, 4.dp).background(DossierColors.folderShade, SheetShape))
                Spacer(Modifier.matchParentSize().offset(1.dp, 2.dp).background(DossierColors.paperShade, SheetShape))
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .shadow(2.dp, SheetShape)
                            .paperTexture()
                            .foldedCorner()
                            .padding(start = 16.dp, end = 14.dp, top = 24.dp, bottom = 12.dp)
                            .then(if (scrollable) Modifier.verticalScroll(key(title) { rememberScrollState() }) else Modifier),
                    content = content,
                )
            }
            PaperClip(Modifier.align(Alignment.TopStart).offset(x = 27.dp, y = 2.dp).rotate(-8f))
        }
    }
}

private fun Modifier.foldedCorner(): Modifier =
    drawWithCache {
        val edge = Fold.toPx()
        val x = size.width - edge
        val flap =
            Path().apply {
                moveTo(x, 0f)
                lineTo(size.width, edge)
                lineTo(x, edge)
                close()
            }
        onDrawBehind {
            drawPath(flap, DossierColors.paperLine)
            drawLine(DossierColors.folderEdge.copy(alpha = 0.65f), Offset(x, 0f), Offset(x, edge), 1.dp.toPx())
            drawLine(DossierColors.folderEdge.copy(alpha = 0.48f), Offset(x, edge), Offset(size.width, edge), 1.dp.toPx())
        }
    }

@Composable
private fun PaperClip(modifier: Modifier) {
    Spacer(
        modifier.size(width = 15.dp, height = 29.dp).drawWithCache {
            val wire =
                Path().apply {
                    moveTo(2f, 11f)
                    lineTo(2f, 5f)
                    cubicTo(2f, -0.2f, 13f, -0.2f, 13f, 5.5f)
                    lineTo(13f, 22f)
                    cubicTo(13f, 28f, 4.5f, 29f, 4.5f, 22.5f)
                    lineTo(4.5f, 9f)
                    cubicTo(4.5f, 5.6f, 10f, 5.6f, 10f, 9f)
                    lineTo(10f, 20f)
                }
            val shadowStroke = Stroke(width = 2.7f, cap = StrokeCap.Round)
            val wireStroke = Stroke(width = 2f, cap = StrokeCap.Round)
            val shine = Stroke(width = 0.72f, cap = StrokeCap.Round)
            onDrawBehind {
                scale(size.width / 15f, pivot = Offset.Zero) {
                    translate(0.8f, 1f) { drawPath(wire, Color.Black.copy(alpha = 0.22f), style = shadowStroke) }
                    drawPath(wire, Color(0xFF5B5749), style = wireStroke)
                    translate(-0.28f, -0.22f) { drawPath(wire, Color(0xFFE1D9BD), style = shine) }
                }
            }
        },
    )
}

/** 구획 제목과 양식의 구분선. */
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
