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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.keymusicman.appflower.model.Graph
import org.jetbrains.skia.Image
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun GraphVisualizer(
    graph: Graph?,
    appBasePath: String? = null,
    modifier: Modifier = Modifier,
    zoomState: MutableState<Float>,
) {
    // stateful zoom and precomputed layout

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopStart,
    ) {
        val density = LocalDensity.current
        if (graph == null) {
            Text("No graph loaded", color = MaterialTheme.colorScheme.onBackground)
        } else if (graph.nodes.isEmpty()) {
            Text("Graph is empty", color = MaterialTheme.colorScheme.onBackground)
        } else {
            // ensure node positions and sizes are stable: pre-measure images and compute layout only once per graph
            val prepared = remember(graph) {
                // load images to compute intrinsic sizes (use first image if available)
                graph.nodes.forEach { node ->
                    node.imagePaths.firstOrNull()
                        ?.let { path ->
                            val bmp = loadImageBitmap(path)

                            if (bmp != null) {
                                val maxDp = 360.dp
                                val intrinsicW = with(density) { bmp.width.toDp() }
                                val intrinsicH = with(density) { bmp.height.toDp() }
                                val ratio = bmp.width.toFloat() / bmp.height.toFloat()
                                val wDp: Dp
                                val hDp: Dp
                                if (intrinsicW > maxDp || intrinsicH > maxDp) {
                                    if (ratio >= 1f) {
                                        wDp = maxDp
                                        hDp = maxDp / ratio
                                    } else {
                                        hDp = maxDp
                                        wDp = maxDp * ratio
                                    }
                                } else {
                                    wDp = intrinsicW
                                    hDp = intrinsicH
                                }

                                node.width = with(density) { wDp.toPx() }
                                node.height = with(density) { hDp.toPx() }
                            }
                        }
                }
                // keep original layout if already set, otherwise compute initial circular layout
                graph
            }

            // Single canvas rendering: edges then images on same canvas with zoom and drag pan
            // track pan offset in state
            val pan = remember { mutableStateOf(Offset(0f, 0f)) }

            // Use Layout to place image composables and a Canvas behind them for edges
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
//                    // background Canvas draws edges and responds to pan/zoom
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // draw edges using screen-space coordinates computed from node positions, zoom and pan
                        drawGraphEdges(prepared, pan.value, zoomState.value)
                    }

                    // image children for each node (selected state)
                    prepared.nodes.forEach { node ->
                        val path = node.imagePaths.getOrNull(node.selectedState)
                        val bmp = path?.let { loadImageBitmap(it) }
                        if (bmp != null) {
                            // cap displayed size to 720 dp while preserving aspect ratio
                            val wDp = with(density) { node.width.toDp() * zoomState.value }
                            val hDp = with(density) { node.height.toDp() * zoomState.value }
                            Image(
                                bitmap = bmp,
                                contentDescription = node.id,
                                modifier = Modifier.requiredSize(wDp, hDp)
                            )
                        } else {
                            // placeholder box when no image
                            Box(modifier = Modifier.size(48.dp)) { }
                        }
                    }
                }
            ) { measurables, constraints ->
                // first measurable is the background canvas
                val placeables = buildList {
                    if (measurables.isNotEmpty()) {
                        add(measurables[0].measure(constraints))
                    }
                    for (i in 1 until measurables.size) {
                        add(measurables[i].measure(Constraints()))
                    }
                }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    // place background canvas full size
                    if (placeables.isNotEmpty()) {
                        placeables[0].place(0, 0)
                    }
                    // place images matching prepared.nodes order; skip first measurable
                    val nodesList = prepared.nodes.toList()
                    for (i in 1 until placeables.size) {
                        val node = nodesList.getOrNull(i - 1) ?: continue
                        val p = placeables[i]
                        // compute positioned center taking pan and zoom into account
                        val x = ((node.x - node.width / 2f) * zoomState.value + pan.value.x).toInt()
                        val y =
                            ((node.y - node.height / 2f) * zoomState.value + pan.value.y).toInt()

                        println("Place: placeable at ${x}, ${y}")
                        println("Place: node ${node.x}, ${node.y}")

                        p.place(x, y)
                    }
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

private fun DrawScope.drawGraphEdges(graph: Graph, pan: Offset, zoom: Float) {
    graph.edges.forEach { edge ->
        val fromNode = graph.nodes.find { it.id == edge.from } ?: return@forEach
        val toNode = graph.nodes.find { it.id == edge.to } ?: return@forEach

        // compute ellipse radii based on measured image sizes (fallback to 60f), scaled by zoom
        val fromRx = if (fromNode.width > 0f) (fromNode.width / 2f) * zoom else 60f * zoom
        val fromRy = if (fromNode.height > 0f) (fromNode.height / 2f) * zoom else 60f * zoom
        val toRx = if (toNode.width > 0f) (toNode.width / 2f) * zoom else 60f * zoom
        val toRy = if (toNode.height > 0f) (toNode.height / 2f) * zoom else 60f * zoom

        // compute screen-space positions of node centers after applying zoom and pan
        val fromPoint = Offset(fromNode.x * zoom + pan.x, fromNode.y * zoom + pan.y)
        val toPoint = Offset(toNode.x * zoom + pan.x, toNode.y * zoom + pan.y)
        println("Draw edges: from ${fromPoint.x}, ${fromPoint.y}, to ${toPoint.x}, ${toPoint.y}")

        drawEdgeEllipse(fromPoint, toPoint, fromRx, fromRy, toRx, toRy, 15f * zoom)
    }
}

private fun DrawScope.drawEdgeEllipse(
    from: Offset,
    to: Offset,
    fromRx: Float,
    fromRy: Float,
    toRx: Float,
    toRy: Float,
    arrowSize: Float
) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val distance = sqrt(dx * dx + dy * dy)

    if (distance == 0f) return

    // prefer connecting horizontally when horizontal separation is larger than vertical
    val absDx = kotlin.math.abs(dx)
    val absDy = kotlin.math.abs(dy)
    val ux: Float
    val uy: Float
    val fromIntersect: Offset
    val toIntersect: Offset

    if (absDx > absDy) {
        // horizontal preference: connect east/west sides
        val dir = if (dx >= 0f) 1f else -1f
        ux = dir
        uy = 0f
        fromIntersect = Offset(from.x + ux * fromRx, from.y)
        toIntersect = Offset(to.x - ux * toRx, to.y)
    } else {
        // default: connect along actual direction vector
        ux = dx / distance
        uy = dy / distance
        fromIntersect = Offset(from.x + ux * fromRx, from.y + uy * fromRy)
        toIntersect = Offset(to.x - ux * toRx, to.y - uy * toRy)
    }

    drawLine(
        color = Color.Gray,
        start = fromIntersect,
        end = toIntersect,
        strokeWidth = 2f
    )

    val angle = atan2(toIntersect.y - fromIntersect.y, toIntersect.x - fromIntersect.x)
    val arrowTip = toIntersect
    val arrowEnd1 = Offset(
        arrowTip.x - arrowSize * cos(angle - Math.PI / 6).toFloat(),
        arrowTip.y - arrowSize * sin(angle - Math.PI / 6).toFloat()
    )
    val arrowEnd2 = Offset(
        arrowTip.x - arrowSize * cos(angle + Math.PI / 6).toFloat(),
        arrowTip.y - arrowSize * sin(angle + Math.PI / 6).toFloat()
    )

    drawLine(Color.Gray, arrowTip, arrowEnd1, strokeWidth = 2f)
    drawLine(Color.Gray, arrowTip, arrowEnd2, strokeWidth = 2f)
}
