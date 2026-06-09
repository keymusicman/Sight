package io.github.keymusicman.sight.plugin

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewCacheTest {

    private val tmpDir: File = createTempDirectory().toFile()

    @Test
    fun `isValid returns false when sentinel is absent`() {
        assertFalse(PreviewCache.isValid(tmpDir.absolutePath, PreviewRenderConfig()))
    }

    @Test
    fun `isValid returns false when sentinel content differs from config`() {
        PreviewCache.writeSentinel(tmpDir.absolutePath, PreviewRenderConfig(useCustomConfig = true))
        assertFalse(PreviewCache.isValid(tmpDir.absolutePath, PreviewRenderConfig()))
    }

    @Test
    fun `isValid returns true when sentinel content matches config`() {
        val config = PreviewRenderConfig()
        PreviewCache.writeSentinel(tmpDir.absolutePath, config)
        assertTrue(PreviewCache.isValid(tmpDir.absolutePath, config))
    }

    @Test
    fun `writeSentinel writes config toString to sentinel file`() {
        val config = PreviewRenderConfig(fontScale = 1.5f)
        PreviewCache.writeSentinel(tmpDir.absolutePath, config)
        assertEquals(config.toString(), PreviewCache.sentinelFile(tmpDir.absolutePath).readText())
    }

    @Test
    fun `expectedFile returns correct path for no-provider screen`() {
        val file = PreviewCache.expectedFile(tmpDir.absolutePath, "com.example.HomeScreen")
        assertEquals("com.example.HomeScreen.png", file.name)
    }

    @Test
    fun `expectedFile returns correct path for multi-state screen`() {
        val file = PreviewCache.expectedFile(tmpDir.absolutePath, "com.example.HomeScreen", stateIndex = 1)
        assertEquals("com.example.HomeScreen_1.png", file.name)
    }

    @Test
    fun `expectedFile encodes special characters in composable fqn`() {
        val file = PreviewCache.expectedFile(tmpDir.absolutePath, "com.example.My Screen")
        assertEquals("com.example.My_Screen.png", file.name)
    }

    @Test
    fun `clearAll deletes all files including sentinel`() {
        val config = PreviewRenderConfig()
        PreviewCache.writeSentinel(tmpDir.absolutePath, config)
        PreviewCache.outDir(tmpDir.absolutePath).also { it.mkdirs() }.resolve("HomeScreen.png").writeText("data")
        PreviewCache.clearAll(tmpDir.absolutePath)
        assertFalse(PreviewCache.sentinelFile(tmpDir.absolutePath).exists())
        assertFalse(PreviewCache.outDir(tmpDir.absolutePath).resolve("HomeScreen.png").exists())
    }

    @Test
    fun `clearAll is a no-op when outDir does not exist`() {
        val emptyDir = File(tmpDir, "nonexistent").absolutePath
        PreviewCache.clearAll(emptyDir)   // must not throw
    }

    @Test
    fun `expectedFile with JPEG format returns jpg extension`() {
        val file = PreviewCache.expectedFile(tmpDir.absolutePath, "com.example.HomeScreen", format = OutputFormat.JPEG)
        assertEquals("com.example.HomeScreen.jpg", file.name)
    }

    @Test
    fun `expectedFile with BMP format returns bmp extension`() {
        val file = PreviewCache.expectedFile(tmpDir.absolutePath, "com.example.HomeScreen", format = OutputFormat.BMP)
        assertEquals("com.example.HomeScreen.bmp", file.name)
    }

    @Test
    fun `expectedFile with explicit PNG format returns png extension`() {
        val file = PreviewCache.expectedFile(tmpDir.absolutePath, "com.example.HomeScreen", format = OutputFormat.PNG)
        assertEquals("com.example.HomeScreen.png", file.name)
    }

    @Test
    fun `expectedFile with JPEG format and state index returns correct path`() {
        val file = PreviewCache.expectedFile(tmpDir.absolutePath, "com.example.HomeScreen", stateIndex = 2, format = OutputFormat.JPEG)
        assertEquals("com.example.HomeScreen_2.jpg", file.name)
    }

    @Test
    fun `clearIndexedFiles deletes only indexed state images for the given composable`() {
        val dir = PreviewCache.outDir(tmpDir.absolutePath).also { it.mkdirs() }
        val fqn = "com.example.HomeScreen"
        // Indexed state files for the target composable (should be deleted, any extension).
        dir.resolve("com.example.HomeScreen_0.png").writeText("0")
        dir.resolve("com.example.HomeScreen_1.png").writeText("1")
        dir.resolve("com.example.HomeScreen_7.jpg").writeText("7")
        // Must be left untouched: the no-index single image, a different composable, the sentinel.
        dir.resolve("com.example.HomeScreen.png").writeText("single")
        dir.resolve("com.example.OtherScreen_0.png").writeText("other")
        PreviewCache.writeSentinel(tmpDir.absolutePath, PreviewRenderConfig())

        PreviewCache.clearIndexedFiles(tmpDir.absolutePath, fqn)

        assertFalse(dir.resolve("com.example.HomeScreen_0.png").exists())
        assertFalse(dir.resolve("com.example.HomeScreen_1.png").exists())
        assertFalse(dir.resolve("com.example.HomeScreen_7.jpg").exists())
        assertTrue(dir.resolve("com.example.HomeScreen.png").exists())
        assertTrue(dir.resolve("com.example.OtherScreen_0.png").exists())
        assertTrue(PreviewCache.sentinelFile(tmpDir.absolutePath).exists())
    }

    @Test
    fun `clearIndexedFiles is a no-op when outDir does not exist`() {
        val emptyDir = File(tmpDir, "nonexistent-indexed").absolutePath
        PreviewCache.clearIndexedFiles(emptyDir, "com.example.HomeScreen")   // must not throw
    }

    @Test
    fun `listStateImages returns indexed images sorted by state index`() {
        val dir = PreviewCache.outDir(tmpDir.absolutePath).also { it.mkdirs() }
        // Out of order on disk, double-digit index to catch lexical vs numeric sorting.
        dir.resolve("com.example.HomeScreen_10.png").writeText("10")
        dir.resolve("com.example.HomeScreen_2.png").writeText("2")
        dir.resolve("com.example.HomeScreen_0.png").writeText("0")
        // Noise that must be excluded.
        dir.resolve("com.example.HomeScreen.png").writeText("single")
        dir.resolve("com.example.OtherScreen_0.png").writeText("other")

        val names = PreviewCache.listStateImages(tmpDir.absolutePath, "com.example.HomeScreen").map { File(it).name }
        assertEquals(
            listOf("com.example.HomeScreen_0.png", "com.example.HomeScreen_2.png", "com.example.HomeScreen_10.png"),
            names,
        )
    }

    @Test
    fun `listStateImages falls back to single no-index image`() {
        val dir = PreviewCache.outDir(tmpDir.absolutePath).also { it.mkdirs() }
        dir.resolve("com.example.HomeScreen.png").writeText("single")
        val names = PreviewCache.listStateImages(tmpDir.absolutePath, "com.example.HomeScreen").map { File(it).name }
        assertEquals(listOf("com.example.HomeScreen.png"), names)
    }

    @Test
    fun `listStateImages returns empty when outDir does not exist`() {
        val emptyDir = File(tmpDir, "nonexistent-list").absolutePath
        assertTrue(PreviewCache.listStateImages(emptyDir, "com.example.HomeScreen").isEmpty())
    }
}
