package io.github.keymusicman.sight.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "SightPluginSettings",
    storages = [Storage("appflower-plugin-settings.xml")]
)
class PluginSettingsService : PersistentStateComponent<PluginSettingsService.State> {

    class State {
        var outputFormat: OutputFormat = OutputFormat.PNG
        var jpegQuality: Int = 85
        var incrementalRendering: Boolean = false
        var telemetryEnabled: Boolean = true
        var useSubprocessRenderer: Boolean = false
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) { myState = state }

    companion object {
        fun getInstance(): PluginSettingsService =
            ApplicationManager.getApplication().getService(PluginSettingsService::class.java)
    }
}
