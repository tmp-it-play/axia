package team.themoment.axia.game.panels

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType

@Composable
internal fun PanelColumns(
    leftWeight: Float = 1f,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LedgerSpread(maxWidth < 720.dp, leftWeight, left, right)
    }
}

@Composable
internal fun LedgerSpread(
    stacked: Boolean,
    leftWeight: Float,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    if (stacked) {
        LedgerColumn(Modifier.fillMaxSize()) {
            left()
            Spacer(Modifier.height(22.dp))
            right()
        }
    } else {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LedgerColumn(Modifier.weight(leftWeight)) { left() }
            PageRule()
            LedgerColumn(Modifier.weight(1f)) { right() }
        }
    }
}

@Composable
internal fun LedgerColumn(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Box(modifier.fillMaxHeight()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll).padding(top = 5.dp, bottom = 22.dp, end = 7.dp),
            content = content,
        )
        if (scroll.canScrollForward || scroll.canScrollBackward) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style =
                    LocalScrollbarStyle.current.copy(
                        thickness = 4.dp,
                        unhoverColor = DossierColors.folderEdge.copy(alpha = 0.68f),
                        hoverColor = DossierColors.blueInk,
                    ),
            )
        }
    }
}

@Composable
internal fun PageRule() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(DossierColors.folderEdge.copy(alpha = 0.45f)))
}

@Composable
internal fun LedgerHeading(title: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(title, style = DossierType.title(22.sp), color = DossierColors.ink)
        Spacer(Modifier.height(7.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(DossierColors.blueInk))
    }
}
