package team.themoment.axia.tasks

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.auth.AuthService
import team.themoment.axia.auth.requireAuthenticatedSession
import team.themoment.axia.plugins.preventSensitiveResponseCaching

fun Route.taskMutationRoutes(
    auth: AuthService,
    service: TaskMutationService,
) {
    suspend fun ApplicationCall.user(): Long {
        preventSensitiveResponseCaching()
        return requireAuthenticatedSession(auth).userId
    }
    post("/v1/issues") { call.respond(service.createIssue(call.user(), call.receive())) }
    patch("/v1/issues/{id}") { call.respond(service.updateIssue(call.user(), call.mutationId(), call.receive())) }
    post("/v1/issues/{id}/close") { call.respond(service.issueState(call.user(), call.mutationId(), call.receive(), false)) }
    post("/v1/issues/{id}/reopen") { call.respond(service.issueState(call.user(), call.mutationId(), call.receive(), true)) }
    post("/v1/issues/{id}/comments") { call.respond(service.comment(call.user(), call.mutationId(), call.receive(), TaskKind.ISSUE)) }
    patch("/v1/pull-requests/{id}") { call.respond(service.updatePullRequest(call.user(), call.mutationId(), call.receive())) }
    post("/v1/pull-requests/{id}/comments") {
        call.respond(service.comment(call.user(), call.mutationId(), call.receive(), TaskKind.PULL_REQUEST))
    }
    put("/v1/pull-requests/{id}/reviewers") { call.respond(service.reviewers(call.user(), call.mutationId(), call.receive())) }
    put("/v1/tasks/{id}/assignees") { call.respond(service.assign(call.user(), call.mutationId(), call.receive())) }
    put("/v1/tasks/{id}/project-fields") { call.respond(service.projectFields(call.user(), call.mutationId(), call.receive())) }
    post("/v1/tasks/{id}/projects") { call.respond(service.addProject(call.user(), call.mutationId(), call.receive())) }
    put("/v1/tasks/{id}/project-archive") { call.respond(service.archive(call.user(), call.mutationId(), call.receive())) }
    post("/v1/projects/{id}/drafts") { call.respond(service.createDraft(call.user(), call.mutationId(), call.receive())) }
    patch("/v1/drafts/{id}") { call.respond(service.updateDraft(call.user(), call.mutationId(), call.receive())) }
    post("/v1/drafts/{id}/convert") { call.respond(service.convertDraft(call.user(), call.mutationId(), call.receive())) }
}

private fun ApplicationCall.mutationId(): Long =
    parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw ApiFailure.invalidRequest("태스크 또는 보드 ID를 확인해 주세요.")
