package com.keymusicman.appflower.model

import kotlinx.serialization.Serializable

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
    val screenshot_location: String,
    val selected_state: Int = 0
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
data class AppGraph(
    val metadata: GraphMetadata,
    val subgraphs: Map<String, Subgraph>
)

fun getImageDimension(path: String): Pair<Int, Int>? {
    val file = java.io.File(path)
    if (!file.exists()) return null
    val pos = file.name.lastIndexOf('.')
    if (pos == -1) return null
    val suffix = file.name.substring(pos + 1)
    val iter = javax.imageio.ImageIO.getImageReadersBySuffix(suffix)
    while (iter.hasNext()) {
        val reader = iter.next()
        var stream: javax.imageio.stream.FileImageInputStream? = null
        try {
            stream = javax.imageio.stream.FileImageInputStream(file)
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

// Immutable layout models and layout builder

// Local data classes only used internally within buildLayoutGraph
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
    val height: Float,
    val imagePaths: List<String> = emptyList(), // screenshot paths for rendering
    val selectedState: Int = 0
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

// Backward compatibility: keep old function signature for now
suspend fun buildLayoutGraph(
    appGraph: AppGraph,
    projectPath: String? = null,
    scale: Float = 0.33f
): LayoutGraph = LayoutGraphBuilder.build(appGraph, projectPath, scale)
