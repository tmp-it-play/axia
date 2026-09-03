package team.themoment.axia.reminders

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import team.themoment.axia.api.ApiErrorCode
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.authorization.AuthorizationPolicy
import team.themoment.axia.authorization.AuthorizationService
import team.themoment.axia.authorization.AuthorizationSnapshot
import team.themoment.axia.authorization.accessDenied
import team.themoment.axia.authorization.lockMemberManagement
import team.themoment.axia.domain.OrganizationRole
import team.themoment.axia.domain.ProductId
import team.themoment.axia.domain.TechnicalPosition
import team.themoment.axia.members.currentNotificationActors
import team.themoment.axia.members.insertFunctionalNotification
import team.themoment.axia.members.memberTransaction
import team.themoment.axia.notifications.GlobalReminderPolicy
import team.themoment.axia.notifications.ReminderSettingsResponse
import team.themoment.axia.notifications.isUnavailable
import team.themoment.axia.persistence.execute
import team.themoment.axia.persistence.inTransaction
import team.themoment.axia.persistence.instant
import team.themoment.axia.persistence.nullableInt
import team.themoment.axia.persistence.nullableLong
import team.themoment.axia.persistence.query
import team.themoment.axia.scheduling.JobLease
import team.themoment.axia.tasks.BoardReminderPolicy
import team.themoment.axia.tasks.ReviewReminderScope
import team.themoment.axia.tasks.TaskKind
import team.themoment.axia.tasks.TaskPriority
import team.themoment.axia.tasks.TaskStatus
import team.themoment.axia.tasks.requireCurrentReviewAssignment
import team.themoment.axia.tasks.reviewReminderSettings
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.sql.DataSource

