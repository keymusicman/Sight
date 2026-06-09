package io.github.keymusicman.sight.plugin

import io.github.keymusicman.sight.model.AppGraph
import io.github.keymusicman.sight.model.GraphMetadata
import io.github.keymusicman.sight.model.Screen
import io.github.keymusicman.sight.model.Subgraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphRenderingServiceTest {

    private fun graph(vararg screens: Screen) = AppGraph(
        metadata = GraphMetadata(version = "4.0", generated_at = ""),
        subgraphs = mapOf("s" to Subgraph("s", screens.firstOrNull()?.id ?: "", screens.toList(), emptyList())),
    )

    @Test
    fun rendersEachScreenWithItsOwnModulePath() {
        val calls = mutableListOf<Triple<String, String, Int>>() // modulePath, fqn, stateIndex
        val g = graph(
            Screen(id = "A", composable_fqn = "P.A", module_path = "/modA"),
            Screen(id = "B", composable_fqn = "P.B", module_path = "/modB"),
        )
        GraphRenderingService.renderSelectedStates(g, defaultModulePath = "/fallback") { modulePath, fqn, _, stateIndex ->
            calls += Triple(modulePath, fqn, stateIndex)
            "$modulePath/$fqn.png"
        }
        assertTrue(Triple("/modA", "P.A", -1) in calls, calls.toString())
        assertTrue(Triple("/modB", "P.B", -1) in calls, calls.toString())
    }

    @Test
    fun fallsBackToDefaultModulePathWhenBlank() {
        val calls = mutableListOf<String>()
        val g = graph(Screen(id = "A", composable_fqn = "P.A", module_path = ""))
        GraphRenderingService.renderSelectedStates(g, defaultModulePath = "/fallback") { modulePath, _, _, _ ->
            calls += modulePath; null
        }
        assertEquals(listOf("/fallback"), calls)
    }

    @Test
    fun usesSelectedStateIndexWhenProviderPresent() {
        val calls = mutableListOf<Int>()
        val g = graph(Screen(id = "A", composable_fqn = "P.A", preview_provider_fqn = "P.Provider", selected_state = 3, module_path = "/m"))
        GraphRenderingService.renderSelectedStates(g, defaultModulePath = "/fallback") { _, _, provider, stateIndex ->
            assertEquals("P.Provider", provider)
            calls += stateIndex; null
        }
        assertEquals(listOf(3), calls)
    }

    @Test
    fun clampsNegativeSelectedStateToZero() {
        val states = mutableListOf<Int>()
        val g = graph(Screen(id = "A", composable_fqn = "P.A", preview_provider_fqn = "P.P", selected_state = -5, module_path = "/m"))
        GraphRenderingService.renderSelectedStates(g, defaultModulePath = "/f") { _, _, _, stateIndex ->
            states += stateIndex; null
        }
        assertEquals(listOf(0), states)
    }

    @Test
    fun skipsScreensWithBlankComposableFqn() {
        val calls = mutableListOf<String>()
        val g = graph(Screen(id = "A", composable_fqn = "", module_path = "/modA"))
        GraphRenderingService.renderSelectedStates(g, defaultModulePath = "/fallback") { _, fqn, _, _ ->
            calls += fqn; null
        }
        assertTrue(calls.isEmpty(), calls.toString())
    }
}
