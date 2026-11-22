plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    api(libs.kotlinx.coroutinesCore)
    implementation(libs.kotlinx.serializationJson)
}
