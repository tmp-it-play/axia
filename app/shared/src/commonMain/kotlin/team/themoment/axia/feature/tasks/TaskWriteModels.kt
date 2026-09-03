package team.themoment.axia.feature.tasks

import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import team.themoment.axia.domain.ProductId
import team.themoment.axia.members.CurrentUserResponse
import team.themoment.axia.members.MemberGitHubStatus
import team.themoment.axia.members.MemberSummary
import team.themoment.axia.platform.AxiaTime
import team.themoment.axia.products.IntegrationAccess
import team.themoment.axia.products.IntegrationListResponse
import team.themoment.axia.products.ProjectIntegration
import team.themoment.axia.products.RepositoryIntegration
import team.themoment.axia.tasks.AddTaskToProjectRequest
import team.themoment.axia.tasks.AssignTaskRequest
import team.themoment.axia.tasks.ConvertDraftRequest
import team.themoment.axia.tasks.CreateCommentRequest
import team.themoment.axia.tasks.CreateDraftRequest
import team.themoment.axia.tasks.CreateIssueRequest
import team.themoment.axia.tasks.ProjectFieldsUpdate
import team.themoment.axia.tasks.ProjectItemArchiveRequest
import team.themoment.axia.tasks.ReviewRequestUpdate
import team.themoment.axia.tasks.TaskDetail
import team.themoment.axia.tasks.TaskKind
import team.themoment.axia.tasks.TaskMutationRequest
import team.themoment.axia.tasks.TaskPriority
import team.themoment.axia.tasks.TaskStatus
import team.themoment.axia.tasks.TaskWarning
import team.themoment.axia.tasks.UpdateDraftRequest
import team.themoment.axia.tasks.UpdateIssueRequest
import team.themoment.axia.tasks.UpdatePullRequestRequest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

enum class TaskWriteMode(
    val title: String,
    val submitLabel: String,
) {
    CREATE_ISSUE("Issue 만들기", "Issue 만들기"),
    CREATE_DRAFT("Project Draft 만들기", "Draft 만들기"),
    EDIT("태스크 수정", "변경 저장"),
    COMMENT("댓글 작성", "댓글 등록"),
    ASSIGNEES("담당자 관리", "담당자 변경"),
    REVIEWERS("리뷰 요청 관리", "리뷰 요청 변경"),
    FIELDS("Project 필드 수정", "필드 저장"),
    ADD_TO_PROJECT("Project 보드에 추가", "보드에 추가"),
    CONVERT("Draft를 Issue로 변환", "Issue로 변환"),
    ARCHIVE("Project 항목 보관 관리", "보관 상태 변경"),
    ISSUE_CLOSE("Issue 닫기", "Issue 닫기"),
    ISSUE_REOPEN("Issue 다시 열기", "Issue 다시 열기"),
}

/** One user intent, kept in memory only. Confirmation must reuse every byte and both identifiers. */
class TaskWriteCommand(
    val operationId: String,
    val issuedAtEpochMillis: Long,
    val method: String,
    val path: String,
    val body: String,
    val taskId: Long?,
) {
    override fun toString(): String = "TaskWriteCommand(method=$method, path=$path, body=[REDACTED])"
}

data class TaskWriteDraft(
    val title: String = "",
    val body: String = "",
    val repositoryId: Long? = null,
    val projectId: Long? = null,
    val labels: String = "",
    val milestone: String = "",
    val clearMilestone: Boolean = false,
    val addUserIds: Set<Long> = emptySet(),
    val removeGitHubIds: Set<Long> = emptySet(),
    val reRequest: Boolean = false,
    val status: TaskStatus = TaskStatus.UNSET,
    val priority: TaskPriority = TaskPriority.UNSET,
    val startDate: String = "",
    val targetDate: String = "",
    val archived: Boolean = true,
) {
    override fun toString(): String = "TaskWriteDraft(repositoryId=$repositoryId, projectId=$projectId, content=[REDACTED])"
}

fun eligibleTaskCandidates(
    members: List<MemberSummary>,
    product: ProductId,
): List<MemberSummary> =
    members
        .filter { it.isActive && product in it.pocs && it.githubStatus == MemberGitHubStatus.MEMBER && !it.githubLogin.isNullOrBlank() }
        .sortedWith(compareBy<MemberSummary> { it.displayName }.thenBy { it.id })

