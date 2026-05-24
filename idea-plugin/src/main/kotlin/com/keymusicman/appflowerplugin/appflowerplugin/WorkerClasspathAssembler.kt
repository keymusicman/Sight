package com.keymusicman.appflowerplugin.appflowerplugin

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
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

        // Fixed-name JARs. (category, file). Category is purely for error messages.
        val required = listOf(
            "design-tools" to File(pluginsDesignTools, "layoutlib.jar"),
            "android-plugin" to File(pluginsAndroid, "layoutlib-api.jar"),
            "android-plugin" to File(pluginsAndroid, "sdk-common.jar"),
            "android-plugin" to File(pluginsAndroid, "sdk-tools.jar"),
            // android-base-common.jar provides com.android.sdklib.AndroidApiLevel, referenced from
            // FolderConfiguration.<clinit> in Studio 2026.x. Without it, FolderConfiguration fails
            // class init once and the JVM caches that failure for the worker's lifetime — turning
            // one missing class into "every render fails" because every render touches it.
            "android-plugin" to File(pluginsAndroid, "android-base-common.jar"),
            "android-plugin" to File(pluginsAndroid, "android.jar"),
            "android-plugin" to File(pluginsAndroid, "ui-animation-tooling-internal.jar"),
        )
        val missing = required.filter { !it.second.isFile }
        require(missing.isEmpty()) {
            "Missing IDE JARs required by render worker: " +
                missing.joinToString { "${it.first}/${it.second.name} (${it.second.absolutePath})" }
        }

        // Version/build-variant filenames are resolved by pattern, not exact name:
        //  - guava/fastutil platform JARs are "intellij.libraries.X.jar" in some Studio builds and
        //    "module-intellij.libraries.X.jar" in others.
        //  - kxml2 carries its version (e.g. kxml2-2.3.0.jar) and lives under the Android plugin's
        //    lib/ (not Contents/lib/) in Studio 2025.x.
        val guava = findOne(libDir, Regex("(module-)?intellij\\.libraries\\.guava\\.jar"))
        val fastutil = findOne(libDir, Regex("(module-)?intellij\\.libraries\\.fastutil\\.jar"))
        val kxml = findOne(pluginsAndroid, Regex("kxml2.*\\.jar"))

        val workerFatJar = locateWorkerFatJar()

        return buildList {
            required.forEach { add(it.second.absolutePath) }
            add(guava.absolutePath)
            add(fastutil.absolutePath)
            add(kxml.absolutePath)
            add(workerFatJar.absolutePath)
        }
    }

    /** Returns the single file in [dir] whose name matches [pattern], or throws with a clear message. */
    private fun findOne(dir: File, pattern: Regex): File =
        dir.listFiles { _, n -> n.matches(pattern) }?.firstOrNull()
            ?: error("No file matching /$pattern/ under $dir — IDE layout changed?")

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
        if (looksLikeIdeHome(raw)) return raw
        val asContents = File(raw, "Contents")
        if (looksLikeIdeHome(asContents)) return asContents
        error("Cannot find IDE home (lib/ + plugins/android/lib/) under $raw — IDE layout changed?")
    }

    /** A directory is the IDE home if it has both `lib/` and the bundled Android plugin's `lib/`. */
    private fun looksLikeIdeHome(dir: File): Boolean =
        File(dir, "lib").isDirectory && File(dir, "plugins/android/lib").isDirectory

    private const val PLUGIN_ID = "com.keymusicman.appflowerplugin.AppFlowerPlugin"
    private const val WORKER_FAT_JAR = "render-worker-all.jar"

    /**
     * The worker fat JAR is bundled with the plugin and copied to `<plugin>/lib/` alongside the
     * plugin's own JAR. We locate it via the plugin's install path from [PluginManagerCore].
     *
     * We deliberately do NOT use `protectionDomain.codeSource.location`: inside the IDE the plugin
     * classloader reports a null code-source location, which yielded a confusing NPE.
     */
    private fun locateWorkerFatJar(): File {
        val descriptor = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
            ?: error("Plugin $PLUGIN_ID not found via PluginManagerCore — cannot locate $WORKER_FAT_JAR")
        val pluginRoot = descriptor.pluginPath?.toFile()
            ?: error("Plugin $PLUGIN_ID has no install path — cannot locate $WORKER_FAT_JAR")

        val candidate = File(pluginRoot, "lib/$WORKER_FAT_JAR")
        if (candidate.isFile) return candidate

        // Defensive fallback: scan the plugin tree (lib layout could differ across packaging modes).
        val found = pluginRoot.walkTopDown().firstOrNull { it.isFile && it.name == WORKER_FAT_JAR }
        return found ?: error(
            "worker fat JAR not found under $pluginRoot — make sure :render-worker:shadowJar ran and " +
                "was copied into the plugin's lib/"
        )
    }
}
