package team.themoment.axia.game.panels

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.map.WorldMapModel
import team.themoment.axia.simulation.economy.Creditor
import team.themoment.axia.simulation.economy.DebtPlan
import team.themoment.axia.simulation.economy.DebtWorkoutSnapshot
import team.themoment.axia.simulation.events.GameEvent
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.paperTexture
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val issueDate = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREAN)

/** 사건을 신문 한 면으로 열고, 신문을 접거나 대응을 결정할 때까지 뒤쪽 책상의 입력을 막는다. */
@Composable
fun EventDialog(
    event: GameEvent,
    remaining: Int,
    debt: DebtWorkoutSnapshot,
    onCreditorTerms: (Creditor, DebtPlan?) -> Unit,
    countryName: (String) -> String,
    mapModel: WorldMapModel,
    regionOwners: Map<String, String>,
    recentEvents: List<GameEvent>,
    onFold: () -> Unit,
    onChoose: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val doubleClickMillis = LocalViewConfiguration.current.doubleTapTimeoutMillis
    var lastChoice by remember { mutableStateOf<TimeMark?>(null) }
    key(event.id) {
        val first = remember { FocusRequester() }
        val last = remember { FocusRequester() }
        var submitted by remember { mutableStateOf(false) }
        BoxWithConstraints(
            modifier =
                modifier
                    .fillMaxSize()
                    .semantics { paneTitle = "사건 신문" }
                    .onPreviewKeyEvent {
                        if (it.key != Key.Escape) return@onPreviewKeyEvent false
                        if (it.type == KeyEventType.KeyUp) onFold()
                        true
                    }.onKeyEvent {
                        when (it.key) {
                            Key.Tab, Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> false
                            else -> true
                        }
                    }.focusProperties { onExit = { cancelFocusChange() } }
                    .focusGroup(),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.66f)).pointerInput(Unit) { detectTapGestures { } })
            val pageHeight = minOf(maxHeight - 24.dp, (maxWidth - 24.dp) / 0.78f)
            val pageWidth = pageHeight * 0.78f
            val margin = (pageWidth * 0.055f).coerceIn(10.dp, 44.dp)
            val compact = pageHeight < 730.dp
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .size(pageWidth, pageHeight)
                    .shadow(12.dp)
                    .paperTexture()
                    .padding(horizontal = margin, vertical = if (compact) 10.dp else 18.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(event.date.format(issueDate), Modifier.weight(1f), color = DossierColors.ink, style = DossierType.number(12.sp))
                    Text(
                        "신문 접기",
                        modifier =
                            Modifier
                                .focusRequester(first)
                                .focusProperties { previous = last }
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(role = Role.Button, onClick = onFold)
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                        color = DossierColors.blueInk,
                        style = DossierType.title(13.sp),
                    )
                }
                HorizontalDivider(thickness = 1.dp, color = DossierColors.ink)
                Text(
                    "국정일보",
                    Modifier.align(Alignment.CenterHorizontally).padding(vertical = 3.dp),
                    style = DossierType.title(if (compact) 39.sp else 49.sp).copy(letterSpacing = 5.sp),
                    color = DossierColors.ink,
                )
                HorizontalDivider(thickness = 3.dp, color = DossierColors.ink)
                Spacer(Modifier.height(2.dp))
                HorizontalDivider(thickness = 1.dp, color = DossierColors.ink)
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val sectionHeight = maxHeight
                    Layout(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(scroll)
                                .heightIn(min = sectionHeight)
                                .padding(end = 8.dp, top = 10.dp, bottom = 10.dp),
                        measurePolicy = frontPageLayout,
                        content = {
                            Column(verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 15.dp)) {
                                val country = countryName(event.countryId)
                                Text(
                                    "${event.category.label}  /  $country",
                                    style = DossierType.title(14.sp),
                                    color = DossierColors.stamp,
                                )
                                Text(
                                    event.name,
                                    color = DossierColors.ink,
                                    style =
                                        DossierType
                                            .title(
                                                if (compact) 33.sp else 44.sp,
                                            ).copy(lineHeight = if (compact) 39.sp else 52.sp),
                                )
                                Text(
                                    event.text,
                                    color = DossierColors.ink,
                                    style =
                                        DossierType
                                            .number(
                                                if (compact) 16.sp else 19.sp,
                                            ).copy(lineHeight = if (compact) 23.sp else 29.sp),
                                )
                                EventLocationMap(
                                    mapModel,
                                    regionOwners,
                                    event.countryId,
                                    country,
                                    Modifier.fillMaxWidth().height((sectionHeight * 0.36f).coerceIn(70.dp, 295.dp)),
                                )
                            }
                            Box {
                                if (event.definitionId != "sovereign_default" && event.choices.size <= 2) {
                                    WorldBriefs(event, recentEvents, countryName, if (compact) 1 else 2)
                                }
                            }
                            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (event.definitionId == "sovereign_default") CreditorTermsForm(debt, onCreditorTerms)
                                ResponseOptions(event, submitted, first, last) { index ->
                                    if (!submitted &&
                                        (lastChoice?.elapsedNow()?.inWholeMilliseconds ?: Long.MAX_VALUE) >= doubleClickMillis
                                    ) {
                                        submitted = true
                                        lastChoice = TimeSource.Monotonic.markNow()
                                        onChoose(event.id, index)
                                    }
                                }
                            }
                        },
                    )
                    VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
                HorizontalDivider(thickness = 1.dp, color = DossierColors.ink)
                Row(
                    Modifier.fillMaxWidth().padding(top = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    event.deadline?.let {
                        Text(
                            "${shortDate(it)}까지 결정하지 않으면 첫 번째 대응을 따릅니다.",
                            Modifier.weight(1f),
                            color = DossierColors.inkMuted,
                            style = DossierType.number(12.sp),
                        )
                    }
                    if (remaining > 1) Text("다음 사건 ${remaining - 1}건", color = DossierColors.ink, style = DossierType.title(12.sp))
                }
            }
            LaunchedEffect(first) { first.requestFocus() }
        }
    }
}

