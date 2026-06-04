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
        data class Resolved(val ideEntries: List<String>, val agpEntries: List<String>)

        val resolved = ReadAction.compute<Resolved, Throwable> {
            val module = findModule(project, modulePath)
                ?: error("UserModuleClasspathResolver: no module found for $modulePath")
            // recursively() — include transitive dependency modules and their libraries.
            // withoutSdk()   — the SDK android.jar from IntelliJ's model differs from the
            //                  SDK platform's android.jar Layoutlib needs; we add the latter
            //                  explicitly below.
            // productionOnly() — exclude test source roots.
            val ide = OrderEnumerator.orderEntries(module)
                .recursively()
                .withoutSdk()
                .productionOnly()
                .classes()
                .pathsList
                .pathList
                .toList()
            // OrderEnumerator misses transitive AAR runtime jars (e.g.
            // `androidx.customview:customview-poolingcontainer`, a transitive of
            // `androidx.ui-tooling`) — IDEA's project model collapses them into a single
            // library and doesn't surface them via `OrderEnumerator`. The in-process renderer
            // routes through `AndroidFacetRenderModelModule` which uses Studio's own
            // resolver; we mirror that here via `AndroidGradleClassJarProvider`, which is
            // the same code path Studio uses internally to feed Layoutlib.
            val agp = resolveAgpRuntimeClasspath(module)
            Resolved(ide, agp)
        }

        val explicit = explicitAgpOutputs(modulePath)
        val sdkJar = locateSdkAndroidJar()

        // Order: AGP-resolved runtime classpath first (it's the authoritative one — same as
        // what AndroidFacetRenderModelModule feeds Layoutlib in-process), then IDE entries
        // for any gaps, then AGP build outputs we wired in manually, then the SDK jar.
        val seen = LinkedHashSet<String>()
        resolved.agpEntries.forEach { if (File(it).exists()) seen.add(it) }
        resolved.ideEntries.forEach { if (File(it).exists()) seen.add(it) }
        explicit.forEach { if (File(it).exists()) seen.add(it) }
        if (sdkJar != null) seen.add(sdkJar)

        // The runtime R.jar must win the classloader's first-match lookup over the all-zero
        // placeholder compile R.jar (which the IDE compile classpath puts in earlier), else
        // every painterResource id resolves to 0 → blank render.
        val ordered = prioritizeRuntimeRJars(seen.toList())
        log.info(
            "UserModuleClasspathResolver: resolved ${ordered.size} classpath entries for $modulePath " +
                "(agp=${resolved.agpEntries.size}, ide=${resolved.ideEntries.size}, " +
                "explicit=${explicit.size}, sdk=${sdkJar != null})"
        )
        return ordered
    }

    /**
     * Calls Studio's [`AndroidGradleClassJarProvider.getModuleExternalLibraries`][provider] via
     * reflection to obtain the AGP-resolved runtime classpath for [module]. This is the same
     * resolver Studio uses internally to feed Layoutlib, and it correctly includes transitive
     * AAR runtime jars that `OrderEnumerator` misses (the IDE's project model collapses them).
     *
     * Reflection (rather than a compile-time dependency) so the plugin can still load on a
     * vanilla IntelliJ install where the Android plugin is absent — in that case we fall back
     * to whatever `OrderEnumerator` returned plus the explicit AGP outputs.
     *
     * [provider]: com.android.tools.idea.gradle.AndroidGradleClassJarProvider
     */
    private fun resolveAgpRuntimeClasspath(module: Module): List<String> = try {
        val cls = Class.forName("com.android.tools.idea.gradle.AndroidGradleClassJarProvider")
        val instance = cls.getField("INSTANCE").get(null)
        val method = cls.getMethod("getModuleExternalLibraries", Module::class.java)
        @Suppress("UNCHECKED_CAST")
        val files = method.invoke(instance, module) as List<File>
        files.map { it.absolutePath }
    } catch (e: ClassNotFoundException) {
        log.warn("UserModuleClasspathResolver: AndroidGradleClassJarProvider not on classpath — falling back to OrderEnumerator-only resolution; non-Android-Studio IDE?")
        emptyList()
    } catch (e: Throwable) {
        log.warn("UserModuleClasspathResolver: AndroidGradleClassJarProvider invocation failed", e)
        emptyList()
    }

    /**
     * Find the .main source-set module matching [modulePath]. Mirrors the logic in
     * `ComposableRenderer.resolveModuleCached` so the classpath we build matches the
     * module the in-process renderer would have used.
     */
    internal fun findModule(project: Project, modulePath: String): Module? {
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
    private fun explicitAgpOutputs(modulePath: String): List<String> {
        val moduleDir = File(modulePath)
        return buildList {
            add(File(moduleDir, "build/tmp/kotlin-classes/debug").absolutePath)
            findGeneratedRJars(moduleDir).forEach { add(it.absolutePath) }
        }
    }

    /**
     * Discovers the AGP-generated R class jar(s) for [moduleDir]'s debug variant.
     *
     * **Why this matters:** the standalone worker loads user/AAR bytecode with a plain
     * [java.net.URLClassLoader]. Unlike Studio's `ModuleClassLoader` (used by the in-process
     * [ComposableRenderer]), it does **not** synthesize R classes on the fly — so the
     * *transitive* generated R.jar must be on the classpath. Dependency bytecode references R
     * classes that exist only in this generated jar, never in the AAR's own `classes.jar`: e.g.
     * `androidx.customview.poolingcontainer.R$id`, touched by `PoolingContainer.<clinit>` during
     * **every** `ComposeViewAdapter` inflation. Missing it ⇒ `NoClassDefFoundError` ⇒ every
     * preview fails at `createSession`.
     *
     * The intermediate directory name varies by module type and AGP version:
     *   - `compile_and_runtime_not_namespaced_r_class_jar` — older AGP application modules
     *   - `compile_and_runtime_r_class_jar`                — newer AGP (Gradle 9.x) / features
     *   - `compile_r_class_jar`                            — the module's own, non-transitive R
     * so we glob every `*r_class_jar*` intermediate rather than hardcode one path (the hardcoded
     * `compile_and_runtime_not_namespaced_r_class_jar` path was the original bug — it doesn't
     * exist for this project's AGP version). Jars are returned largest-first so the fat
     * transitive jar wins [java.net.URLClassLoader] lookups over the thin own-module jar.
     */
    /**
     * Ensures the user classloader resolves the app's **runtime** R classes (real resource ids),
     * not the compile-time placeholder R whose ids are all `0`.
     *
     * AGP emits two R.jars: the runtime `compile_and_runtime_*r_class_jar` (real, merged,
     * transitive ids) and the compile-only `compile_r_class_jar` (own-module, all-zero
     * placeholders — values get linked in only at packaging). The placeholder jar reaches the
     * classpath first via the IDE/AGP compile classpath, so a plain `URLClassLoader` resolves
     * `R$drawable.foo` to `0`, and `painterResource(0)` throws during composition → blank render.
     *
     * When a runtime R.jar is present we hoist it to the front (so it wins first-match lookups)
     * and drop the placeholder jars entirely (the runtime jar is a strict superset). With no
     * runtime jar we leave the list untouched — the placeholder is all that's available.
     */
    internal fun prioritizeRuntimeRJars(entries: List<String>): List<String> {
        fun isRJar(p: String) = p.endsWith("R.jar")
        // `compile_and_runtime_r_class_jar` and the older `compile_and_runtime_not_namespaced_…`
        // are the real merged runtime ids; `compile_r_class_jar` is the all-zero placeholder.
        fun isRuntimeRJar(p: String) = isRJar(p) && p.contains("compile_and_runtime")
        fun isPlaceholderRJar(p: String) = isRJar(p) && p.contains("compile_r_class_jar")

        val runtime = entries.filter { isRuntimeRJar(it) }
        if (runtime.isEmpty()) return entries
        val rest = entries.filterNot { isRuntimeRJar(it) || isPlaceholderRJar(it) }
        return runtime + rest
    }

    internal fun findGeneratedRJars(moduleDir: File): List<File> {
        val intermediates = File(moduleDir, "build/intermediates")
        val rClassDirs = intermediates.listFiles { f -> f.isDirectory && f.name.contains("r_class_jar") }
            ?: return emptyList()
        return rClassDirs
            .asSequence()
            .map { File(it, "debug") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.name == "R.jar" } }
            .distinctBy { it.absolutePath }
            .sortedByDescending { it.length() }
            .toList()
    }

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
