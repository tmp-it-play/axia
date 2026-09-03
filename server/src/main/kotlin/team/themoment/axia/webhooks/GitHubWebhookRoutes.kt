package team.themoment.axia.webhooks

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream

fun Route.gitHubWebhookRoutes(service: GitHubWebhookService) {
    post("/webhooks/github") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("X-Content-Type-Options", "nosniff")
        if (!call.request.contentType().match(ContentType.Application.Json)) {
            call.respondText("Unsupported content type", status = HttpStatusCode.UnsupportedMediaType)
            return@post
        }

        fun singleHeader(name: String): String? =
            call.request.headers
                .getAll(name)
                ?.singleOrNull()
        val signature = singleHeader("X-Hub-Signature-256")
        if (signature == null) {
            call.respondText("Invalid signature", status = HttpStatusCode.Unauthorized)
            return@post
        }
        val channel = call.receiveChannel()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count == -1) break
            if (output.size().toLong() + count > MAXIMUM_GITHUB_BODY_BYTES) {
                call.respondText("Payload too large", status = HttpStatusCode.PayloadTooLarge)
                return@post
            }
            output.write(buffer, 0, count)
        }
        when (service.receive(output.toByteArray(), signature, singleHeader("X-GitHub-Delivery"), singleHeader("X-GitHub-Event"))) {
            GitHubWebhookResult.Accepted -> {
                call.respondText("", status = HttpStatusCode.NoContent)
            }

            GitHubWebhookResult.InvalidSignature -> {
                call.respondText("Invalid signature", status = HttpStatusCode.Unauthorized)
            }

            GitHubWebhookResult.InvalidEnvelope -> {
                call.respondText("Invalid event envelope", status = HttpStatusCode.BadRequest)
            }

            GitHubWebhookResult.PayloadMismatch -> {
                call.respondText("Delivery identity conflict", status = HttpStatusCode.Conflict)
            }

            GitHubWebhookResult.RetryableFailure -> {
                call.respondText(
                    "Event processing is temporarily unavailable",
                    status = HttpStatusCode.ServiceUnavailable,
                )
            }
        }
    }
}

private const val MAXIMUM_GITHUB_BODY_BYTES = 25L * 1024 * 1024
