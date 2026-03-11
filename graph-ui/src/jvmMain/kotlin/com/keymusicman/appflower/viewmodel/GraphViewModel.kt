package com.keymusicman.appflower.viewmodel

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import co.touchlab.kermit.Logger
import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.model.LayoutGraph
import com.keymusicman.appflower.model.buildLayoutGraph
import com.keymusicman.appflower.model.filterToView
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * View model to construct and expose the LayoutGraph for the UI.
 * - layoutGraphState: current built LayoutGraph or null
 * - zoomState: simple zoom holder shared with UI
 * - buildFromAppGraphV2: load and layout from AppGraph asynchronously
 */
class GraphViewModel {
    private val scope =
        CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("GraphViewModel"))

    val layoutGraphState: MutableState<LayoutGraph?> = mutableStateOf(null)
    val appGraphState: MutableState<AppGraph?> = mutableStateOf(null)
    val zoomState: MutableState<Float> = mutableStateOf(0.5f)
    val panState: MutableState<Offset> = mutableStateOf(Offset.Zero)
    private val selectedStateByNodeId: MutableState<Map<String, Int>> = mutableStateOf(emptyMap())
    val statePickerNodeId: MutableState<String?> = mutableStateOf(null)
    var viewportWidth: Float = 0f
    var viewportHeight: Float = 0f

    val selectedNodeIds: MutableState<Set<String>> = mutableStateOf(emptySet())
    val views: MutableState<List<GraphView>> = mutableStateOf(emptyList())
    val activeViewId: MutableState<String?> = mutableStateOf(null)
    private var currentProjectPath: String? = null

    val activeDisplayGraph: LayoutGraph?
        get() {
            val full = layoutGraphState.value ?: return null
            val viewId = activeViewId.value ?: return full
            val view = views.value.find { it.id == viewId } ?: return full
            return full.filterToView(view.nodeIds)
        }

    companion object {
        const val ZOOM_MIN = 0.1f
        const val ZOOM_MAX = 3.0f
    }

    fun zoom(factor: Float) {
        val cx = viewportWidth / 2f
        val cy = viewportHeight / 2f
        val pan = panState.value
        val newZoom = (zoomState.value * factor).coerceIn(ZOOM_MIN, ZOOM_MAX)
        val actualFactor = newZoom / zoomState.value
        panState.value = Offset(
            cx * (1f - actualFactor) + pan.x * actualFactor,
            cy * (1f - actualFactor) + pan.y * actualFactor
        )
        zoomState.value = newZoom
    }

    /** Set zoom to an absolute value, keeping the viewport center fixed. */
    fun setZoom(newZoom: Float) {
        val clamped = newZoom.coerceIn(ZOOM_MIN, ZOOM_MAX)
        val cx = viewportWidth / 2f
        val cy = viewportHeight / 2f
        val pan = panState.value
        val factor = clamped / zoomState.value
        panState.value = Offset(
            cx * (1f - factor) + pan.x * factor,
            cy * (1f - factor) + pan.y * factor
        )
        zoomState.value = clamped
    }

    fun panToNode(nodeId: String) {
        val node = layoutGraphState.value?.nodes?.get(nodeId) ?: return
        val zoom = zoomState.value
        panState.value = Offset(
            viewportWidth / 2f - node.x * zoom,
            viewportHeight / 2f - node.y * zoom
        )
    }

    fun buildFromAppGraphV2(appGraph: AppGraph, projectPath: String? = null) {
        currentProjectPath = projectPath
        appGraphState.value = appGraph
        views.value = emptyList()
        activeViewId.value = null
        selectedNodeIds.value = emptySet()
        if (projectPath != null) loadViews(projectPath)
        scope.launch {
            val startedAtNanos = System.nanoTime()
            val layoutGraph = buildLayoutGraph(appGraph, projectPath, scale = .5f)
            layoutGraphState.value = layoutGraph
            selectedStateByNodeId.value = layoutGraph.nodes.values.associate { node ->
                node.id to node.selectedState.coerceAtLeast(0)
            }
            val elapsedMilliseconds = (System.nanoTime() - startedAtNanos) / 1_000_000

            Logger.d { "Graph layout completed in $elapsedMilliseconds ms for ${layoutGraphState.value?.nodes?.size ?: 0} nodes and ${layoutGraphState.value?.edges?.size ?: 0} edges" }
        }
    }

    fun toggleNodeSelection(nodeId: String) {
        val current = selectedNodeIds.value
        selectedNodeIds.value = if (nodeId in current) current - nodeId else current + nodeId
    }

    fun clearSelection() {
        selectedNodeIds.value = emptySet()
    }

    fun createView(name: String) {
        val nodeIds = selectedNodeIds.value
        if (nodeIds.isEmpty()) return
        val view = GraphView(name = name, nodeIds = nodeIds)
        views.value = views.value + view
        activeViewId.value = view.id
        selectedNodeIds.value = emptySet()
        saveViews()
    }

    fun createPathView(name: String) {
        val selected = selectedNodeIds.value.toList()
        if (selected.size != 2) return
        val edges = layoutGraphState.value?.edges ?: return
        val pathNodes = GraphPathFinder.findPathNodes(selected[0], selected[1], edges)
        val view = GraphView(name = name, nodeIds = pathNodes)
        views.value = views.value + view
        activeViewId.value = view.id
        selectedNodeIds.value = emptySet()
        saveViews()
    }

    fun createSubgraphView(subgraphKey: String) {
        val layoutGraph = layoutGraphState.value ?: return
        val nodeIds = layoutGraph.nodes.keys.filter { it.startsWith("$subgraphKey:") }.toSet()
        if (nodeIds.isEmpty()) return
        val view = GraphView(name = subgraphKey, nodeIds = nodeIds)
        views.value = views.value + view
        activeViewId.value = view.id
        saveViews()
    }

    fun activateView(id: String?) {
        activeViewId.value = id
        selectedNodeIds.value = emptySet()
    }

    fun deleteView(id: String) {
        views.value = views.value.filter { it.id != id }
        if (activeViewId.value == id) activeViewId.value = null
        saveViews()
    }

    private fun viewsFile(projectPath: String) =
        java.io.File(projectPath, ".appflower/views.json")

    private fun saveViews() {
        val path = currentProjectPath ?: return
        scope.launch {
            val json = Json.encodeToString(views.value)
            val file = viewsFile(path)
            file.parentFile?.mkdirs()
            file.writeText(json)
        }
    }

    private fun loadViews(projectPath: String) {
        val file = viewsFile(projectPath)
        if (!file.exists()) return
        try {
            views.value = Json.decodeFromString(file.readText())
        } catch (e: Exception) {
            Logger.w { "Failed to load views: ${e.message}" }
        }
    }

    fun selectState(nodeId: String, selectedState: Int, statesCount: Int) {
        Logger.d { "Selecting state $selectedState for node $nodeId with statesCount $statesCount" }

        val startedAtNanos = System.nanoTime()
        val normalized = selectedState
            .coerceAtLeast(0)
            .coerceAtMost((statesCount - 1).coerceAtLeast(0))
        selectedStateByNodeId.value += (nodeId to normalized)
        val currentGraph = layoutGraphState.value ?: return
        val currentNode = currentGraph.nodes[nodeId] ?: return
        val updatedNodes =
            currentGraph.nodes + (nodeId to currentNode.copy(selectedState = normalized))
        layoutGraphState.value = currentGraph.copy(nodes = updatedNodes)
        val elapsedMilliseconds = (System.nanoTime() - startedAtNanos) / 1_000_000

        Logger.d {
            "Changed state of node $nodeId to $selectedState in $elapsedMilliseconds ms"
        }
    }

    fun openStatePicker(nodeId: String) {
        statePickerNodeId.value = nodeId
    }

    fun closeStatePicker() {
        statePickerNodeId.value = null
    }

    fun applySelectedStates(appGraph: AppGraph): AppGraph {
        val selectedById = layoutGraphState.value
            ?.nodes
            ?.mapValues { (_, node) -> node.selectedState }
            ?: selectedStateByNodeId.value
        return appGraph.copy(
            subgraphs = appGraph.subgraphs.mapValues { (subgraphKey, subgraph) ->
                subgraph.copy(
                    screens = subgraph.screens.map { screen ->
                        val nodeId = "$subgraphKey:${screen.id}"
                        val selected = selectedById[nodeId] ?: screen.selected_state
                        screen.copy(selected_state = selected.coerceAtLeast(0))
                    }
                )
            }
        )
    }

}
