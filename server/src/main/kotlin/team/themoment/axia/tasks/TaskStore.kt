package team.themoment.axia.tasks

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import team.themoment.axia.api.ApiErrorCode
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.authorization.AuthorizationPolicy
import team.themoment.axia.authorization.AuthorizationSnapshot
import team.themoment.axia.authorization.hasRetainedTaskWrite
import team.themoment.axia.authorization.lockMemberManagement
import team.themoment.axia.domain.OrganizationRole
import team.themoment.axia.domain.ProductId
import team.themoment.axia.domain.TechnicalPosition
import team.themoment.axia.integration.github.GitHubActor
import team.themoment.axia.integration.github.GitHubAssignmentOccurrence
import team.themoment.axia.integration.github.GitHubCompletionOccurrence
import team.themoment.axia.integration.github.GitHubTaskSnapshot
import team.themoment.axia.integration.github.objects
import team.themoment.axia.integration.github.requiredInstant
import team.themoment.axia.integration.github.requiredObject
import team.themoment.axia.integration.github.requiredText
import team.themoment.axia.integration.github.text
import team.themoment.axia.members.MemberContributionHistory
import team.themoment.axia.persistence.execute
import team.themoment.axia.persistence.inTransaction
import team.themoment.axia.persistence.insertId
import team.themoment.axia.persistence.instant
import team.themoment.axia.persistence.nullableInt
import team.themoment.axia.persistence.nullableLong
import team.themoment.axia.persistence.query
import team.themoment.axia.reminders.ReminderTiming
import team.themoment.axia.reminders.UnavailableInterval
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

data class TaskQuery(
    val productId: ProductId? = null,
    val userId: Long? = null,
    val position: TechnicalPosition? = null,
    val kind: TaskKind? = null,
    val unassignedOnly: Boolean = false,
    val includeClosed: Boolean = false,
    val search: String = "",
    val cursor: Long = 0,
)

data class StoredTask(
    val id: Long,
    val nodeId: String,
    val productId: ProductId,
    val repositoryId: Long?,
    val repositoryName: String?,
    val defaultProjectId: Long?,
    val number: Int?,
    val kind: TaskKind,
    val title: String,
    val body: String,
    val url: String,
    val open: Boolean,
    val completed: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val convertedAt: Instant?,
    val lifecycleVersion: Long,
    val details: JsonObject,
)

internal data class ReviewReminderScope(
    val githubId: Long,
    val occurrenceId: String,
) {
    val assignmentKey: String get() = "review:$githubId:$occurrenceId"
    val stateKey: String get() =
        "review:$githubId:" +
            team.themoment.axia.security.CryptoSupport.sha256(occurrenceId.toByteArray(Charsets.UTF_8)).joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
}

internal data class CurrentReviewAssignment(
    val scope: ReviewReminderScope,
    val userId: Long?,
    val currentlyLinked: Boolean,
)

internal fun requireCurrentReviewAssignment(
    connection: Connection,
    taskId: Long,
    assignmentKey: String,
    forUpdate: Boolean = false,
): CurrentReviewAssignment {
    val parts = assignmentKey.split(':', limit = 3)
    val githubId = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }
    val occurrence = parts.getOrNull(2)
    if (parts.firstOrNull() != "review" || githubId == null || occurrence.isNullOrEmpty() || occurrence.length > 255 ||
        occurrence.any { it.code !in 33..126 } || assignmentKey != "review:$githubId:$occurrence"
    ) {
        throw ApiFailure.invalidRequest("리뷰 요청 식별자가 올바르지 않습니다.")
    }
    return connection
        .query(
            "SELECT a.user_id,(g.github_user_id=a.github_user_id AND g.organization_membership='MEMBER') AS currently_linked " +
                "FROM review_assignments a JOIN task_projections t ON t.id=a.task_id " +
                "LEFT JOIN user_github_links g ON g.user_id=a.user_id " +
                "WHERE a.task_id=? AND a.github_user_id=? AND a.request_occurrence_id=? AND a.active=TRUE " +
                "AND t.kind='PULL_REQUEST' AND t.is_open=TRUE AND t.is_completed=FALSE" + if (forUpdate) " FOR UPDATE" else "",
            taskId,
            githubId,
            occurrence,
        ) {
            CurrentReviewAssignment(
                ReviewReminderScope(githubId, occurrence),
                it.nullableLong("user_id"),
                it.getBoolean("currently_linked"),
            )
        }.singleOrNull() ?: throw staleReviewAssignment()
}

internal fun reviewReminderSettings(
    connection: Connection,
    taskId: Long,
    scope: ReviewReminderScope,
    lifecycle: Long,
): Pair<Int?, Boolean> =
    connection
        .query(
            "SELECT hours,suppressed FROM review_reminder_overrides WHERE task_id=? AND github_user_id=? " +
                "AND request_occurrence_id=? AND lifecycle_version=?",
            taskId,
            scope.githubId,
            scope.occurrenceId,
            lifecycle,
        ) { it.nullableInt("hours") to it.getBoolean("suppressed") }
        .singleOrNull() ?: (null to false)

internal fun staleReviewAssignment(): ApiFailure =
    ApiFailure(HttpStatusCode.Conflict, ApiErrorCode.Conflict, "리뷰 요청이 변경되거나 종료되었습니다. 목록을 새로고침해 주세요.")

