package com.keymusicman.appflowerplugin.appflowerplugin

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import com.keymusicman.appflower.loader.GraphLoader
import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.ui.AppTheme
import com.keymusicman.appflower.ui.GraphPanel
import com.keymusicman.appflower.viewmodel.GraphViewModel
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class ModuleGraphPanel(
    private val project: Project,
    private val moduleInfo: GradleModuleInfo,
) : JPanel(BorderLayout()), Disposable {

    @Volatile private var disposed = false
    @Volatile private var lastGraphModifiedAt: Long = 0L
    private var watcherFuture: ScheduledFuture<*>? = null
    private lateinit var composePanel: ComposePanel

    @OptIn(ExperimentalComposeUiApi::class)
    override fun dispose() {
        disposed = true
        watcherFuture?.cancel(false)
        watcherFuture = null
        if (::composePanel.isInitialized) composePanel.dispose()
    }

    private val log = Logger.getInstance(ModuleGraphPanel::class.java)

    private val graphFile = File(moduleInfo.modulePath, "build/graph/app-graph.json")
    private val buildButton = JButton("Build graph").apply { addActionListener { runExportGraph() } }
    private val refreshButton = JButton("Refresh previews").apply { addActionListener { refreshPreviews() } }
    private val configButton = JButton("Configure…").apply {
        addActionListener {
            val parent = SwingUtilities.getWindowAncestor(this) as? java.awt.Frame
            val currentConfig = PreviewConfigService.getInstance(project).config
            PreviewConfigDialog(parent, currentConfig) { newConfig ->
                PreviewConfigService.getInstance(project).updateConfig(newConfig)
                refreshPreviews()
            }.isVisible = true
        }
    }
    private val statusLabel = JLabel()
    private val viewModel = GraphViewModel()

    init {
        add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(buildButton)
            add(refreshButton)
            add(configButton)
            add(statusLabel)
        }, BorderLayout.NORTH)

        composePanel = ComposePanel().apply {
            setContent {
                AppTheme(isDark = true) {
                    GraphPanel(
                        viewModel = viewModel,
                        onViewSource = { nodeId ->
                            viewModel.appGraphState.value?.let { appGraph ->
                                AppExecutorUtil.getAppExecutorService().submit {
                                    ReadAction.run<Throwable> {
                                        SourceNavigator.navigateToSource(
                                            project, nodeId, appGraph, moduleInfo.modulePath
                                        )
                                    }
                                }
                            }
                        },
                        onRefreshNode = onRefreshNode@{ nodeId ->
                            val currentGraph = viewModel.appGraphState.value ?: return@onRefreshNode
                            val colon = nodeId.indexOf(':')
                            if (colon < 0) return@onRefreshNode
                            val subgraphKey = nodeId.substring(0, colon)
                            val screenId = nodeId.substring(colon + 1)
                            val screen = currentGraph.subgraphs[subgraphKey]?.screens?.find { it.id == screenId }
                                ?: return@onRefreshNode
                            val fqn = screen.composable_fqn.takeIf { it.isNotBlank() } ?: return@onRefreshNode

                            val displayName = nodeId.substringAfterLast(':')
                            refreshButton.isEnabled = false
                            buildButton.isEnabled = false
                            configButton.isEnabled = false
                            statusLabel.text = "Rendering $displayName…"

                            AppExecutorUtil.getAppExecutorService().submit {
                                if (disposed) return@submit
                                val previewConfig = PreviewConfigService.getInstance(project).config
                                val providerFqn = screen.preview_provider_fqn
                                val sourceFilePath = screen.location.takeIf { it.isNotBlank() }?.let { loc ->
                                    java.io.File(moduleInfo.modulePath, loc).absolutePath
                                }

                                if (providerFqn == null) {
                                    PreviewCache.expectedFile(moduleInfo.modulePath, fqn).delete()
                                    runCatching {
                                        RendererRouter.render(
                                            project, moduleInfo.modulePath, fqn,
                                            sourceFilePath = sourceFilePath,
                                            previewConfig = previewConfig,
                                        )
                                    }.onFailure { e -> log.error("onRefreshNode() failed for nodeId=$nodeId", e) }
                                } else {
                                    var i = 0
                                    while (true) {
                                        val f = PreviewCache.expectedFile(moduleInfo.modulePath, fqn, i)
                                        if (!f.exists()) break
                                        f.delete()
                                        i++
                                    }
                                    var index = 0
                                    while (index < MAX_PREVIEW_STATES) {
                                        val path = runCatching {
                                            RendererRouter.render(
                                                project, moduleInfo.modulePath, fqn,
                                                parameterProviderFqn = providerFqn,
                                                stateIndex = index,
                                                sourceFilePath = sourceFilePath,
                                                previewConfig = previewConfig,
                                            )
                                        }.onFailure { e ->
                                            log.error("onRefreshNode() failed for nodeId=$nodeId index=$index", e)
                                        }.getOrNull()
                                        if (path == null) break
                                        index++
                                    }
                                }

                                viewModel.bumpNodeImageRevision(nodeId)

                                SwingUtilities.invokeLater {
                                    if (!disposed) {
                                        refreshButton.isEnabled = true
                                        buildButton.isEnabled = true
                                        configButton.isEnabled = true
                                        statusLabel.text = ""
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
        add(composePanel, BorderLayout.CENTER)

        refreshState()
        watcherFuture = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay(::checkGraphFileChanged, 2, 2, TimeUnit.SECONDS)
    }

    private fun checkGraphFileChanged() {
        if (disposed) return
        val ts = graphFile.lastModified()
        if (ts != 0L && ts != lastGraphModifiedAt) {
            lastGraphModifiedAt = ts
            refreshState()
        }
    }

    fun refreshState() {
        lastGraphModifiedAt = graphFile.lastModified()
        buildButton.isEnabled = false
        configButton.isEnabled = false
        statusLabel.text = "Loading…"

        AppExecutorUtil.getAppExecutorService().submit {
            if (disposed) return@submit
            val appGraph = if (graphFile.exists()) GraphLoader.loadFromFile(graphFile) else null

            if (appGraph != null) {
                val processStartMs = System.currentTimeMillis()
                val previewConfig = PreviewConfigService.getInstance(project).config
                val cacheValid = PreviewCache.isValid(moduleInfo.modulePath, previewConfig)

                val (phase1Rendered, phase1Stats) = appGraph.renderSelectedStates(cacheValid) { done, total ->
                    SwingUtilities.invokeLater { if (!disposed) statusLabel.text = "Rendering $done/$total…" }
                }

                viewModel.buildFromAppGraphV2(appGraph, moduleInfo.modulePath)

                val multiStateCount = appGraph.subgraphs.values
                    .sumOf { sub -> sub.screens.count { it.preview_provider_fqn != null && it.composable_fqn.isNotBlank() } }

                val phase2Stats: PhaseStats
                if (multiStateCount > 0) {
                    SwingUtilities.invokeLater {
                        if (!disposed) {
                            buildButton.isEnabled = true
                            configButton.isEnabled = true
                            statusLabel.text = "Rendering state variants 0/$multiStateCount…"
                        }
                    }
                    phase2Stats = appGraph.renderRemainingStates(
                        phase1Rendered = phase1Rendered,
                        cacheValid = cacheValid,
                        onProgress = { done, total ->
                            SwingUtilities.invokeLater { if (!disposed) statusLabel.text = "Rendering state variants $done/$total…" }
                        },
                        onNodeDone = { nodeId -> viewModel.bumpNodeImageRevision(nodeId) },
                    )
                    viewModel.silentRefreshLayout(appGraph, moduleInfo.modulePath)
                } else {
                    phase2Stats = PhaseStats(0, 0, 0)
                }

                val processElapsedMs = System.currentTimeMillis() - processStartMs
                val totalRendered = phase1Stats.rendered + phase2Stats.rendered
                val totalSkipped = phase1Stats.skipped + phase2Stats.skipped
                val totalFailed = phase1Stats.failed + phase2Stats.failed
                log.info("rendering process done in ${processElapsedMs}ms — rendered=$totalRendered skipped=$totalSkipped failed=$totalFailed")

                PreviewCache.writeSentinel(moduleInfo.modulePath, previewConfig)
            }

            SwingUtilities.invokeLater {
                if (!disposed) {
                    buildButton.isEnabled = true
                    configButton.isEnabled = true
                    statusLabel.text = if (appGraph == null) "No graph — click Build graph" else ""
                }
            }
        }
    }

    private fun runExportGraph() {
        buildButton.isEnabled = false
        configButton.isEnabled = false
        statusLabel.text = "Building…"

        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalProjectPath = moduleInfo.projectRootPath
            taskNames = listOf(moduleInfo.gradleTaskPath)
            externalSystemIdString = GradleConstants.SYSTEM_ID.id
        }

        ExternalSystemUtil.runTask(
            settings,
            DefaultRunExecutor.EXECUTOR_ID,
            project,
            GradleConstants.SYSTEM_ID,
            object : TaskCallback {
                override fun onSuccess() { refreshState() }
                override fun onFailure() {
                    SwingUtilities.invokeLater {
                        statusLabel.text = "Build failed — see Gradle console for details."
                        buildButton.isEnabled = true
                        configButton.isEnabled = true
                    }
                }
            },
            ProgressExecutionMode.IN_BACKGROUND_ASYNC
        )
    }

    private fun refreshPreviews() {
        val currentGraph = viewModel.appGraphState.value ?: return
        refreshButton.isEnabled = false
        buildButton.isEnabled = false
        configButton.isEnabled = false
        statusLabel.text = "Rendering previews…"

        AppExecutorUtil.getAppExecutorService().submit {
            if (disposed) return@submit
            PreviewCache.clearAll(moduleInfo.modulePath)
            val processStartMs = System.currentTimeMillis()
            val previewConfig = PreviewConfigService.getInstance(project).config

            val (phase1Rendered, phase1Stats) = currentGraph.renderSelectedStates(cacheValid = false) { done, total ->
                SwingUtilities.invokeLater { if (!disposed) statusLabel.text = "Rendering $done/$total…" }
            }

            viewModel.buildFromAppGraphV2(currentGraph, moduleInfo.modulePath)

            val multiStateCount = currentGraph.subgraphs.values
                .sumOf { sub -> sub.screens.count { it.preview_provider_fqn != null && it.composable_fqn.isNotBlank() } }

            val phase2Stats: PhaseStats
            if (multiStateCount > 0) {
                SwingUtilities.invokeLater {
                    if (!disposed) {
                        refreshButton.isEnabled = true
                        buildButton.isEnabled = true
                        configButton.isEnabled = true
                        statusLabel.text = "Rendering state variants 0/$multiStateCount…"
                    }
                }
                phase2Stats = currentGraph.renderRemainingStates(
                    phase1Rendered = phase1Rendered,
                    cacheValid = false,
                    onProgress = { done, total ->
                        SwingUtilities.invokeLater { if (!disposed) statusLabel.text = "Rendering state variants $done/$total…" }
                    },
                    onNodeDone = { nodeId -> viewModel.bumpNodeImageRevision(nodeId) },
                )
                viewModel.silentRefreshLayout(currentGraph, moduleInfo.modulePath)
            } else {
                phase2Stats = PhaseStats(0, 0, 0)
            }

            val processElapsedMs = System.currentTimeMillis() - processStartMs
            val totalRendered = phase1Stats.rendered + phase2Stats.rendered
            val totalSkipped = phase1Stats.skipped + phase2Stats.skipped
            val totalFailed = phase1Stats.failed + phase2Stats.failed
            log.info("rendering process done in ${processElapsedMs}ms — rendered=$totalRendered skipped=$totalSkipped failed=$totalFailed")

            PreviewCache.writeSentinel(moduleInfo.modulePath, previewConfig)

            SwingUtilities.invokeLater {
                if (!disposed) {
                    refreshButton.isEnabled = true
                    buildButton.isEnabled = true
                    configButton.isEnabled = true
                    statusLabel.text = ""
                }
            }
        }
    }

    private companion object {
        const val MAX_PREVIEW_STATES = 20
    }

    private data class PhaseStats(val rendered: Int, val skipped: Int, val failed: Int)
    private data class FailedRender(val fqn: String, val providerFqn: String?, val stateIndex: Int, val sourceFilePath: String?, val expectedFile: File)
    private data class FailedMultiState(val nodeId: String, val fqn: String, val providerFqn: String, val sourceFilePath: String?)

    private fun AppGraph.renderSelectedStates(
        cacheValid: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Pair<Set<File>, PhaseStats> {
        val phase1StartMs = System.currentTimeMillis()
        val previewConfig = PreviewConfigService.getInstance(project).config
        val screensToRender = subgraphs.values.flatMap { sub ->
            sub.screens.filter { it.composable_fqn.isNotBlank() }
        }
        val total = screensToRender.size
        var done = 0
        val rendered = mutableSetOf<File>()
        var totalRendered = 0
        var totalSkipped = 0
        var totalFailed = 0
        val failedScreens = mutableListOf<FailedRender>()

        screensToRender.forEach { screen ->
            val previewStartMs = System.currentTimeMillis()
            val fqn = screen.composable_fqn
            val providerFqn = screen.preview_provider_fqn
            val selectedState = screen.selected_state.coerceAtLeast(0)
            val sourceFilePath = screen.location.takeIf { it.isNotBlank() }
                ?.let { File(moduleInfo.modulePath, it).absolutePath }

            var previewRendered = 0
            var previewSkipped = 0
            var previewFailed = 0

            if (providerFqn == null) {
                val f = PreviewCache.expectedFile(moduleInfo.modulePath, fqn)
                if (cacheValid && f.exists()) {
                    log.info("image SKIPPED (cache): $fqn")
                    previewSkipped++
                    totalSkipped++
                } else {
                    runCatching {
                        RendererRouter.render(
                            project, moduleInfo.modulePath, fqn,
                            sourceFilePath = sourceFilePath,
                            previewConfig = previewConfig,
                        )
                    }.onFailure { e ->
                        log.error("renderSelectedStates() exception for screen=${screen.id}", e)
                        previewFailed++
                        totalFailed++
                    }.onSuccess { path ->
                        if (path != null) {
                            rendered.add(f)
                            previewRendered++
                            totalRendered++
                        } else {
                            previewFailed++
                            totalFailed++
                            failedScreens.add(FailedRender(fqn, null, -1, sourceFilePath, f))
                        }
                    }
                }
            } else {
                val f = PreviewCache.expectedFile(moduleInfo.modulePath, fqn, selectedState)
                if (cacheValid && f.exists()) {
                    log.info("image SKIPPED (cache): $fqn[$selectedState]")
                    previewSkipped++
                    totalSkipped++
                } else {
                    runCatching {
                        RendererRouter.render(
                            project, moduleInfo.modulePath, fqn,
                            parameterProviderFqn = providerFqn,
                            stateIndex = selectedState,
                            sourceFilePath = sourceFilePath,
                            previewConfig = previewConfig,
                        )
                    }.onFailure { e ->
                        log.error("renderSelectedStates() exception for screen=${screen.id} stateIndex=$selectedState", e)
                        previewFailed++
                        totalFailed++
                    }.onSuccess { path ->
                        if (path != null) {
                            rendered.add(f)
                            previewRendered++
                            totalRendered++
                        } else {
                            previewFailed++
                            totalFailed++
                            failedScreens.add(FailedRender(fqn, providerFqn, selectedState, sourceFilePath, f))
                        }
                    }
                }
            }

            val previewElapsedMs = System.currentTimeMillis() - previewStartMs
            log.info("preview done in ${previewElapsedMs}ms: $fqn — rendered=$previewRendered skipped=$previewSkipped failed=$previewFailed")

            done++
            onProgress(done, total)
        }

        if (failedScreens.isNotEmpty()) {
            log.info("phase1: retrying ${failedScreens.size} failed composable(s) after main loop")
            for (fr in failedScreens) {
                runCatching {
                    if (fr.providerFqn == null) {
                        RendererRouter.render(project, moduleInfo.modulePath, fr.fqn, sourceFilePath = fr.sourceFilePath, previewConfig = previewConfig)
                    } else {
                        RendererRouter.render(project, moduleInfo.modulePath, fr.fqn, parameterProviderFqn = fr.providerFqn, stateIndex = fr.stateIndex, sourceFilePath = fr.sourceFilePath, previewConfig = previewConfig)
                    }
                }.onSuccess { path ->
                    if (path != null) { rendered.add(fr.expectedFile); totalRendered++; totalFailed-- }
                }.onFailure { e -> log.warn("phase1 retry exception for ${fr.fqn}", e) }
            }
        }
        val phase1ElapsedMs = System.currentTimeMillis() - phase1StartMs
        val stats = PhaseStats(totalRendered, totalSkipped, totalFailed)
        log.info("phase1 done in ${phase1ElapsedMs}ms — screens=$total rendered=${stats.rendered} skipped=${stats.skipped} failed=${stats.failed}")
        return rendered to stats
    }

    private fun AppGraph.renderRemainingStates(
        phase1Rendered: Set<File>,
        cacheValid: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
        onNodeDone: (nodeId: String) -> Unit,
    ): PhaseStats {
        val phase2StartMs = System.currentTimeMillis()
        val previewConfig = PreviewConfigService.getInstance(project).config
        val multiStateScreens = subgraphs.flatMap { (subgraphKey, subgraph) ->
            subgraph.screens
                .filter { it.preview_provider_fqn != null && it.composable_fqn.isNotBlank() }
                .map { subgraphKey to it }
        }
        val total = multiStateScreens.size
        var done = 0
        var totalRendered = 0
        var totalSkipped = 0
        var totalFailed = 0
        val failedAt0 = mutableListOf<FailedMultiState>()

        multiStateScreens.forEach { (subgraphKey, screen) ->
            val previewStartMs = System.currentTimeMillis()
            val nodeId = "$subgraphKey:${screen.id}"
            val fqn = screen.composable_fqn
            val providerFqn = screen.preview_provider_fqn!!
            val sourceFilePath = screen.location.takeIf { it.isNotBlank() }
                ?.let { File(moduleInfo.modulePath, it).absolutePath }

            var previewRendered = 0
            var previewSkipped = 0
            var previewFailed = 0

            var firstAttemptFailed = false
            var index = 0
            while (index < MAX_PREVIEW_STATES) {
                val f = PreviewCache.expectedFile(moduleInfo.modulePath, fqn, index)
                val skip = if (cacheValid) f.exists() else f in phase1Rendered
                if (skip) {
                    log.info("image SKIPPED (${if (cacheValid) "cache" else "phase1"}): $fqn[$index]")
                    previewSkipped++
                    totalSkipped++
                    index++; continue
                }
                val path = runCatching {
                    RendererRouter.render(
                        project, moduleInfo.modulePath, fqn,
                        parameterProviderFqn = providerFqn,
                        stateIndex = index,
                        sourceFilePath = sourceFilePath,
                        previewConfig = previewConfig,
                    )
                }.onFailure { e ->
                    log.error("renderRemainingStates() exception for screen=${screen.id} index=$index", e)
                    previewFailed++
                    totalFailed++
                }.getOrNull()
                if (path == null) {
                    if (previewRendered == 0) firstAttemptFailed = true
                    break
                }
                previewRendered++
                totalRendered++
                index++
            }
            if (firstAttemptFailed) failedAt0.add(FailedMultiState(nodeId, fqn, providerFqn, sourceFilePath))

            val previewElapsedMs = System.currentTimeMillis() - previewStartMs
            log.info("preview done in ${previewElapsedMs}ms: $fqn (all states) — rendered=$previewRendered skipped=$previewSkipped failed=$previewFailed")

            done++
            onProgress(done, total)
            onNodeDone(nodeId)
        }

        val phase2ElapsedMs = System.currentTimeMillis() - phase2StartMs
        val stats = PhaseStats(totalRendered, totalSkipped, totalFailed)
        log.info("phase2 done in ${phase2ElapsedMs}ms — screens=$total rendered=${stats.rendered} skipped=${stats.skipped} failed=${stats.failed}")
        return stats
    }
}
