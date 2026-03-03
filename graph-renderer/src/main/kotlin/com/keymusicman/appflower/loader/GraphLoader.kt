package com.keymusicman.appflower.loader

import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.model.AppGraphV2
import kotlinx.serialization.json.Json
import java.io.File

object GraphLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadGraphFromProject(projectPath: String): AppGraphV2? {
        val graphFile = File(projectPath.trim(), "app/build/graph/app-graph.json")
        return if (graphFile.exists()) {
            try {
                val content = graphFile.readText()
                json.decodeFromString<AppGraphV2>(content)
            } catch (e: Exception) {
                println("Error loading graph: ${e.message}")
                e.printStackTrace()
                null
            }
        } else {
            println("Graph file not found at: ${graphFile.absolutePath}")
            null
        }
    }

    fun loadFromFile(file: File): AppGraphV2? {
        return if (file.exists()) {
            try {
                json.decodeFromString<AppGraphV2>(file.readText())
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
