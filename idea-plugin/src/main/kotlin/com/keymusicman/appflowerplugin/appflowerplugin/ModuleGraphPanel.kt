package com.keymusicman.appflowerplugin.appflowerplugin

import androidx.compose.ui.awt.ComposePanel
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.keymusicman.appflower.loader.GraphLoader
import com.keymusicman.appflower.ui.GraphVisualizer
import com.keymusicman.appflower.viewmodel.GraphViewModel
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Per-module Swing panel that embeds [GraphVisualizer] (Compose) for the navigation graph
 * and provides a "Build graph" button to run the exportGraph Gradle task.
 *
 * States:
 *  - No graph JSON present → centered "Build graph" button
 *  - Graph present         → toolbar with "Build graph" + Compose graph visualizer (zoom/pan)
 *  - Building              → button disabled + "Building…" label
 *  - Error                 → error label + "Build graph" button
 */
class ModuleGraphPanel(
    private val project: Project,
    private val moduleInfo: GradleModuleInfo
) : JPanel(BorderLayout()) {

    private val graphFile = File(moduleInfo.modulePath, "build/graph/app-graph.json")

    private val buildButton = JButton("Build graph").apply {
        addActionListener { runExportGraph() }
    }
    private val statusLabel = JLabel()
    private val viewModel = GraphViewModel()

    /** Compose panel hosting GraphVisualizer; created once and reused across refreshes. */
    private val composePanel = ComposePanel().apply {
        setContent {
            GraphVisualizer(
                appBasePath = moduleInfo.modulePath,
                viewModel = viewModel
            )
        }
    }

    init {
        refreshState()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Re-checks the graph file and updates the view. Called after successful build. */
    fun refreshState() {
        Thread {
            val appGraphV2 = if (graphFile.exists()) GraphLoader.loadFromFile(graphFile) else null

            SwingUtilities.invokeLater {
                removeAll()
                if (appGraphV2 != null) {
                    viewModel.buildFromAppGraphV2(appGraphV2, moduleInfo.projectRootPath)
                    val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                        add(buildButton)
                        add(statusLabel)
                    }
                    buildButton.isEnabled = true
                    statusLabel.text = ""
                    add(toolbar, BorderLayout.NORTH)
                    add(composePanel, BorderLayout.CENTER)
                } else {
                    // No graph yet — show centered build button
                    val center = JPanel(FlowLayout(FlowLayout.CENTER)).apply {
                        add(JLabel("No graph found for module '${moduleInfo.name}'.  "))
                        add(buildButton)
                    }
                    buildButton.isEnabled = true
                    statusLabel.text = ""
                    add(center, BorderLayout.CENTER)
                }
                revalidate()
                repaint()
            }
        }.start()
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun runExportGraph() {
        setBuildingState()

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
                override fun onSuccess() {
                    refreshState()
                }

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

    private fun setBuildingState() {
        buildButton.isEnabled = false
        statusLabel.text = "Building…"
    }
}

