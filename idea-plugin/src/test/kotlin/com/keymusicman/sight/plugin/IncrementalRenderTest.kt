package com.keymusicman.sight.plugin

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncrementalRenderTest {

    private val tmpDir: File = createTempDirectory().toFile()

    @Test
    fun `returns false when incremental rendering is disabled`() {
        val outFile = tmpDir.resolve("screen.png").also { it.writeText("img") }
        assertFalse(shouldSkipIncrementalRender(outFile, sourceFilePath = null, incrementalRendering = false))
    }

    @Test
    fun `returns false when output file does not exist`() {
        val outFile = tmpDir.resolve("screen_missing.png")
        assertFalse(shouldSkipIncrementalRender(outFile, sourceFilePath = null, incrementalRendering = true))
    }

    @Test
    fun `returns true when incremental is enabled, outFile exists, and sourceFilePath is null`() {
        val outFile = tmpDir.resolve("screen.png").also { it.writeText("img") }
        assertTrue(shouldSkipIncrementalRender(outFile, sourceFilePath = null, incrementalRendering = true))
    }

    @Test
    fun `returns true when source is older than output file`() {
        val sourceFile = tmpDir.resolve("Screen.kt").also { it.writeText("src") }
        val outFile = tmpDir.resolve("screen.png").also { it.writeText("img") }
        sourceFile.setLastModified(outFile.lastModified() - 5_000)
        assertTrue(shouldSkipIncrementalRender(outFile, sourceFilePath = sourceFile.absolutePath, incrementalRendering = true))
    }

    @Test
    fun `returns false when source is newer than output file`() {
        val sourceFile = tmpDir.resolve("Screen2.kt").also { it.writeText("src") }
        val outFile = tmpDir.resolve("screen2.png").also { it.writeText("img") }
        sourceFile.setLastModified(outFile.lastModified() + 5_000)
        assertFalse(shouldSkipIncrementalRender(outFile, sourceFilePath = sourceFile.absolutePath, incrementalRendering = true))
    }
}
