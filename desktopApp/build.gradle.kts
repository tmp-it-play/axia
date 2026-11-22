import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
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
            packageVersion = "1.0.0"
            macOS {
                bundleID = "team.themoment.axia"
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
