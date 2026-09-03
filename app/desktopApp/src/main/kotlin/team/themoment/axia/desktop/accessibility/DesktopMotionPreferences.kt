package team.themoment.axia.desktop.accessibility

import java.util.Locale

/** Reads the current OS preference on every call; failures never imply that animation is safe. */
fun interface DesktopMotionPreferenceReader {
    fun readReducedMotion(): Boolean
}

object DesktopMotionPreferences {
    fun createReader(): DesktopMotionPreferenceReader = createReader(System.getProperty("os.name"), System.getProperty("os.arch"))

    internal fun createReader(
        osName: String,
        architecture: String,
    ): DesktopMotionPreferenceReader =
        nativeMotionCall("initialize") {
            val os = osName.lowercase(Locale.ROOT)
            val arch = architecture.lowercase(Locale.ROOT)
            when {
                os.startsWith("mac") && arch in setOf("aarch64", "arm64") -> MacMotionPreferenceReader()
                os.startsWith("windows") && arch in setOf("amd64", "x86_64") -> WindowsMotionPreferenceReader()
                else -> throw DesktopMotionPreferenceException("unsupported platform")
            }
        }
}

class DesktopMotionPreferenceException(
    operation: String,
    val nativeStatus: Int? = null,
    cause: Throwable? = null,
) : IllegalStateException("OS motion preference failed: $operation (status=${nativeStatus ?: "unavailable"})", cause)

internal inline fun <T> nativeMotionCall(
    operation: String,
    action: () -> T,
): T =
    try {
        action()
    } catch (exception: DesktopMotionPreferenceException) {
        throw exception
    } catch (exception: Exception) {
        throw DesktopMotionPreferenceException(operation, cause = exception)
    } catch (error: LinkageError) {
        throw DesktopMotionPreferenceException(operation, cause = error)
    }
