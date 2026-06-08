package com.keymusicman.graph.processor

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

class GraphSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return GraphSymbolProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
            projectRoot = environment.options["projectRoot"] ?: "",
            moduleName = environment.options["moduleName"] ?: "",
            outputFileName = environment.options["outputFileName"] ?: "app-graph-fragment.json",
        )
    }
}
