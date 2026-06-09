package io.github.keymusicman.sight.plugin

import com.intellij.openapi.project.Project
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.GradleProject
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
     * Uses the Gradle Tooling API to find all subprojects that expose an `exportGraph`
     * task, regardless of how the task was registered (direct, convention plugin, etc.).
     * Reuses the existing Gradle daemon if one is running; starts one otherwise.
     */
    fun findModulesWithExportGraph(project: Project): List<GradleModuleInfo> {
        val linkedRoots = GradleSettings.getInstance(project)
            .linkedProjectsSettings
            .map { it.externalProjectPath }
            .ifEmpty { listOfNotNull(project.basePath) }

        return linkedRoots.flatMap { rootPath ->
            try {
                GradleConnector.newConnector()
                    .forProjectDirectory(File(rootPath))
                    .connect()
                    .use { connection ->
                        val rootProject = connection.getModel(GradleProject::class.java)
                        collectModulesWithTask(rootProject, "exportGraph", rootPath)
                    }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun collectModulesWithTask(
        gradleProject: GradleProject,
        taskName: String,
        rootPath: String,
    ): List<GradleModuleInfo> {
        val result = mutableListOf<GradleModuleInfo>()

        val task = gradleProject.tasks.firstOrNull { it.name == taskName }
        if (task != null) {
            result.add(
                GradleModuleInfo(
                    name = gradleProject.name,
                    modulePath = gradleProject.projectDirectory.absolutePath,
                    gradleTaskPath = task.path,
                    projectRootPath = rootPath,
                )
            )
        }

        gradleProject.children.forEach { child ->
            result.addAll(collectModulesWithTask(child, taskName, rootPath))
        }

        return result
    }
}