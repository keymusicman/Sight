plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp) // kept so the Sight plugin can apply KSP from the build classpath
    id("io.github.keymusicman.sight") version "0.1.0"
}

android {
    namespace = "io.github.keymusicman.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.keymusicman.sample"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // sight-annotations (compileOnly) + sight-processor (ksp) and the projectRoot/moduleName
    // KSP args are added automatically by the io.github.keymusicman.sight plugin.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
}
