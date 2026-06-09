package com.keymusicman.sight.worker

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the `res/` prefixing that makes user fonts load through androidx `ResourcesCompat`.
 *
 * Regression guard for the bug where the worker resolved user font resources to absolute on-disk
 * paths; `ResourcesCompat.loadFont` requires a value starting with `"res/"`, so every Compose
 * `Font(R.font.x)` silently fell back to Roboto instead of the app's Inter font.
 */
class FontResourceValuesTest {

    @Test
    fun `absolute ttf path is prefixed with res so ResourcesCompat accepts it`() {
        val v = "/Users/dev/app/core/uikit/src/main/res/font/inter.ttf"
        assertEquals("res$v", fontValueForResourcesCompat(v))
    }

    @Test
    fun `otf and ttc font files are also prefixed`() {
        assertEquals("res/a/b/x.otf", fontValueForResourcesCompat("/a/b/x.otf"))
        assertEquals("res/a/b/x.ttc", fontValueForResourcesCompat("/a/b/x.ttc"))
    }

    @Test
    fun `the rewritten value starts with the res slash gate ResourcesCompat checks`() {
        assertEquals(true, fontValueForResourcesCompat("/a/inter.ttf").startsWith("res/"))
    }

    @Test
    fun `xml font-family values are left untouched`() {
        val v = "/Users/dev/.gradle/caches/.../res/font/hc_font_family.xml"
        assertEquals(v, fontValueForResourcesCompat(v))
    }

    @Test
    fun `already-prefixed or non-file values are left untouched`() {
        assertEquals("res/font/inter.ttf", fontValueForResourcesCompat("res/font/inter.ttf"))
        assertEquals("#ff0000", fontValueForResourcesCompat("#ff0000"))
        assertEquals("", fontValueForResourcesCompat(""))
    }
}
