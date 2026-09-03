package team.themoment.axia.feature.analytics

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
import team.themoment.axia.analytics.CompletionKind
import team.themoment.axia.designsystem.component.AxiaBadge
import team.themoment.axia.designsystem.component.AxiaBadgeStyle
import team.themoment.axia.designsystem.component.AxiaBanner
import team.themoment.axia.designsystem.component.AxiaBannerStyle
import team.themoment.axia.designsystem.component.AxiaButton
import team.themoment.axia.designsystem.component.AxiaButtonStyle
import team.themoment.axia.designsystem.component.AxiaDivider
import team.themoment.axia.designsystem.component.AxiaEmptyState
import team.themoment.axia.designsystem.component.AxiaPanel
import team.themoment.axia.designsystem.component.AxiaSelect
import team.themoment.axia.designsystem.component.AxiaSelectOption
import team.themoment.axia.designsystem.theme.AxiaTheme
import team.themoment.axia.domain.ProductId
import team.themoment.axia.platform.AxiaTime

/** Completion history is public to active members, independently of private analytics scope. */
@Composable
fun CompletionHistoryScreen(
    state: CompletionHistoryUiState,
    callbacks: CompletionHistoryCallbacks,
    modifier: Modifier = Modifier,
) {
    val memberNames = state.members.associate { it.id to it.displayName }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
            verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            AxiaSelect(
                label = "완료 기여 구성원",
                value = state.selectedUserId,
                options =
                    listOf(AxiaSelectOption<Long?>(null, "모든 구성원")) +
                        state.members.map {
                            AxiaSelectOption<Long?>(it.id, it.displayName + if (it.isActive) "" else " (비활성)")
                        },
                onChange = callbacks.onFilterUser,
                enabled = !state.loading,
            )
            AxiaSelect(
                label = "제품",
                value = state.selectedProductId,
                options =
                    listOf(AxiaSelectOption<ProductId?>(null, "모든 제품")) +
                        ProductId.entries.map { AxiaSelectOption<ProductId?>(it, it.displayName) },
                onChange = callbacks.onFilterProduct,
                enabled = !state.loading,
            )
            AxiaButton("새로고침", callbacks.onRefresh, enabled = !state.loading, style = AxiaButtonStyle.Secondary)
        }
        Text(
            "완료된 이슈와 병합된 PR의 기록입니다. 모든 활성 구성원이 열람할 수 있습니다.",
            style = AxiaTheme.typography.secondary,
            color = AxiaTheme.semanticColors.textSecondary,
        )
        state.error?.let { AxiaBanner("완료 이력을 불러오지 못했어요", description = it, style = AxiaBannerStyle.Danger, announceChanges = true) }
        AxiaPanel(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            LazyColumn {
                if (state.completions.isEmpty()) {
                    item { AxiaEmptyState(if (state.loading) "완료 이력을 불러오고 있어요" else "완료 이력이 없어요", "다른 구성원이나 제품을 선택해 기록을 확인해 주세요.") }
                }
                items(state.completions, key = { it.id }) { completion ->
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = AxiaTheme.spacing.space4),
                        verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            AxiaBadge(completion.productId.displayName, AxiaBadgeStyle.Product)
                            AxiaBadge(if (completion.kind == CompletionKind.ISSUE_CLOSE) "이슈 닫힘" else "PR 병합", AxiaBadgeStyle.Success)
                            Text(
                                AxiaTime.label(completion.occurredAtEpochMillis),
                                style = AxiaTheme.typography.metadata,
                                color = AxiaTheme.semanticColors.textSecondary,
                            )
                        }
                        Text(completion.title, style = AxiaTheme.typography.bodyEmphasis, color = AxiaTheme.semanticColors.textPrimary)
                        Text(
                            "처리시간 ${processingDurationLabel(completion.processingSeconds.toDouble())}",
                            style = AxiaTheme.typography.secondary,
                            color = AxiaTheme.semanticColors.textSecondary,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            AxiaButton("태스크 열기", { callbacks.onOpenTask(completion.taskId) }, style = AxiaButtonStyle.Secondary)
                            if (completion.creditedUserIds.isEmpty()) {
                                Text("완료 기여 구성원 없음", style = AxiaTheme.typography.metadata, color = AxiaTheme.semanticColors.textSecondary)
                            }
                            completion.creditedUserIds.forEach { userId ->
                                AxiaButton(
                                    memberNames[userId] ?: "구성원 #$userId",
                                    { callbacks.onOpenMember(userId) },
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
                            "이전 완료 이력 더 보기",
                            callbacks.onLoadMore,
                            enabled = !state.loading,
                            modifier = Modifier.padding(top = AxiaTheme.spacing.space4),
                            style = AxiaButtonStyle.Secondary,
                        )
                    }
                }
            }
        }
    }
}
