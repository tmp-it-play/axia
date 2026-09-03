package team.themoment.axia.integration.datagsm.events

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Decodes the raw signed envelope and immutable identity extensions independently.
 *
 * The published DataGSM contract currently lacks major_club_id in student snapshots
 * and student_id in club member references. Those payloads deliberately fail closed;
 * accepting fixture extensions is not evidence that the live provider supports them.
 */
class DataGsmEventDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    fun envelope(rawBody: ByteArray): DataGsmEventEnvelope {
        val objectValue = parseObject(rawBody, DataGsmEventFailure.InvalidEnvelope)
        val id = objectValue.requiredString("id", DataGsmEventFailure.InvalidEnvelope)
        val type = objectValue.requiredString("event", DataGsmEventFailure.InvalidEnvelope)
        val timestamp = objectValue.requiredString("timestamp", DataGsmEventFailure.InvalidEnvelope)
        if (!EVENT_ID_PATTERN.matches(id) || !EVENT_TYPE_PATTERN.matches(type)) invalidEnvelope()
        val dispatchedAt =
            try {
                Instant.parse(timestamp)
            } catch (_: DateTimeParseException) {
                invalidEnvelope()
            }
        return DataGsmEventEnvelope(id, type, dispatchedAt)
    }

    fun decode(
        rawBody: ByteArray,
        envelope: DataGsmEventEnvelope,
    ): DataGsmEvent {
        if (envelope.type !in SUPPORTED_EVENTS) return DataGsmEvent.Unsupported(envelope)
        val root = parseObject(rawBody, DataGsmEventFailure.InvalidProviderContract)
        val data = root["data"] as? JsonObject ?: invalidContract()
        val before = data.changeItems("old")
        val after = data.changeItems("new")
        if (before.keys != after.keys || before.isEmpty() || before.size > MAXIMUM_CHANGES) invalidContract()

        return when (envelope.type) {
            "student.updated" -> {
                val changes =
                    before.keys.sorted().map { index ->
                        val old = before.getValue(index).studentOrNull()
                        val new = after.getValue(index).studentOrNull()
                        requireSameIdentity(old?.studentId, new?.studentId)
                        DataGsmStudentChange(old, new)
                    }
                if (changes.map { it.studentId }.toSet().size != changes.size) invalidContract()
                DataGsmEvent.StudentsUpdated(envelope, changes)
            }

            "club.updated" -> {
                val changes =
                    before.keys.sorted().map { index ->
                        val old = before.getValue(index).clubOrNull()
                        val new = after.getValue(index).clubOrNull()
                        requireSameIdentity(old?.clubId, new?.clubId)
                        DataGsmClubChange(old, new)
                    }
                if (changes.map { it.clubId }.toSet().size != changes.size) invalidContract()
                DataGsmEvent.ClubsUpdated(envelope, changes)
            }

            else -> {
                DataGsmEvent.Unsupported(envelope)
            }
        }
    }

    private fun parseObject(
        rawBody: ByteArray,
        failure: DataGsmEventFailure,
    ): JsonObject {
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawBody))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw DataGsmEventException(failure)
            }
        return try {
            json.parseToJsonElement(text) as? JsonObject ?: throw DataGsmEventException(failure)
        } catch (_: SerializationException) {
            throw DataGsmEventException(failure)
        } catch (_: IllegalArgumentException) {
            throw DataGsmEventException(failure)
        }
    }

    private fun JsonObject.changeItems(name: String): Map<Int, JsonObject> {
        val array = this[name] as? JsonArray ?: invalidContract()
        val result = mutableMapOf<Int, JsonObject>()
        array.forEach { element ->
            val item = element as? JsonObject ?: invalidContract()
            val index = item.requiredPrimitive("index").intOrNull?.takeIf { it >= 0 } ?: invalidContract()
            val snapshot = item["object"] as? JsonObject ?: invalidContract()
            if (result.put(index, snapshot) != null) invalidContract()
        }
        return result
    }

    private fun JsonObject.studentOrNull(): DataGsmStudentEventSnapshot? {
        if (isEmpty()) return null
        if (!containsKey("major_club_id")) {
            throw DataGsmEventException(DataGsmEventFailure.InvalidProviderContract, DataGsmContractIssue.MissingMajorClubId)
        }
        val clubId = nullableLong("major_club_id")?.also { if (it <= 0) invalidContract() }
        val clubName = nullableString("major_club_name")
        if ((clubId == null) != (clubName == null)) invalidContract()
        return DataGsmStudentEventSnapshot(
            studentId = positiveLong("student_id"),
            name = requiredString("name"),
            email = requiredString("email"),
            sex = requiredString("sex"),
            grade = nullableInt("grade"),
            classNumber = nullableInt("class_num"),
            number = nullableInt("number"),
            studentNumber = nullableInt("student_number"),
            major = nullableString("major"),
            specialty = nullableString("specialty"),
            role = requiredString("role"),
            majorClubId = clubId,
            majorClubName = clubName,
            githubId = nullableString("github_id")?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    private fun JsonObject.clubOrNull(): DataGsmClubEventSnapshot? {
        if (isEmpty()) return null
        val leader = requiredField("leader").let { if (it is JsonNull) null else it.studentReference() }
        val participants = (this["participants"] as? JsonArray ?: invalidContract()).map { it.studentReference() }
        if (participants.size > MAXIMUM_CHANGES || participants.map { it.studentId }.toSet().size != participants.size) invalidContract()
        return DataGsmClubEventSnapshot(
            clubId = positiveLong("club_id"),
            name = requiredString("name"),
            type = requiredString("type"),
            status = requiredString("status"),
            leader = leader,
            participants = participants,
        )
    }

    private fun JsonElement.studentReference(): DataGsmEventStudentReference {
        val reference = this as? JsonObject ?: invalidContract()
        if (!reference.containsKey("student_id")) {
            throw DataGsmEventException(DataGsmEventFailure.InvalidProviderContract, DataGsmContractIssue.MissingMemberStudentId)
        }
        return DataGsmEventStudentReference(reference.positiveLong("student_id"))
    }

    private fun requireSameIdentity(
        before: Long?,
        after: Long?,
    ) {
        if (before == null && after == null) invalidContract()
        if (before != null && after != null && before != after) invalidContract()
    }

    private fun JsonObject.requiredField(name: String): JsonElement = this[name] ?: invalidContract()

    private fun JsonObject.requiredPrimitive(name: String): JsonPrimitive = requiredField(name) as? JsonPrimitive ?: invalidContract()

    private fun JsonObject.positiveLong(name: String): Long =
        requiredPrimitive(name).takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 } ?: invalidContract()

    private fun JsonObject.requiredString(
        name: String,
        failure: DataGsmEventFailure = DataGsmEventFailure.InvalidProviderContract,
    ): String {
        val primitive = this[name] as? JsonPrimitive ?: throw DataGsmEventException(failure)
        return primitive
            .takeIf { it.isString && it.content.isNotBlank() && it.content.length <= MAXIMUM_STRING_LENGTH }
            ?.content
            ?: throw DataGsmEventException(failure)
    }

    private fun JsonObject.nullableString(name: String): String? {
        val value = requiredField(name)
        if (value is JsonNull) return null
        val primitive = value as? JsonPrimitive ?: invalidContract()
        if (!primitive.isString || primitive.content.length > MAXIMUM_STRING_LENGTH) invalidContract()
        return primitive.content
    }

    private fun JsonObject.nullableInt(name: String): Int? {
        val value = requiredField(name)
        if (value is JsonNull) return null
        return (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: invalidContract()
    }

    private fun JsonObject.nullableLong(name: String): Long? {
        val value = requiredField(name)
        if (value is JsonNull) return null
        return (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: invalidContract()
    }

    private fun invalidEnvelope(): Nothing = throw DataGsmEventException(DataGsmEventFailure.InvalidEnvelope)

    private fun invalidContract(): Nothing = throw DataGsmEventException(DataGsmEventFailure.InvalidProviderContract)

    private companion object {
        const val MAXIMUM_CHANGES = 10_000
        const val MAXIMUM_STRING_LENGTH = 4096
        val EVENT_ID_PATTERN = Regex("evt_[0-9a-f]{32}")
        val EVENT_TYPE_PATTERN = Regex("[a-z][a-z0-9._-]{0,127}")
        val SUPPORTED_EVENTS = setOf("student.updated", "club.updated")
    }
}
