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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import team.themoment.axia.game.GameScreen
import team.themoment.axia.map.WorldMapModel
import team.themoment.axia.newgame.NewGameScreen
import team.themoment.axia.simulation.economy.EconomyData
import team.themoment.axia.simulation.world.Country
import team.themoment.axia.simulation.world.World
import team.themoment.axia.update.AppUpdater
import team.themoment.axia.update.UpdateNotice

/** 게임이 시작되기 전에는 국가 선택 화면, 시작한 뒤에는 게임 화면. 업데이트 알림은 두 화면 위에 띄운다. */
@Composable
internal fun AxiaApp(
    world: World,
    data: EconomyData,
    playable: List<Country>,
    mapModel: WorldMapModel,
    campaigns: Campaigns,
    updater: AppUpdater?,
    onInstallerLaunched: () -> Unit,
    onDiscardAndExit: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        val runner = campaigns.runner
        if (runner == null) {
            NewGameScreen(data, playable, campaigns.slots, campaigns::start, campaigns::load)
        } else {
            GameScreen(world = world, mapModel = mapModel, runner = runner)
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
                            TextButton(onClick = onDiscardAndExit) { Text("저장하지 않고 종료") }
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
