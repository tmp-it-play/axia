package team.themoment.axia

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import org.jetbrains.compose.resources.painterResource
import team.themoment.axia.map.MapGeometry
import team.themoment.axia.map.WorldMapModel
import team.themoment.axia.resources.Res
import team.themoment.axia.resources.logo
import team.themoment.axia.simulation.Simulation
import team.themoment.axia.simulation.SimulationRunner
import team.themoment.axia.simulation.economy.EconomyData
import team.themoment.axia.simulation.time.GameSpeed
import team.themoment.axia.simulation.world.PlayableCountries
import team.themoment.axia.simulation.world.World
import team.themoment.axia.simulation.world.WorldLoader
import team.themoment.axia.theme.AxiaTheme
import team.themoment.axia.update.AppUpdater
import team.themoment.axia.update.Installation
import java.awt.Dimension

private val SPEED_KEYS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five).zip(GameSpeed.all).toMap()

/** 진행 중인 한 판. 시뮬레이션 코루틴의 수명을 함께 관리한다. */
private class GameSession(
    world: World,
    data: EconomyData,
    playerCountryId: String,
) {
    private val scope = CoroutineScope(SupervisorJob())
    val runner = SimulationRunner(Simulation(world, data, playerCountryId), scope)

    fun close() = scope.cancel()
}

fun main() {
    System.setProperty("apple.awt.application.name", "Axia")
    val world = WorldLoader.loadDefault()
    val data = EconomyData.loadDefault(world)
    val playable = PlayableCountries.loadDefault(world, data)
    val mapModel = WorldMapModel(world, MapGeometry.loadDefault())
    val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val updater = Installation.current()?.let { AppUpdater(it, updateScope) }
    updater?.checkForUpdate()
    application {
        var session by remember { mutableStateOf<GameSession?>(null) }
        val exit = {
            session?.close()
            updateScope.cancel()
            exitApplication()
        }
        Window(
            onCloseRequest = exit,
            title = "Axia",
            icon = painterResource(Res.drawable.logo),
            state = rememberWindowState(width = 1440.dp, height = 900.dp),
            onPreviewKeyEvent = { event -> session?.let { handleTimeKey(event, it.runner) } ?: false },
        ) {
            LaunchedEffect(window) { window.minimumSize = Dimension(1100, 700) }
            AxiaTheme {
                AxiaApp(
                    world = world,
                    data = data,
                    playable = playable,
                    mapModel = mapModel,
                    runner = session?.runner,
                    onStart = { countryId -> session = GameSession(world, data, countryId) },
                    updater = updater,
                    onInstallerLaunched = exit,
                )
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