/** One minute scheduler callback; every state transition and notification shares a fenced transaction. */
class ReminderService(
    private val dataSource: DataSource,
    private val authorization: AuthorizationService,
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun process(lease: JobLease): Unit =
        withContext(ioDispatcher) {
            var afterId = 0L
            while (true) {
                val ids =
                    dataSource.inTransaction(readOnly = true) {
                        query(
                            "SELECT id FROM task_projections WHERE id>? AND is_open=TRUE AND is_completed=FALSE ORDER BY id LIMIT 100",
                            afterId,
                        ) { it.getLong(1) }
                    }
                if (ids.isEmpty()) break
                ids.forEach { id ->
                    dataSource.memberTransaction {
                        val now = clock.instant()
                        lease.requireOwned(this, now)
                        lockMemberManagement()
                        val task = reminderTask(this, id, forUpdate = true) ?: return@memberTransaction
                        if (!task.active) return@memberTransaction
                        processTask(this, task, now)
                    }
                }
                afterId = ids.last()
            }
        }

    suspend fun setOverride(
        userId: Long,
        taskId: Long,
        hours: Int?,
        assignmentKey: String? = null,
    ): ReminderSettingsResponse =
        withContext(ioDispatcher) {
            hours?.let(::validateHours)
            dataSource.memberTransaction {
                val actor = authorization.requireCurrent(this, userId)
                val task = reminderTask(this, taskId, forUpdate = true) ?: throw taskNotFound()
                if (assignmentKey != null) {
                    val review = requireCurrentReviewAssignment(this, taskId, assignmentKey, forUpdate = true)
                    if (!task.active || !review.currentlyLinked || review.userId != actor.userId) throw accessDenied()
                    val settings = reviewReminderSettings(this, taskId, review.scope, task.lifecycle)
                    execute(
                        "INSERT INTO review_reminder_overrides(task_id,github_user_id,request_occurrence_id," +
                            "hours,suppressed,lifecycle_version) " +
                            "VALUES(?,?,?,?,FALSE,?) ON DUPLICATE KEY UPDATE hours=VALUES(hours)",
                        taskId,
                        review.scope.githubId,
                        review.scope.occurrenceId,
                        hours,
                        task.lifecycle,
                    )
                    return@memberTransaction ReminderSettingsResponse(taskId, hours, settings.second, review.scope.assignmentKey)
                }
                if (!task.active ||
                    query("SELECT 1 FROM task_assignees WHERE task_id=? AND user_id=?", taskId, actor.userId) { it.getInt(1) }.isEmpty()
                ) {
                    throw accessDenied()
                }
                execute(
                    "INSERT INTO task_reminder_overrides(task_id,hours,suppressed,lifecycle_version) VALUES(?,?,FALSE,?) ON DUPLICATE KEY UPDATE hours=VALUES(hours)",
                    taskId,
                    hours,
                    task.lifecycle,
                )
                ReminderSettingsResponse(taskId, hours, task.suppressed)
            }
        }

    suspend fun setSuppressed(
        userId: Long,
        taskId: Long,
        suppressed: Boolean,
        assignmentKey: String? = null,
    ): ReminderSettingsResponse =
        withContext(ioDispatcher) {
            dataSource.memberTransaction {
                val actor = authorization.requireCurrent(this, userId)
                val task = reminderTask(this, taskId, forUpdate = true) ?: throw taskNotFound()
                if (!task.active || !canSuppress(actor, task.product)) throw accessDenied()
                if (assignmentKey != null) {
                    val review = requireCurrentReviewAssignment(this, taskId, assignmentKey, forUpdate = true)
                    val settings = reviewReminderSettings(this, taskId, review.scope, task.lifecycle)
                    execute(
                        "INSERT INTO review_reminder_overrides(task_id,github_user_id,request_occurrence_id," +
                            "hours,suppressed,lifecycle_version) " +
                            "VALUES(?,?,?,NULL,?,?) ON DUPLICATE KEY UPDATE suppressed=VALUES(suppressed)," +
                            "lifecycle_version=VALUES(lifecycle_version)",
                        taskId,
                        review.scope.githubId,
                        review.scope.occurrenceId,
                        suppressed,
                        task.lifecycle,
                    )
                    if (settings.second && !suppressed) {
                        execute(
                            "UPDATE reminder_states SET first_sent_at=NULL,next_due_at=NULL WHERE task_id=? AND assignee_key=?",
                            taskId,
                            review.scope.stateKey,
                        )
                    }
                    return@memberTransaction ReminderSettingsResponse(taskId, settings.first, suppressed, review.scope.assignmentKey)
                }
                execute(
                    "INSERT INTO task_reminder_overrides(task_id,hours,suppressed,lifecycle_version) VALUES(?,NULL,?,?) ON DUPLICATE KEY UPDATE suppressed=VALUES(suppressed),lifecycle_version=VALUES(lifecycle_version)",
                    taskId,
                    suppressed,
                    task.lifecycle,
                )
                if (task.suppressed && !suppressed) {
                    execute(
                        "UPDATE reminder_states SET first_sent_at=NULL,next_due_at=NULL WHERE task_id=? AND assignee_key NOT LIKE 'review:%'",
                        taskId,
                    )
                }
                ReminderSettingsResponse(taskId, task.overrideHours, suppressed)
            }
        }

    suspend fun boardPolicy(
        userId: Long,
        projectId: Long,
    ): BoardReminderPolicy =
        withContext(ioDispatcher) {
            dataSource.inTransaction(isolationLevel = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
                val actor = authorization.requireCurrent(this, userId, forUpdate = false)
                val product = projectProduct(this, projectId)
                if (!AuthorizationPolicy.canManageIntegrations(actor, product)) throw accessDenied()
                readBoardPolicy(this, projectId)
            }
        }

    suspend fun setBoardPolicy(
        userId: Long,
        projectId: Long,
        policy: BoardReminderPolicy,
    ): BoardReminderPolicy =
        withContext(ioDispatcher) {
            policy.defaultHours?.let(::validateHours)
            policy.priorityHours.values.forEach(::validateHours)
            dataSource.memberTransaction {
                val actor = authorization.requireCurrent(this, userId)
                if (!AuthorizationPolicy.canManageIntegrations(actor, projectProduct(this, projectId))) throw accessDenied()
                execute("DELETE FROM reminder_policies WHERE project_id=?", projectId)
                policy.defaultHours?.let {
                    execute(
                        "INSERT INTO reminder_policies(project_id,priority,hours) VALUES(?,'DEFAULT',?)",
                        projectId,
                        it,
                    )
                }
                policy.priorityHours.forEach { (priority, hours) ->
                    execute("INSERT INTO reminder_policies(project_id,priority,hours) VALUES(?,?,?)", projectId, priority, hours)
                }
                policy
            }
        }

    suspend fun globalPolicy(userId: Long): GlobalReminderPolicy =
        withContext(ioDispatcher) {
            dataSource.inTransaction(isolationLevel = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
                val actor = authorization.requireCurrent(this, userId, forUpdate = false)
                if (!canManageGlobal(actor)) throw accessDenied()
                GlobalReminderPolicy(
                    query("SELECT kind,hours FROM global_reminder_policies") {
                        TaskKind.valueOf(it.getString(1)) to
                            it.getInt(2)
                    }.toMap(),
                )
            }
        }

    suspend fun setGlobalPolicy(
        userId: Long,
        policy: GlobalReminderPolicy,
    ): GlobalReminderPolicy =
        withContext(ioDispatcher) {
            if (policy.hoursByKind.keys != TaskKind.entries.toSet()) throw ApiFailure.invalidRequest("모든 태스크 유형의 전역 기간을 지정해 주세요.")
            policy.hoursByKind.values.forEach(::validateHours)
            dataSource.memberTransaction {
                val actor = authorization.requireCurrent(this, userId)
                if (!canManageGlobal(actor)) throw accessDenied()
                policy.hoursByKind.forEach { (kind, hours) ->
                    execute(
                        "INSERT INTO global_reminder_policies(kind,hours) VALUES(?,?) ON DUPLICATE KEY UPDATE hours=VALUES(hours)",
                        kind,
                        hours,
                    )
                }
                policy
            }
        }

    private fun processTask(
        connection: Connection,
        task: ReminderTask,
        now: Instant,
    ) {
        val baseAssignments =
            connection.query("SELECT github_user_id,user_id FROM task_assignees WHERE task_id=? ORDER BY github_user_id", task.id) {
                ReminderSubject("user:${it.getLong("github_user_id")}", it.nullableLong("user_id"), task.kind, task.startedAt)
            }
        val reviews =
            if (task.kind == TaskKind.PULL_REQUEST) {
                connection.query(
                    "SELECT github_user_id,user_id,request_occurrence_id,requested_at FROM review_assignments WHERE task_id=? AND active=TRUE ORDER BY github_user_id",
                    task.id,
                ) {
                    ReminderSubject(
                        "review:${it.getLong("github_user_id")}:${digest(it.getString("request_occurrence_id"))}",
                        it.nullableLong("user_id"),
                        TaskKind.REVIEW_REQUEST,
                        requireNotNull(it.instant("requested_at")),
                        ReviewReminderScope(it.getLong("github_user_id"), it.getString("request_occurrence_id")),
                    )
                }
            } else {
                emptyList()
            }
        val subjects = baseAssignments.ifEmpty { listOf(ReminderSubject("unassigned", null, task.kind, task.startedAt)) } + reviews
        val existingKeys =
            connection.query(
                "SELECT assignee_key FROM reminder_states WHERE task_id=? FOR UPDATE",
                task.id,
            ) { it.getString(1) }
        (existingKeys - subjects.map { it.key }.toSet()).forEach { key ->
            connection.execute("DELETE FROM reminder_states WHERE task_id=? AND assignee_key=?", task.id, key)
        }
        val actors = currentNotificationActors(connection)
        val imminentRecipients = linkedSetOf<Long>()
        val overdueRecipients = linkedSetOf<Long>()
        val imminentKeys = mutableListOf<String>()
        val overdueKeys = mutableListOf<String>()
        subjects.forEach { subject ->
            val unavailable = subject.userId?.let { userId -> unavailableIntervals(connection, userId, subject.startedAt) }.orEmpty()
            val subjectTask =
                subject.reviewScope?.let { scope ->
                    val settings = reviewReminderSettings(connection, task.id, scope, task.lifecycle)
                    task.copy(overrideHours = settings.first, suppressed = settings.second)
                } ?: task
            val hours = effectiveHours(connection, subjectTask, subject.kind)
            val dueAt = ReminderTiming.dueAt(subject.startedAt, hours, task.targetDate, unavailable)
            val previous = readState(connection, task.id, subject.key)
            val freshLifecycle = previous == null || previous.lifecycle != task.lifecycle
            val targetChanged = freshLifecycle || previous.targetDate != task.targetDate
            val dueChanged = freshLifecycle || previous.dueAt != dueAt
            var imminentHandled = if (targetChanged) false else previous.imminentHandled
            var firstSent = if (dueChanged) null else previous.firstSentAt
            var nextDue = if (dueChanged) null else previous.nextDueAt
            val subjectEligible = subject.userId != null && actors.any { it.id == subject.userId }
            val positions =
                if (subjectEligible) {
                    connection
                        .query("SELECT position FROM user_positions WHERE user_id=?", subject.userId) {
                            TechnicalPosition.valueOf(it.getString(1))
                        }.toSet()
                } else {
                    emptySet()
                }

            fun recipients(imminent: Boolean): Set<Long> {
                if (subject.key ==
                    "unassigned"
                ) {
                    return actors.filter { it.currentStudent && it.role?.product == task.product }.map { it.id }.toSet()
                }
                if (!subjectEligible) return emptySet()
                return actors
                    .filter { actor ->
                        actor.id == subject.userId || (
                            actor.currentStudent && (
                                actor.role?.headPosition in positions ||
                                    if (imminent) actor.role?.product == task.product else actor.role == OrganizationRole.TEAM_LEADER
                            )
                        )
                    }.map { it.id }
                    .toSet()
            }

            if (task.targetDate != null && !imminentHandled && !now.isBefore(ReminderTiming.imminentAt(task.targetDate))) {
                if (now.isBefore(dueAt)) {
                    imminentRecipients.addAll(recipients(true).filterNot { isUnavailable(connection, it, now) })
                    imminentKeys.add(subject.key)
                }
                imminentHandled = true
            }
            if (!subjectTask.suppressed && !now.isBefore(dueAt) && (nextDue == null || !now.isBefore(nextDue))) {
                val slot = nextDue ?: now
                val subjectAway = subject.userId?.let { isUnavailable(connection, it, now) } == true
                val selected = if (subjectAway) emptySet() else recipients(false).filterNot { isUnavailable(connection, it, now) }.toSet()
                overdueRecipients.addAll(selected)
                overdueKeys.add("${subject.key}:$slot")
                if (selected.isNotEmpty() && firstSent == null) firstSent = now
                nextDue = ReminderTiming.nextRegularSlot(slot, now)
            }
            connection.execute(
                """
                INSERT INTO reminder_states(task_id,assignee_key,lifecycle_version,due_at,first_sent_at,next_due_at,target_date,imminent_handled)
                VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE lifecycle_version=VALUES(lifecycle_version),due_at=VALUES(due_at),first_sent_at=VALUES(first_sent_at),next_due_at=VALUES(next_due_at),target_date=VALUES(target_date),imminent_handled=VALUES(imminent_handled)
                """.trimIndent(),
                task.id,
                subject.key,
                task.lifecycle,
                dueAt,
                firstSent,
                nextDue,
                task.targetDate,
                imminentHandled,
            )
        }
        val unassigned = baseAssignments.isEmpty() && reviews.isEmpty()
        if (imminentKeys.isNotEmpty()) {
            sendReminder(
                connection,
                task,
                imminentRecipients,
                if (unassigned) "TASK_UNASSIGNED_DUE_SOON" else "TASK_DUE_SOON",
                "imminent:${task.targetDate}:${digest(imminentKeys.sorted().joinToString("|"))}",
                now,
            )
        }
        if (overdueKeys.isNotEmpty()) {
            sendReminder(
                connection,
                task,
                overdueRecipients,
                if (unassigned) "TASK_UNASSIGNED_OVERDUE" else "TASK_OVERDUE",
                "overdue:${digest(overdueKeys.sorted().joinToString("|"))}",
                now,
            )
        }
    }

    private fun sendReminder(
        connection: Connection,
        task: ReminderTask,
        recipients: Set<Long>,
        type: String,
        cycle: String,
        now: Instant,
    ) {
        val payload =
            buildJsonObject {
                put("taskId", task.id)
                put("productId", task.product.name)
                put("taskTitle", task.title)
            }.toString()
        recipients.forEach { userId ->
            insertFunctionalNotification(connection, userId, type, payload, "reminder:${task.id}:${task.lifecycle}:$cycle", now)
        }
    }
}

