package team.themoment.axia.analytics

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.authorization.AuthorizationPolicy
import team.themoment.axia.authorization.AuthorizationService
import team.themoment.axia.authorization.AuthorizationSnapshot
import team.themoment.axia.authorization.accessDenied
import team.themoment.axia.domain.ProductId
import team.themoment.axia.members.AnalyticsScope
import team.themoment.axia.members.AnalyticsScopeKind
import team.themoment.axia.persistence.inTransaction
import team.themoment.axia.persistence.instant
import team.themoment.axia.persistence.nullableLong
import team.themoment.axia.persistence.query
import team.themoment.axia.reminders.ReminderTiming
import team.themoment.axia.reminders.effectiveHours
import team.themoment.axia.reminders.reminderTask
import team.themoment.axia.reminders.unavailableIntervals
import java.sql.Connection
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.sql.DataSource

class AnalyticsService(
    private val dataSource: DataSource,
    private val authorization: AuthorizationService,
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun completions(
        userId: Long,
        range: AnalyticsRange = AnalyticsRange.LAST_30_DAYS,
        from: Instant? = null,
        until: Instant? = null,
    ): CompletionAnalyticsResponse =
        read { connection ->
            val actor = authorization.requireCurrent(connection, userId, forUpdate = false)
            val scope = requireScope(actor)
            val period = period(range, from, until, clock.instant())
            val filter = eventScope(scope)
            val where = "(? IS NULL OR e.occurred_at>=?) AND e.occurred_at<? AND ${filter.sql}"
            val values = listOf(period.from, period.from, period.until) + filter.values
            val first =
                connection
                    .query("SELECT MIN(e.occurred_at) AS first_event FROM completion_events e WHERE $where", *values.toTypedArray()) {
                        it.instant("first_event")
                    }.single()
            val startsAt = period.from ?: first ?: period.until
            val bucket = chooseBucket(startsAt, period.until)
            // Aggregate events directly; joining credits here would multiply team totals by assignees.
            val date = "DATE(DATE_ADD(e.occurred_at, INTERVAL 9 HOUR))"
            val bucketSql =
                when (bucket) {
                    AnalyticsBucket.DAY -> date
                    AnalyticsBucket.WEEK -> "DATE_SUB($date, INTERVAL WEEKDAY($date) DAY)"
                    AnalyticsBucket.MONTH -> "DATE_FORMAT($date, '%Y-%m-01')"
                }
            val grouped =
                connection
                    .query(
                        """
                        SELECT $bucketSql AS bucket_date,SUM(e.kind='ISSUE_CLOSE') AS issue_closes,
                            SUM(e.kind='PR_MERGE') AS pr_merges,COUNT(*) AS total
                        FROM completion_events e WHERE $where GROUP BY bucket_date ORDER BY bucket_date
                        """.trimIndent(),
                        *values.toTypedArray(),
                    ) {
                        CompletionBucket(
                            it.getString("bucket_date"),
                            it.getLong("issue_closes"),
                            it.getLong("pr_merges"),
                            it.getLong("total"),
                        )
                    }.associateBy { it.startsOn }
            val series =
                buildList {
                    var cursor = bucketStart(startsAt.atZone(ReminderTiming.seoul).toLocalDate(), bucket)
                    val last =
                        bucketStart(
                            period.until
                                .minusNanos(1)
                                .atZone(ReminderTiming.seoul)
                                .toLocalDate(),
                            bucket,
                        )
                    while (!cursor.isAfter(last)) {
                        add(grouped[cursor.toString()] ?: CompletionBucket(cursor.toString(), 0, 0, 0))
                        cursor =
                            when (bucket) {
                                AnalyticsBucket.DAY -> cursor.plusDays(1)
                                AnalyticsBucket.WEEK -> cursor.plusWeeks(1)
                                AnalyticsBucket.MONTH -> cursor.plusMonths(1)
                            }
                    }
                }
            CompletionAnalyticsResponse(
                scope,
                period.from?.toEpochMilli(),
                period.until.toEpochMilli(),
                bucket,
                series.sumOf { it.totalEvents },
                series.sumOf { it.issueCloses },
                series.sumOf { it.pullRequestMerges },
                series,
            )
        }

    suspend fun members(
        userId: Long,
        range: AnalyticsRange = AnalyticsRange.LAST_30_DAYS,
        from: Instant? = null,
        until: Instant? = null,
    ): MemberAnalyticsResponse =
        read { connection ->
            val actor = authorization.requireCurrent(connection, userId, forUpdate = false)
            val scope = requireScope(actor)
            val period = period(range, from, until, clock.instant())
            val users = memberScope(scope)
            val product = scope.productId
            val results =
                connection.query(
                    """
                    SELECT u.id,u.display_name,u.is_active,
                        COALESCE(SUM(CASE WHEN e.kind='ISSUE_CLOSE' THEN 1 ELSE 0 END),0) AS issue_closes,
                        COALESCE(SUM(CASE WHEN e.kind='PR_MERGE' THEN 1 ELSE 0 END),0) AS pr_merges,
                        COUNT(e.id) AS credits,AVG(e.processing_seconds) AS average_seconds
                    FROM users u LEFT JOIN completion_credits c ON c.user_id=u.id
                    LEFT JOIN completion_events e ON e.id=c.event_id AND (? IS NULL OR e.occurred_at>=?) AND e.occurred_at<? AND (? IS NULL OR e.product_id=?)
                    WHERE ${users.sql} GROUP BY u.id,u.display_name,u.is_active ORDER BY credits DESC,u.display_name,u.id
                    """.trimIndent(),
                    period.from,
                    period.from,
                    period.until,
                    product,
                    product,
                    *users.values.toTypedArray(),
                ) { result ->
                    val average = result.getDouble("average_seconds").takeUnless { result.wasNull() }
                    MemberCompletionMetrics(
                        result.getLong("id"),
                        result.getString("display_name"),
                        result.getBoolean("is_active"),
                        result.getLong("issue_closes"),
                        result.getLong("pr_merges"),
                        result.getLong("credits"),
                        average,
                    )
                }
            MemberAnalyticsResponse(scope, period.from?.toEpochMilli(), period.until.toEpochMilli(), results)
        }

    suspend fun operations(userId: Long): OperationsAnalyticsResponse =
        read { connection ->
            val actor = authorization.requireCurrent(connection, userId, forUpdate = false)
            val scope = requireScope(actor)
            val now = clock.instant()
            val taskFilter =
                when (scope.kind) {
                    AnalyticsScopeKind.ORGANIZATION -> {
                        SqlFilter("1=1")
                    }

                    AnalyticsScopeKind.PRODUCT -> {
                        SqlFilter("t.product_id=?", listOf(scope.productId))
                    }

                    AnalyticsScopeKind.TECHNICAL_POSITION -> {
                        SqlFilter(
                            "EXISTS(SELECT 1 FROM task_assignees a JOIN users u ON u.id=a.user_id JOIN user_positions p ON p.user_id=u.id WHERE a.task_id=t.id AND u.is_active=TRUE AND p.position=?)",
                            listOf(scope.position),
                        )
                    }

                    AnalyticsScopeKind.NONE -> {
                        throw accessDenied()
                    }
                }
            val ids =
                connection.query(
                    "SELECT t.id FROM task_projections t WHERE t.is_open=TRUE AND t.is_completed=FALSE AND ${taskFilter.sql}",
                    *taskFilter.values.toTypedArray(),
                ) {
                    it.getLong(1)
                }
            val counts = ProductId.entries.associateWith { longArrayOf(0, 0, 0) }.toMutableMap()
            ids.forEach { id ->
                val task = reminderTask(connection, id) ?: return@forEach
                if (!task.active) return@forEach
                val assignees = connection.query("SELECT user_id FROM task_assignees WHERE task_id=?", id) { it.nullableLong("user_id") }
                val due =
                    if (assignees.isEmpty()) {
                        listOf(
                            ReminderTiming.dueAt(task.startedAt, effectiveHours(connection, task), task.targetDate, emptyList()),
                        )
                    } else {
                        assignees.map { assignee ->
                            ReminderTiming.dueAt(
                                task.startedAt,
                                effectiveHours(connection, task),
                                task.targetDate,
                                assignee
                                    ?.let {
                                        unavailableIntervals(connection, it, task.startedAt)
                                    }.orEmpty(),
                            )
                        }
                    }
                val total = counts.getValue(task.product)
                total[0]++
                if (due.any { !now.isBefore(it) }) total[1]++
                if (assignees.isEmpty()) total[2]++
            }
            OperationsAnalyticsResponse(
                scope,
                counts.filterKeys { scope.productId == null || it == scope.productId }.map { (product, c) ->
                    ProductOperationsMetrics(product, c[0], c[1], c[2])
                },
            )
        }

    /** Completion history is operational data, not a graph: every currently eligible member may read it. */
    suspend fun history(
        userId: Long,
        beforeId: Long? = null,
        productId: ProductId? = null,
        limit: Int = 50,
        creditedUserId: Long? = null,
    ): CompletionHistoryResponse =
        read { connection ->
            authorization.requireCurrent(connection, userId, forUpdate = false)
            if (limit !in 1..100 || (beforeId != null && beforeId <= 0) ||
                (creditedUserId != null && creditedUserId <= 0)
            ) {
                throw ApiFailure.invalidRequest("페이지 요청이 올바르지 않습니다.")
            }
            val rows =
                connection.query(
                    """
                    SELECT e.*,t.title FROM completion_events e JOIN task_projections t ON t.id=e.task_id
                    WHERE (? IS NULL OR e.id<?) AND (? IS NULL OR e.product_id=?)
                      AND (? IS NULL OR EXISTS(SELECT 1 FROM completion_credits c WHERE c.event_id=e.id AND c.user_id=?))
                    ORDER BY e.id DESC LIMIT ?
                    """.trimIndent(),
                    beforeId,
                    beforeId,
                    productId,
                    productId,
                    creditedUserId,
                    creditedUserId,
                    limit + 1,
                ) { result ->
                    CompletionHistoryEntry(
                        result.getLong("id"),
                        result.getLong("task_id"),
                        result.getString("title"),
                        ProductId.valueOf(result.getString("product_id")),
                        CompletionKind.valueOf(result.getString("kind")),
                        requireNotNull(result.instant("occurred_at")).toEpochMilli(),
                        result.getLong("processing_seconds"),
                        emptyList(),
                    )
                }
            val output =
                rows.take(limit).map { entry ->
                    entry.copy(
                        creditedUserIds =
                            connection.query(
                                "SELECT user_id FROM completion_credits WHERE event_id=? ORDER BY user_id",
                                entry.id,
                            ) {
                                it.getLong(1)
                            },
                    )
                }
            CompletionHistoryResponse(output, rows.takeIf { it.size > limit }?.get(limit - 1)?.id)
        }

    private suspend fun <T> read(block: (Connection) -> T): T =
        withContext(ioDispatcher) {
            dataSource.inTransaction(isolationLevel = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) { block(this) }
        }
}

