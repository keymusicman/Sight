package com.keymusicman.appflowerplugin.appflowerplugin

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the device-resolution logic that decides the pixel size + density the subprocess
 * worker renders at. Regression guard for the bug where the subprocess path ignored the
 * selected device and always shipped the custom dp values (360×640) + a hardcoded 420 dpi,
 * rendering pixel_5 at 945×1680 instead of 1080×2340.
 */
class DeviceRenderSpecTest {

    // Real-ish geometry; only the relationships matter for these tests.
    private val devices = mapOf(
        "pixel_5" to ScreenDims(widthPx = 1080, heightPx = 2340, densityDpi = 440),
        "pixel_6" to ScreenDims(widthPx = 1080, heightPx = 2400, densityDpi = 411),
    )
    private val lookup: (String) -> ScreenDims? = { devices[it] }

    @Test
    fun `default config renders at the pixel_5 device's real pixels and density`() {
        val spec = resolveDeviceRenderSpec(PreviewRenderConfig(), lookup)
        assertEquals(DeviceRenderSpec(widthPx = 1080, heightPx = 2340, densityDpi = 440), spec)
    }

    @Test
    fun `useCustomConfig false ignores deviceId and still uses pixel_5`() {
        val cfg = PreviewRenderConfig(useCustomConfig = false, deviceId = "pixel_6")
        val spec = resolveDeviceRenderSpec(cfg, lookup)
        assertEquals(DeviceRenderSpec(widthPx = 1080, heightPx = 2340, densityDpi = 440), spec)
    }

    @Test
    fun `custom-config preset resolves that device's pixels`() {
        val cfg = PreviewRenderConfig(useCustomConfig = true, deviceId = "pixel_6")
        val spec = resolveDeviceRenderSpec(cfg, lookup)
        assertEquals(DeviceRenderSpec(widthPx = 1080, heightPx = 2400, densityDpi = 411), spec)
    }

    @Test
    fun `custom device sizes by dp at the pixel_5 base density`() {
        val cfg = PreviewRenderConfig(
            useCustomConfig = true,
            deviceId = CUSTOM_DEVICE_ID,
            customWidthDp = 360,
            customHeightDp = 640,
        )
        val spec = resolveDeviceRenderSpec(cfg, lookup)
        // 360dp * 440/160 = 990 ; 640dp * 440/160 = 1760
        assertEquals(DeviceRenderSpec(widthPx = 990, heightPx = 1760, densityDpi = 440), spec)
    }

    @Test
    fun `unknown preset device falls back to pixel_5`() {
        val cfg = PreviewRenderConfig(useCustomConfig = true, deviceId = "no_such_device")
        val spec = resolveDeviceRenderSpec(cfg, lookup)
        assertEquals(DeviceRenderSpec(widthPx = 1080, heightPx = 2340, densityDpi = 440), spec)
    }
}
