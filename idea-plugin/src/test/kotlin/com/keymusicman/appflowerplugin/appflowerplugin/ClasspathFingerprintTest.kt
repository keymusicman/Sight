package com.keymusicman.appflowerplugin.appflowerplugin

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Unit tests for [ClasspathFingerprint] — the two-tier rebuild detector that lets
 * [SubprocessRenderer] recycle its pooled worker when the user's compiled output changes.
 *
 * - [ClasspathFingerprint.cheapStamp] is the per-render gate: stat-only, must move whenever a
 *   `.class` mtime or a jar's size/mtime changes.
 * - [ClasspathFingerprint.contentHash] is the confirmation: it hashes directory `.class`
 *   **bytes**, so an mtime-only rewrite (recompile producing identical bytecode) leaves it
 *   unchanged — that's the property that avoids a wasted worker respawn.
 *
 * mtimes are set far apart (and, where length is the variable under test, pinned equal) so the
 * assertions don't depend on the host filesystem's mtime resolution.
 */
class ClasspathFingerprintTest {

    private val root: File = createTempDirectory().toFile()
    private val classesDir: File = File(root, "build/tmp/kotlin-classes/debug").also { it.mkdirs() }

    private val T1 = 1_600_000_000_000L
    private val T2 = 1_700_000_000_000L

    private fun writeClass(relPath: String, bytes: ByteArray, mtime: Long): File {
        val f = File(classesDir, relPath)
        f.parentFile.mkdirs()
        f.writeBytes(bytes)
        f.setLastModified(mtime)
        return f
    }

    private fun writeJar(name: String, size: Int, mtime: Long): File {
        val f = File(root, name)
        f.writeBytes(ByteArray(size))
        f.setLastModified(mtime)
        return f
    }

    // ---- cheapStamp ----

    @Test
    fun `cheapStamp is stable when nothing changes`() {
        writeClass("pkg/Foo.class", byteArrayOf(1, 2, 3), T1)
        val entries = listOf(classesDir.absolutePath)

        assertEquals(
            ClasspathFingerprint.cheapStamp(entries),
            ClasspathFingerprint.cheapStamp(entries),
        )
    }

    @Test
    fun `cheapStamp changes when a class file is rewritten with a newer mtime`() {
        writeClass("pkg/Foo.class", byteArrayOf(1, 2, 3), T1)
        val entries = listOf(classesDir.absolutePath)
        val before = ClasspathFingerprint.cheapStamp(entries)

        // Recompile: same file path, newer mtime (content irrelevant to the cheap gate).
        writeClass("pkg/Foo.class", byteArrayOf(1, 2, 3), T2)

        assertNotEquals(before, ClasspathFingerprint.cheapStamp(entries))
    }

    @Test
    fun `cheapStamp changes when a jar's length changes`() {
        val jar = writeJar("dep.jar", size = 1_000, mtime = T1)
        val entries = listOf(classesDir.absolutePath, jar.absolutePath)
        val before = ClasspathFingerprint.cheapStamp(entries)

        // Rebuild the jar to a different size, pinning mtime so length is the only variable.
        writeJar("dep.jar", size = 2_000, mtime = T1)

        assertNotEquals(before, ClasspathFingerprint.cheapStamp(entries))
    }

    // ---- contentHash ----

    @Test
    fun `contentHash is stable across an mtime-only rewrite with identical bytes`() {
        writeClass("pkg/Foo.class", byteArrayOf(1, 2, 3), T1)
        val entries = listOf(classesDir.absolutePath)
        val before = ClasspathFingerprint.contentHash(entries)

        // No-op rebuild: identical bytecode, newer mtime. Must NOT count as a change.
        writeClass("pkg/Foo.class", byteArrayOf(1, 2, 3), T2)

        assertEquals(before, ClasspathFingerprint.contentHash(entries))
    }

    @Test
    fun `contentHash changes when class bytes change`() {
        writeClass("pkg/Foo.class", byteArrayOf(1, 2, 3), T1)
        val entries = listOf(classesDir.absolutePath)
        val before = ClasspathFingerprint.contentHash(entries)

        // Real change: different bytecode (mtime pinned to isolate the byte change).
        writeClass("pkg/Foo.class", byteArrayOf(1, 2, 4), T1)

        assertNotEquals(before, ClasspathFingerprint.contentHash(entries))
    }

    @Test
    fun `contentHash changes when a jar's length changes`() {
        val jar = writeJar("dep.jar", size = 1_000, mtime = T1)
        val entries = listOf(classesDir.absolutePath, jar.absolutePath)
        val before = ClasspathFingerprint.contentHash(entries)

        writeJar("dep.jar", size = 2_000, mtime = T1)

        assertNotEquals(before, ClasspathFingerprint.contentHash(entries))
    }
}
