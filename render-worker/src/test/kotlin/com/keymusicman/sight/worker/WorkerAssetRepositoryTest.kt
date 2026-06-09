package com.keymusicman.sight.worker

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkerAssetRepositoryTest {
    private fun jarWith(entry: String, bytes: ByteArray): File {
        val f = File(createTempDirectory().toFile(), "framework_res.jar")
        JarOutputStream(f.outputStream()).use { jos ->
            jos.putNextEntry(JarEntry(entry))
            jos.write(bytes)
            jos.closeEntry()
        }
        return f
    }

    @Test
    fun `serves a framework jar entry`() {
        val repo = WorkerAssetRepository(jarWith("res/anim/foo.xml", byteArrayOf(7, 8, 9)))
        val bytes = repo.openNonAsset(0, "res/anim/foo.xml", 0)!!.use { it.readBytes() }
        assertEquals(listOf<Byte>(7, 8, 9), bytes.toList())
    }

    @Test
    fun `serves an absolute on-disk user resource file`() {
        val repo = WorkerAssetRepository(jarWith("res/x", byteArrayOf()))
        val userFile = File(createTempDirectory().toFile(), "img.webp").apply { writeBytes(byteArrayOf(1, 2)) }
        val bytes = repo.openNonAsset(0, userFile.absolutePath, 0)!!.use { it.readBytes() }
        assertEquals(listOf<Byte>(1, 2), bytes.toList())
    }

    @Test
    fun `returns null for unknown path`() {
        val repo = WorkerAssetRepository(jarWith("res/x", byteArrayOf()))
        assertNull(repo.openNonAsset(0, "res/nope", 0))
        assertNull(repo.openNonAsset(0, null, 0))
    }
}
