package team.themoment.axia.game.panels

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatPercent
import team.themoment.axia.simulation.society.CivilSocietySnapshot
import team.themoment.axia.simulation.society.GovernmentAction
import team.themoment.axia.simulation.society.NewsItem
import team.themoment.axia.simulation.society.OrganizationOrigin
import team.themoment.axia.simulation.society.OrganizationSnapshot
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.Stamp
import team.themoment.axia.ui.dossier.StatRow
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val NEWS_DATE = DateTimeFormatter.ofPattern("yyyy.M")

/** 조직 서류철. 조직을 정부 연합에 넣고 빼는 조작은 없고, 대화·약속·지원·압박 같은 행위만 할 수 있다. */
@Composable
fun OrganizationsPanel(
    society: CivilSocietySnapshot,
    onAct: (Int, GovernmentAction) -> Unit,
    onRename: (Int, String) -> Unit,
) {
    SectionTitle("정치 자본과 사회 분위기")
    StatRow("정치 자본", "${society.politicalCapital.roundToInt()} / 100")
    Hint("지지율이 높고 행정부 재량이 클수록 매달 더 쌓입니다. 대화, 수사, 해산 같은 행위에 씁니다.")
    StatRow(
        "사회 불안 (지지율 감점)",
        "-${"%.1f".format(society.unrest)}점",
        valueColor =
            if (society.unrest >
                1.0
            ) {
                DossierColors.negative
            } else {
                DossierColors.ink
            },
    )
    if (society.organizations.any { it.kind.state }) {
        StatRow(
            "쿠데타 위험",
            "${(society.coupRisk * 100).roundToInt()}%",
            valueColor = if (society.coupRisk > 0.2) DossierColors.negative else DossierColors.ink,
        )
    }
    SectionTitle("조직 ${society.organizations.size}곳")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        society.organizations.sortedByDescending { it.influence }.forEach { OrganizationCard(it, onAct, onRename) }
    }
    SectionTitle("소식")
    if (society.news.isEmpty()) Hint("아직 소식이 없습니다.")
    society.news.forEach { NewsLine(it) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrganizationCard(
    org: OrganizationSnapshot,
    onAct: (Int, GovernmentAction) -> Unit,
    onRename: (Int, String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, DossierColors.paperLine)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(org.name, style = DossierType.title(14.sp), color = DossierColors.ink, modifier = Modifier.weight(1f))
            Stamp(org.kind.label, color = if (org.kind.state) DossierColors.negative else DossierColors.stamp)
            if (org.origin != OrganizationOrigin.REAL) Stamp(org.origin.label, color = DossierColors.blueInk)
        }
        StatRow("정부에 대한 태도", "${org.stance.roundToInt()}", valueColor = stanceColor(org.stance))
        if (!org.kind.state) StatRow("회원 (성인 대비)", formatPercent(org.membership))
        StatRow("조직력 · 과격성", "${(org.capacity * 100).roundToInt()} · ${(org.radicalism * 100).roundToInt()}")
        StatRow("영향력", "%.1f".format(org.influence))
        org.promise?.let { StatRow("약속", "${it.item.label} ${if (it.direction > 0) "낮추지 않음" else "올리지 않음"} · ${it.until}까지") }
        if (org.subsidized) StatRow("보조금", "지원 중")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            GovernmentAction.entries.forEach { action ->
                val label =
                    when {
                        action == GovernmentAction.SUBSIDY && org.subsidized -> "보조금 중단"
                        action.cost > 0.0 -> "${action.label} · ${action.cost.roundToInt()}"
                        else -> action.label
                    }
                PaperTag(label, enabled = action in org.availableActions, onClick = { onAct(org.id, action) })
            }
        }
        RenameControl(org.id, org.name) { onRename(org.id, it) }
        org.recent.forEach { NewsLine(it) }
    }
}

@Composable
private fun NewsLine(item: NewsItem) {
    Text(
        text = "${NEWS_DATE.format(item.date)} · ${item.text}",
        style = DossierType.number(11.sp),
        color = if (item.major) DossierColors.ink else DossierColors.inkMuted,
    )
}

private fun stanceColor(stance: Double) =
    when {
        stance >= 20.0 -> DossierColors.positive
        stance <= -30.0 -> DossierColors.negative
        else -> DossierColors.ink
    }
