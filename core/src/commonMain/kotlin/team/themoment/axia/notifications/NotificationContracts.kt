package team.themoment.axia.notifications

import kotlinx.serialization.Serializable
import team.themoment.axia.domain.ProductId

@Serializable
data class NotificationSummary(
    val id: Long,
    val type: String,
    val title: String,
    val body: String,
    val createdAtEpochMillis: Long,
    val readAtEpochMillis: Long?,
    val taskId: Long? = null,
    val memberId: Long? = null,
    val productId: ProductId? = null,
    val unavailabilityId: Long? = null,
)

@Serializable
data class NotificationListResponse(
    val notifications: List<NotificationSummary>,
    val nextCursor: Long?,
    val unreadCount: Long,
)

@Serializable
data class RecentAssignmentActivityResponse(
    val assignments: List<NotificationSummary>,
)

@Serializable
data class NativeNotificationClaimResponse(
    val claimId: String?,
    val notifications: List<NotificationSummary>,
)

@Serializable
data class NativeNotificationAckRequest(
    val claimId: String,
    val notificationIds: Set<Long>,
)