internal data class ReminderTask(
    val id: Long,
    val product: ProductId,
    val kind: TaskKind,
    val title: String,
    val active: Boolean,
    val startedAt: Instant,
    val lifecycle: Long,
    val boardId: Long?,
    val priority: TaskPriority,
    val targetDate: LocalDate?,
    val overrideHours: Int?,
    val suppressed: Boolean,
)

internal fun reminderTask(
    connection: Connection,
    id: Long,
    forUpdate: Boolean = false,
): ReminderTask? {
    val base =
        connection
            .query(
                "SELECT t.*,r.enabled AS repository_enabled,r.default_project_id FROM task_projections t " +
                    "LEFT JOIN repository_integrations r ON r.id=t.repository_id WHERE t.id=?" +
                    if (forUpdate) " FOR UPDATE" else "",
                id,
            ) { result ->
                TaskBase(
                    result.getLong("id"),
                    ProductId.valueOf(result.getString("product_id")),
                    TaskKind.valueOf(result.getString("kind")),
                    result.getString("title"),
                    result.getBoolean("is_open") && !result.getBoolean("is_completed"),
                    result.getBoolean("repository_enabled"),
                    result.instant("converted_at") ?: requireNotNull(result.instant("created_at")),
                    result.getLong("lifecycle_version"),
                    result.nullableLong("default_project_id"),
                )
            }.singleOrNull() ?: return null
    val boards =
        connection.query(
            "SELECT tp.* FROM task_projects tp JOIN project_integrations p ON p.id=tp.project_id WHERE tp.task_id=? AND p.enabled=TRUE ORDER BY p.id",
            id,
        ) {
            BoardFields(
                it.getLong("project_id"),
                it.getBoolean("archived"),
                TaskStatus.valueOf(it.getString("status")),
                TaskPriority.valueOf(it.getString("priority")),
                it.getDate("target_date")?.toLocalDate(),
            )
        }
    val board = if (base.kind == TaskKind.DRAFT) boards.singleOrNull() else boards.singleOrNull { it.id == base.defaultBoard }
    val override =
        connection
            .query("SELECT hours,suppressed,lifecycle_version FROM task_reminder_overrides WHERE task_id=?", id) {
                Triple(it.nullableInt("hours"), it.getBoolean("suppressed"), it.getLong("lifecycle_version"))
            }.singleOrNull()
    val active =
        base.open &&
            if (base.kind == TaskKind.DRAFT) board != null && !board.archived && board.status != TaskStatus.DONE else base.repositoryEnabled
    return ReminderTask(
        base.id,
        base.product,
        base.kind,
        base.title,
        active,
        base.startedAt,
        base.lifecycle,
        board?.id,
        board?.priority ?: TaskPriority.UNSET,
        board?.target,
        override?.first,
        override?.second == true && override.third == base.lifecycle,
    )
}

