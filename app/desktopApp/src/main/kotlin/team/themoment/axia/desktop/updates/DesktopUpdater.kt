package team.themoment.axia.desktop.updates

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Properties
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

sealed interface DesktopUpdateStatus {
    data class Unavailable(
        val reason: String,
    ) : DesktopUpdateStatus

    data object Ready : DesktopUpdateStatus

    data object Checking : DesktopUpdateStatus

    data object Downloading : DesktopUpdateStatus

    data object UpdateReady : DesktopUpdateStatus

    data object Installing : DesktopUpdateStatus

    data object NoUpdate : DesktopUpdateStatus

    data class Error(
        val reason: String,
    ) : DesktopUpdateStatus
}

interface DesktopUpdater : AutoCloseable {
    val status: StateFlow<DesktopUpdateStatus>

    fun start()

    fun checkForUpdates()

    companion object {
        fun create(onRestartRequested: () -> Unit): DesktopUpdater =
            try {
                val configuration = UpdateConfiguration.load()
                when (configuration.platform) {
                    "macos-arm64" -> MacDesktopUpdater(configuration, onRestartRequested)
                    "windows-x64" -> WindowsDesktopUpdater(configuration, onRestartRequested)
                    else -> unavailable()
                }
            } catch (_: Exception) {
                unavailable()
            } catch (_: LinkageError) {
                unavailable()
            }

        private fun unavailable(): DesktopUpdater =
            UnavailableDesktopUpdater(
                "서명된 공식 배포 앱과 업데이트 구성 요소가 필요합니다. 개발 실행 또는 지원되지 않는 플랫폼에서는 자동 업데이트를 사용할 수 없습니다.",
            )
    }
}

internal data class UpdateConfiguration(
    val platform: String,
    val version: String,
    val publicKey: String,
    val feedUrl: String,
    val directory: Path,
    val appleTeamId: String?,
) {
    fun binary(name: String): Path {
        val binary = directory.resolve(name).toRealPath()
        require(binary.parent == directory && Files.isRegularFile(binary))
        return binary
    }

    companion object {
        fun load(): UpdateConfiguration {
            val os = System.getProperty("os.name").lowercase()
            val architecture = System.getProperty("os.arch").lowercase()
            val platform =
                when {
                    os.startsWith("mac") && architecture in setOf("aarch64", "arm64") -> "macos-arm64"
                    os.startsWith("windows") && architecture in setOf("amd64", "x86_64") -> "windows-x64"
                    else -> error("Unsupported updater architecture")
                }
            val resourceRoot = Path.of(requireNotNull(System.getProperty("compose.application.resources.dir"))).toRealPath()
            val directory = resourceRoot.resolve("updates").toRealPath()
            require(directory.parent == resourceRoot)
            val properties =
                Properties().apply {
                    Files.newInputStream(directory.resolve("updater.properties")).use(::load)
                }
            require(properties.getProperty("platform") == platform)
            val version = requireNotNull(properties.getProperty("version"))
            require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(version))
            val parts = version.split('.').map(String::toInt)
            require(parts[0] <= 255 && parts[1] <= 255 && parts[2] <= 65535 && parts.any { it != 0 })
            val key = requireNotNull(properties.getProperty("publicKey"))
            val decoded = Base64.getDecoder().decode(key)
            require(decoded.size == 32 && Base64.getEncoder().encodeToString(decoded) == key)
            val feed = "https://kimtaeeun.site/axia/updates/${platform.replace('-', '/')}/appcast.xml"
            require(properties.getProperty("feedUrl") == feed)
            val team = properties.getProperty("appleTeamId")
            if (platform == "macos-arm64") require(team != null && Regex("[A-Z0-9]{10}").matches(team))
            return UpdateConfiguration(platform, version, key, feed, directory, team)
        }
    }
}

private class UnavailableDesktopUpdater(
    reason: String,
) : DesktopUpdater {
    override val status = MutableStateFlow<DesktopUpdateStatus>(DesktopUpdateStatus.Unavailable(reason))

    override fun start() = Unit

    override fun checkForUpdates() {
        showUpdateMessage((status.value as DesktopUpdateStatus.Unavailable).reason)
    }

    override fun close() = Unit
}

internal fun showUpdateMessage(message: String) {
    SwingUtilities.invokeLater {
        JOptionPane.showMessageDialog(null, message, "Axia 업데이트", JOptionPane.INFORMATION_MESSAGE)
    }
}
