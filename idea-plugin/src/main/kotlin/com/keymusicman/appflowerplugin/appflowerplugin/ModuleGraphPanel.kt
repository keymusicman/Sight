package com.keymusicman.appflowerplugin.appflowerplugin

import androidx.compose.ui.awt.ComposePanel
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.keymusicman.appflower.loader.GraphLoader
import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.ui.AppTheme
import com.keymusicman.appflower.ui.GraphPanel
import com.keymusicman.appflower.viewmodel.GraphViewModel
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class ModuleGraphPanel(
    private val project: Project,
    private val moduleInfo: GradleModuleInfo,
) : JPanel(BorderLayout()), Disposable {

    @Volatile private var disposed = false

    override fun dispose() { disposed = true }

    private val log = Logger.getInstance(ModuleGraphPanel::class.java)

    private val graphFile = File(moduleInfo.modulePath, "build/graph/app-graph.json")
    private val buildButton = JButton("Build graph").apply { addActionListener { runExportGraph() } }
    private val refreshButton = JButton("Refresh previews").apply { addActionListener { refreshPreviews() } }
    private val statusLabel = JLabel()
    private val viewModel = GraphViewModel()

    init {
        add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(buildButton)
            add(refreshButton)
            add(statusLabel)
        }, BorderLayout.NORTH)

        val composePanel = ComposePanel().apply {
            setContent {
                AppTheme(isDark = true) {
                    GraphPanel(
                        viewModel = viewModel,
                        onViewSource = { nodeId ->
                            viewModel.appGraphState.value?.let { appGraph ->
                                AppExecutorUtil.getAppExecutorService().submit {
                                    ReadAction.run<Throwable> {
                                        SourceNavigator.navigateToSource(
                                            project, nodeId, appGraph, moduleInfo.projectRootPath
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
        add(composePanel, BorderLayout.CENTER)

        refreshState()
    }

    fun refreshState() {
        buildButton.isEnabled = false
        statusLabel.text = "Loading…"

        AppExecutorUtil.getAppExecutorService().submit {
            if (disposed) return@submit
            val appGraph = if (graphFile.exists())
                GraphLoader.loadFromFile(graphFile)?.withRenderedPreviews()
            else null

            if (appGraph != null) {
                viewModel.buildFromAppGraphV2(appGraph, moduleInfo.projectRootPath)
            }

            SwingUtilities.invokeLater {
                if (!disposed) {
                    buildButton.isEnabled = true
                    statusLabel.text = if (appGraph == null) "No graph — click Build graph" else ""
                }
            }
        }
    }

    private fun runExportGraph() {
        buildButton.isEnabled = false
        statusLabel.text = "Building…"

        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalProjectPath = moduleInfo.projectRootPath
            taskNames = listOf(moduleInfo.gradleTaskPath)
            externalSystemIdString = GradleConstants.SYSTEM_ID.id
        }

        ExternalSystemUtil.runTask(
            settings,
            DefaultRunExecutor.EXECUTOR_ID,
            project,
            GradleConstants.SYSTEM_ID,
            object : TaskCallback {
                override fun onSuccess() { refreshState() }
                override fun onFailure() {
                    SwingUtilities.invokeLater {
                        statusLabel.text = "Build failed — see Gradle console for details."
                        buildButton.isEnabled = true
                    }
                }
            },
            ProgressExecutionMode.IN_BACKGROUND_ASYNC
        )
    }

    private fun refreshPreviews() {
        val currentGraph = viewModel.appGraphState.value ?: return
        refreshButton.isEnabled = false
        buildButton.isEnabled = false
        statusLabel.text = "Rendering previews…"

        AppExecutorUtil.getAppExecutorService().submit {
            if (disposed) return@submit
            val updated = currentGraph.withRenderedPreviews()
            viewModel.buildFromAppGraphV2(updated, moduleInfo.projectRootPath)

            SwingUtilities.invokeLater {
                if (!disposed) {
                    refreshButton.isEnabled = true
                    buildButton.isEnabled = true
                    statusLabel.text = ""
                }
            }
        }
    }

    /** Carries the composable FQN, optional @PreviewParameter provider FQN, and source file path. */
    private data class PreviewTarget(
        val fqn: String,
        val parameterProviderFqn: String? = null,
        val sourceFilePath: String? = null,
    )

    private fun AppGraph.withRenderedPreviews(): AppGraph {
        log.info("withRenderedPreviews() starting for module=${moduleInfo.modulePath}")
        val updatedSubgraphs = subgraphs.mapValues { (_, subgraph) ->
            val updatedScreens = subgraph.screens.map { screen ->
                val target = resolvePreviewTarget(screen)
                log.info("withRenderedPreviews() target=$target for screen=${screen.function}")
                val rendered = target?.let {
                    runCatching {
                        ComposableRenderer.render(
                            project, moduleInfo.modulePath,
                            it.fqn, it.parameterProviderFqn,
                            sourceFilePath = it.sourceFilePath,
                        )
                    }.onFailure { e ->
                        log.error("withRenderedPreviews() exception rendering screen=${screen.function}", e)
                    }.getOrNull()
                }
                if (rendered == null) log.warn("withRenderedPreviews() no preview for ${screen.function}")
                if (rendered != null) screen.copy(screenshot_location = rendered) else screen
            }
            subgraph.copy(screens = updatedScreens)
        }
        log.info("withRenderedPreviews() completed")
        return copy(subgraphs = updatedSubgraphs)
    }

    /**
     * Resolves the best renderable [PreviewTarget] for [screen]:
     * 1. Reads the screenshot test file body to find the production composable name.
     * 2. Searches all main source sets for a @Preview-annotated wrapper, extracting
     *    any @PreviewParameter provider class so the render XML stays complete.
     * 3. Falls back to the raw production composable.
     */
    private fun resolvePreviewTarget(screen: com.keymusicman.appflower.model.Screen): PreviewTarget? {
        val parts = screen.function.split("::")
        if (parts.size != 2) return null
        val fnName = parts[1].trim()

        val mainKtFiles by lazy {
            File(moduleInfo.projectRootPath).walkTopDown()
                .onEnter { dir -> dir.name != "build" && dir.name != ".gradle" }
                .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path.replace('\\', '/') }
                .toList()
        }

        val testFile = File(moduleInfo.projectRootPath, screen.location)
        val productionName = if (testFile.exists()) {
            extractCalledComposable(testFile, fnName, screen.id).also { name ->
                if (name == null)
                    log.warn("resolvePreviewTarget: could not extract composable from $fnName in ${testFile.name}")
                else
                    log.info("resolvePreviewTarget: screen=${screen.id} → extracted=$name from test file")
            }
        } else {
            log.info("resolvePreviewTarget: test file not found (location=${screen.location}), using fnName=$fnName as hint")
            fnName
        } ?: fnName

        // Prefer @Preview-annotated wrappers (named <Name>Preview or <Name>Previews)
        for (candidate in listOf("${productionName}Preview", "${productionName}Previews")) {
            val target = findPreviewTargetInFiles(mainKtFiles, candidate)
            if (target != null) {
                log.info("resolvePreviewTarget: using @Preview wrapper $target")
                return target
            }
        }

        // Fall back to the production composable itself
        val target = findPreviewTargetInFiles(mainKtFiles, productionName)
        if (target != null) {
            log.info("resolvePreviewTarget: using production composable $target")
            return target
        }

        log.warn("resolvePreviewTarget: no renderable composable found for screen=${screen.id}")
        return null
    }

    /**
     * Searches [files] for a file declaring [functionName].
     * If found, returns a [PreviewTarget] with the JVM FQN and, if the function has a
     * @PreviewParameter annotation, the resolved FQN of the provider class.
     *
     * JVM FQN format required by ComposeViewAdapter / ComposableInvoker:
     *   top-level fun in Foo.kt  →  "pkg.FooKt.FunctionName"
     *   fun inside class/object  →  "pkg.ClassName.FunctionName"
     */
    private fun findPreviewTargetInFiles(files: List<File>, functionName: String): PreviewTarget? {
        val funPattern = Regex("""fun\s+${Regex.escape(functionName)}\s*[\(<]""")
        for (file in files) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            if (!funPattern.containsMatchIn(text)) continue
            val pkg = text.lineSequence()
                .firstOrNull { it.startsWith("package ") }
                ?.removePrefix("package ")?.trim() ?: continue
            val enclosingClass = findEnclosingClass(text, functionName)
            val jvmClass = enclosingClass ?: "${file.nameWithoutExtension}Kt"
            val fqn = "$pkg.$jvmClass.$functionName"
            log.info("findPreviewTargetInFiles: $functionName → jvmClass=$jvmClass, fqn=$fqn")
            val providerFqn = extractPreviewParameterProviderFqn(text, functionName)
            return PreviewTarget(fqn, providerFqn, file.absolutePath)
        }
        return null
    }

    /**
     * Returns the simple name of the class/object that immediately encloses [functionName]
     * in [fileText], or null if the function is top-level.
     */
    private fun findEnclosingClass(fileText: String, functionName: String): String? {
        val funStart = Regex("""fun\s+${Regex.escape(functionName)}\s*[\(<]""")
            .find(fileText)?.range?.first ?: return null
        val textBefore = fileText.substring(0, funStart)
        var depth = 0
        for (ch in textBefore) {
            when (ch) { '{' -> depth++; '}' -> depth-- }
        }
        if (depth == 0) return null
        return Regex("""(?:class|object)\s+(\w+)""").findAll(textBefore).lastOrNull()?.groupValues?.get(1)
    }

    /**
     * Finds `@PreviewParameter(SomeProvider::class)` in the signature of [functionName]
     * inside [fileText] and resolves `SomeProvider` to its FQN using the file's imports
     * or package declaration.
     */
    private fun extractPreviewParameterProviderFqn(fileText: String, functionName: String): String? {
        val funStart = Regex("""fun\s+${Regex.escape(functionName)}\s*\(""")
            .find(fileText)?.range?.first ?: return null
        val braceIdx = fileText.indexOf('{', funStart).takeIf { it >= 0 } ?: return null
        val signature = fileText.substring(funStart, braceIdx)

        val providerClassName = Regex("""@PreviewParameter\s*\(\s*(?:value\s*=\s*)?(\w+)::class""")
            .find(signature)?.groupValues?.get(1) ?: return null

        // Resolve: check explicit import first
        val explicitImport = fileText.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("import ") && (it.endsWith(".$providerClassName") || it.contains(".$providerClassName ")) }
            ?.removePrefix("import ")?.trim()
        if (explicitImport != null) return explicitImport

        // Fall back: same package as the file
        val pkg = fileText.lineSequence()
            .firstOrNull { it.startsWith("package ") }
            ?.removePrefix("package ")?.trim() ?: return providerClassName
        return "$pkg.$providerClassName"
    }

    /**
     * Parses the body of [fnName] in [file] and returns the first uppercase-starting
     * function call that looks like the production composable for [screenId].
     */
    private fun extractCalledComposable(file: File, fnName: String, screenId: String): String? {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        val startLine = lines.indexOfFirst {
            Regex("""fun\s+${Regex.escape(fnName)}\s*[\(<]""").containsMatchIn(it)
        }.takeIf { it >= 0 } ?: return null

        val body = buildString {
            var depth = 0; var inBody = false
            for (i in startLine until lines.size) {
                val line = lines[i]
                for (ch in line) { if (ch == '{') { depth++; inBody = true } else if (ch == '}') depth-- }
                appendLine(line)
                if (inBody && depth == 0) break
            }
        }

        val skipNames = setOf(
            "Box", "Column", "Row", "LazyColumn", "Scaffold", "Surface", "Text", "Button",
            "Spacer", "Image", "Card", "remember", "LaunchedEffect", "AppTheme", "MaterialTheme",
            "CompositionLocalProvider", "ComponentActivity"
        )
        val calls = Regex("""(?<![.\w])([A-Z][a-zA-Z0-9]+)\s*\(""")
            .findAll(body).map { it.groupValues[1] }.filter { it !in skipNames }.toList()
        if (calls.isEmpty()) return null
        val screenLower = screenId.lowercase()
        return calls.firstOrNull { it.lowercase().contains(screenLower) } ?: calls.first()
    }
}