fun taskWriteRepositories(
    integrations: IntegrationListResponse?,
    product: ProductId,
): List<RepositoryIntegration> =
    integrations?.repositories.orEmpty().filter {
        it.productId == product && it.enabled && it.access == IntegrationAccess.AVAILABLE &&
            it.nodeId != null
    }

fun taskWriteProjects(
    integrations: IntegrationListResponse?,
    product: ProductId,
): List<ProjectIntegration> =
    integrations?.projects.orEmpty().filter {
        it.productId == product && it.enabled && it.access == IntegrationAccess.AVAILABLE &&
            it.nodeId != null &&
            it.number != 19
    }

fun initialTaskWriteDraft(
    mode: TaskWriteMode,
    detail: TaskDetail?,
    product: ProductId,
    integrations: IntegrationListResponse?,
): TaskWriteDraft {
    val repository =
        taskWriteRepositories(integrations, product).firstOrNull { it.id == detail?.task?.repositoryId }
            ?: taskWriteRepositories(integrations, product).firstOrNull()
    val projects = taskWriteProjects(integrations, product)
    val context =
        detail?.task?.projects?.firstOrNull { it.projectId == repository?.defaultProjectId } ?: detail?.task?.projects?.firstOrNull()
    val project =
        when (mode) {
            TaskWriteMode.CREATE_ISSUE -> {
                repository?.defaultProjectId?.takeIf { id -> projects.any { it.id == id } }
            }

            TaskWriteMode.CREATE_DRAFT -> {
                projects.firstOrNull()?.id
            }

            TaskWriteMode.ADD_TO_PROJECT -> {
                projects
                    .firstOrNull { board ->
                        detail
                            ?.task
                            ?.projects
                            .orEmpty()
                            .none { it.projectId == board.id }
                    }?.id
            }

            else -> {
                context?.projectId
            }
        }
    return TaskWriteDraft(
        title = if (mode == TaskWriteMode.EDIT) detail?.task?.title.orEmpty() else "",
        body = if (mode == TaskWriteMode.EDIT) detail?.body.orEmpty() else "",
        repositoryId = repository?.id,
        projectId = project,
        labels = if (mode == TaskWriteMode.EDIT) detail?.labels.orEmpty().joinToString("\n") else "",
        milestone = if (mode == TaskWriteMode.EDIT) detail?.milestoneNumber?.toString().orEmpty() else "",
        status = context?.status ?: TaskStatus.UNSET,
        priority = context?.priority ?: TaskPriority.UNSET,
        startDate = context?.startDate.orEmpty(),
        targetDate = context?.targetDate.orEmpty(),
        archived = context?.archived != true,
    )
}

fun taskWriteAllowed(
    mode: TaskWriteMode,
    detail: TaskDetail?,
): Boolean =
    when (mode) {
        TaskWriteMode.CREATE_ISSUE, TaskWriteMode.CREATE_DRAFT -> {
            true
        }

        TaskWriteMode.EDIT, TaskWriteMode.ASSIGNEES, TaskWriteMode.FIELDS, TaskWriteMode.ARCHIVE -> {
            detail?.task?.kind in
                setOf(TaskKind.ISSUE, TaskKind.PULL_REQUEST, TaskKind.DRAFT)
        }

        TaskWriteMode.COMMENT, TaskWriteMode.ADD_TO_PROJECT -> {
            detail?.task?.kind in setOf(TaskKind.ISSUE, TaskKind.PULL_REQUEST)
        }

        TaskWriteMode.REVIEWERS -> {
            detail?.task?.kind == TaskKind.PULL_REQUEST
        }

        TaskWriteMode.CONVERT -> {
            detail?.task?.kind == TaskKind.DRAFT
        }

        TaskWriteMode.ISSUE_CLOSE -> {
            detail?.task?.kind == TaskKind.ISSUE && detail.task.isOpen
        }

        TaskWriteMode.ISSUE_REOPEN -> {
            detail?.task?.kind == TaskKind.ISSUE && !detail.task.isOpen
        }
    }

