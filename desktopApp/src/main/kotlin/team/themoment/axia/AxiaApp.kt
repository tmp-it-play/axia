package team.themoment.axia

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import team.themoment.axia.map.WorldMap
import team.themoment.axia.map.WorldMapModel
import team.themoment.axia.selection.SelectionPanel
import team.themoment.axia.simulation.SimulationRunner
import team.themoment.axia.simulation.world.World
import team.themoment.axia.time.TimeControlBar

@Composable
fun AxiaApp(
    world: World,
    mapModel: WorldMapModel,
    runner: SimulationRunner,
) {
    val clock by runner.clock.collectAsState()
    var selectedRegionId by remember { mutableStateOf<String?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        TimeControlBar(
            clock = clock,
            onTogglePause = runner::togglePause,
            onSpeedSelect = runner::setSpeed,
            onAdvanceDay = runner::advanceDay,
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            WorldMap(
                model = mapModel,
                selectedRegionId = selectedRegionId,
                onRegionClick = { selectedRegionId = it },
                modifier = Modifier.fillMaxSize(),
            )
            selectedRegionId?.let { regionId ->
                SelectionPanel(
                    world = world,
                    regionId = regionId,
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                )
            }
        }
    }
}
