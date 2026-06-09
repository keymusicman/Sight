package io.github.keymusicman.sight.plugin

import com.intellij.openapi.diagnostic.Logger
import io.github.keymusicman.sight.aggregator.AggregationResult
import io.github.keymusicman.sight.aggregator.GraphAggregator
import io.github.keymusicman.sight.model.GraphFragment
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Reads each module's `build/graph/app-graph-fragment.json` (the v4 fragment contract)
 * and aggregates them into named graphs via [GraphAggregator].
 */
object FragmentRepository {

    const val FRAGMENT_RELATIVE_PATH = "build/graph/app-graph-fragment.json"

    private val log = Logger.getInstance(FragmentRepository::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Reads + parses the fragment of every module dir that has one. Fills blank module_path with the dir. */
    fun readFragments(moduleDirs: List<String>): List<GraphFragment> =
        moduleDirs.mapNotNull { dir ->
            val file = File(dir, FRAGMENT_RELATIVE_PATH)
            if (!file.exists()) return@mapNotNull null
            runCatching {
                val frag = json.decodeFromString<GraphFragment>(file.readText())
                if (frag.module_path.isBlank()) frag.copy(module_path = dir) else frag
            }.getOrElse { e ->
                log.warn("Failed to parse fragment at ${file.absolutePath}: ${e.message}")
                null
            }
        }

    fun aggregate(moduleDirs: List<String>): AggregationResult =
        GraphAggregator.aggregate(readFragments(moduleDirs))
}
