package com.keymusicman.appflowerplugin.appflowerplugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.keymusicman.appflower.loader.GraphLoader
import com.keymusicman.appflower.model.Graph
import com.keymusicman.appflower.renderer.renderGraphToImage
import java.awt.BorderLayout
import java.awt.image.BufferedImage
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

class FlowToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel(BorderLayout())
        val imageLabel = JLabel("Loading graph...", JLabel.CENTER)
        panel.add(JScrollPane(imageLabel), BorderLayout.CENTER)

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        // Load and render graph in background
        val projectPath = project.basePath
        Thread {
            val rendered = loadAndRenderGraph(projectPath)
            SwingUtilities.invokeLater {
                if (rendered != null) {
                    imageLabel.icon = ImageIcon(rendered)
                    imageLabel.text = null
                } else {
                    imageLabel.text = "No graph found. Run the graph generator first."
                }
            }
        }.start()
    }

    private fun loadAndRenderGraph(projectPath: String?): BufferedImage? {
        if (projectPath == null) return null
        val appGraphV2 = GraphLoader.loadGraphFromProject(projectPath) ?: return null
        val graph = Graph.fromV2(appGraphV2, projectPath)
        return renderGraphToImage(graph, projectPath)
    }
}
