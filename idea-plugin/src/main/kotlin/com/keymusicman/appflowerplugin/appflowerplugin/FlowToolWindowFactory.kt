package com.keymusicman.appflowerplugin.appflowerplugin

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
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
                when {
                    modules.isEmpty() -> {
                        val msg = makeMessagePanel(
                            "No modules with exportGraph task found."
                        )
                        toolWindow.contentManager.addContent(
                            ContentFactory.getInstance().createContent(msg, "App Flow", false)
                        )
                    }

                    modules.size == 1 -> {
                        openModuleTab(toolWindow, project, modules.first())
                    }

                    else -> {
                        val listContent = makeModuleListContent(toolWindow, project, modules)
                        toolWindow.contentManager.addContent(listContent)
                    }
                }
            }
        }.start()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Creates the "Modules" overview tab listing all detected modules. */
    private fun makeModuleListContent(
        toolWindow: ToolWindow,
        project: Project,
        modules: List<GradleModuleInfo>
    ) = ContentFactory.getInstance().createContent(
        makeModuleListPanel(toolWindow, project, modules),
        "Modules",
        false
    )

    private fun makeModuleListPanel(
        toolWindow: ToolWindow,
        project: Project,
        modules: List<GradleModuleInfo>
    ): JPanel {
        val outer = JPanel(BorderLayout())
        val header = JLabel("  Select a module to view its navigation graph:")
            .apply { font = font.deriveFont(Font.BOLD) }
        outer.add(header, BorderLayout.NORTH)

        val list = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(8, 16, 8, 16)
        }

        modules.forEach { moduleInfo ->
            val row = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
            }
            val link = JLabel("<html><a href='#'>${moduleInfo.name}</a> " +
                "<font color='gray'><small>${moduleInfo.gradleTaskPath}</small></font></html>"
            ).apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        openOrFocusModuleTab(toolWindow, project, moduleInfo)
                    }
                })
            }
            row.add(link)
            list.add(row)
        }

        outer.add(JScrollPane(list), BorderLayout.CENTER)
        return outer
    }

    /**
     * Opens a tab for [moduleInfo], or focuses the existing one if already open.
     */
    private fun openOrFocusModuleTab(
        toolWindow: ToolWindow,
        project: Project,
        moduleInfo: GradleModuleInfo
    ) {
        val existing = toolWindow.contentManager.contents
            .firstOrNull { it.displayName == moduleInfo.name }
        if (existing != null) {
            toolWindow.contentManager.setSelectedContent(existing)
        } else {
            openModuleTab(toolWindow, project, moduleInfo)
        }
    }

    /** Creates and selects a new Content tab for [moduleInfo]. */
    private fun openModuleTab(
        toolWindow: ToolWindow,
        project: Project,
        moduleInfo: GradleModuleInfo
    ) {
        val panel = ModuleGraphPanel(project, moduleInfo)
        val content = ContentFactory.getInstance()
            .createContent(panel, moduleInfo.name, false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content)
    }

    private fun makeMessagePanel(message: String) = JPanel(BorderLayout()).apply {
        add(JLabel("<html>${message.replace("\n", "<br>")}</html>", JLabel.CENTER), BorderLayout.CENTER)
    }
}
