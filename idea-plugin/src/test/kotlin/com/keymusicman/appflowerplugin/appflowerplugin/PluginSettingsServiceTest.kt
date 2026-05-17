package com.keymusicman.appflowerplugin.appflowerplugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
