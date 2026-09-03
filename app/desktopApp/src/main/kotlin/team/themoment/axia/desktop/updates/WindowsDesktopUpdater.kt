package team.themoment.axia.desktop.updates

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.WString
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JDialog
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

internal class WindowsDesktopUpdater(
    private val configuration: UpdateConfiguration,
    onRestartRequested: () -> Unit,
) : DesktopUpdater {
    override val status = MutableStateFlow<DesktopUpdateStatus>(DesktopUpdateStatus.Ready)
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val checking = AtomicBoolean(false)
    private val restartApproved = AtomicBoolean(false)
    private val confirmationDialog = AtomicReference<JDialog?>(null)
    private val library = Native.load(configuration.binary("WinSparkle.dll").toString(), WinSparkleLibrary::class.java)
    private val errorCallback =
        WinCallback {
            checking.set(false)
            if (!closed.get()) status.value = DesktopUpdateStatus.Error("네트워크 또는 업데이트 서명을 확인하지 못했습니다.")
        }
    private val foundCallback = WinCallback { if (!closed.get()) status.value = DesktopUpdateStatus.Downloading }
    private val noUpdateCallback =
        WinCallback {
            checking.set(false)
            if (!closed.get()) status.value = DesktopUpdateStatus.NoUpdate
        }
    private val cancelCallback =
        WinCallback {
            checking.set(false)
            if (!closed.get()) status.value = DesktopUpdateStatus.Ready
        }
    private val canShutdown = WinBooleanCallback { if (closed.get()) 0 else 1 }
    private val shutdownCallback =
        WinCallback {
            if (restartApproved.get() && !closed.get()) onRestartRequested()
        }
    private val installerCallback =
        WinInstallerCallback { file ->
            try {
                if (closed.get()) {
                    1
                } else {
                    // WinSparkle calls this only AFTER its mandatory Ed25519 verification succeeds.
                    status.value = DesktopUpdateStatus.UpdateReady
                    val choice = confirmRestart()
                    if (choice && !closed.get()) {
                        launchVerifiedInstaller(Path.of(file.toString()))
                        restartApproved.set(true)
                        status.value = DesktopUpdateStatus.Installing
                    } else {
                        checking.set(false)
                        status.value = DesktopUpdateStatus.Ready
                    }
                    // Handled even on Later: no default installer launch; shutdown callback is gated above.
                    1
                }
            } catch (_: Exception) {
                restartApproved.set(false)
                checking.set(false)
                status.value = DesktopUpdateStatus.Error("검증된 업데이트 설치를 시작하지 못했습니다.")
                -1
            }
        }

    init {
        configuration.binary("AxiaUpdateRunner.exe")
        require(packagedLauncher().fileName.toString().equals("Axia.exe", ignoreCase = true))
        check(library.win_sparkle_set_eddsa_public_key(configuration.publicKey) == 1)
        library.win_sparkle_set_appcast_url(configuration.feedUrl)
        library.win_sparkle_set_app_details(WString("The Moment"), WString("Axia"), WString(configuration.version))
        library.win_sparkle_set_app_build_version(WString(configuration.version))
        library.win_sparkle_set_lang("ko")
        // Startup checks below also download; WinSparkle's separate automatic checker only prompts.
        library.win_sparkle_set_automatic_check_for_updates(0)
        library.win_sparkle_set_error_callback(errorCallback)
        library.win_sparkle_set_did_find_update_callback(foundCallback)
        library.win_sparkle_set_did_not_find_update_callback(noUpdateCallback)
        library.win_sparkle_set_update_cancelled_callback(cancelCallback)
        library.win_sparkle_set_can_shutdown_callback(canShutdown)
        library.win_sparkle_set_shutdown_request_callback(shutdownCallback)
        library.win_sparkle_set_user_run_installer_callback(installerCallback)
    }

    override fun start() {
        if (!closed.get() && started.compareAndSet(false, true)) {
            library.win_sparkle_init()
            checkForUpdates()
        }
    }

    override fun checkForUpdates() {
        if (closed.get()) return
        if (!started.get()) {
            start()
        } else if (checking.compareAndSet(false, true)) {
            status.value = DesktopUpdateStatus.Checking
            library.win_sparkle_check_update_with_ui_and_install()
        } else {
            showUpdateMessage("업데이트 확인 또는 다운로드가 진행 중입니다.")
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            SwingUtilities.invokeLater { confirmationDialog.getAndSet(null)?.dispose() }
            if (started.get()) {
                // cleanup joins WinSparkle's callback thread; never block the EDT that dismisses its prompt.
                Thread({ library.win_sparkle_cleanup() }, "axia-updater-cleanup").apply { isDaemon = true }.start()
            }
        }
    }

    private fun confirmRestart(): Boolean {
        var approved = false
        val prompt = {
            if (!closed.get()) {
                val choices = arrayOf("업데이트 후 재시작", "나중에")
                val pane =
                    JOptionPane(
                        "서명 확인을 마친 업데이트를 설치하고 Axia를 재시작할까요?\nWindows 게시자 경고가 표시될 수 있습니다.",
                        JOptionPane.INFORMATION_MESSAGE,
                        JOptionPane.DEFAULT_OPTION,
                        null,
                        choices,
                        choices[1],
                    )
                val dialog = pane.createDialog(null, "업데이트를 다운로드했어요")
                confirmationDialog.set(dialog)
                if (!closed.get()) dialog.isVisible = true
                approved = !closed.get() && pane.value == choices[0]
                confirmationDialog.compareAndSet(dialog, null)
                dialog.dispose()
            }
        }
        if (SwingUtilities.isEventDispatchThread()) prompt() else SwingUtilities.invokeAndWait(prompt)
        return approved
    }

    private fun packagedLauncher(): Path {
        // jpackage resources live at <installation>/app/resources/updates.
        val launcher =
            configuration.directory.parent.parent.parent
                .resolve("Axia.exe")
                .toRealPath()
        require(Files.isRegularFile(launcher))
        return launcher
    }

    private fun launchVerifiedInstaller(source: Path) {
        require(Files.isRegularFile(source) && source.fileName.toString().endsWith(".msi", ignoreCase = true))
        val originalDigest = digest(source)
        // Keep both executable and MSI outside the installation that the MSI replaces.
        val staging = Files.createTempDirectory("axia-verified-update-")
        val installer = Files.copy(source, staging.resolve("Axia.msi"))
        require(digest(installer) == originalDigest)
        val runner = Files.copy(configuration.binary("AxiaUpdateRunner.exe"), staging.resolve("AxiaUpdateRunner.exe"))
        val process =
            ProcessBuilder(
                runner.toString(),
                installer.toString(),
                originalDigest,
                ProcessHandle.current().pid().toString(),
                packagedLauncher().toString(),
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            // Do not exit until the runner has locked the verified payload and opened the parent handle.
            val ready = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readLine() }.get(15, TimeUnit.SECONDS)
            check(ready == "READY")
        } catch (failure: Exception) {
            process.destroyForcibly()
            throw failure
        }
    }
}

