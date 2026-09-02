package team.themoment.axia.feature.dashboard

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import team.themoment.axia.designsystem.component.AxiaBadge
import team.themoment.axia.designsystem.component.AxiaBadgeStyle
import team.themoment.axia.designsystem.component.AxiaBanner
import team.themoment.axia.designsystem.component.AxiaBannerStyle
import team.themoment.axia.designsystem.component.AxiaDivider
import team.themoment.axia.designsystem.component.AxiaEmptyState
import team.themoment.axia.designsystem.component.AxiaPanel
import team.themoment.axia.designsystem.theme.AxiaTheme

@Composable
fun DashboardScreen(
    state: DashboardUiState,
    modifier: Modifier = Modifier,
    onBrowseTasks: () -> Unit,
    onTaskSelected: ((String) -> Unit)? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (maxWidth >= AxiaTheme.dimensions.dashboardSplitMinWidth) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space6),
            ) {
                TaskQueue(
                    state = state,
                    onBrowseTasks = onBrowseTasks,
                    onTaskSelected = onTaskSelected,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                DashboardStatusRail(
                    state = state,
                    modifier = Modifier.width(AxiaTheme.dimensions.dashboardStatusRailWidth).fillMaxHeight(),
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space6),
            ) {
                TaskQueue(
                    state = state,
                    onBrowseTasks = onBrowseTasks,
                    onTaskSelected = onTaskSelected,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                DashboardStatusRail(
                    state = state,
                    modifier = Modifier.fillMaxWidth().height(AxiaTheme.dimensions.dashboardStackedRailHeight),
                )
            }
        }
    }
}

@Composable
fun GitHubReadOnlyBanner(
    onOpenStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AxiaBanner(
        modifier = modifier,
        title = "GitHub 연결이 불안정해 읽기 전용으로 전환했습니다.",
        description = "마지막으로 동기화된 데이터를 유지하며, 연결이 복구되면 자동으로 다시 동기화합니다.",
        style = AxiaBannerStyle.Danger,
        actionLabel = "GitHub Status 열기",
        onAction = onOpenStatus,
        announceChanges = true,
    )
}

@Composable
private fun TaskQueue(
    state: DashboardUiState,
    onBrowseTasks: () -> Unit,
    onTaskSelected: ((String) -> Unit)?,
    modifier: Modifier,
) {
    val tasks = remember(state.tasks) { state.tasks.sortedForDashboard() }
    AxiaPanel(
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            QueueHeader(taskCount = tasks.size)
            AxiaDivider()
            TaskLedgerColumnHeader()
            AxiaDivider()
            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    AxiaEmptyState(
                        title = "지금 확인할 내 작업이 없습니다",
                        description = "배정된 작업이나 지연·마감 임박 작업이 생기면 이 원장에 표시됩니다.",
                        actionLabel = "전체 태스크 보기",
                        onAction = onBrowseTasks,
                    )
                }
            } else {
                TaskLedger(
                    tasks = tasks,
                    onTaskSelected = onTaskSelected,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun QueueHeader(taskCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(AxiaTheme.spacing.space5),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1)) {
            Text(
                text = "내 작업 목록",
                color = AxiaTheme.semanticColors.textPrimary,
                style = AxiaTheme.typography.sectionTitle,
            )
            Text(
                text = "지연과 마감 임박 작업을 먼저 표시합니다.",
                color = AxiaTheme.semanticColors.textSecondary,
                style = AxiaTheme.typography.secondary,
            )
        }
        AxiaBadge(
            label = if (taskCount == 0) "작업 없음" else "${taskCount}개",
            style = AxiaBadgeStyle.Metadata,
        )
    }
}

@Composable
private fun TaskLedgerColumnHeader() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AxiaTheme.semanticColors.surfaceSubtle)
                .padding(
                    top = AxiaTheme.spacing.space2,
                    end = AxiaTheme.spacing.space4,
                    bottom = AxiaTheme.spacing.space2,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(AxiaTheme.dimensions.flowRail + AxiaTheme.spacing.space3))
        LedgerLabel(text = "종류", modifier = Modifier.width(AxiaTheme.dimensions.taskKindColumnWidth))
        LedgerLabel(text = "제목", modifier = Modifier.weight(2.2f))
        LedgerLabel(text = "제품", modifier = Modifier.weight(1f))
        LedgerLabel(text = "담당자", modifier = Modifier.width(AxiaTheme.dimensions.assigneeColumnWidth))
        LedgerLabel(text = "마감", modifier = Modifier.width(AxiaTheme.dimensions.dueColumnWidth))
    }
}

