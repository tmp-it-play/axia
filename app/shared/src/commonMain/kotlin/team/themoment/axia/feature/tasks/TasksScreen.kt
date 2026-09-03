package team.themoment.axia.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import team.themoment.axia.app.TaskFilters
import team.themoment.axia.designsystem.component.AxiaBadge
import team.themoment.axia.designsystem.component.AxiaBadgeStyle
import team.themoment.axia.designsystem.component.AxiaButton
import team.themoment.axia.designsystem.component.AxiaButtonStyle
import team.themoment.axia.designsystem.component.AxiaDivider
import team.themoment.axia.designsystem.component.AxiaEmptyState
import team.themoment.axia.designsystem.component.AxiaPanel
import team.themoment.axia.designsystem.component.AxiaPanelTransition
import team.themoment.axia.designsystem.component.AxiaSelect
import team.themoment.axia.designsystem.component.AxiaSelectOption
import team.themoment.axia.designsystem.component.AxiaTextField
import team.themoment.axia.designsystem.theme.AxiaTheme
import team.themoment.axia.domain.ProductId
import team.themoment.axia.domain.TechnicalPosition
import team.themoment.axia.members.MemberSummary
import team.themoment.axia.platform.AxiaTime
import team.themoment.axia.tasks.TaskDetail
import team.themoment.axia.tasks.TaskKind
import team.themoment.axia.tasks.TaskPriority
import team.themoment.axia.tasks.TaskStatus
import team.themoment.axia.tasks.TaskSummary
import team.themoment.axia.tasks.TaskUrgency
import team.themoment.axia.tasks.TaskWarning

