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
import team.themoment.axia.ui.dossier.controlTooltip
import team.themoment.axia.ui.dossier.steelTexture

enum class GamePanel(
    val label: String,
    internal val symbol: ConsoleSymbol,
    internal val tooltip: String,
) {
    OVERVIEW("개요", ConsoleSymbol.FOLDER, "정부 지지율과 주요 경제 지표, 다가오는 선거와 심의 중인 법안을 살펴봅니다."),
    ECONOMY("경제", ConsoleSymbol.FACTORY, "경제 지표와 중앙은행의 통화 정책, 대외 거래와 산업별 실적을 살펴봅니다."),
    BUDGET("재정", ConsoleSymbol.VAULT, "세입과 지출 내역을 대조하고 국채 상환 부담을 확인합니다."),
    POLICY("정책", ConsoleSymbol.DECREE, "현행 정책을 살펴보고 개정안을 발의합니다."),
    POLITICS("정치", ConsoleSymbol.ASSEMBLY, "정부 구성과 의회 의석, 다음 선거 일정과 후보를 확인합니다."),
    ORGANIZATIONS("조직", ConsoleSymbol.HIERARCHY, "국내 조직의 영향력과 정부에 대한 태도를 살펴보고 대응을 결정합니다."),
    SOCIETY("사회", ConsoleSymbol.CITIZENS, "인구 구성과 생활 수준을 살펴보고 집단별 만족도를 비교합니다."),
    DIPLOMACY("외교", ConsoleSymbol.GLOBE, "상대국과 협상을 진행하고 국제기구 가입 현황을 확인합니다."),
    MILITARY("군사", ConsoleSymbol.SHIELD, "병력 동원 수준을 정하고 전쟁 상황과 전후 복구를 관리합니다."),
    SITUATION("상황", ConsoleSymbol.FIELD_RADIO, "세계의 분쟁과 사건을 살펴보고 어떤 소식에 시간을 멈출지 정합니다."),
    STATISTICS("통계", ConsoleSymbol.CHART, "경제·재정·사회 지표를 골라 월별 변화를 그래프로 살펴봅니다."),
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
                    modifier = Modifier.fillMaxWidth().height(buttonHeight).controlTooltip(panel.label, panel.tooltip),
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
