package com.keymusicman.appflowerplugin.renderworker.worker

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.resources.ResourceType
import com.keymusicman.appflowerplugin.renderworker.worker.fixtures.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceIdRegistryTest {
    private val registry = ResourceIdRegistry.fromRClasses(
        listOf(R.drawable::class.java, R.string::class.java, R.styleable::class.java),
    )

    @Test
    fun `maps int ids to RES_AUTO references`() {
        assertEquals(
            ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.DRAWABLE, "img_reward_one"),
            registry.resolve(0x7f080001),
        )
        assertEquals(
            ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.STRING, "title"),
            registry.resolve(0x7f0f0001),
        )
    }

    @Test
    fun `maps references back to ids`() {
        val ref = ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.DRAWABLE, "img_reward_two")
        assertEquals(0x7f080002, registry.idOf(ref))
    }

    @Test
    fun `ignores int array styleable fields`() {
        // styleable arrays must not appear and must not crash the scan.
        assertTrue(registry.declaredRefs().none { it.resourceType == ResourceType.STYLEABLE })
        assertEquals(3, registry.size()) // 2 drawables + 1 string
    }

    @Test
    fun `unknown id resolves to null`() {
        assertNull(registry.resolve(0x12345678))
    }
}