@Composable
fun TasksScreen(
    tasks: List<TaskSummary>,
    members: List<MemberSummary>,
    filters: TaskFilters,
    selectedTask: TaskDetail?,
    loading: Boolean,
    detailLoading: Boolean,
    onFiltersChange: (TaskFilters) -> Unit,
    onSelectTask: (TaskSummary) -> Unit,
    onRefresh: () -> Unit,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
    detailActions: @Composable (TaskDetail) -> Unit = {},
    createActions: @Composable () -> Unit = {},
) {
    var search by remember(filters.search) { mutableStateOf(filters.search) }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
            verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
        ) {
            AxiaSelect(
                "제품",
                filters.productId,
                buildList<AxiaSelectOption<ProductId?>> {
                    add(AxiaSelectOption(null, "전 제품"))
                    ProductId.entries.forEach { add(AxiaSelectOption(it, it.displayName)) }
                },
                onChange = { onFiltersChange(filters.copy(productId = it)) },
            )
            AxiaSelect(
                "원본 유형",
                filters.kind,
                buildList<AxiaSelectOption<TaskKind?>> {
                    add(AxiaSelectOption(null, "모든 유형"))
                    TaskKind.entries.forEach { add(AxiaSelectOption(it, it.label)) }
                },
                onChange = { onFiltersChange(filters.copy(kind = it)) },
            )
            AxiaSelect(
                "담당자",
                filters.userId,
                buildList<AxiaSelectOption<Long?>> {
                    add(AxiaSelectOption(null, "모든 구성원"))
                    members.forEach { add(AxiaSelectOption(it.id, it.displayName)) }
                },
                onChange = { onFiltersChange(filters.copy(userId = it)) },
            )
            AxiaSelect(
                "기술 포지션",
                filters.position,
                listOf(AxiaSelectOption<TechnicalPosition?>(null, "모든 포지션")) +
                    TechnicalPosition.entries.map { AxiaSelectOption<TechnicalPosition?>(it, it.displayName) },
                onChange = { onFiltersChange(filters.copy(position = it)) },
            )
            AxiaSelect("할당", filters.unassigned, listOf(AxiaSelectOption(false, "전체"), AxiaSelectOption(true, "담당자 없음")), onChange = {
                onFiltersChange(filters.copy(unassigned = it))
            })
            AxiaSelect(
                "진행 상태",
                filters.includeClosed,
                listOf(
                    AxiaSelectOption(false, "진행 중"),
                    AxiaSelectOption(true, "완료 포함"),
                ),
                onChange = {
                    onFiltersChange(filters.copy(includeClosed = it))
                },
            )
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2)) {
            AxiaTextField(search, { search = it }, "태스크 검색", modifier = Modifier.weight(1f), placeholder = "제목 또는 저장소")
            AxiaButton("검색", { onFiltersChange(filters.copy(search = search)) }, style = AxiaButtonStyle.Secondary)
            AxiaButton(if (loading) "불러오는 중" else "새로고침", onRefresh, enabled = !loading, style = AxiaButtonStyle.Tertiary)
            createActions()
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space5)) {
            AxiaPanel(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(0.dp)) {
                if (tasks.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(AxiaTheme.spacing.space6), contentAlignment = Alignment.Center) {
                        AxiaEmptyState(if (loading) "태스크를 불러오고 있어요" else "조건에 맞는 태스크가 없어요", "제품·담당자 필터를 바꾸거나 연결된 저장소를 확인해 주세요.")
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        tasks.groupBy { it.productId }.forEach { (product, productTasks) ->
                            item(key = "product:${product.name}") {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(
                                            AxiaTheme.semanticColors.surfaceSubtle,
                                        ).padding(AxiaTheme.spacing.space4),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        product.displayName,
                                        Modifier.semantics {
                                            heading()
                                        },
                                        style = AxiaTheme.typography.bodyEmphasis,
                                        color = AxiaTheme.semanticColors.textPrimary,
                                    )
                                    Text(
                                        "${productTasks.size}개",
                                        style = AxiaTheme.typography.metadata,
                                        color = AxiaTheme.semanticColors.textSecondary,
                                    )
                                }
                            }
                            items(productTasks, key = { it.rowKey }) { task ->
                                TaskRow(task, selectedTask?.task?.rowKey == task.rowKey, { onSelectTask(task) })
                                AxiaDivider()
                            }
                        }
                    }
                }
            }
            AxiaPanel(Modifier.width(AxiaTheme.dimensions.detailPanelWidth).fillMaxHeight()) {
                AxiaPanelTransition(
                    contentKey = if (detailLoading) null else selectedTask?.task?.rowKey,
                    modifier = Modifier.fillMaxSize(),
                    animate = !detailLoading && selectedTask != null,
                ) {
                    when {
                        detailLoading -> {
                            AxiaEmptyState("상세 내용을 불러오고 있어요", "잠시만 기다려 주세요.")
                        }

                        selectedTask != null -> {
                            TaskDetailPanel(selectedTask, onOpenSource, {
                                // Review rows contain the reviewer, not the PR's assignees. Fetch the canonical
                                // detail before offering a PR edit so the two assignment scopes never mix.
                                onSelectTask(selectedTask.task.copy(kind = TaskKind.PULL_REQUEST, assignmentKey = null))
                            }, detailActions)
                        }

                        else -> {
                            AxiaEmptyState("태스크를 선택해 주세요", "원본 정보와 보드 상태를 오른쪽에서 확인할 수 있습니다.")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: TaskSummary,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AxiaTheme.dimensions.taskRowMinHeight)
            .background(if (selected) AxiaTheme.semanticColors.surfaceSubtle else AxiaTheme.semanticColors.surface)
            .border(
                AxiaTheme.dimensions.hairline,
                if (TaskWarning.UNASSIGNED in
                    task.warnings
                ) {
                    AxiaTheme.semanticColors.warning.content
                } else {
                    AxiaTheme.semanticColors.surface
                },
            ).clickable(role = Role.Button, onClick = onSelect)
            .padding(AxiaTheme.spacing.space4),
        verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AxiaBadge(task.kind.label, if (task.kind == TaskKind.DRAFT) AxiaBadgeStyle.Warning else AxiaBadgeStyle.Info)
            Text(
                task.title,
                Modifier.weight(1f),
                style = AxiaTheme.typography.bodyEmphasis,
                color = AxiaTheme.semanticColors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            listOfNotNull(
                task.repositoryName,
                task.number?.let {
                    "#$it"
                },
                task.targetDate?.let {
                    "마감 $it"
                },
            ).joinToString(" · "),
            style = AxiaTheme.typography.metadata,
            color = AxiaTheme.semanticColors.textSecondary,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
            verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1),
        ) {
            if (task.urgency !=
                TaskUrgency.NORMAL
            ) {
                AxiaBadge(
                    if (task.urgency ==
                        TaskUrgency.OVERDUE
                    ) {
                        "지연"
                    } else {
                        "마감 임박"
                    },
                    if (task.urgency ==
                        TaskUrgency.OVERDUE
                    ) {
                        AxiaBadgeStyle.Danger
                    } else {
                        AxiaBadgeStyle.Warning
                    },
                )
            }
            task.warnings.forEach { warning -> AxiaBadge(warning.label, AxiaBadgeStyle.Warning) }
            task.assignees.forEach { assignee ->
                Text(
                    assignee.displayName ?: assignee.githubLogin,
                    style = AxiaTheme.typography.secondary,
                    color = AxiaTheme.semanticColors.textSecondary,
                )
            }
            task.assignees.flatMap { it.positions }.distinct().sortedBy { it.ordinal }.forEach { position ->
                AxiaBadge(position.displayName, position.badgeStyle)
            }
        }
    }
}

