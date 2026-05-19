package com.keymusicman.appflowerplugin.appflowerplugin

import com.intellij.openapi.application.PathManager
import java.io.File

/**
 * Assembles the classpath needed to spawn the `:render-worker` JVM.
 *
 * The worker runs Layoutlib directly and therefore needs the same IDE-bundled JARs that the
 * spike proved are sufficient (see `render-worker/SPIKE_NOTES.md`, "Working classpath" section).
 * Because the plugin runs inside Android Studio we can locate these JARs at runtime via
 * IntelliJ Platform's [PathManager] rather than hardcoding `/Applications/Android Studio.app`.
 *
 * If any required JAR is missing, [assemble] throws — we want a clear startup error rather than
 * a [NoClassDefFoundError] inside the spawned worker.
 */
object WorkerClasspathAssembler {

    fun assemble(): List<String> {
        val homePath = resolveHomePath()
        val pluginsAndroid = File(homePath, "plugins/android/lib")
        val pluginsDesignTools = File(homePath, "plugins/design-tools/lib")
        val libDir = File(homePath, "lib")

        // (category, file). Category is purely for error messages.
        val required = listOf(
            "design-tools" to File(pluginsDesignTools, "layoutlib.jar"),
            "android-plugin" to File(pluginsAndroid, "layoutlib-api.jar"),
            "android-plugin" to File(pluginsAndroid, "sdk-common.jar"),
            "android-plugin" to File(pluginsAndroid, "sdk-tools.jar"),
            "android-plugin" to File(pluginsAndroid, "android.jar"),
            "platform" to File(libDir, "module-intellij.libraries.guava.jar"),
            "platform" to File(libDir, "module-intellij.libraries.fastutil.jar"),
            "android-plugin" to File(pluginsAndroid, "ui-animation-tooling-internal.jar"),
        )
        val missing = required.filter { !it.second.isFile }
        require(missing.isEmpty()) {
            "Missing IDE JARs required by render worker: " +
                missing.joinToString { "${it.first}/${it.second.name} (${it.second.absolutePath})" }
        }

        // kxml2 filename includes its version (e.g. kxml2-2.3.0.jar). It lives under the Android
        // plugin's lib/ (not Contents/lib/) in Studio 2025.x.
        val kxml = pluginsAndroid.listFiles { _, n -> n.matches(Regex("kxml2.*\\.jar")) }?.firstOrNull()
            ?: error("kxml2*.jar not found under $pluginsAndroid — IDE layout changed?")

        val workerFatJar = locateWorkerFatJar()

        return buildList {
            required.forEach { add(it.second.absolutePath) }
            add(kxml.absolutePath)
            add(workerFatJar.absolutePath)
        }
    }

    /**
     * Android Studio install root, used as [WorkerInit.androidStudioRoot] so the worker can
     * locate native libraries (layoutlib_jni.dylib etc.) and the Android plugin's resource data
     * directory. On macOS this is the `.app` directory (parent of `Contents/`).
     */
    fun androidStudioRoot(): File {
        val home = File(PathManager.getHomePath())
        return if (home.name == "Contents") home.parentFile else home
    }

    /**
     * [PathManager.getHomePath] on macOS normally returns `.../Contents`, but be defensive in
     * case the host IDE returns the `.app` bundle root instead.
     */
    private fun resolveHomePath(): File {
        val raw = File(PathManager.getHomePath())
        if (File(raw, "lib/module-intellij.libraries.guava.jar").isFile) return raw
        val asContents = File(raw, "Contents")
        if (File(asContents, "lib/module-intellij.libraries.guava.jar").isFile) return asContents
        error("Cannot find IntelliJ Contents/lib under $raw — IDE layout changed?")
    }

    /**
     * The worker fat JAR is bundled with the plugin and copied to `<plugin>/lib/`
     * alongside the plugin's own JAR. We locate it relative to the JAR containing this class.
     */
    private fun locateWorkerFatJar(): File {
        val pluginJarUri = WorkerClasspathAssembler::class.java.protectionDomain.codeSource.location.toURI()
        val pluginJar = File(pluginJarUri)
        val candidate = File(pluginJar.parentFile, "render-worker-all.jar")
        require(candidate.isFile) {
            "worker fat JAR not found at $candidate — make sure :render-worker:shadowJar ran and " +
                "was copied into plugin lib/"
        }
        return candidate
    }
}
