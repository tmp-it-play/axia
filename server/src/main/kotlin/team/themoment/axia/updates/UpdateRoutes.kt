package team.themoment.axia.updates

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.install
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.method
import io.ktor.server.routing.route
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.copyTo
import java.io.IOException

fun Route.updateRoutes(updates: PublishedUpdates) {
    route("/updates/{artifact...}") {
        method(HttpMethod.Get) {
            // Range handling must not intercept HEAD or the explicit 405 response below.
            install(PartialContent)
            publishedUpdateHandler(updates)
        }
        method(HttpMethod.Head) {
            publishedUpdateHandler(updates)
        }
        handle {
            call.response.headers.append(HttpHeaders.Allow, "GET, HEAD")
            call.respond(HttpStatusCode.MethodNotAllowed)
        }
    }
}

private fun Route.publishedUpdateHandler(updates: PublishedUpdates) {
    handle {
        val content =
            try {
                updates.content(call.request.uri.substringBefore('?'))
            } catch (_: IOException) {
                // Do not expose filesystem paths, signed payloads, or partially read artifacts.
                call.respond(HttpStatusCode.InternalServerError)
                return@handle
            }
        if (content == null) {
            call.respond(HttpStatusCode.NotFound)
        } else if (call.request.httpMethod == HttpMethod.Head) {
            if (content is OutgoingContent.ReadChannelContent) {
                call.response.headers.append(HttpHeaders.AcceptRanges, "bytes")
            }
            // HEAD does not open the installer stream and intentionally ignores Range (RFC 9110).
            call.respond(
                object : OutgoingContent.NoContent() {
                    override val contentLength = content.contentLength
                    override val contentType = content.contentType
                },
            )
        } else if (content is OutgoingContent.ReadChannelContent && call.request.headers[HttpHeaders.IfRange] != null) {
            // We publish no strong validator. An unverified If-Range must not authorize a partial response.
            // WriteChannelContent bypasses PartialContent while retaining bounded streaming and the full length.
            call.respond(FullInstallerContent(content))
        } else {
            call.respond(content)
        }
    }
}

private class FullInstallerContent(
    private val content: OutgoingContent.ReadChannelContent,
) : OutgoingContent.WriteChannelContent() {
    override val contentLength = content.contentLength
    override val contentType = content.contentType

    override suspend fun writeTo(channel: ByteWriteChannel) {
        val source = content.readFrom()
        try {
            source.copyTo(channel)
        } finally {
            source.cancel(null)
        }
    }
}
