package com.keymusicman.appflowerplugin.renderworker.worker

import com.android.ide.common.rendering.api.AssetRepository
import com.android.ide.common.rendering.api.HardwareConfig
import com.android.ide.common.rendering.api.ILayoutLog
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.ide.common.rendering.api.SessionParams
import com.android.ide.common.resources.ResourceResolver
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.ide.common.resources.configuration.LocaleQualifier
import com.android.layoutlib.bridge.Bridge
import com.android.resources.Density
import com.android.resources.ResourceType
import com.android.resources.ScreenOrientation
import com.android.resources.ScreenRound
import com.android.resources.ScreenSize
import com.android.resources.aar.FrameworkResourceRepository
import com.keymusicman.appflowerplugin.ipc.Outcome
import com.keymusicman.appflowerplugin.ipc.RenderRequest
import com.keymusicman.appflowerplugin.ipc.RenderResponse
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Per-render logic. Takes a [RenderRequest], builds [SessionParams] (direct
 * constructor — Layoutlib 2025.x has no `Builder`), wires a [FrameworkResourceRepository]
 * for theme resolution, advances the Compose frame clock, calls `session.render()`,
 * writes PNG/JPEG to [RenderRequest.outputPath], and returns a [RenderResponse].
 *
 * The framework resource repository is loaded lazily on first call and cached for
 * the lifetime of this renderer (it's expensive — many MB and tens of ms — but
 * immutable for our purposes).
 */
