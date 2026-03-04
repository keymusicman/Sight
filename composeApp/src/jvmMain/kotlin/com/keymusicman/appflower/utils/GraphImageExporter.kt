package com.keymusicman.appflower.utils

import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.renderer.renderGraphToImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Exports the full navigation graph as a PNG image file.
 * Opens a file-save dialog so the user can choose a destination.
 * Rendering is delegated to the shared :graph-renderer module (runs on IO dispatcher).
 */
suspend fun exportGraphAsImage(appGraph: AppGraph, projectPath: String?) {
    val image = renderGraphToImage(appGraph, projectPath) ?: return

    val chooser = JFileChooser().apply {
        dialogTitle = "Save Graph as Image"
        fileFilter = FileNameExtensionFilter("PNG Images (*.png)", "png")
        selectedFile = File("navigation_graph.png")
    }
    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        var dest = chooser.selectedFile
        if (!dest.name.lowercase().endsWith(".png")) dest = File(dest.absolutePath + ".png")
        ImageIO.write(image, "PNG", dest)
    }
}
