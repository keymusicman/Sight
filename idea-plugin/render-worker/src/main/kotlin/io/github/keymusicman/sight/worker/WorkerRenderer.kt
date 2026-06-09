package io.github.keymusicman.sight.worker

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
import io.github.keymusicman.sight.ipc.Outcome
import io.github.keymusicman.sight.ipc.RenderRequest
import io.github.keymusicman.sight.ipc.RenderResponse
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
    private val log = StdErrLayoutLog()

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
        populateBuildStub(userClassLoader)
        log.resetProviderExhausted()
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
            forceSystemBarsRelayout(session, req.widthPx)

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
            //
            // Compose ModalBottomSheet / Dialog / Popup render in their OWN windows, added to the
            // WindowManager during composition (i.e. from inside this executeCallbacks loop). Each such
            // window gets the same degenerate 0×0 `android.R.id.content` frame from the raw
            // Bridge.createSession layout pass that forceContentFrameFill fixes for the main window —
            // but forceContentFrameFill ran before composition, so it never saw them. Left at 0×0 the
            // popup's whole subtree is clipped to nothing and it paints blank, even though the main
            // window renders fine.
            //
            // We must expand a popup's content frame BEFORE the composition inside it settles its layout
            // and enter-position, not just before render(): the sheet anchors/offset are derived from the
            // container size, so if the popup is 0×0 while it composes, the sheet resolves to a 0-size /
            // off-screen rest position that a later resize doesn't recompute. So: fill content frames on
            // EVERY frame (cheap — fillContentFrame no-ops once a window is already MATCH_PARENT), and
            // when a new window appears, grant extra frames so its composition lays out + settles at the
            // corrected size before we snapshot.
            var nowNs = 0L
            var frames = 0
            var windowCount = forceAllWindowContentFramesFill()
            var settleFrames = 0
            session.setSystemTimeNanos(nowNs)
            var more = session.executeCallbacks(nowNs)
            frames++
            while ((more || frames < 5 || settleFrames > 0) && frames < 30) {
                nowNs += 16_666_666L
                session.setSystemTimeNanos(nowNs)
                val n = forceAllWindowContentFramesFill()
                if (n > windowCount) {
                    // A popup/sheet/dialog window just appeared — give it a settle budget so its
                    // composition re-lays-out and any enter animation finishes at the corrected size.
                    windowCount = n
                    settleFrames = 6
                } else if (settleFrames > 0) {
                    settleFrames--
                }
                more = session.executeCallbacks(nowNs)
                frames++
            }
            forceAllWindowContentFramesFill()

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
            // ComposeViewAdapter can LOG "Sequence doesn't contain element" for an out-of-range
            // PreviewParameterProvider index while still producing a (duplicate) frame, instead of
            // failing the render. The in-process ComposableRenderer treats that logged sentinel as
            // the multi-state loop's stop signal; mirror it here so we don't write a duplicate image
            // (the "14 rendered for 7 states" bug). Must be checked after the executeCallbacks loop,
            // since the provider is read during composition.
            if (log.sawProviderExhausted) {
                return RenderResponse(
                    requestId = req.requestId,
                    outcome = Outcome.FAIL,
                    durationMs = System.currentTimeMillis() - startMs,
                    errorMessage = "provider exhausted (logged ${StdErrLayoutLog.PROVIDER_EXHAUSTED_MARKER})",
                    providerExhausted = true,
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
            // Match the night bit set on params.uiMode below. Without a NightModeQualifier on the
            // folder config, qualified resources (values-night themes/colors) are always resolved as
            // notnight — so an app/Material theme whose dark variant lives in values-night rendered
            // light even with nightMode=true, while Compose's isSystemInDarkTheme() (which reads the
            // uiMode) said dark. The in-process renderer avoids this because Studio's
            // Configuration.setNightMode updates both the qualifier and the uiMode; mirror it here.
            nightModeQualifier = com.android.ide.common.resources.configuration.NightModeQualifier(
                if (req.nightMode) com.android.resources.NightMode.NIGHT
                else com.android.resources.NightMode.NOTNIGHT
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
        // Gesture-nav bottom inset. We render the gesture pill (FLAG_KEY_USE_GESTURE_NAV), but
        // Layoutlib still reserves the bottom inset from the framework `navigation_bar_height`
        // (48dp, the 3-button default) — `navigation_bar_frame_height` is `@dimen/navigation_bar_height`.
        // That pushed app content up by the extra 24dp vs Android Studio (which reserves the 24dp
        // gesture inset). Override both to the gesture height so the content frame matches.
        if (req.showSystemUi) {
            combinedMap[ResourceNamespace.ANDROID]?.get(ResourceType.DIMEN)?.let { d ->
                for (name in listOf("navigation_bar_frame_height", "navigation_bar_height")) {
                    d.put(com.android.ide.common.rendering.api.ResourceValueImpl(
                        ResourceNamespace.ANDROID, ResourceType.DIMEN, name, "${GESTURE_NAV_BAR_HEIGHT_DP}dp"))
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
        if (!req.showSystemUi) {
            params.setForceNoDecor()
        } else {
            // Layoutlib's Layout$Builder gates the bottom gesture-nav pill on this flag; without it
            // the worker drew the status bar but no navigation bar (Studio's RenderTask sets it).
            params.setFlag(RenderParamsFlags.FLAG_KEY_USE_GESTURE_NAV, true)
        }

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

    /**
     * Populates the `android.os.Build` stub that the user app's compile-SDK `android.jar` puts on
     * the worker's user classloader. Layoutlib/Studio populate these from the platform; the
     * standalone worker doesn't, so the stub leaves `SDK_INT = 0` and every `String` field null.
     *
     * Why it matters:
     *  - `SDK_INT = 0` made Compose skip the variable-font weight axis (`Font(…, variationSettings)`
     *    is only applied when `SDK_INT >= 26`), so Inter rendered at its default weight instead of the
     *    requested W500/W600 — text came out narrower than Android Studio.
     *  - Null `Build.FINGERPRINT` (and similar) crash the `<clinit>` of classes reached once SDK_INT
     *    is non-trivial (`NullPointerException` in `FINGERPRINT.toLowerCase()`), blanking the render.
     *
     * SDK_INT is set to 36 to match the framework the worker renders against; the API-29 font loader
     * that this enables opens fonts via `AssetManager`, which [WorkerAssetRepository] now serves
     * (including the `res/`-rewritten font values).
     *
     * Fields are `static final`, so they're written via `sun.misc.Unsafe` (the worker is launched
     * with the matching `--add-opens`). Runs once per worker; failures are non-fatal.
     */
    private var buildStubPopulated = false
    private fun populateBuildStub(cl: ClassLoader) {
        if (buildStubPopulated) return
        runCatching {
            val unsafeCls = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeCls.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val staticBase = unsafeCls.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
            val staticOff = unsafeCls.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
            val putInt = unsafeCls.getMethod("putInt", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val putObj = unsafeCls.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
            fun setInt(f: java.lang.reflect.Field, value: Int) =
                putInt.invoke(unsafe, staticBase.invoke(unsafe, f), staticOff.invoke(unsafe, f) as Long, value)
            fun setObj(f: java.lang.reflect.Field, value: Any?) =
                putObj.invoke(unsafe, staticBase.invoke(unsafe, f), staticOff.invoke(unsafe, f) as Long, value)

            val version = Class.forName("android.os.Build\$VERSION", true, cl)
            version.getDeclaredField("SDK_INT").let { if (it.getInt(null) < 36) setInt(it, 36) }
            version.declaredFields.firstOrNull { it.name == "RELEASE" }
                ?.let { it.isAccessible = true; if (it.get(null) == null) setObj(it, "14") }

            // Any null static String field on Build (FINGERPRINT, MANUFACTURER, MODEL, …) → a
            // non-null placeholder so framework/Compose <clinit>s that read them don't NPE.
            val build = Class.forName("android.os.Build", true, cl)
            for (f in build.declaredFields) {
                if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                if (f.type != String::class.java) continue
                f.isAccessible = true
                if (f.get(null) == null) setObj(f, "unknown")
            }
            buildStubPopulated = true
            System.err.println("worker: populated android.os.Build stub (SDK_INT=${version.getDeclaredField("SDK_INT").getInt(null)})")
        }.onFailure { System.err.println("worker: populateBuildStub failed: ${it::class.java.name}: ${it.message}") }
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
     * Re-measures and re-lays-out the system [StatusBar]/[NavigationBar] subtrees so their
     * content-sized children (the clock TextView, the battery ImageView) get real sizes.
     *
     * Layoutlib, driven via raw `Bridge.createSession`, performs its first layout pass at width 0
     * (the same degenerate pass behind the blank content-frame bug — see [forceContentFrameFill]).
     * That pass bakes each content child's resolved size into its layout params at 0 (a wrap_content
     * clock TextView -> lp 0×0, the battery ImageView -> lp 0×0) and caches their content-derived
     * metrics at zero too: the TextView's text `Layout` built at 0 width, the ImageView's
     * `mDrawableWidth/Height` from a drawable whose intrinsic size wasn't ready yet. The wifi icon
     * survives only because its explicit dp size baked to a real 57×62. `render(forceMeasure=true)`
     * later sets each bar's outer frame directly (window layout) but re-measures the children from
     * those baked-0 layout params, so the clock and battery stay 0×0 and never paint. `forceLayout()`
     * alone doesn't help (it doesn't invalidate the content caches), and measuring through the bar's
     * own `onMeasure` keeps returning 0 (it hands content children a degenerate zero-width spec).
     *
     * Fix: for each content child whose baked lp size is degenerate, re-set its content
     * (`setText`/`setImageDrawable`) to drop the stale cache, measure it **directly** with a generous
     * AT_MOST spec (bypassing the bar's degenerate child spec), then write the real measured pixel
     * size back into its layout params. `render` then measures those children to their fixed sizes,
     * exactly like the explicitly-sized wifi icon. The weighted spacer (a plain View) is left alone
     * so it still absorbs the remaining width. Must run after `createSession` and before `render`.
     */
    private fun forceSystemBarsRelayout(session: RenderSession, deviceWidthPx: Int) {
        runCatching {
            var top = session.rootViews?.firstOrNull()?.viewObject as? android.view.View ?: return
            while ((top.parent as? android.view.View) != null) top = top.parent as android.view.View
            val atMost = android.view.View.MeasureSpec.AT_MOST
            fun pinContentChild(v: android.view.View, barHeight: Int) {
                val lp = v.layoutParams ?: return
                // The degenerate createSession measure baked content children's layout params to 0px
                // (wifi survives because its dp size baked to a real 57x62). Only fix the content
                // views whose baked size is degenerate; leave the weighted spacer (a plain View).
                if (lp.width > 0 && lp.height > 0) return
                when (v) {
                    is android.widget.TextView -> v.text = v.text
                    is android.widget.ImageView -> v.drawable?.let { v.setImageDrawable(null); v.setImageDrawable(it) }
                    else -> return
                }
                v.forceLayout()
                v.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(deviceWidthPx, atMost),
                    android.view.View.MeasureSpec.makeMeasureSpec(barHeight, atMost),
                )
                if (v.measuredWidth > 0 && v.measuredHeight > 0) {
                    lp.width = v.measuredWidth
                    lp.height = v.measuredHeight
                    v.layoutParams = lp
                }
            }
            fun pinAll(v: android.view.View, barHeight: Int) {
                pinContentChild(v, barHeight)
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) pinAll(v.getChildAt(i), barHeight)
            }
            fun walk(v: android.view.View) {
                val cls = v.javaClass.name
                if (cls.endsWith("bars.StatusBar") || cls.endsWith("bars.NavigationBar")) {
                    val h = if (v.height > 0) v.height else v.layoutParams?.height ?: 0
                    if (h > 0) pinAll(v, h)
                } else if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(top)
        }.onFailure { System.err.println("worker: forceSystemBarsRelayout failed: ${it::class.java.name}: ${it.message}") }
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
            fillContentFrame(top)
        }.onFailure { System.err.println("worker: forceContentFrameFill failed: ${it.message}") }
    }

    /**
     * Applies [fillContentFrame] to **every** window registered with `WindowManagerGlobal`, not just
     * the main one. Compose `ModalBottomSheet` / `Dialog` / `Popup` each render in a separate window
     * that is added during composition; like the main window, the raw `Bridge.createSession` layout
     * pass leaves each one's `android.R.id.content` frame at 0×0, clipping the popup's content to
     * nothing (a full-size DecorView wrapping a 0×0 content frame). [forceContentFrameFill] runs
     * before composition and so only ever sees the main window; this pass runs after the
     * `executeCallbacks` loop, once the popup windows exist, and before `render`.
     */
    private fun forceAllWindowContentFramesFill(): Int {
        return runCatching {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val instance = wmg.getMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST")
            val views = wmg.getMethod("getWindowViews").invoke(instance) as List<android.view.View>
            for (v in views) {
                var top = v
                while ((top.parent as? android.view.View) != null) top = top.parent as android.view.View
                fillContentFrame(top)
            }
            views.size
        }.onFailure {
            System.err.println("worker: forceAllWindowContentFramesFill failed: ${it::class.java.name}: ${it.message}")
        }.getOrDefault(0)
    }

    /**
     * Forces the content frame ([android.R.id.content]) of the window rooted at [top] to
     * MATCH_PARENT (weight 1 within a decor LinearLayout). Layoutlib, when driven via raw
     * `Bridge.createSession`, lays this frame out with degenerate 0×0 params; with
     * `clipChildren=true` that clips the entire content subtree to nothing, so only the DecorView
     * background paints. `requestLayout` lets the subsequent forced-measure render lay it out full
     * size. Must run after the view tree exists and before the render.
     */
    private fun fillContentFrame(top: android.view.View) {
        val content = top.findViewById<android.view.View>(android.R.id.content) ?: return
        val lp = content.layoutParams ?: return
        if (lp.width == android.view.ViewGroup.LayoutParams.MATCH_PARENT &&
            lp.height == android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ) return
        lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        runCatching { lp.javaClass.getField("weight").setFloat(lp, 1f) }
        content.layoutParams = lp
        top.requestLayout()
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

// Bottom inset reserved for the gesture-nav pill (Android's gesture nav bar height). The framework
// default `@dimen/navigation_bar_height` is 48dp (the 3-button bar); when we draw the gesture pill
// (FLAG_KEY_USE_GESTURE_NAV) we override the nav-bar dimens to this so the content frame matches
// Android Studio's in-process render. See the override in [WorkerRenderer.buildSessionParams].
private const val GESTURE_NAV_BAR_HEIGHT_DP = 24

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

