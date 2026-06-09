package com.keymusicman.sight.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import java.io.File

class GraphSymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val projectRoot: String,
    private val moduleName: String = "",
    private val outputFileName: String = "app-graph-fragment.json",
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // Collect all files first — used to register an aggregating KSP output below.
        // Without this, KSP treats the processor as non-aggregating and only passes
        // symbols from dirty (changed) files in incremental builds, causing screens and
        // transitions from unchanged files to silently vanish from the fragment.
        val allFiles = resolver.getAllFiles().toList()

        val screens = collectScreens(resolver)
        val functionTransitions = collectFunctionTransitions(resolver)
        val graphs = collectGraphDefs(resolver)

        if (screens.isEmpty() && graphs.isEmpty() && functionTransitions.isEmpty()) return emptyList()

        val json = buildFragmentJson(
            module = moduleName,
            screens = screens,
            transitions = functionTransitions,
            graphs = graphs,
        )
        // Register as an aggregating output so KSP provides all symbols (not just
        // dirty-file symbols) on every subsequent incremental build.
        if (allFiles.isNotEmpty()) {
            codeGenerator.createNewFile(
                dependencies = Dependencies(aggregating = true, *allFiles.toTypedArray()),
                packageName = "com.keymusicman.sight.ksp",
                fileName = "GraphExportMarker",
                extensionName = "txt",
            ).close()
        }
        writeFragment(json)
        return emptyList()
    }

    /** All @SightScreen annotations (repeatable) on every annotated function. */
    private fun collectScreens(resolver: Resolver): List<ScreenEntry> {
        val out = mutableListOf<ScreenEntry>()
        resolver.getSymbolsWithAnnotation("com.keymusicman.sight.SightScreen")
            .filterIsInstance<KSFunctionDeclaration>()
            .forEach { fn ->
                val fqn = fn.qualifiedName?.asString() ?: return@forEach
                val location = fn.containingFile?.filePath?.removePrefix(projectRoot)?.trimStart('/') ?: ""
                val providerFqn = fn.parameters
                    .flatMap { it.annotations }
                    .firstOrNull { it.shortName.asString() == "PreviewParameter" }
                    ?.arguments?.find { it.name?.asString() == "provider" }
                    ?.let { (it.value as? KSType)?.declaration?.qualifiedName?.asString() }
                fn.annotations.filter { it.shortName.asString() == "SightScreen" }.forEach { ann ->
                    val subgraph = ann.arguments.find { it.name?.asString() == "subgraph" }?.value as? String ?: ""
                    val rawId = ann.arguments.find { it.name?.asString() == "id" }?.value as? String ?: ""
                    val id = rawId.ifEmpty { fn.simpleName.asString() }
                    val isRoot = ann.arguments.find { it.name?.asString() == "isRoot" }?.value as? Boolean ?: false
                    out += ScreenEntry(subgraph, id, isRoot, fqn, location, providerFqn)
                }
            }
        return out
    }

    /** Function-level @SightTransition (source_fqn = the function). */
    private fun collectFunctionTransitions(resolver: Resolver): List<TransitionEntry> {
        val out = mutableListOf<TransitionEntry>()
        resolver.getSymbolsWithAnnotation("com.keymusicman.sight.SightTransition")
            .filterIsInstance<KSFunctionDeclaration>()
            .forEach { fn ->
                val fqn = fn.qualifiedName?.asString() ?: return@forEach
                fn.annotations.filter { it.shortName.asString() == "SightTransition" }.forEach { ann ->
                    out += ann.toTransitionEntry(sourceFqn = fqn)
                }
            }
        return out
    }

    /** Each @SightGraph object + its class-level @SightTransition list. */
    private fun collectGraphDefs(resolver: Resolver): List<GraphDefEntry> {
        return resolver.getSymbolsWithAnnotation("com.keymusicman.sight.SightGraph")
            .filterIsInstance<KSClassDeclaration>()
            .map { cls ->
                val graphAnn = cls.annotations.first { it.shortName.asString() == "SightGraph" }
                val name = graphAnn.arguments.find { it.name?.asString() == "name" }?.value as? String ?: ""
                val entry = graphAnn.arguments.find { it.name?.asString() == "entrySubgraph" }?.value as? String ?: ""
                val drop = graphAnn.arguments.find { it.name?.asString() == "dropUnconnected" }?.value as? Boolean ?: true
                val classTransitions = cls.annotations
                    .filter { it.shortName.asString() == "SightTransition" }
                    .map { it.toTransitionEntry(sourceFqn = null) }
                    .toList()
                GraphDefEntry(name, entry, drop, classTransitions)
            }
            .toList()
    }

    private fun KSAnnotation.toTransitionEntry(sourceFqn: String?): TransitionEntry = TransitionEntry(
        sourceFqn = sourceFqn,
        fromSubgraph = arguments.find { it.name?.asString() == "fromSubgraph" }?.value as? String ?: "",
        fromScreen = arguments.find { it.name?.asString() == "fromScreen" }?.value as? String ?: "",
        toScreen = arguments.find { it.name?.asString() == "toScreen" }?.value as? String ?: "",
        toSubgraph = arguments.find { it.name?.asString() == "toSubgraph" }?.value as? String ?: "",
        trigger = arguments.find { it.name?.asString() == "trigger" }?.value as? String ?: "",
    )

    private fun writeFragment(json: String) {
        if (projectRoot.isBlank()) {
            logger.error("GraphSymbolProcessor: 'projectRoot' KSP option missing — cannot write fragment")
            return
        }
        try {
            val outDir = File(projectRoot, "build/graph").also { it.mkdirs() }
            File(outDir, outputFileName).writeText(json)
            logger.info("GraphSymbolProcessor: wrote ${outDir.absolutePath}/$outputFileName")
        } catch (e: Exception) {
            logger.error("GraphSymbolProcessor: failed to write fragment: ${e.message}")
        }
    }
}
