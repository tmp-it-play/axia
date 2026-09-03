package team.themoment.axia.notifications

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import team.themoment.axia.api.ApiErrorCode
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.authorization.AuthorizationService
import team.themoment.axia.domain.ProductId
import team.themoment.axia.members.memberTransaction
import team.themoment.axia.persistence.execute
import team.themoment.axia.persistence.inTransaction
import team.themoment.axia.persistence.instant
import team.themoment.axia.persistence.query
import team.themoment.axia.scheduling.JobLease
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class NotificationService(
    private val dataSource: DataSource,
    private val authorization: AuthorizationService,
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun list(
        userId: Long,
        beforeId: Long? = null,
        unreadOnly: Boolean = false,
        limit: Int = 50,
    ): NotificationListResponse =
        withContext(ioDispatcher) {
            validatePage(beforeId, limit)
            dataSource.inTransaction(isolationLevel = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
                authorization.requireCurrent(this, userId, forUpdate = false)
                val values =
                    query(
                        "SELECT n.* FROM notifications n WHERE recipient_user_id = ? AND (? IS NULL OR id < ?) AND (? = FALSE OR read_at IS NULL) ORDER BY id DESC LIMIT ?",
                        userId,
                        beforeId,
                        beforeId,
                        unreadOnly,
                        limit + 1,
                        map = ::notificationSummary,
                    )
                val unread =
                    query(
                        "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND read_at IS NULL",
                        userId,
                    ) { it.getLong(1) }.single()
                NotificationListResponse(values.take(limit), values.takeIf { it.size > limit }?.get(limit - 1)?.id, unread)
            }
        }

    /** The dashboard's activity is independent of inbox pagination and read/unread filters. */
    suspend fun recentAssignments(userId: Long): RecentAssignmentActivityResponse =
        withContext(ioDispatcher) {
            dataSource.inTransaction(isolationLevel = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
                authorization.requireCurrent(this, userId, forUpdate = false)
                RecentAssignmentActivityResponse(
                    query(
                        """
                        SELECT n.* FROM notifications n
                        WHERE n.recipient_user_id = ?
                          AND n.type IN ('TASK_ASSIGNED','TASK_UNASSIGNED','REVIEW_REQUESTED','REVIEW_REQUEST_REMOVED')
                        ORDER BY n.id DESC LIMIT 5
                        """.trimIndent(),
                        userId,
                        map = ::notificationSummary,
                    ),
                )
            }
        }

    suspend fun markRead(
        userId: Long,
        id: Long,
    ): Unit =
        withContext(ioDispatcher) {
            dataSource.memberTransaction {
                authorization.requireCurrent(this, userId)
                val owned =
                    query("SELECT id FROM notifications WHERE id = ? AND recipient_user_id = ? FOR UPDATE", id, userId) {
                        it.getLong(1)
                    }.isNotEmpty()
                if (!owned) throw notificationNotFound()
                execute(
                    "UPDATE notifications SET read_at = COALESCE(read_at, GREATEST(?, created_at)) WHERE id = ? AND recipient_user_id = ?",
                    clock.instant(),
                    id,
                    userId,
                )
            }
        }

    suspend fun markAllRead(userId: Long): Unit =
        withContext(ioDispatcher) {
            dataSource.memberTransaction {
                authorization.requireCurrent(this, userId)
                execute(
                    "UPDATE notifications SET read_at = GREATEST(?, created_at) WHERE recipient_user_id = ? AND read_at IS NULL",
                    clock.instant(),
                    userId,
                )
            }
        }

    suspend fun claimNative(
        userId: Long,
        sessionId: String,
        limit: Int = 20,
    ): NativeNotificationClaimResponse =
        withContext(ioDispatcher) {
            validatePage(null, limit)
            dataSource.memberTransaction {
                val now = clock.instant()
                requireDeliverySession(this, userId, sessionId, now)
                if (isUnavailable(this, userId, now)) return@memberTransaction NativeNotificationClaimResponse(null, emptyList())
                val notifications =
                    query(
                        """
                        SELECT n.* FROM notifications n
                        LEFT JOIN notification_deliveries d ON d.notification_id = n.id AND d.session_id = ?
                        WHERE n.recipient_user_id = ? AND n.read_at IS NULL
                          AND (d.notification_id IS NULL OR (d.delivered_at IS NULL AND d.lease_until <= ?))
                        ORDER BY n.id LIMIT ? FOR UPDATE
                        """.trimIndent(),
                        sessionId,
                        userId,
                        now,
                        limit,
                        map = ::notificationSummary,
                    )
                if (notifications.isEmpty()) return@memberTransaction NativeNotificationClaimResponse(null, emptyList())
                val claimId = UUID.randomUUID().toString()
                notifications.forEach { notification ->
                    execute(
                        """
                        INSERT INTO notification_deliveries (notification_id,session_id,claim_id,claimed_at,lease_until)
                        VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE claim_id=VALUES(claim_id),claimed_at=VALUES(claimed_at),lease_until=VALUES(lease_until)
                        """.trimIndent(),
                        notification.id,
                        sessionId,
                        claimId,
                        now,
                        now.plus(Duration.ofMinutes(2)),
                    )
                }
                NativeNotificationClaimResponse(claimId, notifications)
            }
        }

    suspend fun acknowledgeNative(
        userId: Long,
        sessionId: String,
        request: NativeNotificationAckRequest,
    ): Unit =
        withContext(ioDispatcher) {
            if (runCatching { UUID.fromString(request.claimId).toString() == request.claimId }.getOrDefault(false).not() ||
                request.notificationIds.isEmpty() || request.notificationIds.size > 100 || request.notificationIds.any { it <= 0 }
            ) {
                throw ApiFailure.invalidRequest("알림 수신 확인 요청이 올바르지 않습니다.")
            }
            dataSource.memberTransaction {
                val now = clock.instant()
                requireDeliverySession(this, userId, sessionId, now)
                request.notificationIds.forEach { id ->
                    val owned =
                        query(
                            "SELECT d.notification_id FROM notification_deliveries d JOIN notifications n ON n.id=d.notification_id WHERE d.notification_id=? AND d.session_id=? AND d.claim_id=? AND n.recipient_user_id=? FOR UPDATE",
                            id,
                            sessionId,
                            request.claimId,
                            userId,
                        ) { it.getLong(1) }.isNotEmpty()
                    if (!owned) throw notificationNotFound()
                    execute(
                        "UPDATE notification_deliveries SET delivered_at=COALESCE(delivered_at,GREATEST(?,claimed_at)) WHERE notification_id=? AND session_id=? AND claim_id=?",
                        now,
                        id,
                        sessionId,
                        request.claimId,
                    )
                }
            }
        }

    suspend fun purgeRead(lease: JobLease): Int =
        withContext(ioDispatcher) {
            dataSource.inTransaction {
                val now = clock.instant()
                lease.requireOwned(this, now)
                execute("DELETE FROM notifications WHERE read_at IS NOT NULL AND read_at <= ?", now.minus(Duration.ofDays(90)))
            }
        }

    private fun requireDeliverySession(
        connection: Connection,
        userId: Long,
        sessionId: String,
        now: Instant,
    ) {
        authorization.requireCurrent(connection, userId)
        val eligible =
            connection
                .query(
                    "SELECT id FROM desktop_sessions WHERE id=? AND user_id=? AND revoked_at IS NULL AND suspended_at IS NULL AND absolute_expires_at>? FOR UPDATE",
                    sessionId,
                    userId,
                    now,
                ) { it.getString(1) }
                .isNotEmpty()
        if (!eligible) throw ApiFailure.authenticationRequired()
    }
}

