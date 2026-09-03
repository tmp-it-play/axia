package team.themoment.axia.analytics

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.auth.AuthService
import team.themoment.axia.auth.requireAuthenticatedSession
import team.themoment.axia.domain.ProductId
import team.themoment.axia.plugins.preventSensitiveResponseCaching
import java.time.Instant

fun Route.analyticsRoutes(
    authService: AuthService,
    analytics: AnalyticsService,
) {
    route("/v1/analytics") {
        get("/completions") {
            call.preventSensitiveResponseCaching()
            val actor = call.requireAuthenticatedSession(authService)
            val range = call.analyticsPeriod()
            call.respond(analytics.completions(actor.userId, range.first, range.second, range.third))
        }
        get("/members") {
            call.preventSensitiveResponseCaching()
            val actor = call.requireAuthenticatedSession(authService)
            val range = call.analyticsPeriod()
            call.respond(analytics.members(actor.userId, range.first, range.second, range.third))
        }
        get("/operations") {
            call.preventSensitiveResponseCaching()
            call.respond(analytics.operations(call.requireAuthenticatedSession(authService).userId))
        }
    }
    get("/v1/completions") {
        call.preventSensitiveResponseCaching()
        val actor = call.requireAuthenticatedSession(authService)
        val query = call.request.queryParameters
        val before = query["beforeId"]?.let { it.toLongOrNull() ?: throw invalidFilter() }
        val limit = query["limit"]?.let { it.toIntOrNull() ?: throw invalidFilter() } ?: 50
        val product = query["productId"]?.let { value -> ProductId.entries.firstOrNull { it.name == value } ?: throw invalidFilter() }
        val creditedUserId = query["userId"]?.let { it.toLongOrNull()?.takeIf { id -> id > 0 } ?: throw invalidFilter() }
        call.respond(analytics.history(actor.userId, before, product, limit, creditedUserId))
    }
}

private fun ApplicationCall.analyticsPeriod(): Triple<AnalyticsRange, Instant?, Instant?> {
    val query = request.queryParameters
    val range =
        query["range"]?.let { value ->
            AnalyticsRange.entries.firstOrNull { it.name == value } ?: throw invalidFilter()
        } ?: AnalyticsRange.LAST_30_DAYS

    fun instant(key: String): Instant? =
        query[key]?.let { value -> value.toLongOrNull()?.let(Instant::ofEpochMilli) ?: throw invalidFilter() }
    return Triple(range, instant("fromEpochMillis"), instant("untilEpochMillis"))
}

private fun invalidFilter(): ApiFailure = ApiFailure.invalidRequest("분석 필터가 올바르지 않습니다.")
