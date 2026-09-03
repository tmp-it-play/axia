package team.themoment.axia.feature.notifications

import team.themoment.axia.notifications.NotificationSummary

data class NotificationUiState(
    val notifications: List<NotificationSummary> = emptyList(),
    val unreadCount: Long = 0,
    val unreadOnly: Boolean = false,
    val nextCursor: Long? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

data class NotificationCallbacks(
    val onRefresh: () -> Unit,
    val onUnreadOnlyChange: (Boolean) -> Unit,
    val onMarkRead: (Long) -> Unit,
    val onMarkAllRead: () -> Unit,
    val onOpenTask: (Long) -> Unit,
    val onOpenMember: (Long) -> Unit,
    /** The argument is the schedule owner's member ID, or null for all schedules. */
    val onOpenUnavailability: (Long?) -> Unit,
    val onLoadMore: () -> Unit,
)
