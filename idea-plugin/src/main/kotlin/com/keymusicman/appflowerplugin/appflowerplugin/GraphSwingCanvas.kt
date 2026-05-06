package com.keymusicman.appflowerplugin.appflowerplugin

import com.keymusicman.appflower.model.LayoutGraph
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import javax.swing.JPanel

class GraphSwingCanvas(
    private val layoutGraph: LayoutGraph,
    private val graphImage: BufferedImage,
    private val onNodeClick: (nodeId: String) -> Unit,
) : JPanel() {

    private var zoom = 1.0
    private var panX = 0.0
    private var panY = 0.0
    private var dragStart: Point? = null
    private var dragStartPan = 0.0 to 0.0
    private var didDrag = false
    private var fittedOnce = false

    init {
        background = Color(0x3c3f41)

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragStart = e.point
                dragStartPan = panX to panY
                didDrag = false
                cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            }
            override fun mouseReleased(e: MouseEvent) {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                if (!didDrag) handleClick(e.point)
                dragStart = null
            }
        })

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val start = dragStart ?: return
                val dx = (e.x - start.x).toDouble()
                val dy = (e.y - start.y).toDouble()
                if (dx * dx + dy * dy > 9.0) didDrag = true
                panX = dragStartPan.first + dx
                panY = dragStartPan.second + dy
                repaint()
            }
        })

        addMouseWheelListener { e ->
            val factor = if (e.wheelRotation < 0) 1.1 else 1.0 / 1.1
            panX = e.x - (e.x - panX) * factor
            panY = e.y - (e.y - panY) * factor
            zoom = (zoom * factor).coerceIn(0.05, 10.0)
            repaint()
        }

        // Fit to view on first layout pass
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (!fittedOnce && width > 0 && height > 0) {
                    fittedOnce = true
                    fitToView()
                }
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        val saved = g2.transform
        g2.translate(panX, panY)
        g2.scale(zoom, zoom)
        g2.drawImage(graphImage, 0, 0, null)
        g2.transform = saved
    }

    private fun fitToView() {
        val w = width.takeIf { it > 0 } ?: return
        val h = height.takeIf { it > 0 } ?: return
        zoom = minOf(w.toDouble() / graphImage.width, h.toDouble() / graphImage.height) * 0.9
        panX = (w - graphImage.width * zoom) / 2.0
        panY = (h - graphImage.height * zoom) / 2.0
        repaint()
    }

    private fun handleClick(screenPoint: Point) {
        val gx = ((screenPoint.x - panX) / zoom).toFloat()
        val gy = ((screenPoint.y - panY) / zoom).toFloat()
        val node = layoutGraph.nodes.values.firstOrNull { ln ->
            gx >= ln.x - ln.width / 2f && gx <= ln.x + ln.width / 2f &&
            gy >= ln.y - ln.height / 2f && gy <= ln.y + ln.height / 2f
        }
        if (node != null) onNodeClick(node.id)
    }
}