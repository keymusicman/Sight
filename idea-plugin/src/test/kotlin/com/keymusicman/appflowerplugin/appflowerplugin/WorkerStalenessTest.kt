package com.keymusicman.appflowerplugin.appflowerplugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [decideStaleness] — the two-tier rule [SubprocessRenderer] applies before each
 * render to decide whether its pooled worker can be reused.
 *
 * The fast-path property matters for cost: when the cheap stat-only stamp is unchanged, the
 * expensive content hash MUST NOT be computed (see [ClasspathFingerprint]). These tests pass a
 * hash supplier that records whether it was invoked.
 */
class WorkerStalenessTest {

    private class RecordingHash(val value: String) : () -> String {
        var called = false
        override fun invoke(): String { called = true; return value }
    }

    @Test
    fun `unchanged cheap stamp is Fresh and never computes the content hash`() {
        val hash = RecordingHash("anything")

        val decision = decideStaleness(
            storedCheapStamp = 42L,
            storedContentHash = "h0",
            currentCheapStamp = 42L,
            currentContentHash = hash,
        )

        assertEquals(StalenessDecision.Fresh, decision)
        assertFalse(hash.called, "content hash must not be computed on the per-render fast path")
    }

    @Test
    fun `moved cheap stamp with matching hash is a NoOpRefresh carrying the new stamp`() {
        val decision = decideStaleness(
            storedCheapStamp = 42L,
            storedContentHash = "h0",
            currentCheapStamp = 99L,
            currentContentHash = { "h0" }, // identical bytecode, just newer mtimes
        )

        assertEquals(StalenessDecision.NoOpRefresh(99L), decision)
    }

    @Test
    fun `moved cheap stamp with changed hash is Recycle`() {
        val hash = RecordingHash("h1")

        val decision = decideStaleness(
            storedCheapStamp = 42L,
            storedContentHash = "h0",
            currentCheapStamp = 99L,
            currentContentHash = hash,
        )

        assertEquals(StalenessDecision.Recycle, decision)
        assertTrue(hash.called, "content hash must be consulted once the cheap stamp moved")
    }
}