private fun requireScope(actor: AuthorizationSnapshot): AnalyticsScope =
    AuthorizationPolicy.analyticsScope(actor).also {
        if (it.kind ==
            AnalyticsScopeKind.NONE
        ) {
            throw accessDenied()
        }
    }

private data class SqlFilter(
    val sql: String,
    val values: List<Any?> = emptyList(),
)

private fun eventScope(scope: AnalyticsScope): SqlFilter =
    when (scope.kind) {
        AnalyticsScopeKind.ORGANIZATION -> {
            SqlFilter("1=1")
        }

        AnalyticsScopeKind.PRODUCT -> {
            SqlFilter("e.product_id=?", listOf(scope.productId))
        }

        AnalyticsScopeKind.TECHNICAL_POSITION -> {
            SqlFilter(
                "EXISTS(SELECT 1 FROM completion_credits c JOIN users u ON u.id=c.user_id JOIN user_positions p ON p.user_id=u.id WHERE c.event_id=e.id AND u.is_active=TRUE AND p.position=?)",
                listOf(scope.position),
            )
        }

        AnalyticsScopeKind.NONE -> {
            throw accessDenied()
        }
    }

private fun memberScope(scope: AnalyticsScope): SqlFilter =
    when (scope.kind) {
        AnalyticsScopeKind.ORGANIZATION -> {
            SqlFilter("1=1")
        }

        AnalyticsScopeKind.PRODUCT -> {
            SqlFilter(
                "u.is_active=TRUE AND EXISTS(SELECT 1 FROM user_pocs p WHERE p.user_id=u.id AND p.product_id=?)",
                listOf(scope.productId),
            )
        }

        AnalyticsScopeKind.TECHNICAL_POSITION -> {
            SqlFilter(
                "u.is_active=TRUE AND EXISTS(SELECT 1 FROM user_positions p WHERE p.user_id=u.id AND p.position=?)",
                listOf(scope.position),
            )
        }

        AnalyticsScopeKind.NONE -> {
            throw accessDenied()
        }
    }

