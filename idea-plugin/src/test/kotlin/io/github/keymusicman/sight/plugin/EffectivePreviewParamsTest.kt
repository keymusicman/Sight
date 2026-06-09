package io.github.keymusicman.sight.plugin

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the `useCustomConfig` gating that the subprocess path must apply to the non-device render
 * params (fontScale / night mode / locale / system UI), mirroring [ComposableRenderer]'s in-process
 * `Configuration` reset.
 *
 * Regression guard for the bug where the subprocess worker shipped `fontScale`, `uiMode` and
 * `locale` UNCONDITIONALLY while the in-process path reset them to neutral defaults when
 * `useCustomConfig == false`. With a persisted `fontScale = 2.0` and `useCustomConfig = false`, the
 * worker rendered text at 2× while Android Studio (in-process) rendered it at 1×.
 */
class EffectivePreviewParamsTest {

    @Test
    fun `useCustomConfig false neutralizes fontScale, night mode, locale and system UI`() {
        // The exact persisted state that triggered the doubled-font bug.
        val cfg = PreviewRenderConfig(
            useCustomConfig = false,
            deviceId = "pixel_fold",
            uiMode = PreviewUiMode.DARK,
            fontScale = 2.0f,
            locale = "fr",
            showSystemUi = true,
        )
        val params = resolveEffectivePreviewParams(cfg)
        assertEquals(
            EffectivePreviewParams(nightMode = false, fontScale = 1.0f, locale = "", showSystemUi = false),
            params,
        )
    }

    @Test
    fun `useCustomConfig true passes the custom params through verbatim`() {
        val cfg = PreviewRenderConfig(
            useCustomConfig = true,
            uiMode = PreviewUiMode.DARK,
            fontScale = 1.5f,
            locale = "de",
            showSystemUi = true,
        )
        val params = resolveEffectivePreviewParams(cfg)
        assertEquals(
            EffectivePreviewParams(nightMode = true, fontScale = 1.5f, locale = "de", showSystemUi = true),
            params,
        )
    }

    @Test
    fun `useCustomConfig true with light mode maps to nightMode false`() {
        val cfg = PreviewRenderConfig(useCustomConfig = true, uiMode = PreviewUiMode.LIGHT, fontScale = 1.0f)
        val params = resolveEffectivePreviewParams(cfg)
        assertEquals(
            EffectivePreviewParams(nightMode = false, fontScale = 1.0f, locale = "", showSystemUi = false),
            params,
        )
    }
}
