package team.themoment.axia.desktop.updates

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicBoolean

internal class MacDesktopUpdater(
    configuration: UpdateConfiguration,
    onRestartRequested: () -> Unit,
) : DesktopUpdater {
    override val status = MutableStateFlow<DesktopUpdateStatus>(DesktopUpdateStatus.Ready)
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val statusCallback =
        StatusCallback { event ->
            if (!closed.get()) {
                status.value =
                    when (event) {
                        0 -> DesktopUpdateStatus.Ready
                        1 -> DesktopUpdateStatus.Checking
                        2 -> DesktopUpdateStatus.Downloading
                        3 -> DesktopUpdateStatus.UpdateReady
                        6 -> DesktopUpdateStatus.Installing
                        7 -> DesktopUpdateStatus.NoUpdate
                        5 -> DesktopUpdateStatus.Unavailable("설치할 수 있는 서명된 업데이트가 없습니다.")
                        else -> DesktopUpdateStatus.Error("네트워크 또는 업데이트 서명을 확인하지 못했습니다.")
                    }
            }
        }
    private val restartCallback =
        RestartCallback {
            if (!closed.get()) onRestartRequested()
        }
    private val library = Native.load(configuration.binary("libaxia_sparkle.dylib").toString(), SparkleLibrary::class.java)

    init {
        check(
            library.axia_sparkle_initialize(
                configuration.publicKey,
                requireNotNull(configuration.appleTeamId),
                statusCallback,
                restartCallback,
            ) ==
                1,
        )
    }

    override fun start() {
        if (!closed.get() && started.compareAndSet(false, true)) library.axia_sparkle_start()
    }

    override fun checkForUpdates() {
        if (!closed.get()) {
            start()
            library.axia_sparkle_check()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) library.axia_sparkle_close()
    }
}

internal fun interface StatusCallback : Callback {
    fun invoke(event: Int)
}

internal fun interface RestartCallback : Callback {
    fun invoke()
}

@Suppress("ktlint:standard:function-naming") // Exact exported C ABI names.
internal interface SparkleLibrary : Library {
    fun axia_sparkle_initialize(
        key: String,
        team: String,
        status: StatusCallback,
        restart: RestartCallback,
    ): Int

    fun axia_sparkle_start()

    fun axia_sparkle_check()

    fun axia_sparkle_close()
}
