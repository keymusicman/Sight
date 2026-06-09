package com.keymusicman.sight.worker

import com.android.ide.common.rendering.api.ActionBarCallback
import com.android.ide.common.rendering.api.ILayoutPullParser
import com.android.ide.common.rendering.api.LayoutlibCallback
import com.android.ide.common.rendering.api.ResourceReference
import com.android.ide.common.rendering.api.ResourceValue
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarFile

/**
 * Minimum [LayoutlibCallback] for the standalone worker.
 *
 * The [loader] must be the user-classpath classloader (parented to the worker's loader so
 * Layoutlib classes are visible). It is consulted for every view/class lookup during
 * inflation, including the `ComposeViewAdapter` and any of the user's composables.
 */
class WorkerLayoutlibCallback(
    private val loader: ClassLoader,
    private val idRegistry: ResourceIdRegistry,
) : LayoutlibCallback() {
    private val nextId = AtomicInteger(0x7f040000)
    private val refToId = mutableMapOf<ResourceReference, Int>()
    private val idToRef = mutableMapOf<Int, ResourceReference>()

    // Cache JarFile instances — every framework XML lookup hits the same framework_res.jar,
    // and opening JarFile is expensive enough that DecorView's two interpolator lookups alone
    // would double the per-render cost without caching.
    private val jarCache = ConcurrentHashMap<String, JarFile>()

    override fun loadView(name: String, signature: Array<out Class<*>>, args: Array<out Any>): Any {
        val cls = loader.loadClass(name)
        val ctor = cls.getConstructor(*signature)
        return ctor.newInstance(*args)
    }

    override fun resolveResourceId(id: Int): ResourceReference? =
        idRegistry.resolve(id) ?: idToRef[id]

    override fun getOrGenerateResourceId(ref: ResourceReference): Int =
        idRegistry.idOf(ref) ?: refToId.getOrPut(ref) {
            val id = nextId.getAndIncrement()
            idToRef[id] = ref
            id
        }

    override fun getParser(layoutResource: ResourceValue?): ILayoutPullParser? = null

    override fun getAdapterBinding(viewObject: Any?, attributes: MutableMap<String, String>?) = null

    override fun getActionBarCallback() = object : ActionBarCallback() {}

    override fun findClass(name: String): Class<*> = loader.loadClass(name)

    override fun isClassLoaded(name: String): Boolean = try {
        Class.forName(name, false, loader); true
    } catch (_: Throwable) {
        false
    }

    override fun hasAndroidXAppCompat(): Boolean = true
    override fun shouldUseCustomInflater(): Boolean = true

    // XmlParserFactory
    override fun createXmlParserForPsiFile(fileName: String?) = null

    /**
     * Called by Layoutlib for **every** framework XML resource (interpolators, anims,
     * drawables, color state lists, etc.) — `ResourceHelper.getXmlBlockParser` →
     * `ParserFactory.create(path)` → `XmlParserFactory.createXmlParserForFile(path)`.
     *
     * The path is the value of [ResourceValue.getValue], populated by
     * `FrameworkResourceRepository`'s `getResourceUrlPrefix()` which produces strings of the
     * form `jar://<absJarPath>!/res/<type>/<name>.xml`. We must parse that URL and stream
     * the entry out of the jar.
     *
     * Returning null here is what produced the `DecorView.<init>` NPE before this fix:
     * `AnimationUtils.loadInterpolator(R.interpolator.linear_out_slow_in)` got a null parser
     * back and crashed in `createInterpolatorFromXml`, killing every render before the
     * composable could run.
     */
    override fun createXmlParserForFile(fileName: String?): XmlPullParser? {
        if (fileName.isNullOrBlank()) return null
        return try {
            val bytes = readResourceBytes(fileName) ?: return null
            KXmlParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(ByteArrayInputStream(bytes), null)
            }
        } catch (e: Throwable) {
            System.err.println("worker: createXmlParserForFile failed for '$fileName': ${e.message}")
            null
        }
    }

    override fun createXmlParser() = KXmlParser()

    /**
     * Reads bytes from a Studio-style resource URL. Mirrors `FileResourceReader.readBytes`
     * (in `com.android.tools.res`) closely enough to handle every shape `FrameworkResourceRepository`
     * and `AarSourceResourceRepository` produce:
     *  - `jar://<path>!/<entry>` or `apk://<path>!/<entry>` — zip entry
     *  - `file://<path>` or plain `<path>` — filesystem read
     *  - `<path>!/<entry>` with no scheme — also a zip entry (some prefixes drop the `jar://`)
     */
    private fun readResourceBytes(path: String): ByteArray? {
        val zipPrefixIdx = when {
            path.startsWith("jar:") -> "jar:".length + (if (path.startsWith("jar://")) 2 else 0)
            path.startsWith("apk:") -> "apk:".length + (if (path.startsWith("apk://")) 2 else 0)
            else -> -1
        }
        if (zipPrefixIdx >= 0) {
            val sep = path.lastIndexOf("!/")
            if (sep < zipPrefixIdx) return null
            return readZipEntry(path.substring(zipPrefixIdx, sep), path.substring(sep + 2))
        }
        if (path.startsWith("file:")) {
            val start = if (path.startsWith("file://")) 7 else 5
            return File(path.substring(start)).takeIf { it.isFile }?.readBytes()
        }
        // Bare `<jar>!/<entry>` — some Studio code paths drop the scheme.
        val sep = path.lastIndexOf("!/")
        if (sep > 0) return readZipEntry(path.substring(0, sep), path.substring(sep + 2))
        return File(path).takeIf { it.isFile }?.readBytes()
    }

    private fun readZipEntry(zipPath: String, entry: String): ByteArray? {
        val jar = jarCache.computeIfAbsent(zipPath) { JarFile(it) }
        val je = jar.getJarEntry(entry) ?: return null
        return jar.getInputStream(je).use { it.readBytes() }
    }
}
