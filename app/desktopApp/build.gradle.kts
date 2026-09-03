import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Base64

val releaseBuild = providers.environmentVariable("AXIA_RELEASE_BUILD").orNull == "true"
val releaseVersion = providers.environmentVariable("AXIA_RELEASE_VERSION").orElse("1.0.0").get()
val macPublicKey = providers.environmentVariable("AXIA_MACOS_ED25519_PUBLIC_KEY").orNull
if (releaseBuild) {
    require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(releaseVersion)) { "Invalid release version" }
    val versionParts = releaseVersion.split('.').map(String::toInt)
    require(versionParts[0] <= 255 && versionParts[1] <= 255 && versionParts[2] <= 65535 && versionParts.any { it != 0 })
}

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":app:shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jna)
    implementation(libs.jna.platform)

    implementation(libs.compose.uiToolingPreview)
    implementation(libs.compose.material3)
}

compose.desktop {
    application {
        mainClass = "team.themoment.axia.DesktopMainKt"

        nativeDistributions {
            modules("java.net.http", "java.prefs", "jdk.crypto.ec", "jdk.unsupported")
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "Axia"
            packageVersion = releaseVersion
            appResourcesRootDir.set(layout.buildDirectory.dir("generated/updater-resources"))
            macOS {
                bundleID = "team.themoment.axia"
                if (releaseBuild && System.getProperty("os.name").startsWith("Mac")) {
                    val key = requireNotNull(macPublicKey) { "AXIA_MACOS_ED25519_PUBLIC_KEY is required" }
                    require(
                        Base64.getDecoder().decode(key).size == 32 &&
                            Base64.getEncoder().encodeToString(Base64.getDecoder().decode(key)) == key,
                    )
                    infoPlist {
                        extraKeysRawXml =
                            """
                            <key>SUFeedURL</key><string>https://kimtaeeun.site/axia/updates/macos/arm64/appcast.xml</string>
                            <key>SUPublicEDKey</key><string>$key</string>
                            <key>SURequireSignedFeed</key><true/>
                            <key>SUVerifyUpdateBeforeExtraction</key><true/>
                            <key>SUSignedFeedFailureExpirationInterval</key><real>0</real>
                            <key>SUEnableAutomaticChecks</key><true/>
                            <key>SUAutomaticallyUpdate</key><false/>
                            <key>SUEnableSystemProfiling</key><false/>
                            <key>SUShowReleaseNotes</key><false/>
                            """.trimIndent()
                    }
                }
            }
            windows {
                upgradeUuid = "18d0e1a8-ecc4-4df7-a2c7-79be983afabd"
                perUserInstall = true
                menuGroup = "Axia"
                shortcut = true
            }
        }
    }
}

tasks.register<Exec>("prepareDesktopUpdater") {
    group = "distribution"
    description = "Fetch hash-pinned native updater SDKs and build the host-specific bridge."
    workingDir(rootProject.projectDir)
    commandLine(
        buildList {
            add(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3")
            add("scripts/release/prepare_native.py")
            if (releaseBuild) add("--release")
        },
    )
}
