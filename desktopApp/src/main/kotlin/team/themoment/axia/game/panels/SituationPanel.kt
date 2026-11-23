package team.themoment.axia.game.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import team.themoment.axia.format.formatPercent
import team.themoment.axia.simulation.WorldSnapshot
import team.themoment.axia.simulation.events.EventCategory
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow
import kotlin.math.roundToInt

/**
 * 상황 화면. 세계에서 진행 중인 큰 흐름: 전쟁, 내전, 영토 분쟁, 장기 추세, 세계 소식, 최근 사건.
 * 자동 일시정지 설정도 여기서 바꾼다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SituationPanel(
    world: WorldSnapshot,
    countryName: (String) -> String,
    pauseSettings: Set<EventCategory>,
    onToggleAutoPause: (EventCategory, Boolean) -> Unit,
) {
    val military = world.military
    val active = military.wars.filter { it.ceasefire == null }
    SectionTitle("진행 중인 전쟁 ${active.size}건")
    if (active.isEmpty()) Hint("진행 중인 국가 간 전쟁이 없습니다.")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { active.forEach { WarCard(it, countryName, null) } }
    val ended = military.wars.filter { it.ceasefire != null }
    if (ended.isNotEmpty()) {
        var showEnded by remember { mutableStateOf(false) }
        PaperTag(if (showEnded) "휴전한 전쟁 접기" else "휴전한 전쟁 ${ended.size}건 보기", onClick = { showEnded = !showEnded })
        if (showEnded) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { ended.forEach { WarCard(it, countryName, null) } }
    }

    SectionTitle("내전")
    military.civilWars.filter { it.ended == null }.forEach {
        StatRow("${countryName(it.country)} · ${it.rebels}", "반군 통제 ${formatPercent(it.rebelControl)}")
    }
    military.civilWars.filter { it.ended != null }.forEach {
        StatRow(
            "${countryName(it.country)} · ${it.rebels}",
            "${shortDate(requireNotNull(it.ended))} 종결",
            valueColor = DossierColors.inkMuted,
        )
    }

    SectionTitle("영토 분쟁")
    military.disputes.forEach { dispute ->
        StatRow(
            dispute.name,
            "실효 지배 ${countryName(dispute.controller)} · 긴장 ${dispute.tension.roundToInt()}",
            valueColor = if (dispute.tension >= 60) DossierColors.negative else DossierColors.ink,
        )
        Hint("주장: ${dispute.claimants.joinToString(", ", transform = countryName)}")
    }

    SectionTitle("장기 추세")
    val trends = world.trends
    StatRow("지구 평균기온 (산업화 이전 대비)", "+%.2f°C".format(trends.temperature))
    StatRow("세계 온실가스 배출 (2024년 = 100)", trends.emissions.roundToInt().toString())
    StatRow("기술 수준 (2024년 = 100)", trends.technology.roundToInt().toString())
    StatRow(
        "국제 유가 (2023년 = 100)",
        world.trade.oilPrice
            .roundToInt()
            .toString(),
    )
    Hint("기온이 오를수록 홍수·가뭄·폭염·산불·폭풍이 잦아집니다.")

    SectionTitle("세계 소식")
    if (world.news.isEmpty()) Hint("아직 소식이 없습니다.")
    world.news.take(NEWS_SHOWN).forEach { WorldNewsLine(it, countryName) }

    SectionTitle("자동 일시정지")
    Hint("선거와 결정이 필요한 사건은 항상 멈춥니다. 우리나라에서 아래 분류의 사건이 일어나면 멈출지 고르세요.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        EventCategory.entries.forEach { category ->
            val on = category in pauseSettings
            PaperTag(category.label, selected = on, onClick = { onToggleAutoPause(category, !on) })
        }
    }
}

private const val NEWS_SHOWN = 40
