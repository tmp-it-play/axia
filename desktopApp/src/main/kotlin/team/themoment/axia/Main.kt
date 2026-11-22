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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import team.themoment.axia.map.MapGeometry
import team.themoment.axia.map.WorldMapModel
import team.themoment.axia.simulation.Simulation
import team.themoment.axia.simulation.SimulationRunner
import team.themoment.axia.simulation.economy.EconomyData
import team.themoment.axia.simulation.time.GameSpeed
import team.themoment.axia.simulation.world.WorldLoader
import team.themoment.axia.theme.AxiaTheme
import team.themoment.axia.update.AppUpdater
import team.themoment.axia.update.Installation
import java.awt.Dimension

private val SPEED_KEYS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five).zip(GameSpeed.all).toMap()

fun main() {
    System.setProperty("apple.awt.application.name", "Axia")
    val world = WorldLoader.loadDefault()
    val mapModel = WorldMapModel(world, MapGeometry.loadDefault())
    val simulationScope = CoroutineScope(SupervisorJob())
    val runner = SimulationRunner(Simulation(world, EconomyData.loadDefault(world), playerCountryId = "KOR"), simulationScope)
    val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val updater = Installation.current()?.let { AppUpdater(it, updateScope) }
    updater?.checkForUpdate()
    application {
        val exit = {
            simulationScope.cancel()
            updateScope.cancel()
            exitApplication()
        }
        Window(
            onCloseRequest = exit,
            title = "Axia",
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
            onPreviewKeyEvent = { event -> handleTimeKey(event, runner) },
        ) {
            LaunchedEffect(window) { window.minimumSize = Dimension(960, 600) }
            AxiaTheme {
                AxiaApp(world = world, mapModel = mapModel, runner = runner, updater = updater, onInstallerLaunched = exit)
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
