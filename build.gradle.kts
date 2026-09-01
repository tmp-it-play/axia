import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.ktor) apply false
}

val ktlintVersion = libs.versions.ktlint.get()

allprojects {
    if (this != rootProject) {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
    }

    configure<KtlintExtension> {
        version.set(ktlintVersion)
        filter {
            exclude("**/generated/**")
        }
    }
}
