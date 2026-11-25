package team.themoment.axia.game

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import team.themoment.axia.game.panels.EventDialog
import team.themoment.axia.game.panels.nextElectionTag
import team.themoment.axia.map.MapData
import team.themoment.axia.map.MapLegend
import team.themoment.axia.map.MapMode
import team.themoment.axia.map.MapModeBar
import team.themoment.axia.map.MapViewport
import team.themoment.axia.map.WorldMap
import team.themoment.axia.map.WorldMapModel
import team.themoment.axia.selection.SelectionPanel
import team.themoment.axia.simulation.SimulationRunner
import team.themoment.axia.simulation.world.World

/** 지도와 분야별 전체 화면이 시간 조작과 캠페인 상태를 공유한다. */
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
    val politics by runner.politics.collectAsState()
    val worldState by runner.world.collectAsState()
    val countryName = { id: String -> world.countries[id]?.name ?: id }
    val desk = remember(runner) { CampaignDesk() }
    val viewport = remember(runner, mapModel) { MapViewport() }
    var selectedRegionId by remember(runner) { mutableStateOf<String?>(null) }
    var mapMode by remember(runner) { mutableStateOf(MapMode.POLITICAL) }
    val playerName = world.country(nation.countryId).name
    val playerIndicators = economy.countries.getValue(nation.countryId)

    val event = worldState.pendingEvents.firstOrNull()
    var foldedEventId by remember(runner) { mutableStateOf<Int?>(null) }
    val showNewspaper = event != null && foldedEventId != event.id
    val gameFocus = remember { FocusRequester() }
    var hasGameFocus by remember { mutableStateOf(false) }
    val pageOpen = desk.panel != null
    LaunchedEffect(pageOpen, showNewspaper, hasGameFocus) {
        if (pageOpen && !showNewspaper && !hasGameFocus) gameFocus.requestFocus()
    }

    Box(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent {
                if (!showNewspaper && desk.panel != null && it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                    desk.panel = null
                    true
                } else {
                    false
                }
            }.onFocusChanged { hasGameFocus = it.hasFocus }
            .focusRequester(gameFocus)
            .focusable(enabled = pageOpen),
    ) {
        Column(Modifier.fillMaxSize()) {
            TopStrip(
                countryName = playerName,
                approval = nation.approval,
                indicators = playerIndicators,
                clock = clock,
                nextElection = nextElectionTag(politics.schedule, clock.date),
                politicalCapital = nation.society.politicalCapital,
                pendingEventCount = if (showNewspaper) 0 else worldState.pendingEvents.size,
                onOpenNewspaper = { foldedEventId = null },
                headline =
                    nation.society.news
                        .firstOrNull { it.major && !it.date.isBefore(clock.date.minusDays(HEADLINE_DAYS)) }
                        ?.text,
                onTogglePause = runner::togglePause,
                onSpeedSelect = runner::setSpeed,
                onAdvanceDay = runner::advanceDay,
            )
            val open = desk.panel
            if (open != null) {
                CampaignPage(
                    panel = open,
                    desk = desk,
                    reviewBody = politics.reviewBody,
                    onReturnToMap = { desk.panel = null },
                ) {
                    CampaignPageContent(open, desk, runner, nation, economy, politics, worldState, clock.date, countryName)
                }
                return@Column
            }
            val mapData = remember(economy, worldState, nation) { MapData(economy, worldState, nation) }
            Row(Modifier.weight(1f)) {
                SideTabs(selected = null, onSelect = {
                    desk.panel = it
                    selectedRegionId = null
                })
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    WorldMap(
                        model = mapModel,
                        viewport = viewport,
                        fillColor = { fill -> mapMode.colorOf(fill, mapData) },
                        selectedRegionId = selectedRegionId,
                        regionOwners = worldState.military.regionOwners,
                        onRegionClick = { selectedRegionId = it },
                        regionOverlay = nation.countryId to { regionId -> mapMode.regionColor(regionId, mapData) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Column(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalAlignment = Alignment.End) {
                        MapModeBar(selected = mapMode, onSelect = { mapMode = it })
                        MapLegend(mode = mapMode, modifier = Modifier.padding(top = 8.dp))
                    }
                    selectedRegionId?.let { regionId ->
                        key(regionId) {
                            SelectionPanel(
                                world = world,
                                regionId = regionId,
                                economy = economy,
                                controllerId = worldState.military.regionOwners[regionId],
                                onRegionalProgram = runner::setRegionalProgram,
                                onRenameParty = runner::renameParty,
                                onOpenDiplomacy = {
                                    desk.openPartner(it)
                                    selectedRegionId = null
                                },
                                snapshot = worldState,
                                playerRegion =
                                    mapData.regions[regionId]?.takeIf {
                                        (
                                            worldState.military.regionOwners[regionId]
                                                ?: nation.countryId
                                        ) ==
                                            nation.countryId
                                    },
                                playerId = nation.countryId,
                                onClose = { selectedRegionId = null },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
        if (event != null && showNewspaper) {
            EventDialog(
                event = event,
                debt = nation.budget.debtWorkout,
                onCreditorTerms = runner::setCreditorTerms,
                remaining = worldState.pendingEvents.size,
                recentEvents = worldState.events,
                countryName = countryName,
                onChoose = runner::chooseEventOption,
                onFold = { foldedEventId = event.id },
                mapModel = mapModel,
                regionOwners = worldState.military.regionOwners,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 큰 소식을 상단 띠에 띄워 두는 기간. */
private const val HEADLINE_DAYS = 30L
