package com.keymusicman.appflower.aggregator

import com.keymusicman.appflower.model.GraphFragment
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphAggregatorTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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
}
