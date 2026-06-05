package com.keymusicman.appflower.aggregator

import com.keymusicman.appflower.model.GraphDef
import com.keymusicman.appflower.model.GraphFragment
import com.keymusicman.appflower.model.ScreenDef
import com.keymusicman.appflower.model.TransitionDef
import com.keymusicman.appflower.model.AppGraph
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

    private fun connFromTo(g: AppGraph): Set<Triple<String, String, String?>> =
        g.subgraphs.values.flatMap { sub ->
            sub.connections.map { c ->
                Triple(
                    "${c.from.subgraph}:${c.from.screen_id}",
                    if (c.to.type == "screen") "${c.to.subgraph}:${c.to.screen_id}" else "subgraph:${c.to.subgraph}",
                    c.trigger.ifEmpty { null },
                )
            }
        }.toSet()

    @Test
    fun functionToScreenResolvesGloballyAcrossSubgraphs() {
        val r = GraphAggregator.aggregate(listOf(frag(
            screens = listOf(
                ScreenDef(subgraph = "main", id = "MainScreen", is_root = true, composable_fqn = "P.MainScreen"),
                ScreenDef(subgraph = "profile", id = "profile", is_root = true, composable_fqn = "P.Profile"),
                ScreenDef(subgraph = "profile", id = "profile_2", composable_fqn = "P.Profile2"),
            ),
            transitions = listOf(
                TransitionDef(source_fqn = "P.MainScreen", to_screen = "profile_2", trigger = "profile_tap"),
            ),
        )))
        assertTrue(r.errors.isEmpty(), r.errors.toString())
        val conns = connFromTo(r.graphs.single().graph)
        assertTrue(Triple("main:MainScreen", "profile:profile_2", "profile_tap") in conns, conns.toString())
        assertTrue(conns.none { it.second == "profile:profile" }, "must not connect to profile")
    }

    @Test
    fun functionToSubgraphMakesSubgraphEdge() {
        val r = GraphAggregator.aggregate(listOf(frag(
            screens = listOf(
                ScreenDef(subgraph = "main", id = "Main", is_root = true, composable_fqn = "P.Main"),
                ScreenDef(subgraph = "profile", id = "profile", is_root = true, composable_fqn = "P.Profile"),
            ),
            transitions = listOf(
                TransitionDef(source_fqn = "P.Main", to_subgraph = "profile", trigger = "profile_tap"),
            ),
        )))
        val conns = connFromTo(r.graphs.single().graph)
        assertTrue(Triple("main:Main", "subgraph:profile", "profile_tap") in conns, conns.toString())
    }

    @Test
    fun ambiguousGlobalToScreenIsError() {
        val r = GraphAggregator.aggregate(listOf(frag(
            screens = listOf(
                ScreenDef(subgraph = "main", id = "Main", is_root = true, composable_fqn = "P.Main"),
                ScreenDef(subgraph = "a", id = "dup", is_root = true),
                ScreenDef(subgraph = "b", id = "dup", is_root = true),
            ),
            transitions = listOf(TransitionDef(source_fqn = "P.Main", to_screen = "dup")),
        )))
        assertTrue(r.errors.any { it.contains("ambiguous") }, r.errors.toString())
    }

    @Test
    fun fromSubgraphSelectsRepeatedScreenMembership() {
        val r = GraphAggregator.aggregate(listOf(frag(
            screens = listOf(
                ScreenDef(subgraph = "onboarding", id = "onboarding", is_root = true, composable_fqn = "P.Onboarding"),
                ScreenDef(subgraph = "onboarding_dark", id = "onboarding", is_root = true, composable_fqn = "P.Onboarding"),
                ScreenDef(subgraph = "onboarding", id = "login", composable_fqn = "P.Login"),
            ),
            transitions = listOf(
                TransitionDef(source_fqn = "P.Onboarding", from_subgraph = "onboarding", to_screen = "login", trigger = "continue"),
            ),
        )))
        val conns = connFromTo(r.graphs.single().graph)
        assertTrue(Triple("onboarding:onboarding", "onboarding:login", "continue") in conns, conns.toString())
        assertTrue(conns.none { it.first == "onboarding_dark:onboarding" }, conns.toString())
    }

    @Test
    fun graphLevelTransitionOnlyAppliesToItsGraph() {
        val r = GraphAggregator.aggregate(listOf(frag(
            graphs = listOf(
                GraphDef(name = "auth_graph"),
                GraphDef(name = "main", transitions = listOf(
                    TransitionDef(from_screen = "main", to_subgraph = "auth", trigger = "registration_complete"),
                )),
            ),
            screens = listOf(
                ScreenDef(subgraph = "auth", id = "login", is_root = true, composable_fqn = "P.Login"),
                ScreenDef(subgraph = "auth", id = "registration", composable_fqn = "P.Reg"),
                ScreenDef(subgraph = "main", id = "main", is_root = true, composable_fqn = "P.Main"),
            ),
        )))
        assertTrue(r.errors.isEmpty(), r.errors.toString())
        val byName = r.graphs.associateBy { it.name }
        val mainConns = connFromTo(byName.getValue("main").graph)
        val authConns = connFromTo(byName.getValue("auth_graph").graph)
        assertTrue(Triple("main:main", "subgraph:auth", "registration_complete") in mainConns, mainConns.toString())
        assertTrue(authConns.none { it.second == "subgraph:auth" && it.first == "main:main" }, authConns.toString())
    }

    @Test
    fun graphLevelTransitionRequiresFromScreen() {
        val r = GraphAggregator.aggregate(listOf(frag(
            graphs = listOf(GraphDef(name = "g", transitions = listOf(TransitionDef(to_subgraph = "x")))),
            screens = listOf(ScreenDef(subgraph = "x", id = "X", is_root = true)),
        )))
        assertTrue(r.errors.any { it.contains("requires fromScreen") }, r.errors.toString())
    }

    @Test
    fun dropUnconnectedKeepsOnlyReachable() {
        val r = GraphAggregator.aggregate(listOf(frag(
            graphs = listOf(GraphDef(name = "g", entry_subgraph = "main_1")),
            screens = listOf(
                ScreenDef(subgraph = "main_1", id = "main", is_root = true, composable_fqn = "P.Main1"),
                ScreenDef(subgraph = "main_2", id = "main", is_root = true, composable_fqn = "P.Main2"),
            ),
        )))
        val g = r.graphs.single().graph
        assertTrue(g.subgraphs.containsKey("main_1"), g.subgraphs.keys.toString())
        assertTrue(!g.subgraphs.containsKey("main_2"), g.subgraphs.keys.toString())
    }

    @Test
    fun dropUnconnectedFalseKeepsEverything() {
        val r = GraphAggregator.aggregate(listOf(frag(
            graphs = listOf(GraphDef(name = "g", entry_subgraph = "main_1", drop_unconnected = false)),
            screens = listOf(
                ScreenDef(subgraph = "main_1", id = "main", is_root = true, composable_fqn = "P.Main1"),
                ScreenDef(subgraph = "main_2", id = "main", is_root = true, composable_fqn = "P.Main2"),
            ),
        )))
        val g = r.graphs.single().graph
        assertTrue(g.subgraphs.containsKey("main_1") && g.subgraphs.containsKey("main_2"), g.subgraphs.keys.toString())
    }

    @Test
    fun noEntryKeepsEverything() {
        val r = GraphAggregator.aggregate(listOf(frag(
            graphs = listOf(GraphDef(name = "g")),
            screens = listOf(
                ScreenDef(subgraph = "a", id = "A", is_root = true),
                ScreenDef(subgraph = "b", id = "B", is_root = true),
            ),
        )))
        val g = r.graphs.single().graph
        assertEquals(setOf("a", "b"), g.subgraphs.keys)
    }

    @Test
    fun reachabilityFollowsSubgraphEdges() {
        val r = GraphAggregator.aggregate(listOf(frag(
            graphs = listOf(GraphDef(name = "g", entry_subgraph = "main")),
            screens = listOf(
                ScreenDef(subgraph = "main", id = "Main", is_root = true, composable_fqn = "P.Main"),
                ScreenDef(subgraph = "profile", id = "profile", is_root = true, composable_fqn = "P.Profile"),
                ScreenDef(subgraph = "orphan", id = "Orphan", is_root = true, composable_fqn = "P.Orphan"),
            ),
            transitions = listOf(TransitionDef(source_fqn = "P.Main", to_subgraph = "profile")),
        )))
        val g = r.graphs.single().graph
        assertEquals(setOf("main", "profile"), g.subgraphs.keys)
    }
}
