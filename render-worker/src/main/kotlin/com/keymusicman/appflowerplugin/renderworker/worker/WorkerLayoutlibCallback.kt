package com.keymusicman.appflowerplugin.renderworker.worker

import com.android.ide.common.rendering.api.ActionBarCallback
import com.android.ide.common.rendering.api.ILayoutPullParser
import com.android.ide.common.rendering.api.LayoutlibCallback
import com.android.ide.common.rendering.api.ResourceReference
import com.android.ide.common.rendering.api.ResourceValue
import org.kxml2.io.KXmlParser
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimum [LayoutlibCallback] for the standalone worker.
 *
 * The [loader] must be the user-classpath classloader (parented to the worker's loader so
 * Layoutlib classes are visible). It is consulted for every view/class lookup during
 * inflation, including the `ComposeViewAdapter` and any of the user's composables.
 */
class WorkerLayoutlibCallback(private val loader: ClassLoader) : LayoutlibCallback() {
    private val nextId = AtomicInteger(0x7f040000)
    private val refToId = mutableMapOf<ResourceReference, Int>()
    private val idToRef = mutableMapOf<Int, ResourceReference>()

    override fun loadView(name: String, signature: Array<out Class<*>>, args: Array<out Any>): Any {
        val cls = loader.loadClass(name)
        val ctor = cls.getConstructor(*signature)
        return ctor.newInstance(*args)
    }

    override fun resolveResourceId(id: Int): ResourceReference? = idToRef[id]

    override fun getOrGenerateResourceId(ref: ResourceReference): Int =
        refToId.getOrPut(ref) {
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
    override fun createXmlParserForFile(fileName: String?) = null
    override fun createXmlParser() = KXmlParser()
}
