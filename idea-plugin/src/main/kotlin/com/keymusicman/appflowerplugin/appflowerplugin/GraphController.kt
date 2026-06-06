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
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.concurrency.AppExecutorUtil
import com.keymusicman.appflower.model.GraphSet
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File
import javax.swing.SwingUtilities

/**
 * Owns the aggregated [GraphSet] and manages the tool window's [ContentManager] tabs.
 * Each tab is a [GraphTabPanel] wrapped in a native [com.intellij.ui.content.Content]
 * (closeable, proper hover). [addTab] creates a new tab; [rebuild] re-aggregates fragments.
 */
class GraphController(
    private val project: Project,
    private val toolWindow: ToolWindow,
    modules: List<GradleModuleInfo>,
) : Disposable {

    private val log = Logger.getInstance(GraphController::class.java)
    @Volatile private var disposed = false
    private val moduleDirs = modules.map { it.modulePath }
    private val projectRoots = modules.map { it.projectRootPath }.distinct()
    @Volatile private var graphSet = GraphSet(emptyList())
    private val tabPanels = mutableListOf<GraphTabPanel>()

    init {
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoveQuery(event: ContentManagerEvent) {
                if (tabPanels.size <= 1) event.consume()
            }
            override fun contentRemoved(event: ContentManagerEvent) {
                tabPanels.remove(event.content.getUserData(TAB_KEY))
            }
        })
        addTab()
        rebuild()
    }

    fun addTab() {
        val tab = GraphTabPanel(
            graphSet, ::onViewSource, ::onRefreshNode,
            ::runExportGraph, ::refreshPreviews, ::openConfig,
        )
        tabPanels += tab
        val content = ContentFactory.getInstance().createContent(tab, "Graph ${tabPanels.size}", false)
        content.isCloseable = true
        content.putUserData(TAB_KEY, tab)
        Disposer.register(content, tab)
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content)
    }

    fun rebuild() {
        setAllStatus("Loading…")
        AppExecutorUtil.getAppExecutorService().submit {
            if (disposed) return@submit
            val result = FragmentRepository.aggregate(moduleDirs)
            SwingUtilities.invokeLater {
                if (disposed) return@invokeLater
                graphSet = GraphSet(result.graphs)
                tabPanels.forEach { it.updateGraphSet(graphSet) }
                setAllProblems(result.errors, result.warnings)
                if (result.errors.isNotEmpty()) log.warn("Aggregation errors: ${result.errors}")
            }
        }
    }

    private fun runExportGraph() {
        setAllBusy(true, "Building…")
        var remaining = projectRoots.size
        if (remaining == 0) { setAllBusy(false, ""); return }
        projectRoots.forEach { root ->
            val settings = ExternalSystemTaskExecutionSettings().apply {
                externalProjectPath = root
                taskNames = listOf("exportGraph", "compileDebugKotlin")
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
                            if (failed) setAllStatus("Build failed — see Gradle console.")
                            remaining--
                            if (remaining <= 0) {
                                setAllBusy(false, "")
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
        val frame = WindowManager.getInstance().getFrame(project)
        val current = PreviewConfigService.getInstance(project).config
        PreviewConfigDialog(frame, current) { newConfig ->
            PreviewConfigService.getInstance(project).updateConfig(newConfig)
            rebuild()
        }.isVisible = true
    }

    private fun onViewSource(nodeId: String, modulePath: String) {
        val tab = toolWindow.contentManager.selectedContent?.getUserData(TAB_KEY) ?: return
        val appGraph = tab.currentAppGraph() ?: return
        AppExecutorUtil.getAppExecutorService().submit {
            if (disposed) return@submit
            ReadAction.run<Throwable> {
                SourceNavigator.navigateToSource(project, nodeId, appGraph, modulePath)
            }
        }
    }

    private fun onRefreshNode(nodeId: String, modulePath: String) {
        val tab = toolWindow.contentManager.selectedContent?.getUserData(TAB_KEY) ?: return
        val appGraph = tab.currentAppGraph() ?: return
        val colon = nodeId.indexOf(':')
        if (colon < 0) return
        val sub = nodeId.substring(0, colon)
        val id = nodeId.substring(colon + 1)
        val screen = appGraph.subgraphs[sub]?.screens?.firstOrNull { it.id == id } ?: return
        if (screen.composable_fqn.isBlank()) return
        val mp = modulePath.ifBlank { screen.module_path }.ifBlank { return }
        setAllBusy(true, "Rendering $id…")
        AppExecutorUtil.getAppExecutorService().submit {
            if (disposed) return@submit
            val config = PreviewConfigService.getInstance(project).config
            val sourceFile = screen.location.takeIf { it.isNotBlank() }?.let { File(mp, it).absolutePath }
            if (screen.preview_provider_fqn != null) {
                // Re-render every state, not just the selected one. Clear first so the on-disk set
                // matches exactly what the provider yields (same approach as refreshPreviews).
                PreviewCache.clearIndexedFiles(mp, screen.composable_fqn)
                var stateIndex = 0
                while (!disposed) {
                    val result = runCatching {
                        RendererRouter.render(project, mp, screen.composable_fqn,
                            parameterProviderFqn = screen.preview_provider_fqn, stateIndex = stateIndex,
                            sourceFilePath = sourceFile, previewConfig = config)
                    }.onFailure { e -> log.warn("onRefreshNode render failed for $nodeId stateIndex=$stateIndex", e) }
                        .getOrNull()
                    if (result == null) break
                    stateIndex++
                }
            } else {
                runCatching {
                    RendererRouter.render(project, mp, screen.composable_fqn,
                        parameterProviderFqn = null, stateIndex = -1,
                        sourceFilePath = sourceFile, previewConfig = config)
                }.onFailure { e -> log.warn("onRefreshNode render failed for $nodeId", e) }
            }
            val images = PreviewCache.listStateImages(mp, screen.composable_fqn)
            SwingUtilities.invokeLater {
                if (disposed) return@invokeLater
                // Update only this node's images in place — rebuilding the whole layout (reloadView)
                // reset pan/zoom and made the graph jump back to the entry node on every refresh.
                tab.updateNodeImages(nodeId, images)
                setAllBusy(false, "")
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

    private fun refreshPreviews() {
        setAllBusy(true, "Rendering previews…")
        AppExecutorUtil.getAppExecutorService().submit {
            if (disposed) return@submit
            val config = PreviewConfigService.getInstance(project).config
            val seen = mutableSetOf<Pair<String, String>>()
            val units = mutableListOf<RenderUnit>()
            var skippedNoFqn = 0
            graphSet.graphs.forEach { ng ->
                ng.graph.subgraphs.values.forEach { sub ->
                    sub.screens.forEach { s ->
                        if (s.composable_fqn.isBlank()) { skippedNoFqn++; return@forEach }
                        val mp = s.module_path.ifBlank { moduleDirs.firstOrNull() }.takeUnless { it.isNullOrBlank() } ?: return@forEach
                        val state = if (s.preview_provider_fqn != null) 0 else -1
                        if (seen.add(Pair(mp, s.composable_fqn))) {
                            val sourceFile = s.location.takeIf { it.isNotBlank() }?.let { File(mp, it).absolutePath }
                            units += RenderUnit(mp, s.composable_fqn, s.preview_provider_fqn, state, sourceFile)
                        }
                    }
                }
            }
            val total = units.size
            if (total == 0) {
                log.warn("refreshPreviews: no renderable screens (skippedNoFqn=$skippedNoFqn)")
            }
            val renderErrors = mutableListOf<String>()
            units.forEachIndexed { i, u ->
                if (disposed) return@submit
                if (u.provider != null) {
                    // Start from a clean slate so the on-disk state set matches exactly what the
                    // provider yields — removes phantom duplicate states left by earlier over-renders
                    // and prevents incremental skip from reading stale higher-index files as valid.
                    PreviewCache.clearIndexedFiles(u.modulePath, u.fqn)
                    var stateIndex = 0
                    while (!disposed) {
                        val result = runCatching {
                            RendererRouter.render(project, u.modulePath, u.fqn,
                                parameterProviderFqn = u.provider, stateIndex = stateIndex,
                                sourceFilePath = u.sourceFile, previewConfig = config)
                        }.onFailure { e ->
                            log.warn("render failed for ${u.fqn} stateIndex=$stateIndex", e)
                            renderErrors += "${u.fqn}: ${e.message ?: e::class.simpleName}"
                        }.getOrNull()
                        if (result == null) break
                        stateIndex++
                    }
                } else {
                    runCatching {
                        RendererRouter.render(project, u.modulePath, u.fqn,
                            parameterProviderFqn = null, stateIndex = -1,
                            sourceFilePath = u.sourceFile, previewConfig = config)
                    }.onFailure { e ->
                        log.warn("render failed for ${u.fqn}", e)
                        renderErrors += "${u.fqn}: ${e.message ?: e::class.simpleName}"
                    }
                }
                val done = i + 1
                SwingUtilities.invokeLater { if (!disposed) setAllStatus("Rendering $done/$total…") }
            }
            SwingUtilities.invokeLater {
                if (disposed) return@invokeLater
                tabPanels.forEach { it.reloadView() }
                val warnings = buildList {
                    if (skippedNoFqn > 0) add("$skippedNoFqn screen(s) skipped — composable_fqn not set in fragment JSON")
                    addAll(renderErrors)
                }
                if (warnings.isNotEmpty()) setAllProblems(emptyList(), warnings)
                setAllBusy(false, "")
            }
        }
    }

    private fun setAllStatus(text: String) = tabPanels.forEach { it.setStatus(text) }
    private fun setAllProblems(errors: List<String>, warnings: List<String>) = tabPanels.forEach { it.setProblems(errors, warnings) }
    private fun setAllBusy(busy: Boolean, status: String) = tabPanels.forEach { it.setBusy(busy, status) }

    override fun dispose() { disposed = true }

    companion object {
        val TAB_KEY: Key<GraphTabPanel> = Key.create("appflower.graphTab")
    }
}
