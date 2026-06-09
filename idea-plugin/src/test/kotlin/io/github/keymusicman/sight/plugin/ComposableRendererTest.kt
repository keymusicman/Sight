package io.github.keymusicman.sight.plugin

import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertTrue

class ComposableRendererTest {

    @Test
    fun `clearCaches empties fqnCache`() {
        val field = ComposableRenderer::class.java.getDeclaredField("fqnCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val fqnCache = field.get(ComposableRenderer) as ConcurrentHashMap<String, String>
        fqnCache["com.example.Foo"] = "com.example.FooKt.Foo"
        assertTrue(fqnCache.isNotEmpty(), "cache should be non-empty before clearing")

        ComposableRenderer.clearCaches()

        assertTrue(fqnCache.isEmpty(), "fqnCache should be empty after clearCaches()")
    }
}
