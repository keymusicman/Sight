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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
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
fun GraphVisualizer(graph: Graph?, appBasePath: String? = null, modifier: Modifier = Modifier) {
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
            // draw edges first so images render on top
            ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                drawGraphEdges(graph)
            }

            Layout(
                content = {
                    graph.nodes.forEach { node ->
                        if (node.imagePath != null) {
                            val imageBitmap = remember(node.imagePath) {
                                loadImageBitmap(node.imagePath)
                            }
                            if (imageBitmap != null) {
                                // compute size keeping aspect ratio, default max 120 dp
                                val maxDp = 120.dp
                                val ratio = imageBitmap.width.toFloat() / imageBitmap.height.toFloat()
                                val widthDp = if (ratio >= 1f) maxDp else (maxDp * ratio)
                                val heightDp = if (ratio >= 1f) (maxDp / ratio) else maxDp
                                Image(
                                    bitmap = imageBitmap,
                                    contentDescription = node.id,
                                    modifier = Modifier.size(widthDp, heightDp)
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) { measurables, constraints ->
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val placeables = mutableListOf<androidx.compose.ui.layout.Placeable>()
                    measurables.forEachIndexed { mIndex, measurable ->
                        val placeable = measurable.measure(constraints)
                        placeables.add(placeable)
                        // assign measured width/height back to node for edge calculations
                        if (mIndex < graph.nodes.size) {
                            val node = graph.nodes.elementAt(mIndex)
                            node.width = placeable.width.toFloat()
                            node.height = placeable.height.toFloat()
                        }
                    }

                    // simple iterative collision resolution using measured sizes
                    val nodesList = graph.nodes.toMutableList()
                    val iterations = 6
                    for (it in 0 until iterations) {
                        for (i in nodesList.indices) {
                            for (j in i + 1 until nodesList.size) {
                                val a = nodesList[i]
                                val b = nodesList[j]
                                val dx = a.x - b.x
                                val dy = a.y - b.y
                                val overlapX = (a.width / 2f + b.width / 2f) - kotlin.math.abs(dx)
                                val overlapY = (a.height / 2f + b.height / 2f) - kotlin.math.abs(dy)
                                if (overlapX > 0f && overlapY > 0f) {
                                    // push apart along the axis of greatest overlap
                                    val pushX = if (overlapX > overlapY) overlapX else 0f
                                    val pushY = if (overlapY >= overlapX) overlapY else 0f
                                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                                    val nx = if (dist == 0f) (if (i % 2 == 0) 1f else -1f) else dx / dist
                                    val ny = if (dist == 0f) (if (j % 2 == 0) 1f else -1f) else dy / dist
                                    a.x += (nx * pushX) / 2f
                                    a.y += (ny * pushY) / 2f
                                    b.x -= (nx * pushX) / 2f
                                    b.y -= (ny * pushY) / 2f
                                }
                            }
                        }
                    }

                    // place after resolving
                    placeables.forEachIndexed { mIndex, placeable ->
                        if (mIndex < graph.nodes.size) {
                            val node = graph.nodes.elementAt(mIndex)
                            placeable.place(
                                x = (node.x - placeable.width / 2).toInt(),
                                y = (node.y - placeable.height / 2).toInt()
                            )
                        }
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
