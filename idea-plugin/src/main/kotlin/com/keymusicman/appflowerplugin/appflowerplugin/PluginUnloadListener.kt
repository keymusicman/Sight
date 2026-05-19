package com.keymusicman.appflowerplugin.appflowerplugin

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor

class PluginUnloadListener : DynamicPluginListener {
    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        handleUnload(pluginDescriptor.pluginId.idString)
    }

    internal fun handleUnload(pluginId: String) {
        if (pluginId == "com.keymusicman.appflowerplugin.AppFlowerPlugin") {
            runCatching { TelemetryService.getInstance()?.dispose() }
            ComposableRenderer.clearCaches()
            SubprocessRenderer.shutdownAll()
        }
    }
}
