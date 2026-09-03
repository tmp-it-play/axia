package team.themoment.axia.feature.unavailability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import team.themoment.axia.designsystem.component.AxiaBadge
import team.themoment.axia.designsystem.component.AxiaBadgeStyle
import team.themoment.axia.designsystem.component.AxiaBanner
import team.themoment.axia.designsystem.component.AxiaBannerStyle
import team.themoment.axia.designsystem.component.AxiaButton
import team.themoment.axia.designsystem.component.AxiaButtonStyle
import team.themoment.axia.designsystem.component.AxiaChoiceGroup
import team.themoment.axia.designsystem.component.AxiaChoiceOption
import team.themoment.axia.designsystem.component.AxiaDivider
import team.themoment.axia.designsystem.component.AxiaEmptyState
import team.themoment.axia.designsystem.component.AxiaPanel
import team.themoment.axia.designsystem.component.AxiaSelect
import team.themoment.axia.designsystem.component.AxiaSelectOption
import team.themoment.axia.designsystem.component.AxiaTextField
import team.themoment.axia.designsystem.theme.AxiaTheme
import team.themoment.axia.members.MemberSummary
import team.themoment.axia.platform.AxiaTime
import team.themoment.axia.unavailability.UNAVAILABILITY_REASON_WARNING
import team.themoment.axia.unavailability.UnavailabilityPeriod
import team.themoment.axia.unavailability.UnavailabilityType

@Composable
fun UnavailabilityScreen(
    state: UnavailabilityUiState,
    callbacks: UnavailabilityCallbacks,
    modifier: Modifier = Modifier,
) {
    val currentMember = state.currentUser?.member
    val members = (state.members + listOfNotNull(currentMember)).distinctBy(MemberSummary::id)
    val membersById = members.associateBy(MemberSummary::id)
    val selectedMember = state.selectedUserId?.let(membersById::get) ?: currentMember.takeIf { state.selectedUserId == null }
    val periods =
        state.periods
            .filter { state.selectedUserId == null || it.userId == state.selectedUserId }
            .sortedWith(compareByDescending<UnavailabilityPeriod> { it.startsAtEpochMillis }.thenByDescending { it.id })
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
            verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            AxiaSelect(
                label = "일정 조회 대상",
                value = state.selectedUserId,
                options =
                    listOf(AxiaSelectOption<Long?>(null, "모든 구성원")) +
                        members.map {
                            AxiaSelectOption<Long?>(it.id, it.displayName + if (it.id == currentMember?.id) " (나)" else "")
                        },
                onChange = callbacks.onFilterUser,
                enabled = !state.saving,
            )
            AxiaButton("새로고침", callbacks.onRefresh, enabled = !state.loading && !state.saving, style = AxiaButtonStyle.Secondary)
            AxiaButton(
                label = if (selectedMember?.id == currentMember?.id) "내 일정 등록" else "${selectedMember?.displayName ?: "구성원"} 일정 등록",
                onClick = { selectedMember?.let { callbacks.onCreate(it.id) } },
                enabled = !state.saving && selectedMember?.let { canEditUnavailability(state.currentUser, it) } == true,
            )
        }
        Text(
            "모든 일정은 한국 시간(Asia/Seoul)입니다. 휴가와 업무 불가 기간의 유형·시각·사유는 모든 팀원이 볼 수 있습니다.",
            style = AxiaTheme.typography.secondary,
            color = AxiaTheme.semanticColors.textSecondary,
        )
        state.error?.let { AxiaBanner("일정을 저장하지 못했어요", description = it, style = AxiaBannerStyle.Danger, announceChanges = true) }
        state.successMessage?.let { AxiaBanner("일정에 반영했어요", description = it, style = AxiaBannerStyle.Success, announceChanges = true) }
        Row(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4)) {
            AxiaPanel(Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4)) {
                    Text(
                        "휴가 · 업무 불가 ${periods.size}건",
                        style = AxiaTheme.typography.sectionTitle,
                        color = AxiaTheme.semanticColors.textPrimary,
                        modifier = Modifier.semantics { heading() },
                    )
                    when {
                        state.loading && periods.isEmpty() -> {
                            Text("일정을 불러오고 있어요", color = AxiaTheme.semanticColors.textSecondary)
                        }

                        periods.isEmpty() -> {
                            AxiaEmptyState("등록한 일정이 없어요", "휴가나 업무에 참여할 수 없는 시간을 팀에 알려 주세요.")
                        }

                        else -> {
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 640.dp),
                                verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4),
                            ) {
                                items(periods, key = UnavailabilityPeriod::id) { period ->
                                    PeriodRow(period, membersById[period.userId], state, callbacks)
                                    AxiaDivider()
                                }
                            }
                        }
                    }
                }
            }
            state.editor?.let { draft ->
                AxiaPanel(Modifier.width(AxiaTheme.dimensions.detailPanelWidth)) {
                    PeriodEditor(draft, membersById[draft.userId], state, callbacks)
                }
            }
        }
    }
}

