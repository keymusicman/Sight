package com.keymusicman.sight.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.keymusicman.sight.model.AppGraph
import com.keymusicman.sight.model.Screen

object SourceNavigator {

    private val log = Logger.getInstance(SourceNavigator::class.java)

    fun navigateToSource(
        project: Project,
        nodeId: String,
        appGraph: AppGraph,
        projectRootPath: String,
    ) {
        val screen = findScreen(appGraph, nodeId) ?: run {
            log.warn("navigateToSource: no screen found for nodeId=$nodeId")
            return
        }
        val screenId = nodeId.substringAfter(':')
        val composableName = screen.composable_fqn.substringAfterLast('.').takeIf { it.isNotBlank() } ?: run {
            log.warn("navigateToSource: blank composable name for nodeId=$nodeId, fqn=${screen.composable_fqn}")
            return
        }

        val base = screen.module_path.ifBlank { projectRootPath }
        val locationPath = "$base/${screen.location}"
        log.info("navigateToSource: nodeId=$nodeId fqn=${screen.composable_fqn} location=${screen.location} base=$base resolvedPath=$locationPath")

        val screenshotVFile = resolveVirtualFile(locationPath)
        if (screenshotVFile == null) {
            // Cross-module: location is relative to a different module directory.
            // Fall back to project-wide search for the composable function.
            log.warn("navigateToSource: could not resolve location file at $locationPath — searching project for $composableName")
            val result = findComposableFile(project, composableName, "")
            if (result != null) {
                log.info("navigateToSource: found $composableName at ${result.first.path}:${result.second}")
                navigateToLine(project, result.first, result.second)
            } else {
                log.warn("navigateToSource: $composableName not found in project")
            }
            return
        }

        val screenshotLine = findFunctionLine(screenshotVFile, composableName)
        log.info("navigateToSource: resolved file=${screenshotVFile.path} line=$screenshotLine")

        val candidate = extractComposableCandidate(screenshotVFile, composableName, screenId)
        if (candidate != null) {
            val composableResult = findComposableFile(project, candidate, screenshotVFile.path)
            if (composableResult != null) {
                log.info("navigateToSource: navigating to composable candidate=$candidate at ${composableResult.first.path}:${composableResult.second}")
                navigateToLine(project, composableResult.first, composableResult.second)
                return
            }
        }
        // Fallback: open the location file at the function line
        log.info("navigateToSource: falling back to location file at line ${screenshotLine ?: 0}")
        navigateToLine(project, screenshotVFile, screenshotLine ?: 0)
    }

    // ── Screen lookup ─────────────────────────────────────────────────────────

    private fun findScreen(appGraph: AppGraph, nodeId: String): Screen? {
        val colon = nodeId.indexOf(':')
        if (colon < 0) return null
        val subgraphKey = nodeId.substring(0, colon)
        val screenId = nodeId.substring(colon + 1)
        return appGraph.subgraphs[subgraphKey]?.screens?.find { it.id == screenId }
    }

    // ── VirtualFile resolution ────────────────────────────────────────────────

    private fun resolveVirtualFile(absolutePath: String): VirtualFile? =
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(java.io.File(absolutePath))

    // ── Text-based function line finder ───────────────────────────────────────

    private fun findFunctionLine(vFile: VirtualFile, functionName: String): Int? {
        val lines = String(vFile.contentsToByteArray(), Charsets.UTF_8).lines()
        val pattern = Regex("""fun\s+${Regex.escape(functionName)}\s*[\(<]""")
        return lines.indexOfFirst { pattern.containsMatchIn(it) }.takeIf { it >= 0 }
    }

    // ── Hard task: extract composable candidate from screenshot function body ──

    private val LAYOUT_PRIMITIVES = setOf(
        "Box", "Column", "Row", "LazyColumn", "Scaffold",
        "Surface", "Text", "Button", "Spacer", "Image", "Card", "remember", "LaunchedEffect"
    )

    private fun extractComposableCandidate(
        vFile: VirtualFile,
        fnName: String,
        screenId: String,
    ): String? {
        val lines = String(vFile.contentsToByteArray(), Charsets.UTF_8).lines()
        val startLine = lines.indexOfFirst {
            Regex("""fun\s+${Regex.escape(fnName)}\s*[\(<]""").containsMatchIn(it)
        }.takeIf { it >= 0 } ?: return null
        val body = extractFunctionBody(lines, startLine) ?: return null
        val calls = Regex("""(?<![.\w])([A-Z][a-zA-Z0-9]+)\s*\(""")
            .findAll(body).map { it.groupValues[1] }.filter { it !in LAYOUT_PRIMITIVES }.toList()
        if (calls.isEmpty()) return null
        val screenIdLower = screenId.lowercase()
        return calls.firstOrNull { it.lowercase().contains(screenIdLower) } ?: calls.first()
    }

    private fun extractFunctionBody(lines: List<String>, startLine: Int): String? {
        val sb = StringBuilder()
        var depth = 0
        var inBody = false
        for (i in startLine until lines.size) {
            val line = lines[i]
            for (ch in line) {
                if (ch == '{') { depth++; inBody = true } else if (ch == '}') depth--
            }
            sb.appendLine(line)
            if (inBody && depth == 0) break
        }
        return if (inBody) sb.toString() else null
    }

    // ── Hard task: find composable across project ─────────────────────────────

    private fun findComposableFile(
        project: Project,
        composableName: String,
        excludePath: String,
    ): Pair<VirtualFile, Int>? {
        var result: Pair<VirtualFile, Int>? = null
        PsiSearchHelper.getInstance(project).processElementsWithWord(
            { element, _ ->
                val vFile = element.containingFile?.virtualFile ?: return@processElementsWithWord true
                if (vFile.extension != "kt" || vFile.path == excludePath) return@processElementsWithWord true
                val line = findFunctionLine(vFile, composableName)
                if (line != null) { result = vFile to line; false } else true
            },
            GlobalSearchScope.projectScope(project),
            composableName,
            UsageSearchContext.IN_CODE,
            true
        )
        return result
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun navigateToLine(project: Project, vFile: VirtualFile, line: Int) {
        ApplicationManager.getApplication().invokeLater {
            OpenFileDescriptor(project, vFile, line, 0).navigate(true)
        }
    }
}
