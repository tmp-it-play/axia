package team.themoment.axia.game.panels

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatCompactDollars
import team.themoment.axia.format.formatPercent
import team.themoment.axia.format.formatPopulation
import team.themoment.axia.simulation.WorldSnapshot
import team.themoment.axia.simulation.military.MilitaryAction
import team.themoment.axia.simulation.military.Mobilization
import team.themoment.axia.simulation.military.WarSnapshot
import team.themoment.axia.simulation.text.josa
import team.themoment.axia.simulation.world.Geography
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow
import kotlin.math.roundToInt

/** 군사 서류철. 우리 전력과 동원 태세, 우리가 관련된 전쟁, 군사 행위. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MilitaryPanel(
    world: WorldSnapshot,
    playerId: String,
    politicalCapital: Double,
    countryName: (String) -> String,
    onMobilize: (Mobilization) -> Unit,
    onAction: (String, MilitaryAction) -> Unit,
) {
    val military = world.military
    val forces = military.forces.getValue(playerId)
    SectionTitle("우리 군")
    StatRow("군사력 (세계 최강 = 100)", "%.1f".format(forces.power))
    forces.branches.forEach { (branch, value) -> StatRow("  ${branch.label}", "%.1f".format(value)) }
    StatRow("병력", formatPopulation(forces.personnel))
    StatRow("국방비 (연간)", formatCompactDollars(forces.spending))
    StatRow("준비태세", formatPercent(forces.readiness))
    if (forces.nuclearWarheads > 0) StatRow("핵탄두", "${forces.nuclearWarheads}기")
    val rank =
        military.forces.values
            .sortedByDescending { it.power }
            .indexOfFirst { it.countryId == playerId } + 1
    StatRow("세계 순위", "${rank}위")

    SectionTitle("동원 태세")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Mobilization.entries.forEach { level ->
            PaperTag(
                "${level.label} · 준비태세 ${(level.readiness * 100).roundToInt()}%",
                selected = level == military.mobilization,
                onClick = { onMobilize(level) },
            )
        }
    }
    Hint("동원 태세를 올리면 준비태세가 서서히 오르고, GDP 대비 추가 지출(부분 0.5%, 총동원 2%)이 듭니다.")
    if (military.aid.isNotEmpty()) {
        SectionTitle("군사 지원")
        military.aid.forEach { (id, until) -> StatRow(countryName(id), "${shortDate(until)}까지") }
    }

    val ours = military.wars.filter { it.ceasefire == null && (playerId in it.sideA || playerId in it.sideB) }
    SectionTitle("우리나라의 전쟁")
    if (ours.isEmpty()) Hint("치르고 있는 전쟁이 없습니다.")
    ours.forEach { WarCard(it, countryName, playerId) }

    SectionTitle("군사 행위")
    Hint("인접국, 관계가 -30 미만인 나라, 전쟁 중인 나라를 보여 줍니다. 선전포고는 인접국이거나 양쪽 모두 해안이 있어야 하고, 상대 동맹국이 참전하며 민주국가와의 관계가 나빠집니다.")
    val targets = world.militaryActions.entries.sortedBy { countryName(it.key) }
    val neighbors =
        Geography.default
            .of(playerId)
            .neighbors
            .toSet()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        targets
            .filter { (id, actions) ->
                actions.any { it != MilitaryAction.DECLARE_WAR } || id in neighbors || (world.diplomacy.relations[id] ?: 0.0) < HOSTILE
            }.forEach { (id, actions) ->
                Text(countryName(id), style = DossierType.title(13.sp), color = DossierColors.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    actions.forEach { action ->
                        PaperTag(
                            "${action.label} · ${action.cost.roundToInt()}",
                            enabled = politicalCapital >= action.cost,
                            onClick = { onAction(id, action) },
                        )
                    }
                }
            }
    }

    SectionTitle("세계 군사력 상위")
    military.forces.values.sortedByDescending { it.power }.take(TOP_POWERS).forEach {
        val nuclear = if (it.nuclearWarheads > 0) " · 핵 ${it.nuclearWarheads}" else ""
        StatRow(countryName(it.countryId), "%.1f$nuclear".format(it.power))
    }
}

/** 전쟁 하나. 교전국, 지원국, 전선 통제, 피로도. */
@Composable
internal fun WarCard(
    war: WarSnapshot,
    countryName: (String) -> String,
    playerId: String?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, DossierColors.paperLine)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(war.name, style = DossierType.title(14.sp), color = DossierColors.ink)
        Text(
            "${shortDate(war.started)} 개전" +
                (war.ceasefire?.let { " · ${shortDate(it)} 휴전" } ?: " · 강도 ${(war.intensity * 100).roundToInt()}"),
            style = DossierType.number(11.sp),
            color = DossierColors.inkMuted,
        )
        StatRow("한쪽", war.sideA.joinToString(", ", transform = countryName))
        StatRow("다른 쪽", war.sideB.joinToString(", ", transform = countryName))
        if (war.supportA.isNotEmpty() || war.supportB.isNotEmpty()) {
            Hint("지원: ${(war.supportA + war.supportB).distinct().take(8).joinToString(", ", transform = countryName)}")
        }
        war.fronts.forEach { front ->
            val holder = if (front.control >= 0) front.attacker else front.defender
            val held = if (front.control >= 0) front.defender else front.attacker
            val trend =
                if (front.trend > 0.0005) {
                    " ▲"
                } else if (front.trend < -0.0005) {
                    " ▼"
                } else {
                    ""
                }
            StatRow(
                "${countryName(front.attacker)} → ${countryName(front.defender)}",
                "${countryName(holder).josa("이", "가")} ${countryName(held)} ${formatPercent(kotlin.math.abs(front.control))} 통제$trend",
            )
        }
        war.weariness.entries.sortedByDescending { it.value }.take(4).forEach { (id, value) ->
            StatRow(
                "전쟁 피로 · ${countryName(id)}",
                value.roundToInt().toString(),
                valueColor =
                    if (id == playerId &&
                        value > 40
                    ) {
                        DossierColors.negative
                    } else {
                        DossierColors.ink
                    },
            )
        }
    }
}

private const val TOP_POWERS = 10
private const val HOSTILE = -30.0