private fun digest(path: Path): String {
    val hash = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            hash.update(buffer, 0, size)
        }
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

internal fun interface WinCallback : Callback {
    fun invoke()
}

internal fun interface WinBooleanCallback : Callback {
    fun invoke(): Int
}

internal fun interface WinInstallerCallback : Callback {
    fun invoke(path: WString): Int
}

@Suppress("ktlint:standard:function-naming") // Exact exported C ABI names.
internal interface WinSparkleLibrary : Library {
    fun win_sparkle_init()

    fun win_sparkle_cleanup()

    fun win_sparkle_set_eddsa_public_key(key: String): Int

    fun win_sparkle_set_appcast_url(url: String)

    fun win_sparkle_set_app_details(
        company: WString,
        name: WString,
        version: WString,
    )

    fun win_sparkle_set_app_build_version(version: WString)

    fun win_sparkle_set_lang(language: String)

    fun win_sparkle_set_automatic_check_for_updates(enabled: Int)

    fun win_sparkle_set_error_callback(callback: WinCallback)

    fun win_sparkle_set_did_find_update_callback(callback: WinCallback)

    fun win_sparkle_set_did_not_find_update_callback(callback: WinCallback)

    fun win_sparkle_set_update_cancelled_callback(callback: WinCallback)

    fun win_sparkle_set_can_shutdown_callback(callback: WinBooleanCallback)

    fun win_sparkle_set_shutdown_request_callback(callback: WinCallback)

    fun win_sparkle_set_user_run_installer_callback(callback: WinInstallerCallback)

    fun win_sparkle_check_update_with_ui_and_install()
}
