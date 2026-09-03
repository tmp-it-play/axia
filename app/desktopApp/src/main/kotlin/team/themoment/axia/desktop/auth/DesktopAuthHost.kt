package team.themoment.axia.desktop.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import team.themoment.axia.designsystem.theme.AxiaTheme
import team.themoment.axia.designsystem.theme.AxiaThemeMode
import team.themoment.axia.feature.auth.AuthScreen

/** The application shell is not composed until a usable server session exists. */
@Composable
fun DesktopAuthHost(
    controller: DesktopAuthController,
    themeMode: AxiaThemeMode,
    content: @Composable (DesktopAuthState.SignedIn) -> Unit,
) {
    val state by controller.state.collectAsState()
    when (val current = state) {
        is DesktopAuthState.SignedIn -> {
            content(current)
        }

        is DesktopAuthState.Gated -> {
            AxiaTheme(themeMode) {
                AuthScreen(
                    state = current.screen,
                    onLogin = controller::login,
                    onReopenBrowser = controller::reopenBrowser,
                    onCancel = controller::cancelLogin,
                    onRetry = controller::retry,
                    onOpenDataGsmMyInfo = controller::openDataGsmMyInfo,
                )
            }
        }
    }
}
