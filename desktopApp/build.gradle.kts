import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":simulation"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.components.resources)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.kotlinx.serializationJson)
}

compose.resources {
    packageOfResClass = "team.themoment.axia.resources"
}

compose.desktop {
    application {
        mainClass = "team.themoment.axia.MainKt"
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "Axia"
            packageVersion = providers.gradleProperty("axia.version").getOrElse("1.0.0")
            modules("java.net.http")
            macOS {
                bundleID = "team.themoment.axia"
                iconFile.set(rootProject.layout.projectDirectory.file("assets/logo.icns"))
            }
            windows {
                iconFile.set(rootProject.layout.projectDirectory.file("assets/logo.ico"))
                upgradeUuid = "18d0e1a8-ecc4-4df7-a2c7-79be983afabd"
                perUserInstall = true
                menuGroup = "Axia"
                shortcut = true
            }
        }
    }
}

tasks.withType<AbstractJPackageTask>().configureEach {
    if (targetFormat == TargetFormat.Dmg) {
        // Compose는 앱 번들을 만들 때만 --icon을 전달하므로 DMG 볼륨에도 같은 아이콘을 지정한다.
        freeArgs.add("--icon")
        freeArgs.add(iconFile.map { "\"${it.asFile.absolutePath}\"" })
    }
}