class WorkerRenderer(
    private val bridge: Bridge,
    private val userClassLoader: ClassLoader,
    private val androidStudioRoot: File,
) {
    private val callback = WorkerLayoutlibCallback(userClassLoader)
    private val log: ILayoutLog = StdErrLayoutLog()

    // Resolved together: the same jar feeds both the parsed-values repo (theme/style
    // resolution) and the raw-file asset repo (Resources.getAnimation, getDrawable, etc.
    // — Layoutlib's AssetManager calls AssetRepository.openNonAsset for these).
    private val frameworkJarPath: File by lazy {
        val bootstrap = LayoutlibBootstrap(androidStudioRoot, targetApiLevel = 36)
        val dataDir = bootstrap.locateLayoutlibDataDir()
            ?: error("Cannot locate layoutlib data dir under $androidStudioRoot")
        File(dataDir, "framework_res.jar").also {
            require(it.isFile) { "framework_res.jar not found at $it" }
        }
    }
    private val framework: FrameworkBundle by lazy { loadFramework() }
    private val assetRepository: AssetRepository by lazy {
        FrameworkAssetRepository(frameworkJarPath)
    }

    fun render(req: RenderRequest): RenderResponse {
        val startMs = System.currentTimeMillis()
        var session: com.android.ide.common.rendering.api.RenderSession? = null
        return try {
            val xml = buildComposeViewAdapterXml(req)
            val params = buildSessionParams(req, xml)
            session = bridge.createSession(params)
            val createRes = session.result
            if (!createRes.isSuccess) {
                val msg = createRes.errorMessage
                val providerExhausted = msg?.contains("Sequence doesn't contain element") == true
                createRes.exception?.let {
                    System.err.println("createSession failed for ${req.composableFqn}:")
                    it.printStackTrace(System.err)
                }
                return RenderResponse(
                    requestId = req.requestId,
                    outcome = Outcome.FAIL,
                    durationMs = System.currentTimeMillis() - startMs,
                    errorClass = createRes.exception?.javaClass?.name,
                    errorMessage = "createSession: $msg",
                    providerExhausted = providerExhausted,
                )
            }

            // Advance the frame clock so Compose's MonotonicFrameClock ticks and the
            // initial composition + any effects run before we snapshot. Mirrors the
            // spike + the in-IDE ComposableRenderer's strategy.
            var nowNs = System.nanoTime()
            var frames = 0
            var more = session.executeCallbacks(nowNs)
            frames++
            while ((more || frames < 5) && frames < 10) {
                nowNs += 16_666_666L
                more = session.executeCallbacks(nowNs)
                frames++
            }

            val renderRes = session.render()
            if (!renderRes.isSuccess) {
                val msg = renderRes.errorMessage
                val providerExhausted = msg?.contains("Sequence doesn't contain element") == true
                renderRes.exception?.let {
                    System.err.println("render() failed for ${req.composableFqn}:")
                    it.printStackTrace(System.err)
                }
                return RenderResponse(
                    requestId = req.requestId,
                    outcome = Outcome.FAIL,
                    durationMs = System.currentTimeMillis() - startMs,
                    errorClass = renderRes.exception?.javaClass?.name,
                    errorMessage = msg,
                    providerExhausted = providerExhausted,
                )
            }
            val image = session.image
                ?: return RenderResponse(
                    requestId = req.requestId,
                    outcome = Outcome.FAIL,
                    durationMs = System.currentTimeMillis() - startMs,
                    errorMessage = "session.image was null after successful render()",
                )

            writeImage(image, req)
            RenderResponse(
                requestId = req.requestId,
                outcome = Outcome.SUCCESS,
                outputPath = req.outputPath,
                durationMs = System.currentTimeMillis() - startMs,
            )
        } catch (e: Throwable) {
            System.err.println("render() threw for ${req.composableFqn}:")
            e.printStackTrace(System.err)
            RenderResponse(
                requestId = req.requestId,
                outcome = Outcome.FAIL,
                durationMs = System.currentTimeMillis() - startMs,
                errorClass = e.javaClass.name,
                errorMessage = e.message,
            )
        } finally {
            // Guard against the documented NPE when disposing a failed session.
            try { session?.dispose() } catch (_: Throwable) { }
        }
    }

    private fun buildComposeViewAdapterXml(req: RenderRequest): String {
        val providerAttr = if (req.parameterProviderFqn != null) buildString {
            append("\n    tools:parameterProviderClass=\"${req.parameterProviderFqn}\"")
            if (req.stateIndex >= 0) append("\n    tools:parameterProviderIndex=\"${req.stateIndex}\"")
        } else ""
        // Pass previewWidth/previewHeight so ComposeViewAdapter sizes the composition.
        // Without these, wrap_content can collapse to 0x0 and the rendered image is
        // blank (see SPIKE_NOTES "Result" — that exact bug bit the spike).
        val sizeAttr = "\n    tools:previewWidth=\"${req.widthDp}\"" +
            "\n    tools:previewHeight=\"${req.heightDp}\""
        return """
            <androidx.compose.ui.tooling.ComposeViewAdapter
                xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                tools:composableName="${req.composableFqn}"$providerAttr$sizeAttr />
        """.trimIndent()
    }

    private fun buildSessionParams(req: RenderRequest, xml: String): SessionParams {
        val parser = WorkerPullParser(xml)

        // Pixel sizing. widthDp/heightDp are in DP; convert to PX using the requested density.
        // Density.DEFAULT_DENSITY (mdpi = 160) is the divisor for the DP->PX formula.
        val densityDpi = req.density.takeIf { it > 0 } ?: Density.DEFAULT_DENSITY
        val widthPx = (req.widthDp.toLong() * densityDpi / Density.DEFAULT_DENSITY).toInt()
        val heightPx = (req.heightDp.toLong() * densityDpi / Density.DEFAULT_DENSITY).toInt()

        val hw = HardwareConfig(
            /* screenWidth     */ widthPx,
            /* screenHeight    */ heightPx,
            /* density         */ Density.create(densityDpi),
            /* xdpi            */ densityDpi.toFloat(),
            /* ydpi            */ densityDpi.toFloat(),
            /* screenSize      */ ScreenSize.NORMAL,
            /* orientation     */ if (heightPx >= widthPx) ScreenOrientation.PORTRAIT else ScreenOrientation.LANDSCAPE,
            /* screenRound     */ ScreenRound.NOTROUND,
            /* softwareButtons */ true,
        )

        // Resolve the theme via FrameworkResourceRepository. The folder config we build
        // affects which qualified resources are picked (e.g. night vs notnight values).
        val folderConfig = FolderConfiguration.createDefault().apply {
            if (req.locale.isNotBlank()) {
                LocaleQualifier.getQualifier(req.locale)?.let { localeQualifier = it }
            }
            densityQualifier = com.android.ide.common.resources.configuration.DensityQualifier(
                Density.create(densityDpi)
            )
        }
        val configuredMap = framework.configuredFor(folderConfig)
        val themeRef = ResourceReference(
            ResourceNamespace.ANDROID,
            ResourceType.STYLE,
            "Theme.Material.Light.NoActionBar",
        )
        val resources = ResourceResolver.create(configuredMap, themeRef)
        resources.setLogger(log)

        val params = SessionParams(
            /* layoutDescription */ parser,
            /* renderingMode     */ SessionParams.RenderingMode.NORMAL,
            /* projectKey        */ Any(),
            /* hardwareConfig    */ hw,
            /* renderResources   */ resources,
            /* layoutlibCallback */ callback,
            /* minSdkVersion     */ 21,
            /* targetSdkVersion  */ 34,
            /* log               */ log,
        )
        params.setAssetRepository(assetRepository)

        // showSystemUi=true => keep decor; showSystemUi=false => strip it.
        // setForceNoDecor() takes no arguments in this Layoutlib version (verified via
        // javap on RenderParams) — call it only when we want decor stripped.
        if (!req.showSystemUi) params.setForceNoDecor()

        // Locale BCP-47 (RenderParams.setLocale).
        if (req.locale.isNotBlank()) params.setLocale(req.locale)

        // Font scale (RenderParams.setFontScale) — verified available via javap.
        params.fontScale = req.fontScale

        // UI mode: Configuration.UI_MODE_TYPE_NORMAL (0x01) OR'd with the night bit.
        // UI_MODE_NIGHT_NO = 0x10, UI_MODE_NIGHT_YES = 0x20.
        val uiMode = 0x01 or (if (req.nightMode) 0x20 else 0x10)
        params.uiMode = uiMode

        return params
    }

    private fun writeImage(image: BufferedImage, req: RenderRequest) {
        val outFile = File(req.outputPath)
        outFile.parentFile?.mkdirs()
        when (req.outputFormat.uppercase()) {
            "PNG" -> ImageIO.write(image, "PNG", outFile)
            "JPEG", "JPG" -> {
                val writer = ImageIO.getImageWritersByFormatName("JPEG").next()
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = req.jpegQuality.coerceIn(1, 100) / 100f
                }
                ImageIO.createImageOutputStream(outFile).use { ios ->
                    writer.output = ios
                    // JPEG doesn't support alpha; flatten onto RGB first.
                    val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
                    rgb.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
                    writer.write(null, IIOImage(rgb, null, null), param)
                    writer.dispose()
                    rgb.flush()
                }
            }
            else -> error("unsupported output format: ${req.outputFormat}")
        }
    }

    // ---- Framework resource loading ----

    private fun loadFramework(): FrameworkBundle {
        val repo = FrameworkResourceRepository.create(
            frameworkJarPath.toPath(),
            /* languagesToLoad */ emptySet(),
            /* cachingData */ null,
            /* useCompiled9Patches */ false,
        )
        return FrameworkBundle(repo)
    }

    /** Wraps [FrameworkResourceRepository]; delegates configuration to [ConfiguredResources]. */
    private class FrameworkBundle(private val repo: FrameworkResourceRepository) {
        fun configuredFor(
            folderConfig: FolderConfiguration,
        ): Map<ResourceNamespace, Map<ResourceType, com.android.ide.common.resources.ResourceValueMap>> =
            ConfiguredResources.of(repo, folderConfig)
    }
}

