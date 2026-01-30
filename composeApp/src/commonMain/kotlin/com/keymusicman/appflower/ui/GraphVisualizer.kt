package com.keymusicman.appflower.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.keymusicman.appflower.model.Graph
import com.keymusicman.appflower.model.GraphEdge
import com.keymusicman.appflower.model.GraphNode
import com.keymusicman.appflower.model.LayoutGraph
import com.keymusicman.appflower.model.LayoutNode
import com.keymusicman.appflower.model.buildLayoutGraph
import org.jetbrains.skia.Image
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun GraphVisualizer(
    graph: Graph?,
    appBasePath: String? = null,
    modifier: Modifier = Modifier,
    zoomState: MutableState<Float>,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopStart,
    ) {
        val density = LocalDensity.current

        if (graph == null) {
            Text("No graph loaded", color = MaterialTheme.colorScheme.onBackground)
            return@Box
        }

        if (graph.nodes.isEmpty()) {
            Text("Graph is empty", color = MaterialTheme.colorScheme.onBackground)
            return@Box
        }

        // Build immutable domain and layout once per graph
        val domainNodes = graph.nodes.map { n -> GraphNode(n.id, n.imagePaths, n.selectedState) }
        val domainEdges = graph.edges.map { e -> GraphEdge(e.from, e.to, e.trigger) }

        // pre-load bitmaps and create map by node id
        val bitmaps: Map<String, ImageBitmap?> = remember(graph, appBasePath) {
            domainNodes.associate { node ->
                val path = node.imagePaths.firstOrNull()
                val bmp = path?.let { loadImageBitmap(it) }
                node.id to bmp
            }
        }

        val bitmapMap: Map<String, ImageBitmap> = remember(bitmaps) {
            bitmaps.filterValues { it != null }.mapValues { it.value!! }
        }

        // build layout graph once and reuse across recompositions
        val layoutGraph: LayoutGraph = remember(graph, appBasePath) {
            buildLayoutGraph(domainNodes, domainEdges, bitmapMap)
        }

        // deterministic ordered list of layout nodes for composing children
        val nodeList: List<LayoutNode> = remember(layoutGraph) {
            layoutGraph.nodes.values.sortedWith(compareBy({ it.x }, { it.y }, { it.id }))
        }

        // Single canvas rendering: edges then images on same canvas with zoom and drag pan
        val pan = remember { mutableStateOf(Offset(0f, 0f)) }

        Layout(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        pan.value += dragAmount
                    }
                },
            content = {
                // background Canvas draws edges and responds to pan/zoom using precomputed layout
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawGraphEdgesLayout(layoutGraph, pan.value, zoomState.value)
                }

                // image children for each node (selected state) measured using precomputed sizes only
                nodeList.forEach { ln ->
                    val bmp = bitmaps[ln.id]
                    if (bmp != null) {
                        val wDp = with(density) { ln.width.toDp() * zoomState.value }
                        val hDp = with(density) { ln.height.toDp() * zoomState.value }
                        Image(
                            bitmap = bmp,
                            contentDescription = ln.id,
                            modifier = Modifier.requiredSize(wDp, hDp)
                        )
                    } else {
                        Box(modifier = Modifier.size(48.dp)) { }
                    }
                }
            }
        ) { measurables, constraints ->
            // measure children using precomputed sizes only
            val placeables = buildList {
                if (measurables.isNotEmpty()) add(measurables[0].measure(constraints))
                // remaining measurables correspond to nodes in nodeList order
                for (i in nodeList.indices) {
                    val ln = nodeList[i]
                    val w = with(density) { ln.width.toDp().roundToPx() }
                    val h = with(density) { ln.height.toDp().roundToPx() }
                    add(measurables[i + 1].measure(Constraints.fixed(w, h)))
                }
            }

            layout(constraints.maxWidth, constraints.maxHeight) {
                if (placeables.isNotEmpty()) placeables[0].place(0, 0)
                // place nodes by immutable coordinates
                for (i in nodeList.indices) {
                    val ln = nodeList[i]
                    val p = placeables[i + 1]
                    val x = (ln.x * zoomState.value + pan.value.x - ln.width / 2f * zoomState.value).toInt()
                    val y = (ln.y * zoomState.value + pan.value.y - ln.height / 2f * zoomState.value).toInt()
                    p.place(x, y)
                }
            }
        }
    }
}

private fun loadImageBitmap(path: String): ImageBitmap? {
    return try {
        val file = File(path)
        if (file.exists()) {
            Image.makeFromEncoded(file.readBytes())
                .toComposeImageBitmap()
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

private fun DrawScope.drawGraphEdgesLayout(layoutGraph: LayoutGraph, pan: Offset, zoom: Float) {
    layoutGraph.edges.forEach { edge ->
        val points = edge.points.map { Offset(it.x * zoom + pan.x, it.y * zoom + pan.y) }
        if (points.size >= 2) {
            for (i in 0 until points.size - 1) {
                drawLine(Color.Gray, points[i], points[i + 1], strokeWidth = 2f)
            }
            // arrow at end
            val last = points.last()
            val prev = points[points.size - 2]
            val angle = atan2(last.y - prev.y, last.x - prev.x)
            val arrowSize = 12f * zoom
            val arrowEnd1 = Offset(
                last.x - arrowSize * cos(angle - Math.PI / 6).toFloat(),
                last.y - arrowSize * sin(angle - Math.PI / 6).toFloat()
            )
            val arrowEnd2 = Offset(
                last.x - arrowSize * cos(angle + Math.PI / 6).toFloat(),
                last.y - arrowSize * sin(angle + Math.PI / 6).toFloat()
            )
            drawLine(Color.Gray, last, arrowEnd1, strokeWidth = 2f)
            drawLine(Color.Gray, last, arrowEnd2, strokeWidth = 2f)
        }
    }
}

