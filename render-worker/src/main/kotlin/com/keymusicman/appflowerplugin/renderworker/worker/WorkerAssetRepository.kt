package com.keymusicman.appflowerplugin.renderworker.worker

import com.android.ide.common.rendering.api.AssetRepository
import java.io.File
import java.io.InputStream
import java.util.jar.JarFile

/**
 * Serves files for `AssetManager.open` / `openNonAsset` — framework resources out of
 * `framework_res.jar` AND user resources from their absolute on-disk paths. Raw bitmaps (webp/png)
 * resolved from the user repo arrive here as absolute file paths; XML resources go through
 * [WorkerLayoutlibCallback.createXmlParserForFile] instead.
 */
class WorkerAssetRepository(frameworkJar: File) : AssetRepository() {
    private val jar = JarFile(frameworkJar)

    override fun isSupported(): Boolean = true

    // Fonts loaded via the API-29 path (Build.VERSION.SDK_INT >= 29) come through `AssetManager.open`
    // (the asset branch), not openNonAsset — serve them the same way.
    override fun openAsset(path: String?, mode: Int): InputStream? = openImpl(path)

    override fun openNonAsset(cookie: Int, path: String?, mode: Int): InputStream? = openImpl(path)

    private fun openImpl(path: String?): InputStream? {
        if (path.isNullOrEmpty()) return null
        // Framework jar entries (Layoutlib passes paths with and without the "res/" prefix).
        jar.getJarEntry(path)?.let { return jar.getInputStream(it) }
        if (!path.startsWith("res/")) {
            jar.getJarEntry("res/$path")?.let { return jar.getInputStream(it) }
        }
        // User resource file at an absolute on-disk path.
        File(path).takeIf { it.isFile }?.let { return it.inputStream() }
        // Font values are rewritten to "res/<abs-path>" (see [fontValueForResourcesCompat]) so they
        // pass androidx ResourcesCompat's `startsWith("res/")` gate. Strip the "res" prefix back to
        // the real file so the API-29 font loader (which opens the value via AssetManager) finds it.
        if (path.startsWith("res/")) {
            File(path.substring(3)).takeIf { it.isFile }?.let { return it.inputStream() }
        }
        return null
    }
}
