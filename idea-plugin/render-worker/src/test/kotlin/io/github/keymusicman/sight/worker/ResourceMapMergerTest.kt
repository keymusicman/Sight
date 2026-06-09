package io.github.keymusicman.sight.worker

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.ide.common.rendering.api.ResourceValueImpl
import com.android.ide.common.resources.ResourceValueMap
import com.android.resources.ResourceType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResourceMapMergerTest {
    private fun ref(type: ResourceType, name: String) =
        ResourceReference(ResourceNamespace.RES_AUTO, type, name)

    @Test
    fun `keeps loaded values and injects placeholders for declared-but-missing`() {
        val loaded = ResourceValueMap.create().apply {
            put(ResourceValueImpl(ResourceNamespace.RES_AUTO, ResourceType.DRAWABLE, "real_one", "/path/real_one.webp"))
        }
        val user = mapOf(ResourceType.DRAWABLE to loaded)
        val declared = setOf(ref(ResourceType.DRAWABLE, "real_one"), ref(ResourceType.DRAWABLE, "missing_two"))

        val placeheld = mutableListOf<ResourceReference>()
        val combined = ResourceMapMerger.merge(
            framework = mapOf(ResourceNamespace.ANDROID to emptyMap()),
            user = user,
            declaredRefs = declared,
        ) { placeheld.add(it) }

        val drawables = combined[ResourceNamespace.RES_AUTO]?.get(ResourceType.DRAWABLE)
        assertNotNull(drawables)
        assertEquals("/path/real_one.webp", drawables.get("real_one")!!.value)
        // A missing DRAWABLE must placehold to a loadable bitmap *file* (→ BitmapDrawable), not a
        // "#00000000" COLOR (→ ColorDrawable), which would CCE in Compose's imageResource cast and
        // abort the whole render. See ResourceMapMerger.transparentPngPath.
        val placeholder = drawables.get("missing_two")!!.value!!
        assertTrue(placeholder.endsWith(".png"), "expected a .png file path, got $placeholder")
        assertTrue(File(placeholder).isFile, "placeholder bitmap should exist on disk: $placeholder")
        assertEquals(listOf(ref(ResourceType.DRAWABLE, "missing_two")), placeheld)
    }

    @Test
    fun `typed placeholders per resource type`() {
        val declared = setOf(
            ref(ResourceType.STRING, "s"),
            ref(ResourceType.DIMEN, "d"),
            ref(ResourceType.COLOR, "c"),
        )
        val combined = ResourceMapMerger.merge(emptyMap(), emptyMap(), declared)
        val res = combined[ResourceNamespace.RES_AUTO]!!
        assertEquals("", res[ResourceType.STRING]!!.get("s")!!.value)
        assertEquals("0dp", res[ResourceType.DIMEN]!!.get("d")!!.value)
        assertEquals("#00000000", res[ResourceType.COLOR]!!.get("c")!!.value)
    }

    @Test
    fun `framework namespaces are preserved`() {
        val combined = ResourceMapMerger.merge(
            framework = mapOf(ResourceNamespace.ANDROID to emptyMap()),
            user = emptyMap(),
            declaredRefs = emptySet(),
        )
        assertNotNull(combined[ResourceNamespace.ANDROID])
    }
}