internal fun effectiveHours(
    connection: Connection,
    task: ReminderTask,
    kind: TaskKind = task.kind,
): Int {
    val board = task.boardId?.let { readBoardPolicy(connection, it) }
    val global = connection.query("SELECT hours FROM global_reminder_policies WHERE kind=?", kind) { it.getInt(1) }.singleOrNull()
    return ReminderTiming.thresholdHours(
        kind,
        task.priority,
        task.overrideHours,
        board?.priorityHours.orEmpty(),
        board?.defaultHours,
        global,
    )
}

internal fun unavailableIntervals(
    connection: Connection,
    userId: Long,
    start: Instant,
): List<UnavailableInterval> =
    connection.query(
        "SELECT starts_at,ends_at FROM unavailability_periods WHERE user_id=? AND ends_at>?",
        userId,
        start,
    ) { UnavailableInterval(requireNotNull(it.instant("starts_at")), requireNotNull(it.instant("ends_at"))) }

private data class TaskBase(
    val id: Long,
    val product: ProductId,
    val kind: TaskKind,
    val title: String,
    val open: Boolean,
    val repositoryEnabled: Boolean,
    val startedAt: Instant,
    val lifecycle: Long,
    val defaultBoard: Long?,
)

private data class BoardFields(
    val id: Long,
    val archived: Boolean,
    val status: TaskStatus,
    val priority: TaskPriority,
    val target: LocalDate?,
)

