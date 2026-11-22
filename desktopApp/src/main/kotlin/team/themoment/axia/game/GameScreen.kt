package team.themoment.axia.game

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import team.themoment.axia.game.panels.BudgetPanel
import team.themoment.axia.game.panels.EconomyPanel
import team.themoment.axia.game.panels.OverviewPanel
import team.themoment.axia.game.panels.SocietyPanel
import team.themoment.axia.game.panels.StatisticsPanel
import team.themoment.axia.map.MapLegend
import team.themoment.axia.map.MapMode
import team.themoment.axia.map.MapModeBar
import team.themoment.axia.map.WorldMap
import team.themoment.axia.map.WorldMapModel
import team.themoment.axia.selection.SelectionPanel
import team.themoment.axia.simulation.SimulationRunner
import team.themoment.axia.simulation.world.World
import team.themoment.axia.ui.dossier.DossierPanel

/** 게임 화면. 상단 띠, 왼쪽 폴더 탭, 지도, 지도 위에 펼치는 서류철 패널. */
@Composable
fun GameScreen(
    world: World,
    mapModel: WorldMapModel,
    runner: SimulationRunner,
    modifier: Modifier = Modifier,
) {
    val clock by runner.clock.collectAsState()
    val economy by runner.economy.collectAsState()
    val nation by runner.nation.collectAsState()
    var panel by remember { mutableStateOf<GamePanel?>(GamePanel.OVERVIEW) }
    var selectedRegionId by remember { mutableStateOf<String?>(null) }
    var mapMode by remember { mutableStateOf(MapMode.POLITICAL) }
    val playerName = world.country(nation.countryId).name
    val playerIndicators = economy.countries.getValue(nation.countryId)
    val playerHistory = economy.history.getValue(nation.countryId)

    Column(modifier = modifier.fillMaxSize()) {
        TopStrip(
            countryName = playerName,
            approval = nation.approval,
            indicators = playerIndicators,
            clock = clock,
            onTogglePause = runner::togglePause,
            onSpeedSelect = runner::setSpeed,
            onAdvanceDay = runner::advanceDay,
        )
        Row(Modifier.weight(1f)) {
            SideTabs(selected = panel, onSelect = { panel = if (panel == it) null else it })
            Box(Modifier.weight(1f).fillMaxHeight()) {
                WorldMap(
                    model = mapModel,
                    fillColor = { fill -> mapMode.colorOf(fill, economy) },
                    selectedRegionId = selectedRegionId,
                    onRegionClick = { selectedRegionId = it },
                    modifier = Modifier.fillMaxSize(),
                )
                Column(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalAlignment = Alignment.End) {
                    MapModeBar(selected = mapMode, onSelect = { mapMode = it })
                    MapLegend(mode = mapMode, modifier = Modifier.padding(top = 8.dp))
                }
                panel?.let { open ->
                    DossierPanel(
                        title = open.label,
                        onClose = { panel = null },
                        scrollable = true,
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp)
                                .width(if (open == GamePanel.STATISTICS) 760.dp else 440.dp),
                    ) {
                        when (open) {
                            GamePanel.OVERVIEW -> OverviewPanel(playerName, nation, playerIndicators)
                            GamePanel.ECONOMY -> EconomyPanel(playerIndicators, playerHistory)
                            GamePanel.BUDGET -> BudgetPanel(nation.budget, runner::setTaxRate, runner::setSpending)
                            GamePanel.SOCIETY -> SocietyPanel(nation)
                            GamePanel.STATISTICS -> StatisticsPanel(playerHistory, nation.history)
                        }
                    }
                }
                selectedRegionId?.let { regionId ->
                    SelectionPanel(
                        world = world,
                        regionId = regionId,
                        economy = economy.countries[world.region(regionId).countryId],
                        onClose = { selectedRegionId = null },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    )
                }
            }
        }
    }
}
