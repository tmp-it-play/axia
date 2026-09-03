package team.themoment.axia.members

import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.auth.AuthService
import team.themoment.axia.auth.requireAuthenticatedSession
import team.themoment.axia.plugins.preventSensitiveResponseCaching

fun Route.memberRoutes(
    authService: AuthService,
    members: MemberService,
) {
    get("/v1/me") {
        call.preventSensitiveResponseCaching()
        call.respond(members.currentUser(call.requireAuthenticatedSession(authService).userId))
    }
    route("/v1/members") {
        get {
            call.preventSensitiveResponseCaching()
            val actor = call.requireAuthenticatedSession(authService)
            val activeOnly = call.request.queryParameters["activeOnly"].strictOptionalBoolean() ?: true
            val unassigned = call.request.queryParameters["unassignedPositionsOnly"].strictOptionalBoolean() ?: false
            call.respond(members.listMembers(actor.userId, activeOnly, unassigned))
        }
        get("/{id}") {
            call.preventSensitiveResponseCaching()
            call.respond(members.memberDetail(call.requireAuthenticatedSession(authService).userId, memberId(call.parameters["id"])))
        }
        put("/{id}/organization-role") {
            call.preventSensitiveResponseCaching()
            val actor = call.requireAuthenticatedSession(authService)
            val request = call.receive<OrganizationRoleUpdateRequest>()
            call.respond(members.setOrganizationRole(actor.userId, memberId(call.parameters["id"]), request.role))
        }
        put("/{id}/positions") {
            call.preventSensitiveResponseCaching()
            val actor = call.requireAuthenticatedSession(authService)
            val request = call.receive<PositionsUpdateRequest>()
            call.respond(members.setPositions(actor.userId, memberId(call.parameters["id"]), request.positions))
        }
        put("/{id}/pocs") {
            call.preventSensitiveResponseCaching()
            val actor = call.requireAuthenticatedSession(authService)
            val request = call.receive<PocsUpdateRequest>()
            call.respond(members.setPocs(actor.userId, memberId(call.parameters["id"]), request.products))
        }
        put("/{id}/explicit-admin") {
            call.preventSensitiveResponseCaching()
            val actor = call.requireAuthenticatedSession(authService)
            val request = call.receive<ExplicitAdminUpdateRequest>()
            call.respond(members.setExplicitAdmin(actor.userId, memberId(call.parameters["id"]), request.enabled))
        }
    }
}

private fun memberId(value: String?): Long = value?.toLongOrNull()?.takeIf { it > 0 } ?: throw memberNotFound()

private fun String?.strictOptionalBoolean(): Boolean? =
    when (this) {
        null -> null
        "true" -> true
        "false" -> false
        else -> throw ApiFailure.invalidRequest("올바른 필터 값을 입력해 주세요.")
    }
