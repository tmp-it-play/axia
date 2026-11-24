package team.themoment.axia.update

import java.nio.file.Path

enum class InstallerPlatform(
    val packageSuffix: String,
) {
    WINDOWS_X64(".msi"),
    MACOS_ARM64("-macos-arm64.dmg"),
    MACOS_X64("-macos-x64.dmg"),
}

/** jpackage로 설치한 앱의 버전, 런처 및 실행 중인 배포판의 아키텍처. */
class Installation(
    val version: AppVersion,
    val launcher: Path,
    val platform: InstallerPlatform,
) {
    companion object {
        fun current(): Installation? {
            val launcher = Path.of(System.getProperty("jpackage.app-path") ?: return null)
            val os = System.getProperty("os.name")
            val isMac = os.startsWith("Mac", ignoreCase = true)
            val path = if (isMac) launcher.toRealPath() else launcher
            val platform =
                when {
                    os.startsWith("Windows") -> InstallerPlatform.WINDOWS_X64
                    isMac -> macPlatform(path)
                    else -> null
                } ?: return null
            val versionText = checkNotNull(System.getProperty("jpackage.app-version")) { "jpackage 런처가 앱 버전을 전달하지 않았습니다" }
            val version = checkNotNull(AppVersion.parse(versionText)) { "앱 버전 형식이 올바르지 않습니다: $versionText" }
            return Installation(version, path, platform)
        }

        private fun macPlatform(launcher: Path): InstallerPlatform? {
            if (!launcher.endsWith("Contents/MacOS/Axia")) return null
            val bundle = launcher.parent.parent.parent
            val bundleName = bundle?.fileName ?: return null
            if (!bundleName.toString().endsWith(".app")) return null
            // Rosetta에서 실행 중인 Intel 배포판도 같은 아키텍처의 런타임으로 업데이트한다.
            return when (System.getProperty("os.arch")) {
                "aarch64", "arm64" -> InstallerPlatform.MACOS_ARM64
                "x86_64", "amd64" -> InstallerPlatform.MACOS_X64
                else -> null
            }
        }
    }
}
