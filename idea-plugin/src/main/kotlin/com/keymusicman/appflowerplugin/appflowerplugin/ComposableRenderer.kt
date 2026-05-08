package com.keymusicman.appflowerplugin.appflowerplugin

import com.android.resources.ResourceFolderType
import com.android.tools.configurations.Configuration
import com.android.tools.idea.configurations.ConfigurationManager
import com.android.tools.idea.rendering.AndroidBuildTargetReference
import com.android.tools.idea.rendering.AndroidFacetRenderModelModule
import com.android.tools.idea.rendering.StudioRenderService
import com.android.tools.idea.rendering.isSuccess
import com.android.tools.rendering.parsers.RenderXmlFileSnapshot
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.android.facet.AndroidFacet
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

    // Set to true to render a plain red TextView instead of ComposeViewAdapter.
    // Isolates whether failures are in the rendering pipeline or in Compose itself.
    private const val DEBUG_SIMPLE_LAYOUT = false

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
        parameterProviderFqn: String? = null,
        stateIndex: Int = -1,
        widthDp: Int = 360,
        heightDp: Int = 640,
        sourceFilePath: String? = null,
    ): String? {
        LOG.info("render() called for composable=$composableFqn, modulePath=$modulePath, sourceFilePath=$sourceFilePath")

        val allModules = ModuleManager.getInstance(project).modules

        // Find the owning module for the source file. Skip synthetic source-set sub-modules
        // (e.g. *.main, *.test) — AndroidFacet lives on the parent module, not these children.
        // If the owning module turns out to be a library (no app manifest), fall back to the
        // app module from modulePath, which has the full resource + manifest context Layoutlib needs.
        val sourceOwnerModule = if (sourceFilePath != null) {
            // Exclude IntelliJ's per-source-set sub-modules (e.g. *.main, *.test).
            // AndroidFacet lives on the parent Gradle module, not these synthetic children.
            val sourceSetSegments = setOf("main", "test", "unitTest", "androidTest")
            val candidate = allModules
                .filter { m ->
                    val seg = m.name.substringAfterLast('.')
                    seg !in sourceSetSegments && !seg.startsWith("screenshotTest")
                }
                .flatMap { m ->
                    ModuleRootManager.getInstance(m).contentRoots
                        .filter { root ->
                            sourceFilePath.startsWith(root.path + "/") || sourceFilePath == root.path
                        }
                        .map { root -> m to root.path.length }
                }
                .maxByOrNull { (_, len) -> len }
                ?.first

            when {
                candidate == null -> {
                    LOG.warn("render() no module owns sourceFilePath=$sourceFilePath, falling back to modulePath")
                    null
                }
                AndroidFacet.getInstance(candidate)?.configuration?.isLibraryProject == true -> {
                    LOG.info("render() ${candidate.name} is a library module; using app module from modulePath for rendering")
                    null
                }
                else -> {
                    LOG.info("render() resolved owner app module=${candidate.name} for sourceFilePath=$sourceFilePath")
                    candidate
                }
            }
        } else null

        // The root "holder" module triggers "holder module ambiguous" in GradleBuildSystemFilePreviewServices
        // because multiple build-variant sub-modules share the same logical name.
        // Android Studio's own preview avoids this by using the .main source-set module instead.
        val appRootModule = sourceOwnerModule
            ?: allModules.firstOrNull { m ->
                ModuleRootManager.getInstance(m).contentRoots.any { root -> root.path == modulePath }
            }

        if (appRootModule == null) {
            LOG.warn("render() failed: no module found matching path=$modulePath. " +
                "Available modules: ${allModules.map { it.name }}")
            return null
        }

        val module = allModules.firstOrNull { m -> m.name == "${appRootModule.name}.main" }
            ?.also { LOG.info("render() using .main source-set module=${it.name} to avoid holder-module ambiguity") }
            ?: appRootModule

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
            val xml = if (DEBUG_SIMPLE_LAYOUT) {
                // Plain TextView — tests that the rendering pipeline works independently of Compose.
                """
                <TextView
                    xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="${widthDp}dp"
                    android:layout_height="${heightDp}dp"
                    android:text="$composableFqn"
                    android:textColor="#FF0000"
                    android:textSize="24sp"
                    android:gravity="center" />
                """.trimIndent()
            } else {
                // Real path: ComposeViewAdapter is the same bridge Android Studio's Preview uses.
                val providerAttr = if (parameterProviderFqn != null) buildString {
                    append("\n    tools:parameterProviderClass=\"$parameterProviderFqn\"")
                    if (stateIndex >= 0) append("\n    tools:parameterProviderIndex=\"$stateIndex\"")
                } else ""
                // No <?xml?> declaration — kxml2 treats it as a Processing Instruction and rejects it.
                """
                <androidx.compose.ui.tooling.ComposeViewAdapter
                    xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    tools:composableName="$composableFqn"$providerAttr />
                """.trimIndent()
            }
            LOG.info("render() xml for composable=$composableFqn:\n$xml")

            task.setXmlFile(RenderXmlFileSnapshot(project, "preview.xml", ResourceFolderType.LAYOUT, xml))

            val result = task.render().get(30, TimeUnit.SECONDS)
            if (result == null) {
                LOG.warn("render() failed: render result is null for composable=$composableFqn")
                return null
            }

            LOG.info("render() result: isSuccess=${result.isSuccess()}, module=${module.name}")

            // Log every render message regardless of severity so we can diagnose failures.
            result.logger.messages.forEach { msg ->
                LOG.warn("render() layoutlib [${msg.severity}] $composableFqn: ${msg.html}")
            }
            // Broken/missing classes are stored separately from messages — often the real root cause.
            runCatching { result.logger.brokenClasses.takeIf { it.isNotEmpty() } }.getOrNull()
                ?.let { LOG.warn("render() brokenClasses for $composableFqn: ${it.keys}") }
            runCatching { result.logger.missingClasses.takeIf { it.isNotEmpty() } }.getOrNull()
                ?.let { LOG.warn("render() missingClasses for $composableFqn: $it") }

            val image = result.renderedImage.copy
                ?: run {
                    LOG.warn("render() failed: rendered image is null for composable=$composableFqn")
                    return null
                }

            val safeName = composableFqn.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val outDir = File(modulePath, "build/appflower-previews").also { it.mkdirs() }
            val outFile = if (stateIndex >= 0) File(outDir, "${safeName}_${stateIndex}.png")
                          else File(outDir, "$safeName.png")
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
