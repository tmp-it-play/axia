package team.themoment.axia.tasks

import kotlinx.serialization.Serializable
import team.themoment.axia.domain.ProductId
import team.themoment.axia.domain.TechnicalPosition

@Serializable
enum class TaskKind { ISSUE, PULL_REQUEST, REVIEW_REQUEST, DRAFT }

@Serializable
enum class TaskPriority { HIGH, MEDIUM, LOW, IN_REVIEW, UNSET }

@Serializable
enum class TaskStatus { TODO, IN_PROGRESS, DONE, UNSET }

@Serializable
enum class TaskWarning { UNASSIGNED, NON_POC_ASSIGNEE, INACTIVE_ASSIGNEE, PREVIOUS_GITHUB_ACCOUNT }

@Serializable
enum class TaskUrgency { OVERDUE, DUE_SOON, NORMAL }

@Serializable
data class TaskAssignee(
    val githubId: Long,
    val githubLogin: String,
    val githubNodeId: String?,
    val userId: Long?,
    val displayName: String?,
    val positions: Set<TechnicalPosition> = emptySet(),
    val warnings: Set<TaskWarning> = emptySet(),
)

@Serializable
data class TaskProjectContext(
    val projectId: Long,
    val projectNumber: Int,
    val itemNodeId: String,
    val archived: Boolean,
    val status: TaskStatus,
    val priority: TaskPriority,
    val startDate: String?,
    val targetDate: String?,
)

@Serializable
data class TaskSummary(
    val id: Long,
    val canonicalNodeId: String,
    val productId: ProductId,
    val repositoryId: Long?,
    val repositoryName: String?,
    val number: Int?,
    val kind: TaskKind,
    val title: String,
    val url: String,
    val isOpen: Boolean,
    val isCompleted: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val assignees: List<TaskAssignee>,
    val projects: List<TaskProjectContext>,
    val status: TaskStatus,
    val priority: TaskPriority,
    val startDate: String?,
    val targetDate: String?,
    val urgency: TaskUrgency,
    val warnings: Set<TaskWarning>,
    val canWrite: Boolean,
    val reviewRequestedAtEpochMillis: Long? = null,
    val assignmentKey: String? = null,
    val revision: String = "",
    val dueAtEpochMillis: Long? = null,
)

@Serializable
data class TaskDetail(
    val task: TaskSummary,
    val body: String,
    val labels: List<String>,
    val milestone: String?,
    val reviewers: List<TaskAssignee>,
    val linkedIssues: List<GitHubTaskLink>,
    val linkedPullRequests: List<GitHubTaskLink>,
    val parent: GitHubTaskLink?,
    val subIssues: List<GitHubTaskLink>,
    val comments: List<TaskComment>,
    val reminderOverrideHours: Int?,
    val remindersSuppressed: Boolean,
    val canChangeReminderOverride: Boolean,
    val canChangeReminderSuppression: Boolean,
    val milestoneNumber: Int? = null,
)

@Serializable
data class GitHubTaskLink(
    val nodeId: String,
    val title: String,
    val url: String,
)

@Serializable
data class TaskComment(
    val id: String,
    val author: String,
    val body: String,
    val createdAtEpochMillis: Long,
)

@Serializable
data class GitHubAvailability(
    val readOnly: Boolean = false,
    val statusUrl: String = "https://www.githubstatus.com/",
)

@Serializable
data class TaskListResponse(
    val tasks: List<TaskSummary>,
    val nextCursor: Long? = null,
    val github: GitHubAvailability = GitHubAvailability(),
)

/** Each external mutation must use a new operation ID; retries reuse it until the outcome is known. */
@Serializable
data class CreateIssueRequest(
    val operationId: String,
    val repositoryId: Long,
    val title: String,
    val body: String,
    val assigneeUserIds: Set<Long> = emptySet(),
    val labels: List<String> = emptyList(),
    val milestoneNumber: Int? = null,
    val projectId: Long? = null,
    val operationIssuedAtEpochMillis: Long = 0,
)