fun taskWriteValidation(
    mode: TaskWriteMode,
    draft: TaskWriteDraft,
    detail: TaskDetail?,
    currentUser: CurrentUserResponse?,
    members: List<MemberSummary>,
    product: ProductId,
    integrations: IntegrationListResponse?,
    readOnly: Boolean = false,
): String? {
    val creating = mode in setOf(TaskWriteMode.CREATE_ISSUE, TaskWriteMode.CREATE_DRAFT)
    if (readOnly) return "GitHub 연결이 복구될 때까지 읽기 전용입니다."
    if (currentUser == null || !currentUser.member.isActive ||
        currentUser.member.githubStatus != MemberGitHubStatus.MEMBER
    ) {
        return "현재 로그인과 GitHub 연결을 확인해 주세요."
    }
    if (!taskWriteAllowed(mode, detail)) return "이 태스크 유형에서는 지원하지 않는 작업입니다."
    if ((creating && product !in currentUser.capabilities.writableProducts) ||
        (!creating && detail?.task?.canWrite != true)
    ) {
        return "이 제품 또는 태스크를 변경할 권한이 없습니다."
    }
    if (!creating && detail?.task?.revision?.matches(Regex("[0-9a-f]{64}")) != true) return "최신 태스크를 다시 불러와 주세요."
    if (integrations == null) return "저장소와 보드 정보를 불러오고 있습니다."
    if (!creating && detail?.task?.productId != product) return "태스크의 제품을 다시 확인해 주세요."
    val repositories = taskWriteRepositories(integrations, product)
    val projects = taskWriteProjects(integrations, product)
    if (mode in setOf(TaskWriteMode.CREATE_ISSUE, TaskWriteMode.CONVERT) &&
        repositories.none { it.id == draft.repositoryId }
    ) {
        return "사용할 수 있는 저장소를 선택해 주세요."
    }
    if (mode in setOf(TaskWriteMode.CREATE_DRAFT, TaskWriteMode.FIELDS, TaskWriteMode.ADD_TO_PROJECT, TaskWriteMode.ARCHIVE) &&
        projects.none { it.id == draft.projectId }
    ) {
        return "사용할 수 있는 Project 보드를 선택해 주세요."
    }
    if (mode == TaskWriteMode.CREATE_ISSUE && draft.projectId != null &&
        projects.none { it.id == draft.projectId }
    ) {
        return "선택한 보드가 비활성화되었습니다."
    }
    if (mode in setOf(TaskWriteMode.FIELDS, TaskWriteMode.ARCHIVE) &&
        detail
            ?.task
            ?.projects
            .orEmpty()
            .none { it.projectId == draft.projectId }
    ) {
        return "이 태스크가 포함된 보드를 선택해 주세요."
    }
    if (mode == TaskWriteMode.ADD_TO_PROJECT &&
        detail
            ?.task
            ?.projects
            .orEmpty()
            .any { it.projectId == draft.projectId }
    ) {
        return "이미 추가된 보드입니다."
    }
    val editsText = creating || (mode == TaskWriteMode.EDIT && detail?.task?.kind != TaskKind.PULL_REQUEST)
    if (editsText && (draft.title.isBlank() || draft.title.trim().length > 256)) return "제목은 1~256자로 입력해 주세요."
    if ((editsText || mode == TaskWriteMode.COMMENT) &&
        (draft.body.length > 65_536 || '\u0000' in draft.body || '\u0000' in draft.title)
    ) {
        return "본문은 65,536자 이내로 입력해 주세요."
    }
    if (mode == TaskWriteMode.COMMENT && draft.body.isBlank()) return "댓글 내용을 입력해 주세요."
    if (mode == TaskWriteMode.CREATE_ISSUE || (mode == TaskWriteMode.EDIT && detail?.task?.kind != TaskKind.DRAFT)) {
        if (draft.labels
                .split('\n')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .any { it.length > 50 || '\u0000' in it } ||
            labels(draft).size > 100
        ) {
            return "Label은 이름당 50자, 최대 100개까지 입력해 주세요."
        }
        if (!draft.clearMilestone && draft.milestone.isNotBlank() &&
            draft.milestone.toIntOrNull()?.takeIf { it > 0 } == null
        ) {
            return "Milestone 번호는 양의 정수로 입력해 주세요."
        }
        if (mode == TaskWriteMode.EDIT && detail?.milestone != null && draft.milestone.isBlank() &&
            !draft.clearMilestone
        ) {
            return "기존 Milestone을 제거하려면 ‘Milestone 해제’를 선택해 주세요."
        }
    }
    val candidates = eligibleTaskCandidates(members, product).map { it.id }.toSet()
    if (draft.addUserIds.size > 10 || !candidates.containsAll(draft.addUserIds)) return "새 담당자는 해당 제품의 활성 POC에서 최대 10명까지 선택해 주세요."
    val current = if (mode == TaskWriteMode.REVIEWERS) detail?.reviewers.orEmpty() else detail?.task?.assignees.orEmpty()
    if (mode in setOf(TaskWriteMode.ASSIGNEES, TaskWriteMode.REVIEWERS) && draft.addUserIds.isEmpty() &&
        draft.removeGitHubIds.isEmpty()
    ) {
        return "추가하거나 해제할 계정을 선택해 주세요."
    }
    if (draft.removeGitHubIds.size > 10) return "한 번에 최대 10개 계정을 해제할 수 있습니다."
    val addedCount =
        draft.addUserIds.count { userId ->
            current.none {
                it.userId == userId &&
                    TaskWarning.PREVIOUS_GITHUB_ACCOUNT !in it.warnings
            }
        }
    if (draft.addUserIds.isNotEmpty() &&
        current.count { it.githubId !in draft.removeGitHubIds } + addedCount > 10
    ) {
        return "기존 계정을 포함해 담당자는 최대 10명까지 추가할 수 있습니다."
    }
    if (!current.map { it.githubId }.toSet().containsAll(draft.removeGitHubIds)) return "해제할 담당자 목록이 변경되었습니다. 최신 상태를 확인해 주세요."
    if (current.any {
            it.userId in draft.addUserIds && it.githubId in draft.removeGitHubIds &&
                TaskWarning.PREVIOUS_GITHUB_ACCOUNT !in it.warnings
        }
    ) {
        return "같은 계정을 추가와 해제에 동시에 선택할 수 없습니다."
    }
    if (mode == TaskWriteMode.FIELDS &&
        listOf(draft.startDate, draft.targetDate).any {
            it.isNotBlank() &&
                (!it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) || runCatching { LocalDate.parse(it) }.isFailure)
        }
    ) {
        return "날짜는 YYYY-MM-DD 형식으로 입력해 주세요."
    }
    return null
}

