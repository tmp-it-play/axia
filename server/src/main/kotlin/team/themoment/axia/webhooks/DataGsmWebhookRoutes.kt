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
import team.themoment.axia.integration.datagsm.events.DataGsmEventResult
import team.themoment.axia.integration.datagsm.events.DataGsmEventService
import java.io.ByteArrayOutputStream

fun Route.dataGsmWebhookRoutes(service: DataGsmEventService) {
    post("/webhooks/datagsm") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("X-Content-Type-Options", "nosniff")
        if (!call.request.contentType().match(ContentType.Application.Json)) {
            call.respondText("Unsupported content type", status = HttpStatusCode.UnsupportedMediaType)
            return@post
        }
        val signatures = call.request.headers.getAll(SIGNATURE_HEADER)
        if (signatures?.size != 1) {
            call.respondText("Invalid signature", status = HttpStatusCode.Unauthorized)
            return@post
        }
        val advertisedSize = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (advertisedSize != null && advertisedSize > MAXIMUM_BODY_BYTES) {
            call.respondText("Payload too large", status = HttpStatusCode.PayloadTooLarge)
            return@post
        }
        val channel = call.receiveChannel()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count == -1) break
            if (output.size().toLong() + count > MAXIMUM_BODY_BYTES) {
                call.respondText("Payload too large", status = HttpStatusCode.PayloadTooLarge)
                return@post
            }
            output.write(buffer, 0, count)
        }
        when (service.receive(output.toByteArray(), signatures.single())) {
            DataGsmEventResult.Accepted -> {
                call.respondText("", status = HttpStatusCode.NoContent)
            }

            DataGsmEventResult.InvalidSignature -> {
                call.respondText("Invalid signature", status = HttpStatusCode.Unauthorized)
            }

            DataGsmEventResult.InvalidEnvelope -> {
                call.respondText("Invalid event envelope", status = HttpStatusCode.BadRequest)
            }

            DataGsmEventResult.PayloadMismatch -> {
                call.respondText("Delivery identity conflict", status = HttpStatusCode.Conflict)
            }

            DataGsmEventResult.UnsupportedProviderContract -> {
                call.respondText("Unsupported DataGSM event contract", status = HttpStatusCode.ServiceUnavailable)
            }

            DataGsmEventResult.RetryableFailure -> {
                call.respondText("Event processing is temporarily unavailable", status = HttpStatusCode.ServiceUnavailable)
            }
        }
    }
}

private const val SIGNATURE_HEADER = "X-DataGSM-Signature"
private const val MAXIMUM_BODY_BYTES = 2L * 1024 * 1024
