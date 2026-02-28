package com.keymusicman.appflower.utils

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.keymusicman.appflower.model.Graph
import com.keymusicman.appflower.model.GraphEdge
import com.keymusicman.appflower.model.GraphNode
import com.keymusicman.appflower.model.LayoutGraph
import com.keymusicman.appflower.model.buildLayoutGraph
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Exports the full navigation graph as a PNG image file.
 * Renders the complete graph at layout scale (zoom = 1) regardless of current UI pan/zoom.
 * Opens a file-save dialog so the user can choose a destination.
 */
fun exportGraphAsImage(graph: Graph, projectPath: String?) {
    val domainNodes = graph.nodes.map { n -> GraphNode(n.id, n.imagePaths, n.selectedState) }
    val domainEdges = graph.edges.map { e -> GraphEdge(e.from, e.to, e.trigger) }

    // Load Skia images and build a Compose ImageBitmap map for layout sizing
    val skiaImages: Map<String, SkiaImage?> = domainNodes.associate { node ->
        node.id to node.imagePaths.firstOrNull()?.let { loadSkiaImage(it) }
    }
    val composeBitmapMap = skiaImages.mapNotNull { (id, img) ->
        img?.let { id to it.toComposeImageBitmap() }
    }.toMap()

    val layoutGraph: LayoutGraph = buildLayoutGraph(domainNodes, domainEdges, composeBitmapMap)
    if (layoutGraph.nodes.isEmpty()) return

    // Compute canvas size from layout bounds
    val padding = 200f
    val canvasWidth = (layoutGraph.nodes.values.maxOf { it.x + it.width / 2f } + padding).roundToInt().coerceAtLeast(1)
    val canvasHeight = (layoutGraph.nodes.values.maxOf { it.y + it.height / 2f } + padding).roundToInt().coerceAtLeast(1)

    val surface = Surface.makeRaster(
        ImageInfo(canvasWidth, canvasHeight, ColorType.N32, ColorAlphaType.PREMUL, ColorSpace.sRGB)
    )
    val canvas = surface.canvas

    canvas.clear(0xFF9e9e9e.toInt()) // white background
    drawEdges(canvas, layoutGraph)
    drawNodes(canvas, layoutGraph, skiaImages)

    val pngBytes = surface.makeImageSnapshot()
        .encodeToData(EncodedImageFormat.PNG)
        ?.bytes ?: return

    // Prompt user for a save path
    val chooser = JFileChooser().apply {
        dialogTitle = "Save Graph as Image"
        fileFilter = FileNameExtensionFilter("PNG Images (*.png)", "png")
        selectedFile = File("navigation_graph.png")
    }
    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        var dest = chooser.selectedFile
        if (!dest.name.lowercase().endsWith(".png")) dest = File(dest.absolutePath + ".png")
        dest.writeBytes(pngBytes)
    }
}

private fun loadSkiaImage(path: String): SkiaImage? = try {
    val file = File(path)
    if (file.exists()) SkiaImage.makeFromEncoded(file.readBytes()) else null
} catch (_: Exception) {
    null
}

private fun drawEdges(canvas: Canvas, layoutGraph: LayoutGraph) {
    val paint = Paint().apply {
        color = 0xFF888888.toInt()
        strokeWidth = 2f
        isAntiAlias = true
        strokeCap = PaintStrokeCap.ROUND
    }
    layoutGraph.edges.forEach { edge ->
        val pts = edge.points
        if (pts.size >= 2) {
            for (i in 0 until pts.size - 1) {
                canvas.drawLine(pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y, paint)
            }
            val last = pts.last()
            val prev = pts[pts.size - 2]
            val angle = atan2(last.y - prev.y, last.x - prev.x)
            val arrowSize = 12f
            canvas.drawLine(last.x, last.y,
                last.x - arrowSize * cos(angle - Math.PI / 6).toFloat(),
                last.y - arrowSize * sin(angle - Math.PI / 6).toFloat(), paint)
            canvas.drawLine(last.x, last.y,
                last.x - arrowSize * cos(angle + Math.PI / 6).toFloat(),
                last.y - arrowSize * sin(angle + Math.PI / 6).toFloat(), paint)
        }
    }
}

private fun drawNodes(canvas: Canvas, layoutGraph: LayoutGraph, skiaImages: Map<String, SkiaImage?>) {
    val sampling = FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)
    val paint = Paint().apply { isAntiAlias = true }
    layoutGraph.nodes.values.forEach { ln ->
        val img = skiaImages[ln.id] ?: return@forEach
        val src = Rect.makeWH(img.width.toFloat(), img.height.toFloat())
        val dst = Rect.makeXYWH(ln.x - ln.width / 2f, ln.y - ln.height / 2f, ln.width, ln.height)
        canvas.drawImageRect(img, src, dst, sampling, paint, true)
    }
}
