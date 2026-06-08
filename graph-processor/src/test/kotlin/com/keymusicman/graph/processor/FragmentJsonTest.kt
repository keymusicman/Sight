package com.keymusicman.graph.processor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FragmentJsonTest {

    @Test
    fun emitsScreensTransitionsAndGraphs() {
        val json = buildFragmentJson(
            module = ":mod1",
            screens = listOf(
                ScreenEntry(subgraph = "main", id = "Main", isRoot = true, fqn = "P.Main", location = "src/Main.kt", previewProviderFqn = null),
                ScreenEntry(subgraph = "onboarding", id = "onboarding", isRoot = true, fqn = "P.Onb", location = "", previewProviderFqn = "P.Provider"),
            ),
            transitions = listOf(
                TransitionEntry(sourceFqn = "P.Main", fromSubgraph = "", fromScreen = "", toScreen = "", toSubgraph = "profile", trigger = "tap"),
            ),
            graphs = listOf(
                GraphDefEntry(name = "graph_1", entrySubgraph = "onboarding", dropUnconnected = true, transitions = listOf(
                    TransitionEntry(sourceFqn = null, fromSubgraph = "", fromScreen = "login", toScreen = "", toSubgraph = "registration", trigger = "reg"),
                )),
            ),
        )
        assertTrue(json.contains("\"module\":\":mod1\""), json)
        assertTrue(json.contains("\"is_root\":true"), json)
        assertTrue(json.contains("\"preview_provider_fqn\":\"P.Provider\""), json)
        assertTrue(json.contains("\"preview_provider_fqn\":null"), json)
        assertTrue(json.contains("\"to_subgraph\":\"profile\""), json)
        assertTrue(json.contains("\"name\":\"graph_1\""), json)
        assertTrue(json.contains("\"drop_unconnected\":true"), json)
        assertTrue(json.contains("\"from_screen\":\"login\""), json)
    }

    @Test
    fun emptyFragmentIsValidJsonObject() {
        val json = buildFragmentJson(module = ":x", screens = emptyList(), transitions = emptyList(), graphs = emptyList())
        assertEquals('{', json.trim().first())
        assertEquals('}', json.trim().last())
        assertTrue(json.contains("\"graphs\":[]"), json)
        assertTrue(json.contains("\"screens\":[]"), json)
        assertTrue(json.contains("\"transitions\":[]"), json)
    }
}
