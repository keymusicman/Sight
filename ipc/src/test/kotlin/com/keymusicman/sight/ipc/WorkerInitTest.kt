package com.keymusicman.sight.ipc

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkerInitTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `round-trips userResDirs and rJarPaths`() {
        val init = WorkerInit(
            androidStudioRoot = "/AS",
            userClasspath = listOf("/a.jar"),
            targetApiLevel = 36,
            userResDirs = listOf("/m/src/main/res", "/dep/res"),
            rJarPaths = listOf("/m/R.jar"),
        )
        val decoded = json.decodeFromString(
            WorkerInit.serializer(),
            json.encodeToString(WorkerInit.serializer(), init),
        )
        assertEquals(init, decoded)
    }

    @Test
    fun `old payload without new fields decodes with empty defaults`() {
        val legacy = """{"androidStudioRoot":"/AS","userClasspath":[],"targetApiLevel":36}"""
        val decoded = json.decodeFromString(WorkerInit.serializer(), legacy)
        assertEquals(emptyList(), decoded.userResDirs)
        assertEquals(emptyList(), decoded.rJarPaths)
    }
}
