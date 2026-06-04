package com.keymusicman.appflowerplugin.ipc

import kotlinx.serialization.Serializable

@Serializable
data class WorkerInit(
    val androidStudioRoot: String,
    val userClasspath: List<String>,
    val targetApiLevel: Int,
    val userResDirs: List<String> = emptyList(),
    val rJarPaths: List<String> = emptyList(),
)

@Serializable
data class RenderRequest(
    val requestId: String,
    val composableFqn: String,
    val parameterProviderFqn: String? = null,
    val stateIndex: Int = -1,
    val outputPath: String,
    val outputFormat: String,
    val jpegQuality: Int = 85,
    // Device size in PIXELS + density (dpi). The plugin resolves these from the selected device
    // (e.g. pixel_5 → 1080×2340 @ 440dpi); the worker renders the canvas at exactly these pixels.
    val widthPx: Int,
    val heightPx: Int,
    val density: Int,
    val nightMode: Boolean,
    val fontScale: Float,
    val locale: String,
    val showSystemUi: Boolean,
)

@Serializable
data class RenderResponse(
    val requestId: String,
    val outcome: Outcome,
    val outputPath: String? = null,
    val durationMs: Long,
    val errorClass: String? = null,
    val errorMessage: String? = null,
    val providerExhausted: Boolean = false,
)

@Serializable
enum class Outcome { SUCCESS, FAIL }

@Serializable
data class ShutdownRequest(val reason: String = "")
