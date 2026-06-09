package io.github.keymusicman.sight.plugin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FragmentRepositoryTest {

    private fun tmpModule(name: String, fragmentJson: String?): File {
        val prefix = name.padEnd(3, '_')
        val dir = File.createTempFile(prefix, "").let { it.delete(); it.mkdirs(); it }
        if (fragmentJson != null) {
            val f = File(dir, "build/graph/app-graph-fragment.json")
            f.parentFile.mkdirs()
            f.writeText(fragmentJson)
        }
        return dir
    }

    @Test
    fun readsFragmentsAndFillsModulePathWhenAbsent() {
        val modDir = tmpModule(
            "mod",
            """{"module":":mod","screens":[{"subgraph":"main","id":"Main","is_root":true,"composable_fqn":"P.Main"}]}""",
        )
        val frags = FragmentRepository.readFragments(listOf(modDir.absolutePath))
        assertEquals(1, frags.size)
        assertEquals(":mod", frags[0].module)
        assertEquals(modDir.absolutePath, frags[0].module_path)
    }

    @Test
    fun skipsModulesWithoutFragment() {
        val withFrag = tmpModule("a", """{"module":":a","screens":[]}""")
        val without = tmpModule("b", null)
        val frags = FragmentRepository.readFragments(listOf(withFrag.absolutePath, without.absolutePath))
        assertEquals(listOf(":a"), frags.map { it.module })
    }

    @Test
    fun skipsModulesWithUnparseableFragment() {
        val good = tmpModule("good", """{"module":":good","screens":[]}""")
        val bad = tmpModule("baad", """{ this is not valid json """)
        val frags = FragmentRepository.readFragments(listOf(good.absolutePath, bad.absolutePath))
        assertEquals(listOf(":good"), frags.map { it.module })
    }

    @Test
    fun aggregatesAcrossModules() {
        val a = tmpModule("a", """{"module":":a","screens":[{"subgraph":"main","id":"Main","is_root":true,"composable_fqn":"P.Main"}]}""")
        val b = tmpModule("b", """{"module":":b","screens":[{"subgraph":"profile","id":"Profile","is_root":true,"composable_fqn":"P.Profile"}]}""")
        val result = FragmentRepository.aggregate(listOf(a.absolutePath, b.absolutePath))
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val graph = result.graphs.single().graph
        assertEquals(setOf("main", "profile"), graph.subgraphs.keys)
        assertEquals(a.absolutePath, graph.subgraphs.getValue("main").screens.single().module_path)
    }
}
