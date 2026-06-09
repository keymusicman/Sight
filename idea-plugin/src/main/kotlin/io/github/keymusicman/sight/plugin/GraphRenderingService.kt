package io.github.keymusicman.sight.plugin

import io.github.keymusicman.sight.model.AppGraph

/**
 * Renders previews for an aggregated [AppGraph]. Each screen is rendered against ITS OWN module
 * (screen.module_path), so a single graph can span modules. The render call is injected so this
 * is unit-testable without a live renderer.
 */
object GraphRenderingService {

    /** (modulePath, composableFqn, providerFqn, stateIndex) -> output path or null on failure. */
    fun interface RenderCall {
        fun render(modulePath: String, composableFqn: String, providerFqn: String?, stateIndex: Int): String?
    }

    /** Renders each screen's currently-selected state (or its single image when no provider). */
    fun renderSelectedStates(appGraph: AppGraph, defaultModulePath: String, render: RenderCall) {
        appGraph.subgraphs.values.forEach { subgraph ->
            subgraph.screens.forEach { screen ->
                val fqn = screen.composable_fqn
                if (fqn.isBlank()) return@forEach
                val modulePath = screen.module_path.ifBlank { defaultModulePath }
                val provider = screen.preview_provider_fqn
                val stateIndex = if (provider != null) screen.selected_state.coerceAtLeast(0) else -1
                render.render(modulePath, fqn, provider, stateIndex)
            }
        }
    }
}
