package com.keymusicman.appflowerplugin.appflowerplugin

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.keymusicman.appflower.loader.GraphLoader
import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.model.buildLayoutGraph
import com.keymusicman.appflower.renderer.renderLayoutGraph
import kotlinx.coroutines.runBlocking
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
) : JPanel(BorderLayout()) {

    private val graphFile = File(moduleInfo.modulePath, "build/graph/app-graph.json")
    private val buildButton = JButton("Build graph").apply { addActionListener { runExportGraph() } }
    private val statusLabel = JLabel()

    init {
        refreshState()
    }

    fun refreshState() {
        Thread {
            val appGraph = if (graphFile.exists())
                GraphLoader.loadFromFile(graphFile)?.withRenderedPreviews()
            else null

            val canvas = appGraph?.let { graph ->
                runBlocking {
                    val layout = buildLayoutGraph(graph, moduleInfo.projectRootPath)
                    val image = renderLayoutGraph(layout) ?: return@runBlocking null
                    GraphSwingCanvas(layout, image) { nodeId ->
                        AppExecutorUtil.getAppExecutorService().submit {
                            ReadAction.run<Throwable> {
                                SourceNavigator.navigateToSource(
                                    project, nodeId, graph, moduleInfo.projectRootPath
                                )
                            }
                        }
                    }
                }
            }

            SwingUtilities.invokeLater {
                removeAll()
                if (canvas != null) {
                    val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                        add(buildButton)
                        add(statusLabel)
                    }
                    buildButton.isEnabled = true
                    statusLabel.text = ""
                    add(toolbar, BorderLayout.NORTH)
                    add(canvas, BorderLayout.CENTER)
                } else {
                    add(JPanel(FlowLayout(FlowLayout.CENTER)).apply {
                        add(JLabel("No graph found for module '${moduleInfo.name}'.  "))
                        add(buildButton)
                    }, BorderLayout.CENTER)
                    buildButton.isEnabled = true
                    statusLabel.text = ""
                }
                revalidate()
                repaint()
            }
        }.start()
    }

    private fun runExportGraph() {
        buildButton.isEnabled = false
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
                    }
                }
            },
            ProgressExecutionMode.IN_BACKGROUND_ASYNC
        )
    }

    private fun AppGraph.withRenderedPreviews(): AppGraph {
        val updatedSubgraphs = subgraphs.mapValues { (_, subgraph) ->
            val updatedScreens = subgraph.screens.map { screen ->
                val rendered = runCatching {
                    ComposableRenderer.render(
                        project = project,
                        modulePath = moduleInfo.modulePath,
                        composableFqn = screen.function,
                    )
                }.getOrNull()
                if (rendered != null) screen.copy(screenshot_location = rendered) else screen
            }
            subgraph.copy(screens = updatedScreens)
        }
        return copy(subgraphs = updatedSubgraphs)
    }
}