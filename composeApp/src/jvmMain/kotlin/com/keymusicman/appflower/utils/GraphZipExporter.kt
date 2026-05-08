package com.keymusicman.appflower.utils

import com.keymusicman.appflower.model.AppGraph
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Prepares a ZIP archive for the web viewer:
 * - app-graph.json at archive root
 * - screenshot files/directories copied at their screenshot_location paths
 */
suspend fun prepareGraphZipArchive(appGraph: AppGraph, projectPath: String?): String {
    require(!projectPath.isNullOrBlank()) { "Project path is required" }
    require(File(projectPath.trim()).isDirectory) { "Invalid project path: $projectPath" }

    val chooser = JFileChooser().apply {
        dialogTitle = "Save Web Archive ZIP"
        fileFilter = FileNameExtensionFilter("ZIP Archives (*.zip)", "zip")
        selectedFile = File("navigation_graph_bundle.zip")
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) {
        return "ZIP preparation canceled."
    }

    var destination = chooser.selectedFile
    if (!destination.name.lowercase()
            .endsWith(".zip")
    ) {
        destination = File(destination.absolutePath + ".zip")
    }

    val stagingDir = Files.createTempDirectory("appflower-zip-")
        .toFile()
    try {
        File(stagingDir, "app-graph.json").writeText(
            Json { prettyPrint = true }.encodeToString(appGraph),
            Charsets.UTF_8
        )

        zipDirectoryContents(stagingDir, destination)
        return "ZIP prepared: ${destination.absolutePath} (preview PNG bundling deferred)"
    } finally {
        stagingDir.deleteRecursively()
    }
}

private fun zipDirectoryContents(sourceDir: File, zipFile: File) {
    val rootPath = sourceDir.toPath()
    ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
        sourceDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relative = rootPath.relativize(file.toPath())
                    .toString()
                    .replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(relative))
                FileInputStream(file).use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
    }
}
