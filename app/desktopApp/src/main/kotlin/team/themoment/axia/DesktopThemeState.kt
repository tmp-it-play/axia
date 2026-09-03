package team.themoment.axia

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import team.themoment.axia.designsystem.component.AxiaBanner
import team.themoment.axia.designsystem.component.AxiaBannerStyle
import team.themoment.axia.designsystem.theme.AxiaTheme
import team.themoment.axia.designsystem.theme.AxiaThemeMode
import java.util.prefs.Preferences

private const val THEME_MODE_PREFERENCE_KEY = "theme-mode"

internal object DesktopThemePreferences {
    fun loadThemeMode(): AxiaThemeMode {
        val node = preferenceNode
        node.sync()
        val value = node.get(THEME_MODE_PREFERENCE_KEY, null)
        if (value == null) {
            // Preferences.get silently returns its default when the backing store read fails.
            check(THEME_MODE_PREFERENCE_KEY !in node.keys()) { "Stored theme preference could not be read" }
            return AxiaThemeMode.System
        }
        return when (value) {
            "system" -> AxiaThemeMode.System
            "light" -> AxiaThemeMode.Light
            "dark" -> AxiaThemeMode.Dark
            else -> error("Stored theme preference is invalid")
        }
    }

    fun saveThemeMode(mode: AxiaThemeMode) {
        val value =
            when (mode) {
                AxiaThemeMode.System -> "system"
                AxiaThemeMode.Light -> "light"
                AxiaThemeMode.Dark -> "dark"
            }
        val node = preferenceNode
        node.put(THEME_MODE_PREFERENCE_KEY, value)
        node.flush()
    }

    private val preferenceNode: Preferences
        get() = Preferences.userNodeForPackage(DesktopThemePreferences::class.java)
}

internal enum class DesktopThemeFailure(
    val title: String,
    val description: String,
    val action: String,
) {
    Load(
        "테마 설정을 읽지 못했어요",
        "시스템 테마를 임시로 적용합니다. 기기 설정 저장소의 접근 권한을 확인한 뒤 다시 불러오세요.",
        "다시 불러오기",
    ),
    Save(
        "테마 설정을 저장하지 못했어요",
        "선택한 테마는 적용됐지만 기기 저장은 확인되지 않았습니다. 설정 저장소의 접근 권한을 확인한 뒤 다시 저장해 주세요.",
        "다시 저장",
    ),
}

internal class DesktopThemeState {
    var mode by mutableStateOf(AxiaThemeMode.System)
        private set

    var failure by mutableStateOf<DesktopThemeFailure?>(null)
        private set

    init {
        reload()
    }

    fun select(mode: AxiaThemeMode) {
        this.mode = mode
        failure =
            try {
                DesktopThemePreferences.saveThemeMode(mode)
                null
            } catch (_: Exception) {
                DesktopThemeFailure.Save
            }
    }

    fun retry() {
        when (failure) {
            DesktopThemeFailure.Load -> reload()
            DesktopThemeFailure.Save -> select(mode)
            null -> Unit
        }
    }

    private fun reload() {
        try {
            mode = DesktopThemePreferences.loadThemeMode()
            failure = null
        } catch (_: Exception) {
            failure = DesktopThemeFailure.Load
        }
    }
}

@Composable
internal fun DesktopThemeFailureNotice(state: DesktopThemeState) {
    val failure = state.failure ?: return
    AxiaTheme(state.mode) {
        AxiaBanner(
            title = failure.title,
            description = failure.description,
            style = AxiaBannerStyle.Warning,
            actionLabel = failure.action,
            onAction = state::retry,
            announceChanges = true,
            modifier = Modifier.padding(AxiaTheme.spacing.space3),
        )
    }
}
