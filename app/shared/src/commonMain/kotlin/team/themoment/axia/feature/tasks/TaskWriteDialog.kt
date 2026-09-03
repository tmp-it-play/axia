package team.themoment.axia.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import team.themoment.axia.designsystem.component.AxiaBanner
import team.themoment.axia.designsystem.component.AxiaBannerStyle
import team.themoment.axia.designsystem.component.AxiaButton
import team.themoment.axia.designsystem.component.AxiaButtonStyle
import team.themoment.axia.designsystem.component.AxiaCheckbox
import team.themoment.axia.designsystem.component.AxiaDialog
import team.themoment.axia.designsystem.component.AxiaDivider
import team.themoment.axia.designsystem.component.AxiaSelect
import team.themoment.axia.designsystem.component.AxiaSelectOption
import team.themoment.axia.designsystem.component.AxiaTextField
import team.themoment.axia.designsystem.theme.AxiaTheme
import team.themoment.axia.domain.ProductId
import team.themoment.axia.members.CurrentUserResponse
import team.themoment.axia.members.MemberSummary
import team.themoment.axia.products.IntegrationListResponse
import team.themoment.axia.tasks.TaskDetail
import team.themoment.axia.tasks.TaskKind
import team.themoment.axia.tasks.TaskPriority
import team.themoment.axia.tasks.TaskStatus
import team.themoment.axia.tasks.TaskWarning

