package team.themoment.axia

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.themoment.axia.simulation.Simulation
import team.themoment.axia.simulation.SimulationRunner
import team.themoment.axia.simulation.campaign.CampaignSave
import team.themoment.axia.simulation.campaign.CampaignScenario
import team.themoment.axia.simulation.campaign.CampaignStart
import team.themoment.axia.simulation.economy.EconomyData
import team.themoment.axia.simulation.world.World

/** 시작 화면과 실행 중인 한 판 사이의 전환. 상태 갱신은 UI 스레드, 저장과 재생은 작업 스레드에서 한다. */
internal class Campaigns(
    private val world: World,
    private val data: EconomyData,
    private val store: CampaignStore,
    private val scope: CoroutineScope,
) {
    var runner by mutableStateOf<SimulationRunner?>(null)
        private set
    var slots by mutableStateOf<Map<CampaignSlot, SavedCampaign?>>(emptyMap())
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var exitSaveFailed by mutableStateOf(false)
        private set
    private var sessionScope: CoroutineScope? = null

    fun readSlots() = operation("저장 목록 확인 중") { slots = store.slots() }

    fun start(
        countryId: String,
        scenario: CampaignScenario,
    ) = operation("캠페인 시작 중") {
        open(CampaignSave.new(CampaignStart(countryId, Simulation.DEFAULT_SEED, scenario)))
    }

    fun load(slot: CampaignSlot) = operation("${slot.label} 불러오는 중") { open(store.load(slot)) }

    private suspend fun open(save: CampaignSave) {
        check(runner == null)
        val simulation = withContext(Dispatchers.Default) { save.restore(world, data) }
        val newScope = CoroutineScope(SupervisorJob())
        sessionScope = newScope
        runner = SimulationRunner(simulation, newScope, save, ::autosave)
    }

    fun save(slot: CampaignSlot) =
        operation("${slot.label} 기록 중") {
            val campaign = requireNotNull(runner).checkpoint()
            write(slot, campaign)
            message = "${slot.label} · ${world.country(campaign.start.countryId).name} · ${campaign.date}"
        }

    private suspend fun autosave(campaign: CampaignSave) {
        try {
            write(CampaignSlot.AUTO, campaign)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { message = "자동저장 실패 · ${e.message}" }
        }
    }

    fun leave() = operation("캠페인 자동저장 중") { saveAndClose() }

    fun exit(onExit: () -> Unit) {
        if (runner == null) {
            onExit()
            return
        }
        operation("종료 전 자동저장 중") {
            try {
                saveAndClose()
            } catch (e: Exception) {
                if (e !is CancellationException) exitSaveFailed = true
                throw e
            }
            onExit()
        }
    }

    private suspend fun saveAndClose() {
        val current = runner ?: return
        write(CampaignSlot.AUTO, current.checkpoint(pause = true))
        close()
    }

    fun close() {
        sessionScope?.cancel()
        sessionScope = null
        runner = null
    }

    private suspend fun write(
        slot: CampaignSlot,
        campaign: CampaignSave,
    ) {
        store.save(slot, campaign)
        withContext(Dispatchers.Main) { slots = slots + (slot to campaign.summary()) }
    }

    fun dismissMessage() {
        message = null
        exitSaveFailed = false
    }

    private fun operation(
        label: String,
        block: suspend () -> Unit,
    ) {
        if (busy != null) return
        busy = label
        dismissMessage()
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                message = "${label.removeSuffix(" 중")} 실패 · ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }
}
