package com.keymusicman.appflowerplugin.appflowerplugin

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.keymusicman.appflower.model.GraphSet
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Tool-window panel for the multi-graph view. Owns the aggregated [GraphSet] and a tab strip;
 * each tab ([GraphTabPanel]) shows a "Graph" dropdown over the canvas. "+" adds a tab.
 * Preview rendering is added in a follow-up; this panel handles discovery/aggregation/display,
 * graph (re)build, configuration, and source navigation.
 */
class MultiGraphPanel(
    private val project: Project,
    modules: List<GradleModuleInfo>,
) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(MultiGraphPanel::class.java)

    @Volatile private var disposed = false
    private val moduleDirs: List<String> = modules.map { it.modulePath }
    private val projectRoots: List<String> = modules.map { it.projectRootPath }.distinct()

    @Volatile private var graphSet: GraphSet = GraphSet(emptyList())

    private val tabbedPane = JBTabbedPane()
    private val tabs = mutableListOf<GraphTabPanel>()

    private val statusLabel = JLabel()
    private val buildButton = JButton("Build graph").apply { addActionListener { runExportGraph() } }
    private val refreshButton = JButton("Refresh previews").apply {
        isEnabled = false
        toolTipText = "Preview rendering wired in a follow-up step"
    }
    private val configButton = JButton("Configure…").apply { addActionListener { openConfig() } }
    private val addTabButton = JButton("+").apply {
        toolTipText = "Add another graph tab"
        addActionListener { addTab() }
    }

    init {
        add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(buildButton); add(refreshButton); add(configButton); add(addTabButton); add(statusLabel)
        }, BorderLayout.NORTH)
        add(tabbedPane, BorderLayout.CENTER)

        addTab()       // start with one tab
        rebuild()      // discover + aggregate + display
    }

    private fun addTab() {
        val tab = GraphTabPanel(graphSet, ::onViewSource, ::onRefreshNode)
        Disposer.register(this, tab)
        tabs += tab
        tabbedPane.addTab("Graph ${tabs.size}", tab)
        tabbedPane.selectedComponent = tab
    }

    /** Re-reads every module's fragment, re-aggregates, and pushes the new GraphSet to all tabs. */
    fun rebuild() {
        statusLabel.text = "Loading…"
        AppExecutorUtil.getAppExecutorService().submit {
            if (disposed) return@submit
            val result = FragmentRepository.aggregate(moduleDirs)
            SwingUtilities.invokeLater {
                if (disposed) return@invokeLater
                graphSet = GraphSet(result.graphs)
                tabs.forEach { it.updateGraphSet(graphSet) }
                val parts = buildList {
                    if (result.errors.isNotEmpty()) add("${result.errors.size} error(s)")
                    if (result.warnings.isNotEmpty()) add("${result.warnings.size} warning(s)")
                    if (result.graphs.isEmpty()) add("No graphs — click Build graph")
                }
                statusLabel.text = parts.joinToString("  ")
                statusLabel.toolTipText = (result.errors + result.warnings).joinToString("\n").ifBlank { null }
                if (result.errors.isNotEmpty()) log.warn("Aggregation errors: ${result.errors}")
            }
        }
    }

    private fun runExportGraph() {
        buildButton.isEnabled = false
        statusLabel.text = "Building…"
        var remaining = projectRoots.size
        if (remaining == 0) { buildButton.isEnabled = true; return }
        projectRoots.forEach { root ->
            val settings = ExternalSystemTaskExecutionSettings().apply {
                externalProjectPath = root
                taskNames = listOf("exportGraph")
                externalSystemIdString = GradleConstants.SYSTEM_ID.id
            }
            ExternalSystemUtil.runTask(
                settings,
                DefaultRunExecutor.EXECUTOR_ID,
                project,
                GradleConstants.SYSTEM_ID,
                object : TaskCallback {
                    override fun onSuccess() = onRootDone()
                    override fun onFailure() = onRootDone(failed = true)
                    private fun onRootDone(failed: Boolean = false) {
                        SwingUtilities.invokeLater {
                            if (disposed) return@invokeLater
                            if (failed) statusLabel.text = "Build failed — see Gradle console."
                            remaining--
                            if (remaining <= 0) {
                                buildButton.isEnabled = true
                                rebuild()
                            }
                        }
                    }
                },
                ProgressExecutionMode.IN_BACKGROUND_ASYNC,
            )
        }
    }

    private fun openConfig() {
        val parent = SwingUtilities.getWindowAncestor(this) as? java.awt.Frame
        val current = PreviewConfigService.getInstance(project).config
        PreviewConfigDialog(parent, current) { newConfig ->
            PreviewConfigService.getInstance(project).updateConfig(newConfig)
            rebuild()
        }.isVisible = true
    }

    private fun onViewSource(nodeId: String, modulePath: String) {
        val tab = tabbedPane.selectedComponent as? GraphTabPanel ?: return
        val appGraph = tab.currentAppGraph() ?: return
        AppExecutorUtil.getAppExecutorService().submit {
            if (disposed) return@submit
            ReadAction.run<Throwable> {
                SourceNavigator.navigateToSource(project, nodeId, appGraph, modulePath)
            }
        }
    }

    /** Per-node preview refresh — wired in the rendering follow-up. */
    private fun onRefreshNode(nodeId: String, modulePath: String) {
        // Intentionally empty until preview rendering is added (B2 part 2).
    }

    override fun dispose() {
        disposed = true
    }
}
