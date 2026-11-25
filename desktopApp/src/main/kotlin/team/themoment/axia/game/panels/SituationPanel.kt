package team.themoment.axia.game.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatPercent
import team.themoment.axia.format.formatPopulation
import team.themoment.axia.simulation.WorldSnapshot
import team.themoment.axia.simulation.events.EventCategory
import team.themoment.axia.simulation.events.GameEvent
import team.themoment.axia.simulation.military.MilitarySnapshot
import team.themoment.axia.simulation.military.WarRecoverySnapshot
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow
import kotlin.math.roundToInt

internal enum class SituationSection(
    val label: String,
) {
    CONFLICT("전쟁·분쟁"),
    NEWS("세계 소식"),
    TRENDS("장기 추세"),
    PAUSE("자동 일시정지"),
}

@Composable
internal fun SituationPanel(
    world: WorldSnapshot,
    countryName: (String) -> String,
    pauseSettings: Set<EventCategory>,
    onToggleAutoPause: (EventCategory, Boolean) -> Unit,
    onRenameRebels: (Int, String) -> Unit,
    section: SituationSection,
) {
    key(section) {
        when (section) {
            SituationSection.CONFLICT -> WorldConflicts(world.military, countryName, onRenameRebels)
            SituationSection.NEWS -> WorldBulletins(world, countryName)
            SituationSection.TRENDS -> WorldTrends(world)
            SituationSection.PAUSE -> PausePreferences(pauseSettings, onToggleAutoPause)
        }
    }
}

@Composable
private fun WorldConflicts(
    military: MilitarySnapshot,
    countryName: (String) -> String,
    onRenameRebels: (Int, String) -> Unit,
) {
    PanelColumns(
        left = {
            val (active, ended) = military.wars.partition { it.ceasefire == null }
            LedgerHeading("진행 중인 전쟁 · ${active.size}건")
            if (active.isEmpty()) Hint("진행 중인 국가 간 전쟁이 없습니다.")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { active.forEach { WarCard(it, countryName, null) } }
            if (ended.isNotEmpty()) {
                var showEnded by remember { mutableStateOf(false) }
                PaperTag(if (showEnded) "휴전 기록 접기" else "휴전 기록 ${ended.size}건", onClick = { showEnded = !showEnded })
                if (showEnded) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { ended.forEach { WarCard(it, countryName, null) } }
                }
            }
            SectionTitle("내전")
            if (military.civilWars.isEmpty()) Hint("기록된 내전이 없습니다.")
            military.civilWars.forEach { war ->
                key(war.country, war.rebels) {
                    val endedOn = war.ended
                    val label = "${countryName(war.country)} · ${war.rebels}"
                    if (endedOn == null) {
                        StatRow(label, "반군 통제 ${formatPercent(war.rebelControl)}")
                        war.generatedId?.let { id ->
                            val names = military.civilWars.filter { it.country == war.country }.map { it.rebels }
                            RenameControl(id, war.rebels, names) { onRenameRebels(id, it) }
                        }
                    } else {
                        StatRow(label, "${shortDate(endedOn)} 종결", valueColor = DossierColors.inkMuted)
                    }
                }
            }
        },
        right = {
            LedgerHeading("영토 분쟁 · ${military.disputes.size}곳")
            if (military.disputes.isEmpty()) Hint("영토 분쟁이 없습니다.")
            military.disputes.sortedByDescending { it.tension }.forEach { dispute ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(dispute.name, Modifier.weight(1f), style = DossierType.title(15.sp), color = DossierColors.ink)
                    Text(
                        "긴장 ${dispute.tension.roundToInt()}",
                        style = DossierType.number(13.sp),
                        color = if (dispute.tension >= 60) DossierColors.negative else DossierColors.inkMuted,
                    )
                }
                Hint("실효 지배 ${countryName(dispute.controller)} · 영유권 주장 ${dispute.claimants.joinToString(", ", transform = countryName)}")
                HorizontalDivider(Modifier.padding(top = 6.dp), color = DossierColors.paperLine)
            }
            RecoveryRoutes(military.recovery, countryName)
        },
    )
}

@Composable
private fun RecoveryRoutes(
    recovery: WarRecoverySnapshot,
    countryName: (String) -> String,
) {
    if (recovery.refugees.isEmpty() && recovery.returned.isEmpty() && recovery.reconstructionShares.isEmpty()) return
    SectionTitle("피란과 전후 복구")
    var allRoutes by remember { mutableStateOf(false) }
    val routes = recovery.refugees.sortedByDescending { it.people }
    StatRow("국외 피란 인구", formatPopulation(routes.sumOf { it.people }), note = "게임 시작 이후")
    (if (allRoutes) routes else routes.take(5)).forEach { route ->
        StatRow("${countryName(route.origin)} → ${countryName(route.host)}", formatPopulation(route.people))
    }
    if (routes.size > 5) PaperTag(if (allRoutes) "경로 접기" else "전체 피란 경로", onClick = { allRoutes = !allRoutes })
    recovery.returned.forEach { (id, count) -> StatRow("${countryName(id)} 귀환", formatPopulation(count)) }
    recovery.reconstructionShares.forEach { (id, share) -> StatRow("${countryName(id)} 재건 지출", "연율 GDP의 ${formatPercent(share)}") }
}

