package team.themoment.axia.game

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType

enum class GamePanel(
    val label: String,
) {
    OVERVIEW("개요"),
    ECONOMY("경제"),
    BUDGET("재정"),
    SOCIETY("사회"),
    STATISTICS("통계"),
}

/** 왼쪽 가장자리의 폴더 색인 탭. 선택된 탭은 마닐라 폴더 색으로 튀어나온다. */
@Composable
fun SideTabs(
    selected: GamePanel?,
    onSelect: (GamePanel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .width(64.dp)
                .fillMaxHeight()
                .background(DossierColors.desk)
                .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GamePanel.entries.forEach { panel ->
            val isSelected = panel == selected
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = if (isSelected) 6.dp else 12.dp)
                        .height(52.dp)
                        .background(
                            if (isSelected) DossierColors.folder else DossierColors.deskRaised,
                            RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp),
                        ).clickable { onSelect(panel) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = panel.label,
                    style = DossierType.title(14.sp),
                    color = if (isSelected) DossierColors.ink else DossierColors.deskText,
                )
            }
        }
    }
}
