package com.keymusicman.appflower

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.settings.ThemePreference
import com.keymusicman.appflower.ui.AppTheme
import com.keymusicman.appflower.ui.GraphPanel
import com.keymusicman.appflower.loader.GraphLoader
import com.keymusicman.appflower.recents.RecentGraph
import com.keymusicman.appflower.recents.deriveProjectPath
import com.keymusicman.appflower.utils.exportGraphAsDrawio
import com.keymusicman.appflower.utils.exportGraphAsImage
import com.keymusicman.appflower.utils.openGraphFilePicker
import com.keymusicman.appflower.utils.prepareGraphZipArchive
import com.keymusicman.appflower.viewmodel.GraphViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun FrameWindowScope.App(
    graphFile: File,
    recents: List<RecentGraph>,
    onOpenFile: (File) -> Unit,
    onClose: () -> Unit,
    isDark: Boolean,
    themePreference: ThemePreference,
    onThemeChange: (ThemePreference) -> Unit,
) {
    val viewModel = remember { GraphViewModel() }
    var appGraph by remember { mutableStateOf<AppGraph?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val projectPath = remember(graphFile) { deriveProjectPath(graphFile) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(graphFile) {
        appGraph = null
        val loaded = withContext(Dispatchers.IO) { GraphLoader.loadFromFile(graphFile) }
        if (loaded != null) {
            appGraph = loaded
            viewModel.buildFromAppGraphV2(loaded, projectPath)
        }
    }

    MenuBar {
        Menu("File") {
            Item("Open…", shortcut = KeyShortcut(Key.O, meta = true)) {
                openGraphFilePicker()?.let { onOpenFile(it) }
            }
            Item("Close", shortcut = KeyShortcut(Key.W, meta = true)) {
                onClose()
            }
            Separator()
            Item("Settings…") { showSettings = true }
            Separator()
            Menu("Recent Graphs") {
                if (recents.isEmpty()) {
                    Item("No recent graphs", enabled = false, onClick = {})
                } else {
                    recents.forEach { recent ->
                        Item(recent.displayName) {
                            val file = File(recent.path)
                            if (file.exists()) onOpenFile(file)
                        }
                    }
                }
            }
            Separator()
            Menu("Export") {
                Item("Save as Image", enabled = appGraph != null) {
                    coroutineScope.launch {
                        val graph = viewModel.appGraphForExport() ?: return@launch
                        exportGraphAsImage(graph, projectPath)
                    }
                }
                Item("Export to draw.io", enabled = appGraph != null) {
                    coroutineScope.launch {
                        val graph = viewModel.appGraphForExport() ?: return@launch
                        exportGraphAsDrawio(graph, projectPath)
                    }
                }
                Item("Prepare ZIP for Web", enabled = appGraph != null) {
                    coroutineScope.launch {
                        try {
                            val graph = viewModel.appGraphForExport() ?: return@launch
                            prepareGraphZipArchive(graph, projectPath)
                        } catch (_: Exception) { }
                    }
                }
            }
        }
    }

    AppTheme(isDark = isDark) {
        MaterialTheme {
            GraphPanel(viewModel = viewModel, modifier = Modifier.fillMaxSize())
        }
        if (showSettings) {
            SettingsWindow(
                current = themePreference,
                onSelect = { pref ->
                    onThemeChange(pref)
                    showSettings = false
                },
                onClose = { showSettings = false },
            )
        }
    }
}
