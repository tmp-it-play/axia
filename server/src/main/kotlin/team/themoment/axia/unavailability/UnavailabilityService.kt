package team.themoment.axia.unavailability

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import team.themoment.axia.api.ApiErrorCode
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.authorization.AuthorizationPolicy
import team.themoment.axia.authorization.AuthorizationService
import team.themoment.axia.authorization.accessDenied
import team.themoment.axia.authorization.lockMemberManagement
import team.themoment.axia.domain.OrganizationRole
import team.themoment.axia.domain.TechnicalPosition
import team.themoment.axia.members.currentNotificationActors
import team.themoment.axia.members.insertFunctionalNotification
import team.themoment.axia.members.memberConflict
import team.themoment.axia.members.memberInstant
import team.themoment.axia.members.memberTransaction
import team.themoment.axia.members.requireActiveTarget
import team.themoment.axia.members.setMemberInstant
import team.themoment.axia.persistence.inTransaction
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

class UnavailabilityService(
    private val dataSource: DataSource,
    private val authorization: AuthorizationService,
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun list(
        actorId: Long,
        from: Instant,
        until: Instant,
        userId: Long? = null,
        position: TechnicalPosition? = null,
        type: UnavailabilityType? = null,
    ): UnavailabilityListResponse =
        withContext(ioDispatcher) {
            validateInterval(from, until)
            if (userId != null && userId <= 0) throw invalidPeriod()
            dataSource.inTransaction(isolationLevel = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
                authorization.requireCurrent(this, actorId, forUpdate = false)
                prepareStatement(
                    """
                    SELECT p.* FROM unavailability_periods p
                    WHERE p.starts_at < ? AND p.ends_at > ?
                      AND (? IS NULL OR p.user_id = ?)
                      AND (? IS NULL OR p.type = ?)
                      AND (? IS NULL OR EXISTS (SELECT 1 FROM user_positions up WHERE up.user_id = p.user_id AND up.position = ?))
                    ORDER BY p.starts_at, p.id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setMemberInstant(1, until)
                    statement.setMemberInstant(2, from)
                    statement.setObject(3, userId)
                    statement.setObject(4, userId)
                    statement.setString(5, type?.name)
                    statement.setString(6, type?.name)
                    statement.setString(7, position?.name)
                    statement.setString(8, position?.name)
                    statement.executeQuery().use { results ->
                        UnavailabilityListResponse(buildList { while (results.next()) add(results.toPeriod()) })
                    }
                }
            }
        }

    suspend fun create(
        actorId: Long,
        request: UnavailabilityCreateRequest,
    ): UnavailabilityPeriod =
        withContext(ioDispatcher) {
            val values = validateValues(request.startsAtEpochMillis, request.endsAtEpochMillis, request.reason)
            val targetId = request.userId ?: actorId
            dataSource.memberTransaction {
                lockMemberManagement()
                val actor = authorization.requireCurrent(this, actorId)
                val target = requireActiveTarget(this, targetId)
                if (!AuthorizationPolicy.canAdjustUnavailability(actor, target)) throw accessDenied()
                val now = clock.instant()
                val id =
                    prepareStatement(
                        "INSERT INTO unavailability_periods (user_id, type, starts_at, ends_at, reason, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS,
                    ).use { statement ->
                        statement.setLong(1, targetId)
                        statement.setString(2, request.type.name)
                        statement.setMemberInstant(3, values.start)
                        statement.setMemberInstant(4, values.end)
                        statement.setString(5, values.reason)
                        statement.setMemberInstant(6, now)
                        statement.setMemberInstant(7, now)
                        check(statement.executeUpdate() == 1) { "Unavailability was not inserted" }
                        statement.generatedKeys.use { keys ->
                            check(keys.next()) { "Inserted unavailability identifier was not returned" }
                            keys.getLong(1)
                        }
                    }
                val period = findPeriod(this, id, forUpdate = false) ?: error("Inserted unavailability was not found")
                notifyChange(this, actorId, target.positions, period, "CREATED", now)
                period
            }
        }

    suspend fun update(
        actorId: Long,
        periodId: Long,
        request: UnavailabilityUpdateRequest,
    ): UnavailabilityPeriod =
        withContext(ioDispatcher) {
            val values = validateValues(request.startsAtEpochMillis, request.endsAtEpochMillis, request.reason)
            if (request.expectedVersion <= 0) throw invalidPeriod()
            dataSource.memberTransaction {
                lockMemberManagement()
                val actor = authorization.requireCurrent(this, actorId)
                val existing = findPeriod(this, periodId, forUpdate = true) ?: throw periodNotFound()
                val target = requireActiveTarget(this, existing.userId)
                if (!AuthorizationPolicy.canAdjustUnavailability(actor, target)) throw accessDenied()
                if (existing.version != request.expectedVersion) throw periodChanged()
                val version = Math.addExact(existing.version, 1L)
                val now = clock.instant()
                prepareStatement(
                    "UPDATE unavailability_periods SET type = ?, starts_at = ?, ends_at = ?, reason = ?, version = ?, updated_at = GREATEST(?, updated_at) WHERE id = ? AND version = ?",
                ).use { statement ->
                    statement.setString(1, request.type.name)
                    statement.setMemberInstant(2, values.start)
                    statement.setMemberInstant(3, values.end)
                    statement.setString(4, values.reason)
                    statement.setLong(5, version)
                    statement.setMemberInstant(6, now)
                    statement.setLong(7, periodId)
                    statement.setLong(8, request.expectedVersion)
                    check(statement.executeUpdate() == 1) { "Locked unavailability could not be updated" }
                }
                val period = findPeriod(this, periodId, forUpdate = false) ?: error("Updated unavailability was not found")
                notifyChange(this, actorId, target.positions, period, "UPDATED", now)
                period
            }
        }

    suspend fun delete(
        actorId: Long,
        periodId: Long,
        expectedVersion: Long,
    ): Unit =
        withContext(ioDispatcher) {
            if (expectedVersion <= 0) throw invalidPeriod()
            dataSource.memberTransaction {
                lockMemberManagement()
                val actor = authorization.requireCurrent(this, actorId)
                val existing = findPeriod(this, periodId, forUpdate = true) ?: throw periodNotFound()
                val target = requireActiveTarget(this, existing.userId)
                if (!AuthorizationPolicy.canAdjustUnavailability(actor, target)) throw accessDenied()
                if (existing.version != expectedVersion) throw periodChanged()
                prepareStatement("DELETE FROM unavailability_periods WHERE id = ? AND version = ?").use { statement ->
                    statement.setLong(1, periodId)
                    statement.setLong(2, expectedVersion)
                    check(statement.executeUpdate() == 1) { "Locked unavailability could not be deleted" }
                }
                notifyChange(this, actorId, target.positions, existing, "DELETED", clock.instant())
            }
        }

    private fun notifyChange(
        connection: Connection,
        actorId: Long,
        targetPositions: Set<TechnicalPosition>,
        period: UnavailabilityPeriod,
        event: String,
        now: Instant,
    ) {
        val recipients =
            currentNotificationActors(connection)
                .filter { recipient ->
                    recipient.id != actorId && (
                        recipient.id == period.userId ||
                            (
                                recipient.currentStudent &&
                                    (recipient.role == OrganizationRole.TEAM_LEADER || recipient.role?.headPosition in targetPositions)
                            )
                    )
                }.map { it.id }
                .toMutableSet()
        if (period.userId != actorId) recipients.add(period.userId)
        // Reasons are public in the dedicated view only; never copy health/personal text into notifications or logs.
        val payload =
            buildJsonObject {
                put("unavailabilityId", period.id)
                put("userId", period.userId)
                put("actorUserId", actorId)
                put("type", period.type.name)
                put("startsAtEpochMillis", period.startsAtEpochMillis)
                put("endsAtEpochMillis", period.endsAtEpochMillis)
                put("event", event)
            }.toString()
        recipients.forEach { recipient ->
            insertFunctionalNotification(
                connection,
                recipient,
                "UNAVAILABILITY_CHANGED",
                payload,
                "unavailability:${period.id}:${period.version}:$event",
                now,
            )
        }
    }
}

private fun findPeriod(
    connection: Connection,
    id: Long,
    forUpdate: Boolean,
): UnavailabilityPeriod? =
    connection
        .prepareStatement(
            "SELECT * FROM unavailability_periods WHERE id = ?" + if (forUpdate) " FOR UPDATE" else "",
        ).use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { results -> if (results.next()) results.toPeriod() else null }
        }

private fun ResultSet.toPeriod(): UnavailabilityPeriod =
    UnavailabilityPeriod(
        id = getLong("id"),
        userId = getLong("user_id"),
        type = UnavailabilityType.valueOf(getString("type")),
        startsAtEpochMillis = memberInstant("starts_at").toEpochMilli(),
        endsAtEpochMillis = memberInstant("ends_at").toEpochMilli(),
        reason = getString("reason"),
        version = getLong("version"),
        updatedAtEpochMillis = memberInstant("updated_at").toEpochMilli(),
    )

private data class PeriodValues(
    val start: Instant,
    val end: Instant,
    val reason: String,
) {
    override fun toString(): String = "PeriodValues(start=$start, end=$end, reason=[REDACTED])"
}

private fun validateValues(
    start: Long,
    end: Long,
    reason: String,
): PeriodValues {
    val startsAt = Instant.ofEpochMilli(start)
    val endsAt = Instant.ofEpochMilli(end)
    validateInterval(startsAt, endsAt)
    val normalizedReason = reason.trim()
    if (normalizedReason.isEmpty() || normalizedReason.length > 2_000 || normalizedReason.any { it == '\u0000' }) throw invalidPeriod()
    return PeriodValues(startsAt, endsAt, normalizedReason)
}

private fun validateInterval(
    start: Instant,
    end: Instant,
) {
    if (!start.isBefore(end) || start < MIN_DATETIME || end > MAX_DATETIME) throw invalidPeriod()
}

private fun invalidPeriod(): ApiFailure = ApiFailure.invalidRequest("시작·종료 시각과 1~2000자의 사유를 올바르게 입력해 주세요.")

private fun periodNotFound(): ApiFailure = ApiFailure(HttpStatusCode.NotFound, ApiErrorCode.ResourceNotFound, "휴가·작업 불가 기간을 찾을 수 없습니다.")

private fun periodChanged(): ApiFailure = memberConflict("기간 정보가 변경되었습니다. 최신 정보를 확인한 뒤 다시 시도해 주세요.")

private val MIN_DATETIME: Instant = Instant.parse("1000-01-01T00:00:00Z")
private val MAX_DATETIME: Instant = Instant.parse("9999-12-31T23:59:59.999Z")
