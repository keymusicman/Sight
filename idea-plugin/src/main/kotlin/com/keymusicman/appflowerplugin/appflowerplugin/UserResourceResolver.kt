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
        log.info("UserResourceResolver: resolved ${resDirs.size} res dirs, ${rJars.size} R.jar(s) for $modulePath")
        return UserResources(resDirs.toList(), rJars)
    }

    /** On-disk res dirs for [module] derived from its content roots (no Studio resource API). */
    private fun moduleResDirs(module: Module): List<File> =
        ModuleRootManager.getInstance(module).contentRoots.flatMap { root ->
            File(root.path).let { if (it.isDirectory) ownModuleResDirs(it) else emptyList() }
        }

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
