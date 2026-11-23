package team.themoment.axia.game.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import team.themoment.axia.simulation.nation.CountrySpecialSnapshot
import team.themoment.axia.simulation.nation.PolicyItem
import team.themoment.axia.simulation.nation.SocialSnapshot
import team.themoment.axia.simulation.politics.PoliticsSnapshot
import team.themoment.axia.ui.dossier.DossierSlider
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow
import kotlin.math.roundToInt

/** 예산이 아닌 정책(규제). 재정 탭과 같은 초안에 담아 한 법안으로 발의한다. */
@Composable
fun PolicyPanel(
    social: SocialSnapshot,
    special: CountrySpecialSnapshot,
    politics: PoliticsSnapshot,
    onEdit: (PolicyItem, Double) -> Unit,
    onClear: () -> Unit,
    onIntroduce: () -> Unit,
    onViewBill: () -> Unit,
) {
    val pending = politics.pending
    SectionTitle("사회 지표 (2024년 1월 = 100)")
    StatRow("오염 지수", social.pollution.roundToInt().toString())
    StatRow("범죄 지수", social.crime.roundToInt().toString())
    DraftControls(
        politics = politics,
        hint = "규제 강도를 조정해 재정 변경과 함께 발의하세요. 통과된 목표는 다음 달부터 6개월에 걸쳐 집행됩니다.",
        onIntroduce = onIntroduce,
        onViewBill = onViewBill,
        onClear = onClear,
    )
    SectionTitle("규제와 제도 (0 = 가장 약함, 100 = 가장 강함)")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        social.regulations.forEach { line ->
            val proposed = pending?.changes?.firstOrNull { it.item == line.item }?.to ?: politics.draft[line.item]?.to ?: line.targetLevel
            DossierSlider(
                enabled = pending == null,
                label = line.item.label,
                value = proposed.toFloat(),
                range = 0f..1f,
                valueText = "${(proposed * 100).roundToInt()} · ${if (proposed < 0.5) line.item.lowLabel else line.item.highLabel}",
                secondary =
                    "집행 ${(line.level * 100).roundToInt()} · 승인 ${(line.targetLevel * 100).roundToInt()}" +
                        (if (line.estimated) " · 시작값 추정" else "") +
                        " · 시작 ${(line.initialLevel * 100).roundToInt()}",
                onValueChange = { onEdit(line.item, (it * 100).roundToInt() / 100.0) },
            )
        }
    }
    if (special.policies.isNotEmpty()) {
        SectionTitle("이 나라 고유 정책")
        special.policies.forEach { line ->
            val proposed = pending?.changes?.firstOrNull { it.item == line.item }?.to ?: politics.draft[line.item]?.to ?: line.target
            DossierSlider(
                enabled = pending == null,
                label = line.item.label,
                value = proposed.toFloat(),
                range = 0f..1f,
                valueText = "${(proposed * 100).roundToInt()} · ${if (proposed < 0.5) line.item.lowLabel else line.item.highLabel}",
                secondary = "현재 ${(line.target * 100).roundToInt()} · 시작 ${(line.initial * 100).roundToInt()}",
                onValueChange = { onEdit(line.item, (it * 100).roundToInt() / 100.0) },
            )
            Hint(line.item.description)
        }
    }
}
