package com.keymusicman.appflowerplugin.appflowerplugin

/**
 * Raw screen geometry of an sdklib `Device`, in its natural (portrait) orientation.
 * Read plugin-side from `Device.defaultHardware.screen` (see [ComposableRenderer.deviceScreenDims]).
 */
internal data class ScreenDims(val widthPx: Int, val heightPx: Int, val densityDpi: Int)

/** The pixel size + density the worker needs to build its `HardwareConfig`. */
internal data class DeviceRenderSpec(val widthPx: Int, val heightPx: Int, val densityDpi: Int)

// android.util.DisplayMetrics.DENSITY_DEFAULT (mdpi). Inlined so this file stays free of any
// Studio/SDK dependency and the resolver below is unit-testable without the IntelliJ platform.
private const val MDPI = 160

// Last-resort density when no device can be resolved at all (matches the pixel_5 xxhdpi bucket
// that the subprocess path historically hardcoded).
private const val FALLBACK_DENSITY_DPI = 420

/**
 * Resolves the pixel dimensions + density a render should use, mirroring how
 * [ComposableRenderer] configures its in-process `Configuration`:
 *
 *  - `useCustomConfig == false`            → always the `pixel_5` device (the in-process default,
 *                                            which resets the Configuration to pixel_5 regardless
 *                                            of [PreviewRenderConfig.deviceId]).
 *  - custom device ([CUSTOM_DEVICE_ID])    → `pixel_5` as the density base, sized by
 *                                            `customWidthDp`/`customHeightDp`.
 *  - any other preset                       → that device's own screen geometry.
 *
 * This is the logic the subprocess path was missing: it previously always shipped the custom dp
 * values plus a hardcoded 420 dpi, so e.g. pixel_5 rendered at 945×1680 instead of 1080×2340.
 *
 * [lookupDevice] returns a device's [ScreenDims] (or null if unknown). It is injected so this
 * decision logic is unit-testable without the IntelliJ/SDK device manager.
 */
internal fun resolveDeviceRenderSpec(
    previewConfig: PreviewRenderConfig,
    lookupDevice: (deviceId: String) -> ScreenDims?,
): DeviceRenderSpec {
    fun ScreenDims.toSpec() = DeviceRenderSpec(widthPx, heightPx, densityDpi)
    fun customSpec(densityDpi: Int) = DeviceRenderSpec(
        widthPx = dpToPx(previewConfig.customWidthDp, densityDpi),
        heightPx = dpToPx(previewConfig.customHeightDp, densityDpi),
        densityDpi = densityDpi,
    )

    if (!previewConfig.useCustomConfig) {
        return lookupDevice("pixel_5")?.toSpec() ?: customSpec(FALLBACK_DENSITY_DPI)
    }
    if (previewConfig.deviceId == CUSTOM_DEVICE_ID) {
        val baseDensity = lookupDevice("pixel_5")?.densityDpi ?: FALLBACK_DENSITY_DPI
        return customSpec(baseDensity)
    }
    val device = lookupDevice(previewConfig.deviceId) ?: lookupDevice("pixel_5")
    return device?.toSpec() ?: customSpec(FALLBACK_DENSITY_DPI)
}

private fun dpToPx(dp: Int, densityDpi: Int): Int =
    (dp.toLong() * densityDpi / MDPI).toInt()

/** The non-device render params the worker needs, after `useCustomConfig` gating is applied. */
internal data class EffectivePreviewParams(
    val nightMode: Boolean,
    val fontScale: Float,
    val locale: String,
    val showSystemUi: Boolean,
)

/**
 * Resolves the effective night-mode / font-scale / locale / system-UI params, mirroring how
 * [ComposableRenderer] applies them in-process:
 *
 *  - `useCustomConfig == true`  → use the [PreviewRenderConfig] values verbatim.
 *  - `useCustomConfig == false` → reset to neutral defaults (light, 1.0×, system locale, no decor),
 *                                 exactly as the in-process path does (`config.setFontScale(1.0f)`
 *                                 etc.) so a stale/persisted custom value never leaks into a default
 *                                 render.
 *
 * This is the gating the subprocess path was missing: it shipped `fontScale`, `uiMode` and `locale`
 * unconditionally, so a persisted `fontScale = 2.0` with `useCustomConfig = false` rendered text at
 * 2× in the worker while the in-process path rendered it at 1×.
 */
internal fun resolveEffectivePreviewParams(previewConfig: PreviewRenderConfig): EffectivePreviewParams =
    if (previewConfig.useCustomConfig) {
        EffectivePreviewParams(
            nightMode = previewConfig.uiMode == PreviewUiMode.DARK,
            fontScale = previewConfig.fontScale,
            locale = previewConfig.locale,
            showSystemUi = previewConfig.showSystemUi,
        )
    } else {
        EffectivePreviewParams(
            nightMode = false,
            fontScale = 1.0f,
            locale = "",
            showSystemUi = false,
        )
    }
