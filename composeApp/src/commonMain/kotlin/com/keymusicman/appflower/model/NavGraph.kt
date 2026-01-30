package com.keymusicman.appflower.model

import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.geometry.Offset

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
    // position and size removed from static assignment; sizes will still be stored but positions are computed dynamically
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

            val nodes = layoutNodes(nodesMap.values.toList(), edges)
            return Graph(nodes.toSet(), edges)
        }

        private fun findImages(basePath: String, nodeName: String, projectPath: String?): List<String> {
            val regex = Regex("${nodeName}(?:_.+?)?_(\\d+)\\.png")
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

        // Left-to-right flow layout. Pure function: does not mutate input nodes and returns new node instances
        // expose layout computation for dynamic placement during UI layout

        fun computeLayoutNodes(nodes: List<Node>, edges: List<Edge>, containerW: Float, containerH: Float): List<Pair<String, Offset>> {
            if (nodes.isEmpty()) return emptyList()

            // reuse previous algorithm to compute depth and index but return centers as Offsets scaled to container
            val adjacency: MutableMap<String, MutableList<String>> = nodes.associate { it.id to mutableListOf<String>() }.toMutableMap()
            val incomingCount: MutableMap<String, Int> = nodes.associate { it.id to 0 }.toMutableMap()
            edges.forEach { e ->
                if (adjacency.containsKey(e.from)) {
                    adjacency[e.from]?.add(e.to)
                }
                incomingCount[e.to] = (incomingCount[e.to] ?: 0) + 1
            }

            val entryIds = nodes.map { it.id }.filter { incomingCount[it] == 0 }
            val startIds = entryIds.ifEmpty { nodes.map { it.id } }

            val depthMap = mutableMapOf<String, Int>()

            fun explore(id: String, curDepth: Int, stack: MutableSet<String>) {
                val prev = depthMap[id]
                if (prev != null && curDepth <= prev) return
                depthMap[id] = curDepth
                if (!stack.add(id)) return
                val neighbors = adjacency[id] ?: emptyList()
                for (n in neighbors) explore(n, curDepth + 1, stack)
                stack.remove(id)
            }

            for (s in startIds) explore(s, 0, mutableSetOf())
            nodes.forEach { if (!depthMap.containsKey(it.id)) depthMap[it.id] = 0 }
            val nodesByDepth: Map<Int, List<String>> = depthMap.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.sorted() }
            val maxDepth = nodesByDepth.keys.maxOrNull() ?: 0

            val leftMargin = 100f
            val topMargin = 100f
            val usableW = (containerW - leftMargin * 2).coerceAtLeast(400f)
            val horizontalGap = 600f

            val result = mutableListOf<Pair<String, Offset>>()
            for ((d, ids) in nodesByDepth) {
                val indexMap = ids.withIndex().associate { it.value to it.index }
                for (id in ids) {
                    val index = indexMap[id] ?: 0
                    val x = leftMargin + d * horizontalGap
                    val y = topMargin + index * 900f
                    result.add(id to Offset(x, y))
                }
            }
            return result
        }

        private fun layoutNodes(nodes: List<Node>, edges: List<Edge>): List<Node> {
            if (nodes.isEmpty()) return nodes

            // build adjacency and incoming count maps
            val adjacency: MutableMap<String, MutableList<String>> = nodes.associate { it.id to mutableListOf<String>() }.toMutableMap()
            val incomingCount: MutableMap<String, Int> = nodes.associate { it.id to 0 }.toMutableMap()
            edges.forEach { e ->
                if (adjacency.containsKey(e.from)) {
                    adjacency[e.from]?.add(e.to)
                }
                incomingCount[e.to] = (incomingCount[e.to] ?: 0) + 1
            }

            // find entry nodes (no incoming edges).
            val entryIds = nodes.map { it.id }.filter { incomingCount[it] == 0 }
            val startIds = entryIds.ifEmpty { nodes.map { it.id } }

            // depth map: node id -> depth (max depth found)
            val depthMap = mutableMapOf<String, Int>()

            // DFS explore from each start id, avoid following edges that close a cycle on the current path
            fun explore(id: String, curDepth: Int, stack: MutableSet<String>) {
                val prev = depthMap[id]
                if (prev != null && curDepth <= prev) return // no improvement
                depthMap[id] = curDepth
                if (!stack.add(id)) return // cycle detected - do not follow further to avoid increasing depth
                val neighbors = adjacency[id] ?: emptyList()
                for (n in neighbors) {
                    explore(n, curDepth + 1, stack)
                }
                stack.remove(id)
            }

            for (s in startIds) {
                explore(s, 0, mutableSetOf())
            }

            // ensure every node has a depth (unreachable nodes get depth 0)
            nodes.forEach { if (!depthMap.containsKey(it.id)) depthMap[it.id] = 0 }

            // group nodes by depth, deterministic order by id
            val nodesByDepth: Map<Int, List<String>> = depthMap.entries
                .groupBy({ it.value }, { it.key })
                .mapValues { it.value.sorted() }

            val maxDepth = nodesByDepth.keys.maxOrNull() ?: 0

            // layout parameters (pixels)
            val horizontalGap = 400f
            val verticalGap = 1000f
            val leftMargin = 100f
            val topMargin = 100f

            // precompute index for each node within its depth column
            val indexByDepthAndId = mutableMapOf<Int, Map<String, Int>>()
            for ((depth, ids) in nodesByDepth) {
                val indexMap = ids.withIndex().associate { it.value to it.index }
                indexByDepthAndId[depth] = indexMap
            }

            // produce new node instances with assigned x/y
            return nodes.map { original ->
                val d = depthMap[original.id] ?: 0
                val x = leftMargin + d * horizontalGap
                val idsAtDepth = nodesByDepth[d] ?: listOf(original.id)
                val index = indexByDepthAndId[d]?.get(original.id) ?: 0
                val y = topMargin + index * verticalGap
                original.copy(x = x, y = y)
            }
        }
    }
}
