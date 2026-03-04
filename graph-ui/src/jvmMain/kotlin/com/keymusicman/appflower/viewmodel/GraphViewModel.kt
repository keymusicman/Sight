package com.keymusicman.appflower.viewmodel

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.model.LayoutGraph
import com.keymusicman.appflower.model.buildLayoutGraph
import kotlinx.coroutines.launch
import kotlin.time.measureTime

/**
 * View model to construct and expose the LayoutGraph for the UI.
 * - layoutGraphState: current built LayoutGraph or null
 * - zoomState: simple zoom holder shared with UI
 * - buildFromAppGraphV2: load and layout from AppGraph asynchronously
 */
class GraphViewModel : ViewModel() {
    val layoutGraphState: MutableState<LayoutGraph?> = mutableStateOf(null)
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
                val layoutGraph = buildLayoutGraph(appGraph, projectPath, scale = .5f)
                layoutGraphState.value = layoutGraph
            }

            println("Graph layout completed in ${time.inWholeSeconds} seconds")
        }
    }

}
