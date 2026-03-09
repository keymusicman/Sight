package com.keymusicman.appflower.loader

import co.touchlab.kermit.Logger
import com.keymusicman.appflower.model.AppGraph
import kotlinx.serialization.json.Json
import java.io.File

object GraphLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadGraphFromProject(projectPath: String): AppGraph? {
        val graphFile = File(projectPath.trim(), "app/build/graph/app-graph.json")
        return if (graphFile.exists()) {
            try {
                val content = graphFile.readText()
                json.decodeFromString<AppGraph>(content)
            } catch (e: Exception) {
                Logger.w { "Error loading graph: ${e.message}" }
                e.printStackTrace()
                null
            }
        } else {
            Logger.i { "Graph file not found at: ${graphFile.absolutePath}" }
            null
        }
    }

    fun loadFromFile(file: File): AppGraph? {
        return if (file.exists()) {
            try {
                json.decodeFromString<AppGraph>(file.readText())
            } catch (e: Exception) {
                null
            }
        } else null
    }

    fun findGraphFile(startPath: String): File? {
        var current = File(startPath)
        val maxDepth = 5
        var depth = 0

        while (depth < maxDepth && current.isDirectory) {
            val graphFile = File(current, "build/graph/app-graph.json")
            if (graphFile.exists()) {
                return graphFile
            }
            current = current.parentFile ?: break
            depth++
        }
        return null
    }
}
