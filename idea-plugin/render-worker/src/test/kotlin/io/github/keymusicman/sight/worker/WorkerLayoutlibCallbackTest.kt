package io.github.keymusicman.sight.worker

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.resources.ResourceType
import io.github.keymusicman.sight.worker.fixtures.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkerLayoutlibCallbackTest {
    private val registry = ResourceIdRegistry.fromRClasses(listOf(R.drawable::class.java))
    private val callback = WorkerLayoutlibCallback(javaClass.classLoader, registry)

    @Test
    fun `resolveResourceId uses the registry first`() {
        assertEquals(
            ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.DRAWABLE, "img_reward_one"),
            callback.resolveResourceId(0x7f080001),
        )
    }

    @Test
    fun `getOrGenerateResourceId returns the registry id for a known ref`() {
        val ref = ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.DRAWABLE, "img_reward_two")
        assertEquals(0x7f080002, callback.getOrGenerateResourceId(ref))
    }

    @Test
    fun `unknown ref gets a generated id that round-trips`() {
        val unknown = ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.STRING, "not_in_registry")
        val id = callback.getOrGenerateResourceId(unknown)
        assertTrue(id >= 0x7f040000)
        assertEquals(unknown, callback.resolveResourceId(id))
    }
}
