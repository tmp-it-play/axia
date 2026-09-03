package team.themoment.axia.products

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.auth.AuthService
import team.themoment.axia.auth.requireAuthenticatedSession
import team.themoment.axia.domain.ProductId
import team.themoment.axia.plugins.preventSensitiveResponseCaching

fun Route.integrationRoutes(
    auth: AuthService,
    service: IntegrationService,
) {
    route("/v1") {
        get("/products") {
            call.preventSensitiveResponseCaching()
            call.respond(service.products(call.requireAuthenticatedSession(auth).userId))
        }
        get("/products/{product}/integrations") {
            call.preventSensitiveResponseCaching()
            call.respond(service.integrations(call.requireAuthenticatedSession(auth).userId, call.product()))
        }
        post("/products/{product}/repositories") {
            call.respond(
                HttpStatusCode.Created,
                service.addRepository(call.requireAuthenticatedSession(auth).userId, call.product(), call.receive()),
            )
        }
        post("/products/{product}/projects") {
            call.respond(
                HttpStatusCode.Created,
                service.addProject(call.requireAuthenticatedSession(auth).userId, call.product(), call.receive()),
            )
        }
        patch("/products/{product}/repositories/{id}") {
            call.respond(
                service.updateRepository(
                    call.requireAuthenticatedSession(auth).userId,
                    call.product(),
                    call.integrationId(),
                    call.receive(),
                ),
            )
        }
        patch("/products/{product}/projects/{id}") {
            call.respond(
                service.updateProject(call.requireAuthenticatedSession(auth).userId, call.product(), call.integrationId(), call.receive()),
            )
        }
        put("/repositories/{id}/default-project") {
            call.respond(service.setDefaultProject(call.requireAuthenticatedSession(auth).userId, call.integrationId(), call.receive()))
        }
        put("/projects/{id}/field-mappings") {
            call.respond(service.setMappings(call.requireAuthenticatedSession(auth).userId, call.integrationId(), call.receive()))
        }
        get("/projects/{id}/field-catalog") {
            call.respond(service.fieldCatalog(call.requireAuthenticatedSession(auth).userId, call.integrationId()))
        }
    }
}

private fun ApplicationCall.product(): ProductId =
    parameters["product"]?.let { value -> ProductId.entries.singleOrNull { it.name == value } }
        ?: throw ApiFailure.invalidRequest("제품 ID를 확인해 주세요.")

private fun ApplicationCall.integrationId(): Long =
    parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw ApiFailure.invalidRequest("연동 ID를 확인해 주세요.")