private data class ReminderSubject(
    val key: String,
    val userId: Long?,
    val kind: TaskKind,
    val startedAt: Instant,
    val reviewScope: ReviewReminderScope? = null,
)

private data class ReminderState(
    val lifecycle: Long,
    val dueAt: Instant?,
    val firstSentAt: Instant?,
    val nextDueAt: Instant?,
    val targetDate: LocalDate?,
    val imminentHandled: Boolean,
)

private fun readState(
    connection: Connection,
    taskId: Long,
    key: String,
): ReminderState? =
    connection
        .query("SELECT * FROM reminder_states WHERE task_id=? AND assignee_key=? FOR UPDATE", taskId, key) {
            ReminderState(
                it.getLong("lifecycle_version"),
                it.instant("due_at"),
                it.instant("first_sent_at"),
                it.instant("next_due_at"),
                it.getDate("target_date")?.toLocalDate(),
                it.getBoolean("imminent_handled"),
            )
        }.singleOrNull()

private fun readBoardPolicy(
    connection: Connection,
    id: Long,
): BoardReminderPolicy {
    val map =
        connection
            .query("SELECT priority,hours FROM reminder_policies WHERE project_id=?", id) {
                it.getString(1) to it.getInt(2)
            }.toMap()
    return BoardReminderPolicy(map["DEFAULT"], map.filterKeys { it != "DEFAULT" }.mapKeys { TaskPriority.valueOf(it.key) })
}

private fun projectProduct(
    connection: Connection,
    id: Long,
): ProductId =
    connection.query("SELECT product_id FROM project_integrations WHERE id=?", id) { ProductId.valueOf(it.getString(1)) }.singleOrNull()
        ?: throw taskNotFound()

private fun canSuppress(
    actor: AuthorizationSnapshot,
    product: ProductId,
): Boolean =
    actor.isEligible &&
        (
            actor.organizationRole == OrganizationRole.TEAM_LEADER || actor.organizationRole == OrganizationRole.C_LEADER ||
                actor.organizationRole?.headPosition != null ||
                actor.organizationRole?.product == product
        )

private fun canManageGlobal(actor: AuthorizationSnapshot): Boolean =
    actor.effectiveSystemAdministrator || actor.organizationRole == OrganizationRole.C_LEADER

private fun validateHours(hours: Int) {
    if (hours !in 1..87600) throw ApiFailure.invalidRequest("지연 기간은 1~87600시간으로 입력해 주세요.")
}

private fun taskNotFound(): ApiFailure = ApiFailure(HttpStatusCode.NotFound, ApiErrorCode.ResourceNotFound, "태스크 또는 보드를 찾을 수 없습니다.")

private fun digest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {
        "%02x".format(it)
    }
