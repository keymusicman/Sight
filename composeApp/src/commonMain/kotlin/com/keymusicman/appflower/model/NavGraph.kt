package com.keymusicman.appflower.model

import kotlinx.serialization.Serializable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap

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
    var selectedState: Int = 0
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
            // legacy stub: domain Node must not hold geometry. Return input unchanged.
            return nodes
        }
    }
}


// Immutable layout models and layout builder

data class GraphNode(
    val id: String,
    val imagePaths: List<String> = emptyList(),
    val selectedState: Int = 0
)

data class GraphEdge(
    val from: String,
    val to: String,
    val trigger: String? = null
)

/** Simple point class for layout geometry */
data class PointF(val x: Float, val y: Float)

data class LayoutNode(
    val id: String,
    val x: Float, // center x
    val y: Float, // center y
    val width: Float,
    val height: Float
)

data class LayoutEdge(
    val from: String,
    val to: String,
    val points: List<PointF>
)

data class LayoutGraph(
    val nodes: Map<String, LayoutNode>,
    val edges: List<LayoutEdge>
)

/**
 * Build a fully immutable, render-ready layout graph.
 * - sizes are taken from provided bitmaps (width/3, height/3)
 * - columns are assigned by depth from the entry node (left-to-right)
 * - nodes in a column are stacked vertically; merges are centered relative to incoming lanes
 * - edges are routed orthogonally with a simple 3-bend polyline (start -> midX -> end)
 */
fun buildLayoutGraph(
    nodes: List<GraphNode>,
    edges: List<GraphEdge>,
    bitmaps: Map<String, ImageBitmap>
): LayoutGraph {
    if (nodes.isEmpty()) return LayoutGraph(emptyMap(), emptyList())

    // maps for quick lookup
    val nodeIds = nodes.map { it.id }
    val adjacency: MutableMap<String, MutableList<String>> = nodeIds.associateWith { mutableListOf<String>() }.toMutableMap()
    val incomingCount: MutableMap<String, Int> = nodeIds.associateWith { 0 }.toMutableMap()

    edges.forEach { e ->
        if (adjacency.containsKey(e.from)) adjacency[e.from]?.add(e.to)
        incomingCount[e.to] = (incomingCount[e.to] ?: 0) + 1
    }

    // find entry node (no incoming). If multiple, pick deterministic first by id.
    val entries = nodeIds.filter { incomingCount[it] == 0 }
    val entryId = if (entries.isNotEmpty()) entries.sorted().first() else nodeIds.sorted().first()

    // compute depth via BFS (shortest distance from entry)
    val depthMap = mutableMapOf<String, Int>()
    val queue = ArrayDeque<String>()
    depthMap[entryId] = 0
    queue.add(entryId)

    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        val curDepth = depthMap[cur] ?: 0
        val neighbors = adjacency[cur] ?: emptyList()
        for (n in neighbors) {
            val prev = depthMap[n]
            if (prev == null || curDepth + 1 < prev) {
                depthMap[n] = curDepth + 1
                queue.add(n)
            }
        }
    }
    // ensure all nodes have a depth
    nodes.forEach { if (!depthMap.containsKey(it.id)) depthMap[it.id] = 0 }

    // group nodes by depth deterministically
    val nodesByDepth: Map<Int, List<String>> = depthMap.entries
        .groupBy({ it.value }, { it.key })
        .mapValues { it.value.sorted() }

    val sortedDepths = nodesByDepth.keys.sorted()

    // compute sizes from bitmaps
    val sizeById: Map<String, Pair<Float, Float>> = nodes.associate { n ->
        val bmp = bitmaps[n.id]
        val w = bmp?.width?.toFloat()?.div(3f) ?: 180f
        val h = bmp?.height?.toFloat()?.div(3f) ?: 120f
        n.id to (w to h)
    }.toMap()

    // layout parameters (pixels)
    val leftMargin = 100f
    val topMargin = 100f
    val horizontalGap = 400f
    val verticalGap = 100f

    val layoutNodeMap = mutableMapOf<String, LayoutNode>()

    // iterate depths left-to-right and assign positions
    for (d in sortedDepths) {
        val ids = nodesByDepth[d] ?: continue
        var gapHeight = 0f
        for ((index, id) in ids.withIndex()) {
            val (w, h) = sizeById[id] ?: (180f to 120f)
            val x = leftMargin + d * horizontalGap
            var y = topMargin + gapHeight
            gapHeight += h + verticalGap

            // if has incoming from earlier depths, center vertically relative to those sources
//            val incomingFrom = edges.filter { it.to == id }.map { it.from }.filter { depthMap[it] ?: 0 < d }
//            if (incomingFrom.isNotEmpty()) {
//                val ys = incomingFrom.mapNotNull { layoutNodeMap[it]?.y }
//                if (ys.isNotEmpty()) {
//                    val avg = ys.sum() / ys.size
//                    y = avg
//                }
//            }

            layoutNodeMap[id] = LayoutNode(id = id, x = x, y = y, width = w, height = h)
        }
    }

    // produce layout node list in deterministic order (by depth then id)
    val layoutNodesList = sortedDepths.flatMap { d ->
        (nodesByDepth[d] ?: emptyList()).mapNotNull { layoutNodeMap[it] }
    }

    // convert to map by id for deterministic lookup
    val layoutNodesMap: Map<String, LayoutNode> = layoutNodesList.associateBy { it.id }

    // route orthogonal edges: start at right-center of from, end at left-center of to
    val layoutEdges = edges.mapNotNull { e ->
        val fromNode = layoutNodesMap[e.from] ?: return@mapNotNull null
        val toNode = layoutNodesMap[e.to] ?: return@mapNotNull null

        val start = PointF(fromNode.x + fromNode.width / 2f, fromNode.y)
        val end = PointF(toNode.x - toNode.width / 2f, toNode.y)
        val midX = (start.x + end.x) / 2f

        val points = listOf(
            start,
            PointF(midX, start.y),
            PointF(midX, end.y),
            end
        )

        LayoutEdge(from = e.from, to = e.to, points = points)
    }

    return LayoutGraph(nodes = layoutNodesMap, edges = layoutEdges)
}