@Composable
private fun TaskDetailPanel(
    detail: TaskDetail,
    onOpenSource: (String) -> Unit,
    onOpenCanonicalTask: () -> Unit,
    actions: @Composable (TaskDetail) -> Unit,
) {
    val task = detail.task
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2)) {
            AxiaBadge(task.kind.label, AxiaBadgeStyle.Info)
            AxiaBadge(task.productId.displayName, AxiaBadgeStyle.Product)
        }
        Text(
            task.title,
            Modifier.semantics { heading() },
            style = AxiaTheme.typography.sectionTitle,
            color = AxiaTheme.semanticColors.textPrimary,
        )
        if (task.kind ==
            TaskKind.DRAFT
        ) {
            Text(
                "저장소에 발행되지 않은 Project 초안입니다. Done으로 바꾸어도 완료 기여에는 포함하지 않습니다.",
                style = AxiaTheme.typography.secondary,
                color = AxiaTheme.semanticColors.textSecondary,
            )
        }
        AxiaButton("GitHub 원본 열기", { onOpenSource(task.url) }, style = AxiaButtonStyle.Secondary)
        if (task.kind == TaskKind.REVIEW_REQUEST) AxiaButton("PR 상세 및 작업 관리", onOpenCanonicalTask, style = AxiaButtonStyle.Secondary)
        Text(detail.body.ifBlank { "본문이 없습니다." }, style = AxiaTheme.typography.body, color = AxiaTheme.semanticColors.textPrimary)
        Text(
            "생성 ${AxiaTime.label(task.createdAtEpochMillis)}",
            style = AxiaTheme.typography.metadata,
            color = AxiaTheme.semanticColors.textSecondary,
        )
        task.reviewRequestedAtEpochMillis?.let { DetailText("리뷰 요청 시각", AxiaTime.label(it)) }
        if (task.assignees.isNotEmpty()) {
            DetailText(
                if (task.kind ==
                    TaskKind.REVIEW_REQUEST
                ) {
                    "이 리뷰 요청의 담당자"
                } else {
                    "담당자"
                },
                task.assignees.joinToString { it.displayName ?: it.githubLogin },
            )
        }
        task.warnings.forEach { Text(it.label, style = AxiaTheme.typography.secondary, color = AxiaTheme.semanticColors.warning.content) }
        detail.reviewers.takeIf { it.isNotEmpty() }?.let { reviewers ->
            DetailText(
                "리뷰 요청",
                reviewers.joinToString {
                    it.displayName
                        ?: it.githubLogin
                },
            )
        }
        if (detail.labels.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
                verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1),
            ) {
                detail.labels.forEach { AxiaBadge(it, AxiaBadgeStyle.Metadata) }
            }
        }
        detail.milestone?.let { DetailText("Milestone", it) }
        task.projects.forEach { board ->
            AxiaDivider()
            DetailText("Project #${board.projectNumber}", if (board.archived) "보관됨" else board.status.label)
            DetailText("Priority", board.priority.label)
            DetailText("시작일 / 마감일", "${board.startDate ?: "미지정"} / ${board.targetDate ?: "미지정"}")
        }
        (detail.linkedIssues + detail.linkedPullRequests + listOfNotNull(detail.parent) + detail.subIssues).forEach { link ->
            AxiaButton(link.title, { onOpenSource(link.url) }, style = AxiaButtonStyle.Tertiary)
        }
        actions(detail)
        if (detail.comments.isNotEmpty()) {
            AxiaDivider()
            DetailText("댓글", "${detail.comments.size}개")
            detail.comments.forEach { comment ->
                Text(
                    "${comment.author} · ${AxiaTime.label(comment.createdAtEpochMillis)}",
                    style = AxiaTheme.typography.metadata,
                    color = AxiaTheme.semanticColors.textSecondary,
                )
                Text(comment.body, style = AxiaTheme.typography.body, color = AxiaTheme.semanticColors.textPrimary)
            }
        }
    }
}