@Composable
fun TaskWriteDialog(
    mode: TaskWriteMode,
    detail: TaskDetail?,
    currentUser: CurrentUserResponse?,
    members: List<MemberSummary>,
    selectedProduct: ProductId,
    integrations: IntegrationListResponse?,
    loading: Boolean,
    saving: Boolean,
    pendingCommand: TaskWriteCommand?,
    error: String?,
    onProductChange: (ProductId) -> Unit,
    onSubmit: (TaskWriteCommand) -> Unit,
    onConfirmPending: () -> Unit,
    onDismiss: () -> Unit,
    readOnly: Boolean = false,
) {
    val creating = mode in setOf(TaskWriteMode.CREATE_ISSUE, TaskWriteMode.CREATE_DRAFT)
    val captured = remember(mode, detail?.task?.id, selectedProduct) { if (creating) null else detail }
    var draft by remember(mode, captured?.task?.id, selectedProduct) {
        mutableStateOf(initialTaskWriteDraft(mode, captured, selectedProduct, integrations))
    }
    var attempted by remember(mode, captured?.task?.id, selectedProduct) { mutableStateOf(false) }
    var submitted by remember(mode, captured?.task?.id, selectedProduct) { mutableStateOf(false) }
    var filter by remember(mode) { mutableStateOf("") }
    var initializedIntegrations by remember(mode, captured?.task?.id, selectedProduct) { mutableStateOf(integrations != null) }
    val enabled = !submitted && !saving && !loading && pendingCommand == null && !readOnly
    LaunchedEffect(saving, pendingCommand, error) {
        if (!saving && pendingCommand == null && error != null) submitted = false
    }
    val repositories = taskWriteRepositories(integrations, selectedProduct)
    val projects = taskWriteProjects(integrations, selectedProduct)
    LaunchedEffect(integrations, selectedProduct) {
        if (!initializedIntegrations && integrations != null && pendingCommand == null && !saving) {
            val initial = initialTaskWriteDraft(mode, captured, selectedProduct, integrations)
            if (draft.repositoryId == null) draft = draft.copy(repositoryId = initial.repositoryId)
            if (draft.projectId == null) draft = draft.copy(projectId = initial.projectId)
            initializedIntegrations = true
        }
    }
    val changedWhileOpen = !creating && captured?.task?.revision != detail?.task?.revision
    val validation =
        if (changedWhileOpen) {
            "태스크 원본이 변경되었습니다. 닫고 최신 태스크에서 다시 열어 주세요."
        } else {
            taskWriteValidation(
                mode,
                draft,
                captured,
                currentUser,
                members,
                selectedProduct,
                integrations,
                readOnly,
            )
        }
    val current = if (mode == TaskWriteMode.REVIEWERS) captured?.reviewers.orEmpty() else captured?.task?.assignees.orEmpty()
    val candidates =
        eligibleTaskCandidates(members, selectedProduct).filter { candidate ->
            mode == TaskWriteMode.REVIEWERS ||
                current.none { it.userId == candidate.id && TaskWarning.PREVIOUS_GITHUB_ACCOUNT !in it.warnings }
        }

    fun toggle(
        ids: Set<Long>,
        id: Long,
        selected: Boolean,
    ): Set<Long> = if (selected) ids + id else ids - id
    AxiaDialog(
        title = mode.title,
        onDismiss = onDismiss,
        dismissEnabled = !saving,
        footer = {
            if (pendingCommand != null) {
                AxiaButton(if (saving) "반영 여부 확인 중" else "반영 결과 확인", onConfirmPending, enabled = !saving && !readOnly)
            } else {
                AxiaButton(
                    if (saving) "GitHub에 반영 중" else mode.submitLabel,
                    {
                        attempted = true
                        if (validation == null && enabled) {
                            submitted = true
                            onSubmit(buildTaskWriteCommand(mode, draft, captured))
                        }
                    },
                    enabled = enabled && validation == null,
                    style =
                        if (mode ==
                            TaskWriteMode.ISSUE_CLOSE
                        ) {
                            AxiaButtonStyle.Danger
                        } else {
                            AxiaButtonStyle.Primary
                        },
                )
            }
        },
    ) {
        if (pendingCommand !=
            null
        ) {
            AxiaBanner(
                "반영 결과를 확인해야 합니다",
                description = "같은 요청의 결과만 다시 확인합니다. 중복 생성을 막기 위해 내용을 바꾸거나 새 작업을 보내지 않습니다.",
                style = AxiaBannerStyle.Warning,
                announceChanges = true,
            )
        }
        if (error != null) AxiaBanner("변경을 확인해 주세요", description = error, style = AxiaBannerStyle.Danger, announceChanges = true)
        if (loading) Text("현재 권한과 연동 정보를 불러오고 있습니다.", style = AxiaTheme.typography.body, color = AxiaTheme.semanticColors.textSecondary)
        if (validation != null && !loading &&
            pendingCommand == null
        ) {
            AxiaBanner(
                "저장 전 확인",
                description = validation,
                style =
                    if (attempted ||
                        changedWhileOpen
                    ) {
                        AxiaBannerStyle.Warning
                    } else {
                        AxiaBannerStyle.Info
                    },
            )
        }
        if (creating) {
            AxiaSelect(
                "제품",
                selectedProduct,
                currentUser
                    ?.capabilities
                    ?.writableProducts
                    .orEmpty()
                    .sortedBy {
                        it.ordinal
                    }.map { AxiaSelectOption(it, it.displayName) },
                onProductChange,
                enabled = enabled,
            )
        } else {
            captured?.let {
                Text(
                    "${it.task.productId.displayName} / ${it.task.title}",
                    style = AxiaTheme.typography.bodyEmphasis,
                    color = AxiaTheme.semanticColors.textPrimary,
                )
            }
        }
        if (mode in setOf(TaskWriteMode.CREATE_ISSUE, TaskWriteMode.CONVERT)) {
            AxiaSelect("저장소", draft.repositoryId, repositories.map { AxiaSelectOption<Long?>(it.id, "${it.owner}/${it.name}") }, { id ->
                val repository = repositories.singleOrNull { it.id == id }
                draft =
                    draft.copy(
                        repositoryId = id,
                        projectId =
                            if (mode ==
                                TaskWriteMode.CREATE_ISSUE
                            ) {
                                repository?.defaultProjectId?.takeIf { selected -> projects.any { it.id == selected } }
                            } else {
                                draft.projectId
                            },
                    )
            }, enabled = enabled)
            if (repositories.isEmpty()) HelpText("사용할 수 있는 저장소가 없습니다. 제품 연동을 먼저 설정해 주세요.")
        }
        if (mode in
            setOf(
                TaskWriteMode.CREATE_ISSUE,
                TaskWriteMode.CREATE_DRAFT,
                TaskWriteMode.FIELDS,
                TaskWriteMode.ADD_TO_PROJECT,
                TaskWriteMode.ARCHIVE,
            )
        ) {
            val available =
                projects.filter { board ->
                    when (mode) {
                        TaskWriteMode.FIELDS, TaskWriteMode.ARCHIVE -> {
                            captured
                                ?.task
                                ?.projects
                                .orEmpty()
                                .any { it.projectId == board.id }
                        }

                        TaskWriteMode.ADD_TO_PROJECT -> {
                            captured
                                ?.task
                                ?.projects
                                .orEmpty()
                                .none { it.projectId == board.id }
                        }

                        else -> {
                            true
                        }
                    }
                }
            AxiaSelect(
                "Project 보드",
                draft.projectId,
                buildList<AxiaSelectOption<Long?>> {
                    if (mode == TaskWriteMode.CREATE_ISSUE) add(AxiaSelectOption(null, "보드에 추가하지 않음"))
                    available.forEach { add(AxiaSelectOption(it.id, "#${it.number} ${it.title.orEmpty()}")) }
                },
                { id ->
                    val context = captured?.task?.projects?.singleOrNull { it.projectId == id }
                    draft =
                        draft.copy(
                            projectId = id,
                            status = context?.status ?: TaskStatus.UNSET,
                            priority =
                                context?.priority ?: TaskPriority.UNSET,
                            startDate = context?.startDate.orEmpty(),
                            targetDate = context?.targetDate.orEmpty(),
                            archived =
                                context?.archived != true,
                        )
                },
                enabled = enabled,
            )
            if (available.isEmpty() && mode != TaskWriteMode.CREATE_ISSUE) HelpText("이 작업에 사용할 수 있는 보드가 없습니다.")
        }
        if (creating || (mode == TaskWriteMode.EDIT && captured?.task?.kind != TaskKind.PULL_REQUEST)) {
            AxiaTextField(draft.title, { draft = draft.copy(title = it) }, "제목", enabled = enabled, supportingText = "최대 256자")
            AxiaTextField(
                draft.body,
                {
                    draft = draft.copy(body = it)
                },
                "본문",
                enabled = enabled,
                singleLine = false,
                modifier =
                    Modifier.heightIn(
                        min = 140.dp,
                    ),
                supportingText = "GitHub Markdown을 사용할 수 있습니다.",
            )
        }
        if (mode ==
            TaskWriteMode.COMMENT
        ) {
            AxiaTextField(draft.body, {
                draft = draft.copy(body = it)
            }, "댓글", enabled = enabled, singleLine = false, modifier = Modifier.heightIn(min = 160.dp))
        }
        if (mode == TaskWriteMode.CREATE_ISSUE || (mode == TaskWriteMode.EDIT && captured?.task?.kind != TaskKind.DRAFT)) {
            if (captured?.task?.kind == TaskKind.PULL_REQUEST) HelpText("PR 제목과 본문, Close/Reopen, Merge와 리뷰 제출은 GitHub에서 관리합니다.")
            AxiaTextField(
                draft.labels,
                { draft = draft.copy(labels = it) },
                "Label",
                enabled = enabled,
                singleLine = false,
                placeholder = "bug\ndocumentation",
                supportingText = "한 줄에 Label 이름 하나를 입력합니다. 이름 안의 쉼표는 그대로 유지됩니다.",
            )
            AxiaTextField(
                draft.milestone,
                { draft = draft.copy(milestone = it, clearMilestone = false) },
                "Milestone 번호",
                enabled =
                    enabled && !draft.clearMilestone,
                supportingText = captured?.milestone?.let { "현재: $it" } ?: "설정하지 않으려면 비워 두세요.",
            )
            if (captured?.milestone !=
                null
            ) {
                AxiaCheckbox("Milestone 해제", draft.clearMilestone, {
                    draft = draft.copy(clearMilestone = it)
                }, enabled = enabled, description = "선택하면 현재 Milestone을 명시적으로 제거합니다.")
            }
        }
        if (creating || mode in setOf(TaskWriteMode.ASSIGNEES, TaskWriteMode.REVIEWERS)) {
            AxiaDivider()
            Text(
                if (mode ==
                    TaskWriteMode.REVIEWERS
                ) {
                    "새 리뷰 요청"
                } else {
                    "새 담당자 추가"
                },
                style = AxiaTheme.typography.bodyEmphasis,
                color = AxiaTheme.semanticColors.textPrimary,
            )
            HelpText(
                "${selectedProduct.displayName}의 활성 POC와 유효한 GitHub 연결만 표시합니다." +
                    if (creating) " 담당자 없이도 만들 수 있습니다." else " 추가할 계정을 선택해 주세요.",
            )
            AxiaTextField(filter, { filter = it }, "구성원 검색", enabled = enabled, placeholder = "이름 또는 GitHub 계정")
            val visible = candidates.filter { it.displayName.contains(filter, true) || it.githubLogin.orEmpty().contains(filter, true) }
            if (visible.isEmpty()) HelpText("선택할 수 있는 구성원이 없습니다.")
            visible.forEach { candidate ->
                AxiaCheckbox("${candidate.displayName} (@${candidate.githubLogin})", candidate.id in draft.addUserIds, {
                    draft =
                        draft.copy(addUserIds = toggle(draft.addUserIds, candidate.id, it))
                }, enabled = enabled)
            }
            if (mode ==
                TaskWriteMode.REVIEWERS
            ) {
                AxiaCheckbox("선택한 기존 리뷰어에게 다시 요청", draft.reRequest, {
                    draft = draft.copy(reRequest = it)
                }, enabled = enabled, description = "재요청하면 해당 리뷰어의 요청 시점과 지연 주기가 새로 시작됩니다.")
            }
            if (!creating && current.isNotEmpty()) {
                AxiaDivider()
                Text("현재 계정 해제", style = AxiaTheme.typography.bodyEmphasis, color = AxiaTheme.semanticColors.textPrimary)
                HelpText("선택한 계정만 해제됩니다. 외부 담당자, 비-POC, 이전 계정은 선택하지 않으면 그대로 유지됩니다.")
                current.forEach { assignee ->
                    AxiaCheckbox(
                        "@${assignee.githubLogin}${assignee.displayName?.let { " — $it" }.orEmpty()}",
                        assignee.githubId in draft.removeGitHubIds,
                        {
                            draft =
                                draft.copy(removeGitHubIds = toggle(draft.removeGitHubIds, assignee.githubId, it))
                        },
                        enabled = enabled,
                        description =
                            assignee.warnings.takeIf { it.isNotEmpty() }?.joinToString { warning ->
                                warning.taskWriteLabel
                            },
                    )
                }
                if (draft.removeGitHubIds.isNotEmpty()) {
                    AxiaBanner(
                        "${draft.removeGitHubIds.size}개 계정을 해제합니다",
                        description = "진행 중인 작업의 담당 정보가 바뀝니다. 선택한 GitHub 계정을 확인해 주세요.",
                        style = AxiaBannerStyle.Warning,
                    )
                }
            }
        }
        if (mode == TaskWriteMode.FIELDS) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4),
                verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
            ) {
                AxiaSelect("Status", draft.status, TaskStatus.entries.map { AxiaSelectOption(it, it.taskWriteLabel) }, {
                    draft =
                        draft.copy(status = it)
                }, enabled = enabled)
                AxiaSelect("Priority", draft.priority, TaskPriority.entries.map { AxiaSelectOption(it, it.taskWriteLabel) }, {
                    draft =
                        draft.copy(priority = it)
                }, enabled = enabled)
            }
            AxiaTextField(draft.startDate, {
                draft = draft.copy(startDate = it)
            }, "Start date", enabled = enabled, placeholder = "YYYY-MM-DD", supportingText = "비워 두면 날짜를 해제합니다.")
            AxiaTextField(draft.targetDate, {
                draft = draft.copy(targetDate = it)
            }, "Target date", enabled = enabled, placeholder = "YYYY-MM-DD", supportingText = "마감일은 한국 시간 23:59로 계산됩니다.")
            if (captured?.task?.kind == TaskKind.DRAFT) HelpText("Draft의 Done은 완료 기여 통계에 포함되지 않습니다.")
        }
        when (mode) {
            TaskWriteMode.CONVERT -> {
                AxiaBanner(
                    "실제 저장소 Issue로 변환합니다",
                    description = "변환 시점부터 Issue 처리 시간과 지연 시간이 시작됩니다. Draft를 영구 삭제하지 않습니다.",
                )
            }

            TaskWriteMode.ARCHIVE -> {
                AxiaCheckbox("보관함으로 이동", draft.archived, {
                    draft = draft.copy(archived = it)
                }, enabled = enabled, description = "선택을 해제하면 항목을 보드로 복구합니다. 원본 Issue·PR은 닫히거나 삭제되지 않습니다.")
            }

            TaskWriteMode.ISSUE_CLOSE -> {
                AxiaBanner(
                    "GitHub 원본 Issue를 닫습니다",
                    description = "현재 담당자의 완료 기여가 기록될 수 있습니다. Project의 Done 변경과는 별개의 작업입니다.",
                    style = AxiaBannerStyle.Warning,
                )
            }

            TaskWriteMode.ISSUE_REOPEN -> {
                HelpText("GitHub 원본 Issue를 다시 엽니다. 이후 다시 닫히면 새 완료 이벤트로 기록됩니다.")
            }

            else -> {}
        }
    }
}