/** Called only by an enabled submit action; not by recomposition, retry, or confirmation. */
@OptIn(ExperimentalUuidApi::class)
fun buildTaskWriteCommand(
    mode: TaskWriteMode,
    draft: TaskWriteDraft,
    detail: TaskDetail?,
    operationId: String = Uuid.random().toString(),
    issuedAtEpochMillis: Long = AxiaTime.nowEpochMillis(),
): TaskWriteCommand {
    require(taskWriteAllowed(mode, detail))
    val id = if (mode in setOf(TaskWriteMode.CREATE_ISSUE, TaskWriteMode.CREATE_DRAFT)) null else detail?.task?.id
    val revision = detail?.task?.revision
    val updated = detail?.task?.updatedAtEpochMillis ?: 0
    val milestone = if (draft.clearMilestone || draft.milestone.isBlank()) null else requireNotNull(draft.milestone.toIntOrNull())

    fun command(
        method: String,
        path: String,
        body: String,
    ) = TaskWriteCommand(operationId, issuedAtEpochMillis, method, path, body, id)
    return when (mode) {
        TaskWriteMode.CREATE_ISSUE -> {
            command(
                "POST",
                "/v1/issues",
                Json.encodeToString(
                    CreateIssueRequest(
                        operationId,
                        requireNotNull(draft.repositoryId),
                        draft.title.trim(),
                        draft.body,
                        draft.addUserIds,
                        labels(draft),
                        milestone,
                        draft.projectId,
                        issuedAtEpochMillis,
                    ),
                ),
            )
        }

        TaskWriteMode.CREATE_DRAFT -> {
            command(
                "POST",
                "/v1/projects/${requireNotNull(draft.projectId)}/drafts",
                Json.encodeToString(CreateDraftRequest(operationId, draft.title.trim(), draft.body, draft.addUserIds, issuedAtEpochMillis)),
            )
        }

        TaskWriteMode.EDIT -> {
            when (detail!!.task.kind) {
                TaskKind.ISSUE -> {
                    command(
                        "PATCH",
                        "/v1/issues/$id",
                        Json.encodeToString(
                            UpdateIssueRequest(
                                operationId,
                                updated,
                                draft.title.trim(),
                                draft.body,
                                labels(draft),
                                milestone,
                                revision,
                                issuedAtEpochMillis,
                            ),
                        ),
                    )
                }

                TaskKind.PULL_REQUEST -> {
                    command(
                        "PATCH",
                        "/v1/pull-requests/$id",
                        Json.encodeToString(UpdatePullRequestRequest(operationId, labels(draft), milestone, revision, issuedAtEpochMillis)),
                    )
                }

                TaskKind.DRAFT -> {
                    command(
                        "PATCH",
                        "/v1/drafts/$id",
                        Json.encodeToString(UpdateDraftRequest(operationId, draft.title.trim(), draft.body, revision, issuedAtEpochMillis)),
                    )
                }

                TaskKind.REVIEW_REQUEST -> {
                    error("Review rows do not support mutations")
                }
            }
        }

        TaskWriteMode.COMMENT -> {
            command(
                "POST",
                "/v1/${if (detail!!.task.kind == TaskKind.ISSUE) "issues" else "pull-requests"}/$id/comments",
                Json.encodeToString(CreateCommentRequest(operationId, draft.body, revision, issuedAtEpochMillis)),
            )
        }

        TaskWriteMode.ASSIGNEES -> {
            command(
                "PUT",
                "/v1/tasks/$id/assignees",
                Json.encodeToString(
                    AssignTaskRequest(operationId, updated, draft.addUserIds, draft.removeGitHubIds, revision, issuedAtEpochMillis),
                ),
            )
        }

        TaskWriteMode.REVIEWERS -> {
            command(
                "PUT",
                "/v1/pull-requests/$id/reviewers",
                Json.encodeToString(
                    ReviewRequestUpdate(
                        operationId,
                        updated,
                        draft.addUserIds,
                        draft.removeGitHubIds,
                        draft.reRequest,
                        revision,
                        issuedAtEpochMillis,
                    ),
                ),
            )
        }

        TaskWriteMode.FIELDS -> {
            command(
                "PUT",
                "/v1/tasks/$id/project-fields",
                Json
                    .encodeToString(
                        ProjectFieldsUpdate(
                            operationId,
                            requireNotNull(
                                draft.projectId,
                            ),
                            updated,
                            draft.status,
                            draft.priority,
                            draft.startDate.ifBlank {
                                null
                            },
                            draft.targetDate.ifBlank { null },
                            revision,
                            issuedAtEpochMillis,
                        ),
                    ),
            )
        }

        TaskWriteMode.ADD_TO_PROJECT -> {
            command(
                "POST",
                "/v1/tasks/$id/projects",
                Json.encodeToString(AddTaskToProjectRequest(operationId, requireNotNull(draft.projectId), revision, issuedAtEpochMillis)),
            )
        }

        TaskWriteMode.CONVERT -> {
            command(
                "POST",
                "/v1/drafts/$id/convert",
                Json.encodeToString(ConvertDraftRequest(operationId, requireNotNull(draft.repositoryId), revision, issuedAtEpochMillis)),
            )
        }

        TaskWriteMode.ARCHIVE -> {
            command(
                "PUT",
                "/v1/tasks/$id/project-archive",
                Json.encodeToString(
                    ProjectItemArchiveRequest(operationId, requireNotNull(draft.projectId), draft.archived, revision, issuedAtEpochMillis),
                ),
            )
        }

        TaskWriteMode.ISSUE_CLOSE, TaskWriteMode.ISSUE_REOPEN -> {
            command(
                "POST",
                "/v1/issues/$id/${if (mode == TaskWriteMode.ISSUE_CLOSE) "close" else "reopen"}",
                Json.encodeToString(TaskMutationRequest(operationId, updated, revision, issuedAtEpochMillis)),
            )
        }
    }
}

private fun labels(draft: TaskWriteDraft): List<String> =
    draft.labels
        .split('\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