@Composable
private fun LedgerLabel(
    text: String,
    modifier: Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        color = AxiaTheme.semanticColors.textSecondary,
        style = AxiaTheme.typography.metadata,
        maxLines = 1,
    )
}

@Composable
private fun TaskLedger(
    tasks: List<DashboardTask>,
    onTaskSelected: ((String) -> Unit)?,
    modifier: Modifier,
) {
    val groupedTasks = remember(tasks) { tasks.groupBy(DashboardTask::productName) }
    LazyColumn(modifier = modifier) {
        groupedTasks.forEach { (productName, productTasks) ->
            item(key = "product:$productName") {
                ProductGroupHeader(
                    productName = productName,
                    taskCount = productTasks.size,
                )
            }
            items(
                items = productTasks,
                key = DashboardTask::id,
            ) { task ->
                DashboardTaskRow(
                    task = task,
                    onSelected = onTaskSelected,
                )
            }
        }
    }
}

@Composable
private fun ProductGroupHeader(
    productName: String,
    taskCount: Int,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AxiaTheme.semanticColors.surfaceSubtle)
                .height(AxiaTheme.dimensions.taskRowMinHeight)
                .padding(end = AxiaTheme.spacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .width(AxiaTheme.dimensions.flowRail)
                    .fillMaxHeight()
                    .background(AxiaTheme.semanticColors.action),
        )
        Row(
            modifier = Modifier.padding(start = AxiaTheme.spacing.space3),
            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = productName,
                color = AxiaTheme.semanticColors.textPrimary,
                style = AxiaTheme.typography.bodyEmphasis,
            )
            Text(
                text = "${taskCount}개",
                color = AxiaTheme.semanticColors.textSecondary,
                style = AxiaTheme.typography.numericMetadata,
            )
        }
    }
}

@Composable
private fun DashboardTaskRow(
    task: DashboardTask,
    onSelected: ((String) -> Unit)?,
) {
    val interactionSource = remember(task.id) { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val taskTypeDescription =
        buildList {
            add(task.contentKind.label)
            addAll(task.contexts.sortedBy { it.ordinal }.map(DashboardTaskContext::label))
        }.joinToString()
    val interactiveModifier =
        if (onSelected == null) {
            Modifier
        } else {
            Modifier
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClick = { onSelected(task.id) },
                ).semantics(mergeDescendants = true) {
                    contentDescription = "$taskTypeDescription, ${task.title}"
                    stateDescription = task.accessibilityStateDescription()
                    role = Role.Button
                }
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = AxiaTheme.dimensions.taskRowMinHeight)
                .then(interactiveModifier)
                .border(
                    width = if (isFocused) AxiaTheme.dimensions.focusRing else 0.dp,
                    color = if (isFocused) AxiaTheme.semanticColors.action else Color.Transparent,
                    shape = RectangleShape,
                ).padding(end = AxiaTheme.spacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .width(AxiaTheme.dimensions.flowRail)
                    .height(AxiaTheme.dimensions.taskRowMinHeight)
                    .background(task.position.railColor()),
        )
        Spacer(modifier = Modifier.width(AxiaTheme.spacing.space3))
        TaskKindCell(task = task, modifier = Modifier.width(AxiaTheme.dimensions.taskKindColumnWidth))
        TaskTitle(task = task, modifier = Modifier.weight(2.2f))
        ProductAndPositionCell(task = task, modifier = Modifier.weight(1f))
        AssigneeCell(task = task, modifier = Modifier.width(AxiaTheme.dimensions.assigneeColumnWidth))
        DueCell(task = task, modifier = Modifier.width(AxiaTheme.dimensions.dueColumnWidth))
    }
    AxiaDivider()
}

@Composable
private fun TaskKindCell(
    task: DashboardTask,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = AxiaTheme.spacing.space2),
        verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1),
        horizontalAlignment = Alignment.Start,
    ) {
        AxiaBadge(
            label = task.contentKind.label,
            style = AxiaBadgeStyle.Metadata,
        )
        task.contexts.sortedBy { it.ordinal }.forEach { context ->
            AxiaBadge(
                label = context.label,
                style = AxiaBadgeStyle.Info,
            )
        }
    }
}

@Composable
private fun ProductAndPositionCell(
    task: DashboardTask,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.padding(end = AxiaTheme.spacing.space2),
        verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1),
    ) {
        Text(
            text = task.productName,
            color = AxiaTheme.semanticColors.textSecondary,
            style = AxiaTheme.typography.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        task.position?.let { position ->
            AxiaBadge(
                label = position.label(),
                style = position.badgeStyle(),
            )
        }
    }
}

