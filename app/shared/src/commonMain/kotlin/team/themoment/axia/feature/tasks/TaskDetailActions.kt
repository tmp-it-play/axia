package team.themoment.axia.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import team.themoment.axia.designsystem.component.AxiaButton
import team.themoment.axia.designsystem.component.AxiaButtonStyle
import team.themoment.axia.designsystem.component.AxiaCheckbox
import team.themoment.axia.designsystem.component.AxiaDivider
import team.themoment.axia.designsystem.component.AxiaTextField
import team.themoment.axia.designsystem.theme.AxiaTheme
import team.themoment.axia.tasks.TaskDetail
import team.themoment.axia.tasks.TaskKind

@Composable
fun TaskDetailActions(
    detail: TaskDetail,
    readOnly: Boolean,
    busy: Boolean,
    onAction: (TaskWriteMode) -> Unit,
    onReminderOverride: (Int?) -> Unit,
    onReminderSuppression: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val task = detail.task
    val gitHubWriteEnabled = task.canWrite && !readOnly && !busy
    val reminderOverrideEnabled = detail.canChangeReminderOverride && !busy
    val reminderSuppressionEnabled = detail.canChangeReminderSuppression && !busy
    var overrideHours by remember(
        task.id,
        task.assignmentKey,
        detail.reminderOverrideHours,
    ) { mutableStateOf(detail.reminderOverrideHours?.toString().orEmpty()) }
    val parsedHours = overrideHours.toIntOrNull()
    val validHours = overrideHours.isBlank() || (parsedHours != null && parsedHours in 1..87600)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3)) {
        if (readOnly) {
            Text(
                "GitHub 원본 변경은 연결 복구 후 사용할 수 있습니다. 권한이 있는 리마인드 설정은 계속 변경할 수 있습니다.",
                style = AxiaTheme.typography.secondary,
                color = AxiaTheme.semanticColors.warning.content,
            )
        }
        if (!task.canWrite) {
            Text(
                "현재 권한 또는 연동 상태에서 GitHub 원본은 읽기 전용입니다.",
                style = AxiaTheme.typography.secondary,
                color = AxiaTheme.semanticColors.textSecondary,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
            verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
        ) {
            val modes =
                buildList {
                    add(TaskWriteMode.EDIT)
                    add(TaskWriteMode.ASSIGNEES)
                    if (task.kind in
                        setOf(TaskKind.ISSUE, TaskKind.PULL_REQUEST)
                    ) {
                        add(TaskWriteMode.COMMENT)
                        add(TaskWriteMode.ADD_TO_PROJECT)
                    }
                    if (task.kind == TaskKind.PULL_REQUEST) add(TaskWriteMode.REVIEWERS)
                    if (task.projects.isNotEmpty()) {
                        add(TaskWriteMode.FIELDS)
                        add(TaskWriteMode.ARCHIVE)
                    }
                    if (task.kind == TaskKind.DRAFT) add(TaskWriteMode.CONVERT)
                    if (task.kind == TaskKind.ISSUE) {
                        add(if (task.isOpen) TaskWriteMode.ISSUE_CLOSE else TaskWriteMode.ISSUE_REOPEN)
                    }
                }
            modes.filter { taskWriteAllowed(it, detail) }.forEach { mode ->
                AxiaButton(
                    mode.title,
                    { onAction(mode) },
                    style =
                        if (mode ==
                            TaskWriteMode.ISSUE_CLOSE
                        ) {
                            AxiaButtonStyle.Danger
                        } else {
                            AxiaButtonStyle.Secondary
                        },
                    enabled = gitHubWriteEnabled,
                )
            }
        }
        if (task.kind ==
            TaskKind.PULL_REQUEST
        ) {
            Text(
                "PR 닫기·다시 열기·Merge·리뷰 제출은 GitHub 원본에서 진행해 주세요.",
                style = AxiaTheme.typography.metadata,
                color = AxiaTheme.semanticColors.textSecondary,
            )
        }
        if (detail.canChangeReminderOverride || detail.canChangeReminderSuppression) {
            AxiaDivider()
            Text("리마인드", style = AxiaTheme.typography.bodyEmphasis, color = AxiaTheme.semanticColors.textPrimary)
            if (detail.canChangeReminderOverride) {
                AxiaTextField(
                    overrideHours,
                    { overrideHours = it },
                    "개별 지연 기준 (시간)",
                    supportingText = "비우면 보드 또는 전역 기본값을 사용합니다. 마감일이 있으면 마감일이 우선합니다.",
                    enabled =
                    reminderOverrideEnabled,
                    errorText = if (validHours) null else "1~87,600시간을 입력해 주세요.",
                )
                AxiaButton(
                    "지연 기준 저장",
                    { onReminderOverride(if (overrideHours.isBlank()) null else parsedHours) },
                    enabled =
                        reminderOverrideEnabled && validHours,
                    style = AxiaButtonStyle.Secondary,
                )
            }
            if (detail.canChangeReminderSuppression) {
                AxiaCheckbox(
                    "이 태스크의 자동 리마인드 제외",
                    detail.remindersSuppressed,
                    onReminderSuppression,
                    enabled =
                    reminderSuppressionEnabled,
                    description = "일반 담당 변경 알림은 유지됩니다. 닫힌 뒤 다시 열린 태스크는 제외가 초기화됩니다.",
                )
            }
        }
    }
}
