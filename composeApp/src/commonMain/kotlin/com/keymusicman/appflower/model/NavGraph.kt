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
    val imagePath: String? = null,
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
                    val fromImage = transition.from_path?.let { findImage(it, transition.from, projectPath) }
                    nodesMap[transition.from] = Node(transition.from, fromImage)
                }
                if (!nodesMap.containsKey(transition.to)) {
                    val toImage = transition.to_path?.let { findImage(it, transition.to, projectPath) }
                    nodesMap[transition.to] = Node(transition.to, toImage)
                }
                edges.add(Edge(transition.from, transition.to, transition.trigger))
            }

            val nodes = layoutNodes(nodesMap.values.toList())
            return Graph(nodes.toSet(), edges)
        }

        private fun findImage(basePath: String, nodeName: String, projectPath: String?): String? {
            val regex = Regex("${nodeName}_.+?_0\\.png")
            return try {
                val fullPath = if (projectPath != null) {
                    java.io.File(projectPath, basePath)
                } else {
                    java.io.File(basePath)
                }
                if (fullPath.exists() && fullPath.isDirectory) {
                    fullPath.listFiles()?.find { regex.matches(it.name) }?.absolutePath
                } else {
                    null
                }
            } catch (e: Exception) {
                null
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