@Composable
private fun PeriodRow(
    period: UnavailabilityPeriod,
    member: MemberSummary?,
    state: UnavailabilityUiState,
    callbacks: UnavailabilityCallbacks,
) {
    val mayEdit = member?.let { canEditUnavailability(state.currentUser, it) } == true
    val now = AxiaTime.nowEpochMillis()
    Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
            verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                member?.displayName ?: "구성원 #${period.userId}",
                style = AxiaTheme.typography.bodyEmphasis,
                color = AxiaTheme.semanticColors.textPrimary,
            )
            AxiaBadge(period.type.label, AxiaBadgeStyle.Warning)
            AxiaBadge(
                when {
                    period.endsAtEpochMillis <= now -> "종료됨"
                    period.startsAtEpochMillis > now -> "예정"
                    else -> "진행 중"
                },
                AxiaBadgeStyle.Metadata,
            )
        }
        Text(
            "${AxiaTime.label(period.startsAtEpochMillis)} → ${AxiaTime.label(period.endsAtEpochMillis)}",
            style = AxiaTheme.typography.secondary,
            color = AxiaTheme.semanticColors.textSecondary,
        )
        Text(period.reason, style = AxiaTheme.typography.body, color = AxiaTheme.semanticColors.textPrimary)
        if (mayEdit) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2)) {
                AxiaButton("일정 수정", { callbacks.onEdit(period) }, enabled = !state.saving, style = AxiaButtonStyle.Secondary)
                AxiaButton("삭제", { callbacks.onRequestDelete(period.id) }, enabled = !state.saving, style = AxiaButtonStyle.Tertiary)
            }
            if (state.deleteConfirmationId == period.id) {
                AxiaBanner("이 일정을 삭제할까요?", description = "삭제하면 팀의 가용 상태에 즉시 반영됩니다.", style = AxiaBannerStyle.Warning)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2)) {
                    AxiaButton("취소", { callbacks.onRequestDelete(null) }, enabled = !state.saving, style = AxiaButtonStyle.Secondary)
                    AxiaButton(
                        "일정 삭제",
                        { callbacks.onDeletePeriod(period.id, period.version) },
                        enabled = !state.saving,
                        style = AxiaButtonStyle.Danger,
                    )
                }
            }
        }
    }
}

@Composable
private fun PeriodEditor(
    draft: UnavailabilityEditorDraft,
    member: MemberSummary?,
    state: UnavailabilityUiState,
    callbacks: UnavailabilityCallbacks,
) {
    val mayEdit = member?.let { canEditUnavailability(state.currentUser, it) } == true
    val enabled = mayEdit && !state.saving
    Column(
        modifier = Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4),
    ) {
        Text(
            if (draft.periodId == null) "일정 등록" else "일정 수정",
            style = AxiaTheme.typography.sectionTitle,
            color = AxiaTheme.semanticColors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(member?.displayName ?: "구성원 확인 필요", style = AxiaTheme.typography.bodyEmphasis, color = AxiaTheme.semanticColors.textPrimary)
        if (!mayEdit) {
            AxiaBanner("이 일정은 수정할 수 없어요", description = "현재 권한 또는 구성원의 활성 상태를 확인해 주세요.", style = AxiaBannerStyle.Warning)
        }
        AxiaChoiceGroup(
            groupLabel = "일정 유형",
            options =
                UnavailabilityType.entries.map {
                    AxiaChoiceOption(
                        key = it,
                        label = it.label,
                        description = if (it == UnavailabilityType.VACATION) "팀에 휴가 기간을 알립니다." else "팀 업무에 참여할 수 없는 시간을 알립니다.",
                        enabled = enabled,
                    )
                },
            selectedKey = draft.type,
            onSelectionChange = { callbacks.onEditorChange(draft.copy(type = it)) },
        )
        AxiaTextField(
            value = draft.startsAt,
            onValueChange = { callbacks.onEditorChange(draft.copy(startsAt = it)) },
            label = "시작 시각",
            placeholder = "YYYY-MM-DD HH:mm",
            supportingText = "한국 시간 · 예: 2026-09-03 09:00",
            errorText = draft.startError.takeIf { draft.startsAt.isNotBlank() },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        AxiaTextField(
            value = draft.endsAt,
            onValueChange = { callbacks.onEditorChange(draft.copy(endsAt = it)) },
            label = "종료 시각",
            placeholder = "YYYY-MM-DD HH:mm",
            supportingText = "종료 시각은 필수이며, 시작 시각보다 늦어야 합니다.",
            errorText = draft.endError.takeIf { draft.endsAt.isNotBlank() },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        AxiaBanner("사유는 팀 전체에 공개됩니다", description = UNAVAILABILITY_REASON_WARNING, style = AxiaBannerStyle.Warning)
        AxiaTextField(
            value = draft.reason,
            onValueChange = { callbacks.onEditorChange(draft.copy(reason = it)) },
            label = "공개 사유",
            placeholder = "팀이 알아야 할 업무 관련 사유를 간단히 적어 주세요.",
            supportingText = "${draft.reason.trim().length} / 2,000자 · 필수 입력",
            errorText = draft.reasonError.takeIf { draft.reason.isNotEmpty() },
            enabled = enabled,
            singleLine = false,
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
        )
        if (draft.periodId != null && (draft.expectedVersion == null || draft.expectedVersion <= 0)) {
            AxiaBanner("최신 일정을 다시 불러와 주세요", description = "변경 버전을 확인할 수 없어 저장할 수 없습니다.", style = AxiaBannerStyle.Warning)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2)) {
            AxiaButton("취소", callbacks.onCancelEdit, enabled = !state.saving, style = AxiaButtonStyle.Secondary)
            AxiaButton(
                label = if (state.saving) "저장 중…" else "일정 저장",
                onClick = {
                    if (draft.isValid && mayEdit) {
                        if (draft.periodId == null) {
                            state.currentUser
                                ?.member
                                ?.id
                                ?.let { callbacks.onCreatePeriod(draft.createRequest(it)) }
                        } else {
                            callbacks.onUpdatePeriod(draft.periodId, draft.updateRequest())
                        }
                    }
                },
                enabled = enabled && draft.isValid,
            )
        }
    }
}

private val UnavailabilityType.label: String
    get() = if (this == UnavailabilityType.VACATION) "휴가" else "업무 불가"
