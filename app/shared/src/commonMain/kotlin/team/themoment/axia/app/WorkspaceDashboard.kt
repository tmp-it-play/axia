package team.themoment.axia.app

import team.themoment.axia.domain.TechnicalPosition
import team.themoment.axia.feature.dashboard.DashboardActivity
import team.themoment.axia.feature.dashboard.DashboardAssignee
import team.themoment.axia.feature.dashboard.DashboardAssigneeFlag
import team.themoment.axia.feature.dashboard.DashboardContentKind
import team.themoment.axia.feature.dashboard.DashboardTask
import team.themoment.axia.feature.dashboard.DashboardTaskContext
import team.themoment.axia.feature.dashboard.DashboardTaskPosition
import team.themoment.axia.feature.dashboard.DashboardTaskPriority
import team.themoment.axia.feature.dashboard.DashboardTaskUrgency
import team.themoment.axia.feature.dashboard.DashboardUiState
import team.themoment.axia.feature.dashboard.GitHubConnectionState
import team.themoment.axia.feature.dashboard.MemberAvailability
import team.themoment.axia.platform.AxiaTime
import team.themoment.axia.tasks.TaskKind
import team.themoment.axia.tasks.TaskPriority
import team.themoment.axia.tasks.TaskUrgency
import team.themoment.axia.tasks.TaskWarning
import team.themoment.axia.unavailability.UnavailabilityType

internal fun WorkspaceUiState.dashboard(): DashboardUiState {
    val availability = currentUser?.member?.availability
    return DashboardUiState(
        tasks =
            myTasks.map { task ->
                DashboardTask(
                    id = "${task.id}:${task.kind}:${task.assignmentKey.orEmpty()}",
                    contentKind =
                        when (task.kind) {
                            TaskKind.ISSUE -> DashboardContentKind.Issue
                            TaskKind.DRAFT -> DashboardContentKind.Draft
                            else -> DashboardContentKind.PullRequest
                        },
                    title = task.title,
                    productName = task.productId.displayName,
                    repositoryName = task.repositoryName,
                    sourceNumber = task.number,
                    assignee =
                        if (task.assignees.isEmpty()) {
                            DashboardAssignee.Unassigned
                        } else {
                            DashboardAssignee.Assigned(
                                task.assignees.joinToString { it.displayName ?: it.githubLogin },
                                buildSet {
                                    if (TaskWarning.NON_POC_ASSIGNEE in
                                        task.warnings
                                    ) {
                                        add(DashboardAssigneeFlag.NonPoc)
                                    }
                                    if (TaskWarning.INACTIVE_ASSIGNEE in
                                        task.warnings
                                    ) {
                                        add(DashboardAssigneeFlag.Inactive)
                                    }
                                },
                            )
                        },
                    dueLabel = task.targetDate ?: task.dueAtEpochMillis?.let { AxiaTime.label(it) },
                    dueAtEpochMillis = task.dueAtEpochMillis,
                    updatedAtEpochMillis = task.updatedAtEpochMillis,
                    urgency =
                        when (task.urgency) {
                            TaskUrgency.OVERDUE -> DashboardTaskUrgency.Overdue
                            TaskUrgency.DUE_SOON -> DashboardTaskUrgency.DueSoon
                            TaskUrgency.NORMAL -> DashboardTaskUrgency.Normal
                        },
                    priority = if (task.priority == TaskPriority.HIGH) DashboardTaskPriority.High else DashboardTaskPriority.Normal,
                    positions =
                        task.assignees.flatMap { assignee -> assignee.positions }.mapTo(mutableSetOf()) { position ->
                            when (position) {
                                TechnicalPosition.SERVER -> DashboardTaskPosition.Server
                                TechnicalPosition.FRONTEND -> DashboardTaskPosition.Frontend
                                TechnicalPosition.CLOUD -> DashboardTaskPosition.Cloud
                                TechnicalPosition.DESIGN -> DashboardTaskPosition.Design
                            }
                        },
                    contexts =
                        buildSet {
                            if (task.kind ==
                                TaskKind.REVIEW_REQUEST
                            ) {
                                add(DashboardTaskContext.ReviewRequested)
                            }
                            if (task.projects.isNotEmpty()) add(DashboardTaskContext.ProjectItem)
                        },
                )
            },
        unreadNotificationCount = unreadCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        recentAssignments =
            notifications
                .filter {
                    it.type in assignmentNotificationTypes
                }.take(5)
                .map { DashboardActivity(it.id.toString(), it.title, it.body) },
        recentCompletions =
            dashboardCompletions.map {
                DashboardActivity(it.id.toString(), it.title, "${it.productId.displayName} · ${AxiaTime.label(it.occurredAtEpochMillis)}")
            },
        availability =
            if (availability?.type == null) {
                MemberAvailability.Available
            } else {
                MemberAvailability.Unavailable(
                    if (availability.type == UnavailabilityType.VACATION) "휴가 중" else "업무 불가",
                    availability.untilEpochMillis?.let(AxiaTime::label),
                )
            },
        githubConnection = if (github.readOnly) GitHubConnectionState.ReadOnly else GitHubConnectionState.Available,
    )
}

private val assignmentNotificationTypes = setOf("TASK_ASSIGNED", "TASK_UNASSIGNED", "REVIEW_REQUESTED", "REVIEW_REQUEST_REMOVED")
