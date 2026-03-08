package com.keymusicman.appflower.viewmodel

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.model.LayoutGraph
import com.keymusicman.appflower.model.buildLayoutGraph
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.measureTime

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
    val zoomState: MutableState<Float> = mutableStateOf(0.5f)
    val panState: MutableState<Offset> = mutableStateOf(Offset.Zero)
    val selectedStateByNodeId: MutableState<Map<String, Int>> = mutableStateOf(emptyMap())
    val statePickerNodeId: MutableState<String?> = mutableStateOf(null)
    var viewportWidth: Float = 0f
    var viewportHeight: Float = 0f

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

    fun buildFromAppGraphV2(appGraph: AppGraph, projectPath: String? = null) {
        scope.launch {
            val time = measureTime {
                val layoutGraph = buildLayoutGraph(appGraph, projectPath, scale = .5f)
                layoutGraphState.value = layoutGraph
                selectedStateByNodeId.value = layoutGraph.nodes.values.associate { node ->
                    node.id to node.selectedState.coerceAtLeast(0)
                }
            }

            println("Graph layout completed in ${time.inWholeMilliseconds} ms for ${layoutGraphState.value?.nodes?.size ?: 0} nodes and ${layoutGraphState.value?.edges?.size ?: 0} edges")
        }
    }

    fun getSelectedState(nodeId: String, statesCount: Int, fallback: Int = 0): Int {
        val defaultValue = fallback.coerceAtLeast(0)
        val selected = selectedStateByNodeId.value[nodeId] ?: defaultValue
        val maxIndex = (statesCount - 1).coerceAtLeast(0)
        return selected.coerceIn(0, maxIndex)
    }

    fun selectState(nodeId: String, selectedState: Int, statesCount: Int) {
        val normalized = selectedState
            .coerceAtLeast(0)
            .coerceAtMost((statesCount - 1).coerceAtLeast(0))
        selectedStateByNodeId.value = selectedStateByNodeId.value + (nodeId to normalized)
    }

    fun openStatePicker(nodeId: String) {
        statePickerNodeId.value = nodeId
    }

    fun closeStatePicker() {
        statePickerNodeId.value = null
    }

    fun applySelectedStates(appGraph: AppGraph): AppGraph {
        val selectedById = selectedStateByNodeId.value
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
