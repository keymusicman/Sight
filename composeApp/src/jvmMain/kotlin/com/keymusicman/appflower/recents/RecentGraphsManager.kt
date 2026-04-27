package com.keymusicman.appflower.recents

import co.touchlab.kermit.Logger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object RecentGraphsManager {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private const val MAX_RECENTS = 10

    private val recentsFile: File by lazy {
        val configDir = File(System.getProperty("user.home"), ".config/appflower")
        configDir.mkdirs()
        File(configDir, "recents.json")
    }

    fun getAll(): List<RecentGraph> {
        if (!recentsFile.exists()) return emptyList()
        return try {
            json.decodeFromString<List<RecentGraph>>(recentsFile.readText())
        } catch (e: Exception) {
            Logger.w { "Failed to read recents: ${e.message}" }
            emptyList()
        }
    }

    fun getLast(): RecentGraph? = getAll().firstOrNull()

    fun add(file: File) {
        val displayName = deriveDisplayName(file)
        val entry = RecentGraph(
            path = file.absolutePath,
            displayName = displayName,
            lastOpened = System.currentTimeMillis()
        )
        val updated = (listOf(entry) + getAll().filter { it.path != file.absolutePath })
            .take(MAX_RECENTS)
        save(updated)
    }

    private fun save(recents: List<RecentGraph>) {
        try {
            recentsFile.writeText(json.encodeToString(recents))
        } catch (e: Exception) {
            Logger.w { "Failed to save recents: ${e.message}" }
        }
    }
}

/** Derive a human-readable project name from an app-graph.json file path.
 *  Assumes path like {root}/app/build/graph/app-graph.json → returns root dir name. */
fun deriveDisplayName(file: File): String {
    val root = file.parentFile?.parentFile?.parentFile?.parentFile
    return root?.name ?: file.parentFile?.name ?: file.nameWithoutExtension
}

/** Derive the project root path from an app-graph.json file.
 *  Screenshots are resolved as File(projectRoot, screenshotLocation). */
fun deriveProjectPath(file: File): String {
    return file.parentFile?.parentFile?.parentFile?.parentFile?.parentFile?.absolutePath
        ?: file.parent
}
