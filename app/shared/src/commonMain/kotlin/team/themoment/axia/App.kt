package team.themoment.axia

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import team.themoment.axia.app.AxiaAppShell
import team.themoment.axia.app.rememberAxiaAppState
import team.themoment.axia.designsystem.theme.AxiaTheme
import team.themoment.axia.designsystem.theme.AxiaThemeMode
import team.themoment.axia.feature.dashboard.DashboardUiState
import team.themoment.axia.navigation.AxiaAccessFlags
import team.themoment.axia.navigation.AxiaDestination

@Composable
@Preview
fun App(
    themeMode: AxiaThemeMode = AxiaThemeMode.System,
    onThemeModeChange: (AxiaThemeMode) -> Unit = {},
    accessFlags: AxiaAccessFlags = AxiaAccessFlags(),
    dashboardState: DashboardUiState = DashboardUiState(),
    initialDestination: AxiaDestination = AxiaDestination.Dashboard,
) {
    var activeThemeMode by remember { mutableStateOf(themeMode) }
    LaunchedEffect(themeMode) {
        activeThemeMode = themeMode
    }
    val appState =
        rememberAxiaAppState(
            accessFlags = accessFlags,
            initialDestination = initialDestination,
        )

    AxiaTheme(themeMode = activeThemeMode) {
        AxiaAppShell(
            appState = appState,
            dashboardState = dashboardState,
            themeMode = activeThemeMode,
            onThemeModeChange = { selectedThemeMode ->
                activeThemeMode = selectedThemeMode
                onThemeModeChange(selectedThemeMode)
            },
        )
    }
}
