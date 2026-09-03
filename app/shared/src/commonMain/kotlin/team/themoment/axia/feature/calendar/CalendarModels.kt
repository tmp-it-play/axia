package team.themoment.axia.feature.calendar

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import team.themoment.axia.domain.ProductId
import team.themoment.axia.domain.TechnicalPosition
import team.themoment.axia.feature.tasks.TaskWriteCommand
import team.themoment.axia.members.MemberSummary
import team.themoment.axia.platform.AxiaTime
import team.themoment.axia.tasks.ProjectFieldsUpdate
import team.themoment.axia.tasks.TaskKind
import team.themoment.axia.tasks.TaskProjectContext
import team.themoment.axia.tasks.TaskSummary
import team.themoment.axia.unavailability.UnavailabilityPeriod
import team.themoment.axia.unavailability.UnavailabilityType
import team.themoment.axia.unavailability.UnavailabilityUpdateRequest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

enum class CalendarEntryType(
    val label: String,
) {
    VACATION("휴가"),
    UNAVAILABLE("업무 불가"),
    START("시작일"),
    TARGET("마감일"),
}

data class CalendarFilters(
    val productId: ProductId? = null,
    val userId: Long? = null,
    val position: TechnicalPosition? = null,
    val type: CalendarEntryType? = null,
)

data class CalendarEntry(
    val key: String,
    val date: LocalDate,
    val type: CalendarEntryType,
    val title: String,
    val description: String,
    val task: TaskSummary? = null,
    val project: TaskProjectContext? = null,
    val period: UnavailabilityPeriod? = null,
)

data class CalendarCallbacks(
    val onMonthChange: (LocalDate) -> Unit,
    val onFiltersChange: (CalendarFilters) -> Unit,
    val onOpenTask: (Long) -> Unit,
    val onCreatePeriodRange: (Long, LocalDate, LocalDate) -> Unit,
    val onMoveTarget: (TaskSummary, TaskProjectContext, LocalDate) -> Unit,
    val onMovePeriod: (UnavailabilityPeriod, LocalDate, LocalDate) -> Unit,
)

/** Monday-first, six complete weeks. All boundaries refer to Asia/Seoul. */
fun calendarDays(month: LocalDate): List<LocalDate> {
    val first = LocalDate(month.year, month.month, 1)
    val start = first.minus(first.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    return List(42) { start.plus(it, DateTimeUnit.DAY) }
}

fun calendarEntries(
    days: List<LocalDate>,
    tasks: List<TaskSummary>,
    periods: List<UnavailabilityPeriod>,
    members: List<MemberSummary>,
    filters: CalendarFilters,
): Map<LocalDate, List<CalendarEntry>> {
    val visible = days.toSet()
    val people = members.associateBy { it.id }
    val entries = mutableListOf<CalendarEntry>()
    tasks
        .asSequence()
        .filter { it.kind != TaskKind.REVIEW_REQUEST }
        .distinctBy { it.id }
        .filter { filters.productId == null || it.productId == filters.productId }
        .filter { filters.userId == null || it.assignees.any { assignee -> assignee.userId == filters.userId } }
        .filter { filters.position == null || it.assignees.any { assignee -> filters.position in assignee.positions } }
        .forEach { task ->
            task.projects.filterNot { it.archived }.forEach { project ->
                listOf(CalendarEntryType.START to project.startDate, CalendarEntryType.TARGET to project.targetDate)
                    .filter { filters.type == null || it.first == filters.type }
                    .forEach { (type, rawDate) ->
                        val date = rawDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                        if (date != null && date in visible) {
                            entries +=
                                CalendarEntry(
                                    "task:${task.id}:${project.projectId}:${type.name}",
                                    date,
                                    type,
                                    task.title,
                                    "${task.productId.displayName} · Project #${project.projectNumber}",
                                    task = task,
                                    project = project,
                                )
                        }
                    }
            }
        }
    periods.forEach { period ->
        val member = people[period.userId]
        val type = if (period.type == UnavailabilityType.VACATION) CalendarEntryType.VACATION else CalendarEntryType.UNAVAILABLE
        if ((filters.type != null && filters.type != type) ||
            (filters.userId != null && filters.userId != period.userId) ||
            (filters.productId != null && filters.productId !in member?.pocs.orEmpty()) ||
            (filters.position != null && filters.position !in member?.positions.orEmpty())
        ) {
            return@forEach
        }
        days.forEach { date ->
            val from = date.atStartOfDayIn(AxiaTime.zone).toEpochMilliseconds()
            val until = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(AxiaTime.zone).toEpochMilliseconds()
            if (period.startsAtEpochMillis < until && period.endsAtEpochMillis > from) {
                entries +=
                    CalendarEntry(
                        "period:${period.id}:$date",
                        date,
                        type,
                        member?.displayName ?: "구성원 #${period.userId}",
                        "${AxiaTime.label(period.startsAtEpochMillis)} → ${AxiaTime.label(period.endsAtEpochMillis)} · ${period.reason}",
                        period = period,
                    )
            }
        }
    }
    return entries.sortedWith(compareBy<CalendarEntry> { it.type.ordinal }.thenBy { it.title }.thenBy { it.key }).groupBy { it.date }
}

fun movedPeriod(
    period: UnavailabilityPeriod,
    from: LocalDate,
    to: LocalDate,
): UnavailabilityUpdateRequest {
    val shift = to.atStartOfDayIn(AxiaTime.zone).toEpochMilliseconds() - from.atStartOfDayIn(AxiaTime.zone).toEpochMilliseconds()
    return UnavailabilityUpdateRequest(
        period.type,
        period.startsAtEpochMillis + shift,
        period.endsAtEpochMillis + shift,
        period.reason,
        period.version,
    )
}

@OptIn(ExperimentalUuidApi::class)
fun calendarTargetCommand(
    task: TaskSummary,
    project: TaskProjectContext,
    date: String?,
    operationId: String = Uuid.random().toString(),
    issuedAtEpochMillis: Long = AxiaTime.nowEpochMillis(),
): TaskWriteCommand {
    require(task.canWrite && task.kind != TaskKind.REVIEW_REQUEST && project in task.projects && !project.archived)
    require(task.revision.matches(Regex("[0-9a-f]{64}")))
    date?.let { LocalDate.parse(it) }
    val request =
        ProjectFieldsUpdate(
            operationId,
            project.projectId,
            task.updatedAtEpochMillis,
            project.status,
            project.priority,
            project.startDate,
            date,
            task.revision,
            issuedAtEpochMillis,
        )
    return TaskWriteCommand(
        operationId,
        issuedAtEpochMillis,
        "PUT",
        "/v1/tasks/${task.id}/project-fields",
        Json.encodeToString(request),
        task.id,
    )
}
