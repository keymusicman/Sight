package com.keymusicman.appflower.model

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.PriorityQueue

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
                    nodesMap[nodeId] = GraphNode(
                        id = nodeId,
                        imagePaths = imagePaths,
                        selectedState = screen.selected_state.coerceAtLeast(0)
                    )
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
            val selectedPath = node.imagePaths.getOrNull(node.selectedState)
                ?: node.imagePaths.firstOrNull()
            val dim = selectedPath
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
        val incomingParents: MutableMap<String, MutableList<String>> =
            nodeIds.associateWith { mutableListOf<String>() }
                .toMutableMap()
        val incomingCount: MutableMap<String, Int> = nodeIds.associateWith { 0 }
            .toMutableMap()

        edges.forEach { e ->
            if (adjacency.containsKey(e.from)) adjacency[e.from]?.add(e.to)
            if (incomingParents.containsKey(e.to) && adjacency.containsKey(e.from)) {
                incomingParents[e.to]?.add(e.from)
            }
            incomingCount[e.to] = (incomingCount[e.to] ?: 0) + 1
        }

        // find entry node (no incoming). If multiple, pick deterministic first by id.
        val entries = nodeIds.filter { incomingCount[it] == 0 }
        val entryId = if (entries.isNotEmpty()) entries.first() else nodeIds.first()

        // compute depth from entry on SCC-condensed DAG.
        // Using longest path on the DAG keeps node columns stable when extra shortcut edges exist.
        val depthMap = computeDepthBySccDag(nodeIds, adjacency, entryId)

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

        // Build a deterministic primary-parent tree for vertical packing.
        // Non-primary incoming edges are still rendered, but they don't perturb node placement.
        val primaryParentByNode: Map<String, String?> = nodeIds.associateWith { id ->
            if (id == entryId) {
                null
            } else {
                incomingParents[id]
                    .orEmpty()
                    .distinct()
                    .maxWithOrNull(
                        compareBy<String>({ depthMap[it] ?: 0 }, { it })
                    )
            }
        }

        val mutableChildrenMap = nodeIds.associateWith { mutableListOf<String>() }
            .toMutableMap()
        primaryParentByNode.forEach { (nodeId, parentId) ->
            if (parentId != null) {
                mutableChildrenMap[parentId]?.add(nodeId)
            }
        }
        val childrenMap: Map<String, List<String>> = mutableChildrenMap.mapValues { (_, children) ->
            children.sorted()
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
            val selectedState = nodeById[id]?.selectedState ?: 0
            layoutNodeMap[id] =
                LayoutNode(
                    id = id,
                    x = x,
                    y = relY,
                    width = w,
                    height = h,
                    imagePaths = imagePaths,
                    selectedState = selectedState
                )
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
                val selectedState = nodeById[id]?.selectedState ?: 0
                layoutNodeMap[id] = LayoutNode(
                    id = id,
                    x = x,
                    y = extraTop + h / 2f,
                    width = w,
                    height = h,
                    imagePaths = imagePaths,
                    selectedState = selectedState
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

        // Route edges as cubic Bezier curves:
        // - outgoing anchors stay fixed at source right-center
        // - if source/target vertically overlap, connect middle-to-middle on target
        // - for non-overlap incoming edges, keep anchor above/below target center based on
        //   source-vs-target Y direction while preserving deterministic ordering
        val incomingByTarget = edges.groupBy { it.to }
        val targetAnchors: Map<GraphEdge, Float> =
            incomingByTarget.flatMap { (targetId, incomingEdges) ->
                val targetNode = layoutNodesMap[targetId] ?: return@flatMap emptyList()
                val pad = (targetNode.height * 0.14f).coerceAtMost(targetNode.height / 2f - 1f)
                val top = targetNode.y - targetNode.height / 2f + pad
                val bottom = targetNode.y + targetNode.height / 2f - pad
                val (overlappingEdges, nonOverlappingEdges) = incomingEdges.partition { edge ->
                    val fromNode = layoutNodesMap[edge.from] ?: return@partition false
                    overlapsVerticallyInclusive(fromNode, targetNode)
                }
                val sortedNonOverlapping = nonOverlappingEdges.sortedWith(
                    compareBy<GraphEdge> { layoutNodesMap[it.from]?.y ?: Float.MAX_VALUE }
                        .thenBy { it.from }
                        .thenBy { it.to }
                )
                val aboveEdges = sortedNonOverlapping.filter { edge ->
                    val fromY = layoutNodesMap[edge.from]?.y ?: targetNode.y
                    fromY < targetNode.y
                }
                val belowEdges = sortedNonOverlapping.filter { edge ->
                    val fromY = layoutNodesMap[edge.from]?.y ?: targetNode.y
                    fromY > targetNode.y
                }
                val sameLevelEdges = sortedNonOverlapping.filter { edge ->
                    val fromY = layoutNodesMap[edge.from]?.y ?: targetNode.y
                    fromY == targetNode.y
                }
                val nonOverlapAnchors = mutableListOf<Pair<GraphEdge, Float>>()
                nonOverlapAnchors += distributeAnchorsInRange(
                    edges = aboveEdges,
                    startY = top,
                    endY = (targetNode.y - 1f).coerceAtLeast(top)
                )
                nonOverlapAnchors += distributeAnchorsInRange(
                    edges = belowEdges,
                    startY = (targetNode.y + 1f).coerceAtMost(bottom),
                    endY = bottom
                )
                nonOverlapAnchors += sameLevelEdges.map { edge -> edge to targetNode.y }
                val overlapAnchors = overlappingEdges.map { edge -> edge to targetNode.y }
                overlapAnchors + nonOverlapAnchors
            }
                .toMap()

        val layoutEdges = edges.mapNotNull { e ->
            val fromNode = layoutNodesMap[e.from] ?: return@mapNotNull null
            val toNode = layoutNodesMap[e.to] ?: return@mapNotNull null

            val start = PointF(fromNode.x + fromNode.width / 2f, fromNode.y)
            val end = PointF(toNode.x - toNode.width / 2f, targetAnchors[e] ?: toNode.y)
            val dx = end.x - start.x
            val dy = end.y - start.y
            val c1 = PointF(start.x + dx * 0.5f, start.y + dy * 0f)
            val c2 = PointF(start.x + dx * 0.05f, start.y + dy * 1f)
            LayoutEdge(from = e.from, to = e.to, points = listOf(start, c1, c2, end))
        }

        return LayoutGraph(nodes = layoutNodesMap, edges = layoutEdges)
    }
}

