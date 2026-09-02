package team.themoment.axia.plugins

import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.ForwardedHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import kotlinx.serialization.json.Json
import java.util.UUID

private const val REQUEST_ID_HEADER = "X-Request-ID"
private val validRequestId = Regex("[A-Za-z0-9._:-]{1,128}")

fun Application.configureHttpPlugins() {
    install(ForwardedHeaders)
    install(XForwardedHeaders)

    install(CallId) {
        retrieveFromHeader(REQUEST_ID_HEADER)
        verify { validRequestId.matches(it) }
        generate { UUID.randomUUID().toString() }
        replyToHeader(REQUEST_ID_HEADER)
    }

    install(ContentNegotiation) {
        json(
            Json {
                encodeDefaults = true
                explicitNulls = false
                ignoreUnknownKeys = true
            },
        )
    }
}

fun io.ktor.server.application.ApplicationCall.preventSensitiveResponseCaching() {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append(HttpHeaders.Pragma, "no-cache")
}
