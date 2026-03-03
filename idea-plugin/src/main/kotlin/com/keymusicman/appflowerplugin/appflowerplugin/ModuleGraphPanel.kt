package com.keymusicman.appflowerplugin.appflowerplugin

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.keymusicman.appflower.loader.GraphLoader
import com.keymusicman.appflower.model.Graph
import com.keymusicman.appflower.renderer.renderGraphToImage
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * Per-module Swing panel that renders the navigation graph image and provides
 * a "Build graph" button to run the exportGraph Gradle task.
 *
 * States:
 *  - No graph JSON present → centered "Build graph" button
 *  - Graph present         → toolbar with "Build graph" + scrollable graph image
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
    private val imageLabel = JLabel().apply { horizontalAlignment = JLabel.LEFT }
    private val scrollPane = JScrollPane(imageLabel)

    init {
        refreshState()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Re-checks the graph file and updates the view. Called after successful build. */
    fun refreshState() {
        Thread {
            val image = if (graphFile.exists()) {
                val appGraphV2 = GraphLoader.loadFromFile(graphFile)
                if (appGraphV2 != null) {
                    val graph = Graph.fromV2(appGraphV2, moduleInfo.modulePath)
                    renderGraphToImage(graph, moduleInfo.modulePath)
                } else null
            } else null

            SwingUtilities.invokeLater {
                removeAll()
                if (image != null) {
                    imageLabel.icon = ImageIcon(image)
                    val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                        add(buildButton)
                        add(statusLabel)
                    }
                    buildButton.isEnabled = true
                    statusLabel.text = ""
                    add(toolbar, BorderLayout.NORTH)
                    add(scrollPane, BorderLayout.CENTER)
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

    override fun getPreferredSize(): Dimension = Dimension(800, 600)
}
