package com.keymusicman.appflower.aggregator

import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.model.GraphDef
import com.keymusicman.appflower.model.GraphFragment
import com.keymusicman.appflower.model.GraphMetadata
import com.keymusicman.appflower.model.NamedGraph
import com.keymusicman.appflower.model.Screen
import com.keymusicman.appflower.model.Subgraph

const val DEFAULT_SUBGRAPH = "default"
const val DEFAULT_GRAPH_NAME = "default"

data class AggregationResult(
    val graphs: List<NamedGraph>,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/** A screen in the merged global pool, tagged with its owning module. */
internal data class PooledScreen(
    val subgraph: String,
    val id: String,
    val isRoot: Boolean,
    val composableFqn: String,
    val location: String,
    val previewProviderFqn: String?,
    val module: String,
    val modulePath: String,
)

object GraphAggregator {

    fun aggregate(fragments: List<GraphFragment>): AggregationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val pool = buildScreenPool(fragments, errors)
        val graphDefs = collectGraphDefs(fragments)

        val named = graphDefs.map { (name, def) ->
            val graph = assembleAppGraph(pool, def.entrySubgraph)
            NamedGraph(name = name.ifEmpty { DEFAULT_GRAPH_NAME }, graph = graph)
        }
        return AggregationResult(named, errors, warnings)
    }

    private fun normSubgraph(s: String) = s.ifBlank { DEFAULT_SUBGRAPH }

    private fun buildScreenPool(
        fragments: List<GraphFragment>,
        errors: MutableList<String>,
    ): Map<Pair<String, String>, PooledScreen> {
        val pool = linkedMapOf<Pair<String, String>, PooledScreen>()
        fragments.forEach { frag ->
            frag.screens.forEach { s ->
                val sub = normSubgraph(s.subgraph)
                val key = sub to s.id
                val existing = pool[key]
                if (existing != null) {
                    errors += "Duplicate screen '$sub:${s.id}' declared in modules " +
                        "'${existing.module}' and '${frag.module}'"
                } else {
                    pool[key] = PooledScreen(
                        subgraph = sub, id = s.id, isRoot = s.is_root,
                        composableFqn = s.composable_fqn, location = s.location,
                        previewProviderFqn = s.preview_provider_fqn,
                        module = frag.module, modulePath = frag.module_path,
                    )
                }
            }
        }
        pool.values.groupBy { it.subgraph }.forEach { (sub, screens) ->
            val roots = screens.filter { it.isRoot }.map { it.id }.distinct()
            if (roots.size > 1) errors += "Subgraph '$sub' has multiple roots: $roots"
        }
        return pool
    }

    internal data class MergedGraphDef(
        val entrySubgraph: String,
        val dropUnconnected: Boolean,
        val transitions: List<com.keymusicman.appflower.model.TransitionDef>,
    )

    private fun collectGraphDefs(fragments: List<GraphFragment>): Map<String, MergedGraphDef> {
        val defs: List<GraphDef> = fragments.flatMap { it.graphs }
        if (defs.isEmpty()) {
            return mapOf("" to MergedGraphDef("", dropUnconnected = true, transitions = emptyList()))
        }
        return defs.groupBy { it.name }.mapValues { (_, group) ->
            MergedGraphDef(
                entrySubgraph = group.firstOrNull { it.entry_subgraph.isNotBlank() }?.entry_subgraph ?: "",
                dropUnconnected = group.all { it.drop_unconnected },
                transitions = group.flatMap { it.transitions },
            )
        }
    }

    private fun assembleAppGraph(
        pool: Map<Pair<String, String>, PooledScreen>,
        entrySubgraph: String,
    ): AppGraph {
        val subgraphs = pool.values.groupBy { it.subgraph }.mapValues { (key, screens) ->
            Subgraph(
                key = key,
                root_screen = screens.firstOrNull { it.isRoot }?.id ?: "",
                screens = screens.map {
                    Screen(
                        id = it.id,
                        composable_fqn = it.composableFqn,
                        location = it.location,
                        preview_provider_fqn = it.previewProviderFqn,
                        module_path = it.modulePath,
                    )
                },
                connections = emptyList(),
            )
        }
        return AppGraph(
            metadata = GraphMetadata(
                version = "4.0",
                generated_at = "",
                entry_subgraph = entrySubgraph.ifBlank { null },
            ),
            subgraphs = subgraphs,
        )
    }
}
