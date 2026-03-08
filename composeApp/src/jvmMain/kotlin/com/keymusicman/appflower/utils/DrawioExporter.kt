package com.keymusicman.appflower.utils

import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.model.LayoutGraph
import com.keymusicman.appflower.model.buildLayoutGraph
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Exports the navigation graph as a draw.io (.drawio) file.
 * Each node is represented as an mxCell with an embedded Base64 screenshot (if available),
 * or a plain rounded-rectangle shape otherwise.
 * Opens a file-save dialog so the user can choose the destination.
 */
suspend fun exportGraphAsDrawio(appGraph: AppGraph, projectPath: String?) {
    // Build complete layout graph (includes flattening and image loading internally)
    val layoutGraph: LayoutGraph = buildLayoutGraph(appGraph, projectPath, scale = 0.25f)
    if (layoutGraph.nodes.isEmpty()) return

    // Load image data for embedding in XML (convert paths to Base64)
    val imageDataMap: Map<String, ByteArray?> = layoutGraph.nodes.mapNotNull { (id, layoutNode) ->
        val selectedPath = layoutNode.imagePaths.getOrNull(layoutNode.selectedState)
            ?: layoutNode.imagePaths.firstOrNull()
        id to selectedPath?.let { path ->
            try {
                File(path).takeIf { it.exists() }?.readBytes()
            } catch (_: Exception) {
                null
            }
        }
    }.toMap()

    val xml = buildDrawioXml(layoutGraph, imageDataMap)

    val chooser = JFileChooser().apply {
        dialogTitle = "Save Graph as draw.io"
        fileFilter = FileNameExtensionFilter("draw.io Files (*.drawio)", "drawio")
        selectedFile = File("navigation_graph.drawio")
    }
    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        var dest = chooser.selectedFile
        if (!dest.name.lowercase().endsWith(".drawio")) dest = File(dest.absolutePath + ".drawio")
        dest.writeText(xml, Charsets.UTF_8)
    }
}

private fun buildDrawioXml(
    layoutGraph: LayoutGraph,
    imageDataMap: Map<String, ByteArray?>
): String {
    val sb = StringBuilder()
    sb.appendLine("""<mxfile host="AppFlower" version="1.0">""")
    sb.appendLine("""  <diagram name="Navigation Graph">""")
    sb.appendLine("""    <mxGraphModel>""")
    sb.appendLine("""      <root>""")
    sb.appendLine("""        <mxCell id="0"/>""")
    sb.appendLine("""        <mxCell id="1" parent="0"/>""")

    // Nodes
    layoutGraph.nodes.values.forEach { ln ->
        val safeId = ln.id.sanitizeXmlId()
        val label = ln.id.substringAfterLast(":").xmlEscape()
        val x = (ln.x - ln.width / 2f).toInt()
        val y = (ln.y - ln.height / 2f).toInt()
        val w = ln.width.toInt()
        val h = ln.height.toInt()

        val imgData = imageDataMap[ln.id]
        val style = if (imgData != null) {
            val b64 = Base64.getEncoder().encodeToString(imgData)
            "shape=image;verticalLabelPosition=bottom;labelBackgroundColor=default;verticalAlign=top;" +
                    "aspect=fixed;imageAspect=0;image=data:image/png,$b64;"
        } else {
            "rounded=1;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;"
        }

        sb.appendLine(
            """        <mxCell id="$safeId" value="$label" style="$style" vertex="1" parent="1">"""
        )
        sb.appendLine(
            """          <mxGeometry x="$x" y="$y" width="$w" height="$h" as="geometry"/>"""
        )
        sb.appendLine("""        </mxCell>""")
    }

    // Edges
    layoutGraph.edges.forEachIndexed { idx, le ->
        val safeFrom = le.from.sanitizeXmlId()
        val safeTo = le.to.sanitizeXmlId()
        val fromNode = layoutGraph.nodes[le.from]
        val toNode = layoutGraph.nodes[le.to]
        val start = le.points.firstOrNull()
        val end = le.points.lastOrNull()
        val exitY = if (fromNode != null && start != null && fromNode.height > 0f) {
            ((start.y - (fromNode.y - fromNode.height / 2f)) / fromNode.height).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        val entryY = if (toNode != null && end != null && toNode.height > 0f) {
            ((end.y - (toNode.y - toNode.height / 2f)) / toNode.height).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        sb.appendLine(
            """        <mxCell id="edge_$idx" style="curved=1;rounded=1;html=1;""" +
                    """strokeColor=#888888;opacity=40;strokeWidth=2;endArrow=classic;endSize=8;""" +
                    """exitX=1;exitY=$exitY;exitDx=0;exitDy=0;entryX=0;entryY=$entryY;entryDx=0;entryDy=0;" """ +
                    """edge="1" source="$safeFrom" target="$safeTo" parent="1">"""
        )
        sb.appendLine("""          <mxGeometry relative="1" as="geometry"/>""")
        sb.appendLine("""        </mxCell>""")
    }

    sb.appendLine("""      </root>""")
    sb.appendLine("""    </mxGraphModel>""")
    sb.appendLine("""  </diagram>""")
    sb.append("""</mxfile>""")
    return sb.toString()
}

/** Replace characters that are invalid in XML attribute values used as IDs. */
private fun String.sanitizeXmlId(): String =
    replace(":", "_").replace(" ", "_").replace(".", "_")

/** Escape characters that have special meaning in XML attribute values. */
private fun String.xmlEscape(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
