package team.themoment.axia.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import team.themoment.axia.game.panels.BillsPage
import team.themoment.axia.game.panels.BudgetPanel
import team.themoment.axia.game.panels.DiplomacyPanel
import team.themoment.axia.game.panels.EconomyPanel
import team.themoment.axia.game.panels.LegislativeWorkspace
import team.themoment.axia.game.panels.MilitaryPanel
import team.themoment.axia.game.panels.OrganizationsPanel
import team.themoment.axia.game.panels.OverviewPanel
import team.themoment.axia.game.panels.PoliticsPanel
import team.themoment.axia.game.panels.SituationPanel
import team.themoment.axia.game.panels.SocietyPanel
import team.themoment.axia.game.panels.StatisticsPanel
import team.themoment.axia.simulation.SimulationRunner
import team.themoment.axia.simulation.WorldSnapshot
import team.themoment.axia.simulation.economy.EconomySnapshot
import team.themoment.axia.simulation.nation.InstitutionItem
import team.themoment.axia.simulation.nation.NationSnapshot
import team.themoment.axia.simulation.politics.PoliticsSnapshot
import java.time.LocalDate

@Composable
internal fun CampaignPageContent(
    open: GamePanel,
    desk: CampaignDesk,
    runner: SimulationRunner,
    nation: NationSnapshot,
    economy: EconomySnapshot,
    politics: PoliticsSnapshot,
    worldState: WorldSnapshot,
    today: LocalDate,
    countryName: (String) -> String,
) {
    val playerIndicators = economy.countries.getValue(nation.countryId)
    val playerHistory = economy.history.getValue(nation.countryId)
    when (open) {
        GamePanel.OVERVIEW -> {
            OverviewPanel(
                nation,
                playerIndicators,
                politics,
                today,
                onElections = {
                    desk.politics = PoliticsPage.ELECTIONS
                    desk.panel = GamePanel.POLITICS
                },
                onViewBill = desk::viewBill,
            )
        }

        GamePanel.ECONOMY -> {
            EconomyPanel(
                page = desk.economy,
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
                date = today,
                monetary = economy.monetary.getValue(nation.countryId),
                politics = politics,
                politicalCapital = nation.society.politicalCapital,
                onAppointGovernor = runner::appointGovernor,
                onPressureBank = runner::pressureBank,
                onDirectRate = runner::directPolicyRate,
                onOpenIndependence = { desk.editDraft(InstitutionItem.CENTRAL_BANK_INDEPENDENCE) },
                onViewBill = desk::viewBill,
            )
        }

        GamePanel.BUDGET -> {
            BudgetPanel(
                nation.budget,
                politics,
                desk.budget,
                desk::editDraft,
                desk::reviewDraft,
                desk::viewBill,
            )
        }

        GamePanel.POLICY -> {
            if (desk.policy == PolicyPage.HEARING) {
                BillsPage(
                    politics,
                    desk.showLastDecision,
                    onSelectLast = { desk.showLastDecision = it },
                    onOpenDraft = { if (politics.draft.isEmpty()) desk.editDraft(null) else desk.reviewDraft() },
                    onRevise = {
                        runner.reviseStalledBudget()
                        desk.reviewDraft()
                    },
                    onNegotiate = runner::negotiateBudgetWith,
                    onOppositionVeto = runner::setOppositionVeto,
                )
            } else {
                LegislativeWorkspace(
                    nation,
                    economy.monetary.getValue(nation.countryId),
                    politics,
                    desk,
                    onEdit = runner::editBudgetDraft,
                    onClear = runner::clearBudgetDraft,
                    onIntroduce = {
                        runner.introduceBudget()
                        desk.viewBill()
                    },
                )
            }
        }

        GamePanel.ORGANIZATIONS -> {
            OrganizationsPanel(
                nation.society,
                desk.organizations,
                onAct = runner::actOnOrganization,
                onRename = runner::renameOrganization,
            )
        }

        GamePanel.POLITICS -> {
            PoliticsPanel(
                politics,
                today = today,
                page = desk.politics,
                onCallEarlyElection = runner::callEarlyElection,
                onCoalitionPartner = runner::preferCoalitionPartner,
                onChooseElectionList = runner::chooseElectionList,
                onRenamePerson = runner::renamePerson,
                onRenameParty = { id, name -> runner.renameParty(nation.countryId, id, name) },
            )
        }

        GamePanel.DIPLOMACY -> {
            DiplomacyPanel(
                worldState,
                nation.society.politicalCapital,
                countryName,
                runner::diplomaticAction,
                runner::changeMembership,
                desk.diplomacy,
                desk.partner,
                onSelectCountry = { desk.partner = it },
            )
        }

        GamePanel.MILITARY -> {
            MilitaryPanel(
                desk.military,
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
            val pauseSettings by runner.pauseSettings.collectAsState()
            SituationPanel(
                worldState,
                countryName,
                pauseSettings,
                runner::setAutoPause,
                runner::renameRebels,
                section = desk.situation,
            )
        }

        GamePanel.SOCIETY -> {
            SocietyPanel(nation, desk.society)
        }

        GamePanel.STATISTICS -> {
            StatisticsPanel(playerHistory, nation.history)
        }
    }
}
