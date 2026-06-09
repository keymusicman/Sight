package com.keymusicman.sight.processor

internal data class ScreenEntry(
    val subgraph: String,
    val id: String,
    val isRoot: Boolean,
    val fqn: String,
    val location: String,
    val previewProviderFqn: String?,
)

internal data class TransitionEntry(
    val sourceFqn: String?,   // null for class-level (@SightGraph object) transitions
    val fromSubgraph: String = "",
    val fromScreen: String = "",
    val toScreen: String = "",
    val toSubgraph: String = "",
    val trigger: String = "",
)

internal data class GraphDefEntry(
    val name: String,
    val entrySubgraph: String,
    val dropUnconnected: Boolean,
    val transitions: List<TransitionEntry>,
)

private fun String.esc(): String = replace("\\", "\\\\").replace("\"", "\\\"")

private fun transitionJson(t: TransitionEntry): String = buildString {
    append("{")
    val parts = mutableListOf<String>()
    t.sourceFqn?.let { parts += "\"source_fqn\":\"${it.esc()}\"" }
    parts += "\"from_subgraph\":\"${t.fromSubgraph.esc()}\""
    parts += "\"from_screen\":\"${t.fromScreen.esc()}\""
    parts += "\"to_screen\":\"${t.toScreen.esc()}\""
    parts += "\"to_subgraph\":\"${t.toSubgraph.esc()}\""
    parts += "\"trigger\":\"${t.trigger.esc()}\""
    append(parts.joinToString(","))
    append("}")
}

internal fun buildFragmentJson(
    module: String,
    screens: List<ScreenEntry>,
    transitions: List<TransitionEntry>,
    graphs: List<GraphDefEntry>,
): String = buildString {
    append("{")
    append("\"module\":\"${module.esc()}\",")
    append("\"graphs\":[")
    append(graphs.joinToString(",") { g ->
        "{\"name\":\"${g.name.esc()}\"," +
            "\"entry_subgraph\":\"${g.entrySubgraph.esc()}\"," +
            "\"drop_unconnected\":${g.dropUnconnected}," +
            "\"transitions\":[${g.transitions.joinToString(",") { transitionJson(it) }}]}"
    })
    append("],")
    append("\"screens\":[")
    append(screens.joinToString(",") { s ->
        val provider = if (s.previewProviderFqn != null) "\"${s.previewProviderFqn.esc()}\"" else "null"
        "{\"subgraph\":\"${s.subgraph.esc()}\"," +
            "\"id\":\"${s.id.esc()}\"," +
            "\"is_root\":${s.isRoot}," +
            "\"composable_fqn\":\"${s.fqn.esc()}\"," +
            "\"location\":\"${s.location.esc()}\"," +
            "\"preview_provider_fqn\":$provider}"
    })
    append("],")
    append("\"transitions\":[")
    append(transitions.joinToString(",") { transitionJson(it) })
    append("]")
    append("}")
}