class TaskStore(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun ingestRepository(
        repositoryId: Long,
        snapshot: GitHubTaskSnapshot,
    ): Long? =
        database {
            lockMemberManagement()
            ingestRepository(this, repositoryId, snapshot, clock.instant())
        }

    /** Caller owns the transaction so webhook receipt, projection, and completion credits can commit together. */
    fun ingestRepository(
        connection: Connection,
        repositoryId: Long,
        snapshot: GitHubTaskSnapshot,
        now: Instant,
    ): Long? =
        with(connection) {
            val repository =
                query(
                    "SELECT product_id,tracking_started_at FROM repository_integrations WHERE id=? AND enabled=TRUE FOR UPDATE",
                    repositoryId,
                ) {
                    ProductId.valueOf(it.getString("product_id")) to requireNotNull(it.instant("tracking_started_at"))
                }.singleOrNull() ?: return null
            val existing = byNode(snapshot.nodeId)
            val completions = snapshot.completions.filter { !it.occurredAt.isBefore(repository.second) }
            if (existing == null && !snapshot.open && completions.isEmpty() && snapshot.createdAt.isBefore(repository.second)) return null
            val id = upsert(snapshot, repository.first, repositoryId, now)
            completions.forEach { completion -> recordCompletion(id, repository.first, snapshot, completion) }
            snapshot.assignmentOccurrences
                .filter {
                    !it.occurredAt.isBefore(repository.second)
                }.forEach { recordAssignment(id, repository.first, snapshot.title, it, now) }
            id
        }

    suspend fun ingestDraft(
        projectId: Long,
        snapshot: GitHubTaskSnapshot,
        context: TaskProjectContext,
    ): Long =
        database {
            lockMemberManagement()
            ingestDraft(this, projectId, snapshot, context, clock.instant())
        }

    fun ingestDraft(
        connection: Connection,
        projectId: Long,
        snapshot: GitHubTaskSnapshot,
        context: TaskProjectContext,
        now: Instant,
    ): Long =
        with(connection) {
            check(!autoCommit)
            require(snapshot.kind == TaskKind.DRAFT && context.projectId == projectId)
            val product =
                query("SELECT product_id FROM project_integrations WHERE id=? AND enabled=TRUE FOR UPDATE", projectId) {
                    ProductId.valueOf(it.getString(1))
                }.singleOrNull() ?: throw notFound()
            val id = upsert(snapshot, product, null, now)
            putProjectContext(id, context)
            id
        }

    suspend fun replaceBoardSnapshot(
        projectId: Long,
        contexts: Map<Long, TaskProjectContext>,
    ) = database {
        lockMemberManagement()
        replaceBoardSnapshot(this, projectId, contexts)
    }

    fun replaceBoardSnapshot(
        connection: Connection,
        projectId: Long,
        contexts: Map<Long, TaskProjectContext>,
    ) = with(connection) {
        check(!autoCommit)
        require(contexts.values.all { it.projectId == projectId })
        query("SELECT id FROM project_integrations WHERE id=? FOR UPDATE", projectId) { it.getLong(1) }.singleOrNull() ?: throw notFound()
        val previous = query("SELECT task_id FROM task_projects WHERE project_id=?", projectId) { it.getLong(1) }
        execute("DELETE FROM task_projects WHERE project_id=?", projectId)
        contexts.forEach { (taskId, context) -> putProjectContext(taskId, context) }
        (previous - contexts.keys).forEach { taskId ->
            execute("UPDATE task_projections SET is_open=FALSE,is_completed=FALSE WHERE id=? AND kind='DRAFT'", taskId)
            execute(
                "DELETE FROM reminder_states WHERE task_id=? AND NOT EXISTS(SELECT 1 FROM task_projections WHERE id=? AND is_open=TRUE)",
                taskId,
                taskId,
            )
            execute(
                "DELETE FROM task_write_grants WHERE task_id=? AND NOT EXISTS(SELECT 1 FROM task_projections WHERE id=? AND is_open=TRUE)",
                taskId,
                taskId,
            )
        }
    }

    suspend fun raw(id: Long): StoredTask = database { byId(id) ?: throw notFound() }

    suspend fun revision(id: Long): String = database { revision(this, id) }

    fun revision(
        connection: Connection,
        id: Long,
    ): String =
        with(connection) {
            val stored = byId(id) ?: throw notFound()
            val source =
                buildJsonObject {
                    put("node", stored.nodeId)
                    put("title", stored.title)
                    put("body", stored.body)
                    put("open", stored.open)
                    put("completed", stored.completed)
                    put("updatedAt", stored.updatedAt.toString())
                    put("details", stored.details)
                    put(
                        "assignees",
                        JsonArray(
                            query("SELECT github_user_id FROM task_assignees WHERE task_id=? ORDER BY github_user_id", id) {
                                JsonPrimitive(it.getLong(1))
                            },
                        ),
                    )
                    put(
                        "reviews",
                        JsonArray(
                            query(
                                "SELECT github_user_id,request_occurrence_id FROM review_assignments WHERE task_id=? AND active=TRUE ORDER BY github_user_id",
                                id,
                            ) {
                                buildJsonObject {
                                    put("id", it.getLong(1))
                                    put("occurrence", it.getString(2))
                                }
                            },
                        ),
                    )
                    put(
                        "projects",
                        JsonArray(
                            query(
                                "SELECT project_id,item_node_id,archived,status,priority,start_date,target_date FROM task_projects WHERE task_id=? ORDER BY project_id",
                                id,
                            ) { result ->
                                buildJsonObject {
                                    put("id", result.getLong("project_id"))
                                    put("item", result.getString("item_node_id"))
                                    put("archived", result.getBoolean("archived"))
                                    put("status", result.getString("status"))
                                    put("priority", result.getString("priority"))
                                    put("start", result.getDate("start_date")?.toString())
                                    put("target", result.getDate("target_date")?.toString())
                                }
                            },
                        ),
                    )
                }
            team.themoment.axia.security.CryptoSupport.sha256(canonicalJson(source).toString().toByteArray()).joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
        }

    suspend fun byCanonicalId(nodeId: String): Long? = database { byNode(nodeId)?.id }

    suspend fun markConverted(
        id: Long,
        convertedAt: Instant,
    ) = database {
        execute("UPDATE task_projections SET converted_at=? WHERE id=? AND kind='ISSUE' AND converted_at IS NULL", convertedAt, id)
    }

    suspend fun list(
        actor: AuthorizationSnapshot,
        criteria: TaskQuery,
    ): TaskListResponse =
        database {
            require(criteria.cursor >= 0 && criteria.cursor <= Int.MAX_VALUE)
            val tasks =
                query(
                    SELECT_TASK + " WHERE (? IS NULL OR t.product_id=?) AND (?=TRUE OR t.is_open=TRUE) ORDER BY t.id",
                    criteria.productId?.name,
                    criteria.productId?.name,
                    criteria.includeClosed,
                    map = ::taskRow,
                ).flatMap { stored ->
                    val original = summary(stored, actor, criteria.userId)
                    listOf(original) +
                        if (stored.open && stored.kind == TaskKind.PULL_REQUEST) reviewSummaries(stored, actor, original) else emptyList()
                }.filter { criteria.kind == null || it.kind == criteria.kind }
                    .filter { criteria.userId == null || it.assignees.any { assignee -> assignee.userId == criteria.userId } }
                    .filter { criteria.position == null || it.assignees.any { assignee -> criteria.position in assignee.positions } }
                    .filter { !criteria.unassignedOnly || it.assignees.isEmpty() }
                    .filter {
                        criteria.search.isBlank() || it.title.contains(criteria.search, ignoreCase = true) ||
                            it.repositoryName.orEmpty().contains(criteria.search, ignoreCase = true)
                    }.sortedWith(taskOrdering)
            val start = criteria.cursor.toInt().coerceAtMost(tasks.size)
            val page = tasks.drop(start).take(100)
            TaskListResponse(page, (start + page.size).toLong().takeIf { it < tasks.size }, githubAvailability())
        }

    suspend fun detail(
        actor: AuthorizationSnapshot,
        id: Long,
        assignmentKey: String? = null,
    ): TaskDetail =
        database {
            val stored = byId(id) ?: throw notFound()
            val original = summary(stored, actor)
            val review = assignmentKey?.let { requireCurrentReviewAssignment(this, id, it) }
            val summary =
                if (review == null) {
                    original
                } else {
                    reviewSummaries(stored, actor, original).singleOrNull { it.assignmentKey == review.scope.assignmentKey }
                        ?: throw staleReviewAssignment()
                }
            val override =
                if (review ==
                    null
                ) {
                    reminderOverride(id)
                } else {
                    reviewReminderSettings(this, id, review.scope, stored.lifecycleVersion)
                }
            val role = actor.organizationRole
            val canSuppress =
                role in setOf(OrganizationRole.TEAM_LEADER, OrganizationRole.C_LEADER) || role?.headPosition != null ||
                    role?.product == stored.productId
            val reminderActive = summary.isOpen && !summary.isCompleted && sourceWritable(stored)
            TaskDetail(
                task = summary,
                body = stored.body,
                labels = (stored.details["labels"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
                milestone = (stored.details["milestone"] as? JsonObject)?.text("title"),
                reviewers = reviewAssignees(id, stored.productId),
                linkedIssues = links(stored.details, "linkedIssues"),
                linkedPullRequests = links(stored.details, "linkedPullRequests"),
                parent = (stored.details["parent"] as? JsonObject)?.let { link(it) },
                subIssues = links(stored.details, "subIssues"),
                comments =
                    (stored.details["comments"] as? JsonArray)
                        ?.map { item ->
                            val comment = item as JsonObject
                            TaskComment(
                                comment.requiredText("node_id"),
                                comment.requiredObject("user").requiredText("login"),
                                comment.text("body").orEmpty(),
                                comment.requiredInstant("created_at").toEpochMilli(),
                            )
                        }.orEmpty(),
                reminderOverrideHours = override.first,
                remindersSuppressed = override.second,
                canChangeReminderOverride =
                    actor.isEligible && reminderActive && summary.assignees.any { it.userId == actor.userId } &&
                        (review == null || (review.currentlyLinked && review.userId == actor.userId)),
                canChangeReminderSuppression = actor.isEligible && reminderActive && canSuppress,
                milestoneNumber =
                    (stored.details["milestone"] as? JsonObject)
                        ?.get("number")
                        ?.jsonPrimitive
                        ?.content
                        ?.toIntOrNull(),
            )
        }

    suspend fun availability(): GitHubAvailability = database { githubAvailability() }

    private fun Connection.githubAvailability(): GitHubAvailability =
        GitHubAvailability(
            query("SELECT state FROM provider_health WHERE provider='GITHUB'") { it.getString(1) }.singleOrNull() != "HEALTHY",
        )

    private fun Connection.upsert(
        snapshot: GitHubTaskSnapshot,
        product: ProductId,
        repositoryId: Long?,
        now: Instant,
    ): Long {
        val existing = byNode(snapshot.nodeId)
        if (existing != null &&
            (existing.productId != product || existing.repositoryId != repositoryId || existing.kind != snapshot.kind)
        ) {
            throw ApiFailure(HttpStatusCode.Conflict, ApiErrorCode.Conflict, "원본 태스크의 제품 연결이 일치하지 않습니다.")
        }
        if (existing != null && snapshot.updatedAt.isBefore(existing.updatedAt)) return existing.id
        if (existing?.convertedAt != null && snapshot.convertedAt != null && existing.convertedAt != snapshot.convertedAt) {
            throw ApiFailure(HttpStatusCode.Conflict, ApiErrorCode.Conflict, "원본 태스크의 변환 시각이 변경되었습니다.")
        }
        if (existing != null && snapshot.snapshotRequestedAt != null) {
            val lastSync =
                query(
                    "SELECT synced_at FROM task_projections WHERE id=?",
                    existing.id,
                ) { requireNotNull(it.instant("synced_at")) }.single()
            if (lastSync.isAfter(snapshot.snapshotRequestedAt)) return existing.id
        }
        val previousAssignees =
            existing
                ?.let { old ->
                    query("SELECT github_user_id,user_id FROM task_assignees WHERE task_id=?", old.id) {
                        it.getLong(1) to
                            it.nullableLong("user_id")
                    }.toMap()
                }.orEmpty()
        val previousReviewers =
            existing
                ?.let { old ->
                    query("SELECT github_user_id,user_id FROM review_assignments WHERE task_id=? AND active=TRUE", old.id) {
                        it.getLong(1) to
                            it.nullableLong("user_id")
                    }.toMap()
                }.orEmpty()
        val previousReviews =
            existing
                ?.let { old ->
                    query("SELECT github_user_id,request_occurrence_id FROM review_assignments WHERE task_id=? AND active=TRUE", old.id) {
                        it.getLong(1) to
                            it.getString(2)
                    }.toMap()
                }.orEmpty()
        val reopened = existing != null && !existing.open && snapshot.open
        val id =
            if (existing == null) {
                insertId(
                    """INSERT INTO task_projections(canonical_node_id,product_id,repository_id,kind,number,title,body,source_url,is_open,is_completed,created_at,updated_at,closed_at,converted_at,snapshot_json,synced_at)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    snapshot.nodeId,
                    product,
                    repositoryId,
                    snapshot.kind,
                    snapshot.number,
                    snapshot.title,
                    snapshot.body,
                    snapshot.url,
                    snapshot.open,
                    snapshot.completed,
                    snapshot.createdAt,
                    snapshot.updatedAt,
                    snapshot.closedAt,
                    snapshot.convertedAt,
                    snapshot.details.toString(),
                    now,
                )
            } else {
                execute(
                    """UPDATE task_projections SET title=?,body=?,source_url=?,is_open=?,is_completed=?,updated_at=?,closed_at=?,converted_at=COALESCE(converted_at,?),snapshot_json=?,synced_at=?,lifecycle_version=lifecycle_version+? WHERE id=?""",
                    snapshot.title,
                    snapshot.body,
                    snapshot.url,
                    snapshot.open,
                    snapshot.completed,
                    snapshot.updatedAt,
                    snapshot.closedAt,
                    snapshot.convertedAt,
                    snapshot.details.toString(),
                    now,
                    if (reopened) 1 else 0,
                    existing.id,
                )
                existing.id
            }
        execute("DELETE FROM task_assignees WHERE task_id=?", id)
        snapshot.assignees.distinctBy { it.id }.forEach { assignee ->
            val userId = previousAssignees[assignee.id] ?: currentUser(assignee.id)
            execute(
                "INSERT INTO task_assignees(task_id,github_user_id,github_login,github_node_id,user_id) VALUES(?,?,?,?,?)",
                id,
                assignee.id,
                assignee.login,
                assignee.nodeId,
                userId,
            )
        }
        execute("UPDATE review_assignments SET active=FALSE WHERE task_id=?", id)
        snapshot.reviewAssignments.forEach { review ->
            execute(
                """INSERT INTO review_assignments(task_id,github_user_id,github_login,github_node_id,user_id,request_occurrence_id,requested_at,active)
                   VALUES(?,?,?,?,?,?,?,TRUE) ON DUPLICATE KEY UPDATE github_login=VALUES(github_login),github_node_id=VALUES(github_node_id),user_id=VALUES(user_id),
                   request_occurrence_id=VALUES(request_occurrence_id),requested_at=VALUES(requested_at),active=TRUE""",
                id,
                review.actor.id,
                review.actor.login,
                review.actor.nodeId,
                previousReviewers[review.actor.id] ?: currentUser(review.actor.id),
                review.occurrenceId,
                review.requestedAt,
            )
            if (previousReviews[review.actor.id] != review.occurrenceId) {
                notifyAssignment(
                    id,
                    product,
                    snapshot.title,
                    review.actor.id,
                    currentUser(review.actor.id),
                    "REVIEW_REQUESTED",
                    "review:${review.occurrenceId}",
                    now,
                )
            }
        }
        execute(
            "DELETE o FROM review_reminder_overrides o LEFT JOIN review_assignments a ON a.task_id=o.task_id " +
                "AND a.github_user_id=o.github_user_id AND a.request_occurrence_id=o.request_occurrence_id AND a.active=TRUE " +
                "WHERE o.task_id=? AND a.task_id IS NULL",
            id,
        )
        execute(
            "DELETE g FROM review_task_write_grants g JOIN task_projections t ON t.id=g.task_id " +
                "LEFT JOIN review_assignments a ON a.task_id=g.task_id AND a.user_id=g.user_id AND a.github_user_id=g.github_user_id " +
                "AND a.request_occurrence_id=g.request_occurrence_id AND a.active=TRUE " +
                "WHERE g.task_id=? AND (a.task_id IS NULL OR t.is_open=FALSE OR t.is_completed=TRUE " +
                "OR t.lifecycle_version<>g.lifecycle_version " +
                "OR g.closed_occurrence_ids<>COALESCE(JSON_EXTRACT(t.snapshot_json, '$.closedOccurrenceIds'), JSON_ARRAY()))",
            id,
        )
        (previousReviews.keys - snapshot.reviewAssignments.map { it.actor.id }.toSet()).forEach { githubId ->
            notifyAssignment(
                id,
                product,
                snapshot.title,
                githubId,
                previousReviewers[githubId],
                "REVIEW_REQUEST_REMOVED",
                "review-removed:${previousReviews.getValue(githubId)}",
                now,
            )
        }
        if (snapshot.kind == TaskKind.DRAFT) {
            val currentIds = snapshot.assignees.map { it.id }.toSet()
            snapshot.assignees.filter { it.id !in previousAssignees.keys }.forEach {
                notifyAssignment(
                    id,
                    product,
                    snapshot.title,
                    it.id,
                    currentUser(it.id),
                    "TASK_ASSIGNED",
                    "draft-assigned:${UUID.randomUUID()}",
                    now,
                )
            }
            (previousAssignees.keys - currentIds).forEach {
                notifyAssignment(
                    id,
                    product,
                    snapshot.title,
                    it,
                    previousAssignees[it],
                    "TASK_UNASSIGNED",
                    "draft-unassigned:${UUID.randomUUID()}",
                    now,
                )
            }
        }
        execute(
            "DELETE g FROM task_write_grants g JOIN task_projections t ON t.id=g.task_id " +
                "LEFT JOIN task_assignees a ON a.task_id=g.task_id AND a.user_id=g.user_id AND a.github_user_id=g.github_user_id " +
                "WHERE g.task_id=? AND (a.task_id IS NULL OR t.is_open=FALSE OR t.is_completed=TRUE " +
                "OR t.lifecycle_version<>g.lifecycle_version " +
                "OR g.closed_occurrence_ids<>COALESCE(JSON_EXTRACT(t.snapshot_json, '$.closedOccurrenceIds'), JSON_ARRAY()))",
            id,
        )
        if (!snapshot.open || reopened) {
            execute("DELETE FROM reminder_states WHERE task_id=?", id)
            execute("DELETE FROM review_reminder_overrides WHERE task_id=?", id)
            if (!snapshot.open) execute("DELETE FROM task_write_grants WHERE task_id=?", id)
            if (reopened) {
                execute(
                    "UPDATE task_reminder_overrides SET suppressed=FALSE,lifecycle_version=lifecycle_version+1 WHERE task_id=?",
                    id,
                )
            }
        }
        return id
    }

    private fun Connection.recordAssignment(
        taskId: Long,
        product: ProductId,
        title: String,
        event: GitHubAssignmentOccurrence,
        now: Instant,
    ) {
        val inserted =
            execute(
                "INSERT IGNORE INTO task_assignment_events(occurrence_id,task_id,github_user_id,assigned,occurred_at) VALUES(?,?,?,?,?)",
                event.id,
                taskId,
                event.assignee.id,
                event.assigned,
                event.occurredAt,
            )
        // Event identity, not its second-resolution timestamp, ends a retained assignment.
        // DraftIssue exposes no assignment timeline; only observed Draft transitions can be enforced here.
        if (!event.assigned) {
            execute(
                "DELETE FROM task_write_grants WHERE task_id=? AND github_user_id=? " +
                    "AND NOT JSON_CONTAINS(unassignment_occurrence_ids, JSON_QUOTE(?))",
                taskId,
                event.assignee.id,
                event.id,
            )
        }
        if (inserted == 0) return
        val user =
            query("SELECT user_id FROM task_assignees WHERE task_id=? AND github_user_id=?", taskId, event.assignee.id) {
                it.nullableLong("user_id")
            }.singleOrNull()
                ?: currentUser(event.assignee.id)
        notifyAssignment(
            taskId,
            product,
            title,
            event.assignee.id,
            user,
            if (event.assigned) "TASK_ASSIGNED" else "TASK_UNASSIGNED",
            "assignment:${event.id}",
            now,
        )
    }

    private fun Connection.notifyAssignment(
        taskId: Long,
        product: ProductId,
        title: String,
        githubId: Long,
        recipient: Long?,
        type: String,
        occurrence: String,
        now: Instant,
    ) {
        if (recipient == null || query("SELECT 1 FROM users WHERE id=? AND is_active=TRUE", recipient) { it.getInt(1) }.isEmpty()) return
        val payload =
            buildJsonObject {
                put("taskId", taskId)
                put("title", title)
                put("productId", product.name)
                put("githubUserId", githubId)
            }
        val dedupe =
            team.themoment.axia.security.CryptoSupport.sha256("$type:$taskId:$recipient:$occurrence".toByteArray()).joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
        execute(
            "INSERT INTO notifications(recipient_user_id,type,payload_json,dedupe_key,created_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
            recipient,
            type,
            payload.toString(),
            "task-assignment:$dedupe",
            now,
        )
    }

    private fun Connection.recordCompletion(
        taskId: Long,
        product: ProductId,
        snapshot: GitHubTaskSnapshot,
        occurrence: GitHubCompletionOccurrence,
    ) {
        val convertedAt = query("SELECT converted_at FROM task_projections WHERE id=?", taskId) { it.instant("converted_at") }.single()
        val startedAt = convertedAt ?: snapshot.createdAt
        if (occurrence.occurredAt.isBefore(startedAt)) return
        val inserted =
            execute(
                "INSERT IGNORE INTO completion_events(occurrence_id,task_id,product_id,kind,occurred_at,processing_seconds) VALUES(?,?,?,?,?,?)",
                occurrence.id,
                taskId,
                product,
                occurrence.kind,
                occurrence.occurredAt,
                Duration.between(startedAt, occurrence.occurredAt).seconds,
            )
        if (inserted == 0) return // Unknown assignees never receive a retroactive credit after joining later.
        val eventId = query("SELECT id FROM completion_events WHERE occurrence_id=?", occurrence.id) { it.getLong(1) }.single()
        occurrence.assignees.distinctBy { it.id }.forEach { assignee ->
            val identity = MemberContributionHistory.at(this, assignee.id, occurrence.occurredAt) ?: return@forEach
            execute(
                "INSERT INTO completion_credits(event_id,user_id,github_user_id,organization_role,positions_json,pocs_json) VALUES(?,?,?,?,?,?)",
                eventId,
                identity.userId,
                assignee.id,
                identity.organizationRole,
                json.encodeToString(identity.positions),
                json.encodeToString(identity.pocs),
            )
        }
    }

    private fun Connection.currentUser(githubId: Long): Long? =
        query(
            "SELECT u.id FROM users u JOIN user_github_links g ON g.user_id=u.id WHERE g.github_user_id=? AND u.is_active=TRUE AND g.organization_membership='MEMBER'",
            githubId,
        ) {
            it.getLong(1)
        }.singleOrNull()

    private fun Connection.putProjectContext(
        taskId: Long,
        context: TaskProjectContext,
    ) {
        val sameProduct =
            query(
                "SELECT 1 FROM task_projections t JOIN project_integrations p ON p.product_id=t.product_id WHERE t.id=? AND p.id=?",
                taskId,
                context.projectId,
            ) {
                it.getInt(1)
            }.isNotEmpty()
        if (!sameProduct) throw ApiFailure(HttpStatusCode.Conflict, ApiErrorCode.Conflict, "보드와 태스크의 제품이 일치하지 않습니다.")
        execute(
            """INSERT INTO task_projects(task_id,project_id,item_node_id,archived,status,priority,start_date,target_date) VALUES(?,?,?,?,?,?,?,?)
               ON DUPLICATE KEY UPDATE item_node_id=VALUES(item_node_id),archived=VALUES(archived),status=VALUES(status),priority=VALUES(priority),start_date=VALUES(start_date),target_date=VALUES(target_date)""",
            taskId,
            context.projectId,
            context.itemNodeId,
            context.archived,
            context.status,
            context.priority,
            context.startDate?.let(LocalDate::parse),
            context.targetDate?.let(LocalDate::parse),
        )
    }

    private fun Connection.byNode(nodeId: String): StoredTask? =
        query(SELECT_TASK + " WHERE t.canonical_node_id=? FOR UPDATE", nodeId, map = ::taskRow).singleOrNull()

    private fun Connection.byId(id: Long): StoredTask? = query(SELECT_TASK + " WHERE t.id=?", id, map = ::taskRow).singleOrNull()

    private fun Connection.summary(
        stored: StoredTask,
        actor: AuthorizationSnapshot,
        dueForUserId: Long? = null,
    ): TaskSummary {
        val assignees = assignees(stored.id, stored.productId).map { if (stored.open) it else it.copy(warnings = emptySet()) }
        val projects =
            query(
                "SELECT tp.*,p.project_number FROM task_projects tp JOIN project_integrations p ON p.id=tp.project_id WHERE tp.task_id=? AND p.enabled=TRUE ORDER BY p.id",
                stored.id,
            ) {
                TaskProjectContext(
                    it.getLong("project_id"),
                    it.getInt("project_number"),
                    it.getString("item_node_id"),
                    it.getBoolean("archived"),
                    TaskStatus.valueOf(it.getString("status")),
                    TaskPriority.valueOf(it.getString("priority")),
                    it.getDate("start_date")?.toLocalDate()?.toString(),
                    it.getDate("target_date")?.toLocalDate()?.toString(),
                )
            }
        val effective =
            if (stored.kind ==
                TaskKind.DRAFT
            ) {
                projects.singleOrNull()
            } else {
                projects.singleOrNull { it.projectId == stored.defaultProjectId }
            }
        val priority = effective?.priority ?: TaskPriority.UNSET
        val hours = effectiveHours(stored, effective?.projectId, priority, reminderOverride(stored.id).first)
        val target = effective?.targetDate?.let(LocalDate::parse)
        val start = stored.convertedAt ?: stored.createdAt
        val timingAssignees = if (dueForUserId == null) assignees else assignees.filter { it.userId == dueForUserId }
        val dueDates =
            if (timingAssignees.isEmpty()) {
                listOf(ReminderTiming.dueAt(start, hours, target, emptyList()))
            } else {
                timingAssignees.map { assignee ->
                    val away =
                        assignee.userId
                            ?.let { userId ->
                                query("SELECT starts_at,ends_at FROM unavailability_periods WHERE user_id=? AND ends_at>?", userId, start) {
                                    UnavailableInterval(requireNotNull(it.instant("starts_at")), requireNotNull(it.instant("ends_at")))
                                }
                            }.orEmpty()
                    ReminderTiming.dueAt(start, hours, target, away)
                }
            }
        val urgency =
            if (stored.open) {
                dueDates
                    .map {
                        ReminderTiming.urgency(clock.instant(), it, target)
                    }.minBy { it.ordinal }
            } else {
                TaskUrgency.NORMAL
            }
        val warnings =
            assignees.flatMapTo(linkedSetOf()) { it.warnings }.also {
                if (stored.open &&
                    assignees.isEmpty()
                ) {
                    it.add(TaskWarning.UNASSIGNED)
                }
            }
        val retained = actor.isEligible && hasRetainedTaskWrite(this, actor.userId, stored.productId, stored.id)
        return TaskSummary(
            stored.id,
            stored.nodeId,
            stored.productId,
            stored.repositoryId,
            stored.repositoryName,
            stored.number,
            stored.kind,
            stored.title,
            stored.url,
            stored.open,
            stored.completed,
            stored.createdAt.toEpochMilli(),
            stored.updatedAt.toEpochMilli(),
            assignees,
            projects,
            effective?.status ?: TaskStatus.UNSET,
            priority,
            effective?.startDate,
            effective?.targetDate,
            urgency,
            warnings,
            actor.isEligible && (
                AuthorizationPolicy.canWriteProduct(
                    actor,
                    stored.productId,
                ) || retained
            ) && sourceWritable(stored) && !githubAvailability().readOnly,
            revision = revision(this, stored.id),
            dueAtEpochMillis = dueDates.minOrNull()?.toEpochMilli(),
        )
    }

    private fun Connection.reviewSummaries(
        stored: StoredTask,
        actor: AuthorizationSnapshot,
        original: TaskSummary,
    ): List<TaskSummary> {
        val reviewers = reviewAssignees(stored.id, stored.productId).associateBy { it.githubId }
        return query(
            "SELECT github_user_id,request_occurrence_id,requested_at FROM review_assignments WHERE task_id=? AND active=TRUE ORDER BY github_user_id",
            stored.id,
        ) {
            Triple(it.getLong("github_user_id"), it.getString("request_occurrence_id"), requireNotNull(it.instant("requested_at")))
        }.map { (githubId, occurrence, requestedAt) ->
            val reviewer = reviewers.getValue(githubId)
            val reviewTask = stored.copy(kind = TaskKind.REVIEW_REQUEST)
            val effectiveProject = original.projects.singleOrNull { it.projectId == stored.defaultProjectId }
            val scope = ReviewReminderScope(githubId, occurrence)
            val settings = reviewReminderSettings(this, stored.id, scope, stored.lifecycleVersion)
            val hours = effectiveHours(reviewTask, effectiveProject?.projectId, original.priority, settings.first)
            val target = original.targetDate?.let(LocalDate::parse)
            val away =
                reviewer.userId
                    ?.let { userId ->
                        query("SELECT starts_at,ends_at FROM unavailability_periods WHERE user_id=? AND ends_at>?", userId, requestedAt) {
                            UnavailableInterval(requireNotNull(it.instant("starts_at")), requireNotNull(it.instant("ends_at")))
                        }
                    }.orEmpty()
            val dueAt = ReminderTiming.dueAt(requestedAt, hours, target, away)
            original.copy(
                kind = TaskKind.REVIEW_REQUEST,
                assignees = listOf(reviewer),
                isCompleted = false,
                urgency = ReminderTiming.urgency(clock.instant(), dueAt, target),
                warnings = reviewer.warnings,
                reviewRequestedAtEpochMillis = requestedAt.toEpochMilli(),
                assignmentKey = scope.assignmentKey,
                dueAtEpochMillis = dueAt.toEpochMilli(),
                canWrite = original.canWrite,
            )
        }
    }

    private fun Connection.sourceWritable(task: StoredTask): Boolean =
        if (task.repositoryId != null) {
            query(
                "SELECT 1 FROM repository_integrations WHERE id=? AND enabled=TRUE AND access_state='AVAILABLE' AND github_node_id IS NOT NULL",
                task.repositoryId,
            ) { it.getInt(1) }.isNotEmpty()
        } else {
            query(
                "SELECT 1 FROM task_projects t JOIN project_integrations p ON p.id=t.project_id " +
                    "WHERE t.task_id=? AND p.enabled=TRUE AND p.access_state='AVAILABLE' AND p.github_node_id IS NOT NULL",
                task.id,
            ) { it.getInt(1) }.isNotEmpty()
        }

    private fun Connection.effectiveHours(
        task: StoredTask,
        boardId: Long?,
        priority: TaskPriority,
        directHours: Int?,
    ): Int {
        val policies =
            if (boardId !=
                null
            ) {
                query(
                    "SELECT priority,hours FROM reminder_policies WHERE project_id=?",
                    boardId,
                ) { it.getString(1) to it.getInt(2) }.toMap()
            } else {
                emptyMap()
            }
        val global = query("SELECT hours FROM global_reminder_policies WHERE kind=?", task.kind) { it.getInt(1) }.singleOrNull()
        return ReminderTiming.thresholdHours(
            task.kind,
            priority,
            directHours,
            policies
                .filterKeys {
                    it != "DEFAULT"
                }.mapKeys { TaskPriority.valueOf(it.key) },
            policies["DEFAULT"],
            global,
        )
    }

    private fun Connection.reminderOverride(taskId: Long): Pair<Int?, Boolean> =
        query("SELECT hours,suppressed FROM task_reminder_overrides WHERE task_id=?", taskId) {
            it.nullableInt("hours") to
                it.getBoolean("suppressed")
        }.singleOrNull()
            ?: (null to false)

    private fun Connection.assignees(
        taskId: Long,
        product: ProductId,
    ): List<TaskAssignee> =
        query(
            ASSIGNEE_SELECT + " FROM task_assignees a " + ASSIGNEE_JOINS + " WHERE a.task_id=? ORDER BY a.github_login",
            product,
            taskId,
        ) { assigneeRow(it) }.map { assignee -> assignee.copy(positions = positions(assignee.userId)) }

    private fun Connection.reviewAssignees(
        taskId: Long,
        product: ProductId,
    ): List<TaskAssignee> =
        query(
            ASSIGNEE_SELECT + " FROM review_assignments a " + ASSIGNEE_JOINS +
                " WHERE a.task_id=? AND a.active=TRUE ORDER BY a.github_login",
            product,
            taskId,
        ) { assigneeRow(it) }.map { assignee -> assignee.copy(positions = positions(assignee.userId)) }

    private fun Connection.positions(userId: Long?): Set<TechnicalPosition> =
        if (userId ==
            null
        ) {
            emptySet()
        } else {
            query("SELECT position FROM user_positions WHERE user_id=?", userId) { TechnicalPosition.valueOf(it.getString(1)) }.toSet()
        }

    private fun assigneeRow(result: ResultSet): TaskAssignee =
        TaskAssignee(
            result.getLong("github_user_id"),
            result.getString("github_login"),
            result.getString("github_node_id"),
            result.nullableLong("user_id"),
            result.getString("display_name"),
            warnings =
                buildSet {
                    if (!result.getBoolean("is_poc")) add(TaskWarning.NON_POC_ASSIGNEE)
                    if (result.nullableLong("user_id") != null &&
                        (!result.getBoolean("is_active") || result.getString("organization_membership") != "MEMBER")
                    ) {
                        add(TaskWarning.INACTIVE_ASSIGNEE)
                    }
                    if (result.nullableLong("user_id") != null &&
                        result.nullableLong("current_github_id") != result.getLong("github_user_id")
                    ) {
                        add(TaskWarning.PREVIOUS_GITHUB_ACCOUNT)
                    }
                },
        )

    private fun taskRow(result: ResultSet): StoredTask =
        StoredTask(
            result.getLong("id"),
            result.getString("canonical_node_id"),
            ProductId.valueOf(result.getString("product_id")),
            result.nullableLong("repository_id"),
            result.getString("repository_name"),
            result.nullableLong("default_project_id"),
            result.nullableInt("number"),
            TaskKind.valueOf(result.getString("kind")),
            result.getString("title"),
            result.getString("body"),
            result.getString("source_url"),
            result.getBoolean("is_open"),
            result.getBoolean("is_completed"),
            requireNotNull(result.instant("created_at")),
            requireNotNull(result.instant("updated_at")),
            result.instant("converted_at"),
            result.getLong("lifecycle_version"),
            json.parseToJsonElement(result.getString("snapshot_json")) as JsonObject,
        )

    private fun links(
        details: JsonObject,
        key: String,
    ): List<GitHubTaskLink> =
        (details[key] as? JsonArray)
            ?.map {
                link(it as JsonObject)
            }.orEmpty()

    private fun canonicalJson(value: JsonElement): JsonElement =
        when (value) {
            is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to canonicalJson(it.value) })
            is JsonArray -> JsonArray(value.map(::canonicalJson))
            else -> value
        }

    private fun link(value: JsonObject): GitHubTaskLink =
        GitHubTaskLink(value.requiredText("id"), value.requiredText("title"), value.requiredText("url"))

    private suspend fun <T> database(block: Connection.() -> T): T =
        withContext(Dispatchers.IO) {
            repeat(3) { attempt ->
                try {
                    return@withContext dataSource.inTransaction(block = block)
                } catch (failure: SQLException) {
                    if (attempt == 2 || failure.errorCode !in setOf(1205, 1213)) throw failure
                    delay(25L * (attempt + 1))
                }
            }
            error("Unreachable transaction retry")
        }

    companion object {
        fun notFound(): ApiFailure = ApiFailure(HttpStatusCode.NotFound, ApiErrorCode.ResourceNotFound, "태스크를 찾을 수 없습니다.")

        private const val SELECT_TASK =
            "SELECT t.*,r.name AS repository_name,r.default_project_id FROM task_projections t " +
                "LEFT JOIN repository_integrations r ON r.id=t.repository_id"
        private const val ASSIGNEE_SELECT =
            "SELECT a.*,u.display_name,u.is_active,g.github_user_id AS current_github_id," +
                "g.organization_membership,(p.user_id IS NOT NULL) AS is_poc"
        private const val ASSIGNEE_JOINS =
            "LEFT JOIN users u ON u.id=a.user_id LEFT JOIN user_github_links g ON g.user_id=u.id " +
                "LEFT JOIN user_pocs p ON p.user_id=u.id AND p.product_id=?"
        val taskOrdering: Comparator<TaskSummary> =
            compareBy<TaskSummary> { it.urgency.ordinal }
                .thenBy { if (it.priority == TaskPriority.HIGH) 0 else 1 }
                .thenBy { it.targetDate ?: "9999-12-31" }
                .thenByDescending { it.updatedAtEpochMillis }
                .thenBy { it.id }
                .thenBy { it.kind.ordinal }
                .thenBy { it.assignmentKey.orEmpty() }
    }
}
