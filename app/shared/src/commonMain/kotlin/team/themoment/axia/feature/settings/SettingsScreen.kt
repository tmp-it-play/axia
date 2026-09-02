package team.themoment.axia.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
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
) {
    Column(
        modifier = modifier.fillMaxWidth(),
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
    }
}