/** 기사와 정부 대응을 먼저 조판한다. 단신은 둘 사이의 여백에 전부 들어갈 때만 인쇄한다. */
private val frontPageLayout =
    MeasurePolicy { (article, briefs, response), constraints ->
        val childConstraints = constraints.copy(minHeight = 0)
        val lead = article.measure(childConstraints)
        val actions = response.measure(childConstraints)
        val height = maxOf(constraints.minHeight, lead.height + actions.height)
        val gap = height - lead.height - actions.height
        val extra = briefs.measure(childConstraints)
        layout(constraints.maxWidth, height) {
            lead.placeRelative(0, 0)
            if (extra.height in 1..gap) extra.placeRelative(0, lead.height + (gap - extra.height) / 2)
            actions.placeRelative(0, height - actions.height)
        }
    }

@Composable
private fun WorldBriefs(
    lead: GameEvent,
    recentEvents: List<GameEvent>,
    countryName: (String) -> String,
    count: Int,
) {
    val briefs =
        remember(lead.id, recentEvents, count) {
            val earliest = lead.date.minusDays(30)
            recentEvents
                .asSequence()
                .filter { it.countryId != lead.countryId && it.definitionId != lead.definitionId && it.date in earliest..lead.date }
                .distinctBy { it.definitionId }
                .distinctBy { it.countryId }
                .take(count)
                .toList()
        }
    if (briefs.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        HorizontalDivider(thickness = 1.dp, color = DossierColors.ink)
        Text("세계 단신", Modifier.padding(vertical = 5.dp), style = DossierType.title(14.sp), color = DossierColors.stamp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            briefs.forEach { brief ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${shortDate(brief.date)} · ${countryName(brief.countryId)}",
                        style = DossierType.number(11.sp),
                        color = DossierColors.inkMuted,
                    )
                    Text(brief.name, style = DossierType.title(15.sp), color = DossierColors.ink)
                    Text(
                        brief.text,
                        style = DossierType.number(13.sp),
                        color = DossierColors.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResponseOptions(
    event: GameEvent,
    submitted: Boolean,
    first: FocusRequester,
    last: FocusRequester,
    onChoose: (Int) -> Unit,
) {
    Column {
        HorizontalDivider(thickness = 2.dp, color = DossierColors.ink)
        Text("정부 대응", Modifier.padding(vertical = 7.dp), style = DossierType.title(16.sp), color = DossierColors.ink)
        event.choices.forEachIndexed { index, choice ->
            HorizontalDivider(thickness = 1.dp, color = DossierColors.folderEdge.copy(alpha = 0.58f))
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (index ==
                            event.choices.lastIndex
                        ) {
                            Modifier.focusRequester(last).focusProperties { next = first }
                        } else {
                            Modifier
                        },
                    ).pointerHoverIcon(PointerIcon.Hand)
                    .clickable(enabled = !submitted, role = Role.Button) { onChoose(index) }
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(choice.label, color = DossierColors.blueInk, style = DossierType.title(16.sp))
                    Text(choice.description, color = DossierColors.ink, style = DossierType.number(13.sp, FontWeight.Normal))
                }
                Text("›", color = DossierColors.blueInk, style = DossierType.title(22.sp))
            }
        }
    }
}
