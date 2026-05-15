package com.keymusicman.appflowerplugin.appflowerplugin

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

    override fun dispose() { disposed = true }

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

        val composePanel = ComposePanel().apply {
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
                                        ComposableRenderer.render(
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
                                            ComposableRenderer.render(
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
    }

    fun refreshState() {
        buildButton.isEnabled = false
        configButton.isEnabled = false
        statusLabel.text = "Loading…"

        AppExecutorUtil.getAppExecutorService().submit {
            if (disposed) return@submit
            val appGraph = if (graphFile.exists()) GraphLoader.loadFromFile(graphFile) else null

            if (appGraph != null) {
                val previewConfig = PreviewConfigService.getInstance(project).config
                val cacheValid = PreviewCache.isValid(moduleInfo.modulePath, previewConfig)

                val phase1Rendered = appGraph.renderSelectedStates(cacheValid) { done, total ->
                    SwingUtilities.invokeLater { if (!disposed) statusLabel.text = "Rendering $done/$total…" }
                }

                viewModel.buildFromAppGraphV2(appGraph, moduleInfo.modulePath)

                val multiStateCount = appGraph.subgraphs.values
                    .sumOf { sub -> sub.screens.count { it.preview_provider_fqn != null && it.composable_fqn.isNotBlank() } }

                if (multiStateCount > 0) {
                    SwingUtilities.invokeLater {
                        if (!disposed) {
                            buildButton.isEnabled = true
                            configButton.isEnabled = true
                            statusLabel.text = "Rendering state variants 0/$multiStateCount…"
                        }
                    }
                    appGraph.renderRemainingStates(
                        phase1Rendered = phase1Rendered,
                        cacheValid = cacheValid,
                        onProgress = { done, total ->
                            SwingUtilities.invokeLater { if (!disposed) statusLabel.text = "Rendering state variants $done/$total…" }
                        },
                        onNodeDone = { nodeId -> viewModel.bumpNodeImageRevision(nodeId) },
                    )
                    viewModel.silentRefreshLayout(appGraph, moduleInfo.modulePath)
                }

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
            val previewConfig = PreviewConfigService.getInstance(project).config

            val phase1Rendered = currentGraph.renderSelectedStates(cacheValid = false) { done, total ->
                SwingUtilities.invokeLater { if (!disposed) statusLabel.text = "Rendering $done/$total…" }
            }

            viewModel.buildFromAppGraphV2(currentGraph, moduleInfo.modulePath)

            val multiStateCount = currentGraph.subgraphs.values
                .sumOf { sub -> sub.screens.count { it.preview_provider_fqn != null && it.composable_fqn.isNotBlank() } }

            if (multiStateCount > 0) {
                SwingUtilities.invokeLater {
                    if (!disposed) {
                        refreshButton.isEnabled = true
                        buildButton.isEnabled = true
                        configButton.isEnabled = true
                        statusLabel.text = "Rendering state variants 0/$multiStateCount…"
                    }
                }
                currentGraph.renderRemainingStates(
                    phase1Rendered = phase1Rendered,
                    cacheValid = false,
                    onProgress = { done, total ->
                        SwingUtilities.invokeLater { if (!disposed) statusLabel.text = "Rendering state variants $done/$total…" }
                    },
                    onNodeDone = { nodeId -> viewModel.bumpNodeImageRevision(nodeId) },
                )
                viewModel.silentRefreshLayout(currentGraph, moduleInfo.modulePath)
            }

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

    private fun AppGraph.renderSelectedStates(
        cacheValid: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Set<File> {
        val previewConfig = PreviewConfigService.getInstance(project).config
        val screensToRender = subgraphs.values.flatMap { sub ->
            sub.screens.filter { it.composable_fqn.isNotBlank() }
        }
        val total = screensToRender.size
        var done = 0
        val rendered = mutableSetOf<File>()

        screensToRender.forEach { screen ->
            val fqn = screen.composable_fqn
            val providerFqn = screen.preview_provider_fqn
            val selectedState = screen.selected_state.coerceAtLeast(0)
            val sourceFilePath = screen.location.takeIf { it.isNotBlank() }
                ?.let { File(moduleInfo.modulePath, it).absolutePath }

            if (providerFqn == null) {
                val f = PreviewCache.expectedFile(moduleInfo.modulePath, fqn)
                if (!(cacheValid && f.exists())) {
                    runCatching {
                        ComposableRenderer.render(
                            project, moduleInfo.modulePath, fqn,
                            sourceFilePath = sourceFilePath,
                            previewConfig = previewConfig,
                        )
                    }.onFailure { e -> log.error("renderSelectedStates() exception for screen=${screen.id}", e) }
                     .onSuccess { path -> if (path != null) rendered.add(f) }
                }
            } else {
                val f = PreviewCache.expectedFile(moduleInfo.modulePath, fqn, selectedState)
                if (!(cacheValid && f.exists())) {
                    runCatching {
                        ComposableRenderer.render(
                            project, moduleInfo.modulePath, fqn,
                            parameterProviderFqn = providerFqn,
                            stateIndex = selectedState,
                            sourceFilePath = sourceFilePath,
                            previewConfig = previewConfig,
                        )
                    }.onFailure { e -> log.error("renderSelectedStates() exception for screen=${screen.id} stateIndex=$selectedState", e) }
                     .onSuccess { path -> if (path != null) rendered.add(f) }
                }
            }

            done++
            onProgress(done, total)
        }

        return rendered
    }

    private fun AppGraph.renderRemainingStates(
        phase1Rendered: Set<File>,
        cacheValid: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
        onNodeDone: (nodeId: String) -> Unit,
    ) {
        val previewConfig = PreviewConfigService.getInstance(project).config
        val multiStateScreens = subgraphs.flatMap { (subgraphKey, subgraph) ->
            subgraph.screens
                .filter { it.preview_provider_fqn != null && it.composable_fqn.isNotBlank() }
                .map { subgraphKey to it }
        }
        val total = multiStateScreens.size
        var done = 0

        multiStateScreens.forEach { (subgraphKey, screen) ->
            val nodeId = "$subgraphKey:${screen.id}"
            val fqn = screen.composable_fqn
            val providerFqn = screen.preview_provider_fqn!!
            val sourceFilePath = screen.location.takeIf { it.isNotBlank() }
                ?.let { File(moduleInfo.modulePath, it).absolutePath }

            var index = 0
            while (index < MAX_PREVIEW_STATES) {
                val f = PreviewCache.expectedFile(moduleInfo.modulePath, fqn, index)
                val skip = if (cacheValid) f.exists() else f in phase1Rendered
                if (skip) { index++; continue }
                val path = runCatching {
                    ComposableRenderer.render(
                        project, moduleInfo.modulePath, fqn,
                        parameterProviderFqn = providerFqn,
                        stateIndex = index,
                        sourceFilePath = sourceFilePath,
                        previewConfig = previewConfig,
                    )
                }.onFailure { e -> log.error("renderRemainingStates() exception for screen=${screen.id} index=$index", e) }
                 .getOrNull()
                if (path == null) break
                index++
            }

            done++
            onProgress(done, total)
            onNodeDone(nodeId)
        }
    }
}
