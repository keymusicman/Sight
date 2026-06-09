package com.keymusicman.sight.worker

import com.android.ide.common.rendering.api.ILayoutPullParser
import com.android.ide.common.rendering.api.ResourceNamespace
import org.kxml2.io.KXmlParser
import java.io.StringReader

/**
 * Minimal [ILayoutPullParser] that wraps a KXmlParser over the given XML string.
 *
 * Layoutlib does not ship a `LayoutPullParser.createFromString(...)` helper in the
 * version bundled with Studio 2025.x — we must provide our own implementation.
 */
class WorkerPullParser(xml: String) : ILayoutPullParser, KXmlParser() {
    init {
        setFeature("http://xmlpull.org/v1/doc/features.html#process-namespaces", true)
        setInput(StringReader(xml))
    }

    override fun getViewCookie(): Any? = null
    override fun getLayoutNamespace(): ResourceNamespace = ResourceNamespace.RES_AUTO
}
