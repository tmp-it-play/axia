package team.themoment.axia.game.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.simulation.diplomacy.BilateralSnapshot
import team.themoment.axia.simulation.diplomacy.DiplomaticAction
import team.themoment.axia.simulation.diplomacy.DiplomaticDispatch
import team.themoment.axia.simulation.diplomacy.DiplomaticOutcome
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow
import team.themoment.axia.ui.dossier.metricTooltip
import kotlin.math.ceil
import kotlin.math.roundToInt

private enum class ActionGroup(
    val label: String,
) {
    EXCHANGE("교류"),
    TREATY("조약"),
    PRESSURE("압박"),
}

private val DiplomaticAction.group: ActionGroup
    get() =
        when (this) {
            DiplomaticAction.SUMMIT, DiplomaticAction.AID -> {
                ActionGroup.EXCHANGE
            }

            DiplomaticAction.TRADE_AGREEMENT, DiplomaticAction.ALLIANCE, DiplomaticAction.TRANSIT_TREATY, DiplomaticAction.END_TRANSIT -> {
                ActionGroup.TREATY
            }

            DiplomaticAction.SANCTION, DiplomaticAction.LIFT_SANCTION, DiplomaticAction.CONDEMN, DiplomaticAction.EXPEL_DIPLOMATS -> {
                ActionGroup.PRESSURE
            }
        }

@Composable
internal fun DiplomaticPartner(
    partner: BilateralSnapshot,
    name: String,
    politicalCapital: Double,
    dispatch: DiplomaticDispatch?,
    onAction: (String, DiplomaticAction) -> Unit,
) {
    var group by remember(partner.countryId) { mutableStateOf(ActionGroup.EXCHANGE) }
    Column(Modifier.fillMaxWidth().relationTooltip(name)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(name, modifier = Modifier.weight(1f), style = DossierType.title(22.sp), color = DossierColors.ink)
            Text(
                "관계 ${partner.relation.roundToInt()} · ${relationLabel(partner.relation)}",
                style = DossierType.title(15.sp),
                color = relationColor(partner.relation),
            )
        }
        RelationScale(partner.relation)
    }
    SectionTitle("양국 현황")
    if (partner.atWar) StatRow("군사 상태", "교전 중", valueColor = DossierColors.negative)
    val agreements =
        listOfNotNull(
            (partner.alliance ?: "동맹").takeIf { partner.allied },
            "무역협정".takeIf { partner.tradeAgreement },
            "상호 통행권".takeIf { partner.transit },
        )
    StatRow("발효 중인 조약", agreements.joinToString(" · ").ifEmpty { "없음" })
    if (partner.sanctionedByUs > 0) StatRow("우리 제재", "강도 ${(partner.sanctionedByUs * 100).roundToInt()}")
    if (partner.sanctioningUs > 0) {
        StatRow("상대국 제재", "강도 ${(partner.sanctioningUs * 100).roundToInt()}", valueColor = DossierColors.negative)
    }
    if (partner.aidMonthsLeft > 0) StatRow("경제 원조", "${partner.aidMonthsLeft}회 지급 예정")
    dispatch?.takeIf { it.countryId == partner.countryId }?.let {
        val (result, ink) =
            when (it.outcome) {
                DiplomaticOutcome.COMPLETED -> "시행" to DossierColors.positive
                DiplomaticOutcome.DECLINED -> "상대국 거절 · 비용 환급" to DossierColors.negative
                DiplomaticOutcome.INSUFFICIENT_CAPITAL -> "정치 자본 부족" to DossierColors.negative
                DiplomaticOutcome.UNAVAILABLE -> "실행 조건 미충족" to DossierColors.negative
            }
        SectionTitle("최근 외교 행동 · ${shortDate(it.date)}")
        Text("${it.action.label} · $result", style = DossierType.title(13.sp), color = ink)
    }
    SectionTitle("외교 행동")
    StatRow("정치 자본", "${politicalCapital.toInt()} / 100")
    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionGroup.entries.forEach { choice ->
            PaperTag(choice.label, selected = group == choice, onClick = { group = choice })
        }
    }
    DiplomaticAction.entries.filter { it.group == group }.forEach { action ->
        val blocked =
            when {
                action !in partner.available -> unavailableReason(action, partner)
                politicalCapital < action.cost -> "정치 자본 ${ceil(action.cost - politicalCapital).toInt()}점 부족"
                else -> null
            }
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PaperTag(action.label, enabled = blocked == null, onClick = { onAction(partner.countryId, action) })
                Text(
                    "비용 ${action.cost.toInt()}점",
                    modifier = Modifier.metricTooltip("${action.label} · 정치 자본", "외교 행동에 드는 비용입니다. 상대국이 제안을 거절하면 돌려받습니다."),
                    style = DossierType.number(12.sp),
                    color = DossierColors.inkMuted,
                )
            }
            Hint(action.description)
            if (blocked != null) Text(blocked, style = DossierType.number(12.sp), color = DossierColors.negative)
        }
        HorizontalDivider(color = DossierColors.paperLine)
    }
}

/** 가용 여부는 스냅숏을 따르고, 실행할 수 없는 행동에는 현재 양국 상태에 맞는 이유를 붙인다. */
private fun unavailableReason(
    action: DiplomaticAction,
    partner: BilateralSnapshot,
): String =
    when (action) {
        DiplomaticAction.SUMMIT -> {
            if (partner.atWar) "교전 중에는 개최할 수 없습니다." else "다음 정상회담 가능일 · ${shortDate(requireNotNull(partner.nextSummitDate))}"
        }

        DiplomaticAction.AID -> {
            if (partner.atWar) "교전 중에는 원조할 수 없습니다." else "이미 원조금을 지급하고 있습니다."
        }

        DiplomaticAction.TRADE_AGREEMENT, DiplomaticAction.ALLIANCE, DiplomaticAction.TRANSIT_TREATY -> {
            if (partner.atWar) "교전 중에는 체결할 수 없습니다." else "이미 발효 중입니다."
        }

        DiplomaticAction.END_TRANSIT -> {
            "종료할 통행권이 없습니다."
        }

        DiplomaticAction.SANCTION -> {
            "최대 강도의 제재가 발효 중입니다."
        }

        DiplomaticAction.LIFT_SANCTION -> {
            "해제할 제재가 없습니다."
        }

        DiplomaticAction.CONDEMN, DiplomaticAction.EXPEL_DIPLOMATS -> {
            "실행 조건을 충족하지 못했습니다."
        }
    }

internal fun Modifier.relationTooltip(name: String): Modifier =
    metricTooltip(
        "$name · 외교 관계",
        "−100점에서 +100점 사이로 나타냅니다. 양국 관계가 좋을수록 무역협정과 통행권 협정을 체결하기 쉽습니다.",
    )

/** 가운데 눈금이 중립을 나타내는 -100~100 관계 표시. */
@Composable
private fun RelationScale(relation: Double) {
    val color = relationColor(relation)
    Canvas(Modifier.fillMaxWidth().padding(vertical = 5.dp).height(8.dp)) {
        val middle = size.width / 2f
        val y = size.height / 2f
        val position = middle + middle * relation.coerceIn(-100.0, 100.0).toFloat() / 100f
        drawLine(DossierColors.paperLine, Offset(0f, y), Offset(size.width, y), 4.dp.toPx())
        drawLine(color, Offset(middle, y), Offset(position, y), 4.dp.toPx())
        drawLine(DossierColors.folderEdge, Offset(middle, 0f), Offset(middle, size.height), 1.dp.toPx())
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(position, y))
    }
}
