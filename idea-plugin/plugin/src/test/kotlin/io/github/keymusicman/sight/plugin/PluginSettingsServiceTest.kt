package io.github.keymusicman.sight.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginSettingsServiceTest {

    @Test
    fun `default state has PNG output format`() {
        val state = PluginSettingsService.State()
        assertEquals(OutputFormat.PNG, state.outputFormat)
    }

    @Test
    fun `default state has jpegQuality 85`() {
        val state = PluginSettingsService.State()
        assertEquals(85, state.jpegQuality)
    }

    @Test
    fun `default state has incremental rendering disabled`() {
        val state = PluginSettingsService.State()
        assertFalse(state.incrementalRendering)
    }

    @Test
    fun `default state has telemetry enabled`() {
        val state = PluginSettingsService.State()
        assertTrue(state.telemetryEnabled)
    }
}
