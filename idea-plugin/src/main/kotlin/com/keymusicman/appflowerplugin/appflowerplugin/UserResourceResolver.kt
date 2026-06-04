package com.keymusicman.appflowerplugin.appflowerplugin

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import java.io.File

/**
 * Resolves the on-disk resource directories and R.jar paths the render worker needs to resolve
 * user resources, mirroring how [UserModuleClasspathResolver] resolves the classpath. Discovery
 * runs in the plugin process (it has the IntelliJ project model); the worker just loads what it
 * is handed. See `docs/superpowers/specs/2026-06-02-worker-user-resources-design.md`.
 */
object UserResourceResolver {
    private val log = Logger.getInstance(UserResourceResolver::class.java)

    data class UserResources(val resDirs: List<String>, val rJarPaths: List<String>)

    fun resolve(project: Project, modulePath: String): UserResources {
        val resDirs = LinkedHashSet<String>()
        // The target module's own res dirs, derived straight from [modulePath] — which IS the
        // module dir (same value used for R.jar discovery below). This is deliberately independent
        // of the IntelliJ module model: findModule() returns the `.main` source-set module, whose
        // content root is the source-set dir (…/example-app/src/main), so feeding it to
        // ownModuleResDirs() (which expects a module ROOT) computes …/src/main/src/*/res = ∅ and
        // the app's own resources never reach the worker → every drawable placeheld → blank render.
        ownModuleResDirs(File(modulePath)).forEach { resDirs.add(it.absolutePath) }
        ReadAction.run<Throwable> {
            val module = UserModuleClasspathResolver.findModule(project, modulePath)
                ?: error("UserResourceResolver: no module found for $modulePath")
            // The target module + every dependency *project* module: their on-disk res dirs.
            moduleResDirs(module).forEach { resDirs.add(it.absolutePath) }
            OrderEnumerator.orderEntries(module).recursively().productionOnly().forEachModule { dep ->
                moduleResDirs(dep).forEach { resDirs.add(it.absolutePath) }
                true
            }
        }
        // External AAR res dirs: sibling `res` of each transformed `classes.jar` on the classpath.
        UserModuleClasspathResolver.resolve(project, modulePath)
            .mapNotNull { aarResDirForJar(it) }
            .forEach { resDirs.add(it.absolutePath) }

        val rJars = UserModuleClasspathResolver.findGeneratedRJars(File(modulePath)).map { it.absolutePath }
        val srcResCount = resDirs.count { it.contains("${File.separator}src${File.separator}") }
        log.info(
            "UserResourceResolver: resolved ${resDirs.size} res dirs ($srcResCount source-set), " +
                "${rJars.size} R.jar(s) for $modulePath"
        )
        return UserResources(resDirs.toList(), rJars)
    }

    /**
     * On-disk res dirs for [module] derived from its content roots (no Studio resource API).
     * A content root may be the module dir (…/lib) or a source-set dir (…/lib/src/main) depending
     * on how the module was imported, so [ownModuleResDirs] is tried against both shapes.
     */
    private fun moduleResDirs(module: Module): List<File> =
        ModuleRootManager.getInstance(module).contentRoots.flatMap { root ->
            File(root.path).takeIf { it.isDirectory }?.let { dir ->
                ownModuleResDirs(dir) + moduleDirFromSourceSet(dir)?.let(::ownModuleResDirs).orEmpty()
            }.orEmpty()
        }.distinctBy { it.absolutePath }

    /** If [dir] is a source-set dir (`…/src/<set>`), the module root is its grandparent. */
    private fun moduleDirFromSourceSet(dir: File): File? =
        dir.takeIf { it.parentFile?.name == "src" }?.parentFile?.parentFile

    /** `src/<sourceSet>/res` dirs plus any `build/generated/**/res` dirs under [moduleDir]. */
    internal fun ownModuleResDirs(moduleDir: File): List<File> {
        val srcSetRes = File(moduleDir, "src").listFiles { f -> f.isDirectory }
            ?.map { File(it, "res") }?.filter { it.isDirectory }.orEmpty()
        val generatedRoot = File(moduleDir, "build/generated/res")
        val generated = if (generatedRoot.isDirectory) {
            generatedRoot.walkTopDown().filter { it.isDirectory && it.name == "res" }.toList()
        } else emptyList()
        return (srcSetRes + generated).distinctBy { it.absolutePath }
    }

    /** `<…>/transformed/<aar>/jars/classes.jar` → `<…>/transformed/<aar>/res` if it exists. */
    internal fun aarResDirForJar(jarPath: String): File? {
        val jar = File(jarPath)
        if (jar.name != "classes.jar") return null
        val aarRoot = jar.parentFile?.parentFile ?: return null // up from jars/ to <aar>/
        return File(aarRoot, "res").takeIf { it.isDirectory }
    }
}
