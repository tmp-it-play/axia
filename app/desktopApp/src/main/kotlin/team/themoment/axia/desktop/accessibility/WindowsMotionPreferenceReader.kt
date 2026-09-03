@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package team.themoment.axia.desktop.accessibility

import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary

internal class WindowsMotionPreferenceReader(
    private val api: WindowsMotionApi = Native.load("User32", WindowsMotionApi::class.java),
) : DesktopMotionPreferenceReader {
    override fun readReducedMotion(): Boolean =
        nativeMotionCall("read Windows client-area animation") {
            // Win32 BOOL is 32 bits. GET + zero flags cannot update the user's settings.
            val animationEnabled = IntByReference()
            if (api.SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, animationEnabled, 0) == 0) {
                throw DesktopMotionPreferenceException("read Windows client-area animation", Native.getLastError())
            }
            animationEnabled.value == 0
        }

    private companion object {
        const val SPI_GETCLIENTAREAANIMATION = 0x1042
    }
}

internal interface WindowsMotionApi : StdCallLibrary {
    fun SystemParametersInfoW(
        action: Int,
        parameter: Int,
        value: IntByReference,
        flags: Int,
    ): Int
}
