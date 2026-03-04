package com.keymusicman.appflower.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Build a fully immutable, render-ready layout graph directly from AppGraph.
 * - Internally flattens AppGraph to nodes and edges on Dispatchers.IO
 * - Loads image dimensions efficiently via imageResolver
 * - Computes hierarchical contour-based layout
 * - sizes are taken from image dimensions (scaled)
 * - columns are assigned by depth from the entry node (left-to-right)
 * - nodes in a column are stacked vertically; merges are centered relative to incoming lanes
 * - edges are routed orthogonally with a simple 3-bend polyline (start -> midX -> end)
 */
object LayoutGraphBuilder {
    suspend fun build(
        appGraph: AppGraph,
        projectPath: String? = null,
        scale: Float = 0.33f,
        imageResolver: ImageDimensionResolver = DefaultImageDimensionResolver(projectPath),
        gaps: LayoutGaps = LayoutGaps()
    ): LayoutGraph {
        // Flatten AppGraph on IO dispatcher (disk I/O for screenshots)
        val (nodes, edges) = withContext(Dispatchers.IO) {
            val projectPath = projectPath?.trim()
            val nodesMap = mutableMapOf<String, GraphNode>()
            val edgesList = mutableListOf<GraphEdge>()

            // Build map of subgraph key to root_screen for resolving subgraph targets
            val subgraphRoots = appGraph.subgraphs.mapValues { (_, subgraph) ->
                "${subgraph.key}:${subgraph.root_screen}"
            }

            // Extract all screens from all subgraphs
            appGraph.subgraphs.forEach { (subgraphKey, subgraph) ->
                subgraph.screens.forEach { screen ->
                    val nodeId = "$subgraphKey:${screen.id}"
                    val imagePaths =
                        findImagesInLocation(screen.screenshot_location, screen.id, projectPath)
                    nodesMap[nodeId] = GraphNode(nodeId, imagePaths)
                }
            }

            // Collect connections from all subgraphs
            appGraph.subgraphs.forEach { (_, subgraph) ->
                subgraph.connections.forEach { connection ->
                    val fromId = if (connection.from.type == "screen") {
                        "${connection.from.subgraph}:${connection.from.screen_id}"
                    } else {
                        // Should not happen for "from", but handle gracefully
                        subgraphRoots[connection.from.subgraph]
                    }

                    val toId = when (connection.to.type) {
                        "screen" -> {
                            "${connection.to.subgraph}:${connection.to.screen_id}"
                        }

                        "subgraph" -> {
                            // Resolve to root screen of target subgraph
                            subgraphRoots[connection.to.subgraph]
                        }

                        else -> {
                            null
                        }
                    }

                    if (fromId != null && toId != null) {
                        edgesList.add(GraphEdge(fromId, toId, null))
                    }
                }
            }

            nodesMap.values.toList() to edgesList
        }

        if (nodes.isEmpty()) return LayoutGraph(emptyMap(), emptyList())

        // Load image dimensions efficiently (header-only, no full image load)
        val imageDimensions: Map<String, Pair<Int, Int>> = nodes.associate { node ->
            val dim = node.imagePaths.firstOrNull()
                ?.let { path ->
                    imageResolver.resolveDimension(path)
                }
            node.id to (dim ?: (540 to 360))
        }

        // Create lookup map for node details (id -> GraphNode)
        val nodeById = nodes.associateBy { it.id }

        // Rest of the layout algorithm (unchanged from original buildLayoutGraph)
        // maps for quick lookup
        val nodeIds = nodes.map { it.id }
        val adjacency: MutableMap<String, MutableList<String>> =
            nodeIds.associateWith { mutableListOf<String>() }
                .toMutableMap()
        val incomingCount: MutableMap<String, Int> = nodeIds.associateWith { 0 }
            .toMutableMap()

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

        // compute sizes from image dimensions (scaled)
        val sizeById: Map<String, Pair<Float, Float>> = nodes.associate { n ->
            val dim = imageDimensions[n.id]
            val w = dim?.first?.toFloat()
                ?.times(scale) ?: 180f
            val h = dim?.second?.toFloat()
                ?.times(scale) ?: 120f
            n.id to (w to h)
        }

        // Use injected gaps, scaled by scale factor
        val leftMargin = gaps.leftMargin * scale
        val topMargin = gaps.topMargin * scale
        val minHorizontalGap = gaps.minHorizontalGap * scale
        val minVerticalGap = gaps.minVerticalGap * scale

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

        // Contour-based subtree layout.
        // Siblings are packed using per-depth column contours: two sibling subtrees are only pushed
        // apart enough to avoid conflicts in the depth columns they actually share.
        // Nodes that are in different depth columns can occupy the same Y range without conflict.
        // globallyVisited prevents shared children from contributing their subtree contours twice,
        // which would otherwise push sibling parents too far apart.
        val globallyVisited = mutableSetOf<String>()
        fun layoutSubtree(id: String, visiting: Set<String>): SubtreeResult {
            globallyVisited.add(id)
            val depth = depthMap[id] ?: 0
            val (_, h) = sizeById[id] ?: (180f to 120f)
            val kids = (childrenMap[id] ?: emptyList()).filter { it !in visiting }

            if (kids.isEmpty()) {
                return SubtreeResult(mapOf(id to h / 2f), mapOf(depth to 0f), mapOf(depth to h))
            }

            val inner = visiting + id
            val kidResults = kids.map { kid ->
                if (kid in globallyVisited) {
                    // Shared child already laid out by a sibling: return its position as a leaf with
                    // empty contours so its depth columns don't double-count in sibling packing.
                    // The actual Y will be re-centred by the post-pass.
                    val (_, kh) = sizeById[kid] ?: (180f to 120f)
                    SubtreeResult(mapOf(kid to kh / 2f), emptyMap(), emptyMap())
                } else {
                    layoutSubtree(kid, inner)
                }
            }

            val kidTopYs = FloatArray(kids.size)
            val combTop = mutableMapOf<Int, Float>()
            val combBottom = mutableMapOf<Int, Float>()

            for (i in kidResults.indices) {
                val kr = kidResults[i]
                // Push this kid so its top-contour clears the combined bottom-contour of all
                // previously placed siblings — only at depths they actually share.
                // Allow negative values: when no shared depth conflicts exist the subtree can
                // slide upward so the parent lands immediately after the previous sibling.
                val minTopY = combBottom.keys.intersect(kr.topContour.keys)
                    .maxOfOrNull { d ->
                        (combBottom[d] ?: 0f) + minVerticalGap - (kr.topContour[d] ?: 0f)
                    }
                    ?: 0f

                kidTopYs[i] = minTopY

                kr.topContour.forEach { (d, t) ->
                    combTop[d] = minOf(combTop[d] ?: Float.MAX_VALUE, minTopY + t)
                }
                kr.bottomContour.forEach { (d, b) ->
                    combBottom[d] = maxOf(combBottom[d] ?: 0f, minTopY + b)
                }
            }

            // Center parent on midpoint of first and last direct child — not on the full extent
            // of all descendants, which can be skewed by deep chains in one branch.
            val firstChildY = kidTopYs[0] + (kidResults[0].nodeY[kids[0]] ?: (h / 2f))
            val lastChildY =
                kidTopYs[kids.size - 1] + (kidResults[kids.size - 1].nodeY[kids[kids.size - 1]]
                    ?: (h / 2f))
            val nodeYLocal = (firstChildY + lastChildY) / 2f

            val nodeYMap = mutableMapOf(id to nodeYLocal)
            val handled = mutableSetOf<String>()
            for (i in kids.indices) {
                kidResults[i].nodeY.forEach { (nid, relY) ->
                    if (!handled.contains(nid)) {
                        nodeYMap[nid] = kidTopYs[i] + relY
                        handled.add(nid)
                    }
                }
            }

            // Include own node in the contours
            combTop[depth] = minOf(combTop[depth] ?: Float.MAX_VALUE, nodeYLocal - h / 2f)
            combBottom[depth] = maxOf(combBottom[depth] ?: 0f, nodeYLocal + h / 2f)

            return SubtreeResult(nodeYMap, combTop, combBottom)
        }

        val layoutNodeMap = mutableMapOf<String, LayoutNode>()

        // Run contour layout from the entry node
        val rootResult = layoutSubtree(entryId, emptySet())
        val visited = rootResult.nodeY.keys.toMutableSet()

        rootResult.nodeY.forEach { (id, relY) ->
            val (w, h) = sizeById[id] ?: (180f to 120f)
            val d = depthMap[id] ?: 0
            val x = columnX[d] ?: leftMargin
            val imagePaths = nodeById[id]?.imagePaths ?: emptyList()
            layoutNodeMap[id] =
                LayoutNode(id = id, x = x, y = relY, width = w, height = h, imagePaths = imagePaths)
        }

        // stack any nodes unreachable from entry below the main layout
        val mainBottom = layoutNodeMap.values.maxOfOrNull { it.y + it.height / 2f } ?: 0f
        var extraTop = mainBottom + minVerticalGap
        for (id in nodeIds) {
            if (id !in visited) {
                val (w, h) = sizeById[id] ?: (180f to 120f)
                val d = depthMap[id] ?: 0
                val x = columnX[d] ?: leftMargin
                val imagePaths = nodeById[id]?.imagePaths ?: emptyList()
                layoutNodeMap[id] = LayoutNode(
                    id = id,
                    x = x,
                    y = extraTop + h / 2f,
                    width = w,
                    height = h,
                    imagePaths = imagePaths
                )
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
}

// Intermediate result of a contour-based subtree layout (all Y values relative to topY = 0).
private data class SubtreeResult(
    val nodeY: Map<String, Float>,        // node id -> center Y
    val topContour: Map<Int, Float>,      // depth -> min top-Y  (y - h/2) of any node at that depth
    val bottomContour: Map<Int, Float>    // depth -> max bottom-Y (y + h/2) of any node at that depth
)


// Dependency injection abstractions for testability
interface ImageDimensionResolver {
    suspend fun resolveDimension(imagePath: String): Pair<Int, Int>?
}

class DefaultImageDimensionResolver(private val projectPath: String? = null) :
    ImageDimensionResolver {
    override suspend fun resolveDimension(imagePath: String): Pair<Int, Int>? =
        withContext(Dispatchers.IO) {
            val paths = findImagesInLocation(imagePath, "", null)
            paths.firstOrNull()
                ?.let { getImageDimension(it) }
        }
}

private fun findImagesInLocation(
    screenshotLocation: String,
    screenId: String,
    projectPath: String?
): List<String> {
    return try {
        val fullPath = if (projectPath != null) {
            File(projectPath, screenshotLocation)
        } else {
            File(screenshotLocation)
        }
        System.err.println("[AppFlower] findImagesInLocation: screenId=$screenId, resolved path=${fullPath.absolutePath}, exists=${fullPath.exists()}, isFile=${fullPath.isFile}, isDir=${fullPath.isDirectory}")
        when {
            fullPath.isFile -> listOf(fullPath.absolutePath)
            fullPath.isDirectory -> {
                // Match {screenId}[_variant]_{index}.{ext} case-insensitively
                val regex = Regex(
                    "${Regex.escape(screenId)}(?:_.+?)?_(\\d+)\\.(?:png|jpg|jpeg|webp)",
                    RegexOption.IGNORE_CASE
                )
                val files = fullPath.listFiles()
                System.err.println("[AppFlower] findImagesInLocation: dir contains ${files?.size ?: 0} files, regex=$regex")
                files
                    ?.filter { regex.matches(it.name) }
                    ?.sortedBy { file ->
                        regex.find(file.name)?.groups?.get(1)?.value?.toIntOrNull() ?: 0
                    }
                    ?.map { it.absolutePath } ?: emptyList()
            }

            else -> {
                System.err.println("[AppFlower] findImagesInLocation: path does not exist or is not accessible: ${fullPath.absolutePath}")
                emptyList()
            }
        }
    } catch (e: Exception) {
        System.err.println("[AppFlower] findImagesInLocation: exception for screenId=$screenId: ${e.message}")
        emptyList()
    }
}

data class LayoutGaps(
    val leftMargin: Float = 100f,
    val topMargin: Float = 100f,
    val minHorizontalGap: Float = 250f,
    val minVerticalGap: Float = 250f
)