internal fun isUnavailable(
    connection: Connection,
    userId: Long,
    now: Instant,
): Boolean =
    connection
        .query(
            "SELECT 1 FROM unavailability_periods WHERE user_id=? AND starts_at<=? AND ends_at>? LIMIT 1",
            userId,
            now,
            now,
        ) { it.getInt(1) }
        .isNotEmpty()

private fun notificationSummary(result: ResultSet): NotificationSummary {
    val payload = Json.parseToJsonElement(result.getString("payload_json")) as? JsonObject ?: JsonObject(emptyMap())
    val type = result.getString("type")
    val displayName = payload.text("conflictingDisplayName") ?: payload.text("conflicting_display_name") ?: "다른 구성원"
    val taskName = payload.text("taskTitle") ?: payload.text("title") ?: "태스크"
    val text =
        when (type) {
            "GITHUB_ID_CONFLICT" -> "GitHub ID 충돌" to "$displayName 님이 현재 연결된 GitHub ID로 로그인을 시도했습니다."
            "MEMBER_POSITION_UNASSIGNED" -> "기술 포지션 미지정" to "새 구성원의 기술 포지션을 확인해 주세요."
            "TASK_INACTIVE_ASSIGNEE" -> "태스크 재배정 필요" to "탈퇴한 구성원이 맡은 미완료 태스크가 있습니다."
            "UNAVAILABILITY_CHANGED" -> "휴가·작업 불가 일정 변경" to "구성원의 일정이 변경되었습니다."
            "TASK_ASSIGNED", "ASSIGNED", "TASK_ASSIGNMENT" -> "태스크 배정" to "$taskName 담당자로 지정되었습니다."
            "TASK_UNASSIGNED", "UNASSIGNED", "TASK_UNASSIGNMENT" -> "태스크 담당 해제" to "$taskName 담당자에서 해제되었습니다."
            "REVIEW_REQUESTED" -> "PR 리뷰 요청" to "$taskName 리뷰어로 지정되었습니다."
            "REVIEW_REQUEST_REMOVED" -> "PR 리뷰 요청 해제" to "$taskName 리뷰 요청이 해제되었습니다."
            "TASK_DUE_SOON" -> "마감 임박" to "$taskName 마감이 3일 이내입니다."
            "TASK_OVERDUE" -> "지연 태스크 확인" to "$taskName 작업 상태를 확인해 주세요."
            "TASK_UNASSIGNED_DUE_SOON" -> "미할당 태스크 마감 임박" to "담당자가 없는 $taskName 마감이 임박했습니다."
            "TASK_UNASSIGNED_OVERDUE" -> "미할당 태스크 지연" to "담당자가 없는 $taskName 지연 상태를 확인해 주세요."
            else -> "Axia 알림" to "새로운 활동이 있습니다."
        }
    return NotificationSummary(
        id = result.getLong("id"),
        type = type,
        title = text.first,
        body = text.second,
        createdAtEpochMillis = requireNotNull(result.instant("created_at")).toEpochMilli(),
        readAtEpochMillis = result.instant("read_at")?.toEpochMilli(),
        taskId = payload.number("taskId"),
        memberId = payload.number("userId") ?: payload.number("departedUserId"),
        productId = payload.text("productId")?.let { id -> ProductId.entries.firstOrNull { it.name == id } },
        unavailabilityId = payload.number("unavailabilityId"),
    )
}

private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.number(name: String): Long? = (get(name) as? JsonPrimitive)?.longOrNull

private fun validatePage(
    beforeId: Long?,
    limit: Int,
) {
    if ((beforeId != null && beforeId <= 0) || limit !in 1..100) throw ApiFailure.invalidRequest("페이지 요청이 올바르지 않습니다.")
}

private fun notificationNotFound(): ApiFailure = ApiFailure(HttpStatusCode.NotFound, ApiErrorCode.ResourceNotFound, "알림을 찾을 수 없습니다.")
