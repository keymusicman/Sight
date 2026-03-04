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
}
