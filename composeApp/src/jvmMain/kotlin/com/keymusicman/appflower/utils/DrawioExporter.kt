package com.keymusicman.appflower.utils

import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.model.flattenAppGraph
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
    val (domainNodes, domainEdges) = flattenAppGraph(appGraph, projectPath)

    // Load images and derive dimensions for layout
    val imageDataMap: Map<String, Pair<ByteArray, Pair<Int, Int>>?> = domainNodes.associate { node ->
        node.id to node.imagePaths.firstOrNull()?.let { path ->
            try {
                val file = File(path)
                if (file.exists()) {
                    val img = ImageIO.read(file)
                    if (img != null) file.readBytes() to (img.width to img.height) else null
                } else null
            } catch (_: Exception) { null }
        }
    }

    val imageDimensions: Map<String, Pair<Int, Int>> = imageDataMap.mapNotNull { (id, v) ->
        v?.let { id to it.second }
    }.toMap()

    val layoutGraph: LayoutGraph = buildLayoutGraph(domainNodes, domainEdges, imageDimensions, scale = 0.25f)
    if (layoutGraph.nodes.isEmpty()) return

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
    imageDataMap: Map<String, Pair<ByteArray, Pair<Int, Int>>?>
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
            val b64 = Base64.getEncoder().encodeToString(imgData.first)
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
        sb.appendLine(
            """        <mxCell id="edge_$idx" style="edgeStyle=orthogonalEdgeStyle;rounded=0;html=1;""" +
                    """exitX=1;exitY=0.5;exitDx=0;exitDy=0;entryX=0;entryY=0.5;entryDx=0;entryDy=0;""" +
                    """strokeWidth=4;endSize=5;" """ +
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
