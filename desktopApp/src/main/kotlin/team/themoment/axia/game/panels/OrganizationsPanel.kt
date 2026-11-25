package team.themoment.axia.game.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import team.themoment.axia.ui.dossier.StatRow
import team.themoment.axia.ui.dossier.metricTooltip
import kotlin.math.roundToInt

internal enum class OrganizationsTab(
    val label: String,
) {
    LIST("조직 목록"),
    NEWS("소식"),
}

@Composable
internal fun OrganizationsPanel(
    society: CivilSocietySnapshot,
    tab: OrganizationsTab,
    onAct: (Int, GovernmentAction) -> Unit,
    onRename: (Int, String) -> Unit,
) {
    val ranked = remember(society.organizations) { society.organizations.sortedByDescending { it.influence } }
    var selectedId by remember { mutableStateOf(ranked.firstOrNull()?.id) }
    val selected = ranked.firstOrNull { it.id == selectedId } ?: ranked.firstOrNull()
    LaunchedEffect(selected?.id) { selectedId = selected?.id }
    key(tab) {
        when (tab) {
            OrganizationsTab.LIST -> {
                SocietyRegister(
                    backLabel = "조직 목록으로",
                    list = { openDetail ->
                        LedgerHeading("조직 ${ranked.size}곳")
                        if (ranked.isEmpty()) {
                            Hint("등록된 조직이 없습니다.")
                        } else {
                            SocietyRegisterHeader("조직명", "영향력", "태도")
                            Column(Modifier.selectableGroup()) {
                                ranked.forEach { org ->
                                    SocietyRegisterRow(
                                        org.name,
                                        "%.1f".format(org.influence),
                                        "%+d".format(org.stance.roundToInt()),
                                        stanceColor(org.stance),
                                        org.id == selected?.id,
                                        tooltip = "정치적 영향력은 조직 유형과 조직력, 회원 규모에 따라 달라집니다. 태도가 양수이면 정부를 지지합니다.",
                                    ) {
                                        selectedId = org.id
                                        openDetail()
                                    }
                                }
                            }
                        }
                    },
                    detail = {
                        if (selected != null) {
                            OrganizationDetail(selected, society.politicalCapital, onAct, onRename)
                        } else {
                            SocietyConditions(society)
                        }
                    },
                )
            }

            OrganizationsTab.NEWS -> {
                PanelColumns(
                    left = {
                        LedgerHeading("조직 소식")
                        if (society.news.isEmpty()) Hint("아직 소식이 없습니다.")
                        society.news.forEach { NewsLine(it) }
                    },
                    right = { SocietyConditions(society) },
                )
            }
        }
    }
}

@Composable
private fun SocietyConditions(society: CivilSocietySnapshot) {
    LedgerHeading("사회 분위기")
    StatRow("정치 자본", "${society.politicalCapital.toInt()} / 100")
    Hint("지지율이 높고 행정부 재량이 클수록 매달 더 쌓입니다.")
    StatRow(
        "사회 불안에 따른 지지율 감점",
        "%.1f점".format(society.unrest),
        valueColor = if (society.unrest > 1.0) DossierColors.negative else DossierColors.ink,
    )
    if (society.organizations.any { it.kind.state }) {
        StatRow(
            "쿠데타 위험",
            formatPercent(society.coupRisk),
            valueColor = if (society.coupRisk > 0.2) DossierColors.negative else DossierColors.ink,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrganizationDetail(
    org: OrganizationSnapshot,
    capital: Double,
    onAct: (Int, GovernmentAction) -> Unit,
    onRename: (Int, String) -> Unit,
) {
    LedgerHeading(org.name)
    StatRow("유형", if (org.origin == OrganizationOrigin.REAL) org.kind.label else "${org.kind.label} · ${org.origin.label}")
    StatRow(
        "정부에 대한 태도",
        "%+d".format(org.stance.roundToInt()),
        valueColor = stanceColor(org.stance),
        tooltip = "−100에 가까울수록 정부에 반대하고 +100에 가까울수록 지지합니다.",
    )
    if (!org.kind.state) StatRow("회원 비중", formatPercent(org.membership), note = "성인 대비")
    StatRow("조직력 · 과격성", "${(org.capacity * 100).roundToInt()} · ${(org.radicalism * 100).roundToInt()}")
    org.promise?.let { promise ->
        val limit = if (promise.direction > 0) "낮추지 않음" else "올리지 않음"
        StatRow("정부의 약속", "${promise.item.label} $limit", note = "${shortDate(promise.until)}까지")
    }
    if (org.subsidized) StatRow("보조금", "지원 중")
    SectionTitle("정부 행동")
    StatRow("보유 정치 자본", "${capital.toInt()} / 100")
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        GovernmentAction.entries.forEach { action ->
            val label =
                when {
                    action == GovernmentAction.SUBSIDY && org.subsidized -> "보조금 중단"
                    action.cost > 0.0 -> "${action.label} · ${action.cost.toInt()}"
                    else -> action.label
                }
            val modifier =
                if (action.cost > 0.0) {
                    val detail = if (capital < action.cost) "이 행동에 필요한 정치 자본이 부족합니다." else "이 행동을 실행하면 정치 자본을 씁니다."
                    Modifier.metricTooltip(action.label, detail)
                } else {
                    Modifier
                }
            PaperTag(label, modifier, enabled = action in org.availableActions, onClick = { onAct(org.id, action) })
        }
    }
    if (GovernmentAction.DIALOGUE !in org.availableActions && capital >= GovernmentAction.DIALOGUE.cost) {
        StatRow("다음 대화 가능일", shortDate(org.dialogueReadyOn))
    }
    RenameControl(org.id, org.name) { onRename(org.id, it) }
    if (org.recent.isNotEmpty()) {
        SectionTitle("최근 소식")
        org.recent.forEach { NewsLine(it) }
    }
}

@Composable
private fun NewsLine(item: NewsItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(shortDate(item.date), Modifier.width(82.dp), style = DossierType.number(12.sp), color = DossierColors.inkMuted)
        Text(
            item.text,
            Modifier.weight(1f),
            style = DossierType.number(13.sp),
            color = if (item.major) DossierColors.negative else DossierColors.ink,
        )
    }
    HorizontalDivider(color = DossierColors.folderEdge.copy(alpha = 0.33f))
}

private fun stanceColor(stance: Double) =
    when {
        stance >= 20.0 -> DossierColors.positive
        stance <= -30.0 -> DossierColors.negative
        else -> DossierColors.ink
    }
