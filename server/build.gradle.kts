plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
}

group = "team.themoment.axia"
version = "1.0.0"
application {
    mainClass = "team.themoment.axia.ApplicationKt"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core"))
    implementation(libs.bouncycastlePkix)
    implementation(libs.hikari)
    implementation(libs.javaJwt)
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback)
    implementation(libs.mysqlConnector)
    implementation(libs.ktor.clientCio)
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.ktor.serverCallId)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverForwardedHeader)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverStatusPages)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}
