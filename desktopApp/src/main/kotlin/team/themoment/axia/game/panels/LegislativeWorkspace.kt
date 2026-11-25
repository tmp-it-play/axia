package team.themoment.axia.game.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import team.themoment.axia.game.CampaignDesk
import team.themoment.axia.game.PolicyPage
import team.themoment.axia.simulation.economy.MonetaryPolicy
import team.themoment.axia.simulation.nation.NationSnapshot
import team.themoment.axia.simulation.nation.PolicyItem
import team.themoment.axia.simulation.politics.PoliticsSnapshot
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow
import kotlin.math.roundToInt

@Composable
internal fun LegislativeWorkspace(
    nation: NationSnapshot,
    monetary: MonetaryPolicy,
    politics: PoliticsSnapshot,
    desk: CampaignDesk,
    onEdit: (PolicyItem, Double) -> Unit,
    onClear: () -> Unit,
    onIntroduce: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val register = policyRegister(nation, monetary)
    val selected = desk.draftItem
    val options = register.getValue(selected?.group ?: PolicyGroup.REGULATIONS)
    val option = options.firstOrNull { it.item == selected } ?: options.first()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val stacked = maxWidth < 720.dp
        val wide = maxWidth >= 1000.dp
        val bill: @Composable () -> Unit = {
            BillFolio(politics, desk::editDraft, desk::reviewDraft, desk::viewBill, desk.policy, onClear, onIntroduce)
        }
        val summary: @Composable () -> Unit = {
            bill()
            Spacer(Modifier.height(14.dp))
            VoteForecast(politics, details = false, desk::viewBill)
        }
        when (desk.policy) {
            PolicyPage.CURRENT -> {
                val registerPage: @Composable () -> Unit = {
                    LedgerHeading("분야별 정책")
                    PolicyCatalog(register, politics, wide, desk::editDraft)
                }
                val side: @Composable () -> Unit = {
                    summary()
                    SectionTitle("사회 지표 (2024년 1월 = 100)")
                    StatRow(
                        "오염 지수",
                        nation.social.pollution
                            .roundToInt()
                            .toString(),
                    )
                    StatRow(
                        "범죄 지수",
                        nation.social.crime
                            .roundToInt()
                            .toString(),
                    )
                    politics.lastDecision?.let { last ->
                        SectionTitle("최근 법안")
                        StatRow(shortDate(last.decisionDate), last.stage.label)
                        PaperTag("심의 결과 보기", onClick = desk::viewLastDecision)
                    }
                }
                LedgerSpread(stacked, 1.85f, registerPage, side)
            }

            PolicyPage.EDIT -> {
                val index: @Composable () -> Unit = {
                    LedgerHeading("조항 선택")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        register.forEach { (group, choices) ->
                            PaperTag(group.label, selected = group == option.item.group, onClick = { desk.editDraft(choices.first().item) })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    LawRegisterGroup(option.item.group, options, politics, option.item, desk::editDraft)
                }
                val editor: @Composable () -> Unit = { PolicyEditor(option, monetary, politics, onEdit) }
                if (wide) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        LedgerColumn(Modifier.weight(0.86f)) { index() }
                        PageRule()
                        key(option.item) { LedgerColumn(Modifier.weight(1.22f)) { editor() } }
                        PageRule()
                        LedgerColumn(Modifier.weight(1f)) { summary() }
                    }
                } else {
                    LedgerSpread(stacked, 0.86f, index) {
                        editor()
                        Spacer(Modifier.height(25.dp))
                        summary()
                    }
                }
            }

            PolicyPage.REVIEW -> {
                LedgerSpread(stacked, 1.25f, bill) { VoteForecast(politics, details = true, desk::viewBill) }
            }

            PolicyPage.HEARING -> {
                return@BoxWithConstraints
            }
        }
    }
}

@Composable
private fun PolicyCatalog(
    register: Map<PolicyGroup, List<DraftOption>>,
    politics: PoliticsSnapshot,
    twoColumns: Boolean,
    onSelect: (PolicyItem) -> Unit,
) {
    val section: @Composable (PolicyGroup) -> Unit = { group ->
        register[group]?.let { LawRegisterGroup(group, it, politics, null, onSelect) }
    }
    if (twoColumns) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
            Column(Modifier.weight(1f)) {
                section(PolicyGroup.REGULATIONS)
                section(PolicyGroup.PROGRAMS)
                section(PolicyGroup.INSTITUTIONS)
            }
            Column(Modifier.weight(1f)) {
                section(PolicyGroup.TAXES)
                section(PolicyGroup.SPENDING)
            }
        }
    } else {
        register.keys.forEach { section(it) }
    }
}
