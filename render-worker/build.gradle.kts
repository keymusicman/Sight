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
    val studioLib = file("$asRoot/Contents/lib")

    // kxml2 version suffix changes between releases — match by glob.
    val kxml2Jar = androidPluginLib
        .listFiles { f -> f.isFile && f.name.startsWith("kxml2") && f.name.endsWith(".jar") }
        ?.firstOrNull()

    val compileOnlyJars = listOfNotNull(
        file("$designToolsLib/layoutlib.jar").takeIf { it.exists() },
        file("$androidPluginLib/layoutlib-api.jar").takeIf { it.exists() },
        file("$androidPluginLib/sdk-common.jar").takeIf { it.exists() },
        file("$androidPluginLib/sdk-tools.jar").takeIf { it.exists() },
        // android.jar bundled in the IDE provides com.android.tools.environment.Logger SPI.
        file("$androidPluginLib/android.jar").takeIf { it.exists() },
        // Guava (Bridge.<clinit> uses ImmutableMap; ResourceRepositoryUtil returns a Guava Table).
        file("$studioLib/module-intellij.libraries.guava.jar").takeIf { it.exists() },
        // fastutil (AarSourceResourceRepository.loadFromStream).
        file("$studioLib/module-intellij.libraries.fastutil.jar").takeIf { it.exists() },
        // kxml2 (pull parser used by WorkerLayoutlibCallback / WorkerPullParser).
        kxml2Jar,
    )
    if (compileOnlyJars.isNotEmpty()) compileOnly(files(compileOnlyJars))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    // The resource-repository APIs (sdk-common/sdk-tools + guava/fastutil) are compileOnly
    // for the shaded jar but are needed at test runtime.
    if (compileOnlyJars.isNotEmpty()) testImplementation(files(compileOnlyJars))
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.keymusicman.appflowerplugin.renderworker.worker.RenderWorkerMainKt"
    }
}

tasks.shadowJar {
    archiveBaseName.set("render-worker")
    archiveClassifier.set("all")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "com.keymusicman.appflowerplugin.renderworker.worker.RenderWorkerMainKt"
    }
}
