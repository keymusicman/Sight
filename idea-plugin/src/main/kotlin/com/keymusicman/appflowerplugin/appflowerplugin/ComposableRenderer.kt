package com.keymusicman.appflowerplugin.appflowerplugin

import com.android.resources.ResourceFolderType
import com.android.tools.configurations.Configuration
import com.android.tools.idea.configurations.ConfigurationManager
import com.android.tools.idea.rendering.AndroidBuildTargetReference
import com.android.tools.idea.rendering.AndroidFacetRenderModelModule
import com.android.tools.idea.rendering.StudioRenderService
import com.android.tools.rendering.parsers.RenderXmlFileSnapshot
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
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
        val module = ModuleManager.getInstance(project).modules
            .firstOrNull { it.moduleFilePath.startsWith(modulePath) }
            ?: return null

        val facet = AndroidFacet.getInstance(module) ?: return null
        val moduleVf = module.moduleFile ?: return null

        val config: Configuration = ConfigurationManager
            .getOrCreateInstance(module)
            .getConfiguration(moduleVf)

        val buildTargetRef = AndroidBuildTargetReference.gradleOnly(facet)
        val renderModelModule = AndroidFacetRenderModelModule(buildTargetRef)

        val renderService = StudioRenderService.getInstance(project)
        val logger = renderService.createLogger(project)

        val task = renderService
            .taskBuilder(renderModelModule, config, logger)
            .disableDecorations()
            .build()
            .get(30, TimeUnit.SECONDS)
            ?: return null

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

            val result = task.render().get(30, TimeUnit.SECONDS) ?: return null
            val image: BufferedImage = result.getRenderedImage().getCopy() ?: return null

            val tag = composableFqn.substringAfterLast('.')
            val tempFile = File.createTempFile("appflower_${tag}_", ".png")
            ImageIO.write(image, "PNG", tempFile)
            tempFile.deleteOnExit()
            tempFile.absolutePath
        } finally {
            task.dispose()
        }
    }
}
