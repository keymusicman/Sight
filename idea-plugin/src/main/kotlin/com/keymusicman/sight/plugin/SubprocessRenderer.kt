package com.keymusicman.sight.plugin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.keymusicman.sight.ipc.Outcome
import com.keymusicman.sight.ipc.RenderRequest
import com.keymusicman.sight.ipc.WorkerInit
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Subprocess-isolated drop-in replacement for [ComposableRenderer.render].
 *
 * **Why:** Layoutlib accumulates >5 GB of native memory in-process (see
 * `idea-plugin/COMPOSABLE_RENDERING.md` § "Native memory leak investigation"). Hosting
 * Layoutlib in a short-lived worker JVM lets the OS reclaim that memory when the worker
 * exits.
 *
 * **Pooling:** one [SubprocessRendererClient] per `modulePath`, reused across renders so
 * worker startup cost (Layoutlib init ≈ 1–2 s) is amortized. After [RECYCLE_AFTER_RENDERS]
 * renders the client is closed and the next render lazily spawns a fresh worker — this
 * caps the worker's resident memory.
 *
 * **Signature parity:** matches [ComposableRenderer.render] so call sites can switch via
 * a single boolean. The `useSimpleLayout` flag is accepted for parity but ignored — the
 * worker always renders through `ComposeViewAdapter`.
 */
object SubprocessRenderer {

    private val log = Logger.getInstance(SubprocessRenderer::class.java)

    /** Per-render watchdog. Matches the in-process renderer's 30 s timeouts at each phase. */
    private val RENDER_TIMEOUT = Duration.ofSeconds(60)

    /** Recycle the worker after this many renders to bound resident memory. Tunable. */
    private const val RECYCLE_AFTER_RENDERS = 50

    /**
     * A pooled worker plus the fingerprint of the compiled output it was started against.
     * [cheapStamp]/[contentHash] form the baseline for [decideStaleness]: when the module is
     * rebuilt, the worker's [URLClassLoader][java.net.URLClassLoader] still serves the old
     * bytecode, so we detect the change and respawn instead. [cheapStamp] is `var` so a no-op
     * rebuild can refresh the gate without re-hashing on every subsequent render.
     */
    private data class ClientEntry(
        val client: SubprocessRendererClient,
        var renders: Int,
        val classpath: List<String>,
        var cheapStamp: Long,
        val contentHash: String,
    )

    private val clients = ConcurrentHashMap<String, ClientEntry>()

