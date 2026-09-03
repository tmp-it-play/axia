package team.themoment.axia

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import team.themoment.axia.app.AxiaAppShell
import team.themoment.axia.app.WorkspaceApp
import team.themoment.axia.app.WorkspaceGateway
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
    gateway: WorkspaceGateway? = null,
    onManageSessions: () -> Unit = {},
    onLogout: () -> Unit = {},
    onCheckUpdates: () -> Unit = {},
    updateStatusMessage: String? = null,
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
        if (gateway != null) {
            WorkspaceApp(
                gateway = gateway,
                themeMode = activeThemeMode,
                onThemeModeChange = { selected ->
                    activeThemeMode = selected
                    onThemeModeChange(selected)
                },
                onManageSessions = onManageSessions,
                onLogout = onLogout,
                onCheckUpdates = onCheckUpdates,
                updateStatusMessage = updateStatusMessage,
                initialDestination = initialDestination,
            )
            return@AxiaTheme
        }
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
