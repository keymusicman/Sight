package com.keymusicman.appflowerplugin.renderworker.worker

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The worker receives the device size in **pixels** + density and renders the canvas at exactly
 * those pixels (via HardwareConfig). The ComposeViewAdapter root, however, must be sized in **dp**;
 * [pxToCeilDp] derives that dp value, rounding UP so the root is never smaller than the canvas
 * (a smaller root would let the composition shrink below the device size).
 */
class WorkerDimensionsTest {

    @Test
    fun `rounds dp up so the root never undershoots the canvas`() {
        // pixel_5: 1080px / 2340px @ 440dpi → 392.7dp / 850.9dp → ceil 393 / 851.
        assertEquals(393, pxToCeilDp(1080, 440))
        assertEquals(851, pxToCeilDp(2340, 440))
    }

    @Test
    fun `exact dp conversions are not inflated`() {
        // 1440px @ 480dpi = exactly 480dp; must stay 480 (no spurious +1 from ceil).
        assertEquals(480, pxToCeilDp(1440, 480))
    }

    @Test
    fun `falls back to mdpi when density is non-positive`() {
        assertEquals(100, pxToCeilDp(100, 0))
        assertEquals(100, pxToCeilDp(100, -5))
    }
}
