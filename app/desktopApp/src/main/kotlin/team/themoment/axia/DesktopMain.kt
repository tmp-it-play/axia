package team.themoment.axia

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import team.themoment.axia.designsystem.foundation.defaultAxiaDimensions
import team.themoment.axia.designsystem.theme.AxiaThemeMode
import java.awt.Dimension
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences
import kotlin.math.roundToInt

private const val THEME_MODE_PREFERENCE_KEY = "theme-mode"
private const val SYSTEM_THEME_PREFERENCE = "system"
private const val LIGHT_THEME_PREFERENCE = "light"
private const val DARK_THEME_PREFERENCE = "dark"

fun main() =
    application {
        var themeMode by remember { mutableStateOf(DesktopThemePreferences.loadThemeMode()) }

        Window(
            onCloseRequest = ::exitApplication,
            state =
                rememberWindowState(
                    width = defaultAxiaDimensions.defaultWindowWidth,
                    height = defaultAxiaDimensions.defaultWindowHeight,
                ),
            title = "Axia",
        ) {
            LaunchedEffect(window) {
                window.minimumSize =
                    Dimension(
                        defaultAxiaDimensions.minimumWindowWidth.value.roundToInt(),
                        defaultAxiaDimensions.minimumWindowHeight.value.roundToInt(),
                    )
            }

            App(
                themeMode = themeMode,
                onThemeModeChange = { selectedThemeMode ->
                    themeMode = selectedThemeMode
                    DesktopThemePreferences.saveThemeMode(selectedThemeMode)
                },
            )
        }
    }

private object DesktopThemePreferences {
    fun loadThemeMode(): AxiaThemeMode =
        try {
            when (preferenceNode.get(THEME_MODE_PREFERENCE_KEY, SYSTEM_THEME_PREFERENCE)) {
                LIGHT_THEME_PREFERENCE -> AxiaThemeMode.Light
                DARK_THEME_PREFERENCE -> AxiaThemeMode.Dark
                else -> AxiaThemeMode.System
            }
        } catch (_: SecurityException) {
            AxiaThemeMode.System
        } catch (_: IllegalStateException) {
            AxiaThemeMode.System
        }

    fun saveThemeMode(themeMode: AxiaThemeMode) {
        val preferenceValue =
            when (themeMode) {
                AxiaThemeMode.System -> SYSTEM_THEME_PREFERENCE
                AxiaThemeMode.Light -> LIGHT_THEME_PREFERENCE
                AxiaThemeMode.Dark -> DARK_THEME_PREFERENCE
            }

        try {
            preferenceNode.put(THEME_MODE_PREFERENCE_KEY, preferenceValue)
            preferenceNode.flush()
        } catch (_: SecurityException) {
            // Keep the in-memory selection when preferences are unavailable.
        } catch (_: IllegalStateException) {
            // Keep the in-memory selection when preferences are unavailable.
        } catch (_: BackingStoreException) {
            // Keep the in-memory selection when the backing store cannot be flushed.
        }
    }

    private val preferenceNode: Preferences
        get() = Preferences.userNodeForPackage(DesktopThemePreferences::class.java)
}
