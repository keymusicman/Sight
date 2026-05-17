package com.keymusicman.appflowerplugin.appflowerplugin

import java.io.File

fun shouldSkipIncrementalRender(
    outFile: File,
    sourceFilePath: String?,
    incrementalRendering: Boolean,
): Boolean {
    if (!incrementalRendering) return false
    if (!outFile.exists()) return false
    if (sourceFilePath == null) return true
    val sourceFile = File(sourceFilePath)
    return sourceFile.lastModified() <= outFile.lastModified()
}
