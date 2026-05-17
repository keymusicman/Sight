package com.keymusicman.appflowerplugin.appflowerplugin

import com.android.ide.common.resources.Locale as AndroidLocale
import com.android.resources.NightMode
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
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.android.facet.AndroidFacet
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Renders @Composable functions from the user's Android module using Layoutlib.
 *
 * Layoutlib works on compiled bytecode, so the module must already be built
 * (the exportGraph task guarantees this).
 */
object ComposableRenderer {

    private val LOG = Logger.getInstance(ComposableRenderer::class.java)

    private data class ModuleCacheKey(val modulePath: String, val sourceFilePath: String?)
    private data class ModuleCacheEntry(val module: Module, val facet: AndroidFacet, val configVf: VirtualFile)
    private val moduleCache = ConcurrentHashMap<ModuleCacheKey, ModuleCacheEntry>()
    private val fqnCache = ConcurrentHashMap<String, String>()

    fun clearCaches() {
        moduleCache.clear()
        fqnCache.clear()
    }

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
        sourceFilePath: String? = null,
        onLog: ((String) -> Unit)? = null,
        useSimpleLayout: Boolean = DEBUG_SIMPLE_LAYOUT,
        previewConfig: PreviewRenderConfig = PreviewRenderConfig(),
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
        val imageStartMs = System.currentTimeMillis()
        val heapBefore = Runtime.getRuntime().run { totalMemory() - freeMemory() }
        val gcBefore   = readGcStats()

        val pluginSettings = PluginSettingsService.getInstance()
        val outputFormat = pluginSettings.getState().outputFormat
        val outFile = PreviewCache.expectedFile(modulePath, composableFqn, stateIndex, outputFormat)
        if (shouldSkipIncrementalRender(outFile, sourceFilePath, pluginSettings.getState().incrementalRendering)) {
            logInfo("render() skipped (incremental): $composableFqn -> ${outFile.absolutePath}")
            TelemetryService.getInstance().recordSkip()
            return outFile.absolutePath
        }

        val (module, facet, configVf) = resolveModuleCached(
            project, modulePath, sourceFilePath,
            logInfo = { logInfo(it) }, logWarn = { logWarn(it) },
        ) ?: return null

        val configManager = ConfigurationManager.getOrCreateInstance(module)
        val config: Configuration = configManager.getConfiguration(configVf)

        if (previewConfig.useCustomConfig) {
            val baseDeviceId = if (previewConfig.deviceId == CUSTOM_DEVICE_ID) "pixel_5" else previewConfig.deviceId
            val device = configManager.devices.firstOrNull { it.id == baseDeviceId }
            if (device != null) {
                config.setDevice(device, false)
                logInfo("render() device set to ${device.displayName} for $composableFqn")
            } else {
                logWarn("render() device '${previewConfig.deviceId}' not found — using default: ${config.device?.displayName}")
            }
            config.setNightMode(if (previewConfig.uiMode == PreviewUiMode.DARK) NightMode.NIGHT else NightMode.NOTNIGHT)
            config.setFontScale(previewConfig.fontScale)
            config.setLocale(if (previewConfig.locale.isBlank()) AndroidLocale.ANY else AndroidLocale.create(previewConfig.locale))
            logInfo("render() custom config applied: device=${previewConfig.deviceId}, uiMode=${previewConfig.uiMode}, fontScale=${previewConfig.fontScale}, locale='${previewConfig.locale}', showSystemUi=${previewConfig.showSystemUi}")
        } else {
            // ConfigurationManager caches the Configuration object per file — our mutations from
            // a previous useCustomConfig=true render persist on it. Reset all four fields to known
            // defaults so stale custom settings don't leak into this render.
            configManager.devices.firstOrNull { it.id == "pixel_5" }
                ?.let { config.setDevice(it, false) }
            config.setNightMode(NightMode.NOTNIGHT)
            config.setFontScale(1.0f)
            config.setLocale(AndroidLocale.ANY)
            logInfo("render() useCustomConfig=false — reset to defaults (pixel_5, light, 1.0x, system locale) for $composableFqn")
        }

