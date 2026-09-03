package team.themoment.axia

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.WindowState

internal class DesktopWindowState(
    val nativeState: WindowState,
) {
    var visible by mutableStateOf(true)
        private set

    var activationRequest by mutableLongStateOf(0L)
        private set

    fun show() {
        nativeState.isMinimized = false
        visible = true
        // Opening an already visible window must still request native activation.
        activationRequest++
    }

    fun hide() {
        visible = false
    }
}
