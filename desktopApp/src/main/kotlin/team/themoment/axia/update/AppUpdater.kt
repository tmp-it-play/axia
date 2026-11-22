package team.themoment.axia.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
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
}

/**
 * 최신 공개 릴리즈를 확인하고, 사용자가 동의하면 MSI를 내려받아 화면 없이 설치한다.
 *
 * 실행 중인 앱의 파일은 교체할 수 없으므로, 창 없는 PowerShell 프로세스가 이 앱의 종료를 기다렸다가
 * `msiexec /qn`으로 설치(같은 upgradeUuid의 이전 버전은 MSI가 제거)하고 앱을 다시 실행한다.
 * 설치에 실패하면 [workDir]에 종료 코드와 로그를 남기고, 다시 실행된 앱이 이를 알린다.
 */
class AppUpdater(
    private val installation: Installation,
    private val scope: CoroutineScope,
) {
    private val releases = GitHubReleases(userAgent = "Axia/${installation.version}")
    private val workDir = Path.of(System.getProperty("java.io.tmpdir"), "axia-update")
    private val failureMarker = workDir.resolve("failed")
    private val installLog = workDir.resolve("install.log")
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    /** 새 버전 확인은 사용자가 요청한 작업이 아니므로, 오프라인 등으로 실패하면 기록만 남기고 알리지 않는다. */
    fun checkForUpdate() {
        scope.launch(Dispatchers.IO) {
            val installer =
                try {
                    releases.latestInstaller()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    System.err.println("Axia 업데이트 확인 실패: $e")
                    return@launch
                }
            if (installer == null || installer.version <= installation.version) return@launch
            val previousExitCode = consumeFailureMarker()
            mutableState.value =
                if (previousExitCode == null) {
                    UpdateState.Available(installer)
                } else {
                    UpdateState.Failed(installer, "설치에 실패했습니다 (msiexec 종료 코드 $previousExitCode). 로그: $installLog")
                }
        }
    }

    fun dismiss() {
        mutableState.value = UpdateState.None
    }

    /** 설치 패키지를 내려받아 설치 프로세스를 띄운 뒤 [onInstallerLaunched]를 호출한다. 호출자는 즉시 앱을 종료해야 한다. */
    @OptIn(ExperimentalPathApi::class)
    fun install(
        installer: ReleaseInstaller,
        onInstallerLaunched: () -> Unit,
    ) {
        scope.launch {
            mutableState.value = UpdateState.Downloading(installer, progress = 0f)
            try {
                withContext(Dispatchers.IO) {
                    workDir.deleteRecursively()
                    Files.createDirectories(workDir)
                    val msi = workDir.resolve(installer.fileName)
                    releases.download(installer, msi) { progress ->
                        mutableState.value = UpdateState.Downloading(installer, progress)
                    }
                    launchInstaller(msi)
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

    private fun consumeFailureMarker(): String? {
        if (!Files.exists(failureMarker)) return null
        val exitCode = failureMarker.readText().trim()
        Files.delete(failureMarker)
        return exitCode
    }

    private fun launchInstaller(msi: Path) {
        val msiexecArguments = "/i \"$msi\" /qn /norestart /l*v \"$installLog\""
        val script =
            """
            Wait-Process -Id ${ProcessHandle.current().pid()} -ErrorAction SilentlyContinue
            ${'$'}msiexec = [System.Diagnostics.Process]::Start('msiexec.exe', ${powerShellLiteral(msiexecArguments)})
            ${'$'}msiexec.WaitForExit()
            if (${'$'}msiexec.ExitCode -eq 0 -or ${'$'}msiexec.ExitCode -eq 3010) {
                Remove-Item -LiteralPath ${powerShellLiteral(workDir.toString())} -Recurse -Force
            } else {
                Set-Content -LiteralPath ${powerShellLiteral(failureMarker.toString())} -Value ${'$'}msiexec.ExitCode
            }
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
