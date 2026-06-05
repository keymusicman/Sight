package com.keymusicman.appflower.aggregator

import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.model.Connection
import com.keymusicman.appflower.model.ConnectionEndpoint
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

internal data class ResolvedConn(
    val fromSubgraph: String,
    val fromId: String,
    val toType: String,        // "screen" | "subgraph"
    val toSubgraph: String,
    val toId: String?,
    val trigger: String,
) {
    fun toConnection() = Connection(
        from = ConnectionEndpoint(type = "screen", subgraph = fromSubgraph, screen_id = fromId),
        to = if (toType == "screen")
            ConnectionEndpoint(type = "screen", subgraph = toSubgraph, screen_id = toId)
        else
            ConnectionEndpoint(type = "subgraph", subgraph = toSubgraph, screen_id = null),
        trigger = trigger,
    )
}

object GraphAggregator {

    fun aggregate(fragments: List<GraphFragment>): AggregationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val pool = buildScreenPool(fragments, errors)
        val globalConnections = resolveFunctionTransitions(fragments, pool, errors, warnings)
        val graphDefs = collectGraphDefs(fragments)

        val named = graphDefs.map { (name, def) ->
            val graphConns = resolveGraphTransitions(def.transitions, pool, errors, warnings)
            val graph = assembleAppGraph(pool, globalConnections + graphConns, def.entrySubgraph)
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

    private fun resolveGlobalById(
        id: String,
        byId: Map<String, List<PooledScreen>>,
        field: String,
        source: String,
        errors: MutableList<String>,
    ): PooledScreen? {
        val matches = byId[id].orEmpty()
        return when {
            matches.isEmpty() -> { errors += "$field='$id' not found in any subgraph (from $source)"; null }
            matches.size > 1 -> {
                errors += "$field='$id' is ambiguous across subgraphs ${matches.map { it.subgraph }} (from $source)"
                null
            }
            else -> matches.first()
        }
    }

    private fun resolveFunctionTransitions(
        fragments: List<GraphFragment>,
        pool: Map<Pair<String, String>, PooledScreen>,
        errors: MutableList<String>,
        warnings: MutableList<String>,
    ): List<ResolvedConn> {
        val byFqn = pool.values.filter { it.composableFqn.isNotBlank() }.groupBy { it.composableFqn }
        val byId = pool.values.groupBy { it.id }
        val conns = mutableListOf<ResolvedConn>()

        fragments.flatMap { it.transitions }.filter { it.source_fqn != null }.forEach { t ->
            val source = t.source_fqn!!
            val sources = byFqn[source].orEmpty()
                .let { s -> if (t.from_subgraph.isNotBlank()) s.filter { it.subgraph == t.from_subgraph } else s }
            if (sources.isEmpty()) {
                warnings += "Transition source '$source' has no matching @AppFlowScreen — skipping"
                return@forEach
            }
            sources.forEach { src ->
                when {
                    t.to_screen.isNotBlank() -> {
                        val target = if (t.to_subgraph.isNotBlank()) {
                            pool[t.to_subgraph to t.to_screen].also {
                                if (it == null) warnings += "toScreen '${t.to_subgraph}:${t.to_screen}' not found (from $source)"
                            }
                        } else {
                            resolveGlobalById(t.to_screen, byId, "toScreen", source, errors)
                        }
                        if (target != null) {
                            conns += ResolvedConn(src.subgraph, src.id, "screen", target.subgraph, target.id, t.trigger)
                        }
                    }
                    t.to_subgraph.isNotBlank() -> {
                        conns += ResolvedConn(src.subgraph, src.id, "subgraph", t.to_subgraph, null, t.trigger)
                    }
                }
                if (t.from_screen.isNotBlank()) {
                    val from = pool[src.subgraph to t.from_screen]
                    if (from == null) errors += "fromScreen '${src.subgraph}:${t.from_screen}' not found (on $source)"
                    else conns += ResolvedConn(src.subgraph, t.from_screen, "screen", src.subgraph, src.id, t.trigger)
                }
            }
        }
        return conns.distinct()
    }

    private fun resolveGraphTransitions(
        transitions: List<com.keymusicman.appflower.model.TransitionDef>,
        pool: Map<Pair<String, String>, PooledScreen>,
        errors: MutableList<String>,
        warnings: MutableList<String>,
    ): List<ResolvedConn> {
        val byId = pool.values.groupBy { it.id }
        val conns = mutableListOf<ResolvedConn>()
        transitions.forEach { t ->
            if (t.from_screen.isBlank()) {
                errors += "Graph-level @AppFlowTransition requires fromScreen"
                return@forEach
            }
            val from = resolveGlobalById(t.from_screen, byId, "fromScreen", "graph", errors) ?: return@forEach
            when {
                t.to_screen.isNotBlank() -> {
                    val to = if (t.to_subgraph.isNotBlank()) {
                        pool[t.to_subgraph to t.to_screen].also {
                            if (it == null) warnings += "graph toScreen '${t.to_subgraph}:${t.to_screen}' not found"
                        }
                    } else {
                        resolveGlobalById(t.to_screen, byId, "toScreen", "graph", errors)
                    }
                    if (to != null) conns += ResolvedConn(from.subgraph, from.id, "screen", to.subgraph, to.id, t.trigger)
                }
                t.to_subgraph.isNotBlank() ->
                    conns += ResolvedConn(from.subgraph, from.id, "subgraph", t.to_subgraph, null, t.trigger)
                else -> errors += "Graph-level @AppFlowTransition has neither toScreen nor toSubgraph"
            }
        }
        return conns.distinct()
    }

    private fun assembleAppGraph(
        pool: Map<Pair<String, String>, PooledScreen>,
        connections: List<ResolvedConn>,
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
                connections = connections.filter { it.fromSubgraph == key }.map { it.toConnection() },
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
