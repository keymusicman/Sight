package com.keymusicman.appflowerplugin.appflowerplugin

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [UserModuleClasspathResolver.findGeneratedRJars] — the AGP-generated R.jar
 * discovery that the standalone worker classloader depends on (it can't synthesize R classes
 * on the fly the way Studio's ModuleClassLoader does). Regression guard for the
 * `NoClassDefFoundError: androidx/customview/poolingcontainer/R$id` crash that killed every
 * Compose preview when the resolver hardcoded the (nonexistent for newer AGP) intermediate
 * path `compile_and_runtime_not_namespaced_r_class_jar/...`.
 */
class UserModuleClasspathResolverTest {

    private val tmp: File = createTempDirectory().toFile()

    private fun writeJar(relPath: String, size: Int): File {
        val f = File(tmp, relPath)
        f.parentFile.mkdirs()
        f.writeBytes(ByteArray(size))
        return f
    }

    @Test
    fun `finds transitive R jar under compile_and_runtime_r_class_jar (newer AGP path)`() {
        // The layout produced by Gradle 9.4.1 / recent AGP for example-app (an application module):
        // the fat transitive R.jar lives here, NOT under compile_and_runtime_not_namespaced_r_class_jar.
        val rJar = writeJar(
            "build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar",
            size = 4096,
        )
        val found = UserModuleClasspathResolver.findGeneratedRJars(tmp)
        assertTrue(rJar in found, "expected to discover $rJar, got $found")
    }

    @Test
    fun `returns the fat transitive jar before the thin own-module jar`() {
        // Both exist: the thin own-module R.jar (small) and the transitive runtime R.jar (large).
        // Ordering matters — URLClassLoader resolves first match, so the transitive jar (which
        // alone contains dependency R classes like poolingcontainer.R$id) must come first.
        val thin = writeJar(
            "build/intermediates/compile_r_class_jar/debug/generateDebugRFile/R.jar",
            size = 1_000,
        )
        val fat = writeJar(
            "build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar",
            size = 100_000,
        )
        val found = UserModuleClasspathResolver.findGeneratedRJars(tmp)
        assertEquals(listOf(fat, thin), found)
    }

    @Test
    fun `returns empty when no r_class_jar intermediates exist`() {
        File(tmp, "build/intermediates/other_stuff").mkdirs()
        assertEquals(emptyList(), UserModuleClasspathResolver.findGeneratedRJars(tmp))
    }
}
