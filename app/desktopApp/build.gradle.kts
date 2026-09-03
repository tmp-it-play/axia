import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
            packageName = "team.themoment.axia"
            packageVersion = "1.0.0"
        }
    }
}
