package team.themoment.axia.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.console.ConsoleBrass
import team.themoment.axia.ui.console.ConsoleKey
import team.themoment.axia.ui.console.consoleFrame
import team.themoment.axia.ui.dossier.steelTexture

enum class GamePanel(
    val label: String,
    internal val symbol: ConsoleSymbol,
) {
    OVERVIEW("개요", ConsoleSymbol.FOLDER),
    ECONOMY("경제", ConsoleSymbol.FACTORY),
    BUDGET("재정", ConsoleSymbol.VAULT),
    POLICY("정책", ConsoleSymbol.DECREE),
    POLITICS("정치", ConsoleSymbol.ASSEMBLY),
    ORGANIZATIONS("조직", ConsoleSymbol.HIERARCHY),
    SOCIETY("사회", ConsoleSymbol.CITIZENS),
    DIPLOMACY("외교", ConsoleSymbol.GLOBE),
    MILITARY("군사", ConsoleSymbol.SHIELD),
    SITUATION("상황", ConsoleSymbol.FIELD_RADIO),
    STATISTICS("통계", ConsoleSymbol.CHART),
}

/** 강철 버튼 열. 작은 창에서는 모든 버튼에 도달할 수 있도록 세로로 스크롤한다. */
@Composable
fun SideTabs(
    selected: GamePanel?,
    onSelect: (GamePanel) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .width(102.dp)
            .fillMaxHeight()
            .steelTexture()
            .consoleFrame(),
    ) {
        val gap = 4.dp
        val buttonHeight = ((maxHeight - 24.dp - gap * (GamePanel.entries.size - 1)) / GamePanel.entries.size).coerceIn(40.dp, 52.dp)
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            GamePanel.entries.forEach { panel ->
                ConsoleKey(
                    onClick = { onSelect(panel) },
                    active = panel == selected,
                    modifier = Modifier.fillMaxWidth().height(buttonHeight),
                ) { ink ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ConsoleGlyph(
                            panel.symbol,
                            Modifier.size(26.dp),
                            color = if (panel == selected) ConsoleBrass else panel.symbol.metal,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(panel.label, color = ink, style = DossierType.title(14.sp), maxLines = 1)
                    }
                }
            }
        }
    }
}