@Composable
private fun HelpText(text: String) {
    Text(text, Modifier.fillMaxWidth(), style = AxiaTheme.typography.secondary, color = AxiaTheme.semanticColors.textSecondary)
}

internal val TaskStatus.taskWriteLabel: String get() =
    when (this) {
        TaskStatus.TODO -> "Todo"
        TaskStatus.IN_PROGRESS -> "In Progress"
        TaskStatus.DONE -> "Done"
        TaskStatus.UNSET -> "설정하지 않음"
    }
internal val TaskPriority.taskWriteLabel: String get() =
    when (this) {
        TaskPriority.HIGH -> "High"
        TaskPriority.MEDIUM -> "Medium"
        TaskPriority.LOW -> "Low"
        TaskPriority.IN_REVIEW -> "In review"
        TaskPriority.UNSET -> "설정하지 않음"
    }
private val TaskWarning.taskWriteLabel: String get() =
    when (this) {
        TaskWarning.NON_POC_ASSIGNEE -> "현재 제품의 POC 아님"
        TaskWarning.INACTIVE_ASSIGNEE -> "비활성 구성원"
        TaskWarning.PREVIOUS_GITHUB_ACCOUNT -> "이전 GitHub 계정"
        TaskWarning.UNASSIGNED -> "담당자 없음"
    }
