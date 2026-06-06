package com.keymusicman.appflowerplugin.appflowerplugin

import java.io.File

object PreviewCache {

    private const val SENTINEL_FILENAME = ".render-config"
    private const val PREVIEW_DIR = "build/appflower-previews"

    fun outDir(modulePath: String): File = File(modulePath, PREVIEW_DIR)

    fun sentinelFile(modulePath: String): File = File(outDir(modulePath), SENTINEL_FILENAME)

    fun expectedFile(modulePath: String, composableFqn: String, stateIndex: Int = -1, format: OutputFormat = OutputFormat.PNG): File {
        val safeName = composableFqn.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val ext = format.extension
        val name = if (stateIndex >= 0) "${safeName}_${stateIndex}.$ext" else "$safeName.$ext"
        return File(outDir(modulePath), name)
    }

    fun isValid(modulePath: String, config: PreviewRenderConfig): Boolean {
        val sentinel = sentinelFile(modulePath)
        return sentinel.exists() && sentinel.readText() == config.toString()
    }

    fun writeSentinel(modulePath: String, config: PreviewRenderConfig) {
        val sentinel = sentinelFile(modulePath)
        sentinel.parentFile.mkdirs()
        sentinel.writeText(config.toString())
    }

    fun clearAll(modulePath: String) {
        outDir(modulePath).listFiles()?.forEach { it.delete() }
    }

    /**
     * Deletes every `${safeName}_<index>.<ext>` state image for [composableFqn] in this module's
     * preview dir (any extension). Used before re-rendering a multi-state provider so the on-disk
     * set ends up matching exactly the states the provider actually yields — otherwise stale higher
     * indices left over from an over-render (see the worker's provider-exhausted handling) keep
     * showing as phantom duplicate states, and with incremental rendering they even suppress the
     * re-render that would correct the count.
     */
    fun clearIndexedFiles(modulePath: String, composableFqn: String) {
        val safeName = composableFqn.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val rx = Regex("${Regex.escape(safeName)}_\\d+\\.[A-Za-z0-9]+")
        outDir(modulePath).listFiles()?.forEach { if (rx.matches(it.name)) it.delete() }
    }
}
