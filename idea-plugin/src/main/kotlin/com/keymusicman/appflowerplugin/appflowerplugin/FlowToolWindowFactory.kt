package com.keymusicman.appflowerplugin.appflowerplugin

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class FlowToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // controller is set asynchronously once module scanning finishes;
        // the lambda captures it by reference via the array wrapper.
        val controllerRef = arrayOfNulls<GraphController>(1)

        // "+" lives in the tab strip (right of the last tab), Debug Renderer in the title bar.
        (toolWindow as? ToolWindowEx)?.setTabActions(
            object : DumbAwareAction("Add Graph Tab", null, AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    controllerRef[0]?.addTab()
                }
            }
        )
        toolWindow.setTitleActions(listOf(
            object : DumbAwareAction("Debug Renderer") {
                override fun actionPerformed(e: AnActionEvent) {
                    SwingUtilities.invokeLater {
                        ComposableRenderDebugDialog(project).isVisible = true
                    }
                }
            },
        ))

        val loading = makeMessagePanel("Scanning for modules with exportGraph task…")
        val loadingContent = ContentFactory.getInstance().createContent(loading, "Loading…", false)
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
                    val controller = GraphController(project, toolWindow, modules)
                    Disposer.register(toolWindow.disposable, controller)
                    controllerRef[0] = controller
                }
            }
        }.start()
    }

    private fun makeMessagePanel(message: String) = JPanel(BorderLayout()).apply {
        add(JLabel("<html>${message.replace("\n", "<br>")}</html>", JLabel.CENTER), BorderLayout.CENTER)
    }
}
