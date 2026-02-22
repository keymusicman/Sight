package com.keymusicman.appflower.viewmodel

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.model.AppGraphV2
import com.keymusicman.appflower.model.Graph
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

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
    val zoomState: MutableState<Float> = mutableStateOf(1f)

    fun buildFromAppGraph(appGraph: AppGraph, projectPath: String? = null) {
        graphState.value = Graph.from(appGraph, projectPath)
    }

    fun buildFromAppGraphV2(appGraphV2: AppGraphV2, projectPath: String? = null) {
        graphState.value = Graph.fromV2(appGraphV2, projectPath)
    }

    fun loadFromJsonFile(path: String, projectPath: String? = null) {
        try {
            val text = File(path).readText()
            val appGraphV2: AppGraphV2 = Json.decodeFromString(text)
            graphState.value = Graph.fromV2(appGraphV2, projectPath)
        } catch (e: Exception) {
            // on failure clear graph (caller may observe and react)
            graphState.value = null
        }
    }
}