@Composable
private fun TaskTitle(
    task: DashboardTask,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = AxiaTheme.spacing.space2, horizontal = AxiaTheme.spacing.space2),
        verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1),
    ) {
        Text(
            text = task.title,
            color = AxiaTheme.semanticColors.textPrimary,
            style = AxiaTheme.typography.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val source = task.sourceLabel()
        if (source != null) {
            Text(
                text = source,
                color = AxiaTheme.semanticColors.textSecondary,
                style = AxiaTheme.typography.metadata,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AssigneeCell(
    task: DashboardTask,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.padding(end = AxiaTheme.spacing.space2),
        verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1),
    ) {
        val assigneeName =
            when (val assignee = task.assignee) {
                DashboardAssignee.Unassigned -> null
                is DashboardAssignee.Assigned -> assignee.name
            }
        Text(
            text = assigneeName ?: "담당자 없음",
            color =
                if (assigneeName == null) {
                    AxiaTheme.semanticColors.warning.content
                } else {
                    AxiaTheme.semanticColors.textSecondary
                },
            style = AxiaTheme.typography.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        task.assigneeStateLabels().forEach { notice ->
            AxiaBadge(
                label = notice,
                style =
                    if (notice == "비활성 담당자") {
                        AxiaBadgeStyle.Danger
                    } else {
                        AxiaBadgeStyle.Warning
                    },
            )
        }
    }
}

@Composable
private fun DueCell(
    task: DashboardTask,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1),
    ) {
        Text(
            text = task.dueLabel ?: "마감 없음",
            color = AxiaTheme.semanticColors.textSecondary,
            style = AxiaTheme.typography.numericMetadata,
            maxLines = 1,
        )
        when (task.urgency) {
            DashboardTaskUrgency.Overdue -> AxiaBadge("지연", AxiaBadgeStyle.Danger)
            DashboardTaskUrgency.DueSoon -> AxiaBadge("마감 임박", AxiaBadgeStyle.Warning)
            DashboardTaskUrgency.Normal -> Unit
        }
    }
}

@Composable
private fun DashboardStatusRail(
    state: DashboardUiState,
    modifier: Modifier,
) {
    val overdueCount = state.tasks.count { it.urgency == DashboardTaskUrgency.Overdue }
    val dueSoonCount = state.tasks.count { it.urgency == DashboardTaskUrgency.DueSoon }

    AxiaPanel(
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier.padding(AxiaTheme.spacing.space5),
                verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1),
            ) {
                Text(
                    text = "현재 상태",
                    color = AxiaTheme.semanticColors.textPrimary,
                    style = AxiaTheme.typography.sectionTitle,
                )
                Text(
                    text = "내가 먼저 확인할 항목만 모았습니다.",
                    color = AxiaTheme.semanticColors.textSecondary,
                    style = AxiaTheme.typography.secondary,
                )
            }
            AxiaDivider()
            StatusMetricRow(
                label = "읽지 않은 알림",
                value = state.unreadNotificationCount.toString(),
                style = if (state.unreadNotificationCount > 0) AxiaBadgeStyle.Info else AxiaBadgeStyle.Metadata,
            )
            StatusMetricRow(
                label = "지연",
                value = overdueCount.toString(),
                style = if (overdueCount > 0) AxiaBadgeStyle.Danger else AxiaBadgeStyle.Metadata,
            )
            StatusMetricRow(
                label = "마감 임박",
                value = dueSoonCount.toString(),
                style = if (dueSoonCount > 0) AxiaBadgeStyle.Warning else AxiaBadgeStyle.Metadata,
            )
            AvailabilityRow(availability = state.availability)
            AxiaDivider()
            ActivitySection(
                title = "최근 배정·해제",
                emptyMessage = "최근 배정 변경이 없습니다.",
                activities = state.recentAssignments,
            )
            AxiaDivider()
            ActivitySection(
                title = "최근 완료",
                emptyMessage = "최근 완료한 작업이 없습니다.",
                activities = state.recentCompletions,
            )
        }
    }
}

@Composable
private fun StatusMetricRow(
    label: String,
    value: String,
    style: AxiaBadgeStyle,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = AxiaTheme.dimensions.taskRowMinHeight)
                .padding(horizontal = AxiaTheme.spacing.space5, vertical = AxiaTheme.spacing.space2),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = AxiaTheme.semanticColors.textSecondary,
            style = AxiaTheme.typography.body,
        )
        AxiaBadge(label = value, style = style)
    }
}

