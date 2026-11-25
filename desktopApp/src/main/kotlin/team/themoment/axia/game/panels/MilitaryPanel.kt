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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatCompactDollars
import team.themoment.axia.format.formatPercent
import team.themoment.axia.format.formatPopulation
import team.themoment.axia.game.MilitaryPage
import team.themoment.axia.simulation.WorldSnapshot
import team.themoment.axia.simulation.military.MaritimeRestriction
import team.themoment.axia.simulation.military.MilitaryAction
import team.themoment.axia.simulation.military.MilitarySnapshot
import team.themoment.axia.simulation.military.Mobilization
import team.themoment.axia.simulation.military.ReconstructionEffort
import team.themoment.axia.simulation.military.TreatyRegion
import team.themoment.axia.simulation.military.WarSnapshot
import team.themoment.axia.simulation.text.josa
import team.themoment.axia.simulation.world.Geography
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.DossierLinkRow
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow
import kotlin.math.roundToInt

/** 군사 서류철. 우리 전력과 동원 태세, 우리가 관련된 전쟁, 군사 행위. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MilitaryPanel(
    page: MilitaryPage,
    world: WorldSnapshot,
    playerId: String,
    politicalCapital: Double,
    countryName: (String) -> String,
    onMobilize: (Mobilization) -> Unit,
    onAction: (String, MilitaryAction) -> Unit,
    onReconstruction: (ReconstructionEffort) -> Unit,
    onTradeRoute: (String, String, Boolean) -> Unit,
    onRegionTreaty: (String, String) -> Unit,
) {
    val military = world.military
    val forces = military.forces.getValue(playerId)
    when (page) {
        MilitaryPage.FORCES -> {
            PanelColumns(
                left = {
                    LedgerHeading("우리 군")
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
                        military.aid.forEach { (id, until) -> StatRow(countryName(id), "${shortDate(until)} 만료") }
                    }
                },
                right = {
                    LedgerHeading("세계 군사력")
                    military.forces.values.sortedByDescending { it.power }.take(TOP_POWERS).forEach {
                        val nuclear = if (it.nuclearWarheads > 0) " · 핵 ${it.nuclearWarheads}" else ""
                        StatRow(countryName(it.countryId), "%.1f$nuclear".format(it.power))
                    }
                },
            )
        }

        MilitaryPage.WARS -> {
            PanelColumns(
                left = {
                    val ours = military.wars.filter { it.ceasefire == null && (playerId in it.sideA || playerId in it.sideB) }
                    LedgerHeading("우리나라의 전쟁")
                    if (ours.isEmpty()) Hint("치르고 있는 전쟁이 없습니다.")
                    ours.forEach { WarCard(it, countryName, playerId) }

                    if (military.maritimeRestrictions.isNotEmpty()) {
                        SectionTitle("해상 교역 제한")
                        military.maritimeRestrictions.forEach { order ->
                            val affected =
                                order.partner?.let { "${countryName(order.target)} ↔ ${countryName(it)} · 선적 중단" }
                                    ?: "${countryName(order.target)} · 해상 수송능력 45%"
                            StatRow(countryName(order.issuer), affected)
                        }
                    }
                },
                right = {
                    LedgerHeading("전후 재건 예산")
                    val rebuilding = military.recovery
                    StatRow("남은 복구 수요", "GDP의 ${formatPercent(rebuilding.damage[playerId] ?: 0.0)}")
                    StatRow("이번 달 재건 지출 (연율)", "GDP의 ${formatPercent(rebuilding.reconstructionShares[playerId] ?: 0.0)}")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ReconstructionEffort.entries.forEach { effort ->
                            PaperTag(
                                "${effort.label} · ${formatPercent(effort.annualShare)}",
                                selected = effort == military.reconstructionEffort,
                                onClick = { onReconstruction(effort) },
                            )
                        }
                    }
                    Hint("교전이 끝나면 남은 피해만큼 매달 예산을 집행합니다. 복구를 앞당기면 귀환도 빨라집니다.")
                },
            )
        }

        MilitaryPage.COUNTRIES -> {
            val neighbors = Geography.default.of(playerId).neighbors
            val eligible =
                world.militaryActions.entries
                    .filter { (id, actions) ->
                        actions.any { it != MilitaryAction.DECLARE_WAR } || id in neighbors ||
                            (world.diplomacy.relations[id] ?: 0.0) < HOSTILE
                    }.sortedBy { countryName(it.key) }
            var preferred by remember(playerId) { mutableStateOf<String?>(null) }
            val target = eligible.firstOrNull { it.key == preferred } ?: eligible.firstOrNull()
            PanelColumns(
                leftWeight = 0.8f,
                left = {
                    LedgerHeading("상대국")
                    if (eligible.isEmpty()) Hint("현재 선택할 수 있는 상대국이 없습니다.")
                    eligible.forEach { (id, actions) ->
                        DossierLinkRow(countryName(id), if (id == target?.key) "선택됨" else "${actions.size}개 행동") { preferred = id }
                    }
                },
                right = {
                    if (target != null) {
                        val (id, actions) = target
                        LedgerHeading(countryName(id))
                        StatRow("정치 자본", "${politicalCapital.toInt()} / 100")
                        key(id) {
                            CountryActions(
                                military,
                                playerId,
                                id,
                                actions,
                                politicalCapital,
                                countryName,
                                onAction,
                                onTradeRoute,
                                onRegionTreaty,
                            )
                        }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CountryActions(
    military: MilitarySnapshot,
    playerId: String,
    id: String,
    actions: Set<MilitaryAction>,
    politicalCapital: Double,
    countryName: (String) -> String,
    onAction: (String, MilitaryAction) -> Unit,
    onTradeRoute: (String, String, Boolean) -> Unit,
    onRegionTreaty: (String, String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        actions.forEach { action ->
            val holder =
                when (action) {
                    MilitaryAction.OFFER_TERRITORY -> playerId
                    MilitaryAction.REQUEST_TERRITORY -> id
                    else -> null
                }
            val area =
                holder?.let {
                    military.disputes
                        .firstOrNull { dispute ->
                            dispute.controller == it && dispute.treatyRegions.isNotEmpty() && playerId in dispute.claimants &&
                                id in dispute.claimants
                        }?.name
                }
            val actionLabel = if (area != null) "$area · ${action.label}" else action.label
            PaperTag(
                "$actionLabel · ${action.cost.roundToInt()}",
                enabled = politicalCapital >= action.cost,
                onClick = { onAction(id, action) },
            )
        }
    }
    if (MilitaryAction.BLOCKADE in actions || MilitaryAction.END_BLOCKADE in actions) {
        TradeRouteForm(
            id,
            playerId,
            military.forces.keys,
            military.maritimeRestrictions,
            politicalCapital,
            countryName,
            onTradeRoute,
        )
    }
    if (MilitaryAction.WITHDRAWAL_TREATY in actions) {
        RegionTreatyForm(
            target = id,
            ours = military.negotiableRegions[playerId].orEmpty(),
            theirs = military.negotiableRegions[id].orEmpty(),
            capital = politicalCapital,
            onSubmit = onRegionTreaty,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RegionTreatyForm(
    target: String,
    ours: List<TreatyRegion>,
    theirs: List<TreatyRegion>,
    capital: Double,
    onSubmit: (String, String) -> Unit,
) {
    if (ours.isEmpty() && theirs.isEmpty()) return
    var opened by remember(target) { mutableStateOf(false) }
    PaperTag(if (opened) "지역 협상 접기" else "행정구역 이관 협상", onClick = { opened = !opened })
    if (!opened) return
    var offer by remember(target) { mutableStateOf(ours.isNotEmpty()) }
    val choices = if (offer) ours else theirs
    var selected by remember(target, offer) { mutableStateOf<TreatyRegion?>(null) }
    var page by remember(target, offer) { mutableStateOf(0) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PaperTag("우리 지역 양도", selected = offer, enabled = ours.isNotEmpty(), onClick = { offer = true })
        PaperTag("상대 지역 요구", selected = !offer, enabled = theirs.isNotEmpty(), onClick = { offer = false })
    }
    val start = page.coerceAtMost((choices.size - 1).coerceAtLeast(0) / REGIONS_PER_PAGE) * REGIONS_PER_PAGE
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        choices.drop(start).take(REGIONS_PER_PAGE).forEach { region ->
            PaperTag(region.name, selected = region == selected, onClick = { selected = region })
        }
    }
    if (choices.size > REGIONS_PER_PAGE) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PaperTag("이전", enabled = start > 0, onClick = { page-- })
            PaperTag("다음", enabled = start + REGIONS_PER_PAGE < choices.size, onClick = { page++ })
        }
    }
    selected?.takeIf { it in choices }?.let { region ->
        val cost = if (offer) MilitaryAction.OFFER_TERRITORY.cost else MilitaryAction.REQUEST_TERRITORY.cost
        PaperTag("${region.name} 이관 제안 · ${cost.roundToInt()}", enabled = capital >= cost, onClick = { onSubmit(target, region.id) })
    }
}

private const val REGIONS_PER_PAGE = 8

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
        war.treaty?.let { treaty ->
            val terms = treaty.territory?.let { "$it → ${countryName(requireNotNull(treaty.to))}" } ?: "상호 철수"
            StatRow("평화협정 · ${shortDate(treaty.date)}", terms)
        }
        StatRow("협상 대표", "${countryName(war.sideA.first())} · ${countryName(war.sideB.first())}")
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TradeRouteForm(
    target: String,
    player: String,
    countries: Set<String>,
    orders: List<MaritimeRestriction>,
    capital: Double,
    countryName: (String) -> String,
    onChange: (String, String, Boolean) -> Unit,
) {
    var open by remember(target) { mutableStateOf(false) }
    PaperTag(if (open) "교역로 접기" else "상대국의 개별 해상 교역로", onClick = { open = !open })
    if (!open) return
    val partners =
        remember(target, countries, countryName) {
            val geo = Geography.default
            countries.filter { it != target && geo.of(it).coastal && !geo.neighbors(it, target) }.sortedBy(countryName)
        }
    var page by remember(target) { mutableStateOf(0) }
    Hint("거래 상대를 선택하면 해당 두 나라 사이의 원유·곡물 선적과 교역 결제를 양방향 중단하거나 재개합니다.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        partners.drop(page * REGIONS_PER_PAGE).take(REGIONS_PER_PAGE).forEach { partner ->
            val closed = MaritimeRestriction(player, target, partner) in orders
            PaperTag(
                countryName(partner) + if (closed) " · 재개" else " · 차단 ${MaritimeRestriction.ROUTE_COST.toInt()}",
                selected = closed,
                enabled = closed || capital >= MaritimeRestriction.ROUTE_COST,
                onClick = { onChange(target, partner, !closed) },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PaperTag("이전", enabled = page > 0, onClick = { page-- })
        PaperTag("다음", enabled = (page + 1) * REGIONS_PER_PAGE < partners.size, onClick = { page++ })
    }
}
