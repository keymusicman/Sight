package com.keymusicman.appflowerplugin.appflowerplugin

import com.android.resources.ResourceFolderType
import com.android.tools.configurations.Configuration
import com.android.tools.idea.configurations.ConfigurationManager
import com.android.tools.idea.rendering.AndroidBuildTargetReference
import com.android.tools.idea.rendering.AndroidFacetRenderModelModule
import com.android.tools.idea.rendering.StudioRenderService
import com.android.tools.idea.rendering.isSuccess
import com.android.tools.rendering.parsers.RenderXmlFileSnapshot
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.android.facet.AndroidFacet
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Renders @Composable functions from the user's Android module using Layoutlib.
 *
 * Layoutlib works on compiled bytecode, so the module must already be built
 * (the exportGraph task guarantees this).
 */
object ComposableRenderer {

    private val LOG = Logger.getInstance(ComposableRenderer::class.java)

    // Set to true to render a plain red TextView instead of ComposeViewAdapter.
    // Isolates whether failures are in the rendering pipeline or in Compose itself.
    private const val DEBUG_SIMPLE_LAYOUT = false

    /**
     * Renders [composableFqn] and returns the path to a temp PNG, or null on failure.
     *
     * @param project        the open IntelliJ project
     * @param modulePath     absolute path to the Gradle module directory (from GradleModuleInfo)
     * @param composableFqn  fully-qualified composable function name, e.g. "com.example.HomeScreen"
     */
    fun render(
        project: Project,
        modulePath: String,
        composableFqn: String,
        parameterProviderFqn: String? = null,
        stateIndex: Int = -1,
        widthDp: Int = 360,
        heightDp: Int = 640,
        sourceFilePath: String? = null,
        onLog: ((String) -> Unit)? = null,
        useSimpleLayout: Boolean = DEBUG_SIMPLE_LAYOUT,
    ): String? {
        fun logInfo(msg: String) { LOG.info(msg); onLog?.invoke("[INFO] $msg") }
        fun logWarn(msg: String, e: Throwable? = null) {
            if (e != null) LOG.warn(msg, e) else LOG.warn(msg)
            onLog?.invoke("[WARN] $msg${e?.let { "\n  ${it.message}" } ?: ""}")
        }
        fun logError(msg: String, e: Throwable? = null) {
            if (e != null) LOG.error(msg, e) else LOG.error(msg)
            onLog?.invoke("[ERROR] $msg${e?.let { "\n  ${it.message}" } ?: ""}")
        }

        logInfo("render() called for composable=$composableFqn, modulePath=$modulePath, sourceFilePath=$sourceFilePath")

        val allModules = ModuleManager.getInstance(project).modules

        // Find the owning module for the source file. Skip synthetic source-set sub-modules
        // (e.g. *.main, *.test) — AndroidFacet lives on the parent module, not these children.
        // If the owning module turns out to be a library (no app manifest), fall back to the
        // app module from modulePath, which has the full resource + manifest context Layoutlib needs.
        val sourceOwnerModule = if (sourceFilePath != null) {
            // Exclude IntelliJ's per-source-set sub-modules (e.g. *.main, *.test).
            // AndroidFacet lives on the parent Gradle module, not these synthetic children.
            val sourceSetSegments = setOf("main", "test", "unitTest", "androidTest")
            val candidate = allModules
                .filter { m ->
                    val seg = m.name.substringAfterLast('.')
                    seg !in sourceSetSegments && !seg.startsWith("screenshotTest")
                }
                .flatMap { m ->
                    ModuleRootManager.getInstance(m).contentRoots
                        .filter { root ->
                            sourceFilePath.startsWith(root.path + "/") || sourceFilePath == root.path
                        }
                        .map { root -> m to root.path.length }
                }
                .maxByOrNull { (_, len) -> len }
                ?.first

            when {
                candidate == null -> {
                    logWarn("render() no module owns sourceFilePath=$sourceFilePath, falling back to modulePath")
                    null
                }
                AndroidFacet.getInstance(candidate) == null -> {
                    logInfo("render() ${candidate.name} has no AndroidFacet (root/holder module); falling back to modulePath")
                    null
                }
                AndroidFacet.getInstance(candidate)?.configuration?.isLibraryProject == true -> {
                    logInfo("render() ${candidate.name} is a library module; using app module from modulePath for rendering")
                    null
                }
                else -> {
                    logInfo("render() resolved owner app module=${candidate.name} for sourceFilePath=$sourceFilePath")
                    candidate
                }
            }
        } else null

        // The root "holder" module triggers "holder module ambiguous" in GradleBuildSystemFilePreviewServices
        // because multiple build-variant sub-modules share the same logical name.
        // Android Studio's own preview avoids this by using the .main source-set module instead.
        val appRootModule = sourceOwnerModule
            ?: allModules.firstOrNull { m ->
                ModuleRootManager.getInstance(m).contentRoots.any { root -> root.path == modulePath }
            }

        if (appRootModule == null) {
            logWarn("render() failed: no module found matching path=$modulePath. " +
                "Available modules: ${allModules.map { it.name }}")
            return null
        }

        val module = allModules.firstOrNull { m -> m.name == "${appRootModule.name}.main" }
            ?.also { logInfo("render() using .main source-set module=${it.name} to avoid holder-module ambiguity") }
            ?: appRootModule

        val facet = AndroidFacet.getInstance(module)
        if (facet == null) {
            logWarn("render() failed: no AndroidFacet for module=${module.name}")
            return null
        }

        val lfs = LocalFileSystem.getInstance()
        // ConfigurationManager.getConfiguration needs a file VirtualFile (not a directory) to
        // set up the correct theme, density, and API level from the module's manifest.
        // Prefer the source file that contains the composable; fall back to the module's
        // AndroidManifest.xml so ConfigurationManager has module context; last resort: module dir.
        val configVf = (sourceFilePath?.let { lfs.findFileByPath(it) }
            ?: lfs.findFileByPath("$modulePath/src/main/AndroidManifest.xml")
            ?: lfs.findFileByPath(modulePath))
            ?: run {
                logWarn("render() failed: could not find VirtualFile for modulePath=$modulePath")
                return null
            }
        logInfo("render() using configVf=${configVf.path} for ConfigurationManager")

        val config: Configuration = ConfigurationManager
            .getOrCreateInstance(module)
            .getConfiguration(configVf)

        // from(facet, configVf) resolves the build target from the source file so Layoutlib
        // uses the debug variant classpath (including debugImplementation deps like ui-tooling).
        // gradleOnly() omits variant context, which can cause ComposeViewAdapter to be broken.
        val buildTargetRef = AndroidBuildTargetReference.gradleOnly(facet)
        val renderModelModule = AndroidFacetRenderModelModule(buildTargetRef)

        val renderService = StudioRenderService.getInstance(project)
        val renderLogger = renderService.createLogger(project)

        val task = try {
            renderService
                .taskBuilder(renderModelModule, config, renderLogger)
                .disableDecorations()
                .build()
                .get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logError("render() failed: exception building render task for composable=$composableFqn", e)
            return null
        }
        if (task == null) {
            logWarn("render() failed: render task is null for composable=$composableFqn")
            return null
        }

        return try {
            val xml = if (useSimpleLayout) {
                // Plain TextView — tests that the rendering pipeline works independently of Compose.
                """
                <TextView
                    xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="${widthDp}dp"
                    android:layout_height="${heightDp}dp"
                    android:text="$composableFqn"
                    android:textColor="#FF0000"
                    android:textSize="24sp"
                    android:gravity="center" />
                """.trimIndent()
            } else {
                // Real path: ComposeViewAdapter is the same bridge Android Studio's Preview uses.
                val resolvedName = resolveComposableNameForLayoutlib(project, composableFqn)
                if (resolvedName != composableFqn) logInfo("render() resolved top-level FQN: $composableFqn → $resolvedName")
                val providerAttr = if (parameterProviderFqn != null) buildString {
                    append("\n    tools:parameterProviderClass=\"$parameterProviderFqn\"")
                    if (stateIndex >= 0) append("\n    tools:parameterProviderIndex=\"$stateIndex\"")
                } else ""
                // No <?xml?> declaration — kxml2 treats it as a Processing Instruction and rejects it.
                """
                <androidx.compose.ui.tooling.ComposeViewAdapter
                    xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    tools:composableName="$resolvedName"$providerAttr />
                """.trimIndent()
            }
            logInfo("render() xml for composable=$composableFqn:\n$xml")

            task.setXmlFile(RenderXmlFileSnapshot(project, "preview.xml", ResourceFolderType.LAYOUT, xml))

            // Step 1: inflate — diagnostic only; failures are logged but do not abort.
            // task.render() handles inflation internally via a more permissive code path, so
            // aborting on inflate() failure regresses composables whose packages can't be resolved
            // at this stage but still render correctly via render().
            val inflateResult = task.inflate().get(30, TimeUnit.SECONDS)
            if (inflateResult != null) {
                runCatching { inflateResult.renderResult }.getOrNull()?.let { r ->
                    logInfo("inflate() status=${r.status} isSuccess=${r.isSuccess}")
                    if (!r.isSuccess) {
                        logWarn("inflate() failed (non-fatal): error=${r.errorMessage}")
                        r.exception?.let { ex -> logWarn("inflate() exception:\n${ex.stackTraceToString().take(3000)}") }
                    }
                }
                inflateResult.logger.messages.forEach { msg ->
                    logWarn("inflate() layoutlib [${msg.severity}]: ${msg.html}")
                }
                runCatching { inflateResult.logger.brokenClasses.takeIf { it.isNotEmpty() } }.getOrNull()
                    ?.forEach { (cls, ex) -> logWarn("inflate() brokenClass $cls:\n${ex?.stackTraceToString()?.take(2000) ?: "(no trace)"}") }
                runCatching { inflateResult.logger.missingClasses.takeIf { it.isNotEmpty() } }.getOrNull()
                    ?.let { logWarn("inflate() missingClasses: $it") }
            }

            // Advance the frame clock so Compose's MonotonicFrameClock ticks and the initial
            // composition + any effects run before we snapshot. A single-render approach avoids
            // the double-render issue where the first render() consumes the canvas state and
            // the second render() returns a blank image.
            val frameNs = 16_666_666L // ~60fps
            var currentFrameNs = System.nanoTime()
            var frames = 0
            var lastCallbacks = task.executeCallbacks(currentFrameNs).get(30, TimeUnit.SECONDS)
            frames++
            while ((lastCallbacks?.hasMoreCallbacks() == true || frames < 5) && frames < 10) {
                currentFrameNs += frameNs
                lastCallbacks = task.executeCallbacks(currentFrameNs).get(30, TimeUnit.SECONDS)
                frames++
            }
            logInfo("render() executeCallbacks done after $frames frame(s) for $composableFqn")

            val result = task.render().get(30, TimeUnit.SECONDS)
            if (result == null) {
                logWarn("render() failed: render result is null for composable=$composableFqn")
                return null
            }

            logInfo("render() result: isSuccess=${result.isSuccess()}, module=${module.name}")
            // Log the underlying Result status/exception for deeper failure diagnosis.
            runCatching { result.renderResult }.getOrNull()?.let { r ->
                if (!r.isSuccess) {
                    logWarn("render() renderResult status=${r.status} error=${r.errorMessage}", r.exception)
                    r.exception?.let { ex ->
                        var cause: Throwable? = ex
                        var depth = 0
                        while (cause != null && depth < 6) {
                            logWarn("render() cause[$depth]: ${cause::class.simpleName}: ${cause.message}")
                            cause = cause.cause
                            depth++
                        }
                    }
                }
            }

            // Log every render message regardless of severity so we can diagnose failures.
            result.logger.messages.forEach { msg ->
                logWarn("render() layoutlib [${msg.severity}] $composableFqn: ${msg.html}")
            }
            // Return null when a render message indicates an out-of-bounds provider index —
            // this is how the multi-state loop detects that all valid states have been rendered.
            if (result.logger.messages.any { it.html.contains("Sequence doesn't contain element") }) {
                logInfo("render() stopping multi-state loop: provider exhausted at stateIndex=$stateIndex")
                return null
            }
            // Broken/missing classes are stored separately from messages — often the real root cause.
            val broken = runCatching { result.logger.brokenClasses.takeIf { it.isNotEmpty() } }.getOrNull()
            if (broken != null) {
                broken.forEach { (cls, ex) -> logWarn("render() brokenClass $cls for $composableFqn: ${ex?.message}", ex) }
                // ComposeViewAdapter broken means ui-tooling classpath is unusable; no image will render.
                if (broken.keys.any { it.contains("ComposeViewAdapter") }) {
                    logWarn("render() aborting: ComposeViewAdapter broken — ui-tooling not loadable in Layoutlib classpath")
                    return null
                }
            }
            runCatching { result.logger.missingClasses.takeIf { it.isNotEmpty() } }.getOrNull()
                ?.let { logWarn("render() missingClasses for $composableFqn: $it") }

            val image = result.renderedImage.copy
                ?: run {
                    logWarn("render() failed: rendered image is null for composable=$composableFqn")
                    return null
                }

            // Crop to the composable's measured size. ComposeViewAdapter uses wrap_content so it
            // measures to the composable's intrinsic size, not the full device canvas. rootViews
            // gives the ComposeViewAdapter's layout bounds (left/top/right/bottom) inside the image.
            val outputImage = runCatching {
                result.rootViews.firstOrNull()?.let { root ->
                    val left = root.left
                    val top = root.top
                    val width = root.right - root.left
                    val height = root.bottom - root.top
                    logInfo("render() root view bounds: ${width}x${height} at ($left,$top) for $composableFqn")
                    if (width > 0 && height > 0 && left >= 0 && top >= 0 &&
                        left + width <= image.width && top + height <= image.height
                    ) {
                        image.getSubimage(left, top, width, height)
                    } else {
                        logWarn("render() root view bounds outside image (${image.width}x${image.height}): left=$left top=$top w=$width h=$height — using full image")
                        null
                    }
                }
            }.getOrNull() ?: run {
                logInfo("render() no root view bounds — using full image ${image.width}x${image.height} for $composableFqn")
                image
            }

            val safeName = composableFqn.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val outDir = File(modulePath, "build/appflower-previews").also { it.mkdirs() }
            val outFile = if (stateIndex >= 0) File(outDir, "${safeName}_${stateIndex}.png")
                          else File(outDir, "$safeName.png")
            ImageIO.write(outputImage, "PNG", outFile)
            logInfo("render() succeeded for composable=$composableFqn -> ${outFile.absolutePath}")
            outFile.absolutePath
        } catch (e: Exception) {
            logError("render() failed: exception during rendering of composable=$composableFqn", e)
            null
        } finally {
            task.dispose()
        }
    }

