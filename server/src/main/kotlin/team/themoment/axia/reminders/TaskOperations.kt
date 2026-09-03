package team.themoment.axia.reminders

import team.themoment.axia.analytics.ProductOperationsMetrics
import team.themoment.axia.domain.ProductId
import team.themoment.axia.persistence.nullableLong
import team.themoment.axia.persistence.query
import java.sql.Connection
import java.time.Instant

/** Canonical tasks count once even when several assignees are independently overdue. */
internal fun taskOperations(
    connection: Connection,
    ids: List<Long>,
    now: Instant,
): List<ProductOperationsMetrics> {
    val counts = ProductId.entries.associateWith { longArrayOf(0, 0, 0) }
    ids.forEach { id ->
        val task = reminderTask(connection, id) ?: return@forEach
        if (!task.active) return@forEach
        val assignees = connection.query("SELECT user_id FROM task_assignees WHERE task_id=?", id) { it.nullableLong("user_id") }
        val dueDates =
            assignees.ifEmpty { listOf(null) }.map { userId ->
                ReminderTiming.dueAt(
                    task.startedAt,
                    effectiveHours(connection, task),
                    task.targetDate,
                    userId
                        ?.let {
                            unavailableIntervals(connection, it, task.startedAt)
                        }.orEmpty(),
                )
            }
        val count = counts.getValue(task.product)
        count[0]++
        if (dueDates.any { !now.isBefore(it) }) count[1]++
        if (assignees.isEmpty()) count[2]++
    }
    return counts.map { (product, count) -> ProductOperationsMetrics(product, count[0], count[1], count[2]) }
}