@Composable
private fun WorldBulletins(
    world: WorldSnapshot,
    countryName: (String) -> String,
) {
    PanelColumns(
        left = {
            LedgerHeading("국제 소식")
            if (world.news.isEmpty()) Hint("아직 들어온 소식이 없습니다.")
            var visibleCount by remember { mutableIntStateOf(NEWS_PER_PAGE) }
            world.news.take(visibleCount).groupBy { it.date }.forEach { (date, news) ->
                SectionTitle(shortDate(date))
                news.forEach {
                    Hint("${countryName(it.countryId)} · ${it.source}")
                    Text(it.text, style = DossierType.number(14.sp), color = DossierColors.ink)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = DossierColors.paperLine)
                }
            }
            if (visibleCount < world.news.size) {
                PaperTag("이전 소식 더 보기", onClick = { visibleCount += NEWS_PER_PAGE })
            }
        },
        right = {
            LedgerHeading("사건 기록")
            var playerOnly by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PaperTag("전체", selected = !playerOnly, onClick = { playerOnly = false })
                PaperTag("우리나라", selected = playerOnly, onClick = { playerOnly = true })
            }
            val events = world.events.filter { !playerOnly || it.countryId == world.diplomacy.playerId }
            if (events.isEmpty()) Hint("기록된 사건이 없습니다.")
            var openId by remember { mutableStateOf<Int?>(null) }
            events.forEach { event ->
                EventHistoryRow(event, countryName(event.countryId), event.id == openId) {
                    openId = if (openId == event.id) null else event.id
                }
            }
        },
    )
}

@Composable
private fun EventHistoryRow(
    event: GameEvent,
    country: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Hint("${shortDate(event.date)} · $country · ${event.category.label}")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(event.name, Modifier.weight(1f), style = DossierType.title(15.sp), color = DossierColors.ink)
            Text(if (expanded) "▾" else "›", style = DossierType.number(15.sp), color = DossierColors.blueInk)
        }
        if (expanded) {
            Text(event.text, style = DossierType.number(14.sp), color = DossierColors.ink)
            event.chosen?.let { choice -> event.choices.getOrNull(choice)?.let { Hint("결정 · ${it.label}") } }
        }
    }
    HorizontalDivider(color = DossierColors.paperLine)
}

@Composable
private fun WorldTrends(world: WorldSnapshot) {
    PanelColumns(
        left = {
            LedgerHeading("기후와 환경")
            SectionTitle("지구 평균기온")
            StatRow("산업화 이전 대비", "%+.2f°C".format(world.trends.temperature))
            Hint("기온이 오를수록 홍수·가뭄·폭염·산불·폭풍이 잦아집니다.")
            SectionTitle("온실가스 배출")
            StatRow(
                "세계 배출 지수",
                world.trends.emissions
                    .roundToInt()
                    .toString(),
                note = "2024년 = 100",
            )
        },
        right = {
            LedgerHeading("기술과 자원")
            SectionTitle("기술 수준")
            StatRow(
                "기술 수준 지수",
                world.trends.technology
                    .roundToInt()
                    .toString(),
                note = "2024년 = 100",
            )
            SectionTitle("국제 유가")
            StatRow(
                "원유 가격 지수",
                world.trade.oilPrice
                    .roundToInt()
                    .toString(),
                note = "2024년 1월 = 100",
            )
        },
    )
}

private val societyEvents =
    setOf(
        EventCategory.DISASTER,
        EventCategory.ACCIDENT,
        EventCategory.EPIDEMIC,
        EventCategory.SCANDAL,
        EventCategory.UNREST,
        EventCategory.TERROR,
    )

@Composable
private fun PausePreferences(
    settings: Set<EventCategory>,
    onToggle: (EventCategory, Boolean) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Hint("우리나라 사건과 세계 대유행이 일어났을 때 시간을 멈출 분류를 고르세요. 선거와 결정이 필요한 사건은 항상 멈춥니다.")
        PanelColumns(
            left = {
                LedgerHeading("사회와 재난")
                societyEvents.forEach { PauseSetting(it, it in settings, onToggle) }
            },
            right = {
                LedgerHeading("정치와 경제")
                EventCategory.entries.filterNot { it in societyEvents }.forEach { PauseSetting(it, it in settings, onToggle) }
            },
        )
    }
}

@Composable
private fun PauseSetting(
    category: EventCategory,
    on: Boolean,
    onToggle: (EventCategory, Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand)
            .toggleable(value = on, role = Role.Switch) { onToggle(category, it) }
            .padding(horizontal = 3.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(category.label, Modifier.weight(1f), style = DossierType.title(15.sp), color = DossierColors.ink)
        Text(if (on) "켬" else "끔", style = DossierType.number(14.sp), color = if (on) DossierColors.blueInk else DossierColors.inkMuted)
    }
    HorizontalDivider(color = DossierColors.paperLine)
}

private const val NEWS_PER_PAGE = 40
