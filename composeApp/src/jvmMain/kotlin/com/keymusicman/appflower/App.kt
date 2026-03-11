package com.keymusicman.appflower

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.ui.GraphVisualizer
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
    onClose: () -> Unit
) {
    val viewModel = remember { GraphViewModel() }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var appGraph by remember { mutableStateOf<AppGraph?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val projectPath = remember(graphFile) { deriveProjectPath(graphFile) }

    LaunchedEffect(graphFile) {
        isLoading = true
        statusMessage = ""
        appGraph = null
        val loaded = withContext(Dispatchers.IO) { GraphLoader.loadFromFile(graphFile) }
        if (loaded != null) {
            appGraph = loaded
            viewModel.buildFromAppGraphV2(loaded, projectPath)
            val subgraphCount = loaded.subgraphs.size
            val totalScreens = loaded.subgraphs.values.sumOf { it.screens.size }
            val totalConnections = loaded.subgraphs.values.sumOf { it.connections.size }
            statusMessage = "Loaded: $subgraphCount subgraphs, $totalScreens screens, $totalConnections connections"
        } else {
            statusMessage = "Failed to load graph"
        }
        isLoading = false
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
                    val graph = appGraph ?: return@Item
                    coroutineScope.launch {
                        exportGraphAsImage(viewModel.applySelectedStates(graph), projectPath)
                    }
                }
                Item("Export to draw.io", enabled = appGraph != null) {
                    val graph = appGraph ?: return@Item
                    coroutineScope.launch {
                        exportGraphAsDrawio(viewModel.applySelectedStates(graph), projectPath)
                    }
                }
                Item("Prepare ZIP for Web", enabled = appGraph != null) {
                    val graph = appGraph ?: return@Item
                    coroutineScope.launch {
                        statusMessage = try {
                            prepareGraphZipArchive(viewModel.applySelectedStates(graph), projectPath)
                        } catch (e: Exception) {
                            "Failed to prepare ZIP: ${e.message}"
                        }
                    }
                }
            }
        }
    }

    MaterialTheme {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Navigation Graph Visualizer", style = MaterialTheme.typography.headlineSmall)

                if (isLoading) {
                    Text("Loading…", style = MaterialTheme.typography.bodySmall)
                }

                if (statusMessage.isNotEmpty()) {
                    Text(
                        statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (statusMessage.startsWith("Failed"))
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.tertiary
                    )
                }

                HorizontalDivider()

                appGraph?.let { graph ->
                    Text(
                        "Subgraphs: ${graph.subgraphs.size}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Screens: ${graph.subgraphs.values.sumOf { it.screens.size }}",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Text("Subgraphs List:", style = MaterialTheme.typography.labelMedium)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        graph.subgraphs.values.forEach { subgraph ->
                            Text(
                                subgraph.key,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            VerticalDivider()

            GraphVisualizer(
                modifier = Modifier
                    .clipToBounds()
                    .weight(1f),
                viewModel = viewModel
            )
        }
    }
}
