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
                                            project, nodeId, appGraph, moduleInfo.projectRootPath
                                        )
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
            appGraph?.renderPreviews()

            if (appGraph != null) {
                viewModel.buildFromAppGraphV2(appGraph, moduleInfo.modulePath)
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
            currentGraph.renderPreviews()
            viewModel.buildFromAppGraphV2(currentGraph, moduleInfo.modulePath)

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

    private fun AppGraph.renderPreviews() {
        log.info("renderPreviews() starting for module=${moduleInfo.modulePath}")
        val previewConfig = PreviewConfigService.getInstance(project).config
        val cacheValid = PreviewCache.isValid(moduleInfo.modulePath, previewConfig)
        if (cacheValid) {
            log.info("renderPreviews() cache valid — will skip existing images")
        }

        subgraphs.forEach { (_, subgraph) ->
            subgraph.screens.forEach { screen ->
                val fqn = screen.composable_fqn.takeIf { it.isNotBlank() } ?: run {
                    log.warn("renderPreviews() skipping screen=${screen.id}: blank composable_fqn")
                    return@forEach
                }
                val providerFqn = screen.preview_provider_fqn
                // location is relative to the module directory (set by KSP projectRoot option)
                val sourceFilePath = screen.location.takeIf { it.isNotBlank() }?.let { loc ->
                    java.io.File(moduleInfo.modulePath, loc).absolutePath
                }

                if (providerFqn == null) {
                    if (cacheValid && PreviewCache.expectedFile(moduleInfo.modulePath, fqn).exists()) {
                        log.info("renderPreviews() skipping cached screen=${screen.id}")
                        return@forEach
                    }
                    runCatching {
                        ComposableRenderer.render(
                            project, moduleInfo.modulePath, fqn,
                            sourceFilePath = sourceFilePath,
                            previewConfig = previewConfig,
                        )
                    }.onFailure { e ->
                        log.error("renderPreviews() exception for screen=${screen.id}", e)
                    }.onSuccess { path ->
                        if (path == null) log.warn("renderPreviews() render returned null for screen=${screen.id}")
                        else log.info("renderPreviews() rendered screen=${screen.id} → $path")
                    }
                } else {
                    var index = 0
                    while (index < MAX_PREVIEW_STATES) {
                        if (cacheValid && PreviewCache.expectedFile(moduleInfo.modulePath, fqn, index).exists()) {
                            log.info("renderPreviews() skipping cached screen=${screen.id} state=$index")
                            index++
                            continue
                        }
                        val path = runCatching {
                            ComposableRenderer.render(
                                project, moduleInfo.modulePath, fqn,
                                parameterProviderFqn = providerFqn,
                                stateIndex = index,
                                sourceFilePath = sourceFilePath,
                                previewConfig = previewConfig,
                            )
                        }.onFailure { e ->
                            log.error("renderPreviews() exception for screen=${screen.id} index=$index", e)
                        }.getOrNull()

                        if (path == null) {
                            log.info("renderPreviews() multi-state done for screen=${screen.id}: $index states rendered")
                            break
                        }
                        log.info("renderPreviews() rendered screen=${screen.id} state=$index → $path")
                        index++
                    }
                }
            }
        }

        PreviewCache.writeSentinel(moduleInfo.modulePath, previewConfig)
        log.info("renderPreviews() completed for module=${moduleInfo.modulePath}")
    }
}
