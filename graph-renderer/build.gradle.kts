plugins {
    alias(libs.plugins.kotlinJvm)
    kotlin("plugin.serialization") version libs.versions.kotlin.get()
}

group = "com.keymusicman.appflower"
version = "1.0.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
