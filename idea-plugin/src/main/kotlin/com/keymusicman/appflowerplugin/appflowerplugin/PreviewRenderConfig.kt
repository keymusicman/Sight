package com.keymusicman.appflowerplugin.appflowerplugin

const val CUSTOM_DEVICE_ID = "custom"

enum class PreviewUiMode { LIGHT, DARK }

data class PreviewRenderConfig(
    val useCustomConfig: Boolean = false,
    val deviceId: String = "pixel_5",
    val customWidthDp: Int = 360,
    val customHeightDp: Int = 640,
    val uiMode: PreviewUiMode = PreviewUiMode.LIGHT,
    val fontScale: Float = 1.0f,
    val locale: String = "",
    val showSystemUi: Boolean = false,
)

val PRESET_DEVICES: List<Pair<String, String>> = listOf(
    "pixel_5"     to "Pixel 5 (default)",
    "pixel_6"     to "Pixel 6",
    "pixel_7"     to "Pixel 7",
    "pixel_7_pro" to "Pixel 7 Pro",
    "pixel_tablet" to "Pixel Tablet",
    "pixel_fold"  to "Pixel Fold",
    CUSTOM_DEVICE_ID to "Custom…",
)
