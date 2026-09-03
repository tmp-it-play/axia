package team.themoment.axia.integration.github

import kotlinx.serialization.json.JsonObject
import team.themoment.axia.tasks.TaskKind
import java.time.Instant

data class GitHubActor(
    val id: Long,
    val login: String,
    val nodeId: String?,
)

data class GitHubReviewAssignment(
    val actor: GitHubActor,
    val occurrenceId: String,
    val requestedAt: Instant,
)

data class GitHubAssignmentOccurrence(
    val id: String,
    val assignee: GitHubActor,
    val assigned: Boolean,
    val occurredAt: Instant,
)

enum class CompletionKind { ISSUE_CLOSE, PR_MERGE }

data class GitHubCompletionOccurrence(
    val id: String,
    val kind: CompletionKind,
    val occurredAt: Instant,
    val assignees: List<GitHubActor>,
)

data class GitHubTaskSnapshot(
    val nodeId: String,
    val kind: TaskKind,
    val number: Int?,
    val title: String,
    val body: String,
    val url: String,
    val open: Boolean,
    val completed: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val closedAt: Instant?,
    val assignees: List<GitHubActor>,
    val reviewAssignments: List<GitHubReviewAssignment>,
    val completions: List<GitHubCompletionOccurrence>,
    val details: JsonObject,
    val snapshotRequestedAt: Instant? = null,
    val assignmentOccurrences: List<GitHubAssignmentOccurrence> = emptyList(),
    val convertedAt: Instant? = null,
)

data class GitHubProjectSnapshot(
    val nodeId: String,
    val number: Int,
    val title: String,
    val fields: List<JsonObject>,
    val repositoryNodeIds: Set<String>,
    val url: String? = null,
)
