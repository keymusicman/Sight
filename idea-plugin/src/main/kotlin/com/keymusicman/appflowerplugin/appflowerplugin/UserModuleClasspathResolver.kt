package com.keymusicman.appflowerplugin.appflowerplugin

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import java.io.File

/**
 * Resolves the runtime classpath the worker JVM needs to load the user's compiled composables.
 *
 * **Why runtime, not compile:** the worker loads user bytecode and runs it through Layoutlib;
 * compile-time API stubs (`-api.jar`) are not sufficient. Per `render-worker/SPIKE_NOTES.md`
 * ("User classpath construction") the AGP runtime classpath via the `android-classes-jar`
 * artifact view is what works.
 *
 * **Strategy:**
 *   1. Start with [OrderEnumerator] productionOnly + recursive + withoutSdk — this gives us
 *      whatever the IntelliJ project model has indexed for the module (usually compile+runtime
 *      mixed, but for Android modules this may miss AAR runtime artifacts processed by AGP).
 *   2. Append the spike's hand-rolled paths: `build/tmp/kotlin-classes/debug/`,
 *      the R.jar, and the SDK platform `android.jar`. These are stable AGP outputs that the
 *      project model often doesn't expose cleanly.
 *
 * If [OrderEnumerator]'s output is missing runtime AAR contents (e.g. ComposeViewAdapter
 * comes from `ui-tooling-release-runtime.jar` which is an AAR), the worker will fail to load
 * those classes. This is a known limitation flagged in the plan — Task 13 (runIde validation)
 * will exercise this end-to-end and we'll iterate from there if needed.
 */
object UserModuleClasspathResolver {

    private val log = Logger.getInstance(UserModuleClasspathResolver::class.java)

    fun resolve(project: Project, modulePath: String): List<String> {
        val ideEntries = ReadAction.compute<List<String>, Throwable> {
            val module = findModule(project, modulePath)
                ?: error("UserModuleClasspathResolver: no module found for $modulePath")
            // recursively() — include transitive dependency modules and their libraries.
            // withoutSdk()   — the SDK android.jar from IntelliJ's model differs from the
            //                  SDK platform's android.jar Layoutlib needs; we add the latter
            //                  explicitly below.
            // productionOnly() — exclude test source roots.
            OrderEnumerator.orderEntries(module)
                .recursively()
                .withoutSdk()
                .productionOnly()
                .classes()
                .pathsList
                .pathList
                .toList()
        }

        val explicit = explicitAgpOutputs(modulePath)
        val sdkJar = locateSdkAndroidJar()

        // Preserve order and remove duplicates while keeping the IDE-derived entries first.
        val seen = LinkedHashSet<String>()
        ideEntries.forEach { if (File(it).exists()) seen.add(it) }
        explicit.forEach { if (File(it).exists()) seen.add(it) }
        if (sdkJar != null) seen.add(sdkJar)

        log.info(
            "UserModuleClasspathResolver: resolved ${seen.size} classpath entries for $modulePath " +
                "(ide=${ideEntries.size}, explicit=${explicit.size}, sdk=${sdkJar != null})"
        )
        return seen.toList()
    }

    /**
     * Find the .main source-set module matching [modulePath]. Mirrors the logic in
     * `ComposableRenderer.resolveModuleCached` so the classpath we build matches the
     * module the in-process renderer would have used.
     */
    private fun findModule(project: Project, modulePath: String): Module? {
        val allModules = ModuleManager.getInstance(project).modules
        val appRootModule = allModules.firstOrNull { m ->
            ModuleRootManager.getInstance(m).contentRoots.any { it.path == modulePath }
        }
        if (appRootModule == null) {
            log.warn(
                "UserModuleClasspathResolver: no module owns content root $modulePath " +
                    "(available: ${allModules.map { it.name }})"
            )
            return null
        }
        // Prefer the .main source-set module when present; it's the one with the production
        // classpath. The holder module (no suffix) may have empty entries in Studio 2025.x.
        return allModules.firstOrNull { it.name == "${appRootModule.name}.main" } ?: appRootModule
    }

    /**
     * AGP build outputs that the IntelliJ project model often does not expose cleanly.
     * Paths derived from `render-worker/SPIKE_NOTES.md` ("User classpath construction").
     * Returned even if missing — caller filters via [File.exists].
     */
    private fun explicitAgpOutputs(modulePath: String): List<String> = listOf(
        File(modulePath, "build/tmp/kotlin-classes/debug").absolutePath,
        File(
            modulePath,
            "build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar"
        ).absolutePath,
    )

    /**
     * Locate the SDK platform's `android.jar` (e.g. `$ANDROID_HOME/platforms/android-37.0/
     * android.jar`). Layoutlib needs this on the user classpath to load core Android classes
     * (see SPIKE_NOTES.md "User classpath construction"). Returns the highest-versioned
     * platform JAR available, or `null` if `$ANDROID_HOME` is unset / no platform installed.
     */
    private fun locateSdkAndroidJar(): String? {
        val sdkRoot = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            System.getProperty("user.home")?.let { "$it/Library/Android/sdk" },   // macOS default
            System.getProperty("user.home")?.let { "$it/Android/Sdk" },           // Linux default
        ).map(::File).firstOrNull { it.isDirectory }
        if (sdkRoot == null) {
            log.warn("UserModuleClasspathResolver: ANDROID_HOME not set and no default SDK found")
            return null
        }
        val platforms = File(sdkRoot, "platforms")
        val best = platforms.listFiles { f -> f.isDirectory && f.name.startsWith("android-") }
            ?.maxByOrNull { it.name }
        if (best == null) {
            log.warn("UserModuleClasspathResolver: no android-* platform under ${platforms.absolutePath}")
            return null
        }
        val jar = File(best, "android.jar")
        return if (jar.isFile) jar.absolutePath else null
    }
}
