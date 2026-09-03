package team.themoment.axia.desktop

import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import team.themoment.axia.desktop.accessibility.DesktopMotionPreferenceReader
import team.themoment.axia.desktop.accessibility.DesktopMotionPreferences

internal sealed interface DesktopMotionPreferenceState {
    val reduceMotion: Boolean

    data object Loading : DesktopMotionPreferenceState {
        override val reduceMotion = true
    }

    data class Available(
        override val reduceMotion: Boolean,
    ) : DesktopMotionPreferenceState

    data object Unavailable : DesktopMotionPreferenceState {
        override val reduceMotion = true
    }
}

/** One application-wide monitor and animation clock, including separate windows and dialogs. */
internal class DesktopMotionState(
    private val createReader: () -> DesktopMotionPreferenceReader = DesktopMotionPreferences::createReader,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MotionDurationScale {
    private val mutableState = MutableStateFlow<DesktopMotionPreferenceState>(DesktopMotionPreferenceState.Loading)
    val state = mutableState.asStateFlow()

    override val scaleFactor: Float
        get() = if (state.value.reduceMotion) 0f else 1f

    suspend fun observe() {
        var reader: DesktopMotionPreferenceReader? = null
        while (currentCoroutineContext().isActive) {
            mutableState.value =
                try {
                    val reduced =
                        withContext(dispatcher) {
                            val current = reader ?: createReader().also { reader = it }
                            current.readReducedMotion()
                        }
                    DesktopMotionPreferenceState.Available(reduced)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    reader = null
                    DesktopMotionPreferenceState.Unavailable
                } catch (_: LinkageError) {
                    reader = null
                    DesktopMotionPreferenceState.Unavailable
                }
            // Read only: changes in System Settings apply while the app is running, even hidden.
            delay(2_000)
        }
    }
}
