package team.themoment.axia.tasks

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.auth.AuthService
import team.themoment.axia.auth.requireAuthenticatedSession
import team.themoment.axia.domain.ProductId
import team.themoment.axia.domain.TechnicalPosition
import team.themoment.axia.plugins.preventSensitiveResponseCaching

fun Route.taskRoutes(
    auth: AuthService,
    service: TaskService,
) {
    route("/v1/tasks") {
        get {
            call.preventSensitiveResponseCaching()
            val user = call.requireAuthenticatedSession(auth).userId
            val parameters = call.request.queryParameters

            fun value(name: String): String? =
                parameters.getAll(name)?.let {
                    if (it.size != 1) throw ApiFailure.invalidRequest("조회 조건을 한 번만 지정해 주세요.")
                    it.single()
                }

            fun flag(name: String): Boolean =
                value(name)?.toBooleanStrictOrNull()
                    ?: if (value(name) == null) false else throw ApiFailure.invalidRequest("조회 조건을 확인해 주세요.")
            val query =
                TaskQuery(
                    productId =
                        value("productId")?.let { text ->
                            ProductId.entries.singleOrNull { it.name == text }
                                ?: throw ApiFailure.invalidRequest("제품 ID를 확인해 주세요.")
                        },
                    userId =
                        value("userId")?.let {
                            it.toLongOrNull()?.takeIf { number -> number > 0 }
                                ?: throw ApiFailure.invalidRequest("구성원 ID를 확인해 주세요.")
                        },
                    position =
                        value("position")?.let { text ->
                            TechnicalPosition.entries.singleOrNull { it.name == text }
                                ?: throw ApiFailure.invalidRequest("기술 포지션을 확인해 주세요.")
                        },
                    kind =
                        value("kind")?.let { text ->
                            TaskKind.entries.singleOrNull { it.name == text }
                                ?: throw ApiFailure.invalidRequest("태스크 종류를 확인해 주세요.")
                        },
                    unassignedOnly = flag("unassignedOnly"),
                    includeClosed = flag("includeClosed"),
                    search =
                        value("search").orEmpty().also {
                            if (it.length >
                                256
                            ) {
                                throw ApiFailure.invalidRequest("검색어는 256자 이내로 입력해 주세요.")
                            }
                        },
                    cursor =
                        value("cursor")?.let {
                            it.toLongOrNull()?.takeIf { number -> number in 0..Int.MAX_VALUE }
                                ?: throw ApiFailure.invalidRequest("목록 커서를 확인해 주세요.")
                        }
                            ?: 0,
                )
            call.respond(service.list(user, query))
        }
        get("/{id}") {
            call.preventSensitiveResponseCaching()
            val user = call.requireAuthenticatedSession(auth).userId
            val id = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw ApiFailure.invalidRequest("태스크 ID를 확인해 주세요.")
            val assignmentKey =
                call.request.queryParameters.getAll("assignmentKey")?.let {
                    if (it.size != 1) throw ApiFailure.invalidRequest("리뷰 요청을 한 번만 지정해 주세요.")
                    it.single()
                }
            call.respond(service.detail(user, id, assignmentKey))
        }
    }
}
