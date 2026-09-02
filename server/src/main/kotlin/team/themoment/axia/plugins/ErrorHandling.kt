package team.themoment.axia.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import team.themoment.axia.api.ApiError
import team.themoment.axia.api.ApiErrorCode
import team.themoment.axia.api.ApiFailure
import kotlin.coroutines.cancellation.CancellationException

fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<CancellationException> { _, cancellation ->
            throw cancellation
        }

        exception<ApiFailure> { call, failure ->
            call.respond(
                failure.status,
                ApiError(
                    code = failure.code,
                    message = failure.publicMessage,
                    requestId = call.callId,
                ),
            )
        }

        exception<BadRequestException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError(
                    code = ApiErrorCode.InvalidRequest,
                    message = "요청 형식이 올바르지 않습니다.",
                    requestId = call.callId,
                ),
            )
        }

        exception<Throwable> { call, cause ->
            this@configureErrorHandling.environment.log.error(
                "Unhandled server failure requestId={} type={}",
                call.callId,
                cause::class.qualifiedName,
            )
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError(
                    code = ApiErrorCode.InternalServerError,
                    message = "요청을 처리하지 못했습니다.",
                    requestId = call.callId,
                ),
            )
        }
    }
}