/**
 * Serves files out of `framework_res.jar` for the `AssetManager.openNonAsset` path —
 * i.e. callers that go through the `Resources` → `AssetManager` API rather than the
 * `Resources_Delegate` shortcuts (e.g. raw resources, some drawable codepaths, fonts).
 *
 * NOTE: framework XML resources resolved via `Resources_Delegate.getAnimation` /
 * `getXml` / `getDrawable` do NOT go through here — they go through
 * `ResourceHelper.getXmlBlockParser` → `ParserFactory.create` →
 * `XmlParserFactory.createXmlParserForFile`, which we wire up in
 * [WorkerLayoutlibCallback.createXmlParserForFile]. This repository is the fallback
 * for the asset-table path.
 *
 * We don't filter by cookie: file lookup is read-only, so returning a stream when the
 * entry exists and null otherwise is safe regardless of which asset table the caller
 * thinks it's reading from. Project assets (cookie 0) simply won't match and fall through.
 */
private class FrameworkAssetRepository(jarFile: java.io.File) : AssetRepository() {
    private val jar = java.util.jar.JarFile(jarFile)

    override fun isSupported(): Boolean = true
    override fun openAsset(path: String?, mode: Int): java.io.InputStream? = null
    override fun openNonAsset(cookie: Int, path: String?, mode: Int): java.io.InputStream? {
        if (path.isNullOrEmpty()) return null
        // Layoutlib has passed paths both with and without the "res/" prefix across versions.
        jar.getJarEntry(path)?.let { return jar.getInputStream(it) }
        if (!path.startsWith("res/")) {
            jar.getJarEntry("res/$path")?.let { return jar.getInputStream(it) }
        }
        return null
    }
}