    fun render(
        project: Project,
        modulePath: String,
        composableFqn: String,
        parameterProviderFqn: String? = null,
        stateIndex: Int = -1,
        sourceFilePath: String? = null,
        onLog: ((String) -> Unit)? = null,
        @Suppress("UNUSED_PARAMETER") useSimpleLayout: Boolean = false,
        previewConfig: PreviewRenderConfig = PreviewRenderConfig(),
    ): String? {
        fun logInfo(msg: String) { log.info(msg); onLog?.invoke("[INFO] $msg") }
        fun logWarn(msg: String, e: Throwable? = null) {
            if (e != null) log.warn(msg, e) else log.warn(msg)
            onLog?.invoke("[WARN] $msg${e?.let { "\n  ${it.message}" } ?: ""}")
        }
        fun logError(msg: String, e: Throwable? = null) {
            if (e != null) log.error(msg, e) else log.error(msg)
            onLog?.invoke("[ERROR] $msg${e?.let { "\n  ${it.message}" } ?: ""}")
        }

        val pluginSettings = PluginSettingsService.getInstance()
        val state = pluginSettings.getState()
        val outputFormat = state.outputFormat
        val outFile = PreviewCache.expectedFile(modulePath, composableFqn, stateIndex, outputFormat)

        // Always exercise the incremental skip BEFORE spawning anything.
        if (shouldSkipIncrementalRender(outFile, sourceFilePath, state.incrementalRendering)) {
            logInfo("subprocess render skipped (incremental): $composableFqn -> ${outFile.absolutePath}")
            TelemetryService.getInstance().recordSkip()
            return outFile.absolutePath
        }

        var entry = try {
            clientFor(project, modulePath)
        } catch (e: Throwable) {
            logError("subprocess render failed: could not start worker for module=$modulePath", e)
            return null
        }

        // A pooled worker loaded the user's classes into a URLClassLoader at startup; the JVM never
        // reloads an already-loaded class. So if the module was rebuilt since the worker started,
        // recycle it — otherwise a newly added composable fails with NoSuchMethodException. The
        // cheap stat-only gate runs every render; the content hash only when that gate moves.
        val decision = decideStaleness(
            storedCheapStamp = entry.cheapStamp,
            storedContentHash = entry.contentHash,
            currentCheapStamp = ClasspathFingerprint.cheapStamp(entry.classpath),
            currentContentHash = { ClasspathFingerprint.contentHash(entry.classpath) },
        )
        when (decision) {
            is StalenessDecision.Fresh -> {}
            is StalenessDecision.NoOpRefresh -> {
                entry.cheapStamp = decision.newCheapStamp
                logInfo("subprocess: $modulePath output touched but bytecode unchanged — keeping worker")
            }
            is StalenessDecision.Recycle -> {
                logInfo("subprocess: $modulePath bytecode changed since worker started — recycling worker to pick up new classes")
                recycle(modulePath)
                entry = try {
                    clientFor(project, modulePath)
                } catch (e: Throwable) {
                    logError("subprocess render failed: could not restart worker for module=$modulePath", e)
                    return null
                }
            }
        }

        // Resolve top-level composable FQNs to their file-facade class form (e.g.
        // "pkg.SetPostcodePreview" -> "pkg.SetPostcodeKt.SetPostcodePreview"). The worker's
        // ComposeViewAdapter splits tools:composableName on the last '.' and loads the class
        // part; for a top-level function that part is just the package and fails with
        // ClassNotFoundException. The worker JVM has no PSI access, so this MUST happen here in
        // the plugin process — mirrors the in-process ComposableRenderer path. Cache keys above
        // intentionally use the original FQN to stay consistent with the in-process renderer.
        val resolvedFqn = ComposableRenderer.resolveComposableNameForLayoutlib(project, composableFqn)
        if (resolvedFqn != composableFqn) {
            logInfo("subprocess render() resolved top-level FQN: $composableFqn → $resolvedFqn")
        }

        // Resolve the device's real pixel size + density plugin-side (the worker has no SDK device
        // list), mirroring the in-process ComposableRenderer's Configuration. Without this the
        // worker rendered every device at the custom dp values + a hardcoded 420 dpi — e.g. pixel_5
        // came out 945×1680 instead of 1080×2340.
        val deviceSpec = resolveDeviceRenderSpec(previewConfig) { deviceId ->
            ComposableRenderer.deviceScreenDims(project, modulePath, sourceFilePath, deviceId)
        }
        // Mirror the in-process Configuration: when useCustomConfig=false the in-process path resets
        // night mode / fontScale / locale / system UI to neutral defaults, so the worker must too.
        // Without this, a persisted custom value (e.g. fontScale=2.0) leaked into default renders and
        // the worker drew text at 2× while Android Studio drew it at 1×.
        val effective = resolveEffectivePreviewParams(previewConfig)
        logInfo(
            "subprocess render() device=${previewConfig.deviceId} useCustomConfig=${previewConfig.useCustomConfig} " +
                "-> ${deviceSpec.widthPx}x${deviceSpec.heightPx}px @ ${deviceSpec.densityDpi}dpi " +
                "fontScale=${effective.fontScale} nightMode=${effective.nightMode} " +
                "locale='${effective.locale}' showSystemUi=${effective.showSystemUi}"
        )

        val req = RenderRequest(
            requestId            = entry.client.nextRequestId(),
            composableFqn        = resolvedFqn,
            parameterProviderFqn = parameterProviderFqn,
            stateIndex           = stateIndex,
            outputPath           = outFile.absolutePath,
            outputFormat         = outputFormat.name,
            jpegQuality          = state.jpegQuality,
            widthPx              = deviceSpec.widthPx,
            heightPx             = deviceSpec.heightPx,
            density              = deviceSpec.densityDpi,
            nightMode            = effective.nightMode,
            fontScale            = effective.fontScale,
            locale               = effective.locale,
            showSystemUi         = effective.showSystemUi,
        )
        outFile.parentFile.mkdirs()

        logInfo(
            "subprocess render() composable=$composableFqn stateIndex=$stateIndex " +
                "request=${req.requestId} modulePath=$modulePath"
        )
        val resp = entry.client.render(req, RENDER_TIMEOUT)
        TelemetryService.getInstance().record(
            RenderSample(
                inflateMs   = 0L,
                callbacksMs = 0L,
                renderMs    = resp.durationMs,
                writeMs     = 0L,
                totalMs     = resp.durationMs,
                format      = outputFormat,
                outcome     = if (resp.outcome == Outcome.SUCCESS) RenderOutcome.SUCCESS else RenderOutcome.FAIL,
                heapBefore  = 0L,
                heapAfter   = 0L,
                gcDelta     = GcStats(0L, 0L),
            )
        )
        entry.renders++
        if (entry.renders >= RECYCLE_AFTER_RENDERS) {
            logInfo("subprocess: recycling client for $modulePath after ${entry.renders} renders")
            recycle(modulePath)
        }

        return when {
            resp.outcome == Outcome.SUCCESS -> {
                logInfo(
                    "subprocess render succeeded: $composableFqn -> ${resp.outputPath} " +
                        "(${resp.durationMs}ms)"
                )
                resp.outputPath
            }
            // Multi-state termination signal — mirrors ComposableRenderer's behavior when the
            // parameter provider runs out of states (Sequence doesn't contain element).
            resp.outcome == Outcome.FAIL && resp.providerExhausted -> {
                logInfo(
                    "subprocess render stopping multi-state loop: provider exhausted at " +
                        "stateIndex=$stateIndex for $composableFqn"
                )
                null
            }
            else -> {
                logWarn(
                    "subprocess render failed for $composableFqn stateIndex=$stateIndex: " +
                        "${resp.errorClass}: ${resp.errorMessage}"
                )
                null
            }
        }
    }

