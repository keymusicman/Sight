package com.keymusicman.sight.plugin

import java.io.File
import java.security.MessageDigest

/**
 * Detects when a module's compiled output has changed on disk, so [SubprocessRenderer] can
 * recycle its pooled worker JVM and pick up new bytecode. Reads the filesystem directly, so it
 * works whether the user built in the IDE or from the terminal (`./gradlew`).
 *
 * Two tiers keep the per-render cost negligible while avoiding wasted worker respawns:
 *
 *  - [cheapStamp] — stat-only, runs on every render. Moves whenever any `.class` mtime or any
 *    jar's size/mtime changes.
 *  - [contentHash] — runs only when [cheapStamp] moved. Hashes directory `.class` **bytes**, so
 *    a recompile producing identical bytecode (mtime bumped, content unchanged) does NOT count
 *    as a change. Jars are folded by `mtime:length` metadata rather than hashing their (often
 *    hundreds of MB of) contents.
 *
 * Both functions ignore classpath entries that don't exist; both are independent of filesystem
 * iteration order ([cheapStamp] via a commutative fold, [contentHash] via sorting by path).
 */
object ClasspathFingerprint {

    /** Cheap stat-only stamp over [entries] (directories walked for `.class` files; jars by file). */
    fun cheapStamp(entries: List<String>): Long {
        var acc = 0L
        forEachContributor(entries, { classFile -> acc += classFile.lastModified() * 31 + classFile.length() }) { jar ->
            acc += jar.lastModified() * 31 + jar.length()
        }
        return acc
    }

    /** Content hash: directory `.class` bytes + jar `mtime:length` metadata, in stable path order. */
    fun contentHash(entries: List<String>): String {
        data class Item(val file: File, val isClassFile: Boolean)
        val items = ArrayList<Item>()
        forEachContributor(entries, { classFile -> items.add(Item(classFile, true)) }) { jar ->
            items.add(Item(jar, false))
        }
        items.sortBy { it.file.absolutePath }

        val digest = MessageDigest.getInstance("SHA-256")
        for ((file, isClassFile) in items) {
            digest.update(file.absolutePath.toByteArray())
            if (isClassFile) {
                digest.update(file.readBytes())
            } else {
                digest.update("${file.lastModified()}:${file.length()}".toByteArray())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Visits each contributing path: directory entries are walked for `.class` files (passed to
     * [onClassFile]); any other existing file is passed to [onFileEntry] (jars). Missing paths
     * are skipped.
     */
    private inline fun forEachContributor(
        entries: List<String>,
        onClassFile: (File) -> Unit,
        onFileEntry: (File) -> Unit,
    ) {
        for (path in entries) {
            val f = File(path)
            when {
                f.isDirectory -> f.walkTopDown().forEach { c ->
                    if (c.isFile && c.name.endsWith(".class")) onClassFile(c)
                }
                f.isFile -> onFileEntry(f)
            }
        }
    }
}
