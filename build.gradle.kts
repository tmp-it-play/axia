import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.ktlint)
}

val ktlintVersion = libs.versions.ktlint.get()

allprojects {
    if (this != rootProject) {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
    }

    val generatedSourcesRoot = layout.buildDirectory.get().asFile
    configure<KtlintExtension> {
        version.set(ktlintVersion)
        filter {
            exclude { it.file.startsWith(generatedSourcesRoot) }
        }
    }
}
