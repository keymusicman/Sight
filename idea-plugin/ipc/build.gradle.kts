plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.kotlinx.serialization)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
}
