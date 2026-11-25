package team.themoment.axia.game.panels

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatPercent
import team.themoment.axia.game.ConsoleGlyph
import team.themoment.axia.game.ConsoleSymbol
import team.themoment.axia.game.NationalMetric
import team.themoment.axia.simulation.WorldSnapshot
import team.themoment.axia.simulation.diplomacy.BilateralSnapshot
import team.themoment.axia.simulation.diplomacy.DiplomaticAction
import team.themoment.axia.simulation.diplomacy.DiplomaticDispatch
import team.themoment.axia.simulation.diplomacy.MembershipDecision
import team.themoment.axia.simulation.diplomacy.MembershipDispatch
import team.themoment.axia.simulation.diplomacy.MembershipOutcome
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow
import team.themoment.axia.ui.dossier.metricTooltip
import kotlin.math.roundToInt

internal enum class DiplomacySection(
    val label: String,
) {
    OVERVIEW("현황"),
    COUNTRIES("국가 목록"),
    ORGANIZATIONS("국제기구"),
}

private enum class PartnerFilter(
    val label: String,
) {
    ALL("전체"),
    NOTABLE("주요 상대"),
    TREATIES("조약"),
    CONFLICT("제재·교전"),
    ;

    fun includes(partner: BilateralSnapshot): Boolean =
        when (this) {
            ALL -> true
            NOTABLE -> partner.notable
            TREATIES -> partner.allied || partner.transit || partner.tradeAgreement
            CONFLICT -> partner.atWar || partner.sanctionedByUs > 0 || partner.sanctioningUs > 0
        }
}

@Composable
internal fun DiplomacyPanel(
    world: WorldSnapshot,
    politicalCapital: Double,
    countryName: (String) -> String,
    onAction: (String, DiplomaticAction) -> Unit,
    onMembership: (String, Boolean) -> Unit,
    section: DiplomacySection,
    selectedCountryId: String?,
    onSelectCountry: (String?) -> Unit,
) {
    val diplomacy = world.diplomacy
    key(section, diplomacy.playerId) {
        when (section) {
            DiplomacySection.OVERVIEW -> {
                val primary =
                    world.trade.partners[diplomacy.playerId]
                        .orEmpty()
                        .firstOrNull()
                        ?.first
                val selected = diplomacy.partners.firstOrNull { it.countryId == selectedCountryId }
                val current = selected ?: diplomacy.partners.firstOrNull { it.countryId == primary } ?: diplomacy.partners.firstOrNull()
                PanelColumns(
                    leftWeight = 0.91f,
                    left = { DiplomaticOverview(world, politicalCapital, countryName, current?.countryId, onSelectCountry) },
                    right = {
                        if (current != null) {
                            DiplomaticPartner(current, countryName(current.countryId), politicalCapital, diplomacy.lastDispatch, onAction)
                        }
                    },
                )
            }

            DiplomacySection.COUNTRIES -> {
                CountryBrowser(
                    diplomacy.partners,
                    politicalCapital,
                    countryName,
                    selectedCountryId,
                    onSelectCountry,
                    diplomacy.lastDispatch,
                    onAction,
                )
            }

            DiplomacySection.ORGANIZATIONS -> {
                MembershipRegister(
                    diplomacy.membershipDecisions,
                    diplomacy.lastMembershipDispatch,
                    onMembership,
                )
            }
        }
    }
}

