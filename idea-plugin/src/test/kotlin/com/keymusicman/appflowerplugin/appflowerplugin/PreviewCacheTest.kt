package com.keymusicman.appflowerplugin.appflowerplugin

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
}
