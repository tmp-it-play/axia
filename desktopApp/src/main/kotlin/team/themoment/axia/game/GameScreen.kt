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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import team.themoment.axia.game.panels.BudgetPanel
import team.themoment.axia.game.panels.DiplomacyPanel
import team.themoment.axia.game.panels.EconomyPanel
import team.themoment.axia.game.panels.EventDialog
import team.themoment.axia.game.panels.MilitaryPanel
import team.themoment.axia.game.panels.OrganizationsPanel
import team.themoment.axia.game.panels.OverviewPanel
import team.themoment.axia.game.panels.PolicyPanel
import team.themoment.axia.game.panels.PoliticsPanel
import team.themoment.axia.game.panels.SituationPanel
import team.themoment.axia.game.panels.SocietyPanel
import team.themoment.axia.game.panels.StatisticsPanel
import team.themoment.axia.game.panels.nextElectionTag
import team.themoment.axia.map.MapData
import team.themoment.axia.map.MapLegend
import team.themoment.axia.map.MapMode
import team.themoment.axia.map.MapModeBar
import team.themoment.axia.map.WorldMap
import team.themoment.axia.map.WorldMapModel
import team.themoment.axia.selection.SelectionPanel
import team.themoment.axia.simulation.SimulationRunner
import team.themoment.axia.simulation.nation.InstitutionItem
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
    val politics by runner.politics.collectAsState()
    val worldState by runner.world.collectAsState()
    val pauseSettings by runner.pauseSettings.collectAsState()
    val countryName = { id: String -> world.countries[id]?.name ?: id }
    var panel by remember { mutableStateOf<GamePanel?>(GamePanel.OVERVIEW) }
    var selectedRegionId by remember { mutableStateOf<String?>(null) }
    var mapMode by remember { mutableStateOf(MapMode.POLITICAL) }
    val playerName = world.country(nation.countryId).name
    val playerIndicators = economy.countries.getValue(nation.countryId)
    val playerHistory = economy.history.getValue(nation.countryId)
    val mapData = remember(economy, worldState, nation) { MapData(economy, worldState, nation) }

    Column(modifier = modifier.fillMaxSize()) {
        TopStrip(
            countryName = playerName,
            approval = nation.approval,
            indicators = playerIndicators,
            clock = clock,
            nextElection = nextElectionTag(politics.schedule, clock.date),
            politicalCapital = nation.society.politicalCapital,
            headline =
                nation.society.news
                    .firstOrNull { it.major && !it.date.isBefore(clock.date.minusDays(HEADLINE_DAYS)) }
                    ?.text,
            onTogglePause = runner::togglePause,
            onSpeedSelect = runner::setSpeed,
            onAdvanceDay = runner::advanceDay,
        )
        Row(Modifier.weight(1f)) {
            SideTabs(selected = panel, onSelect = { panel = if (panel == it) null else it })
            Box(Modifier.weight(1f).fillMaxHeight()) {
                WorldMap(
                    model = mapModel,
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
                panel?.let { open ->
                    DossierPanel(
                        title = open.label,
                        onClose = { panel = null },
                        scrollable = true,
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp)
                                .width(if (open == GamePanel.STATISTICS) 760.dp else 460.dp),
                    ) {
                        when (open) {
                            GamePanel.OVERVIEW -> {
                                OverviewPanel(playerName, nation, playerIndicators)
                            }

                            GamePanel.ECONOMY -> {
                                EconomyPanel(
                                    countryId = nation.countryId,
                                    countryName = countryName,
                                    indicators = playerIndicators,
                                    history = playerHistory,
                                    sectors = economy.sectors.getValue(nation.countryId),
                                    externalAccount = economy.externalAccounts.getValue(nation.countryId),
                                    companies = economy.companies.getValue(nation.countryId),
                                    closures = economy.companyClosures.getValue(nation.countryId),
                                    onRenameCompany = runner::renameCompany,
                                    onRequestImf = runner::requestImfProgram,
                                    trade = worldState.trade,
                                    date = worldState.date,
                                    monetary = economy.monetary.getValue(nation.countryId),
                                    politics = politics,
                                    politicalCapital = nation.society.politicalCapital,
                                    onAppointGovernor = runner::appointGovernor,
                                    onPressureBank = runner::pressureBank,
                                    onDirectRate = runner::directPolicyRate,
                                    onDraftIndependence = { runner.editBudgetDraft(InstitutionItem.CENTRAL_BANK_INDEPENDENCE, it) },
                                    onViewBill = { panel = GamePanel.POLITICS },
                                )
                            }

                            GamePanel.BUDGET -> {
                                BudgetPanel(
                                    nation.budget,
                                    politics,
                                    runner::editBudgetDraft,
                                    runner::clearBudgetDraft,
                                    onIntroduce = {
                                        runner.introduceBudget()
                                        panel = GamePanel.POLITICS
                                    },
                                    onViewBill = { panel = GamePanel.POLITICS },
                                )
                            }

                            GamePanel.POLICY -> {
                                PolicyPanel(
                                    nation.social,
                                    nation.special,
                                    politics,
                                    runner::editBudgetDraft,
                                    runner::clearBudgetDraft,
                                    onIntroduce = {
                                        runner.introduceBudget()
                                        panel = GamePanel.POLITICS
                                    },
                                    onViewBill = { panel = GamePanel.POLITICS },
                                )
                            }

                            GamePanel.ORGANIZATIONS -> {
                                OrganizationsPanel(
                                    nation.society,
                                    onAct = runner::actOnOrganization,
                                    onRename = runner::renameOrganization,
                                )
                            }

                            GamePanel.POLITICS -> {
                                PoliticsPanel(
                                    politics,
                                    today = clock.date,
                                    onCallEarlyElection = runner::callEarlyElection,
                                    onCoalitionPartner = runner::preferCoalitionPartner,
                                    onOppositionVeto = runner::setOppositionVeto,
                                    onBudget = { panel = GamePanel.BUDGET },
                                    onNegotiate = runner::negotiateBudgetWith,
                                    onIntroduce = runner::introduceBudget,
                                    onRevise = {
                                        runner.reviseStalledBudget()
                                        panel = GamePanel.BUDGET
                                    },
                                    onRenamePerson = runner::renamePerson,
                                )
                            }

                            GamePanel.DIPLOMACY -> {
                                DiplomacyPanel(
                                    worldState,
                                    nation.society.politicalCapital,
                                    countryName,
                                    runner::diplomaticAction,
                                    runner::changeMembership,
                                )
                            }

                            GamePanel.MILITARY -> {
                                MilitaryPanel(
                                    worldState,
                                    nation.countryId,
                                    nation.society.politicalCapital,
                                    countryName,
                                    onMobilize = runner::setMobilization,
                                    onReconstruction = runner::setReconstructionEffort,
                                    onTradeRoute = runner::setTradeRoute,
                                    onRegionTreaty = runner::negotiateRegion,
                                    onAction = runner::militaryAction,
                                )
                            }

                            GamePanel.SITUATION -> {
                                SituationPanel(worldState, countryName, pauseSettings, runner::setAutoPause)
                            }

                            GamePanel.SOCIETY -> {
                                SocietyPanel(nation)
                            }

                            GamePanel.STATISTICS -> {
                                StatisticsPanel(playerHistory, nation.history)
                            }
                        }
                    }
                }
                worldState.pendingEvents.firstOrNull()?.let { event ->
                    EventDialog(
                        event = event,
                        debt = nation.budget.debtWorkout,
                        onCreditorTerms = runner::setCreditorTerms,
                        remaining = worldState.pendingEvents.size,
                        countryName = countryName,
                        onChoose = runner::chooseEventOption,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                selectedRegionId?.let { regionId ->
                    key(regionId) {
                        SelectionPanel(
                            world = world,
                            regionId = regionId,
                            economy = economy.countries[worldState.military.regionOwners[regionId] ?: world.region(regionId).countryId],
                            controllerId = worldState.military.regionOwners[regionId],
                            onRegionalProgram = runner::setRegionalProgram,
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
}

/** 큰 소식을 상단 띠에 띄워 두는 기간. */
private const val HEADLINE_DAYS = 30L