@Composable
private fun CountryBrowser(
    partners: List<BilateralSnapshot>,
    politicalCapital: Double,
    countryName: (String) -> String,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    dispatch: DiplomaticDispatch?,
    onAction: (String, DiplomaticAction) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(PartnerFilter.ALL) }
    val search = query.trim()
    val matches =
        partners
            .filter {
                filter.includes(it) &&
                    (it.countryId.contains(search, ignoreCase = true) || countryName(it.countryId).contains(search, ignoreCase = true))
            }.sortedBy { countryName(it.countryId) }
    val selectedIndex = matches.indexOfFirst { it.countryId == selectedId }
    val selected = matches.getOrNull(selectedIndex)
    val scroll = key(search, filter) { rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex.coerceAtLeast(0)) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 720.dp
        val current = selected ?: matches.firstOrNull()?.takeUnless { narrow }
        val directory: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier.fillMaxHeight()) {
                LedgerHeading("외교 상대")
                BasicTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onSelect(null)
                    },
                    singleLine = true,
                    textStyle = DossierType.number(15.sp).copy(color = DossierColors.ink),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "국가명·코드 검색" }
                            .border(1.dp, DossierColors.folderEdge)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    decorationBox = { input ->
                        Box {
                            if (query.isEmpty()) Hint("국가명·코드 검색")
                            input()
                        }
                    },
                )
                FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PartnerFilter.entries.forEach { choice ->
                        PaperTag(choice.label, selected = filter == choice, onClick = {
                            filter = choice
                            onSelect(null)
                        })
                    }
                }
                SectionTitle("국가 ${matches.size}개국")
                if (matches.isEmpty()) Hint("조건에 맞는 국가가 없습니다.")
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().padding(end = 9.dp)) {
                        items(matches, key = { it.countryId }) { partner ->
                            CountryRecord(partner, countryName(partner.countryId), relationSummary(partner), current?.countryId, onSelect)
                        }
                    }
                    if (scroll.canScrollForward || scroll.canScrollBackward) {
                        VerticalScrollbar(rememberScrollbarAdapter(scroll), modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        }
        val detail: @Composable (Modifier) -> Unit = { modifier ->
            key(current?.countryId) {
                LedgerColumn(modifier) {
                    if (narrow) PaperTag("국가 목록으로 돌아가기", onClick = { onSelect(null) })
                    if (current != null) {
                        DiplomaticPartner(current, countryName(current.countryId), politicalCapital, dispatch, onAction)
                    }
                }
            }
        }
        if (narrow) {
            if (current == null) directory(Modifier.fillMaxSize()) else detail(Modifier.fillMaxSize())
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                directory(Modifier.weight(0.85f))
                if (current != null) {
                    PageRule()
                    detail(Modifier.weight(1.15f))
                }
            }
        }
    }
}

@Composable
private fun DiplomaticOverview(
    world: WorldSnapshot,
    politicalCapital: Double,
    countryName: (String) -> String,
    selectedId: String?,
    onSelectCountry: (String) -> Unit,
) {
    val diplomacy = world.diplomacy
    Row(
        Modifier.fillMaxWidth().background(DossierColors.blueInk).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConsoleGlyph(ConsoleSymbol.GLOBE, Modifier.size(25.dp), DossierColors.paper)
        Text(countryName(diplomacy.playerId), style = DossierType.title(18.sp), modifier = Modifier.weight(1f), color = DossierColors.paper)
        val capital = "${politicalCapital.toInt()} / 100"
        Text(
            "정치 자본 $capital",
            modifier = Modifier.metricTooltip("정치 자본", NationalMetric.POLITICAL_CAPITAL.description),
            style = DossierType.number(13.sp),
            color = DossierColors.paper,
        )
    }
    SectionTitle("대외 관계")
    StatRow("동맹국 · 무역협정국", "${diplomacy.partners.count { it.allied }}개국 · ${diplomacy.partners.count { it.tradeAgreement }}개국")
    StatRow("가입한 국제기구", "${diplomacy.organizations.size}곳")
    val crises = diplomacy.partners.filter(PartnerFilter.CONFLICT::includes)
    val conflicts = crises.sortedWith(compareByDescending<BilateralSnapshot> { it.atWar }.thenBy { it.relation })
    StatRow("제재·교전 관계", "${conflicts.size}개국")
    conflicts.take(3).forEach { partner ->
        CountryRecord(partner, countryName(partner.countryId), relationSummary(partner), selectedId, onSelectCountry)
    }
    SectionTitle("주요 교역 상대")
    world.trade.partners[diplomacy.playerId].orEmpty().take(5).forEach { (id, share) ->
        val partner = diplomacy.partners.firstOrNull { it.countryId == id }
        if (partner == null) {
            StatRow(countryName(id), "수출 ${formatPercent(share)}")
        } else {
            CountryRecord(partner, countryName(id), "수출 ${formatPercent(share)}", selectedId, onSelectCountry)
        }
    }
    world.trade.tradeChange[diplomacy.playerId]?.let { StatRow("제재·협정의 잠재 GDP 효과", formatPercent(it, signed = true)) }
    StatRow(
        "국제 유가 (2024년 1월 = 100)",
        world.trade.oilPrice
            .roundToInt()
            .toString(),
    )
}

