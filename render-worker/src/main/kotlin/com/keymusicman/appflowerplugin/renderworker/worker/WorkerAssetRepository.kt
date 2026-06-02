package com.keymusicman.appflowerplugin.renderworker.worker

import com.android.ide.common.rendering.api.AssetRepository
import java.io.File
import java.io.InputStream
import java.util.jar.JarFile

/**
 * Serves files for `AssetManager.openNonAsset` — framework resources out of `framework_res.jar`
 * AND user resources from their absolute on-disk paths. Raw bitmaps (webp/png) resolved from the
 * user repo arrive here as absolute file paths; XML resources go through
 * [WorkerLayoutlibCallback.createXmlParserForFile] instead.
 */
class WorkerAssetRepository(frameworkJar: File) : AssetRepository() {
    private val jar = JarFile(frameworkJar)

    override fun isSupported(): Boolean = true
    override fun openAsset(path: String?, mode: Int): InputStream? = null

    override fun openNonAsset(cookie: Int, path: String?, mode: Int): InputStream? {
        if (path.isNullOrEmpty()) return null
        // Framework jar entries (Layoutlib passes paths with and without the "res/" prefix).
        jar.getJarEntry(path)?.let { return jar.getInputStream(it) }
        if (!path.startsWith("res/")) {
            jar.getJarEntry("res/$path")?.let { return jar.getInputStream(it) }
        }
        // User resource file at an absolute on-disk path.
        val f = File(path)
        if (f.isFile) return f.inputStream()
        return null
    }
}