    // ComposeViewAdapter splits tools:composableName on the last '.' to get (className, methodName).
    // For top-level Kotlin functions the "class" part is just the package, which is not loadable.
    // This resolves the actual file-facade class via PSI so the name becomes e.g.
    // "com.example.StartupScreenKt.TestComposable" instead of "com.example.TestComposable".
    private fun resolveComposableNameForLayoutlib(project: Project, composableFqn: String): String {
        val classPart = composableFqn.substringBeforeLast('.', missingDelimiterValue = "")
        val methodName = composableFqn.substringAfterLast('.')
        if (classPart.isEmpty()) return composableFqn

        return try {
            ReadAction.compute<String, Throwable> {
                val scope = GlobalSearchScope.allScope(project)
                val facade = JavaPsiFacade.getInstance(project)

                // If the class part is already a valid class, no correction is needed.
                if (facade.findClass(classPart, scope) != null) return@compute composableFqn

                // Search all classes in the package for one containing this method.
                val pkg = facade.findPackage(classPart)
                val containingClass = pkg?.classes?.firstOrNull { cls ->
                    cls.findMethodsByName(methodName, false).isNotEmpty()
                }
                containingClass?.qualifiedName?.let { "$it.$methodName" } ?: composableFqn
            }
        } catch (_: Exception) {
            composableFqn
        }
    }
}
