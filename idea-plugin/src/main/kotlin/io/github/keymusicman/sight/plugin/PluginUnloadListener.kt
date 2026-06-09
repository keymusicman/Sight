package io.github.keymusicman.sight.plugin

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor

class PluginUnloadListener : DynamicPluginListener {
    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        handleUnload(pluginDescriptor.pluginId.idString)
    }

    internal fun handleUnload(pluginId: String) {
        if (pluginId == "io.github.keymusicman.sight.SightPlugin") {
            runCatching { TelemetryService.getInstance()?.dispose() }
            ComposableRenderer.clearCaches()
            SubprocessRenderer.shutdownAll()
        }
    }
}
