package team.themoment.axia.feature.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import team.themoment.axia.designsystem.component.AxiaBanner
import team.themoment.axia.designsystem.component.AxiaBannerStyle
import team.themoment.axia.designsystem.component.AxiaButton
import team.themoment.axia.designsystem.component.AxiaButtonStyle
import team.themoment.axia.designsystem.component.AxiaDivider
import team.themoment.axia.designsystem.component.AxiaEmptyState
import team.themoment.axia.designsystem.component.AxiaPanel
import team.themoment.axia.designsystem.component.AxiaSelect
import team.themoment.axia.designsystem.component.AxiaSelectOption
import team.themoment.axia.designsystem.component.AxiaTextField
import team.themoment.axia.designsystem.theme.AxiaTheme
import team.themoment.axia.products.IntegrationAccess
import team.themoment.axia.tasks.TaskKind
import team.themoment.axia.tasks.TaskPriority

@Composable
fun ReminderPolicyScreen(
    state: ReminderPolicyUiState,
    callbacks: ReminderPolicyCallbacks,
    modifier: Modifier = Modifier,
) {
    val canGlobal = canManageGlobalReminderPolicy(state.currentUser)
    val projects =
        state.projects
            .filter {
                canManageBoardReminderPolicy(state.currentUser, it)
            }.sortedWith(compareBy({ it.productId.ordinal }, { it.number }))
    val selected = projects.singleOrNull { it.id == state.selectedProjectId }
    val boardPolicy = selected?.let { state.boardPolicies[it.id] }
    var globalDraft by remember(state.globalPolicy) { mutableStateOf(GlobalReminderDraft.from(state.globalPolicy)) }
    var boardDraft by remember(selected?.id, boardPolicy) { mutableStateOf(BoardReminderDraft.from(boardPolicy)) }
    var previewKind by remember { mutableStateOf(TaskKind.ISSUE) }
    var previewPriority by remember { mutableStateOf(TaskPriority.UNSET) }
    val busy = state.loading || state.saving
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space5)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4),
            verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
        ) {
            Text(
                "리마인드 정책",
                Modifier.semantics { heading() },
                style = AxiaTheme.typography.pageTitle,
                color = AxiaTheme.semanticColors.textPrimary,
            )
            AxiaButton(if (state.loading) "불러오는 중" else "새로고침", callbacks.onRefresh, enabled = !busy, style = AxiaButtonStyle.Secondary)
        }
        AxiaBanner(
            "마감일이 있으면 날짜가 우선합니다",
            description = "Target date가 있으면 한국 시간 23:59가 마감입니다. 아래 시간 기준은 마감일이 없는 작업에만 적용되며, 휴가·작업 불가 시간은 개인별로 제외합니다.",
        )
        state.error?.let { AxiaBanner("정책을 반영하지 못했습니다", description = it, style = AxiaBannerStyle.Danger, announceChanges = true) }
        state.successMessage?.let { AxiaBanner("정책을 저장했습니다", description = it, style = AxiaBannerStyle.Success, announceChanges = true) }
        if (state.currentUser == null) {
            AxiaEmptyState("권한 정보를 불러오고 있습니다", "로그인 상태와 현재 역할을 확인한 뒤 정책을 표시합니다.")
            return@Column
        }
        if (!canGlobal && projects.isEmpty()) {
            AxiaEmptyState("리마인드 정책 관리 권한이 없습니다", "PO는 자신의 제품 보드, Head는 모든 보드를 관리합니다. Team Leader·C-Leader·유효 시스템 관리자는 전역 기준도 관리합니다.")
            return@Column
        }
        if (canGlobal) {
            AxiaPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4)) {
                    SectionTitle("유형별 전역 기준")
                    SupportingText("보드 기본값과 Priority 값이 없을 때 적용합니다. 초기 기준은 Issue·Draft 336시간(14일), PR 담당·리뷰 요청 72시간(3일)입니다.")
                    if (state.globalPolicy ==
                        null
                    ) {
                        SupportingText(if (state.loading) "현재 전역 정책을 불러오고 있습니다." else "현재 정책이 조회되지 않았습니다. 새로고침 후 편집해 주세요.")
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4),
                        verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
                    ) {
                        TaskKind.entries.forEach { kind ->
                            val value = globalDraft.hoursByKind[kind].orEmpty()
                            AxiaTextField(
                                value,
                                {
                                    globalDraft = globalDraft.copy(hoursByKind = globalDraft.hoursByKind + (kind to it))
                                },
                                "${kind.reminderLabel} (시간)",
                                Modifier.width(190.dp),
                                enabled =
                                    !busy && state.globalPolicy != null,
                                errorText =
                                    if (value.isNotBlank() &&
                                        validHours(value) == null
                                    ) {
                                        "1~87,600시간"
                                    } else {
                                        null
                                    },
                            )
                        }
                    }
                    globalDraft.validationError()?.takeIf { state.globalPolicy != null }?.let { SupportingText(it, danger = true) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2)) {
                        AxiaButton(
                            if (state.saving) "저장 중" else "전역 기준 저장",
                            {
                                if (canGlobal && state.globalPolicy != null &&
                                    globalDraft.validationError() == null
                                ) {
                                    callbacks.onSaveGlobal(globalDraft.toPolicy())
                                }
                            },
                            enabled =
                                !busy && state.globalPolicy != null && globalDraft.validationError() == null,
                        )
                        AxiaButton("초기 기준 채우기", {
                            globalDraft = GlobalReminderDraft.defaults()
                        }, enabled = !busy && state.globalPolicy != null, style = AxiaButtonStyle.Secondary)
                    }
                    SupportingText("초기 기준 채우기는 입력만 바꿉니다. 저장해야 적용되며 기존 알림을 삭제하지 않습니다.")
                }
            }
        }
        AxiaPanel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4)) {
                SectionTitle("보드별 기준")
                SupportingText("태스크 직접 기간 → 해당 Priority 값 → 보드 기본값 → 유형별 전역값 순으로 적용합니다. Issue·PR은 저장소 기본 보드만, Draft는 생성된 보드만 사용합니다.")
                AxiaSelect(
                    "Project 보드",
                    selected?.id,
                    projects.map {
                        AxiaSelectOption<Long?>(
                            it.id,
                            "${it.productId.displayName} / #${it.number} ${it.title.orEmpty()}${if (!it.enabled) " (비활성)" else ""}",
                        )
                    },
                    { id ->
                        if (id !=
                            null
                        ) {
                            callbacks.onSelectProject(id)
                        }
                    },
                    enabled = !busy && projects.isNotEmpty(),
                )
                when {
                    projects.isEmpty() -> {
                        SupportingText("관리할 수 있는 보드가 없습니다. 제품 연동을 먼저 추가해 주세요.")
                    }

                    selected == null -> {
                        SupportingText("조회하거나 편집할 보드를 선택해 주세요.")
                    }

                    boardPolicy == null -> {
                        SupportingText(
                            if (state.loading) "보드 정책을 불러오고 있습니다." else "현재 정책을 조회하지 못했습니다. 새로고침으로 다시 확인해 주세요.",
                        )
                    }

                    else -> {
                        if (!selected.enabled ||
                            selected.access != IntegrationAccess.AVAILABLE
                        ) {
                            AxiaBanner(
                                "현재 적용되지 않는 보드입니다",
                                description = "정책은 저장할 수 있지만 보드 연동이 활성화되고 접근 가능해져야 적용됩니다.",
                                style = AxiaBannerStyle.Warning,
                            )
                        }
                        AxiaTextField(
                            boardDraft.defaultHours,
                            {
                                boardDraft = boardDraft.copy(defaultHours = it)
                            },
                            "보드 기본값 (시간)",
                            Modifier.width(230.dp),
                            enabled = !busy,
                            placeholder = "비워 두면 전역 상속",
                            supportingText = "Priority 값이 없는 항목의 기준입니다.",
                            errorText =
                                if (boardDraft.defaultHours.isNotBlank() &&
                                    validHours(boardDraft.defaultHours) == null
                                ) {
                                    "1~87,600시간"
                                } else {
                                    null
                                },
                        )
                        AxiaDivider()
                        SectionTitle("Priority별 기준")
                        SupportingText("빈칸은 ‘사용 안 함’이 아니라 상속입니다. Unset은 Priority가 지정되지 않은 항목의 값입니다.")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4),
                            verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
                        ) {
                            TaskPriority.entries.forEach { priority ->
                                val value = boardDraft.priorityHours[priority].orEmpty()
                                AxiaTextField(
                                    value,
                                    {
                                        boardDraft =
                                            boardDraft.copy(priorityHours = boardDraft.priorityHours + (priority to it))
                                    },
                                    "${priority.reminderLabel} (시간)",
                                    Modifier.width(190.dp),
                                    enabled = !busy,
                                    placeholder = "상위 기준 상속",
                                    supportingText =
                                        validHours(boardDraft.defaultHours)?.let { "비우면 보드 기본 $it 시간" } ?: "비우면 유형별 전역 기준",
                                    errorText =
                                        if (value.isNotBlank() &&
                                            validHours(value) == null
                                        ) {
                                            "1~87,600시간"
                                        } else {
                                            null
                                        },
                                )
                            }
                        }
                        boardDraft.validationError()?.let { SupportingText(it, danger = true) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2)) {
                            AxiaButton(
                                if (state.saving) "저장 중" else "보드 기준 저장",
                                {
                                    if (canManageBoardReminderPolicy(state.currentUser, selected) &&
                                        boardDraft.validationError() == null
                                    ) {
                                        callbacks.onSaveBoard(selected.id, boardDraft.toPolicy())
                                    }
                                },
                                enabled =
                                    !busy && boardDraft.validationError() == null,
                            )
                            AxiaButton(
                                "모두 전역값 상속",
                                { boardDraft = BoardReminderDraft() },
                                enabled = !busy,
                                style = AxiaButtonStyle.Secondary,
                            )
                        }
                        AxiaDivider()
                        SectionTitle("상속 결과 확인")
                        SupportingText("현재 입력값의 미리보기입니다. 태스크 직접 기간과 마감일이 없는 경우를 기준으로 합니다.")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4),
                            verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
                        ) {
                            AxiaSelect("작업 유형", previewKind, TaskKind.entries.map { AxiaSelectOption(it, it.reminderLabel) }, {
                                previewKind =
                                    it
                            })
                            AxiaSelect("Priority", previewPriority, TaskPriority.entries.map { AxiaSelectOption(it, it.reminderLabel) }, {
                                previewPriority =
                                    it
                            })
                        }
                        val effective = previewReminderHours(boardDraft, state.globalPolicy, previewKind, previewPriority)
                        when {
                            boardDraft.validationError() != null -> {
                                SupportingText("유효한 시간을 입력하면 상속 결과를 표시합니다.")
                            }

                            effective != null -> {
                                Text(
                                    "적용 기준: $effective 시간",
                                    style = AxiaTheme.typography.bodyEmphasis,
                                    color = AxiaTheme.semanticColors.textPrimary,
                                )
                            }

                            else -> {
                                SupportingText("유형별 전역 기준을 상속합니다. 현재 운영 전역값은 조회되지 않았으므로 전역 관리자에게 확인해 주세요.")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.semantics { heading() }, style = AxiaTheme.typography.bodyEmphasis, color = AxiaTheme.semanticColors.textPrimary)
}

@Composable
private fun SupportingText(
    text: String,
    danger: Boolean = false,
) {
    Text(
        text,
        style = AxiaTheme.typography.secondary,
        color = if (danger) AxiaTheme.semanticColors.danger.content else AxiaTheme.semanticColors.textSecondary,
    )
}
