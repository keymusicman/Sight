package com.keymusicman.appflower.viewmodel

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.model.Graph

/**
 * Minimal view model to construct and expose the Graph for the UI.
 * - graphState: current built Graph or null
 * - zoomState: simple zoom holder shared with UI
 * - buildFromAppGraph: build graph from an in-memory AppGraph (v1.0 legacy)
 * - buildFromAppGraphV2: build graph from an in-memory AppGraphV2 (v2.0)
 * - loadFromJsonFile: read AppGraphV2 JSON from disk and build
 */
class GraphViewModel {
    val graphState: MutableState<Graph?> = mutableStateOf(null)
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
        graphState.value = Graph.fromV2(appGraph, projectPath)
    }

}
