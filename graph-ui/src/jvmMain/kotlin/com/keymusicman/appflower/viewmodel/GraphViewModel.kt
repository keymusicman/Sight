package com.keymusicman.appflower.viewmodel

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.model.GraphNode
import com.keymusicman.appflower.model.LayoutGraph
import com.keymusicman.appflower.model.buildLayoutGraph
import com.keymusicman.appflower.model.flattenAppGraph
import com.keymusicman.appflower.model.getImageDimension
import kotlinx.coroutines.launch
import kotlin.time.measureTime

/**
 * View model to construct and expose the LayoutGraph for the UI.
 * - layoutGraphState: current built LayoutGraph or null
 * - nodesState: nodes with image paths for lazy loading in UI
 * - zoomState: simple zoom holder shared with UI
 * - buildFromAppGraphV2: load and layout from AppGraph asynchronously on IO dispatcher
 */
class GraphViewModel : ViewModel() {
    val layoutGraphState: MutableState<LayoutGraph?> = mutableStateOf(null)
    val nodesState: MutableState<List<GraphNode>?> = mutableStateOf(null)
    val zoomState: MutableState<Float> = mutableStateOf(0.5f)
    val panState: MutableState<Offset> = mutableStateOf(Offset.Zero)
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
        viewModelScope.launch {
            val time = measureTime {
                val (nodes, edges) = flattenAppGraph(appGraph, projectPath)
                nodesState.value = nodes

                // Load image dimensions efficiently (just metadata, not full image data)
                val imageDimensions: Map<String, Pair<Int, Int>> = nodes.associate { node ->
                    val dim = node.imagePaths.firstOrNull()
                        ?.let { path ->
                            getImageDimension(path)
                        }
                    node.id to (dim ?: (540 to 360))
                }

                val layoutGraph = buildLayoutGraph(
                    nodes,
                    edges,
                    imageDimensions = imageDimensions
                )
                layoutGraphState.value = layoutGraph
            }

            println("Graph layout completed in ${time.inWholeSeconds} seconds")
        }
    }

}
