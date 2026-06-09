package com.keymusicman.sight.viewmodel

import com.keymusicman.sight.model.LayoutGraph
import com.keymusicman.sight.model.LayoutNode
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.assertEquals

class GraphViewModelTest {

    private fun writePng(width: Int, height: Int): String {
        val dir = Files.createTempDirectory("af-graphvm-test").toFile()
        val file = File(dir, "state-${width}x${height}.png")
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", file)
        return file.absolutePath
    }

    private fun singleNodeGraph(node: LayoutNode) =
        LayoutGraph(nodes = mapOf(node.id to node), edges = emptyList())

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

    @Test
    fun `updateNodeImages resizes node to match the new image dimensions`() {
        val vm = GraphViewModel()
        val newImage = writePng(width = 80, height = 160)
        val node = LayoutNode(
            id = "root:Home", x = 100f, y = 200f,
            width = 999f, height = 999f,
            imagePaths = listOf("/old/path.png"), selectedState = 0,
        )
        vm.layoutGraphState.value = singleNodeGraph(node)
        vm.displayLayoutGraphState.value = singleNodeGraph(node)

        vm.updateNodeImages("root:Home", listOf(newImage))

        // Layout scales image dimensions by 0.5, so 80x160 -> 40x80.
        val updated = vm.displayLayoutGraphState.value!!.nodes["root:Home"]!!
        assertEquals(40f, updated.width)
        assertEquals(80f, updated.height)
        assertEquals(listOf(newImage), updated.imagePaths)
    }

    @Test
    fun `updateNodeImages keeps the node centered on the same point`() {
        val vm = GraphViewModel()
        val newImage = writePng(width = 200, height = 100)
        val node = LayoutNode(
            id = "root:Home", x = 100f, y = 200f,
            width = 10f, height = 10f,
            imagePaths = listOf("/old/path.png"), selectedState = 0,
        )
        vm.layoutGraphState.value = singleNodeGraph(node)
        vm.displayLayoutGraphState.value = singleNodeGraph(node)

        vm.updateNodeImages("root:Home", listOf(newImage))

        val updated = vm.displayLayoutGraphState.value!!.nodes["root:Home"]!!
        assertEquals(100f, updated.x)
        assertEquals(200f, updated.y)
    }
}
