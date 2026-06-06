package com.keymusicman.appflowerplugin.appflowerplugin

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class FlowToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.setTitleActions(listOf(
            object : DumbAwareAction("Debug Renderer") {
                override fun actionPerformed(e: AnActionEvent) {
                    SwingUtilities.invokeLater {
                        ComposableRenderDebugDialog(project).isVisible = true
                    }
                }
            }
        ))

        val loading = makeMessagePanel("Scanning for modules with exportGraph task…")
        val loadingContent = ContentFactory.getInstance().createContent(loading, "Modules", false)
        toolWindow.contentManager.addContent(loadingContent)

        Thread {
            val modules = ModuleScanner.findModulesWithExportGraph(project)
            SwingUtilities.invokeLater {
                toolWindow.contentManager.removeContent(loadingContent, true)
                if (modules.isEmpty()) {
                    val msg = makeMessagePanel("No modules with exportGraph task found.")
                    toolWindow.contentManager.addContent(
                        ContentFactory.getInstance().createContent(msg, "App Flow", false)
                    )
                } else {
                    val panel = MultiGraphPanel(project, modules)
                    val content = ContentFactory.getInstance().createContent(panel, "App Flow", false)
                    Disposer.register(content, panel)
                    toolWindow.contentManager.addContent(content)
                    toolWindow.contentManager.setSelectedContent(content)
                }
            }
        }.start()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun makeMessagePanel(message: String) = JPanel(BorderLayout()).apply {
        add(JLabel("<html>${message.replace("\n", "<br>")}</html>", JLabel.CENTER), BorderLayout.CENTER)
    }
}