private data class AnalyticsPeriod(
    val from: Instant?,
    val until: Instant,
)

private fun period(
    range: AnalyticsRange,
    from: Instant?,
    until: Instant?,
    now: Instant,
): AnalyticsPeriod {
    val end = until ?: now
    val start =
        when (range) {
            AnalyticsRange.LAST_7_DAYS -> end.minus(Duration.ofDays(7))
            AnalyticsRange.LAST_30_DAYS -> end.minus(Duration.ofDays(30))
            AnalyticsRange.LAST_90_DAYS -> end.minus(Duration.ofDays(90))
            AnalyticsRange.CUSTOM -> from ?: throw ApiFailure.invalidRequest("사용자 지정 기간의 시작 시각이 필요합니다.")
            AnalyticsRange.ALL -> null
        }
    if ((start != null && !start.isBefore(end)) || end > now || end < MIN_TIME || end > MAX_TIME ||
        (start != null && start < MIN_TIME)
    ) {
        throw ApiFailure.invalidRequest("분석 기간이 올바르지 않습니다.")
    }
    return AnalyticsPeriod(start, end)
}

private fun chooseBucket(
    from: Instant,
    until: Instant,
): AnalyticsBucket =
    when {
        Duration.between(from, until) <= Duration.ofDays(31) -> AnalyticsBucket.DAY
        Duration.between(from, until) <= Duration.ofDays(180) -> AnalyticsBucket.WEEK
        else -> AnalyticsBucket.MONTH
    }

private fun bucketStart(
    date: LocalDate,
    bucket: AnalyticsBucket,
): LocalDate =
    when (bucket) {
        AnalyticsBucket.DAY -> date
        AnalyticsBucket.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        AnalyticsBucket.MONTH -> date.withDayOfMonth(1)
    }

private val MIN_TIME: Instant = Instant.parse("1000-01-01T00:00:00Z")
private val MAX_TIME: Instant = Instant.parse("9999-12-31T23:59:59.999Z")
