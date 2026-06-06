package com.keymusicman.appflowerplugin.appflowerplugin

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [UserModuleClasspathResolver.findGeneratedRJars] and
 * [UserModuleClasspathResolver.prioritizeRuntimeRJars]. Regression guard for the
 * `NoClassDefFoundError: androidx/customview/poolingcontainer/R$id` crash: the standalone
 * worker classloader can't synthesize R classes on the fly (unlike Studio's ModuleClassLoader),
 * so the transitive generated R.jar must reach the classpath. Library/feature modules only
 * generate the thin `compile_r_class_jar`; the transitive R.jar lives in the APPLICATION
 * module that packages them — [findGeneratedRJars] falls back to sibling/ancestor search.
 *
 * Hierarchy design: `tmp` lives 3 levels deep inside `testRoot` (`android/feature/module/`),
 * mirroring a real project layout. [findTransitiveRJarInAncestors] caps at `depth < 3`, so the
 * scan exhausts at `testRoot/` — it never reaches the system temp folder where parallel test
 * runs leave their own artifacts.
 */
class UserModuleClasspathResolverTest {

    // testRoot/ mirrors project root isolation
    //   android/            ← grouping dir (depth-1 scan target)
    //     feature/          ← feature group (depth-0 scan target)
    //       module/         ← tmp, the "current module" under test
    //     app/              ← app group (created per-test when needed)
    //       app-module/     ← app module with fat R.jar
    private val testRoot: File = createTempDirectory().toFile()
    private val androidDir: File = File(testRoot, "android").also { it.mkdirs() }
    private val featureDir: File = File(androidDir, "feature").also { it.mkdirs() }
    private val tmp: File = File(featureDir, "module").also { it.mkdirs() }

    private fun writeJar(moduleDir: File, relPath: String, size: Int): File {
        val f = File(moduleDir, relPath)
        f.parentFile.mkdirs()
        f.writeBytes(ByteArray(size))
        return f
    }

    private fun writeJar(relPath: String, size: Int): File = writeJar(tmp, relPath, size)

    @Test
    fun `finds transitive R jar under compile_and_runtime_r_class_jar (newer AGP path)`() {
        val rJar = writeJar(
            "build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar",
            size = 4096,
        )
        val found = UserModuleClasspathResolver.findGeneratedRJars(tmp)
        assertTrue(rJar in found, "expected to discover $rJar, got $found")
    }

    @Test
    fun `returns the fat transitive jar before the thin own-module jar`() {
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
        // No R.jar anywhere under testRoot. depth<3 exhausts at testRoot/ without escaping
        // to /T/ (system temp) where other test runs may leave R.jars.
        File(tmp, "build/intermediates/other_stuff").mkdirs()
        assertEquals(emptyList(), UserModuleClasspathResolver.findGeneratedRJars(tmp))
    }

    @Test
    fun `findGeneratedRJars falls back to nested sibling app module when feature module has only compile_r_class_jar`() {
        // Mirrors android/feature/login (library) + android/app/example-module (app module).
        // The app module's fat R.jar is one level DOWN inside the "app" grouping dir,
        // so the scan at depth=1 (scanning android/) must also probe sibling dirs' children.
        val thin = writeJar(
            "build/intermediates/compile_r_class_jar/debug/generateDebugRFile/R.jar",
            size = 1_000,
        )
        val appGroupDir = File(androidDir, "app").also { it.mkdirs() }
        val appModuleDir = File(appGroupDir, "app-module").also { it.mkdirs() }
        val fat = writeJar(
            appModuleDir,
            "build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar",
            size = 100_000,
        )

        val found = UserModuleClasspathResolver.findGeneratedRJars(tmp)

        assertTrue(fat in found, "nested sibling app module's transitive R.jar must be discovered: $found")
        assertTrue(thin in found, "feature module's own thin R.jar must also be included: $found")
        assertEquals(fat, found.first(), "transitive jar must come before own-module jar: $found")
    }

    @Test
    fun `prioritizeRuntimeRJars hoists runtime R jar to front, keeps placeholder`() {
        // The placeholder is kept: when the runtime jar comes from a sibling app module it
        // won't include the current module's own R classes — the placeholder is their only source.
        val thin = "/proj/build/intermediates/compile_r_class_jar/debug/generateDebugRFile/R.jar"
        val classes = "/proj/build/tmp/kotlin-classes/debug"
        val fat = "/proj/build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar"

        val result = UserModuleClasspathResolver.prioritizeRuntimeRJars(listOf(thin, classes, fat))

        assertEquals(fat, result.first(), "runtime R.jar must win the first-match lookup")
        assertTrue(thin in result, "placeholder must be kept — may be only source of module-own R classes")
        assertTrue(classes in result, "non-R classpath entries must be preserved")
    }

    @Test
    fun `prioritizeRuntimeRJars leaves classpath untouched when only a placeholder R jar exists`() {
        val thin = "/proj/build/intermediates/compile_r_class_jar/debug/generateDebugRFile/R.jar"
        val classes = "/proj/build/tmp/kotlin-classes/debug"
        val input = listOf(classes, thin)

        assertEquals(input, UserModuleClasspathResolver.prioritizeRuntimeRJars(input))
    }
}
