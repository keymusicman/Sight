package com.keymusicman.appflowerplugin.appflowerplugin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.keymusicman.appflowerplugin.ipc.Outcome
import com.keymusicman.appflowerplugin.ipc.RenderRequest
import com.keymusicman.appflowerplugin.ipc.WorkerInit
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

    private data class ClientEntry(val client: SubprocessRendererClient, var renders: Int)

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

        val entry = try {
            clientFor(project, modulePath)
        } catch (e: Throwable) {
            logError("subprocess render failed: could not start worker for module=$modulePath", e)
            return null
        }

        val req = RenderRequest(
            requestId            = entry.client.nextRequestId(),
            composableFqn        = composableFqn,
            parameterProviderFqn = parameterProviderFqn,
            stateIndex           = stateIndex,
            outputPath           = outFile.absolutePath,
            outputFormat         = outputFormat.name,
            jpegQuality          = state.jpegQuality,
            widthDp              = previewConfig.customWidthDp,
            heightDp             = previewConfig.customHeightDp,
            // TODO(density): the in-process path derives density from the Configuration object,
            // which depends on the resolved Device. The subprocess path has no Configuration
            // (the worker doesn't go through ConfigurationManager). 420 dpi matches pixel_5's
            // xxhdpi bucket — the default device. If the user picks a different preset
            // (e.g. pixel_tablet at xhdpi) the rendered size will be off. To fix properly we
            // should either: (a) extend RenderRequest with a precomputed density derived from
            // deviceId here, or (b) resolve density in the worker once we wire devices into
            // the worker's classpath. Flagged in Task 11 notes.
            density              = 420,
            nightMode            = previewConfig.uiMode == PreviewUiMode.DARK,
            fontScale            = previewConfig.fontScale,
            locale               = previewConfig.locale,
            showSystemUi         = previewConfig.useCustomConfig && previewConfig.showSystemUi,
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
            val init = WorkerInit(
                androidStudioRoot = WorkerClasspathAssembler.androidStudioRoot().absolutePath,
                userClasspath = userCp,
                targetApiLevel = 34,
            )
            val client = SubprocessRendererClient(init, WorkerClasspathAssembler.assemble())
            client.start()
            log.info("subprocess: started worker for modulePath=$modulePath")
            ClientEntry(client, 0)
        }

    private fun recycle(modulePath: String) {
        val entry = clients.remove(modulePath) ?: return
        runCatching { entry.client.close() }
            .onFailure { log.warn("subprocess: error closing recycled client for $modulePath", it) }
    }
}
