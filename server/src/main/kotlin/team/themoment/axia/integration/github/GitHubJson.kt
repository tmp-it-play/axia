package team.themoment.axia.integration.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.requiredText(key: String): String = text(key) ?: throw GitHubApiException(invalidContract = true)

internal fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

internal fun JsonObject.requiredLong(key: String): Long = long(key) ?: throw GitHubApiException(invalidContract = true)

internal fun JsonObject.boolean(key: String): Boolean =
    (get(key) as? JsonPrimitive)?.booleanOrNull ?: throw GitHubApiException(invalidContract = true)

internal fun JsonObject.objectOrNull(key: String): JsonObject? = get(key) as? JsonObject

internal fun JsonObject.requiredObject(key: String): JsonObject = objectOrNull(key) ?: throw GitHubApiException(invalidContract = true)

internal fun JsonObject.objects(key: String): List<JsonObject> =
    (get(key) as? JsonArray)?.mapNotNull {
        if (it ==
            JsonNull
        ) {
            null
        } else {
            it as? JsonObject ?: throw GitHubApiException(invalidContract = true)
        }
    }
        ?: throw GitHubApiException(invalidContract = true)

internal fun JsonObject.instant(key: String): Instant? =
    text(key)?.let { value ->
        try {
            Instant.parse(value)
        } catch (_: Exception) {
            throw GitHubApiException(invalidContract = true)
        }
    }

internal fun JsonObject.requiredInstant(key: String): Instant = instant(key) ?: throw GitHubApiException(invalidContract = true)

internal fun JsonObject.restActor(): GitHubActor =
    GitHubActor(requiredLong("id"), requiredText("login"), text("node_id")).also {
        if (it.id <= 0 || it.login.isBlank()) throw GitHubApiException(invalidContract = true)
    }

internal fun JsonObject.graphActor(): GitHubActor =
    GitHubActor(long("fullDatabaseId") ?: requiredLong("databaseId"), requiredText("login"), requiredText("id")).also {
        if (it.id <= 0 || it.login.isBlank()) throw GitHubApiException(invalidContract = true)
    }
