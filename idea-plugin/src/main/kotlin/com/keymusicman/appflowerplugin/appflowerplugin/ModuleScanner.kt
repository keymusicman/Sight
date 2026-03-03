package com.keymusicman.appflowerplugin.appflowerplugin

import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.io.File

data class GradleModuleInfo(
    val name: String,
    val modulePath: String,
    val gradleTaskPath: String,
    val projectRootPath: String
)

object ModuleScanner {

    /**
     * Scans the project for Gradle build files that register an `exportGraph` task.
     * Uses linked Gradle root project paths from IntelliJ's GradleSettings, then
     * walks the directory tree looking for build scripts referencing "exportGraph".
     */
    fun findModulesWithExportGraph(project: Project): List<GradleModuleInfo> {
        val result = mutableListOf<GradleModuleInfo>()

        val linkedRoots = GradleSettings.getInstance(project)
            .linkedProjectsSettings
            .map { it.externalProjectPath }
            .ifEmpty {
                // fallback: use project base path if no linked Gradle projects found yet
                listOfNotNull(project.basePath)
            }

        for (rootPath in linkedRoots) {
            val rootDir = File(rootPath)
            if (!rootDir.isDirectory) continue

            rootDir.walkTopDown()
                .onEnter { dir ->
                    val name = dir.name
                    // skip hidden dirs, .gradle cache, and build output dirs
                    !name.startsWith(".") && name != "build"
                }
                .filter { it.isFile && (it.name == "build.gradle.kts" || it.name == "build.gradle") }
                .forEach { buildFile ->
                    if (buildFile.readText().contains("exportGraph")) {
                        val moduleDir = buildFile.parentFile
                        val relativePath = moduleDir.relativeTo(rootDir).path
                        val gradleTaskPath = if (relativePath.isEmpty()) {
                            "exportGraph"
                        } else {
                            ":" + relativePath.replace(File.separatorChar, ':') + ":exportGraph"
                        }
                        val displayName = if (relativePath.isEmpty()) rootDir.name else moduleDir.name
                        result.add(
                            GradleModuleInfo(
                                name = displayName,
                                modulePath = moduleDir.absolutePath,
                                gradleTaskPath = gradleTaskPath,
                                projectRootPath = rootPath
                            )
                        )
                    }
                }
        }

        return result
    }
}
