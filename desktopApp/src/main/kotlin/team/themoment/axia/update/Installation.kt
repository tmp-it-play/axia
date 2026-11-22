package team.themoment.axia.update

import java.nio.file.Path

/**
 * MSI로 설치되어 jpackage 런처로 실행 중인 Windows 앱.
 * 개발 실행(`run`)이나 다른 OS에서는 [current]가 null을 돌려주므로 자동 업데이트가 동작하지 않는다.
 */
class Installation(
    val version: AppVersion,
    val launcher: Path,
) {
    companion object {
        fun current(): Installation? {
            if (!System.getProperty("os.name").startsWith("Windows")) return null
            val launcher = System.getProperty("jpackage.app-path") ?: return null
            val versionText = checkNotNull(System.getProperty("jpackage.app-version")) { "jpackage 런처가 앱 버전을 전달하지 않았습니다" }
            val version = checkNotNull(AppVersion.parse(versionText)) { "앱 버전 형식이 올바르지 않습니다: $versionText" }
            return Installation(version, Path.of(launcher))
        }
    }
}
