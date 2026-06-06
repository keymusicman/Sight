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
import java.io.File
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
    private val refreshButton = JButton("Refresh previews").apply { addActionListener { refreshPreviews() } }
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

    /** Re-render a single node's selected preview against its owning module, then reload that tab. */
    private fun onRefreshNode(nodeId: String, modulePath: String) {
        val tab = tabbedPane.selectedComponent as? GraphTabPanel ?: return
        val appGraph = tab.currentAppGraph() ?: return
        val colon = nodeId.indexOf(':')
        if (colon < 0) return
        val sub = nodeId.substring(0, colon)
        val id = nodeId.substring(colon + 1)
        val screen = appGraph.subgraphs[sub]?.screens?.firstOrNull { it.id == id } ?: return
        if (screen.composable_fqn.isBlank()) return
        val mp = modulePath.ifBlank { screen.module_path }.ifBlank { return }
        setBusy(true, "Rendering ${id}…")
        AppExecutorUtil.getAppExecutorService().submit {
            if (disposed) return@submit
            val config = PreviewConfigService.getInstance(project).config
            val state = if (screen.preview_provider_fqn != null) screen.selected_state.coerceAtLeast(0) else -1
            val sourceFile = screen.location.takeIf { it.isNotBlank() }?.let { File(mp, it).absolutePath }
            runCatching {
                RendererRouter.render(project, mp, screen.composable_fqn,
                    parameterProviderFqn = screen.preview_provider_fqn, stateIndex = state,
                    sourceFilePath = sourceFile, previewConfig = config)
            }.onFailure { e -> log.warn("onRefreshNode render failed for $nodeId", e) }
            SwingUtilities.invokeLater {
                if (disposed) return@invokeLater
                tab.reloadView()
                setBusy(false, "")
            }
        }
    }

    private data class RenderUnit(
        val modulePath: String,
        val fqn: String,
        val provider: String?,
        val stateIndex: Int,
        val sourceFile: String?,
    )

    /** Renders the selected state of every screen across all graphs (deduped), then reloads tabs. */
    private fun refreshPreviews() {
        setBusy(true, "Rendering previews…")
        AppExecutorUtil.getAppExecutorService().submit {
            if (disposed) return@submit
            val config = PreviewConfigService.getInstance(project).config
            val seen = mutableSetOf<Triple<String, String, Int>>()
            val units = mutableListOf<RenderUnit>()
            graphSet.graphs.forEach { ng ->
                ng.graph.subgraphs.values.forEach { sub ->
                    sub.screens.forEach { s ->
                        if (s.composable_fqn.isBlank()) return@forEach
                        val mp = s.module_path.ifBlank { moduleDirs.firstOrNull() }.takeUnless { it.isNullOrBlank() } ?: return@forEach
                        val state = if (s.preview_provider_fqn != null) s.selected_state.coerceAtLeast(0) else -1
                        if (seen.add(Triple(mp, s.composable_fqn, state))) {
                            val sourceFile = s.location.takeIf { it.isNotBlank() }?.let { File(mp, it).absolutePath }
                            units += RenderUnit(mp, s.composable_fqn, s.preview_provider_fqn, state, sourceFile)
                        }
                    }
                }
            }
            val total = units.size
            units.forEachIndexed { i, u ->
                if (disposed) return@submit
                runCatching {
                    RendererRouter.render(project, u.modulePath, u.fqn,
                        parameterProviderFqn = u.provider, stateIndex = u.stateIndex,
                        sourceFilePath = u.sourceFile, previewConfig = config)
                }.onFailure { e -> log.warn("render failed for ${u.fqn}", e) }
                val done = i + 1
                SwingUtilities.invokeLater { if (!disposed) statusLabel.text = "Rendering $done/$total…" }
            }
            SwingUtilities.invokeLater {
                if (disposed) return@invokeLater
                tabs.forEach { it.reloadView() }
                setBusy(false, "")
            }
        }
    }

    private fun setBusy(busy: Boolean, status: String) {
        buildButton.isEnabled = !busy
        refreshButton.isEnabled = !busy
        configButton.isEnabled = !busy
        statusLabel.text = status
    }

    override fun dispose() {
        disposed = true
    }
}
