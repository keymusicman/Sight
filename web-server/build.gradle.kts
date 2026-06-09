plugins {
    alias(libs.plugins.kotlinJvm)
    kotlin("plugin.serialization") version libs.versions.kotlin.get()
    application
}

group = "io.github.keymusicman.sight"
version = "1.0.0"

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "io.github.keymusicman.sight.web.WebServerKt"
}

dependencies {
    implementation(project(":graph-renderer"))
    implementation(libs.kotlinx.serialization)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.cors)
    implementation(libs.google.cloud.storage)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
}
