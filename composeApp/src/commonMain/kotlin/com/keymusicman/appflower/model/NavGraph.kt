package com.keymusicman.appflower.model

import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.sin

@Serializable
data class Transition(
    val from: String,
    val from_path: String? = null,
    val to: String,
    val to_path: String? = null,
    val trigger: String? = null
)

@Serializable
data class AppGraph(
    val transitions: List<Transition>
)

data class Node(
    val id: String,
    // list of available state images for the node (ordered by index)
    val imagePaths: List<String> = emptyList(),
    var selectedState: Int = 0,
    var x: Float = 0f,
    var y: Float = 0f,
    var width: Float = 0f,
    var height: Float = 0f
)

data class Edge(
    val from: String,
    val to: String,
    val trigger: String? = null
)

data class Graph(
    val nodes: Set<Node>,
    val edges: List<Edge>
) {
    companion object {
        fun from(appGraph: AppGraph, projectPath: String? = null): Graph {
            val projectPath = projectPath?.trim()
            val nodesMap = mutableMapOf<String, Node>()
            val edges = mutableListOf<Edge>()

            appGraph.transitions.forEach { transition ->
                if (!nodesMap.containsKey(transition.from)) {
                    val fromImages = transition.from_path?.let { findImages(it, transition.from, projectPath) } ?: emptyList()
                    nodesMap[transition.from] = Node(transition.from, fromImages)
                }
                if (!nodesMap.containsKey(transition.to)) {
                    val toImages = transition.to_path?.let { findImages(it, transition.to, projectPath) } ?: emptyList()
                    nodesMap[transition.to] = Node(transition.to, toImages)
                }
                edges.add(Edge(transition.from, transition.to, transition.trigger))
            }

            val nodes = layoutNodes(nodesMap.values.toList())
            return Graph(nodes.toSet(), edges)
        }

        private fun findImages(basePath: String, nodeName: String, projectPath: String?): List<String> {
            val regex = Regex("${nodeName}_.+?_(\\d+)\\.png")
            return try {
                val fullPath = if (projectPath != null) {
                    java.io.File(projectPath, basePath)
                } else {
                    java.io.File(basePath)
                }
                if (fullPath.exists() && fullPath.isDirectory) {
                    fullPath.listFiles()
                        ?.filter { regex.matches(it.name) }
                        ?.sortedBy { file ->
                            val match = regex.find(file.name)
                            match?.groups?.get(1)?.value?.toIntOrNull() ?: 0
                        }
                        ?.map { it.absolutePath } ?: emptyList()
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        private fun layoutNodes(nodes: List<Node>): List<Node> {
            if (nodes.isEmpty()) return nodes

            val layoutedNodes = nodes.toMutableList()
            val count = layoutedNodes.size
            val radius = 500f
            val centerX = 600f
            val centerY = 400f

            // simple circular layout but jitter positions to reduce overlap based on expected sizes
            layoutedNodes.forEachIndexed { index, node ->
                val angle = (2 * Math.PI * index) / count
                var x = (centerX + radius * cos(angle)).toFloat()
                var y = (centerY + radius * sin(angle)).toFloat()
                // apply small jitter based on index to avoid exact collisions
                val jitter = (index % 5) * 8f
                x += jitter
                y += (index % 3) * 6f
                node.x = x
                node.y = y
            }

            return layoutedNodes
        }
    }
}
