package com.keymusicman.appflowerplugin.renderworker.worker

import com.android.ide.common.rendering.api.AssetRepository
import com.android.ide.common.rendering.api.HardwareConfig
import com.android.ide.common.rendering.api.ILayoutLog
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.RenderSession
import com.android.ide.common.rendering.api.ResourceReference
import com.android.ide.common.rendering.api.SessionParams
import com.android.ide.common.resources.ResourceResolver
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.ide.common.resources.configuration.LocaleQualifier
import com.android.layoutlib.bridge.Bridge
import com.android.layoutlib.bridge.android.RenderParamsFlags
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
import java.util.concurrent.TimeUnit
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
    private val userResDirs: List<String> = emptyList(),
    private val rJarPaths: List<String> = emptyList(),
) {
    private val log: ILayoutLog = StdErrLayoutLog()

    private val idRegistry: ResourceIdRegistry by lazy {
        ResourceIdRegistry.fromJars(rJarPaths, userClassLoader).also {
            System.err.println("worker: ResourceIdRegistry loaded ${it.size()} user resource ids")
        }
    }
    private val userResources: UserResourceRepository by lazy { UserResourceRepository(userResDirs) }
    private val callback: WorkerLayoutlibCallback by lazy {
        WorkerLayoutlibCallback(userClassLoader, idRegistry)
    }
    // Log each unresolved (placeheld) resource once across the worker lifetime.
    private val loggedPlaceholders = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
        WorkerAssetRepository(frameworkJarPath)
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

            // THE blank-render fix. Layoutlib lays out the window's content frame
            // (android.R.id.content) with 0×0 LinearLayout params (width=0, height=0, weight=0)
            // when driven via raw Bridge.createSession the way we do — so with clipChildren=true the
            // entire app/Compose subtree (which itself measures to the full device size) is clipped
            // to nothing and only the DecorView background paints (uniform #FAFAFA). Studio's
            // RenderTask path gets a MATCH_PARENT content frame; we force the same here. Without
            // this, EVERY preview is blank regardless of content — even a plain View with a solid
            // background. See the long investigation in the subprocess-blank-render memory.
            forceContentFrameFill(session)

            // Seed the virtual clocks immediately after createSession, exactly as Android Studio's
            // RenderTask does, so Compose's MonotonicFrameClock ticks and the first-frame priming
            // draw runs (RenderSessionImpl only runs it when mElapsedFrameTimeNanos >= 0).
            session.setSystemBootTimeNanos(0L)
            session.setSystemTimeNanos(0L)
            session.setElapsedFrameTimeNanos(TimeUnit.MILLISECONDS.toNanos(500L))

            // Advance the frame clock so Compose's composition + effects run before we snapshot.
            // BridgeRenderSession.executeCallbacks() reads the *virtual* clock (System_Delegate,
            // driven by setSystemTimeNanos), not its nanos argument — so setSystemTimeNanos() must
            // precede each call, exactly as RenderTask.executeCallbacks() does.
            var nowNs = 0L
            var frames = 0
            session.setSystemTimeNanos(nowNs)
            var more = session.executeCallbacks(nowNs)
            frames++
            while ((more || frames < 5) && frames < 10) {
                nowNs += 16_666_666L
                session.setSystemTimeNanos(nowNs)
                more = session.executeCallbacks(nowNs)
                frames++
            }

            // Single render — createSession did NOT render (FLAG_DO_NOT_RENDER_ON_CREATE), so this is
            // the first and only session render ("never render twice" — the first consumes the canvas
            // state, the second comes back blank). forceMeasure=true lays out the now-populated
            // composition at the device size (matches RenderTask.render(forceMeasure)).
            val renderRes = session.render(true)
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
        // Size the root with EXACT dp dimensions matching the device size. The HardwareConfig
        // canvas is in pixels (req.widthPx/heightPx); the XML root must be in dp, so convert back
        // via the request density, rounding UP so the root never undershoots the canvas. With the
        // content frame forced to MATCH_PARENT (see forceContentFrameFill) the ComposeViewAdapter
        // then gets bounded constraints and the composition fills the device.
        // `tools:previewWidth/previewHeight` are NOT honored by this ComposeViewAdapter (1.10.x),
        // so explicit dp is required.
        val widthDp = pxToCeilDp(req.widthPx, req.density)
        val heightDp = pxToCeilDp(req.heightPx, req.density)
        return """
            <androidx.compose.ui.tooling.ComposeViewAdapter
                xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                android:layout_width="${widthDp}dp"
                android:layout_height="${heightDp}dp"
                tools:composableName="${req.composableFqn}"$providerAttr />
        """.trimIndent()
    }

    private fun buildSessionParams(req: RenderRequest, xml: String): SessionParams {
        val parser = WorkerPullParser(xml)

        // The request already carries the device size in pixels (resolved plugin-side from the
        // selected device), so the canvas is those pixels verbatim — no DP->PX conversion here.
        val densityDpi = req.density.takeIf { it > 0 } ?: Density.DEFAULT_DENSITY
        val widthPx = req.widthPx
        val heightPx = req.heightPx

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
        val frameworkMap = framework.configuredFor(folderConfig)
        val userMap = userResources.configuredFor(folderConfig)
        val combinedMap = ResourceMapMerger.merge(
            framework = frameworkMap,
            user = userMap,
            declaredRefs = idRegistry.declaredRefs(),
        ) { ref ->
            val key = "${ref.resourceType.getName()}/${ref.name}"
            if (loggedPlaceholders.add(key)) {
                System.err.println("worker: unresolved resource $key — using placeholder")
            }
        }
        // Make user fonts loadable through androidx ResourcesCompat (see [fontValueForResourcesCompat]:
        // it only loads font values that start with "res/", but our user repo resolves fonts to
        // absolute on-disk paths). Without this every Compose `Font(R.font.x)` falls back to Roboto.
        combinedMap[ResourceNamespace.RES_AUTO]?.get(ResourceType.FONT)?.let { fontMap ->
            for (v in fontMap.values().toList()) {
                val value = v.value ?: continue
                val rewritten = fontValueForResourcesCompat(value)
                if (rewritten != value) {
                    fontMap.put(
                        com.android.ide.common.rendering.api.ResourceValueImpl(
                            v.namespace, v.resourceType, v.name, rewritten,
                        )
                    )
                }
            }
        }
        val themeRef = ResourceReference(
            ResourceNamespace.ANDROID,
            ResourceType.STYLE,
            "Theme.Material.Light.NoActionBar",
        )
        val resources = ResourceResolver.create(combinedMap, themeRef)
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

        // Match Android Studio's RenderTask flags. The first two are load-bearing for getting any
        // pixels at all out of a raw-Bridge render:
        //  - FLAG_DO_NOT_RENDER_ON_CREATE: Bridge.createSession() otherwise calls render(true)
        //    internally, BEFORE we can seed the frame clock — and a second render() then comes back
        //    blank ("the first render consumes the canvas state", per COMPOSABLE_RENDERING.md). With
        //    this flag, createSession only inflates; our single render() below is the first & only one.
        //  - FLAG_KEY_DISABLE_BITMAP_CACHING: forces disposeImageSurface()+renderer.setup() each
        //    render so a fresh ImageReader is created; without it the cached reader can't re-acquire
        //    ("Unable to acquire a buffer item") and the readback is a stale/empty frame.
        params.setFlag(RenderParamsFlags.FLAG_DO_NOT_RENDER_ON_CREATE, true)
        params.setFlag(RenderParamsFlags.FLAG_KEY_DISABLE_BITMAP_CACHING, true)
        params.setFlag(RenderParamsFlags.FLAG_KEY_RESULT_IMAGE_AUTO_SCALE, true)

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

    /**
     * Forces the window's content frame ([android.R.id.content]) to MATCH_PARENT. Layoutlib, when
     * driven via raw `Bridge.createSession`, lays this frame out with degenerate 0×0 LinearLayout
     * params (width=0, height=0, weight=0); with `clipChildren=true` that clips the entire app /
     * Compose subtree to nothing, so only the DecorView background paints and every preview is
     * blank. Setting it to MATCH_PARENT (weight 1 within the decor LinearLayout) restores a
     * full-size content frame. Must run after `createSession` (the view tree exists) and before the
     * render; `requestLayout` lets the subsequent forced-measure render lay it out at full size.
     */
    private fun forceContentFrameFill(session: RenderSession) {
        runCatching {
            var top = session.rootViews?.firstOrNull()?.viewObject as? android.view.View ?: return
            while ((top.parent as? android.view.View) != null) top = top.parent as android.view.View
            val content = top.findViewById<android.view.View>(android.R.id.content) ?: return
            val lp = content.layoutParams ?: return
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            runCatching { lp.javaClass.getField("weight").setFloat(lp, 1f) }
            content.layoutParams = lp
            top.requestLayout()
        }.onFailure { System.err.println("worker: forceContentFrameFill failed: ${it.message}") }
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

// android.util.DisplayMetrics.DENSITY_DEFAULT (mdpi). Inlined to keep this helper pure/testable.
private const val MDPI = 160

/**
 * Converts a pixel dimension to dp at [densityDpi], rounding **up**. Used to size the
 * ComposeViewAdapter root in dp so it is never smaller than the pixel canvas (an undersized root
 * would let the composition shrink below the device size). The output image is the HardwareConfig
 * canvas (exact pixels) regardless, so over-rounding the root by a fraction of a dp is harmless.
 */
internal fun pxToCeilDp(px: Int, densityDpi: Int): Int {
    val dpi = if (densityDpi > 0) densityDpi else MDPI
    return Math.ceil(px.toDouble() * MDPI / dpi).toInt()
}

