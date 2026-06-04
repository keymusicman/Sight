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

    @Test
    fun `prioritizeRuntimeRJars hoists runtime R jar to front and drops placeholder`() {
        // Real-world order: the all-zero placeholder compile R.jar arrives first (via the IDE
        // compile classpath), the real runtime R.jar later (via explicit AGP outputs).
        // URLClassLoader resolves first match, so the runtime jar must end up first; the
        // placeholder (R$drawable.foo == 0 → painterResource(0) → blank render) must be dropped.
        val thin = "/proj/build/intermediates/compile_r_class_jar/debug/generateDebugRFile/R.jar"
        val classes = "/proj/build/tmp/kotlin-classes/debug"
        val fat = "/proj/build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar"

        val result = UserModuleClasspathResolver.prioritizeRuntimeRJars(listOf(thin, classes, fat))

        assertEquals(fat, result.first(), "runtime R.jar must win the first-match lookup")
        assertTrue(thin !in result, "placeholder compile_r_class_jar R.jar must be dropped")
        assertTrue(classes in result, "non-R classpath entries must be preserved")
    }

    @Test
    fun `prioritizeRuntimeRJars leaves classpath untouched when only a placeholder R jar exists`() {
        // No runtime R.jar to prefer — keep the placeholder; it's all we have.
        val thin = "/proj/build/intermediates/compile_r_class_jar/debug/generateDebugRFile/R.jar"
        val classes = "/proj/build/tmp/kotlin-classes/debug"
        val input = listOf(classes, thin)

        assertEquals(input, UserModuleClasspathResolver.prioritizeRuntimeRJars(input))
    }
}
