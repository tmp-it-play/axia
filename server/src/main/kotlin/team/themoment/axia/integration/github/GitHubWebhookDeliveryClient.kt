package team.themoment.axia.integration.github

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonNull
import java.time.Instant
import java.util.UUID

data class GitHubWebhookDelivery(
    val id: Long,
    val guid: String,
    val deliveredAt: Instant,
    val statusCode: Int,
    val installationId: Long?,
    val throttled: Boolean,
) {
    val succeeded: Boolean get() = statusCode in 200..399
}

/** Only delivery metadata is fetched. Request/response payloads from the delivery-detail endpoint are unnecessary. */
class GitHubWebhookDeliveryClient(
    private val transport: GitHubAppTransport,
) {
    suspend fun deliveries(): List<GitHubWebhookDelivery> {
        val deliveries = linkedMapOf<Long, GitHubWebhookDelivery>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = transport.readCursorPage("/app/hook/deliveries", cursor, appAuthentication = true)
            page.items.forEach { item ->
                val guid = item.requiredText("guid")
                val id = item.requiredLong("id")
                val status = item.requiredLong("status_code")
                val installation = item.long("installation_id")
                val throttled =
                    if (item["throttled_at"] == null ||
                        item["throttled_at"] == JsonNull
                    ) {
                        false
                    } else {
                        item.requiredInstant("throttled_at").let { true }
                    }
                val validGuid = runCatching { UUID.fromString(guid).toString() == guid.lowercase() }.getOrDefault(false)
                if (!validGuid || id <= 0 || status !in 0..599 || (installation != null && installation <= 0)) {
                    throw GitHubApiException(invalidContract = true)
                }
                val delivery =
                    GitHubWebhookDelivery(
                        id,
                        guid.lowercase(),
                        item.requiredInstant("delivered_at"),
                        status.toInt(),
                        installation,
                        throttled,
                    )
                val previous = deliveries.put(id, delivery)
                if (previous != null && previous != delivery) throw GitHubApiException(invalidContract = true)
            }
            cursor = page.nextCursor
            if (cursor != null && !cursors.add(cursor)) throw GitHubApiException(invalidContract = true)
        } while (cursor != null)
        return deliveries.values.toList()
    }

    /** The caller first commits its durable intent. An uncertain result must be verified by a later metadata read. */
    suspend fun redeliver(deliveryId: Long) {
        require(deliveryId > 0)
        transport.mutate(HttpMethod.Post, "/app/hook/deliveries/$deliveryId/attempts", appAuthentication = true)
    }
}