@Serializable
data class UpdateIssueRequest(
    val operationId: String,
    val expectedUpdatedAtEpochMillis: Long,
    val title: String,
    val body: String,
    val labels: List<String>,
    val milestoneNumber: Int?,
    val expectedRevision: String? = null,
    val operationIssuedAtEpochMillis: Long = 0,
)

@Serializable
data class TaskMutationRequest(
    val operationId: String,
    val expectedUpdatedAtEpochMillis: Long,
    val expectedRevision: String? = null,
    val operationIssuedAtEpochMillis: Long = 0,
)

@Serializable
data class CreateCommentRequest(
    val operationId: String,
    val body: String,
    val expectedRevision: String? = null,
    val operationIssuedAtEpochMillis: Long = 0,
)

@Serializable
data class AssignTaskRequest(
    val operationId: String,
    val expectedUpdatedAtEpochMillis: Long,
    val addUserIds: Set<Long> = emptySet(),
    val removeGitHubIds: Set<Long> = emptySet(),
    val expectedRevision: String? = null,
    val operationIssuedAtEpochMillis: Long = 0,
)

@Serializable
data class ReviewRequestUpdate(
    val operationId: String,
    val expectedUpdatedAtEpochMillis: Long,
    val addUserIds: Set<Long> = emptySet(),
    val removeGitHubIds: Set<Long> = emptySet(),
    val reRequest: Boolean = false,
    val expectedRevision: String? = null,
    val operationIssuedAtEpochMillis: Long = 0,
)

@Serializable
data class ProjectFieldsUpdate(
    val operationId: String,
    val projectId: Long,
    val expectedUpdatedAtEpochMillis: Long,
    val status: TaskStatus,
    val priority: TaskPriority,
    val startDate: String?,
    val targetDate: String?,
    val expectedRevision: String? = null,
    val operationIssuedAtEpochMillis: Long = 0,
)

@Serializable
data class CreateDraftRequest(
    val operationId: String,
    val title: String,
    val body: String,
    val assigneeUserIds: Set<Long> = emptySet(),
    val operationIssuedAtEpochMillis: Long = 0,
)

@Serializable
data class ConvertDraftRequest(
    val operationId: String,
    val repositoryId: Long,
    val expectedRevision: String? = null,
    val operationIssuedAtEpochMillis: Long = 0,
)

@Serializable
data class ProjectItemArchiveRequest(
    val operationId: String,
    val projectId: Long,
    val archived: Boolean,
    val expectedRevision: String? = null,
    val operationIssuedAtEpochMillis: Long = 0,
)

@Serializable
data class UpdatePullRequestRequest(
    val operationId: String,
    val labels: List<String>,
    val milestoneNumber: Int?,
    val expectedRevision: String? = null,
    val operationIssuedAtEpochMillis: Long = 0,
)

@Serializable
data class UpdateDraftRequest(
    val operationId: String,
    val title: String,
    val body: String,
    val expectedRevision: String? = null,
    val operationIssuedAtEpochMillis: Long = 0,
)

@Serializable
data class AddTaskToProjectRequest(
    val operationId: String,
    val projectId: Long,
    val expectedRevision: String? = null,
    val operationIssuedAtEpochMillis: Long = 0,
)

@Serializable
data class ReminderOverrideRequest(
    val hours: Int?,
    val assignmentKey: String? = null,
)

@Serializable
data class ReminderSuppressionRequest(
    val suppressed: Boolean,
    val assignmentKey: String? = null,
)

@Serializable
data class BoardReminderPolicy(
    val defaultHours: Int?,
    val priorityHours: Map<TaskPriority, Int> = emptyMap(),
)

@Serializable
data class MutationResult(
    val operationId: String,
    val taskId: Long?,
    val confirmed: Boolean,
    val refreshRequired: Boolean = false,
)