        // from(facet, configVf) resolves the build target from the source file so Layoutlib
        // uses the debug variant classpath (including debugImplementation deps like ui-tooling).
        // gradleOnly() omits variant context, which can cause ComposeViewAdapter to be broken.
        val buildTargetRef = AndroidBuildTargetReference.gradleOnly(facet)
        val renderModelModule = AndroidFacetRenderModelModule(buildTargetRef)

        val renderService = StudioRenderService.getInstance(project)
        val renderLogger = renderService.createLogger(project)

        val task = try {
            val builder = renderService.taskBuilder(renderModelModule, config, renderLogger)
            val showDecorations = previewConfig.useCustomConfig && previewConfig.showSystemUi && !useSimpleLayout
            if (!showDecorations) builder.disableDecorations()
            builder.build().get(30, TimeUnit.SECONDS)
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
                    android:layout_width="${previewConfig.customWidthDp}dp"
                    android:layout_height="${previewConfig.customHeightDp}dp"
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
                val sizeAttr = if (previewConfig.useCustomConfig && previewConfig.deviceId == CUSTOM_DEVICE_ID) {
                    "\n    tools:previewWidth=\"${previewConfig.customWidthDp}\"" +
                    "\n    tools:previewHeight=\"${previewConfig.customHeightDp}\""
                } else ""
                // No <?xml?> declaration — kxml2 treats it as a Processing Instruction and rejects it.
                """
                <androidx.compose.ui.tooling.ComposeViewAdapter
                    xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    tools:composableName="$resolvedName"$providerAttr$sizeAttr />
                """.trimIndent()
            }
            logInfo("render() xml for composable=$composableFqn:\n$xml")

            task.setXmlFile(RenderXmlFileSnapshot(project, "preview.xml", ResourceFolderType.LAYOUT, xml))

            // Step 1: inflate — diagnostic only; failures are logged but do not abort.
            // task.render() handles inflation internally via a more permissive code path, so
            // aborting on inflate() failure regresses composables whose packages can't be resolved
            // at this stage but still render correctly via render().
            val inflateResult = task.inflate().get(30, TimeUnit.SECONDS)
            val inflateEndMs = System.currentTimeMillis()
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
            val callbacksEndMs = System.currentTimeMillis()

            val result = task.render().get(30, TimeUnit.SECONDS)
            val renderEndMs = System.currentTimeMillis()
            if (result == null) {
                logWarn("render() failed: render result is null for composable=$composableFqn")
                logInfo("image failed in ${System.currentTimeMillis() - imageStartMs}ms: $composableFqn")
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
                logInfo("image exhausted in ${System.currentTimeMillis() - imageStartMs}ms: $composableFqn stateIndex=$stateIndex")
                return null
            }
            // Broken/missing classes are stored separately from messages — often the real root cause.
            val broken = runCatching { result.logger.brokenClasses.takeIf { it.isNotEmpty() } }.getOrNull()
            if (broken != null) {
                broken.forEach { (cls, ex) -> logWarn("render() brokenClass $cls for $composableFqn: ${ex?.message}", ex) }
                // ComposeViewAdapter broken means ui-tooling classpath is unusable; no image will render.
                if (broken.keys.any { it.contains("ComposeViewAdapter") }) {
                    logWarn("render() aborting: ComposeViewAdapter broken — ui-tooling not loadable in Layoutlib classpath")
                    logInfo("image failed in ${System.currentTimeMillis() - imageStartMs}ms: $composableFqn")
                    val heapAfter = Runtime.getRuntime().run { totalMemory() - freeMemory() }
                    TelemetryService.getInstance().record(
                        RenderSample(
                            inflateMs   = 0L, callbacksMs = 0L, renderMs = 0L, writeMs = 0L,
                            totalMs     = System.currentTimeMillis() - imageStartMs,
                            format      = outputFormat,
                            outcome     = RenderOutcome.FAIL,
                            heapBefore  = heapBefore,
                            heapAfter   = heapAfter,
                            gcDelta     = GcStats(0L, 0L),
                        )
                    )
                    return null
                }
            }
            runCatching { result.logger.missingClasses.takeIf { it.isNotEmpty() } }.getOrNull()
                ?.let { logWarn("render() missingClasses for $composableFqn: $it") }

