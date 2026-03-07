package com.keymusicman.appflower.utils

import com.keymusicman.appflower.model.AppGraph
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
    val projectRoot = File(projectPath.trim())
    require(projectRoot.exists() && projectRoot.isDirectory) { "Invalid project path: ${projectRoot.absolutePath}" }

    val graphFile = resolveGraphFile(projectRoot)
        ?: error("app-graph.json not found. Expected at app/build/graph/app-graph.json or build/graph/app-graph.json")

    val chooser = JFileChooser().apply {
        dialogTitle = "Save Web Archive ZIP"
        fileFilter = FileNameExtensionFilter("ZIP Archives (*.zip)", "zip")
        selectedFile = File("navigation_graph_bundle.zip")
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) {
        return "ZIP preparation canceled."
    }

    var destination = chooser.selectedFile
    if (!destination.name.lowercase().endsWith(".zip")) {
        destination = File(destination.absolutePath + ".zip")
    }

    val stagingDir = Files.createTempDirectory("appflower-zip-").toFile()
    try {
        graphFile.copyTo(File(stagingDir, "app-graph.json"), overwrite = true)

        val screenshotLocations = appGraph.subgraphs.values
            .flatMap { subgraph -> subgraph.screens.map { it.screenshot_location } }
            .filter { it.isNotBlank() }
            .distinct()

        var copiedLocations = 0
        var missingLocations = 0
        screenshotLocations.forEach { location ->
            val source = File(projectRoot, location)
            if (!source.exists()) {
                missingLocations++
                return@forEach
            }
            val destinationPath = File(stagingDir, location)
            if (source.isDirectory) {
                source.copyRecursively(destinationPath, overwrite = true)
            } else {
                destinationPath.parentFile?.mkdirs()
                source.copyTo(destinationPath, overwrite = true)
            }
            copiedLocations++
        }

        zipDirectoryContents(stagingDir, destination)
        return "ZIP prepared: ${destination.absolutePath}\nCopied screenshot locations: $copiedLocations, missing: $missingLocations"
    } finally {
        stagingDir.deleteRecursively()
    }
}

private fun resolveGraphFile(projectRoot: File): File? {
    val appPath = File(projectRoot, "app/build/graph/app-graph.json")
    if (appPath.exists()) return appPath
    val rootPath = File(projectRoot, "build/graph/app-graph.json")
    if (rootPath.exists()) return rootPath
    return null
}

private fun zipDirectoryContents(sourceDir: File, zipFile: File) {
    val rootPath = sourceDir.toPath()
    ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
        sourceDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relative = rootPath.relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(relative))
                FileInputStream(file).use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
    }
}
