package io.github.keymusicman.sight.plugin

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserResourceResolverTest {
    private val tmp: File = createTempDirectory().toFile()

    @Test
    fun `ownModuleResDirs globs src source-set res dirs`() {
        File(tmp, "src/main/res/values").mkdirs()
        File(tmp, "src/debug/res/drawable").mkdirs()
        File(tmp, "src/main/java").mkdirs() // not a res dir
        val dirs = UserResourceResolver.ownModuleResDirs(tmp).map { it.path }
        assertTrue(dirs.any { it.endsWith("src/main/res") }, dirs.toString())
        assertTrue(dirs.any { it.endsWith("src/debug/res") }, dirs.toString())
        assertEquals(2, dirs.size)
    }

    @Test
    fun `ownModuleResDirs includes generated res when present`() {
        File(tmp, "src/main/res").mkdirs()
        File(tmp, "build/generated/res/resValues/debug/res/values").mkdirs()
        val dirs = UserResourceResolver.ownModuleResDirs(tmp).map { it.path }
        assertTrue(dirs.any { it.contains("build/generated/res") && it.endsWith("/res") }, dirs.toString())
    }

    @Test
    fun `aarResDirForJar returns sibling res of a transformed classes_jar`() {
        val classes = File(tmp, "transformed/foo/jars/classes.jar")
        classes.parentFile.mkdirs(); classes.writeBytes(byteArrayOf())
        val res = File(tmp, "transformed/foo/res"); res.mkdirs()
        assertEquals(res, UserResourceResolver.aarResDirForJar(classes.path))
    }

    @Test
    fun `aarResDirForJar returns null for non-classes jar or missing res`() {
        val other = File(tmp, "x/y/some.jar"); other.parentFile.mkdirs(); other.writeBytes(byteArrayOf())
        assertNull(UserResourceResolver.aarResDirForJar(other.path))
        val classesNoRes = File(tmp, "z/jars/classes.jar"); classesNoRes.parentFile.mkdirs(); classesNoRes.writeBytes(byteArrayOf())
        assertNull(UserResourceResolver.aarResDirForJar(classesNoRes.path))
    }
}
