package com.keymusicman.appflowerplugin.appflowerplugin

import com.android.resources.ResourceFolderType
import com.android.tools.configurations.Configuration
import com.android.tools.idea.configurations.ConfigurationManager
import com.android.tools.idea.rendering.AndroidBuildTargetReference
import com.android.tools.idea.rendering.AndroidFacetRenderModelModule
import com.android.tools.idea.rendering.StudioRenderService
import com.android.tools.rendering.parsers.RenderXmlFileSnapshot
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.android.facet.AndroidFacet
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Renders @Composable functions from the user's Android module using Layoutlib.
 *
 * Layoutlib works on compiled bytecode, so the module must already be built
 * (the exportGraph task guarantees this).
 */
object ComposableRenderer {

    private val LOG = Logger.getInstance(ComposableRenderer::class.java)

    /**
     * Renders [composableFqn] and returns the path to a temp PNG, or null on failure.
     *
     * @param project        the open IntelliJ project
     * @param modulePath     absolute path to the Gradle module directory (from GradleModuleInfo)
     * @param composableFqn  fully-qualified composable function name, e.g. "com.example.HomeScreen"
     */
    fun render(
        project: Project,
        modulePath: String,
        composableFqn: String,
        widthDp: Int = 360,
        heightDp: Int = 640,
    ): String? {
        LOG.info("render() called for composable=$composableFqn, modulePath=$modulePath")

        val module = ModuleManager.getInstance(project).modules
            .firstOrNull { m ->
                ModuleRootManager.getInstance(m).contentRoots.any { root -> root.path == modulePath }
            }
        if (module == null) {
            LOG.warn("render() failed: no module found matching path=$modulePath. " +
                "Available modules: ${ModuleManager.getInstance(project).modules.map { it.name }}")
            return null
        }

        val facet = AndroidFacet.getInstance(module)
        if (facet == null) {
            LOG.warn("render() failed: no AndroidFacet for module=${module.name}")
            return null
        }

        val moduleVf = LocalFileSystem.getInstance().findFileByPath(modulePath)
        if (moduleVf == null) {
            LOG.warn("render() failed: could not find VirtualFile for modulePath=$modulePath")
            return null
        }

        val config: Configuration = ConfigurationManager
            .getOrCreateInstance(module)
            .getConfiguration(moduleVf)

        val buildTargetRef = AndroidBuildTargetReference.gradleOnly(facet)
        val renderModelModule = AndroidFacetRenderModelModule(buildTargetRef)

        val renderService = StudioRenderService.getInstance(project)
        val renderLogger = renderService.createLogger(project)

        val task = try {
            renderService
                .taskBuilder(renderModelModule, config, renderLogger)
                .disableDecorations()
                .build()
                .get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            LOG.error("render() failed: exception building render task for composable=$composableFqn", e)
            return null
        }
        if (task == null) {
            LOG.warn("render() failed: render task is null for composable=$composableFqn")
            return null
        }

        return try {
            // Synthetic layout: ComposeViewAdapter + composableName is the same bridge
            // Android Studio's own Preview uses under the hood.
            val xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <androidx.compose.ui.tooling.ComposeViewAdapter
                    xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools"
                    android:layout_width="${widthDp}dp"
                    android:layout_height="${heightDp}dp"
                    tools:composableName="$composableFqn" />
            """.trimIndent()

            task.setXmlFile(RenderXmlFileSnapshot(project, "preview.xml", ResourceFolderType.LAYOUT, xml))

            val result = task.render().get(30, TimeUnit.SECONDS)
            if (result == null) {
                LOG.warn("render() failed: render result is null for composable=$composableFqn")
                return null
            }

            val image: BufferedImage? = result.getRenderedImage().getCopy()
            if (image == null) {
                LOG.warn("render() failed: rendered image is null for composable=$composableFqn")
                return null
            }

            val safeName = composableFqn.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val outDir = File(modulePath, "build/appflower-previews").also { it.mkdirs() }
            val outFile = File(outDir, "$safeName.png")
            ImageIO.write(image, "PNG", outFile)
            LOG.info("render() succeeded for composable=$composableFqn -> ${outFile.absolutePath}")
            outFile.absolutePath
        } catch (e: Exception) {
            LOG.error("render() failed: exception during rendering of composable=$composableFqn", e)
            null
        } finally {
            task.dispose()
        }
    }
}
