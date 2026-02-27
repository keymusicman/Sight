package com.keymusicman.appflower.model

import kotlinx.serialization.Serializable
import androidx.compose.ui.graphics.ImageBitmap

// Legacy v1.0 format (kept for reference, not used)
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

// v2.0 format
@Serializable
data class GraphMetadata(
    val version: String,
    val generated_at: String
)

@Serializable
data class Screen(
    val id: String,
    val function: String,
    val location: String,
    val screenshot_location: String
)

@Serializable
data class ConnectionEndpoint(
    val type: String,
    val subgraph: String,
    val screen_id: String? = null
)

@Serializable
data class Connection(
    val from: ConnectionEndpoint,
    val to: ConnectionEndpoint
)

@Serializable
data class Subgraph(
    val key: String,
    val qualified_name: String,
    val location: String,
    val root_screen: String,
    val screens: List<Screen>,
    val connections: List<Connection>
)

@Serializable
data class AppGraphV2(
    val metadata: GraphMetadata,
    val subgraphs: Map<String, Subgraph>
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

            val nodes = nodesMap.values.toList()
            return Graph(nodes.toSet(), edges)
        }

        fun fromV2(appGraphV2: AppGraphV2, projectPath: String? = null): Graph {
            val projectPath = projectPath?.trim()
            val nodesMap = mutableMapOf<String, Node>()
            val edges = mutableListOf<Edge>()
            
            // Build map of subgraph key to root_screen for resolving subgraph targets
            val subgraphRoots = appGraphV2.subgraphs.mapValues { (_, subgraph) ->
                "${subgraph.key}:${subgraph.root_screen}"
            }

            // Extract all screens from all subgraphs
            appGraphV2.subgraphs.forEach { (subgraphKey, subgraph) ->
                subgraph.screens.forEach { screen ->
                    val nodeId = "$subgraphKey:${screen.id}"
                    val imagePaths = findImagesInLocation(screen.screenshot_location, screen.id, projectPath)
                    nodesMap[nodeId] = Node(nodeId, imagePaths)
                }
            }

            // Collect connections from all subgraphs
            appGraphV2.subgraphs.forEach { (_, subgraph) ->
                subgraph.connections.forEach { connection ->
                    val fromId = if (connection.from.type == "screen") {
                        "${connection.from.subgraph}:${connection.from.screen_id}"
                    } else {
                        // Should not happen for "from", but handle gracefully
                        subgraphRoots[connection.from.subgraph]
                    }

                    val toId = if (connection.to.type == "screen") {
                        "${connection.to.subgraph}:${connection.to.screen_id}"
                    } else if (connection.to.type == "subgraph") {
                        // Resolve to root screen of target subgraph
                        subgraphRoots[connection.to.subgraph]
                    } else {
                        null
                    }

                    if (fromId != null && toId != null) {
                        edges.add(Edge(fromId, toId, null))
                    }
                }
            }

            return Graph(nodesMap.values.toSet(), edges)
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

        private fun findImagesInLocation(screenshotLocation: String, screenId: String, projectPath: String?): List<String> {
            // Screenshot location pattern: {screenId}_{variant}_{index}.png
            val regex = Regex("${screenId}(?:_.+?)?_(\\d+)\\.png")
            return try {
                val fullPath = if (projectPath != null) {
                    java.io.File(projectPath, screenshotLocation)
                } else {
                    java.io.File(screenshotLocation)
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
    val entryId = if (entries.isNotEmpty()) entries.first() else nodeIds.first()

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

    // minimum edge-to-edge gaps (pixels)
    val leftMargin = 100f
    val topMargin = 100f
    val minHorizontalGap = 80f   // min gap between right edge of one column and left edge of next
    val minVerticalGap = 40f     // min gap between bottom edge of one node and top edge of the next

    // pre-compute max width and max height per depth column
    val maxWidthByDepth: Map<Int, Float> = sortedDepths.associateWith { d ->
        (nodesByDepth[d] ?: emptyList()).maxOfOrNull { id ->
            sizeById[id]?.first ?: 180f
        } ?: 180f
    }

    // compute center-X for each column: accounts for both neighboring columns' widths
    val columnX = mutableMapOf<Int, Float>()
    for (d in sortedDepths) {
        columnX[d] = if (d == sortedDepths.first()) {
            leftMargin + (maxWidthByDepth[d] ?: 180f) / 2f
        } else {
            val prevD = sortedDepths[sortedDepths.indexOf(d) - 1]
            (columnX[prevD] ?: leftMargin) +
                (maxWidthByDepth[prevD] ?: 180f) / 2f +
                minHorizontalGap +
                (maxWidthByDepth[d] ?: 180f) / 2f
        }
    }

    // build children map (parent → ordered list of direct children)
    val childrenMap: Map<String, List<String>> = nodeIds.associateWith { id ->
        (adjacency[id] ?: emptyList()).sorted()
    }

    // compute subtree height bottom-up (memoized): vertical space a node + its descendants need
    val subtreeHCache = mutableMapOf<String, Float>()
    fun subtreeH(id: String, visiting: Set<String> = emptySet()): Float {
        subtreeHCache[id]?.let { return it }
        val (_, h) = sizeById[id] ?: (180f to 120f)
        val kids = (childrenMap[id] ?: emptyList()).filter { it !in visiting }
        val result = if (kids.isEmpty()) {
            h
        } else {
            val inner = visiting + id
            val kidsH = kids.sumOf { subtreeH(it, inner).toDouble() }.toFloat() +
                (kids.size - 1) * minVerticalGap
            maxOf(h, kidsH)
        }
        subtreeHCache[id] = result
        return result
    }
    nodeIds.forEach { subtreeH(it) }

    val layoutNodeMap = mutableMapOf<String, LayoutNode>()
    val visited = mutableSetOf<String>()

    // DFS top-down: assign center-Y so each parent is centered on its children's span
    fun assignY(id: String, topY: Float) {
        if (id in visited) return
        visited.add(id)
        val (w, h) = sizeById[id] ?: (180f to 120f)
        val d = depthMap[id] ?: 0
        val x = columnX[d] ?: leftMargin
        val kids = (childrenMap[id] ?: emptyList()).filter { it !in visited }
        if (kids.isEmpty()) {
            layoutNodeMap[id] = LayoutNode(id = id, x = x, y = topY + h / 2f, width = w, height = h)
        } else {
            val kidsH = kids.sumOf { subtreeH(it).toDouble() }.toFloat() + (kids.size - 1) * minVerticalGap
            val span = maxOf(h, kidsH)
            layoutNodeMap[id] = LayoutNode(id = id, x = x, y = topY + span / 2f, width = w, height = h)
            var kidTop = topY + (span - kidsH) / 2f
            for (kid in kids) {
                assignY(kid, kidTop)
                kidTop += subtreeH(kid) + minVerticalGap
            }
        }
    }

    // start DFS from the entry node
    assignY(entryId, 0f)

    // stack any nodes unreachable from entry below the main layout
    val mainBottom = layoutNodeMap.values.maxOfOrNull { it.y + it.height / 2f } ?: 0f
    var extraTop = mainBottom + minVerticalGap
    for (id in nodeIds) {
        if (id !in visited) {
            val (w, h) = sizeById[id] ?: (180f to 120f)
            val d = depthMap[id] ?: 0
            val x = columnX[d] ?: leftMargin
            layoutNodeMap[id] = LayoutNode(id = id, x = x, y = extraTop + h / 2f, width = w, height = h)
            extraTop += h + minVerticalGap
        }
    }

    // shift all nodes so the topmost edge lands at topMargin
    val minY = layoutNodeMap.values.minOfOrNull { it.y - it.height / 2f } ?: 0f
    val yShift = topMargin - minY
    layoutNodeMap.replaceAll { _, ln -> ln.copy(y = ln.y + yShift) }

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
