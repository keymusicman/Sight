package com.keymusicman.appflowerplugin.appflowerplugin

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import com.intellij.openapi.Disposable
import com.keymusicman.appflower.model.GraphSet
import com.keymusicman.appflower.ui.AppTheme
import com.keymusicman.appflower.ui.GraphPanel
import com.keymusicman.appflower.viewmodel.GraphViewModel
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * One tab in the multi-graph view: a "Graph" dropdown (all available graphs) over a Compose
 * canvas. Each tab has its own [GraphViewModel] (independent pan/zoom/selection) and remembers
 * its selected graph. [updateGraphSet] refreshes the dropdown when the aggregate changes.
 */
@OptIn(ExperimentalComposeUiApi::class)
class GraphTabPanel(
    initialGraphSet: GraphSet,
    private val onViewSource: (nodeId: String, modulePath: String) -> Unit,
    private val onRefreshNode: (nodeId: String, modulePath: String) -> Unit,
) : JPanel(BorderLayout()), Disposable {

    private val viewModel = GraphViewModel()
    private val composePanel = ComposePanel()
    private var graphSet: GraphSet = initialGraphSet
    private val selector = JComboBox(DefaultComboBoxModel(initialGraphSet.graphs.map { it.name }.toTypedArray()))

    init {
        add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(JLabel("Graph:"))
            add(selector)
        }, BorderLayout.NORTH)

        initComposeContent()
        add(composePanel, BorderLayout.CENTER)

        selector.addActionListener { showSelected() }
        showSelected()
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

    /** The currently selected graph name, or null if none. */
    fun selectedGraphName(): String? = selector.selectedItem as? String

    fun updateGraphSet(newSet: GraphSet) {
        val previous = selector.selectedItem as? String
        graphSet = newSet
        selector.model = DefaultComboBoxModel(newSet.graphs.map { it.name }.toTypedArray())
        if (previous != null && newSet.graphs.any { it.name == previous }) selector.selectedItem = previous
        showSelected()
    }

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
