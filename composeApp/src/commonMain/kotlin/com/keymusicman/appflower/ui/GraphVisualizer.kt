package com.keymusicman.appflower.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.Layout
import com.keymusicman.appflower.model.Graph
import java.io.File
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
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

            // Single canvas rendering: edges then images on same canvas with zoom
            ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                // draw with transform
                drawContext.canvas.save()
                drawContext.canvas.scale(zoomState.value)

                // draw edges using current node sizes and positions
                drawGraphEdges(prepared)

                // draw images on top
                prepared.nodes.forEach { node ->
                    node.imagePath?.let { path ->
                        val bmp = loadImageBitmap(path)
                        if (bmp != null) {
                            // scale images to max 120 px keeping aspect
                            val maxPx = 120f
                            val ratio = bmp.width.toFloat() / bmp.height.toFloat()
                            val w = if (ratio >= 1f) maxPx else maxPx * ratio
                            val h = if (ratio >= 1f) maxPx / ratio else maxPx
                            // Draw image into canvas with explicit src/dst offsets
                            val dstOffset = androidx.compose.ui.unit.IntOffset((node.x - w / 2f).toInt(), (node.y - h / 2f).toInt())
                            val dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt())
                            drawImage(bmp, srcOffset = androidx.compose.ui.unit.IntOffset(0,0), srcSize = androidx.compose.ui.unit.IntSize(bmp.width, bmp.height), dstOffset = dstOffset, dstSize = dstSize)
                            node.width = w
                            node.height = h
                        }
                    }
                }

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

    // compute direction unit vector
    val ux = dx / distance
    val uy = dy / distance

    // approximate intersection with ellipse along direction vector for source and target
    // parametric point on ellipse centered at origin: (rx*cos(t), ry*sin(t)), find t where vector aligns
    // approximate by scaling direction by radii
    val fromIntersect = Offset(from.x + ux * fromRx, from.y + uy * fromRy)
    val toIntersect = Offset(to.x - ux * toRx, to.y - uy * toRy)

    drawLine(
        color = Color.Gray,
        start = fromIntersect,
        end = toIntersect,
        strokeWidth = 2f
    )

    val angle = atan2(dy, dx)
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
