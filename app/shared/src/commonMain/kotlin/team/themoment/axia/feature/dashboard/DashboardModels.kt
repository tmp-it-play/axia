package team.themoment.axia.feature.dashboard

import androidx.compose.runtime.Immutable

data class DashboardUiState(
    val tasks: List<DashboardTask> = emptyList(),
    val unreadNotificationCount: Int = 0,
    val recentAssignments: List<DashboardActivity> = emptyList(),
    val recentCompletions: List<DashboardActivity> = emptyList(),
    val availability: MemberAvailability = MemberAvailability.Available,
    val githubConnection: GitHubConnectionState = GitHubConnectionState.Available,
)

data class DashboardTask(
    val id: String,
    val contentKind: DashboardContentKind,
    val title: String,
    val productName: String,
    val repositoryName: String? = null,
    val sourceNumber: Int? = null,
    val assignee: DashboardAssignee = DashboardAssignee.Unassigned,
    val dueLabel: String? = null,
    val dueAtEpochMillis: Long? = null,
    val updatedAtEpochMillis: Long = 0,
    val urgency: DashboardTaskUrgency = DashboardTaskUrgency.Normal,
    val priority: DashboardTaskPriority = DashboardTaskPriority.Normal,
    val position: DashboardTaskPosition? = null,
    val contexts: Set<DashboardTaskContext> = emptySet(),
)

@Immutable
data class DashboardActivity(
    val id: String,
    val title: String,
    val context: String,
)

@Immutable
enum class DashboardContentKind(
    val label: String,
) {
    Issue("Issue"),
    PullRequest("PR"),
    Draft("Draft"),
}

@Immutable
enum class DashboardTaskContext(
    val label: String,
) {
    ReviewRequested("리뷰 요청"),
    ProjectItem("Project 항목"),
}

@Immutable
enum class DashboardTaskUrgency {
    Overdue,
    DueSoon,
    Normal,
}

@Immutable
enum class DashboardTaskPriority {
    High,
    Normal,
}

@Immutable
enum class DashboardTaskPosition {
    Server,
    Frontend,
    Cloud,
    Design,
}

sealed interface DashboardAssignee {
    data object Unassigned : DashboardAssignee

    data class Assigned(
        val name: String,
        val flags: Set<DashboardAssigneeFlag> = emptySet(),
    ) : DashboardAssignee {
        init {
            require(name.isNotBlank()) { "Assigned member name must not be blank" }
        }
    }
}

@Immutable
enum class DashboardAssigneeFlag {
    NonPoc,
    Inactive,
}

@Immutable
sealed interface MemberAvailability {
    data object Available : MemberAvailability

    data class Unavailable(
        val label: String,
        val untilLabel: String? = null,
    ) : MemberAvailability
}

@Immutable
sealed interface GitHubConnectionState {
    data object Available : GitHubConnectionState

    data object ReadOnly : GitHubConnectionState
}

private val dashboardTaskComparator =
    compareBy<DashboardTask> { task ->
        when (task.urgency) {
            DashboardTaskUrgency.Overdue -> 0
            DashboardTaskUrgency.DueSoon -> 1
            DashboardTaskUrgency.Normal -> 2
        }
    }.thenBy { task ->
        when (task.priority) {
            DashboardTaskPriority.High -> 0
            DashboardTaskPriority.Normal -> 1
        }
    }.thenBy { task -> task.dueAtEpochMillis ?: Long.MAX_VALUE }
        .thenByDescending { task -> task.updatedAtEpochMillis }

internal fun List<DashboardTask>.sortedForDashboard(): List<DashboardTask> = sortedWith(dashboardTaskComparator)
