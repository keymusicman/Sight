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
    // Path may differ per OS; gradle property `sight.androidStudioPath` can override.
    val asRoot = file(
        providers.gradleProperty("sight.androidStudioPath")
            .getOrElse("/Applications/Android Studio.app")
    )
    val designToolsLib = file("$asRoot/Contents/plugins/design-tools/lib")
    val androidPluginLib = file("$asRoot/Contents/plugins/android/lib")
    val studioLib = file("$asRoot/Contents/lib")

    // kxml2 version suffix changes between releases — match by glob.
    val kxml2Jar = androidPluginLib
        .listFiles { f -> f.isFile && f.name.startsWith("kxml2") && f.name.endsWith(".jar") }
        ?.firstOrNull()

    // Studio renamed these bundled libs from `module-intellij.libraries.<x>.jar` (stable) to
    // `intellij.libraries.<x>.jar` (canary / Preview). Match by suffix so both resolve; the
    // suffix is specific enough to skip e.g. `intellij.libraries.kotlinx.coroutines.guava.jar`.
    fun studioLibBySuffix(suffix: String): File? = studioLib
        .listFiles { f -> f.isFile && f.name.endsWith(suffix) }
        ?.firstOrNull()

    val compileOnlyJars = listOfNotNull(
        file("$designToolsLib/layoutlib.jar").takeIf { it.exists() },
        file("$androidPluginLib/layoutlib-api.jar").takeIf { it.exists() },
        file("$androidPluginLib/sdk-common.jar").takeIf { it.exists() },
        file("$androidPluginLib/sdk-tools.jar").takeIf { it.exists() },
        // com.android.utils.* / SdkConstants — used by AarSourceResourceRepository loading.
        file("$androidPluginLib/android-base-common.jar").takeIf { it.exists() },
        // android.jar bundled in the IDE provides com.android.tools.environment.Logger SPI.
        file("$androidPluginLib/android.jar").takeIf { it.exists() },
        // Guava (Bridge.<clinit> uses ImmutableMap; ResourceRepositoryUtil returns a Guava Table).
        studioLibBySuffix("intellij.libraries.guava.jar"),
        // fastutil (AarSourceResourceRepository.loadFromStream).
        studioLibBySuffix("intellij.libraries.fastutil.jar"),
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
        attributes["Main-Class"] = "io.github.keymusicman.sight.worker.RenderWorkerMainKt"
    }
}

tasks.shadowJar {
    archiveBaseName.set("render-worker")
    archiveClassifier.set("all")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "io.github.keymusicman.sight.worker.RenderWorkerMainKt"
    }
}
