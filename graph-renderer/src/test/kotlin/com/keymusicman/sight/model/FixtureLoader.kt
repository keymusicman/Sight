package com.keymusicman.sight.model

import kotlinx.serialization.json.Json

/**
 * Utility for loading and parsing AppGraph fixtures from test resources.
 */
object FixtureLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun loadFixture(filename: String): AppGraph {
        val resourcePath = "/fixtures/$filename"
        val resource = this::class.java.getResource(resourcePath)
            ?: throw IllegalArgumentException("Fixture not found: $resourcePath")
        val content = resource.readText()
        return json.decodeFromString<AppGraph>(content)
    }
}
