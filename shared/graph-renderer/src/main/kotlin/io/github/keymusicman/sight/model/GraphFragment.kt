package io.github.keymusicman.sight.model

import kotlinx.serialization.Serializable

/** One module's raw, unresolved contribution to the graph (emitted by KSP, Plan 3). */
@Serializable
data class GraphFragment(
    val module: String = "",
    val module_path: String = "",
    val graphs: List<GraphDef> = emptyList(),
    val screens: List<ScreenDef> = emptyList(),
    val transitions: List<TransitionDef> = emptyList(),
)

/** A `@SightGraph` declaration. `name` blank means the default graph. */
@Serializable
data class GraphDef(
    val name: String = "",
    val entry_subgraph: String = "",
    val drop_unconnected: Boolean = true,
    val transitions: List<TransitionDef> = emptyList(),
)

/** A `@SightScreen` declaration. Blank `subgraph` means the default subgraph. */
@Serializable
data class ScreenDef(
    val subgraph: String = "",
    val id: String,
    val is_root: Boolean = false,
    val composable_fqn: String = "",
    val location: String = "",
    val preview_provider_fqn: String? = null,
)

/** A `@SightTransition`. `source_fqn == null` means it was declared on a graph object. */
@Serializable
data class TransitionDef(
    val source_fqn: String? = null,
    val from_subgraph: String = "",
    val from_screen: String = "",
    val to_screen: String = "",
    val to_subgraph: String = "",
    val trigger: String = "",
)

/** A single resolved graph, ready to lay out and render. */
@Serializable
data class NamedGraph(
    val name: String,
    val graph: AppGraph,
)

/** All graphs discovered across modules — the dropdown's data source. */
@Serializable
data class GraphSet(
    val graphs: List<NamedGraph>,
)
