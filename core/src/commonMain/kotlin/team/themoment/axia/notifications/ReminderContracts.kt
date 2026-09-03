package team.themoment.axia.notifications

import kotlinx.serialization.Serializable
import team.themoment.axia.tasks.TaskKind

@Serializable
data class GlobalReminderPolicy(
    val hoursByKind: Map<TaskKind, Int>,
)

@Serializable
data class ReminderSettingsResponse(
    val taskId: Long,
    val overrideHours: Int?,
    val suppressed: Boolean,
    val assignmentKey: String? = null,
)
