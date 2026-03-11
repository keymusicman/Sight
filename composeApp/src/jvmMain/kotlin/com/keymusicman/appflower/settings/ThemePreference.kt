package com.keymusicman.appflower.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

enum class ThemePreference { LIGHT, DARK, SYSTEM }

@Serializable
private data class PrefsFile(val theme: String = "SYSTEM")

object ThemePreferenceManager {
    private val prefsFile = File(System.getProperty("user.home"), ".appflower/prefs.json")

    fun load(): ThemePreference {
        return try {
            val text = prefsFile.readText()
            val prefs = Json.decodeFromString<PrefsFile>(text)
            ThemePreference.valueOf(prefs.theme)
        } catch (_: Exception) {
            ThemePreference.SYSTEM
        }
    }

    fun save(pref: ThemePreference) {
        try {
            prefsFile.parentFile?.mkdirs()
            prefsFile.writeText(Json.encodeToString(PrefsFile(pref.name)))
        } catch (_: Exception) { }
    }
}