    /**
     * Closes every worker. Call from plugin unload to prevent leaked worker processes from
     * lingering after the IDE plugin is disabled / updated.
     */
    fun shutdownAll() {
        val snapshot = clients.values.toList()
        clients.clear()
        snapshot.forEach { runCatching { it.client.close() } }
    }

    private fun clientFor(project: Project, modulePath: String): ClientEntry =
        clients.getOrPut(modulePath) {
            val userCp = UserModuleClasspathResolver.resolve(project, modulePath)
            val userRes = UserResourceResolver.resolve(project, modulePath)
            val init = WorkerInit(
                androidStudioRoot = WorkerClasspathAssembler.androidStudioRoot().absolutePath,
                userClasspath = userCp,
                // Studio Quail's Layoutlib reads ro.build.version.sdk_full from build.prop during
                // Build.VERSION.<clinit>; android-34/build.prop predates that field, so init throws
                // and the JVM caches the failure for the worker's lifetime — every render then fails.
                // android-36+ build.prop carries sdk_full.
                targetApiLevel = 36,
                userResDirs = userRes.resDirs,
                rJarPaths = userRes.rJarPaths,
            )
            val client = SubprocessRendererClient(init, WorkerClasspathAssembler.assemble())
            client.start()
            log.info("subprocess: started worker for modulePath=$modulePath")
            // Capture the output fingerprint baseline NOW, before any later rebuild — see
            // decideStaleness. contentHash must be the pre-rebuild snapshot to detect a change.
            ClientEntry(
                client = client,
                renders = 0,
                classpath = userCp,
                cheapStamp = ClasspathFingerprint.cheapStamp(userCp),
                contentHash = ClasspathFingerprint.contentHash(userCp),
            )
        }

    private fun recycle(modulePath: String) {
        val entry = clients.remove(modulePath) ?: return
        runCatching { entry.client.close() }
            .onFailure { log.warn("subprocess: error closing recycled client for $modulePath", it) }
    }
}