private fun relationSummary(partner: BilateralSnapshot): String =
    when {
        partner.atWar -> "교전 중"
        partner.sanctionedByUs > 0 && partner.sanctioningUs > 0 -> "상호 제재"
        partner.sanctioningUs > 0 -> "상대국 제재"
        partner.sanctionedByUs > 0 -> "제재 중"
        partner.allied -> partner.alliance ?: "동맹"
        partner.tradeAgreement -> "무역협정"
        partner.transit -> "상호 통행권"
        partner.aidMonthsLeft > 0 -> "원조 중"
        else -> ""
    }

@Composable
private fun CountryRecord(
    partner: BilateralSnapshot,
    name: String,
    context: String,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .relationTooltip(name)
            .background(if (partner.countryId == selectedId) DossierColors.folder.copy(alpha = 0.53f) else Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand)
            .selectable(selected = partner.countryId == selectedId, role = Role.RadioButton) { onSelect(partner.countryId) }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = DossierType.title(14.sp), color = DossierColors.ink, modifier = Modifier.weight(1f))
        if (context.isNotEmpty()) Text(context, style = DossierType.number(11.sp), color = DossierColors.inkMuted)
        Text(
            "${relationLabel(partner.relation)} ${partner.relation.roundToInt()}",
            style = DossierType.number(12.sp),
            color = relationColor(partner.relation),
        )
        Text("›", style = DossierType.number(16.sp), color = DossierColors.folderEdge)
    }
    HorizontalDivider(color = DossierColors.paperLine)
}

@Composable
private fun MembershipRegister(
    decisions: List<MembershipDecision>,
    dispatch: MembershipDispatch?,
    onMembership: (String, Boolean) -> Unit,
) {
    val members = decisions.filter { it.member }.sortedBy { it.organization.name }
    val candidates =
        decisions
            .filterNot {
                it.member
            }.sortedWith(compareByDescending<MembershipDecision> { it.canJoin }.thenBy { it.organization.name })
    Column(Modifier.fillMaxSize()) {
        dispatch?.let { result ->
            val name = decisions.firstOrNull { it.organization.id == result.organizationId }?.organization?.name ?: result.organizationId
            val action = if (result.join) "가입" else "탈퇴"
            val outcome =
                when (result.outcome) {
                    MembershipOutcome.COMPLETED -> "발효"
                    MembershipOutcome.UNCHANGED -> if (result.join) "이미 회원국" else "이미 탈퇴한 상태"
                    MembershipOutcome.AT_WAR -> "회원국과 교전 중"
                    MembershipOutcome.INSUFFICIENT_VOTES -> "찬성표 부족"
                    MembershipOutcome.UNKNOWN_ORGANIZATION -> "기구 없음"
                }
            StatRow(
                shortDate(result.date),
                "$name $action · $outcome",
                valueColor = if (result.outcome == MembershipOutcome.COMPLETED) DossierColors.positive else DossierColors.negative,
            )
        }
        PanelColumns(
            left = {
                LedgerHeading("가입 기구 · ${members.size}곳")
                if (members.isEmpty()) Hint("가입한 기구가 없습니다.")
                members.forEach { MembershipRow(it, onMembership) }
            },
            right = {
                LedgerHeading("미가입 기구 · ${candidates.size}곳")
                if (candidates.isEmpty()) Hint("모든 기구에 가입했습니다.") else Hint("가입하려면 군사동맹은 만장일치, 그 밖의 기구는 회원국 3분의 2 이상의 찬성이 필요합니다.")
                candidates.forEach { MembershipRow(it, onMembership) }
            },
        )
    }
}

@Composable
private fun MembershipRow(
    decision: MembershipDecision,
    onMembership: (String, Boolean) -> Unit,
) {
    val org = decision.organization
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(org.name, style = DossierType.title(15.sp), color = DossierColors.ink)
            Hint("${org.kind.label} · 회원국 ${org.members.size}개국")
            if (!decision.member) Hint(if (decision.atWarWithMember) "회원국과 교전 중" else "예상 찬성 ${decision.votes} · 필요 ${decision.required}")
        }
        PaperTag(
            if (decision.member) "탈퇴" else "가입 신청",
            enabled = decision.member || decision.canJoin,
            onClick = { onMembership(org.id, !decision.member) },
        )
    }
    HorizontalDivider(color = DossierColors.paperLine)
}
