package com.keymusicman.appflower.renderer

import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.model.LayoutGraph
import com.keymusicman.appflower.model.buildLayoutGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renders the full navigation graph to a [BufferedImage] using Java2D.
 * No Compose or Skia dependency — works in both Desktop and IntelliJ plugin contexts.
 * Internally flattens AppGraph and computes layout on Dispatchers.Default.
 */
suspend fun renderGraphToImage(appGraph: AppGraph, projectPath: String? = null): BufferedImage? =
    withContext(Dispatchers.Default) {
        val layoutGraph: LayoutGraph = buildLayoutGraph(appGraph, projectPath)
        if (layoutGraph.nodes.isEmpty()) return@withContext null

        // Load actual image files for rendering  
        val loadedImages: Map<String, BufferedImage?> = layoutGraph.nodes.mapNotNull { (id, ln) ->
            id to ln.imagePaths.firstOrNull()?.let { loadBufferedImage(it) }
        }.toMap()

        val padding = 200f
        val canvasWidth = (layoutGraph.nodes.values.maxOf { it.x + it.width / 2f } + padding)
            .roundToInt().coerceAtLeast(1)
        val canvasHeight = (layoutGraph.nodes.values.maxOf { it.y + it.height / 2f } + padding)
            .roundToInt().coerceAtLeast(1)

        val canvas = BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB)
        val g = canvas.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            color = Color(0x9e9e9e)
            fillRect(0, 0, canvasWidth, canvasHeight)
        }

        // Draw edges
        g.color = Color(0x88, 0x88, 0x88)
        g.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        layoutGraph.edges.forEach { edge ->
            val pts = edge.points
            if (pts.size >= 2) {
                for (i in 0 until pts.size - 1) {
                    g.draw(Line2D.Float(pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y))
                }
                val last = pts.last()
                val prev = pts[pts.size - 2]
                val angle = atan2(last.y - prev.y, last.x - prev.x)
                val arrowSize = 12f
                g.draw(Line2D.Float(
                    last.x, last.y,
                    last.x - arrowSize * cos(angle - Math.PI / 6).toFloat(),
                    last.y - arrowSize * sin(angle - Math.PI / 6).toFloat()
                ))
                g.draw(Line2D.Float(
                    last.x, last.y,
                    last.x - arrowSize * cos(angle + Math.PI / 6).toFloat(),
                    last.y - arrowSize * sin(angle + Math.PI / 6).toFloat()
                ))
            }
        }

        // Draw node images
        layoutGraph.nodes.values.forEach { ln ->
            val img = loadedImages[ln.id] ?: return@forEach
            val dstX = (ln.x - ln.width / 2f).roundToInt()
            val dstY = (ln.y - ln.height / 2f).roundToInt()
            g.drawImage(img, dstX, dstY, ln.width.roundToInt(), ln.height.roundToInt(), null)
        }

        g.dispose()
        canvas
    }

private fun loadBufferedImage(path: String): BufferedImage? = try {
    val file = File(path)
    if (file.exists()) ImageIO.read(file) else null
} catch (_: Exception) {
    null
}
