package io.github.keymusicman.sight.aggregator

import io.github.keymusicman.sight.model.GraphFragment
import kotlinx.serialization.json.Json

object FragmentLoader {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun load(filename: String): GraphFragment {
        val resource = this::class.java.getResource("/fragments/$filename")
            ?: throw IllegalArgumentException("Fragment not found: /fragments/$filename")
        return json.decodeFromString(resource.readText())
    }
}
