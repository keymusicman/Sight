package com.keymusicman.appflower.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import coil3.compose.AsyncImage
import com.keymusicman.appflower.model.Graph
import com.keymusicman.appflower.model.GraphEdge
import com.keymusicman.appflower.model.GraphNode
import com.keymusicman.appflower.model.LayoutGraph
import com.keymusicman.appflower.model.LayoutNode
import com.keymusicman.appflower.model.buildLayoutGraph
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageInputStream
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

        // Read only image dimensions (no pixel data) for layout sizing
        val imageDimensions: Map<String, Pair<Int, Int>> = remember(graph, appBasePath) {
            domainNodes.associate { node ->
                val path = node.imagePaths.firstOrNull()
                val dim = path?.let { getImageDimension(it) }
                node.id to (dim ?: (540 to 360))
            }
        }

        // File paths per node for Coil to load lazily
        val pathById: Map<String, String?> = remember(graph) {
            domainNodes.associate { it.id to it.imagePaths.firstOrNull() }
        }

        // build layout graph once and reuse across recompositions
        val layoutGraph: LayoutGraph = remember(graph, appBasePath) {
            buildLayoutGraph(domainNodes, domainEdges, imageDimensions)
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

                // image children for each node — loaded lazily by Coil
                nodeList.forEach { ln ->
                    val path = pathById[ln.id]
                    if (path != null) {
                        AsyncImage(
                            model = File(path),
                            contentDescription = ln.id,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize())
                    }
                }
            }
        ) { measurables, constraints ->
            // measure children: Canvas fills all, images measured at zoomed pixel size
            val placeables = buildList {
                if (measurables.isNotEmpty()) add(measurables[0].measure(constraints))
                for (i in nodeList.indices) {
                    val ln = nodeList[i]
                    val w = (ln.width * zoomState.value).toInt().coerceAtLeast(1)
                    val h = (ln.height * zoomState.value).toInt().coerceAtLeast(1)
                    add(measurables[i + 1].measure(Constraints.fixed(w, h)))
                }
            }

            layout(constraints.maxWidth, constraints.maxHeight) {
                if (placeables.isNotEmpty()) placeables[0].place(0, 0)
                // place nodes by immutable coordinates; skip those fully outside the viewport
                for (i in nodeList.indices) {
                    val ln = nodeList[i]
                    val p = placeables[i + 1]
                    val x = (ln.x * zoomState.value + pan.value.x - ln.width / 2f * zoomState.value).toInt()
                    val y = (ln.y * zoomState.value + pan.value.y - ln.height / 2f * zoomState.value).toInt()
                    if (x + p.width > 0 && x < constraints.maxWidth &&
                        y + p.height > 0 && y < constraints.maxHeight) {
                        p.place(x, y)
                    }
                }
            }
        }
    }
}

/**
 * Gets image dimensions for a given file by reading only the image header — no pixel data loaded.
 * @return width to height in pixels, or null if the file is not a known image
 */
private fun getImageDimension(path: String): Pair<Int, Int>? {
    val file = File(path)
    if (!file.exists()) return null
    val pos = file.name.lastIndexOf('.')
    if (pos == -1) return null
    val suffix = file.name.substring(pos + 1)
    val iter = ImageIO.getImageReadersBySuffix(suffix)
    while (iter.hasNext()) {
        val reader = iter.next()
        var stream: FileImageInputStream? = null
        try {
            stream = FileImageInputStream(file)
            reader.setInput(stream)
            val width = reader.getWidth(reader.minIndex)
            val height = reader.getHeight(reader.minIndex)
            return width to height
        } catch (_: Exception) {
            // try next reader
        } finally {
            stream?.close()
            reader.dispose()
        }
    }
    return null
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

