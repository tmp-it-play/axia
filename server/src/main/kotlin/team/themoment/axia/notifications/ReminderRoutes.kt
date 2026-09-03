package team.themoment.axia.notifications

import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.auth.AuthService
import team.themoment.axia.auth.requireAuthenticatedSession
import team.themoment.axia.plugins.preventSensitiveResponseCaching
import team.themoment.axia.reminders.ReminderService
import team.themoment.axia.tasks.BoardReminderPolicy
import team.themoment.axia.tasks.ReminderOverrideRequest
import team.themoment.axia.tasks.ReminderSuppressionRequest

fun Route.reminderRoutes(
    authService: AuthService,
    reminders: ReminderService,
) {
    put("/v1/tasks/{id}/reminder-override") {
        call.preventSensitiveResponseCaching()
        val actor = call.requireAuthenticatedSession(authService)
        val request = call.receive<ReminderOverrideRequest>()
        call.respond(reminders.setOverride(actor.userId, reminderId(call.parameters["id"]), request.hours, request.assignmentKey))
    }
    put("/v1/tasks/{id}/reminder-suppression") {
        call.preventSensitiveResponseCaching()
        val actor = call.requireAuthenticatedSession(authService)
        val request = call.receive<ReminderSuppressionRequest>()
        call.respond(
            reminders.setSuppressed(actor.userId, reminderId(call.parameters["id"]), request.suppressed, request.assignmentKey),
        )
    }
    get("/v1/projects/{id}/reminder-policy") {
        call.preventSensitiveResponseCaching()
        call.respond(reminders.boardPolicy(call.requireAuthenticatedSession(authService).userId, reminderId(call.parameters["id"])))
    }
    put("/v1/projects/{id}/reminder-policy") {
        call.preventSensitiveResponseCaching()
        val actor = call.requireAuthenticatedSession(authService)
        call.respond(reminders.setBoardPolicy(actor.userId, reminderId(call.parameters["id"]), call.receive<BoardReminderPolicy>()))
    }
    get("/v1/reminder-policy") {
        call.preventSensitiveResponseCaching()
        call.respond(reminders.globalPolicy(call.requireAuthenticatedSession(authService).userId))
    }
    put("/v1/reminder-policy") {
        call.preventSensitiveResponseCaching()
        val actor = call.requireAuthenticatedSession(authService)
        call.respond(reminders.setGlobalPolicy(actor.userId, call.receive<GlobalReminderPolicy>()))
    }
}

private fun reminderId(value: String?): Long =
    value?.toLongOrNull()?.takeIf { it > 0 } ?: throw ApiFailure.invalidRequest("태스크 또는 보드 식별자가 올바르지 않습니다.")
