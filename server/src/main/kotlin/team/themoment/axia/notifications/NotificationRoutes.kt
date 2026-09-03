package team.themoment.axia.notifications

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.auth.AuthService
import team.themoment.axia.auth.requireAuthenticatedSession
import team.themoment.axia.plugins.preventSensitiveResponseCaching

fun Route.notificationRoutes(
    authService: AuthService,
    notifications: NotificationService,
) {
    route("/v1/notifications") {
        get {
            call.preventSensitiveResponseCaching()
            val actor = call.requireAuthenticatedSession(authService)
            val query = call.request.queryParameters
            val before = query["beforeId"]?.let { it.toLongOrNull() ?: throw invalidPage() }
            val limit = query["limit"]?.let { it.toIntOrNull() ?: throw invalidPage() } ?: 50
            val unread = query["unreadOnly"]?.let { it.toBooleanStrictOrNull() ?: throw invalidPage() } ?: false
            call.respond(notifications.list(actor.userId, before, unread, limit))
        }
        post("/{id}/read") {
            call.preventSensitiveResponseCaching()
            val actor = call.requireAuthenticatedSession(authService)
            val id = call.parameters["id"]?.toLongOrNull() ?: throw invalidPage()
            notifications.markRead(actor.userId, id)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/read-all") {
            call.preventSensitiveResponseCaching()
            notifications.markAllRead(call.requireAuthenticatedSession(authService).userId)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/native/claim") {
            call.preventSensitiveResponseCaching()
            val actor = call.requireAuthenticatedSession(authService)
            call.respond(notifications.claimNative(actor.userId, actor.sessionId))
        }
        post("/native/ack") {
            call.preventSensitiveResponseCaching()
            val actor = call.requireAuthenticatedSession(authService)
            notifications.acknowledgeNative(actor.userId, actor.sessionId, call.receive<NativeNotificationAckRequest>())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun invalidPage(): ApiFailure = ApiFailure.invalidRequest("알림 조회 요청이 올바르지 않습니다.")
