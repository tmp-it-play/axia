package team.themoment.axia.update

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

internal object MacInstaller {
    private const val BUNDLE_ID = "team.themoment.axia"

    /** DMG를 검증하고 같은 볼륨에 복사한 뒤 교체 도우미가 준비될 때까지 기다린다. */
    @OptIn(ExperimentalPathApi::class)
    fun launch(
        installation: Installation,
        dmg: Path,
        version: AppVersion,
        failureMarker: Path,
        installLog: Path,
    ) {
        val launcher = installation.launcher.toRealPath()
        val bundle = launcher.parent.parent.parent
        require(bundle.fileName.toString().endsWith(".app") && launcher == bundle.resolve("Contents/MacOS/Axia"))
        check(!Files.getFileStore(bundle).isReadOnly && bundle.none { it.toString() == "AppTranslocation" }) {
            "Axia.app을 응용 프로그램 폴더로 옮겨 실행한 뒤 다시 업데이트해 주세요."
        }
        val currentIdentity = command("/usr/bin/stat", "-f", "%d:%i", bundle.toString())
        val installedVersion = bundleValue(bundle, "CFBundleShortVersionString")
        check(bundleValue(bundle, "CFBundleIdentifier") == BUNDLE_ID && installedVersion == installation.version.toString()) {
            "실행 중인 앱과 설치된 버전이 달라졌습니다. 앱을 다시 실행해 주세요."
        }
        val stage =
            try {
                Files.createTempDirectory(bundle.parent, ".Axia-update-")
            } catch (e: java.io.IOException) {
                throw IllegalStateException("앱 설치 폴더에 쓸 수 없습니다. Axia.app을 홈 폴더의 Applications로 옮긴 뒤 다시 실행해 주세요.", e)
            }
        try {
            val replacement = stage.resolve("Axia.app")
            copyFromImage(dmg, replacement)
            verifyReplacement(replacement, version, installation.platform, bundle)

            val helper = dmg.parent.resolve("mac-replace.sh")
            checkNotNull(javaClass.getResourceAsStream("/update/mac-replace.sh")).use { Files.copy(it, helper) }
            val process =
                ProcessBuilder(
                    "/bin/sh",
                    helper.toString(),
                    ProcessHandle.current().pid().toString(),
                    bundle.toString(),
                    replacement.toString(),
                    currentIdentity,
                    failureMarker.toString(),
                    dmg.toString(),
                ).redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                    .redirectError(ProcessBuilder.Redirect.appendTo(installLog.toFile()))
                    .start()
            // 준비 신호를 받은 뒤에만 게임을 닫는다. 잠금·도우미 실행 실패는 현재 창에서 알린다.
            when (process.inputStream.bufferedReader().use { it.readLine() }) {
                "ready" -> Unit
                "busy" -> error("다른 Axia 창에서 업데이트를 진행하고 있습니다.")
                "changed" -> error("설치된 앱이 변경됐습니다. 다시 실행해 주세요.")
                else -> error("설치 도우미를 시작할 수 없습니다. 로그: $installLog")
            }
        } catch (e: Exception) {
            try {
                stage.deleteRecursively()
            } catch (cleanup: java.io.IOException) {
                e.addSuppressed(cleanup)
            }
            throw e
        }
    }

    private fun copyFromImage(
        dmg: Path,
        replacement: Path,
    ) {
        val mount = Files.createTempDirectory("axia-dmg-")
        try {
            command("/usr/bin/hdiutil", "attach", "-readonly", "-nobrowse", "-noautoopen", "-mountpoint", mount.toString(), dmg.toString())
            try {
                val source = mount.resolve("Axia.app")
                check(!Files.isSymbolicLink(source) && Files.isDirectory(source)) { "DMG에서 Axia.app을 찾을 수 없습니다." }
                command("/usr/bin/ditto", source.toString(), replacement.toString())
            } finally {
                try {
                    command("/usr/bin/hdiutil", "detach", mount.toString())
                } catch (_: IllegalStateException) {
                    command("/usr/bin/hdiutil", "detach", "-force", mount.toString())
                }
            }
        } finally {
            // 분리에 실패한 볼륨은 비어 있지 않다. 원래 hdiutil 오류를 보존하고 mount 하위는 건드리지 않는다.
            if (!mount.toFile().delete()) System.err.println("Axia 업데이트 마운트 폴더를 정리할 수 없습니다: $mount")
        }
    }

    private fun verifyReplacement(
        replacement: Path,
        version: AppVersion,
        platform: InstallerPlatform,
        installed: Path,
    ) {
        val matchesRelease =
            bundleValue(replacement, "CFBundleIdentifier") == BUNDLE_ID &&
                bundleValue(replacement, "CFBundlePackageType") == "APPL" &&
                bundleValue(replacement, "CFBundleExecutable") == "Axia" &&
                bundleValue(replacement, "CFBundleShortVersionString") == version.toString()
        check(matchesRelease) { "DMG의 앱 또는 버전이 릴리스 정보와 다릅니다." }
        val binary = replacement.resolve("Contents/MacOS/Axia")
        val arch =
            when (platform) {
                InstallerPlatform.MACOS_ARM64 -> "arm64"
                InstallerPlatform.MACOS_X64 -> "x86_64"
                else -> error("macOS 설치본에서만 DMG를 적용할 수 있습니다.")
            }
        check(Files.isExecutable(binary) && arch in command("/usr/bin/file", "-b", binary.toString())) {
            "DMG의 실행 파일이 현재 Mac 앱의 아키텍처($arch)와 다릅니다."
        }
        command("/usr/bin/codesign", "--verify", "--strict", "--deep", replacement.toString())
        // 현재 배포본은 ad-hoc 서명이다. 개발자 서명이 있는 설치본은 같은 팀의 앱으로만 교체한다.
        val team = signingTeam(installed)
        if (team != null) {
            check(signingTeam(replacement) == team) { "새 앱의 개발자 서명이 설치된 앱과 다릅니다." }
        }
    }

    private fun signingTeam(bundle: Path): String? =
        command("/usr/bin/codesign", "-dv", "--verbose=4", bundle.toString())
            .lineSequence()
            .firstOrNull { it.startsWith("TeamIdentifier=") }
            ?.substringAfter('=')
            ?.takeUnless { it == "not set" }

    private fun bundleValue(
        bundle: Path,
        key: String,
    ): String = command("/usr/libexec/PlistBuddy", "-c", "Print :$key", bundle.resolve("Contents/Info.plist").toString())

    private fun command(vararg args: String): String {
        val process =
            ProcessBuilder(*args)
                .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                .redirectErrorStream(true)
                .start()
        val output =
            process.inputStream
                .bufferedReader()
                .use { it.readText() }
                .trim()
        check(process.waitFor() == 0) { "${args.first().substringAfterLast('/')} 실행 실패: $output" }
        return output
    }
}
