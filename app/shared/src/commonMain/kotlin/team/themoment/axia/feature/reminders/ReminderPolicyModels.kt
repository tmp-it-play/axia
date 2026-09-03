package team.themoment.axia.feature.reminders

import team.themoment.axia.domain.OrganizationRole
import team.themoment.axia.members.CurrentUserResponse
import team.themoment.axia.members.MemberGitHubStatus
import team.themoment.axia.notifications.GlobalReminderPolicy
import team.themoment.axia.products.ProjectIntegration
import team.themoment.axia.tasks.BoardReminderPolicy
import team.themoment.axia.tasks.TaskKind
import team.themoment.axia.tasks.TaskPriority

data class ReminderPolicyUiState(
    val currentUser: CurrentUserResponse? = null,
    val projects: List<ProjectIntegration> = emptyList(),
    val globalPolicy: GlobalReminderPolicy? = null,
    val boardPolicies: Map<Long, BoardReminderPolicy> = emptyMap(),
    val selectedProjectId: Long? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
)

data class ReminderPolicyCallbacks(
    val onSelectProject: (Long) -> Unit,
    val onRefresh: () -> Unit,
    val onSaveGlobal: (GlobalReminderPolicy) -> Unit,
    val onSaveBoard: (Long, BoardReminderPolicy) -> Unit,
)

data class GlobalReminderDraft(
    val hoursByKind: Map<TaskKind, String>,
) {
    fun validationError(): String? =
        when {
            hoursByKind.keys != TaskKind.entries.toSet() -> "모든 태스크 유형의 전역 기준을 입력해 주세요."
            hoursByKind.values.any { validHours(it) == null } -> "전역 기준은 유형마다 1~87,600시간을 입력해야 합니다."
            else -> null
        }

    fun toPolicy(): GlobalReminderPolicy {
        require(validationError() == null)
        return GlobalReminderPolicy(hoursByKind.mapValues { requireNotNull(validHours(it.value)) })
    }

    companion object {
        fun from(policy: GlobalReminderPolicy?): GlobalReminderDraft =
            GlobalReminderDraft(
                TaskKind.entries.associateWith {
                    policy
                        ?.hoursByKind
                        ?.get(it)
                        ?.toString()
                        .orEmpty()
                },
            )

        fun defaults(): GlobalReminderDraft = from(defaultGlobalReminderPolicy())
    }
}

data class BoardReminderDraft(
    val defaultHours: String = "",
    val priorityHours: Map<TaskPriority, String> = TaskPriority.entries.associateWith { "" },
) {
    fun validationError(): String? =
        when {
            priorityHours.keys != TaskPriority.entries.toSet() -> "모든 Priority 입력을 확인해 주세요."
            (priorityHours.values + defaultHours).any { it.isNotBlank() && validHours(it) == null } -> "1~87,600시간을 입력하거나 상속하려면 비워 두세요."
            else -> null
        }

    fun toPolicy(): BoardReminderPolicy {
        require(validationError() == null)
        return BoardReminderPolicy(
            defaultHours
                .takeIf {
                    it.isNotBlank()
                }?.let(::validHours),
            priorityHours.filterValues { it.isNotBlank() }.mapValues { requireNotNull(validHours(it.value)) },
        )
    }

    companion object {
        fun from(policy: BoardReminderPolicy?): BoardReminderDraft =
            BoardReminderDraft(
                policy?.defaultHours?.toString().orEmpty(),
                TaskPriority.entries.associateWith {
                    policy
                        ?.priorityHours
                        ?.get(it)
                        ?.toString()
                        .orEmpty()
                },
            )
    }
}

fun canManageGlobalReminderPolicy(user: CurrentUserResponse?): Boolean =
    eligible(user) &&
        (
            user!!.capabilities.effectiveSystemAdministrator ||
                user.member.organizationRole in setOf(OrganizationRole.TEAM_LEADER, OrganizationRole.C_LEADER)
        )

fun canManageBoardReminderPolicy(
    user: CurrentUserResponse?,
    project: ProjectIntegration,
): Boolean = eligible(user) && project.productId in user!!.capabilities.managedIntegrationProducts

fun defaultGlobalReminderPolicy(): GlobalReminderPolicy =
    GlobalReminderPolicy(
        TaskKind.entries.associateWith { kind ->
            when (kind) {
                TaskKind.ISSUE, TaskKind.DRAFT -> 336
                TaskKind.PULL_REQUEST, TaskKind.REVIEW_REQUEST -> 72
            }
        },
    )

/** A missing global value is unknown, never silently replaced with a historic initial default. */
fun previewReminderHours(
    board: BoardReminderDraft,
    global: GlobalReminderPolicy?,
    kind: TaskKind,
    priority: TaskPriority,
): Int? {
    if (board.validationError() != null) return null
    val policy = board.toPolicy()
    return policy.priorityHours[priority] ?: policy.defaultHours ?: global?.hoursByKind?.get(kind)
}

internal fun validHours(value: String): Int? = value.trim().toIntOrNull()?.takeIf { it in 1..87600 }

private fun eligible(user: CurrentUserResponse?): Boolean =
    user?.member?.let { it.isActive && it.githubStatus == MemberGitHubStatus.MEMBER } == true

internal val TaskKind.reminderLabel: String get() =
    when (this) {
        TaskKind.ISSUE -> "Issue"
        TaskKind.DRAFT -> "Project Draft"
        TaskKind.PULL_REQUEST -> "PR 담당 작업"
        TaskKind.REVIEW_REQUEST -> "PR 리뷰 요청"
    }
internal val TaskPriority.reminderLabel: String get() =
    when (this) {
        TaskPriority.HIGH -> "High"
        TaskPriority.MEDIUM -> "Medium"
        TaskPriority.LOW -> "Low"
        TaskPriority.IN_REVIEW -> "In review"
        TaskPriority.UNSET -> "Unset · Priority 없음"
    }
