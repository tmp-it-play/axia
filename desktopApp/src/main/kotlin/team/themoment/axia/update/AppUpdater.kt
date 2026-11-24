package team.themoment.axia.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText

sealed interface UpdateState {
    data object None : UpdateState

    data class Available(
        val installer: ReleaseInstaller,
    ) : UpdateState

    data class Downloading(
        val installer: ReleaseInstaller,
        val progress: Float,
    ) : UpdateState

    data class Failed(
        val installer: ReleaseInstaller,
        val reason: String,
    ) : UpdateState

    data object Installing : UpdateState
}

/** 설치 환경에 맞는 공개 릴리스를 확인하고, 사용자가 선택하면 저장 후 설치 도우미에 넘긴다. */
class AppUpdater(
    private val installation: Installation,
    private val scope: CoroutineScope,
) {
    private val releases = GitHubReleases(userAgent = "Axia/${installation.version}")
    private val workDir =
        Path
            .of(System.getProperty("java.io.tmpdir"), "axia-update")
            .resolve(UUID.nameUUIDFromBytes(installation.launcher.toString().toByteArray()).toString())
    private val failureMarker = workDir.resolve("failed")
    private val installLog = workDir.resolve("install.log")
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    /** 새 버전 확인은 사용자가 요청한 작업이 아니므로, 오프라인 등으로 실패하면 기록만 남기고 알리지 않는다. */
    fun checkForUpdate() {
        scope.launch(Dispatchers.IO) {
            val installer =
                try {
                    releases.latestInstaller(installation.platform)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    System.err.println("Axia 업데이트 확인 실패: $e")
                    return@launch
                }
            if (installer == null || installer.version <= installation.version) return@launch
            val failure = consumeFailureMarker()
            mutableState.value =
                if (failure == null) {
                    UpdateState.Available(installer)
                } else {
                    val reason = if (installation.platform == InstallerPlatform.WINDOWS_X64) "msiexec 종료 코드 $failure" else failure
                    UpdateState.Failed(installer, "$reason · 로그: $installLog")
                }
        }
    }

    fun dismiss() {
        mutableState.value = UpdateState.None
    }

    /** 설치 패키지를 내려받아 설치 프로세스를 띄운 뒤 [onInstallerLaunched]를 호출한다. 호출자는 즉시 앱을 종료해야 한다. */
    fun install(
        installer: ReleaseInstaller,
        saveBeforeRestart: suspend () -> Unit,
        onInstallerLaunched: () -> Unit,
    ) {
        scope.launch {
            if (mutableState.value !is UpdateState.Available && mutableState.value !is UpdateState.Failed) return@launch
            mutableState.value = UpdateState.Downloading(installer, progress = 0f)
            try {
                withContext(Dispatchers.IO) {
                    downloadAndLaunch(installer, saveBeforeRestart)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = UpdateState.Failed(installer, e.message ?: e.toString())
                return@launch
            }
            onInstallerLaunched()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private suspend fun downloadAndLaunch(
        installer: ReleaseInstaller,
        saveBeforeRestart: suspend () -> Unit,
    ) {
        Files.createDirectories(workDir)
        val attempt = Files.createTempDirectory(workDir, "download-")
        var handedOff = false
        try {
            val file = attempt.resolve(installer.fileName)
            val context = currentCoroutineContext()
            releases.download(installer, file) { progress ->
                context.ensureActive()
                mutableState.value = UpdateState.Downloading(installer, progress)
            }
            withContext(Dispatchers.Main) {
                mutableState.value = UpdateState.Installing
                saveBeforeRestart()
            }
            when (installation.platform) {
                InstallerPlatform.WINDOWS_X64 -> {
                    launchWindowsInstaller(file)
                }

                InstallerPlatform.MACOS_ARM64, InstallerPlatform.MACOS_X64 -> {
                    MacInstaller.launch(installation, file, installer.version, failureMarker, installLog)
                }
            }
            handedOff = true
        } finally {
            if (!handedOff) attempt.deleteRecursively()
        }
    }

    private fun consumeFailureMarker(): String? =
        try {
            val failure = failureMarker.readText().trim()
            Files.delete(failureMarker)
            failure
        } catch (_: NoSuchFileException) {
            null
        }

    private fun launchWindowsInstaller(msi: Path) {
        val msiexecArguments = "/i \"$msi\" /qn /norestart /l*v \"$installLog\""
        val script =
            """
            Wait-Process -Id ${ProcessHandle.current().pid()} -ErrorAction SilentlyContinue
            ${'$'}msiexec = [System.Diagnostics.Process]::Start('msiexec.exe', ${powerShellLiteral(msiexecArguments)})
            ${'$'}msiexec.WaitForExit()
            if (${'$'}msiexec.ExitCode -ne 0 -and ${'$'}msiexec.ExitCode -ne 3010) {
                Set-Content -LiteralPath ${powerShellLiteral(failureMarker.toString())} -Value ${'$'}msiexec.ExitCode
            }
            Remove-Item -LiteralPath ${powerShellLiteral(msi.parent.toString())} -Recurse -Force
            Start-Process -FilePath ${powerShellLiteral(installation.launcher.toString())}
            """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val process =
            ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        process.outputStream.close()
    }
}

/** PowerShell 작은따옴표 문자열. PowerShell은 유니코드 작은따옴표도 구분자로 취급하므로 함께 이스케이프한다. */
private fun powerShellLiteral(value: String): String = "'" + value.replace(Regex("['‘’‚‛]"), "$0$0") + "'"