            val image = result.renderedImage.copy
                ?: run {
                    logWarn("render() failed: rendered image is null for composable=$composableFqn")
                    logInfo("image failed in ${System.currentTimeMillis() - imageStartMs}ms: $composableFqn")
                    val heapAfter = Runtime.getRuntime().run { totalMemory() - freeMemory() }
                    TelemetryService.getInstance().record(
                        RenderSample(
                            inflateMs   = 0L, callbacksMs = 0L, renderMs = 0L, writeMs = 0L,
                            totalMs     = System.currentTimeMillis() - imageStartMs,
                            format      = outputFormat,
                            outcome     = RenderOutcome.FAIL,
                            heapBefore  = heapBefore,
                            heapAfter   = heapAfter,
                            gcDelta     = GcStats(0L, 0L),
                        )
                    )
                    return null
                }

            // Crop to the composable's measured size. ComposeViewAdapter uses wrap_content so it
            // measures to the composable's intrinsic size, not the full device canvas. rootViews
            // gives the ComposeViewAdapter's layout bounds (left/top/right/bottom) inside the image.
            // Skip cropping when showSystemUi=true — we want the full device frame.
            val skipCrop = previewConfig.useCustomConfig && previewConfig.showSystemUi
            val outputImage = if (skipCrop) {
                logInfo("render() skipCrop=true (useCustomConfig=true, showSystemUi=true) — using full image ${image.width}x${image.height} for $composableFqn")
                image
            } else {
                runCatching {
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
            }

            outFile.parentFile.mkdirs()
            writeImage(outputImage, outputFormat, pluginSettings.getState().jpegQuality, outFile)
            image.flush()
            val pngEndMs = System.currentTimeMillis()
            val heapAfter = Runtime.getRuntime().run { totalMemory() - freeMemory() }
            val gcAfter   = readGcStats()
            TelemetryService.getInstance().record(
                RenderSample(
                    inflateMs   = inflateEndMs - imageStartMs,
                    callbacksMs = callbacksEndMs - inflateEndMs,
                    renderMs    = renderEndMs - callbacksEndMs,
                    writeMs     = pngEndMs - renderEndMs,
                    totalMs     = pngEndMs - imageStartMs,
                    format      = outputFormat,
                    outcome     = RenderOutcome.SUCCESS,
                    heapBefore  = heapBefore,
                    heapAfter   = heapAfter,
                    gcDelta     = gcAfter - gcBefore,
                )
            )
            val stateTag = if (stateIndex >= 0) " stateIndex=$stateIndex" else ""
            logInfo("steps inflate=${inflateEndMs - imageStartMs}ms callbacks=${callbacksEndMs - inflateEndMs}ms render=${renderEndMs - callbacksEndMs}ms write=${pngEndMs - renderEndMs}ms total=${pngEndMs - imageStartMs}ms fqn=$composableFqn$stateTag format=${outputFormat.name}")
            logInfo("render() succeeded for composable=$composableFqn -> ${outFile.absolutePath}")
            outFile.absolutePath
        } catch (e: Exception) {
            logError("render() failed in ${System.currentTimeMillis() - imageStartMs}ms: exception during rendering of composable=$composableFqn", e)
            null
        } finally {
            task.dispose()
            renderService.dispose()
        }
    }

    private fun resolveModuleCached(
        project: Project,
        modulePath: String,
        sourceFilePath: String?,
        logInfo: (String) -> Unit,
        logWarn: (String) -> Unit,
    ): ModuleCacheEntry? {
        val key = ModuleCacheKey(modulePath, sourceFilePath)
        moduleCache[key]?.let { logInfo("render() module cache hit for modulePath=$modulePath"); return it }

        val allModules = ModuleManager.getInstance(project).modules

        val sourceOwnerModule = if (sourceFilePath != null) {
            val sourceSetSegments = setOf("main", "test", "unitTest", "androidTest")
            val candidate = allModules
                .filter { m ->
                    val seg = m.name.substringAfterLast('.')
                    seg !in sourceSetSegments && !seg.startsWith("screenshotTest")
                }
                .flatMap { m ->
                    ModuleRootManager.getInstance(m).contentRoots
                        .filter { root -> sourceFilePath.startsWith(root.path + "/") || sourceFilePath == root.path }
                        .map { root -> m to root.path.length }
                }
                .maxByOrNull { (_, len) -> len }
                ?.first
            when {
                candidate == null -> { logWarn("render() no module owns sourceFilePath=$sourceFilePath, falling back to modulePath"); null }
                AndroidFacet.getInstance(candidate) == null -> { logInfo("render() ${candidate.name} has no AndroidFacet (root/holder module); falling back to modulePath"); null }
                AndroidFacet.getInstance(candidate)?.configuration?.isLibraryProject == true -> { logInfo("render() ${candidate.name} is a library module; using app module from modulePath for rendering"); null }
                else -> { logInfo("render() resolved owner app module=${candidate.name} for sourceFilePath=$sourceFilePath"); candidate }
            }
        } else null

        val appRootModule = sourceOwnerModule
            ?: allModules.firstOrNull { m -> ModuleRootManager.getInstance(m).contentRoots.any { root -> root.path == modulePath } }
            ?: run { logWarn("render() failed: no module found matching path=$modulePath. Available modules: ${allModules.map { it.name }}"); return null }

        val module = allModules.firstOrNull { m -> m.name == "${appRootModule.name}.main" }
            ?.also { logInfo("render() using .main source-set module=${it.name} to avoid holder-module ambiguity") }
            ?: appRootModule

        val facet = AndroidFacet.getInstance(module)
            ?: run { logWarn("render() failed: no AndroidFacet for module=${module.name}"); return null }

        val lfs = LocalFileSystem.getInstance()
        val configVf = (sourceFilePath?.let { lfs.findFileByPath(it) }
            ?: lfs.findFileByPath("$modulePath/src/main/AndroidManifest.xml")
            ?: lfs.findFileByPath(modulePath))
            ?: run { logWarn("render() failed: could not find VirtualFile for modulePath=$modulePath"); return null }
        logInfo("render() using configVf=${configVf.path} for ConfigurationManager")

        return ModuleCacheEntry(module, facet, configVf).also { moduleCache[key] = it }
    }

    // ComposeViewAdapter splits tools:composableName on the last '.' to get (className, methodName).
    // For top-level Kotlin functions the "class" part is just the package, which is not loadable.
    // This resolves the actual file-facade class via PSI so the name becomes e.g.
    // "com.example.StartupScreenKt.TestComposable" instead of "com.example.TestComposable".
    private fun resolveComposableNameForLayoutlib(project: Project, composableFqn: String): String {
        fqnCache[composableFqn]?.let { return it }

        val classPart = composableFqn.substringBeforeLast('.', missingDelimiterValue = "")
        val methodName = composableFqn.substringAfterLast('.')
        if (classPart.isEmpty()) return composableFqn

        val resolved = try {
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

        fqnCache[composableFqn] = resolved
        return resolved
    }

    private fun readGcStats(): GcStats {
        var count = 0L
        var timeMs = 0L
        for (bean in java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            val c = bean.collectionCount
            val t = bean.collectionTime
            if (c >= 0) count  += c
            if (t >= 0) timeMs += t
        }
        return GcStats(count, timeMs)
    }

    private fun writeImage(image: BufferedImage, format: OutputFormat, jpegQuality: Int, outFile: File) {
        if (format == OutputFormat.JPEG) {
            val writer = ImageIO.getImageWritersByFormatName("JPEG").next()
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = jpegQuality / 100f
            }
            ImageIO.createImageOutputStream(outFile).use { ios ->
                writer.output = ios
                // JPEG doesn't support alpha; convert to RGB first
                val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
                rgb.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
                writer.write(null, IIOImage(rgb, null, null), param)
                writer.dispose()
                rgb.flush()
            }
        } else {
            ImageIO.write(image, format.imageIoName, outFile)
        }
    }
}
