package team.themoment.axia.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import kotlinx.datetime.plus
import team.themoment.axia.designsystem.component.AxiaButton
import team.themoment.axia.designsystem.component.AxiaButtonStyle
import team.themoment.axia.designsystem.component.AxiaDialog
import team.themoment.axia.designsystem.component.AxiaEmptyState
import team.themoment.axia.designsystem.component.AxiaPanel
import team.themoment.axia.designsystem.component.AxiaSelect
import team.themoment.axia.designsystem.component.AxiaSelectOption
import team.themoment.axia.designsystem.theme.AxiaTheme
import team.themoment.axia.domain.ProductId
import team.themoment.axia.domain.TechnicalPosition
import team.themoment.axia.feature.unavailability.UnavailabilityCallbacks
import team.themoment.axia.feature.unavailability.UnavailabilityScreen
import team.themoment.axia.feature.unavailability.UnavailabilityUiState
import team.themoment.axia.feature.unavailability.canEditUnavailability
import team.themoment.axia.platform.AxiaTime
import team.themoment.axia.tasks.TaskSummary
import team.themoment.axia.tasks.TaskUrgency

@Composable
fun CalendarScreen(
    month: LocalDate,
    tasks: List<TaskSummary>,
    filters: CalendarFilters,
    periods: UnavailabilityUiState,
    callbacks: CalendarCallbacks,
    periodCallbacks: UnavailabilityCallbacks,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    taskWritePending: Boolean = false,
) {
    val days = remember(month) { calendarDays(month) }
    val entries =
        remember(days, tasks, periods.periods, periods.members, filters) {
            calendarEntries(days, tasks, periods.periods, periods.members, filters)
        }
    var showingPeriods by remember { mutableStateOf(false) }
    var selectedDay by remember(month) { mutableStateOf<LocalDate?>(null) }
    val latestCallbacks by rememberUpdatedState(callbacks)
    val selectedMember = periods.members.firstOrNull { it.id == (filters.userId ?: periods.currentUser?.member?.id) }
    val canCreate = !periods.saving && selectedMember?.let { canEditUnavailability(periods.currentUser, it) } == true
    LaunchedEffect(periods.editor) { if (periods.editor != null) showingPeriods = true }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
            verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            AxiaButton("이전 달", { callbacks.onMonthChange(month.plus(-1, DateTimeUnit.MONTH)) }, style = AxiaButtonStyle.Secondary)
            Text(
                "${month.year}년 ${month.month.number}월",
                Modifier.semantics {
                    heading()
                },
                color = AxiaTheme.semanticColors.textPrimary,
                style = AxiaTheme.typography.sectionTitle,
            )
            AxiaButton("다음 달", { callbacks.onMonthChange(month.plus(1, DateTimeUnit.MONTH)) }, style = AxiaButtonStyle.Secondary)
            AxiaButton("이번 달", {
                val today = AxiaTime.today()
                callbacks.onMonthChange(LocalDate(today.year, today.month, 1))
            }, style = AxiaButtonStyle.Tertiary)
            AxiaButton("새로고침", periodCallbacks.onRefresh, enabled = !periods.loading, style = AxiaButtonStyle.Tertiary)
            AxiaButton(
                if (showingPeriods) "달력 보기" else "일정 목록 · 수동 입력",
                { showingPeriods = !showingPeriods },
                style = AxiaButtonStyle.Secondary,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
            verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
        ) {
            AxiaSelect(
                "제품",
                filters.productId,
                listOf(AxiaSelectOption<ProductId?>(null, "전 제품")) +
                    ProductId.entries.map { AxiaSelectOption<ProductId?>(it, it.displayName) },
                { callbacks.onFiltersChange(filters.copy(productId = it)) },
            )
            AxiaSelect(
                "구성원",
                filters.userId,
                listOf(AxiaSelectOption<Long?>(null, "모든 구성원")) +
                    periods.members.map {
                        AxiaSelectOption<Long?>(it.id, it.displayName)
                    },
                { callbacks.onFiltersChange(filters.copy(userId = it)) },
            )
            AxiaSelect(
                "기술 포지션",
                filters.position,
                listOf(AxiaSelectOption<TechnicalPosition?>(null, "모든 포지션")) +
                    TechnicalPosition.entries.map { AxiaSelectOption<TechnicalPosition?>(it, it.displayName) },
                { callbacks.onFiltersChange(filters.copy(position = it)) },
            )
            AxiaSelect(
                "일정 유형",
                filters.type,
                listOf(AxiaSelectOption<CalendarEntryType?>(null, "모든 유형")) +
                    CalendarEntryType.entries.map { AxiaSelectOption<CalendarEntryType?>(it, it.label) },
                { callbacks.onFiltersChange(filters.copy(type = it)) },
            )
        }
        if (showingPeriods) {
            val matchingIds =
                entries.values
                    .flatten()
                    .mapNotNull { it.period?.id }
                    .toSet()
            UnavailabilityScreen(
                periods.copy(periods = periods.periods.filter { it.id in matchingIds }),
                periodCallbacks,
                Modifier.weight(1f),
            )
        } else {
            Text(
                "한국 시간 · 빈 날짜를 드래그하면 ${selectedMember?.displayName ?: "내"} 일정을 등록합니다. 마감일·휴가 항목을 다른 날짜로 끌어 옮길 수 있습니다. 날짜를 누르면 전체 일정을 볼 수 있습니다.",
                style = AxiaTheme.typography.secondary,
                color = AxiaTheme.semanticColors.textSecondary,
            )
            val bounds = remember(month) { mutableStateMapOf<LocalDate, Rect>() }
            var rangeStart by remember(month) { mutableStateOf<LocalDate?>(null) }
            var rangeEnd by remember(month) { mutableStateOf<LocalDate?>(null) }
            var dropDate by remember(month) { mutableStateOf<LocalDate?>(null) }

            fun dateAt(point: Offset): LocalDate? = bounds.entries.firstOrNull { it.value.contains(point) }?.key
            AxiaPanel(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth().background(AxiaTheme.semanticColors.surfaceSubtle)) {
                        listOf("월", "화", "수", "목", "금", "토", "일").forEach { label ->
                            Text(
                                label,
                                Modifier.weight(1f).padding(AxiaTheme.spacing.space3),
                                style = AxiaTheme.typography.metadata,
                                color = AxiaTheme.semanticColors.textSecondary,
                            )
                        }
                    }
                    days.chunked(7).forEach { week ->
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                            week.forEach { date ->
                                val selected =
                                    rangeStart?.let { first -> rangeEnd?.let { last -> date in minOf(first, last)..maxOf(first, last) } } ==
                                        true ||
                                        date == dropDate
                                val today = date == AxiaTime.today()
                                val color =
                                    when {
                                        selected -> AxiaTheme.semanticColors.info.container
                                        date.month != month.month -> AxiaTheme.semanticColors.surfaceSubtle
                                        else -> AxiaTheme.semanticColors.surface
                                    }
                                Column(
                                    Modifier
                                        .weight(
                                            1f,
                                        ).fillMaxHeight()
                                        .defaultMinSize(minHeight = AxiaTheme.dimensions.calendarDayMinHeight)
                                        .background(color)
                                        .border(AxiaTheme.dimensions.hairline, AxiaTheme.semanticColors.border)
                                        .onGloballyPositioned { bounds[date] = it.boundsInRoot() }
                                        .pointerInput(date, canCreate, selectedMember?.id) {
                                            if (canCreate) {
                                                detectDragGestures(
                                                    onDragStart = {
                                                        rangeStart = date
                                                        rangeEnd = date
                                                    },
                                                    onDrag = {
                                                        change,
                                                        _,
                                                        ->
                                                        bounds[date]?.let { rangeEnd = dateAt(it.topLeft + change.position) }
                                                        change.consume()
                                                    },
                                                    onDragCancel = {
                                                        rangeStart = null
                                                        rangeEnd = null
                                                    },
                                                    onDragEnd = {
                                                        val start = rangeStart
                                                        val end = rangeEnd
                                                        val user = selectedMember.id
                                                        rangeStart = null
                                                        rangeEnd = null
                                                        if (start != null &&
                                                            end != null
                                                        ) {
                                                            latestCallbacks.onCreatePeriodRange(
                                                                user,
                                                                minOf(start, end),
                                                                maxOf(start, end).plus(1, DateTimeUnit.DAY),
                                                            )
                                                        }
                                                    },
                                                )
                                            }
                                        }.padding(AxiaTheme.spacing.space1),
                                    verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1),
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(
                                                minHeight = AxiaTheme.dimensions.minimumInteractiveSize,
                                            ).clickable(role = Role.Button) {
                                                selectedDay =
                                                    date
                                            }.semantics { contentDescription = "$date 일정 보기" }
                                            .padding(
                                                horizontal = AxiaTheme.spacing.space2,
                                            ),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        Text(
                                            "${date.day}${if (today) " · 오늘" else ""}",
                                            color = if (today) AxiaTheme.semanticColors.action else AxiaTheme.semanticColors.textPrimary,
                                            style = AxiaTheme.typography.bodyEmphasis,
                                        )
                                    }
                                    val dayEntries = entries[date].orEmpty()
                                    dayEntries.take(2).forEach { entry ->
                                        val movable =
                                            !periods.saving &&
                                                when {
                                                    entry.type == CalendarEntryType.TARGET -> {
                                                        !readOnly && !taskWritePending &&
                                                            entry.task?.canWrite == true
                                                    }

                                                    entry.period != null -> {
                                                        periods.members.firstOrNull { it.id == entry.period.userId }?.let {
                                                            canEditUnavailability(
                                                                periods.currentUser,
                                                                it,
                                                            )
                                                        } ==
                                                            true
                                                    }

                                                    else -> {
                                                        false
                                                    }
                                                }
                                        CalendarEntryChip(entry, onClick = {
                                            entry.task?.let { callbacks.onOpenTask(it.id) }
                                                ?: entry.period?.let { periodCallbacks.onEdit(it) }
                                        }, movable = movable, onDragPosition = { dropDate = dateAt(it) }, onDrop = {
                                            val target = dropDate
                                            dropDate = null
                                            if (target != null && target != entry.date) {
                                                if (entry.type == CalendarEntryType.TARGET && entry.task != null &&
                                                    entry.project != null
                                                ) {
                                                    latestCallbacks.onMoveTarget(entry.task, entry.project, target)
                                                } else {
                                                    entry.period?.let { latestCallbacks.onMovePeriod(it, entry.date, target) }
                                                }
                                            }
                                        }, onCancelDrag = { dropDate = null })
                                    }
                                    if (dayEntries.size >
                                        2
                                    ) {
                                        AxiaButton("+${dayEntries.size - 2}개", { selectedDay = date }, style = AxiaButtonStyle.Tertiary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    selectedDay?.let { date ->
        AxiaDialog(title = "$date 일정", onDismiss = { selectedDay = null }, footer = {
            AxiaButton("닫기", { selectedDay = null }, style = AxiaButtonStyle.Secondary)
        }) {
            val dayEntries = entries[date].orEmpty()
            if (dayEntries.isEmpty()) AxiaEmptyState("등록된 일정이 없어요", "이 날짜에 새 일정을 등록할 수 있습니다.")
            dayEntries.forEach { entry ->
                CalendarEntryChip(entry, onClick = {
                    selectedDay = null
                    entry.task?.let { callbacks.onOpenTask(it.id) } ?: entry.period?.let { periodCallbacks.onEdit(it) }
                })
                Text(entry.description, style = AxiaTheme.typography.secondary, color = AxiaTheme.semanticColors.textSecondary)
            }
            AxiaButton("${selectedMember?.displayName ?: "내"} 일정 등록", {
                selectedMember?.let { callbacks.onCreatePeriodRange(it.id, date, date.plus(1, DateTimeUnit.DAY)) }
                selectedDay = null
            }, enabled = canCreate)
        }
    }
}

@Composable
private fun CalendarEntryChip(
    entry: CalendarEntry,
    onClick: () -> Unit,
    movable: Boolean = false,
    onDragPosition: (Offset) -> Unit = {},
    onDrop: () -> Unit = {},
    onCancelDrag: () -> Unit = {},
) {
    val palette = AxiaTheme.semanticColors
    val pair =
        when (entry.type) {
            CalendarEntryType.VACATION -> {
                palette.warning
            }

            CalendarEntryType.UNAVAILABLE -> {
                palette.danger
            }

            CalendarEntryType.START -> {
                palette.info
            }

            CalendarEntryType.TARGET -> {
                when (entry.task?.urgency) {
                    TaskUrgency.OVERDUE -> palette.danger
                    TaskUrgency.DUE_SOON -> palette.warning
                    else -> palette.info
                }
            }
        }
    var bounds by remember(entry.key) { mutableStateOf<Rect?>(null) }
    val latestPosition by rememberUpdatedState(onDragPosition)
    val latestDrop by rememberUpdatedState(onDrop)
    val latestCancel by rememberUpdatedState(onCancelDrag)
    Column(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AxiaTheme.dimensions.minimumInteractiveSize)
            .background(pair.container, AxiaTheme.shapes.control)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .pointerInput(entry.key, movable) {
                if (movable) {
                    detectDragGestures(
                        onDragStart = { position -> bounds?.let { latestPosition(it.topLeft + position) } },
                        onDrag = { change, _ ->
                            bounds?.let { latestPosition(it.topLeft + change.position) }
                            change.consume()
                        },
                        onDragEnd = { latestDrop() },
                        onDragCancel = { latestCancel() },
                    )
                }
            }.clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription =
                    "${entry.type.label} ${entry.title}, ${entry.description}${if (movable) ", 다른 날짜로 드래그하여 이동" else ""}"
            }.padding(AxiaTheme.spacing.space2),
        verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1),
    ) {
        Text(
            "${entry.type.label} · ${entry.title}",
            color = pair.content,
            style = AxiaTheme.typography.secondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
