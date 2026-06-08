plugins {
    kotlin("jvm")
}

group = "com.keymusicman"
version = "0.1.0"

dependencies {
    implementation(libs.ksp.api)
    testImplementation(kotlin("test"))
}
