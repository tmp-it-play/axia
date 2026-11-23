package team.themoment.axia

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuBarScope
import androidx.compose.ui.window.MenuComposable
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
import team.themoment.axia.simulation.SimulationRunner
import team.themoment.axia.simulation.economy.EconomyData
import team.themoment.axia.simulation.time.GameSpeed
import team.themoment.axia.simulation.world.PlayableCountries
import team.themoment.axia.simulation.world.WorldLoader
import team.themoment.axia.theme.AxiaTheme
import team.themoment.axia.update.AppUpdater
import team.themoment.axia.update.Installation
import java.awt.Dimension

private val SPEED_KEYS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five).zip(GameSpeed.all).toMap()

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
        val campaigns = remember { Campaigns(world, data, CampaignStore(), updateScope) }
        LaunchedEffect(campaigns) { campaigns.readSlots() }
        val exit = {
            campaigns.close()
            updateScope.cancel()
            exitApplication()
        }
        Window(
            onCloseRequest = { campaigns.exit(exit) },
            title = "Axia",
            icon = painterResource(Res.drawable.logo),
            state = rememberWindowState(width = 1440.dp, height = 900.dp),
            onKeyEvent = { event ->
                campaigns.busy == null && campaigns.message == null && campaigns.runner?.let { handleTimeKey(event, it) } == true
            },
        ) {
            MenuBar { CampaignMenu(campaigns) }
            LaunchedEffect(window) { window.minimumSize = Dimension(1100, 700) }
            AxiaTheme {
                AxiaApp(
                    world = world,
                    data = data,
                    playable = playable,
                    mapModel = mapModel,
                    campaigns = campaigns,
                    updater = updater,
                    onInstallerLaunched = { campaigns.exit(exit) },
                    onDiscardAndExit = exit,
                )
            }
        }
    }
}

@Composable
@MenuComposable
internal fun MenuBarScope.CampaignMenu(campaigns: Campaigns) {
    Menu("캠페인") {
        val running = campaigns.runner != null && campaigns.busy == null
        CampaignSlot.entries.filter { it != CampaignSlot.AUTO }.forEach { slot ->
            Item("${slot.label}에 기록", enabled = running, onClick = { campaigns.save(slot) })
        }
        Separator()
        Item("자동저장 후 시작 화면으로", enabled = running, onClick = campaigns::leave)
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
