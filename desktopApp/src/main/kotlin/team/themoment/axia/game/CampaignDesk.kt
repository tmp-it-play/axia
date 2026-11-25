package team.themoment.axia.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import team.themoment.axia.game.panels.DiplomacySection
import team.themoment.axia.game.panels.OrganizationsTab
import team.themoment.axia.game.panels.SituationSection
import team.themoment.axia.game.panels.SocietyTab
import team.themoment.axia.simulation.nation.PolicyItem
import team.themoment.axia.ui.dossier.DossierIndex
import team.themoment.axia.ui.dossier.PaperTag

internal enum class PolicyPage(
    val label: String,
) {
    CURRENT("현행 정책"),
    EDIT("정책 입안"),
    REVIEW("초안 검토"),
    HEARING("법안 심의"),
}

internal enum class PoliticsPage(
    val label: String,
) {
    GOVERNMENT("정부"),
    CHAMBERS("의회"),
    ELECTIONS("선거"),
}

internal enum class BudgetPage(
    val label: String,
) {
    BALANCE("결산"),
    ACCOUNTS("세입·세출"),
    DEBT("국채"),
}

internal enum class MilitaryPage(
    val label: String,
) {
    FORCES("병력·예산"),
    WARS("전쟁·복구"),
    COUNTRIES("상대국"),
}

internal enum class EconomyPage(
    val label: String,
) {
    INDICATORS("거시 지표"),
    MONETARY("통화"),
    EXTERNAL("대외 거래"),
    INDUSTRY("산업·상품"),
}

/** 같은 캠페인에서 지도, 재정, 정치 화면이 같은 외교 상대와 법안 초안을 열도록 한다. */
internal class CampaignDesk {
    var panel by mutableStateOf<GamePanel?>(null)
    var policy by mutableStateOf(PolicyPage.CURRENT)
    var politics by mutableStateOf(PoliticsPage.GOVERNMENT)
    var diplomacy by mutableStateOf(DiplomacySection.OVERVIEW)
    var budget by mutableStateOf(BudgetPage.BALANCE)
    var military by mutableStateOf(MilitaryPage.FORCES)
    var economy by mutableStateOf(EconomyPage.INDICATORS)
    var draftItem by mutableStateOf<PolicyItem?>(null)
    var showLastDecision by mutableStateOf(false)
    var organizations by mutableStateOf(OrganizationsTab.LIST)
    var society by mutableStateOf(SocietyTab.POPULATION)
    var situation by mutableStateOf(SituationSection.CONFLICT)
    var partner by mutableStateOf<String?>(null)

    fun editDraft(item: PolicyItem?) {
        draftItem = item
        policy = PolicyPage.EDIT
        panel = GamePanel.POLICY
    }

    fun reviewDraft() {
        policy = PolicyPage.REVIEW
        panel = GamePanel.POLICY
    }

    fun viewBill() {
        showLastDecision = false
        policy = PolicyPage.HEARING
        panel = GamePanel.POLICY
    }

    fun viewLastDecision() {
        viewBill()
        showLastDecision = true
    }

    fun openPartner(id: String) {
        diplomacy = DiplomacySection.COUNTRIES
        partner = id
        panel = GamePanel.DIPLOMACY
    }
}

@Composable
internal fun CampaignDeskIndex(desk: CampaignDesk) {
    when (desk.panel) {
        GamePanel.POLICY -> {
            DossierIndex(PolicyPage.entries, desk.policy, PolicyPage::label) { page ->
                desk.policy = page
                if (page == PolicyPage.EDIT) desk.draftItem = null
                if (page == PolicyPage.HEARING) desk.showLastDecision = false
            }
        }

        GamePanel.POLITICS -> {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f)) {
                    DossierIndex(PoliticsPage.entries, desk.politics, PoliticsPage::label) { desk.politics = it }
                }
                PaperTag("법안 심의 열기", onClick = desk::viewBill)
            }
        }

        GamePanel.BUDGET -> {
            DossierIndex(BudgetPage.entries, desk.budget, BudgetPage::label) { desk.budget = it }
        }

        GamePanel.ECONOMY -> {
            DossierIndex(EconomyPage.entries, desk.economy, EconomyPage::label) { desk.economy = it }
        }

        GamePanel.MILITARY -> {
            DossierIndex(MilitaryPage.entries, desk.military, MilitaryPage::label) { desk.military = it }
        }

        GamePanel.DIPLOMACY -> {
            DossierIndex(DiplomacySection.entries, desk.diplomacy, DiplomacySection::label) {
                desk.diplomacy = it
                desk.partner = null
            }
        }

        GamePanel.ORGANIZATIONS -> {
            DossierIndex(OrganizationsTab.entries, desk.organizations, OrganizationsTab::label) { desk.organizations = it }
        }

        GamePanel.SOCIETY -> {
            DossierIndex(SocietyTab.entries, desk.society, SocietyTab::label) { desk.society = it }
        }

        GamePanel.SITUATION -> {
            DossierIndex(SituationSection.entries, desk.situation, SituationSection::label) { desk.situation = it }
        }

        GamePanel.OVERVIEW, GamePanel.STATISTICS, null -> {
            return
        }
    }
}
