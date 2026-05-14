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

    @Test
    fun `nodeImageRevisions starts empty`() {
        val vm = GraphViewModel()
        assertEquals(emptyMap<String, Int>(), vm.nodeImageRevisions.value)
    }

    @Test
    fun `bumpNodeImageRevision increments revision for the node`() {
        val vm = GraphViewModel()
        vm.bumpNodeImageRevision("root:Home")
        assertEquals(1, vm.nodeImageRevisions.value["root:Home"])
        vm.bumpNodeImageRevision("root:Home")
        assertEquals(2, vm.nodeImageRevisions.value["root:Home"])
    }

    @Test
    fun `bumpNodeImageRevision does not affect other nodes`() {
        val vm = GraphViewModel()
        vm.bumpNodeImageRevision("root:Home")
        assertEquals(null, vm.nodeImageRevisions.value["root:Settings"])
    }
}
