package com.keymusicman.appflower.viewmodel

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.model.Graph
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Minimal view model to construct and expose the Graph for the UI.
 * - graphState: current built Graph or null
 * - zoomState: simple zoom holder shared with UI
 * - buildFromAppGraph: build graph from an in-memory AppGraph
 * - loadFromJsonFile: read AppGraph JSON from disk and build
 */
class GraphViewModel {
    val graphState: MutableState<Graph?> = mutableStateOf(null)
    val zoomState: MutableState<Float> = mutableStateOf(1f)

    fun buildFromAppGraph(appGraph: AppGraph, projectPath: String? = null) {
        graphState.value = Graph.from(appGraph, projectPath)
    }

    fun loadFromJsonFile(path: String, projectPath: String? = null) {
        try {
            val text = File(path).readText()
            val appGraph: AppGraph = Json.decodeFromString(text)
            graphState.value = Graph.from(appGraph, projectPath)
        } catch (e: Exception) {
            // on failure clear graph (caller may observe and react)
            graphState.value = null
        }
    }
}