@Composable
private fun DetailText(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1)) {
        Text(label, style = AxiaTheme.typography.metadata, color = AxiaTheme.semanticColors.textSecondary)
        Text(value, style = AxiaTheme.typography.body, color = AxiaTheme.semanticColors.textPrimary)
    }
}

private val TaskSummary.rowKey: String get() = "$id:${kind.name}:$assignmentKey"
private val TaskKind.label: String get() =
    when (this) {
        TaskKind.ISSUE -> "Issue"
        TaskKind.PULL_REQUEST -> "PR"
        TaskKind.REVIEW_REQUEST -> "리뷰 요청"
        TaskKind.DRAFT -> "DRAFT"
    }
private val TaskWarning.label: String get() =
    when (this) {
        TaskWarning.UNASSIGNED -> "담당자 없음"
        TaskWarning.NON_POC_ASSIGNEE -> "비-POC 담당자"
        TaskWarning.INACTIVE_ASSIGNEE -> "비활성 담당자"
        TaskWarning.PREVIOUS_GITHUB_ACCOUNT -> "이전 GitHub 계정 담당"
    }
private val TaskStatus.label: String get() =
    when (this) {
        TaskStatus.TODO -> "할 일"
        TaskStatus.IN_PROGRESS -> "진행 중"
        TaskStatus.DONE -> "Done"
        TaskStatus.UNSET -> "미지정"
    }
private val TaskPriority.label: String get() =
    when (this) {
        TaskPriority.HIGH -> "높음"
        TaskPriority.MEDIUM -> "보통"
        TaskPriority.LOW -> "낮음"
        TaskPriority.IN_REVIEW -> "리뷰 중"
        TaskPriority.UNSET -> "미지정"
    }
private val TechnicalPosition.badgeStyle: AxiaBadgeStyle get() =
    when (this) {
        TechnicalPosition.SERVER -> AxiaBadgeStyle.Server
        TechnicalPosition.FRONTEND -> AxiaBadgeStyle.Frontend
        TechnicalPosition.CLOUD -> AxiaBadgeStyle.Cloud
        TechnicalPosition.DESIGN -> AxiaBadgeStyle.Design
    }
