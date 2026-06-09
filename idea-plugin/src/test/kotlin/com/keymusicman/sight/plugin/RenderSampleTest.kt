package com.keymusicman.sight.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenderSampleTest {

    @Test
    fun `GcStats minus operator returns delta`() {
        val before = GcStats(collectionCount = 10L, collectionTimeMs = 200L)
        val after  = GcStats(collectionCount = 13L, collectionTimeMs = 260L)
        val delta  = after - before
        assertEquals(3L,  delta.collectionCount)
        assertEquals(60L, delta.collectionTimeMs)
    }

    @Test
    fun `RenderSample heapDelta is heapAfter minus heapBefore`() {
        val sample = RenderSample(
            inflateMs   = 10L,
            callbacksMs = 5L,
            renderMs    = 12L,
            writeMs     = 30L,
            totalMs     = 57L,
            format      = OutputFormat.PNG,
            outcome     = RenderOutcome.SUCCESS,
            heapBefore  = 100_000_000L,
            heapAfter   = 140_000_000L,
            gcDelta     = GcStats(0L, 0L),
        )
        assertEquals(40_000_000L, sample.heapDelta)
    }

    @Test
    fun `RenderSample heapDelta can be negative when GC reclaims memory`() {
        val sample = RenderSample(
            inflateMs   = 10L,
            callbacksMs = 5L,
            renderMs    = 12L,
            writeMs     = 30L,
            totalMs     = 57L,
            format      = OutputFormat.PNG,
            outcome     = RenderOutcome.SUCCESS,
            heapBefore  = 140_000_000L,
            heapAfter   = 90_000_000L,
            gcDelta     = GcStats(1L, 30L),
        )
        assertTrue(sample.heapDelta < 0)
    }
}
