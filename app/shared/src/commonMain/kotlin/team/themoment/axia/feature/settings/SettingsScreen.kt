package team.themoment.axia.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import team.themoment.axia.designsystem.component.AxiaButton
import team.themoment.axia.designsystem.component.AxiaButtonStyle
import team.themoment.axia.designsystem.component.AxiaChoiceGroup
import team.themoment.axia.designsystem.component.AxiaChoiceOption
import team.themoment.axia.designsystem.component.AxiaIconKind
import team.themoment.axia.designsystem.component.AxiaPanel
import team.themoment.axia.designsystem.theme.AxiaTheme
import team.themoment.axia.designsystem.theme.AxiaThemeMode

private val themeOptions =
    listOf(
        AxiaChoiceOption(
            key = AxiaThemeMode.System,
            label = "시스템 설정 따름",
            description = "운영체제의 라이트·다크 설정에 맞춥니다.",
            icon = AxiaIconKind.SystemTheme,
        ),
        AxiaChoiceOption(
            key = AxiaThemeMode.Light,
            label = "라이트",
            description = "밝은 작업 원장을 사용합니다.",
            icon = AxiaIconKind.LightTheme,
        ),
        AxiaChoiceOption(
            key = AxiaThemeMode.Dark,
            label = "다크",
            description = "어두운 작업 원장을 사용합니다.",
            icon = AxiaIconKind.DarkTheme,
        ),
    )

@Composable
fun SettingsScreen(
    themeMode: AxiaThemeMode,
    onThemeModeChange: (AxiaThemeMode) -> Unit,
    modifier: Modifier = Modifier,
    onManageSessions: (() -> Unit)? = null,
    onLogout: (() -> Unit)? = null,
    onCheckUpdates: (() -> Unit)? = null,
    updateStatusMessage: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space6),
    ) {
        AxiaPanel(modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space5)) {
                Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1)) {
                    Text(
                        text = "화면 테마",
                        modifier = Modifier.clearAndSetSemantics {},
                        color = AxiaTheme.semanticColors.textPrimary,
                        style = AxiaTheme.typography.sectionTitle,
                    )
                    Text(
                        text = "선택한 테마는 이 기기에 저장됩니다.",
                        color = AxiaTheme.semanticColors.textSecondary,
                        style = AxiaTheme.typography.secondary,
                    )
                }
                AxiaChoiceGroup(
                    groupLabel = "화면 테마",
                    options = themeOptions,
                    selectedKey = themeMode,
                    onSelectionChange = onThemeModeChange,
                )
            }
        }
        if (onManageSessions != null || onLogout != null) {
            AxiaPanel(Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3)) {
                    Text("로그인과 기기", style = AxiaTheme.typography.sectionTitle, color = AxiaTheme.semanticColors.textPrimary)
                    Text(
                        "기기별 240일 세션입니다. 사용하지 않는 기기의 로그인은 여기서 종료할 수 있습니다.",
                        style = AxiaTheme.typography.secondary,
                        color = AxiaTheme.semanticColors.textSecondary,
                    )
                    onManageSessions?.let { AxiaButton("기기 세션 관리", it, style = AxiaButtonStyle.Secondary) }
                    onLogout?.let { AxiaButton("이 기기에서 로그아웃", it, style = AxiaButtonStyle.Danger) }
                }
            }
        }
        onCheckUpdates?.let {
            AxiaPanel(Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3)) {
                    Text("앱 업데이트", style = AxiaTheme.typography.sectionTitle, color = AxiaTheme.semanticColors.textPrimary)
                    Text(
                        "시작할 때 자동으로 확인하며 다운로드 후 다시 시작할 시점을 선택할 수 있습니다.",
                        style = AxiaTheme.typography.secondary,
                        color = AxiaTheme.semanticColors.textSecondary,
                    )
                    updateStatusMessage?.let { message ->
                        Text(message, style = AxiaTheme.typography.body, color = AxiaTheme.semanticColors.textPrimary)
                    }
                    AxiaButton("업데이트 확인", it, style = AxiaButtonStyle.Secondary)
                }
            }
        }
    }
}
