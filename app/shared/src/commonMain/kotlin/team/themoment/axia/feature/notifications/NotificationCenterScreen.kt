package team.themoment.axia.feature.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import team.themoment.axia.designsystem.component.AxiaBadge
import team.themoment.axia.designsystem.component.AxiaBadgeStyle
import team.themoment.axia.designsystem.component.AxiaBanner
import team.themoment.axia.designsystem.component.AxiaBannerStyle
import team.themoment.axia.designsystem.component.AxiaButton
import team.themoment.axia.designsystem.component.AxiaButtonStyle
import team.themoment.axia.designsystem.component.AxiaDivider
import team.themoment.axia.designsystem.component.AxiaEmptyState
import team.themoment.axia.designsystem.component.AxiaPanel
import team.themoment.axia.designsystem.theme.AxiaTheme
import team.themoment.axia.platform.AxiaTime

@Composable
fun NotificationCenterScreen(
    state: NotificationUiState,
    callbacks: NotificationCallbacks,
    modifier: Modifier = Modifier,
) {
    val notifications = state.notifications.filter { !state.unreadOnly || it.readAtEpochMillis == null }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
            verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text("읽지 않은 알림 ${state.unreadCount}개", style = AxiaTheme.typography.sectionTitle, color = AxiaTheme.semanticColors.textPrimary)
            AxiaButton("전체", {
                callbacks.onUnreadOnlyChange(false)
            }, style = if (!state.unreadOnly) AxiaButtonStyle.Primary else AxiaButtonStyle.Secondary)
            AxiaButton("읽지 않음", {
                callbacks.onUnreadOnlyChange(true)
            }, style = if (state.unreadOnly) AxiaButtonStyle.Primary else AxiaButtonStyle.Secondary)
            AxiaButton("새로고침", callbacks.onRefresh, enabled = !state.loading && !state.saving, style = AxiaButtonStyle.Secondary)
            AxiaButton("모두 읽음", callbacks.onMarkAllRead, enabled = state.unreadCount > 0 && !state.saving, style = AxiaButtonStyle.Tertiary)
        }
        Text(
            "네이티브 알림을 받아도 읽음으로 바뀌지 않습니다. 읽은 알림은 90일 후 자동 삭제됩니다.",
            style = AxiaTheme.typography.secondary,
            color = AxiaTheme.semanticColors.textSecondary,
        )
        state.error?.let { AxiaBanner("알림을 확인하지 못했어요", description = it, style = AxiaBannerStyle.Danger, announceChanges = true) }
        AxiaPanel(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            LazyColumn {
                if (notifications.isEmpty()) {
                    item {
                        AxiaEmptyState(
                            if (state.loading) {
                                "알림을 불러오고 있어요"
                            } else if (state.unreadOnly) {
                                "읽지 않은 알림이 없어요"
                            } else {
                                "아직 받은 알림이 없어요"
                            },
                            "태스크 배정, 리뷰 요청, 마감과 구성원 변경 알림을 이곳에서 확인할 수 있습니다.",
                        )
                    }
                }
                items(notifications, key = { it.id }) { notification ->
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = AxiaTheme.spacing.space4),
                        verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
                            verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (notification.readAtEpochMillis == null) AxiaBadge("읽지 않음", AxiaBadgeStyle.Info)
                            notification.productId?.let { AxiaBadge(it.displayName, AxiaBadgeStyle.Product) }
                            Text(
                                notification.title,
                                style = AxiaTheme.typography.bodyEmphasis,
                                color = AxiaTheme.semanticColors.textPrimary,
                                modifier =
                                    Modifier
                                        .semantics {
                                            heading()
                                        },
                            )
                        }
                        Text(notification.body, style = AxiaTheme.typography.body, color = AxiaTheme.semanticColors.textPrimary)
                        Text(
                            AxiaTime.label(notification.createdAtEpochMillis),
                            style = AxiaTheme.typography.metadata,
                            color = AxiaTheme.semanticColors.textSecondary,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2)) {
                            notification.taskId?.let { taskId ->
                                AxiaButton("태스크 열기", { callbacks.onOpenTask(taskId) }, style = AxiaButtonStyle.Secondary)
                            }
                            notification.memberId?.let { memberId ->
                                AxiaButton("구성원 보기", { callbacks.onOpenMember(memberId) }, style = AxiaButtonStyle.Secondary)
                            }
                            if (notification.unavailabilityId != null) {
                                AxiaButton(
                                    "변경 일정 보기",
                                    { callbacks.onOpenUnavailability(notification.memberId) },
                                    style = AxiaButtonStyle.Secondary,
                                )
                            }
                            if (notification.readAtEpochMillis == null) {
                                AxiaButton(
                                    "읽음으로 표시",
                                    { callbacks.onMarkRead(notification.id) },
                                    enabled = !state.saving,
                                    style = AxiaButtonStyle.Tertiary,
                                )
                            }
                        }
                    }
                    AxiaDivider()
                }
                if (state.nextCursor != null) {
                    item {
                        AxiaButton(
                            "이전 알림 더 보기",
                            callbacks.onLoadMore,
                            enabled = !state.loading && !state.saving,
                            modifier = Modifier.padding(top = AxiaTheme.spacing.space4),
                            style = AxiaButtonStyle.Secondary,
                        )
                    }
                }
            }
        }
    }
}
