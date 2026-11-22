package team.themoment.axia

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import team.themoment.axia.map.MapGeometry
import team.themoment.axia.map.WorldMapModel
import team.themoment.axia.simulation.Simulation
import team.themoment.axia.simulation.SimulationRunner
import team.themoment.axia.simulation.time.GameSpeed
import team.themoment.axia.simulation.world.WorldLoader
import team.themoment.axia.theme.AxiaTheme
import java.awt.Dimension

private val SPEED_KEYS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five).zip(GameSpeed.all).toMap()

fun main() {
    System.setProperty("apple.awt.application.name", "Axia")
    val world = WorldLoader.loadDefault()
    val mapModel = WorldMapModel(world, MapGeometry.loadDefault())
    val simulationScope = CoroutineScope(SupervisorJob())
    val runner = SimulationRunner(Simulation(world), simulationScope)
    application {
        Window(
            onCloseRequest = {
                simulationScope.cancel()
                exitApplication()
            },
            title = "Axia",
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
            onPreviewKeyEvent = { event -> handleTimeKey(event, runner) },
        ) {
            LaunchedEffect(window) { window.minimumSize = Dimension(960, 600) }
            AxiaTheme {
                AxiaApp(world = world, mapModel = mapModel, runner = runner)
            }
        }
    }
}

private fun handleTimeKey(
    event: KeyEvent,
    runner: SimulationRunner,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.key == Key.Spacebar) {
        runner.togglePause()
        return true
    }
    val speed = SPEED_KEYS[event.key] ?: return false
    runner.setSpeed(speed)
    return true
}
