package com.keymusicman.appflowerplugin.renderworker.worker

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.resources.ResourceType
import com.keymusicman.appflowerplugin.renderworker.worker.fixtures.R
import com.keymusicman.appflowerplugin.renderworker.worker.fixtures.RNonFinal
import com.keymusicman.appflowerplugin.renderworker.worker.fixtures.RZeroValued
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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

    @Test
    fun `materializes a synthetic id into zero-valued non-final R fields`() {
        // The real blank-render bug: AGP app R fields are non-final with no <clinit>, so their
        // runtime value (via getstatic / Field.getInt) is 0 — the real id lives only in a
        // ConstantValue attribute the JVM ignores for non-final fields. The registry must WRITE a
        // usable synthetic id back into the field, so the user's `getstatic R.x` returns something
        // resolveResourceId can map. Registering id 0 (the old behavior) leaves getstatic == 0 and
        // painterResource(0) blows up.
        val field = RZeroValued.drawable::class.java.getField("ic_zero")
        assertEquals(0, field.getInt(null)) // precondition: field is the default 0

        val reg = ResourceIdRegistry.fromRClasses(
            listOf(
                RZeroValued.drawable::class.java,
                RZeroValued.string::class.java,
                RZeroValued.styleable::class.java,
            ),
        )

        val materialized = field.getInt(null)
        assertNotEquals(0, materialized, "registry must write a non-zero id into the field")
        assertEquals(
            ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.DRAWABLE, "ic_zero"),
            reg.resolve(materialized),
            "the materialized id must round-trip back to its reference",
        )
        // int[] styleable still ignored; 2 drawables + 1 string registered.
        assertTrue(reg.declaredRefs().none { it.resourceType == ResourceType.STYLEABLE })
        assertEquals(3, reg.size())
        assertNull(reg.resolve(0), "id 0 must not resolve to anything")
    }

    @Test
    fun `registers non-final static int fields from real AGP R classes`() {
        // Real app/library R classes use `public static int` (non-final). The blank-render bug:
        // these were skipped, so the registry was empty and every painterResource id resolved to
        // null. The scanner must read them while still skipping the int[] styleable field.
        val reg = ResourceIdRegistry.fromRClasses(
            listOf(
                RNonFinal.drawable::class.java,
                RNonFinal.string::class.java,
                RNonFinal.styleable::class.java,
            ),
        )
        assertEquals(
            ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.DRAWABLE, "ic_avatar"),
            reg.resolve(0x7f080010),
        )
        assertEquals(
            0x7f0f0010,
            reg.idOf(ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.STRING, "title")),
        )
        assertTrue(reg.declaredRefs().none { it.resourceType == ResourceType.STYLEABLE })
        assertEquals(3, reg.size()) // 2 drawables + 1 string; int[] styleable skipped
    }
}
