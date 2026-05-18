plugins {
    alias(libs.plugins.kotlinJvm)
    id("com.gradleup.shadow") version "8.3.5"
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":ipc"))
    implementation(libs.kotlinx.serialization)

    // Layoutlib + supporting APIs are bundled with Android Studio and are provided
    // at runtime via the classpath the plugin assembles. We compile against them.
    // Path may differ per OS; gradle property `appflower.androidStudioPath` can override.
    val asRoot = file(
        providers.gradleProperty("appflower.androidStudioPath")
            .getOrElse("/Applications/Android Studio.app")
    )
    val designToolsLib = file("$asRoot/Contents/plugins/design-tools/lib")
    val androidPluginLib = file("$asRoot/Contents/plugins/android/lib")

    val compileOnlyJars = listOfNotNull(
        file("$designToolsLib/layoutlib.jar").takeIf { it.exists() },
        file("$androidPluginLib/layoutlib-api.jar").takeIf { it.exists() },
        file("$androidPluginLib/sdk-common.jar").takeIf { it.exists() },
        file("$androidPluginLib/sdk-tools.jar").takeIf { it.exists() },
        file("$androidPluginLib/android.jar").takeIf { it.exists() },
    )
    if (compileOnlyJars.isNotEmpty()) compileOnly(files(compileOnlyJars))
}

tasks.shadowJar {
    archiveBaseName.set("render-worker")
    archiveClassifier.set("all")
    mergeServiceFiles()
}
