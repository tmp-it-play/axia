package team.themoment.axia.auth

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.plugins.preventSensitiveResponseCaching

fun Route.authRoutes(authService: AuthService) {
    route("/v1/auth") {
        post("/datagsm/transactions") {
            call.preventSensitiveResponseCaching()
            val response = authService.createTransaction(call.receive<AuthTransactionCreateRequest>())
            call.respond(HttpStatusCode.Created, response)
        }

        get("/datagsm/transactions/{id}") {
            call.preventSensitiveResponseCaching()
            val transactionId = call.parameters["id"] ?: throw ApiFailure.transactionUnavailable()
            val pollingSecret = call.request.headers[AUTH_TRANSACTION_SECRET_HEADER]
            call.respond(authService.pollTransaction(transactionId, pollingSecret))
        }

        post("/refresh") {
            call.preventSensitiveResponseCaching()
            val response = authService.refresh(call.receive<TokenRefreshRequest>())
            val status =
                when (response.status) {
                    TokenRefreshStatus.Succeeded -> HttpStatusCode.OK
                    TokenRefreshStatus.Blocked -> HttpStatusCode.Forbidden
                }
            call.respond(status, response)
        }

        post("/logout") {
            call.preventSensitiveResponseCaching()
            authService.logout(call.requireAuthenticatedSession(authService))
            call.respond(HttpStatusCode.NoContent)
        }

        get("/sessions") {
            call.preventSensitiveResponseCaching()
            call.respond(authService.listSessions(call.requireAuthenticatedSession(authService)))
        }

        delete("/sessions/{id}") {
            call.preventSensitiveResponseCaching()
            val principal = call.requireAuthenticatedSession(authService)
            val sessionId = call.parameters["id"] ?: throw ApiFailure.sessionNotFound()
            authService.revokeSession(principal, sessionId)
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/sessions") {
            call.preventSensitiveResponseCaching()
            authService.revokeAllSessions(call.requireAuthenticatedSession(authService))
            call.respond(HttpStatusCode.NoContent)
        }
    }

    get("/auth/datagsm/callback") {
        call.preventSensitiveResponseCaching()
        val result =
            authService.completeDataGsmCallback(
                state = call.request.queryParameters["state"],
                authorizationCode = call.request.queryParameters["code"],
                providerError = call.request.queryParameters["error"],
            )
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("Referrer-Policy", "no-referrer")
        call.response.headers.append(
            "Content-Security-Policy",
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
        )

        when (result) {
            CallbackHandlingResult.Completed -> {
                call.respondText(
                    text = CALLBACK_COMPLETED_HTML,
                    contentType = ContentType.Text.Html.withCharset(Charsets.UTF_8),
                )
            }

            CallbackHandlingResult.Unavailable -> {
                call.respondText(
                    text = CALLBACK_UNAVAILABLE_HTML,
                    contentType = ContentType.Text.Html.withCharset(Charsets.UTF_8),
                    status = HttpStatusCode.BadRequest,
                )
            }
        }
    }
}

suspend fun ApplicationCall.requireAuthenticatedSession(authService: AuthService): AuthenticatedSession {
    val authorization = request.headers[HttpHeaders.Authorization] ?: throw ApiFailure.authenticationRequired()
    val parts = authorization.split(' ', limit = 2)
    if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true) || parts[1].isBlank()) {
        throw ApiFailure.authenticationRequired()
    }
    return authService.authenticate(parts[1]) ?: throw ApiFailure.authenticationRequired()
}

private const val CALLBACK_COMPLETED_HTML =
    """<!doctype html><html lang="ko"><meta charset="utf-8"><title>Axia 인증 완료</title>""" +
        """<body><main><h1>인증 처리가 완료되었습니다</h1>""" +
        """<p>Axia 앱으로 돌아가 계속해 주세요.</p></main></body></html>"""

private const val CALLBACK_UNAVAILABLE_HTML =
    """<!doctype html><html lang="ko"><meta charset="utf-8"><title>Axia 인증 요청 만료</title>""" +
        """<body><main><h1>인증 요청을 확인할 수 없습니다</h1>""" +
        """<p>Axia 앱에서 로그인을 다시 시작해 주세요.</p></main></body></html>"""
