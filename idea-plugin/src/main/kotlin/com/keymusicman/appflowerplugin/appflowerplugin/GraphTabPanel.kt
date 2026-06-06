package com.keymusicman.appflowerplugin.appflowerplugin

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.model.GraphSet
import com.keymusicman.appflower.ui.AppTheme
import com.keymusicman.appflower.ui.GraphPanel
import com.keymusicman.appflower.ui.NodeContextMenuRequest
import com.keymusicman.appflower.viewmodel.GraphViewModel
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * One tab in the multi-graph view. Hosts a toolbar row:
 *   Graph: [dropdown]  ···space···  [progress]  [badge]  [Build graph]  [Refresh previews]  [Configure]
 * followed by a horizontal splitter: Compose canvas on the left, collapsible Problems panel on the right.
 * The badge shows error/warning counts; clicking it toggles the Problems panel.
 */
@OptIn(ExperimentalComposeUiApi::class)
class GraphTabPanel(
    initialGraphSet: GraphSet,
    private val onViewSource: (nodeId: String, modulePath: String) -> Unit,
    private val onRefreshNode: (nodeId: String, modulePath: String) -> Unit,
    private val onBuild: () -> Unit,
    private val onRefreshPreviews: () -> Unit,
    private val onConfigure: () -> Unit,
    private val onStop: () -> Unit,
) : JPanel(BorderLayout()), Disposable {

    private val viewModel = GraphViewModel()
    private val composePanel = ComposePanel()
    private var graphSet: GraphSet = initialGraphSet
    internal val selector = JComboBox(DefaultComboBoxModel(initialGraphSet.graphs.map { it.name }.toTypedArray()))

    private val progressLabel = JLabel()
    private val problemsBadge = JLabel().apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { toggleProblemsPanel() }
        })
    }
    private val buildButton = JButton("Build graph").apply { addActionListener { onBuild() } }
    private val refreshButton = JButton("Refresh previews").apply { addActionListener { onRefreshPreviews() } }
    private val configButton = JButton("Configure…").apply { addActionListener { onConfigure() } }
    private val stopButton = JButton("Stop").apply {
        isVisible = false
        addActionListener { isEnabled = false; onStop() }
    }

    private val problemsListModel = DefaultListModel<String>()
    private val problemsList = JBList(problemsListModel)
    private val problemsPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createTitledBorder("Problems")
        add(JBScrollPane(problemsList), BorderLayout.CENTER)
        minimumSize = Dimension(0, 0)
        preferredSize = Dimension(260, 0)
    }
    private val splitter = JBSplitter(false).apply {
        firstComponent = composePanel
    }

    private var problemsPanelVisible = false
    private var currentErrors: List<String> = emptyList()
    private var currentWarnings: List<String> = emptyList()

    init {
        val toolbar = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(JLabel("Graph:"))
                add(selector)
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                add(progressLabel)
                add(problemsBadge)
                add(stopButton)
                add(buildButton)
                add(refreshButton)
                add(configButton)
            }, BorderLayout.EAST)
        }
        add(toolbar, BorderLayout.NORTH)

        initComposeContent()
        add(splitter, BorderLayout.CENTER)

        selector.addActionListener { showSelected() }
        showSelected()
    }

    fun setStatus(text: String) {
        progressLabel.text = text
    }

    fun setProblems(errors: List<String>, warnings: List<String>) {
        currentErrors = errors
        currentWarnings = warnings
        progressLabel.text = ""
        updateBadge()
        updateProblemsList()
    }

    fun setBusy(busy: Boolean, status: String, cancellable: Boolean = false) {
        buildButton.isEnabled = !busy
        refreshButton.isEnabled = !busy
        configButton.isEnabled = !busy
        // The Stop button only appears for cancellable operations (rendering, not Gradle builds).
        stopButton.isVisible = busy && cancellable
        stopButton.isEnabled = busy && cancellable
        progressLabel.text = status
        if (!busy) updateBadge()
    }

    private fun updateBadge() {
        val e = currentErrors.size
        val w = currentWarnings.size
        problemsBadge.text = when {
            e > 0 && w > 0 -> "<html><font color='#FF6B68'>$e ✖</font>&nbsp;&nbsp;<font color='#FFC66D'>$w ⚠</font></html>"
            e > 0 -> "<html><font color='#FF6B68'>$e ✖</font></html>"
            w > 0 -> "<html><font color='#FFC66D'>$w ⚠</font></html>"
            else -> ""
        }
        problemsBadge.cursor = if (e + w > 0) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                               else Cursor.getDefaultCursor()
        problemsBadge.toolTipText = if (e + w > 0) "Click to show/hide problems" else null
    }

    private fun updateProblemsList() {
        problemsListModel.clear()
        currentErrors.forEach { problemsListModel.addElement("✖  $it") }
        currentWarnings.forEach { problemsListModel.addElement("⚠  $it") }
    }

    private fun toggleProblemsPanel() {
        if (currentErrors.isEmpty() && currentWarnings.isEmpty()) return
        problemsPanelVisible = !problemsPanelVisible
        if (problemsPanelVisible) {
            splitter.secondComponent = problemsPanel
            splitter.proportion = 0.70f
        } else {
            splitter.secondComponent = null
        }
    }

    private fun initComposeContent() {
        composePanel.setContent {
            AppTheme(isDark = true) {
                GraphPanel(
                    viewModel = viewModel,
                    onViewSource = { nodeId -> onViewSource(nodeId, modulePathFor(nodeId)) },
                    onRefreshNode = { nodeId -> onRefreshNode(nodeId, modulePathFor(nodeId)) },
                    onNodeContextMenu = ::showNodeContextMenu,
                )
            }
        }
    }

    /**
     * Shows a native IntelliJ (Swing) context menu for a graph node, replacing the previous
     * Compose-rendered menu. Built + shown on the EDT; positioned at the click's screen
     * coordinates (captured in [req]) relative to the Compose canvas.
     */
    private fun showNodeContextMenu(req: NodeContextMenuRequest) {
        SwingUtilities.invokeLater {
            if (!composePanel.isShowing) return@invokeLater
            val imagePath = req.imagePath
            val menu = JBPopupMenu()
            menu.add(JMenuItem("Copy").apply {
                isEnabled = imagePath != null
                addActionListener { imagePath?.let(::copyImageToClipboard) }
            })
            menu.add(JMenuItem("Open in Finder").apply {
                isEnabled = imagePath != null
                addActionListener { imagePath?.let { RevealFileAction.openFile(java.io.File(it)) } }
            })
            menu.addSeparator()
            menu.add(JMenuItem("Jump to source").apply {
                addActionListener { onViewSource(req.nodeId, modulePathFor(req.nodeId)) }
            })
            menu.add(JMenuItem("Refresh").apply {
                addActionListener { onRefreshNode(req.nodeId, modulePathFor(req.nodeId)) }
            })
            val pt = Point(req.screenX, req.screenY)
            SwingUtilities.convertPointFromScreen(pt, composePanel)
            menu.show(composePanel, pt.x, pt.y)
        }
    }

    private fun copyImageToClipboard(path: String) {
        runCatching {
            val image = ImageIO.read(java.io.File(path)) ?: return
            Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageTransferable(image), null)
        }
    }

    /** Wraps a [java.awt.Image] so it can be placed on the system clipboard as an image. */
    private class ImageTransferable(private val image: java.awt.Image) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            if (flavor != DataFlavor.imageFlavor) throw UnsupportedFlavorException(flavor)
            return image
        }
    }

    fun currentAppGraph(): AppGraph? = viewModel.appGraphState.value

    fun selectedGraphName(): String? = selector.selectedItem as? String

    fun updateGraphSet(newSet: GraphSet) {
        val previous = selector.selectedItem as? String
        graphSet = newSet
        selector.model = DefaultComboBoxModel(newSet.graphs.map { it.name }.toTypedArray())
        if (previous != null && newSet.graphs.any { it.name == previous }) selector.selectedItem = previous
        showSelected()
    }

    fun reloadView() = showSelected()

    fun bumpNodeImageRevision(nodeId: String) = viewModel.bumpNodeImageRevision(nodeId)

    fun updateNodeImages(nodeId: String, imagePaths: List<String>) =
        viewModel.updateNodeImages(nodeId, imagePaths)

    private fun showSelected() {
        val name = selector.selectedItem as? String ?: return
        val graph = graphSet.graphs.firstOrNull { it.name == name }?.graph ?: return
        viewModel.buildFromAppGraphV2(graph, projectPath = null)
    }

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
