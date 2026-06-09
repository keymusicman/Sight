package io.github.keymusicman.sight.plugin

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@State(
    name = "SightPreviewConfig",
    storages = [Storage("appflower-preview-config.xml")]
)
class PreviewConfigService : PersistentStateComponent<PreviewConfigService.State> {

    class State {
        var useCustomConfig: Boolean = false
        var deviceId: String = "pixel_5"
        var customWidthDp: Int = 360
        var customHeightDp: Int = 640
        var uiMode: PreviewUiMode = PreviewUiMode.LIGHT
        var fontScale: Float = 1.0f
        var locale: String = ""
        var showSystemUi: Boolean = false
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) { myState = state }

    val config: PreviewRenderConfig
        get() = PreviewRenderConfig(
            useCustomConfig = myState.useCustomConfig,
            deviceId       = myState.deviceId,
            customWidthDp  = myState.customWidthDp,
            customHeightDp = myState.customHeightDp,
            uiMode         = myState.uiMode,
            fontScale      = myState.fontScale,
            locale         = myState.locale,
            showSystemUi   = myState.showSystemUi,
        )

    fun updateConfig(c: PreviewRenderConfig) {
        myState.useCustomConfig = c.useCustomConfig
        myState.deviceId        = c.deviceId
        myState.customWidthDp   = c.customWidthDp
        myState.customHeightDp  = c.customHeightDp
        myState.uiMode          = c.uiMode
        myState.fontScale       = c.fontScale
        myState.locale          = c.locale
        myState.showSystemUi    = c.showSystemUi
    }

    companion object {
        fun getInstance(project: Project): PreviewConfigService =
            project.getService(PreviewConfigService::class.java)
    }
}
