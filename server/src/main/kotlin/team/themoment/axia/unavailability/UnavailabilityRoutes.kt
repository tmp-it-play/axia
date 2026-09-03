package team.themoment.axia.unavailability

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.auth.AuthService
import team.themoment.axia.auth.requireAuthenticatedSession
import team.themoment.axia.domain.TechnicalPosition
import team.themoment.axia.plugins.preventSensitiveResponseCaching
import java.time.Instant

fun Route.unavailabilityRoutes(
    authService: AuthService,
    unavailability: UnavailabilityService,
) {
    route("/v1/unavailability") {
        get {
            call.preventSensitiveResponseCaching()
            val actor = call.requireAuthenticatedSession(authService)
            val query = call.request.queryParameters
            val from = query["fromEpochMillis"]?.toLongOrNull() ?: throw invalidFilter()
            val until = query["untilEpochMillis"]?.toLongOrNull() ?: throw invalidFilter()
            val userId = query["userId"]?.let { it.toLongOrNull()?.takeIf { id -> id > 0 } ?: throw invalidFilter() }
            val position =
                query["position"]?.let { value ->
                    TechnicalPosition.entries.firstOrNull { it.name == value }
                        ?: throw invalidFilter()
                }
            val type = query["type"]?.let { value -> UnavailabilityType.entries.firstOrNull { it.name == value } ?: throw invalidFilter() }
            call.respond(unavailability.list(actor.userId, Instant.ofEpochMilli(from), Instant.ofEpochMilli(until), userId, position, type))
        }
        post {
            call.preventSensitiveResponseCaching()
            val actor = call.requireAuthenticatedSession(authService)
            call.respond(HttpStatusCode.Created, unavailability.create(actor.userId, call.receive<UnavailabilityCreateRequest>()))
        }
        patch("/{id}") {
            call.preventSensitiveResponseCaching()
            val actor = call.requireAuthenticatedSession(authService)
            call.respond(unavailability.update(actor.userId, periodId(call.parameters["id"]), call.receive<UnavailabilityUpdateRequest>()))
        }
        delete("/{id}") {
            call.preventSensitiveResponseCaching()
            val actor = call.requireAuthenticatedSession(authService)
            val version =
                call.request.queryParameters["expectedVersion"]
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 } ?: throw invalidFilter()
            unavailability.delete(actor.userId, periodId(call.parameters["id"]), version)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun periodId(value: String?): Long = value?.toLongOrNull()?.takeIf { it > 0 } ?: throw invalidFilter()

private fun invalidFilter(): ApiFailure = ApiFailure.invalidRequest("기간 또는 필터 값을 올바르게 입력해 주세요.")
