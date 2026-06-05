package com.keymusicman.appflower.aggregator

import com.keymusicman.appflower.model.GraphDef
import com.keymusicman.appflower.model.GraphFragment
import com.keymusicman.appflower.model.ScreenDef
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphAggregatorTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun frag(
        module: String = ":m",
        modulePath: String = "/m",
        graphs: List<GraphDef> = emptyList(),
        screens: List<ScreenDef> = emptyList(),
        transitions: List<com.keymusicman.appflower.model.TransitionDef> = emptyList(),
    ) = com.keymusicman.appflower.model.GraphFragment(module, modulePath, graphs, screens, transitions)

    @Test
    fun parsesFragment() {
        val f = json.decodeFromString<GraphFragment>(
            """
            {"module":":a","module_path":"/a",
             "graphs":[{"name":"g","entry_subgraph":"main","drop_unconnected":false,
                        "transitions":[{"from_screen":"x","to_subgraph":"y"}]}],
             "screens":[{"subgraph":"main","id":"Main","is_root":true,"composable_fqn":"P.Main"}],
             "transitions":[{"source_fqn":"P.Main","to_screen":"Other"}]}
            """.trimIndent()
        )
        assertEquals(":a", f.module)
        assertEquals(1, f.graphs.size)
        assertEquals("g", f.graphs[0].name)
        assertEquals(false, f.graphs[0].drop_unconnected)
        assertEquals("Main", f.screens[0].id)
        assertEquals(true, f.screens[0].is_root)
        assertEquals("P.Main", f.transitions[0].source_fqn)
    }

    @Test
    fun blankSubgraphBecomesDefault() {
        val r = GraphAggregator.aggregate(listOf(frag(screens = listOf(ScreenDef(id = "Main")))))
        assertTrue(r.errors.isEmpty(), r.errors.toString())
        val g = r.graphs.single().graph
        assertTrue(g.subgraphs.containsKey("default"))
        assertEquals("Main", g.subgraphs.getValue("default").screens.single().id)
    }

    @Test
    fun duplicateScreenAcrossModulesIsError() {
        val r = GraphAggregator.aggregate(listOf(
            frag(module = ":a", screens = listOf(ScreenDef(subgraph = "main", id = "Main", is_root = true))),
            frag(module = ":b", screens = listOf(ScreenDef(subgraph = "main", id = "Main", is_root = true))),
        ))
        assertTrue(r.errors.any { it.contains("Duplicate screen 'main:Main'") }, r.errors.toString())
    }

    @Test
    fun multipleRootsInSubgraphIsError() {
        val r = GraphAggregator.aggregate(listOf(frag(screens = listOf(
            ScreenDef(subgraph = "main", id = "A", is_root = true),
            ScreenDef(subgraph = "main", id = "B", is_root = true),
        ))))
        assertTrue(r.errors.any { it.contains("multiple roots") }, r.errors.toString())
    }

    @Test
    fun sameIdDifferentSubgraphIsAllowed() {
        val r = GraphAggregator.aggregate(listOf(frag(screens = listOf(
            ScreenDef(subgraph = "onboarding", id = "onboarding", is_root = true),
            ScreenDef(subgraph = "onboarding_dark", id = "onboarding", is_root = true),
        ))))
        assertTrue(r.errors.isEmpty(), r.errors.toString())
    }
}
