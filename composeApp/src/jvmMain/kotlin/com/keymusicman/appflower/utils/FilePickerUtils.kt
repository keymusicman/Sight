package com.keymusicman.appflower.utils

import java.io.File
import java.io.FilenameFilter
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

/** Opens a native file picker dialog for selecting an app-graph.json file.
 *  Uses AWT FileDialog on macOS for native look; JFileChooser elsewhere. */
fun openGraphFilePicker(): File? {
    val isMac = System.getProperty("os.name").lowercase().contains("mac")
    return if (isMac) {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Open app-graph.json", java.awt.FileDialog.LOAD)
        dialog.filenameFilter = FilenameFilter { _, name -> name.endsWith(".json") }
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val file = dialog.file ?: return null
        File(dir, file)
    } else {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        val chooser = JFileChooser()
        chooser.dialogTitle = "Open app-graph.json"
        chooser.fileFilter = FileNameExtensionFilter("JSON files (*.json)", "json")
        val result = chooser.showOpenDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }
}
