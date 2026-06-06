package com.keymusicman.appflowerplugin.appflowerplugin

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import com.intellij.openapi.Disposable
import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.model.GraphSet
import com.keymusicman.appflower.ui.AppTheme
import com.keymusicman.appflower.ui.GraphPanel
import com.keymusicman.appflower.viewmodel.GraphViewModel
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * One tab in the multi-graph view. Hosts a toolbar row:
 *   Graph: [dropdown]  ···space···  [status]  [Build graph]  [Refresh previews]  [Configure]
 * followed by the Compose canvas. Action buttons delegate to [MultiGraphPanel] via callbacks.
 */
@OptIn(ExperimentalComposeUiApi::class)
class GraphTabPanel(
    initialGraphSet: GraphSet,
    private val onViewSource: (nodeId: String, modulePath: String) -> Unit,
    private val onRefreshNode: (nodeId: String, modulePath: String) -> Unit,
    private val onBuild: () -> Unit,
    private val onRefreshPreviews: () -> Unit,
    private val onConfigure: () -> Unit,
) : JPanel(BorderLayout()), Disposable {

    private val viewModel = GraphViewModel()
    private val composePanel = ComposePanel()
    private var graphSet: GraphSet = initialGraphSet
    internal val selector = JComboBox(DefaultComboBoxModel(initialGraphSet.graphs.map { it.name }.toTypedArray()))

    private val statusLabel = JLabel()
    private val buildButton = JButton("Build graph").apply { addActionListener { onBuild() } }
    private val refreshButton = JButton("Refresh previews").apply { addActionListener { onRefreshPreviews() } }
    private val configButton = JButton("Configure…").apply { addActionListener { onConfigure() } }

    init {
        val toolbar = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(JLabel("Graph:"))
                add(selector)
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                add(statusLabel)
                add(buildButton)
                add(refreshButton)
                add(configButton)
            }, BorderLayout.EAST)
        }
        add(toolbar, BorderLayout.NORTH)

        initComposeContent()
        add(composePanel, BorderLayout.CENTER)

        selector.addActionListener { showSelected() }
        showSelected()
    }

    fun setStatus(text: String, tooltip: String? = null) {
        statusLabel.text = text
        statusLabel.toolTipText = tooltip
    }

    fun setBusy(busy: Boolean, status: String) {
        buildButton.isEnabled = !busy
        refreshButton.isEnabled = !busy
        configButton.isEnabled = !busy
        statusLabel.text = status
    }

    private fun initComposeContent() {
        composePanel.setContent {
            AppTheme(isDark = true) {
                GraphPanel(
                    viewModel = viewModel,
                    onViewSource = { nodeId -> onViewSource(nodeId, modulePathFor(nodeId)) },
                    onRefreshNode = { nodeId -> onRefreshNode(nodeId, modulePathFor(nodeId)) },
                )
            }
        }
    }

    /** The AppGraph currently shown in this tab (selected graph), or null before first build. */
    fun currentAppGraph(): AppGraph? = viewModel.appGraphState.value

    /** The currently selected graph name, or null if none. */
    fun selectedGraphName(): String? = selector.selectedItem as? String

    fun updateGraphSet(newSet: GraphSet) {
        val previous = selector.selectedItem as? String
        graphSet = newSet
        selector.model = DefaultComboBoxModel(newSet.graphs.map { it.name }.toTypedArray())
        if (previous != null && newSet.graphs.any { it.name == previous }) selector.selectedItem = previous
        showSelected()
    }

    /** Re-builds the current graph's layout, re-reading preview images from disk. */
    fun reloadView() = showSelected()

    /** Bumps the image revision for a node so its preview re-loads from disk. */
    fun bumpNodeImageRevision(nodeId: String) = viewModel.bumpNodeImageRevision(nodeId)

    private fun showSelected() {
        val name = selector.selectedItem as? String ?: return
        val graph = graphSet.graphs.firstOrNull { it.name == name }?.graph ?: return
        viewModel.buildFromAppGraphV2(graph, projectPath = null)
    }

    /** The owning module of a node's screen, for render/navigation routing. */
    private fun modulePathFor(nodeId: String): String {
        val appGraph = viewModel.appGraphState.value ?: return ""
        val colon = nodeId.indexOf(':')
        if (colon < 0) return ""
        val sub = nodeId.substring(0, colon)
        val id = nodeId.substring(colon + 1)
        return appGraph.subgraphs[sub]?.screens?.firstOrNull { it.id == id }?.module_path ?: ""
    }

    override fun dispose() {
        composePanel.dispose()
    }
}
