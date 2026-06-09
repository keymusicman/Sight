package com.keymusicman.sight.plugin

import com.intellij.openapi.project.Project

/**
 * Routes a render call to either the in-process [ComposableRenderer] or the
 * subprocess-isolated [SubprocessRenderer] based on
 * [PluginSettingsService.State.useSubprocessRenderer].
 */
object RendererRouter {

    fun render(
        project: Project,
        modulePath: String,
        composableFqn: String,
        parameterProviderFqn: String? = null,
        stateIndex: Int = -1,
        sourceFilePath: String? = null,
        onLog: ((String) -> Unit)? = null,
        useSimpleLayout: Boolean = false,
        previewConfig: PreviewRenderConfig = PreviewRenderConfig(),
    ): String? {
        val useSubprocess = PluginSettingsService.getInstance().getState().useSubprocessRenderer
        return if (useSubprocess) {
            SubprocessRenderer.render(
                project, modulePath, composableFqn,
                parameterProviderFqn = parameterProviderFqn,
                stateIndex = stateIndex,
                sourceFilePath = sourceFilePath,
                onLog = onLog,
                previewConfig = previewConfig,
            )
        } else {
            ComposableRenderer.render(
                project, modulePath, composableFqn,
                parameterProviderFqn = parameterProviderFqn,
                stateIndex = stateIndex,
                sourceFilePath = sourceFilePath,
                onLog = onLog,
                useSimpleLayout = useSimpleLayout,
                previewConfig = previewConfig,
            )
        }
    }
}
