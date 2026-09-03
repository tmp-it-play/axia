package team.themoment.axia.tasks

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import team.themoment.axia.api.ApiErrorCode
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.members.memberTransaction
import team.themoment.axia.persistence.execute
import team.themoment.axia.persistence.instant
import team.themoment.axia.persistence.nullableLong
import team.themoment.axia.persistence.query
import java.security.MessageDigest
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

data class ExternalWriteAttempt(
    val id: String,
    val userId: Long,
    val taskId: Long?,
    val kind: String,
    val issuedAtEpochMillis: Long,
    val request: JsonElement,
)

data class ExternalWriteState(
    val status: String,
    val taskId: Long?,
    val checkpoint: JsonObject,
)

data class ExternalWriteClaim(
    val fresh: Boolean,
    val state: ExternalWriteState,
)

/** This is a short-lived safety fence, not an audit log or a queue. No request body is persisted. */
class ExternalWriteOperationStore(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun claim(
        attempt: ExternalWriteAttempt,
        guard: (Connection) -> Unit,
    ): ExternalWriteClaim =
        withContext(Dispatchers.IO) {
            val issued = validateAttemptTime(attempt.id, attempt.issuedAtEpochMillis, clock.instant())
            val hash = hashRequest(attempt.kind, attempt.taskId, attempt.request)
            dataSource.memberTransaction {
                // The shared actor/authorization lock also serializes competing operation-id inserts.
                guard(this)
                val existing =
                    query("SELECT * FROM external_write_operations WHERE id=? FOR UPDATE", attempt.id) { row ->
                        if (row.getLong("user_id") != attempt.userId || !MessageDigest.isEqual(row.getBytes("request_hash"), hash) ||
                            row.getString("kind") != attempt.kind
                        ) {
                            throw operationConflict()
                        }
                        if (!requireNotNull(row.instant("expires_at")).isAfter(clock.instant())) throw operationExpired()
                        ExternalWriteState(
                            row.getString("status"),
                            row.nullableLong("task_id"),
                            row.getString("result_json")?.let { Json.parseToJsonElement(it) as JsonObject } ?: JsonObject(emptyMap()),
                        )
                    }.singleOrNull()
                if (existing != null) return@memberTransaction ExternalWriteClaim(false, existing)
                execute(
                    "INSERT INTO external_write_operations(id,user_id,task_id,request_hash,kind,status,created_at,expires_at) VALUES(?,?,?,?,?,'PREPARED',?,?)",
                    attempt.id,
                    attempt.userId,
                    attempt.taskId,
                    hash,
                    attempt.kind,
                    clock.instant(),
                    issued.plus(MAX_AGE),
                )
                ExternalWriteClaim(true, ExternalWriteState("PREPARED", attempt.taskId, JsonObject(emptyMap())))
            }
        }

    suspend fun beginDispatch(
        id: String,
        guard: (Connection) -> Unit,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.memberTransaction {
                guard(this)
                val taskId =
                    query(
                        "SELECT task_id FROM external_write_operations WHERE id=? FOR UPDATE",
                        id,
                    ) { it.nullableLong("task_id") }.singleOrNull()
                if (taskId != null &&
                    query(
                        "SELECT id FROM external_write_operations WHERE task_id=? AND id<>? AND status='IN_FLIGHT' AND expires_at>? FOR UPDATE",
                        taskId,
                        id,
                        clock.instant(),
                    ) {
                        it.getString(1)
                    }.isNotEmpty()
                ) {
                    throw operationConflict()
                }
                execute(
                    "UPDATE external_write_operations SET status='IN_FLIGHT' WHERE id=? AND status='PREPARED' AND expires_at>?",
                    id,
                    clock.instant(),
                ) ==
                    1
            }
        }

    suspend fun checkpoint(
        id: String,
        values: JsonObject,
        taskId: Long? = null,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.memberTransaction {
                execute(
                    "UPDATE external_write_operations SET result_json=?,task_id=COALESCE(?,task_id) WHERE id=? AND status IN ('IN_FLIGHT','UNKNOWN')",
                    values.toString(),
                    taskId,
                    id,
                )
            }
        }

    suspend fun confirmed(
        id: String,
        taskId: Long?,
        values: JsonObject,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.memberTransaction {
                execute(
                    "UPDATE external_write_operations SET status='CONFIRMED',task_id=COALESCE(?,task_id),result_json=? WHERE id=? AND status IN ('PREPARED','IN_FLIGHT','UNKNOWN','CONFIRMED')",
                    taskId,
                    values.toString(),
                    id,
                )
            }
        }

    suspend fun finishUnconfirmed(
        id: String,
        uncertain: Boolean,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.memberTransaction {
                execute(
                    "UPDATE external_write_operations SET status=? WHERE id=? AND status IN ('PREPARED','IN_FLIGHT')",
                    if (uncertain) "UNKNOWN" else "FAILED",
                    id,
                )
            }
        }

    companion object {
        val MAX_AGE: Duration = Duration.ofHours(24)

        fun validateAttemptTime(
            id: String,
            issuedAtEpochMillis: Long,
            now: Instant,
        ): Instant {
            if (runCatching {
                    UUID
                        .fromString(
                            id,
                        ).toString() == id
                }.getOrDefault(false).not()
            ) {
                throw ApiFailure.invalidRequest("작업 식별자가 올바르지 않습니다.")
            }
            val issued = Instant.ofEpochMilli(issuedAtEpochMillis)
            if (!issued.plus(MAX_AGE).isAfter(now) || issued.isAfter(now.plus(Duration.ofMinutes(5)))) throw operationExpired()
            return issued
        }

        fun hashRequest(
            kind: String,
            taskId: Long?,
            payload: JsonElement,
        ): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(
                "$kind\n${taskId ?: ""}\n${canonical(payload)}".toByteArray(Charsets.UTF_8),
            )

        private fun canonical(value: JsonElement): JsonElement =
            when (value) {
                is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })

                // All request collections are sets of identifiers or label names, not ordered instructions.
                is JsonArray -> JsonArray(value.map(::canonical).sortedBy { it.toString() })

                else -> value
            }
    }
}

private fun operationConflict(): ApiFailure = ApiFailure(HttpStatusCode.Conflict, ApiErrorCode.Conflict, "같은 작업 식별자를 다른 요청에 사용할 수 없습니다.")

private fun operationExpired(): ApiFailure =
    ApiFailure(HttpStatusCode.Conflict, ApiErrorCode.Conflict, "작업 확인 시간이 만료되었습니다. 최신 상태를 확인한 뒤 새 작업을 시작해 주세요.")
