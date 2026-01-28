package com.keymusicman.appflower.ui

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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.keymusicman.appflower.model.Graph
import org.jetbrains.skia.Image
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import androidx.compose.foundation.Canvas as ComposeCanvas

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
        contentAlignment = Alignment.Center
    ) {
        if (graph == null) {
            Text("No graph loaded", color = MaterialTheme.colorScheme.onBackground)
        } else if (graph.nodes.isEmpty()) {
            Text("Graph is empty", color = MaterialTheme.colorScheme.onBackground)
        } else {
            // ensure node positions and sizes are stable: pre-measure images and compute layout only once per graph
            val prepared = remember(graph) {
                // load images to compute intrinsic sizes
                graph.nodes.forEach { node ->
                    node.imagePath?.let { path ->
                        val bmp = loadImageBitmap(path)
                        if (bmp != null) {
                            node.width = bmp.width.toFloat()
                            node.height = bmp.height.toFloat()
                        }
                    }
                }
                // keep original layout if already set, otherwise compute initial circular layout
                graph
            }

            // Single canvas rendering: edges then images on same canvas with zoom and drag pan
            // track pan offset in state
            val pan = remember { mutableStateOf(Offset(0f, 0f)) }

            ComposeCanvas(modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    // update pan by drag amount (note: dragAmount is in pixels)
                    pan.value += dragAmount
                }
            }) {
                // draw with transform
                drawContext.canvas.save()

                // apply pan before scaling so pan happens in view coordinates
                drawContext.canvas.translate(pan.value.x, pan.value.y)
                drawContext.canvas.scale(zoomState.value)

                // draw images on top
                prepared.nodes.forEach { node ->
                    node.imagePath?.let { path ->
                        val bmp = loadImageBitmap(path)
                        if (bmp != null) {
                            // scale images to max 120 px keeping aspect
                            val maxPx = 720f
                            val ratio = bmp.width.toFloat() / bmp.height.toFloat()
                            val w = (if (ratio >= 1f) maxPx else maxPx * ratio)
                            val h = (if (ratio >= 1f) maxPx / ratio else maxPx)
                            // Draw image into canvas with explicit src/dst offsets
                            val dstOffset = IntOffset((node.x - w / 2f).toInt(), (node.y - h / 2f).toInt())
                            val dstSize = IntSize(w.toInt(), h.toInt())
                            drawImage(bmp, srcOffset = IntOffset(0,0), srcSize = IntSize(bmp.width, bmp.height), dstOffset = dstOffset, dstSize = dstSize)
                            node.width = w
                            node.height = h
                        }
                    }
                }

                // draw edges using current node sizes and positions
                drawGraphEdges(prepared)

                drawContext.canvas.restore()
            }
        }
    }
}

private fun loadImageBitmap(path: String): ImageBitmap? {
    return try {
        val file = File(path)
        if (file.exists()) {
            Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

private fun DrawScope.drawGraphEdges(graph: Graph) {
    graph.edges.forEach { edge ->
        val fromNode = graph.nodes.find { it.id == edge.from } ?: return@forEach
        val toNode = graph.nodes.find { it.id == edge.to } ?: return@forEach

        // compute ellipse radii based on measured image sizes (fallback to 60f)
        val fromRx = if (fromNode.width > 0f) fromNode.width / 2f else 60f
        val fromRy = if (fromNode.height > 0f) fromNode.height / 2f else 60f
        val toRx = if (toNode.width > 0f) toNode.width / 2f else 60f
        val toRy = if (toNode.height > 0f) toNode.height / 2f else 60f

        val fromPoint = Offset(fromNode.x, fromNode.y)
        val toPoint = Offset(toNode.x, toNode.y)

        drawEdgeEllipse(fromPoint, toPoint, fromRx, fromRy, toRx, toRy, 15f)
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