private fun overlapsVerticallyInclusive(source: LayoutNode, target: LayoutNode): Boolean {
    val sourceTop = source.y - source.height / 2f
    val sourceBottom = source.y + source.height / 2f
    val targetTop = target.y - target.height / 2f
    val targetBottom = target.y + target.height / 2f
    return sourceBottom >= targetTop && targetBottom >= sourceTop
}

private fun distributeAnchorsInRange(
    edges: List<GraphEdge>,
    startY: Float,
    endY: Float
): List<Pair<GraphEdge, Float>> {
    if (edges.isEmpty()) return emptyList()
    if (edges.size == 1) return listOf(edges.first() to ((startY + endY) / 2f))
    val span = endY - startY
    val step = if (span > 0f) span / (edges.size - 1) else 0f
    return edges.mapIndexed { index, edge ->
        edge to (startY + step * index)
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
        val locationFile = File(screenshotLocation)
        val fullPath = if (projectPath != null && !locationFile.isAbsolute) {
            File(projectPath, screenshotLocation)
        } else {
            locationFile
        }
        Logger.d { "[AppFlower] findImagesInLocation: screenId=$screenId, resolved path=${fullPath.absolutePath}, exists=${fullPath.exists()}, isFile=${fullPath.isFile}, isDir=${fullPath.isDirectory}" }
        when {
            fullPath.isFile -> listOf(fullPath.absolutePath)
            fullPath.isDirectory -> {
                // Match {screenId}[_variant]_{index}.{ext} case-insensitively
                val regex = Regex(
                    "${Regex.escape(screenId)}(?:_.+?)?_(\\d+)\\.(?:png|jpg|jpeg|webp)",
                    RegexOption.IGNORE_CASE
                )
                val files = fullPath.listFiles()
                Logger.d { "[AppFlower] findImagesInLocation: dir contains ${files?.size ?: 0} files, regex=$regex" }
                files
                    ?.filter { regex.matches(it.name) }
                    ?.sortedBy { file ->
                        regex.find(file.name)?.groups?.get(1)?.value?.toIntOrNull() ?: 0
                    }
                    ?.map { it.absolutePath } ?: emptyList()
            }

            else -> {
                Logger.e { "[AppFlower] findImagesInLocation: path does not exist or is not accessible: ${fullPath.absolutePath}" }
                emptyList()
            }
        }
    } catch (e: Exception) {
        Logger.e { "[AppFlower] findImagesInLocation: exception for screenId=$screenId: ${e.message}" }
        emptyList()
    }
}

