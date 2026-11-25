package team.themoment.axia.game.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatRate
import team.themoment.axia.game.ConsoleGlyph
import team.themoment.axia.game.ConsoleSymbol
import team.themoment.axia.simulation.economy.MonetaryPolicy
import team.themoment.axia.simulation.nation.BudgetItem
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.nation.InstitutionItem
import team.themoment.axia.simulation.nation.NationSnapshot
import team.themoment.axia.simulation.nation.PolicyItem
import team.themoment.axia.simulation.nation.RegulationItem
import team.themoment.axia.simulation.nation.SpendingKind
import team.themoment.axia.simulation.nation.TaxKind
import team.themoment.axia.simulation.politics.PoliticsSnapshot
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.metricTooltip
import kotlin.math.roundToInt

internal enum class PolicyGroup(
    val label: String,
    val symbol: ConsoleSymbol,
) {
    REGULATIONS("사회 규제", ConsoleSymbol.CITIZENS),
    PROGRAMS("국가 정책", ConsoleSymbol.DECREE),
    TAXES("세입", ConsoleSymbol.VAULT),
    SPENDING("세출", ConsoleSymbol.SCALES),
    INSTITUTIONS("통화 제도", ConsoleSymbol.INTEREST),
}

internal val PolicyItem.group: PolicyGroup
    get() =
        when (this) {
            is RegulationItem -> PolicyGroup.REGULATIONS
            is CountryPolicyItem -> PolicyGroup.PROGRAMS
            is TaxKind -> PolicyGroup.TAXES
            is SpendingKind -> PolicyGroup.SPENDING
            is InstitutionItem -> PolicyGroup.INSTITUTIONS
        }

internal data class DraftOption(
    val item: PolicyItem,
    val target: Double,
    val inForce: Double? = null,
    val initial: Double? = null,
)

internal fun policyRegister(
    nation: NationSnapshot,
    monetary: MonetaryPolicy,
): Map<PolicyGroup, List<DraftOption>> =
    buildList {
        nation.social.regulations.forEach { add(DraftOption(it.item, it.targetLevel, it.level, it.initialLevel)) }
        nation.special.policies.forEach { add(DraftOption(it.item, it.target, initial = it.initial)) }
        nation.budget.taxes.forEach { add(DraftOption(it.kind, it.targetRate, it.rate, it.initialRate)) }
        nation.budget.spending.forEach { add(DraftOption(it.kind, it.targetLevel, it.level, it.initialLevel)) }
        add(DraftOption(InstitutionItem.CENTRAL_BANK_INDEPENDENCE, if (monetary.independent) 1.0 else 0.0))
    }.groupBy { it.item.group }

internal fun PolicyItem.printLevel(level: Double): String =
    when (this) {
        is BudgetItem -> formatRate(level)
        is InstitutionItem -> if (level >= 0.5) "독립" else "정부 지시"
        is CountryPolicyItem -> if (binary) (if (level >= 0.5) highLabel else lowLabel) else "${(level * 100).roundToInt()} / 100"
        is RegulationItem -> "${(level * 100).roundToInt()} / 100"
    }

internal val PolicyItem.levelDescription: String
    get() =
        when (this) {
            is TaxKind -> "과세 대상 금액에서 실제 세금으로 걷는 비율입니다."
            is SpendingKind -> "연간 GDP에서 이 분야 지출이 차지하는 비율입니다."
            is RegulationItem -> "0점은 ‘$lowLabel’, 100점은 ‘$highLabel’입니다."
            is CountryPolicyItem -> if (binary) description else "0점은 ‘$lowLabel’, 100점은 ‘$highLabel’입니다."
            is InstitutionItem -> "중앙은행이 독립적으로 금리를 결정할지 정합니다."
        }

@Composable
internal fun LawRegisterGroup(
    group: PolicyGroup,
    options: List<DraftOption>,
    politics: PoliticsSnapshot,
    selected: PolicyItem?,
    onSelect: (PolicyItem) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(DossierColors.blueInk).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConsoleGlyph(group.symbol, Modifier.size(21.dp), DossierColors.paper)
        Text(group.label, Modifier.weight(1f), style = DossierType.title(16.sp), color = DossierColors.paper)
        Text("${options.size}개 조항", style = DossierType.number(12.sp), color = DossierColors.paper)
    }
    options.forEach { option ->
        val item = option.item
        val active = selected == item
        val change = politics.pending?.changes?.firstOrNull { it.item == item } ?: politics.draft[item]
        val current = item.printLevel(option.target)
        val proposal = change?.let { (if (politics.pending == null) "초안" else "심의안") + " " + item.printLevel(it.to) }
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (active) DossierColors.paperLine else DossierColors.paper.copy(alpha = 0.35f))
                .metricTooltip(item.label, item.levelDescription)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(role = Role.Button, onClick = { onSelect(item) })
                .heightIn(min = 36.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(item.label, Modifier.weight(1f), style = DossierType.title(14.sp), color = DossierColors.ink)
            Column(horizontalAlignment = Alignment.End) {
                Text(current, style = DossierType.number(13.sp), color = DossierColors.ink)
                if (proposal != null) {
                    Text(proposal, style = DossierType.number(12.sp), color = DossierColors.blueInk)
                }
            }
        }
        HorizontalDivider(color = DossierColors.folderEdge.copy(alpha = 0.35f))
    }
    Spacer(Modifier.height(14.dp))
}
