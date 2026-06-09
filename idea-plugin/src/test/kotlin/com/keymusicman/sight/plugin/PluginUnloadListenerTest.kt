package com.keymusicman.sight.plugin

import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginUnloadListenerTest {

    private fun fqnCache(): ConcurrentHashMap<String, String> {
        val field = ComposableRenderer::class.java.getDeclaredField("fqnCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(ComposableRenderer) as ConcurrentHashMap<String, String>
    }

    @Test
    fun `handleUnload clears caches when plugin id matches`() {
        fqnCache()["com.example.Foo"] = "com.example.FooKt.Foo"
        assertTrue(fqnCache().isNotEmpty())

        PluginUnloadListener().handleUnload("com.keymusicman.sight.SightPlugin")

        assertTrue(fqnCache().isEmpty())
    }

    @Test
    fun `handleUnload does nothing for an unrelated plugin id`() {
        fqnCache()["com.example.Foo"] = "com.example.FooKt.Foo"

        PluginUnloadListener().handleUnload("com.some.other.Plugin")

        assertFalse(fqnCache().isEmpty())
        fqnCache().clear()
    }
}