data class LayoutGaps(
    val leftMargin: Float = 100f,
    val topMargin: Float = 100f,
    val minHorizontalGap: Float = 250f,
    val minVerticalGap: Float = 250f
)

private fun computeDepthBySccDag(
    nodeIds: List<String>,
    adjacency: Map<String, List<String>>,
    entryId: String
): MutableMap<String, Int> {
    var index = 0
    val stack = ArrayDeque<String>()
    val onStack = mutableSetOf<String>()
    val indices = mutableMapOf<String, Int>()
    val lowLink = mutableMapOf<String, Int>()
    val components = mutableListOf<List<String>>()

    fun strongConnect(v: String) {
        indices[v] = index
        lowLink[v] = index
        index++
        stack.addLast(v)
        onStack.add(v)

        for (w in (adjacency[v] ?: emptyList()).sorted()) {
            if (w !in indices) {
                strongConnect(w)
                lowLink[v] = minOf(lowLink[v] ?: Int.MAX_VALUE, lowLink[w] ?: Int.MAX_VALUE)
            } else if (w in onStack) {
                lowLink[v] = minOf(lowLink[v] ?: Int.MAX_VALUE, indices[w] ?: Int.MAX_VALUE)
            }
        }

        if (lowLink[v] == indices[v]) {
            val component = mutableListOf<String>()
            while (true) {
                val w = stack.removeLast()
                onStack.remove(w)
                component.add(w)
                if (w == v) break
            }
            components.add(component.sorted())
        }
    }

    nodeIds.sorted()
        .forEach { node ->
            if (node !in indices) strongConnect(node)
        }

    val componentByNode = mutableMapOf<String, Int>()
    components.forEachIndexed { componentIndex, component ->
        component.forEach { node -> componentByNode[node] = componentIndex }
    }

    val dagAdjacency = components.indices.associateWith { mutableSetOf<Int>() }
        .toMutableMap()
    nodeIds.forEach { from ->
        val fromComponent = componentByNode[from] ?: return@forEach
        adjacency[from].orEmpty()
            .forEach { to ->
                val toComponent = componentByNode[to] ?: return@forEach
                if (fromComponent != toComponent) {
                    dagAdjacency[fromComponent]?.add(toComponent)
                }
            }
    }

    val entryComponent = componentByNode[entryId] ?: 0
    val reachableComponents = mutableSetOf<Int>()
    val reachableQueue = ArrayDeque<Int>()
    reachableComponents.add(entryComponent)
    reachableQueue.add(entryComponent)
    while (reachableQueue.isNotEmpty()) {
        val current = reachableQueue.removeFirst()
        dagAdjacency[current].orEmpty()
            .forEach { next ->
                if (reachableComponents.add(next)) {
                    reachableQueue.add(next)
                }
            }
    }

    val inDegree = reachableComponents.associateWith { 0 }
        .toMutableMap()
    reachableComponents.forEach { from ->
        dagAdjacency[from].orEmpty()
            .forEach { to ->
                if (to in reachableComponents) {
                    inDegree[to] = (inDegree[to] ?: 0) + 1
                }
            }
    }

    val ready = PriorityQueue<Int>()
    inDegree.forEach { (component, degree) ->
        if (degree == 0) ready.add(component)
    }

    val depthByComponent = components.indices.associateWith { 0 }
        .toMutableMap()
    while (ready.isNotEmpty()) {
        val current = ready.poll()
        val currentDepth = depthByComponent[current] ?: 0
        dagAdjacency[current].orEmpty()
            .sorted()
            .forEach { next ->
                if (next !in reachableComponents) return@forEach
                depthByComponent[next] = maxOf(depthByComponent[next] ?: 0, currentDepth + 1)
                val nextDegree = (inDegree[next] ?: 0) - 1
                inDegree[next] = nextDegree
                if (nextDegree == 0) {
                    ready.add(next)
                }
            }
    }

    return nodeIds.associateWith { node ->
        val componentIndex = componentByNode[node] ?: return@associateWith 0
        if (componentIndex in reachableComponents) depthByComponent[componentIndex] ?: 0 else 0
    }
        .toMutableMap()
}
