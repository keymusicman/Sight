package com.keymusicman.appflowerplugin.renderworker.worker

/**
 * Compose loads `Font(R.font.x)` via `androidx.core.content.res.ResourcesCompat.getFont`, whose
 * `loadFont` only proceeds when the resolved resource-value string **starts with `"res/"`** —
 * otherwise it returns null and Compose silently falls back to the system font (Roboto).
 *
 * The worker resolves user font resources to ABSOLUTE on-disk paths (e.g.
 * `/…/core/uikit/src/main/res/font/inter.ttf`), which fail that gate, so every custom font fell
 * back. (Drawables are unaffected: `painterResource` reads them straight through
 * `AssetManager.openNonAsset`, which serves absolute paths.)
 *
 * Prefixing an absolute font-file value with `res` yields `res/…` (the absolute path keeps its
 * leading `/`), which passes the gate; Layoutlib then resolves the embedded absolute path to the
 * real file. Only real font files are rewritten — XML font-family resources (`res/font/NAME.xml`)
 * are read through a different path and are left untouched.
 */
internal fun fontValueForResourcesCompat(value: String): String {
    val lower = value.lowercase()
    val isFontFile = lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc")
    return if (isFontFile && value.startsWith("/")) "res$value" else value
}
