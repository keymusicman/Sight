package io.github.keymusicman.sight.model

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewImageResolutionTest {

    private fun newDir(prefix: String): File =
        File.createTempFile(prefix, "").let { it.delete(); it.mkdirs(); it }

    private fun writePng(dir: File, name: String) {
        dir.mkdirs()
        // Minimal valid 1x1 PNG so the layout's dimension read succeeds.
        val bytes = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMBAQAY3Y2wAAAAAElFTkSuQmCC"
        )
        File(dir, name).writeBytes(bytes)
    }

    @Test
    fun resolvesEachScreenFromItsOwnModule() = runTest {
        val modA = newDir("modA")
        val modB = newDir("modB")
        writePng(File(modA, "build/appflower-previews"), "P.A.png")
        writePng(File(modB, "build/appflower-previews"), "P.B.png")

        val appGraph = AppGraph(
            metadata = GraphMetadata(version = "4.0", generated_at = ""),
            subgraphs = mapOf(
                "s" to Subgraph(
                    key = "s", root_screen = "A",
                    screens = listOf(
                        Screen(id = "A", composable_fqn = "P.A", module_path = modA.absolutePath),
                        Screen(id = "B", composable_fqn = "P.B", module_path = modB.absolutePath),
                    ),
                    connections = emptyList(),
                ),
            ),
        )
        val layout = LayoutGraphBuilder.build(appGraph, projectPath = null, scale = 0.33f)
        val a = layout.nodes.getValue("s:A")
        val b = layout.nodes.getValue("s:B")
        assertTrue(a.imagePaths.single().endsWith("/build/appflower-previews/P.A.png"), a.imagePaths.toString())
        assertTrue(b.imagePaths.single().endsWith("/build/appflower-previews/P.B.png"), b.imagePaths.toString())
        assertEquals(2, layout.nodes.size)
    }
}
