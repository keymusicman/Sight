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
            Layout(
                content = {
                    graph.nodes.forEach { node ->
                        if (node.imagePath != null) {
                            val imageBitmap = remember(node.imagePath) {
                                loadImageBitmap(node.imagePath)
                            }
                            if (imageBitmap != null) {
                                Image(
                                    bitmap = imageBitmap,
                                    contentDescription = node.id,
                                    modifier = Modifier.size(120.dp)
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) { measurables, constraints ->
                layout(constraints.maxWidth, constraints.maxHeight) {
                    graph.nodes.forEachIndexed { index, node ->
                        if (index < measurables.size) {
                            val placeable = measurables[index].measure(constraints)
                            placeable.place(
                                x = (node.x - placeable.width / 2).toInt(),
                                y = (node.y - placeable.height / 2).toInt()
                            )
                        }
                    }
                }
            }
            
            ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                drawGraphEdges(graph)
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
    val imageSize = 60f

    graph.edges.forEach { edge ->
        val fromNode = graph.nodes.find { it.id == edge.from } ?: return@forEach
        val toNode = graph.nodes.find { it.id == edge.to } ?: return@forEach

        val fromPoint = Offset(fromNode.x, fromNode.y)
        val toPoint = Offset(toNode.x, toNode.y)

        drawEdge(fromPoint, toPoint, imageSize, 15f)
    }
}

private fun DrawScope.drawEdge(
    from: Offset,
    to: Offset,
    nodeRadius: Float,
    arrowSize: Float
) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val distance = sqrt(dx * dx + dy * dy)

    if (distance == 0f) return

    val ratio = (distance - nodeRadius) / distance
    val adjustedTo = Offset(
        from.x + dx * ratio,
        from.y + dy * ratio
    )

    drawLine(
        color = Color.Gray,
        start = Offset(from.x + (dx / distance) * nodeRadius, from.y + (dy / distance) * nodeRadius),
        end = adjustedTo,
        strokeWidth = 2f
    )

    val angle = atan2(dy, dx)
    val arrowEnd1 = Offset(
        adjustedTo.x - arrowSize * cos(angle - Math.PI / 6).toFloat(),
        adjustedTo.y - arrowSize * sin(angle - Math.PI / 6).toFloat()
    )
    val arrowEnd2 = Offset(
        adjustedTo.x - arrowSize * cos(angle + Math.PI / 6).toFloat(),
        adjustedTo.y - arrowSize * sin(angle + Math.PI / 6).toFloat()
    )

    drawLine(Color.Gray, adjustedTo, arrowEnd1, strokeWidth = 2f)
    drawLine(Color.Gray, adjustedTo, arrowEnd2, strokeWidth = 2f)
}
