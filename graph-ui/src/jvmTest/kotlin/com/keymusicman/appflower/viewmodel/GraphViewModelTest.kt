package com.keymusicman.appflower.viewmodel

import org.junit.Test
import kotlin.test.assertEquals

class GraphViewModelTest {

    @Test
    fun `backgroundColorState defaults to first preset`() {
        val vm = GraphViewModel()
        assertEquals(GraphViewModel.BACKGROUND_PRESETS[0], vm.backgroundColorState.value)
    }

    @Test
    fun `backgroundColorState can be set to any preset`() {
        val vm = GraphViewModel()
        vm.backgroundColorState.value = GraphViewModel.BACKGROUND_PRESETS[3]
        assertEquals(GraphViewModel.BACKGROUND_PRESETS[3], vm.backgroundColorState.value)
    }
}