@Composable
private fun AvailabilityRow(availability: MemberAvailability) {
    val (label, badgeStyle) =
        when (availability) {
            MemberAvailability.Available -> "작업 가능" to AxiaBadgeStyle.Success
            is MemberAvailability.Unavailable -> availability.label to AxiaBadgeStyle.Warning
        }
    val supportingText =
        when (availability) {
            MemberAvailability.Available -> null
            is MemberAvailability.Unavailable -> availability.untilLabel
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = AxiaTheme.dimensions.taskRowMinHeight)
                .padding(horizontal = AxiaTheme.spacing.space5, vertical = AxiaTheme.spacing.space2),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "내 가용 상태",
                color = AxiaTheme.semanticColors.textSecondary,
                style = AxiaTheme.typography.body,
            )
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    color = AxiaTheme.semanticColors.textSecondary,
                    style = AxiaTheme.typography.metadata,
                )
            }
        }
        AxiaBadge(label = label, style = badgeStyle)
    }
}

@Composable
private fun ActivitySection(
    title: String,
    emptyMessage: String,
    activities: List<DashboardActivity>,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(AxiaTheme.spacing.space5),
        verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
    ) {
        Text(
            text = title,
            color = AxiaTheme.semanticColors.textPrimary,
            style = AxiaTheme.typography.bodyEmphasis,
        )
        if (activities.isEmpty()) {
            Text(
                text = emptyMessage,
                color = AxiaTheme.semanticColors.textSecondary,
                style = AxiaTheme.typography.secondary,
            )
        } else {
            activities.forEach { activity ->
                Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1)) {
                    Text(
                        text = activity.title,
                        color = AxiaTheme.semanticColors.textPrimary,
                        style = AxiaTheme.typography.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = activity.context,
                        color = AxiaTheme.semanticColors.textSecondary,
                        style = AxiaTheme.typography.metadata,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardTaskPosition?.railColor(): Color =
    when (this) {
        DashboardTaskPosition.Server -> AxiaTheme.positionColors.server.representative
        DashboardTaskPosition.Frontend -> AxiaTheme.positionColors.frontend.representative
        DashboardTaskPosition.Cloud -> AxiaTheme.positionColors.cloud.representative
        DashboardTaskPosition.Design -> AxiaTheme.positionColors.design.representative
        null -> AxiaTheme.semanticColors.border
    }

private fun DashboardTask.sourceLabel(): String? {
    val repository = repositoryName ?: return null
    return if (sourceNumber == null) repository else "$repository #$sourceNumber"
}

private fun DashboardTask.assigneeStateLabels(): List<String> =
    when (val assignee = assignee) {
        DashboardAssignee.Unassigned -> {
            emptyList()
        }

        is DashboardAssignee.Assigned -> {
            buildList {
                if (DashboardAssigneeFlag.Inactive in assignee.flags) {
                    add("비활성 담당자")
                }
                if (DashboardAssigneeFlag.NonPoc in assignee.flags) {
                    add("비-POC 담당자")
                }
            }
        }
    }

private fun DashboardTask.accessibilityStateDescription(): String =
    buildList {
        add("제품 $productName")
        position?.let { add("포지션 ${it.label()}") }
        add(
            when (val assignee = assignee) {
                DashboardAssignee.Unassigned -> "담당자 없음"
                is DashboardAssignee.Assigned -> "담당자 ${assignee.name}"
            },
        )
        addAll(assigneeStateLabels())
        when (urgency) {
            DashboardTaskUrgency.Overdue -> add("지연")
            DashboardTaskUrgency.DueSoon -> add("마감 임박")
            DashboardTaskUrgency.Normal -> Unit
        }
    }.joinToString()

private fun DashboardTaskPosition.label(): String =
    when (this) {
        DashboardTaskPosition.Server -> "Server"
        DashboardTaskPosition.Frontend -> "Frontend"
        DashboardTaskPosition.Cloud -> "Cloud"
        DashboardTaskPosition.Design -> "Design"
    }

private fun DashboardTaskPosition.badgeStyle(): AxiaBadgeStyle =
    when (this) {
        DashboardTaskPosition.Server -> AxiaBadgeStyle.Server
        DashboardTaskPosition.Frontend -> AxiaBadgeStyle.Frontend
        DashboardTaskPosition.Cloud -> AxiaBadgeStyle.Cloud
        DashboardTaskPosition.Design -> AxiaBadgeStyle.Design
    }
