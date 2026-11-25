package team.themoment.axia

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import team.themoment.axia.game.GameScreen
import team.themoment.axia.lobby.LoadGameScreen
import team.themoment.axia.lobby.LobbyScreen
import team.themoment.axia.map.WorldMapModel
import team.themoment.axia.newgame.NewGameScreen
import team.themoment.axia.simulation.economy.EconomyData
import team.themoment.axia.simulation.world.Country
import team.themoment.axia.simulation.world.World
import team.themoment.axia.theme.CampaignTypography
import team.themoment.axia.ui.dossier.MetricTooltipHost
import team.themoment.axia.update.AppUpdater
import team.themoment.axia.update.UpdateNotice

private enum class StartScreen { LOBBY, LOAD, NEW_GAME }

@Composable
internal fun AxiaApp(
    world: World,
    data: EconomyData,
    playable: List<Country>,
    mapModel: WorldMapModel,
    campaigns: Campaigns,
    updater: AppUpdater?,
    onInstallerLaunched: () -> Unit,
    onExit: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        val runner = campaigns.runner
        if (runner == null) {
            var screen by remember { mutableStateOf(StartScreen.LOBBY) }
            when (screen) {
                StartScreen.LOBBY -> {
                    val latest = campaigns.continueSlot
                    LobbyScreen(
                        mapModel = mapModel,
                        canContinue = latest != null,
                        onContinue = { latest?.let(campaigns::load) },
                        onLoad = { screen = StartScreen.LOAD },
                        onNewGame = { screen = StartScreen.NEW_GAME },
                        onExit = onExit,
                    )
                }

                StartScreen.LOAD -> {
                    LoadGameScreen(world, campaigns.slots, campaigns::load) { screen = StartScreen.LOBBY }
                }

                StartScreen.NEW_GAME -> {
                    MetricTooltipHost { NewGameScreen(data, playable, campaigns::start, onBack = { screen = StartScreen.LOBBY }) }
                }
            }
        } else {
            CampaignTypography {
                GameScreen(world = world, mapModel = mapModel, runner = runner)
            }
        }
        val busy = campaigns.busy
        if (busy != null) {
            AlertDialog(onDismissRequest = {}, confirmButton = {}, text = { Text(busy) })
        } else {
            campaigns.message?.let { message ->
                AlertDialog(
                    onDismissRequest = campaigns::dismissMessage,
                    text = { Text(message) },
                    confirmButton = { TextButton(onClick = campaigns::dismissMessage) { Text("확인") } },
                    dismissButton = {
                        if (campaigns.exitSaveFailed) {
                            TextButton(onClick = onExit) { Text("저장하지 않고 종료") }
                        }
                    },
                )
            }
        }
        updater?.let {
            val updateState by it.state.collectAsState()
            UpdateNotice(
                state = updateState,
                onInstall = { installer -> it.install(installer, campaigns::saveBeforeUpdate, onInstallerLaunched) },
                onDismiss = it::dismiss,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
            )
        }
    }
}
