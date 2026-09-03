package team.themoment.axia.reminders

import team.themoment.axia.tasks.TaskKind
import team.themoment.axia.tasks.TaskPriority
import team.themoment.axia.tasks.TaskUrgency
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class UnavailableInterval(
    val startsAt: Instant,
    val endsAt: Instant,
) {
    init {
        require(endsAt.isAfter(startsAt))
    }

    fun contains(instant: Instant): Boolean = !instant.isBefore(startsAt) && instant.isBefore(endsAt)
}

/** Date-only GitHub fields use Korean calendar boundaries; elapsed durations always use real UTC time. */
object ReminderTiming {
    val seoul: ZoneId = ZoneId.of("Asia/Seoul")
    val repeatInterval: Duration = Duration.ofHours(72)

    fun thresholdHours(
        kind: TaskKind,
        priority: TaskPriority,
        taskOverride: Int?,
        boardPriorityHours: Map<TaskPriority, Int>,
        boardDefaultHours: Int?,
        globalHours: Int? = null,
    ): Int =
        (taskOverride ?: boardPriorityHours[priority] ?: boardDefaultHours ?: globalHours ?: defaultHours(kind)).also { require(it > 0) }

    fun defaultHours(kind: TaskKind): Int =
        when (kind) {
            TaskKind.ISSUE, TaskKind.DRAFT -> 14 * 24
            TaskKind.PULL_REQUEST, TaskKind.REVIEW_REQUEST -> 3 * 24
        }

    fun dueAt(
        start: Instant,
        hours: Int,
        targetDate: LocalDate?,
        unavailable: List<UnavailableInterval>,
    ): Instant {
        require(hours > 0)
        if (targetDate != null) return targetDate.plusDays(1).atStartOfDay(seoul).toInstant()
        var cursor = start
        var remaining = Duration.ofHours(hours.toLong())
        union(unavailable).forEach { interval ->
            if (!interval.endsAt.isAfter(cursor)) return@forEach
            val available = if (interval.startsAt.isAfter(cursor)) Duration.between(cursor, interval.startsAt) else Duration.ZERO
            if (remaining <= available) return cursor.plus(remaining)
            remaining = remaining.minus(available)
            cursor = interval.endsAt
        }
        return cursor.plus(remaining)
    }

    fun imminentAt(targetDate: LocalDate): Instant = targetDate.minusDays(3).atStartOfDay(seoul).toInstant()

    fun urgency(
        now: Instant,
        dueAt: Instant,
        targetDate: LocalDate?,
    ): TaskUrgency =
        when {
            !now.isBefore(dueAt) -> TaskUrgency.OVERDUE
            targetDate != null && !now.isBefore(imminentAt(targetDate)) -> TaskUrgency.DUE_SOON
            else -> TaskUrgency.NORMAL
        }

    /** Advances over missed slots without shifting the original 72-hour cadence or sending a catch-up burst. */
    fun nextRegularSlot(
        previousSlot: Instant,
        now: Instant,
    ): Instant {
        if (now.isBefore(previousSlot)) return previousSlot
        val elapsed = Duration.between(previousSlot, now).seconds
        return previousSlot.plusSeconds((elapsed / repeatInterval.seconds + 1) * repeatInterval.seconds)
    }

    fun union(intervals: List<UnavailableInterval>): List<UnavailableInterval> {
        val result = mutableListOf<UnavailableInterval>()
        intervals.sortedBy { it.startsAt }.forEach { interval ->
            val previous = result.lastOrNull()
            if (previous == null || interval.startsAt.isAfter(previous.endsAt)) {
                result += interval
            } else if (interval.endsAt.isAfter(previous.endsAt)) {
                result[result.lastIndex] = UnavailableInterval(previous.startsAt, interval.endsAt)
            }
        }
        return result
    }
}
