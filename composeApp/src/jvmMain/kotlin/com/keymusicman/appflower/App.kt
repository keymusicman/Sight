package com.keymusicman.appflower

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import com.keymusicman.appflower.model.AppGraph
import com.keymusicman.appflower.ui.GraphVisualizer
import com.keymusicman.appflower.loader.GraphLoader
import com.keymusicman.appflower.utils.exportGraphAsDrawio
import com.keymusicman.appflower.utils.exportGraphAsImage
import com.keymusicman.appflower.viewmodel.GraphViewModel
import kotlinx.coroutines.launch

@Composable
fun App() {
    var projectPath by remember { mutableStateOf("/Users/keymusicman/example/android") }
    // use ViewModel to build and hold layout graph
    val viewModel = remember { GraphViewModel() }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var appGraph by remember { mutableStateOf<AppGraph?>(null) }
    val coroutineScope = rememberCoroutineScope()

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

                OutlinedTextField(
                    value = projectPath,
                    onValueChange = { projectPath = it },
                    label = { Text("Project Path") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (projectPath.isNotEmpty()) {
                                isLoading = true
                                errorMessage = ""
                                val loadedAppGraph = GraphLoader.loadGraphFromProject(projectPath)
                                if (loadedAppGraph != null) {
                                    appGraph = loadedAppGraph
                                    // ViewModel uses coroutines internally to build layout on IO dispatcher
                                    viewModel.buildFromAppGraphV2(loadedAppGraph, projectPath)
                                    val subgraphCount = loadedAppGraph.subgraphs.size
                                    val totalScreens = loadedAppGraph.subgraphs.values.sumOf { it.screens.size }
                                    val totalConnections = loadedAppGraph.subgraphs.values.sumOf { it.connections.size }
                                    errorMessage =
                                        "Graph loaded: $subgraphCount subgraphs, $totalScreens screens, $totalConnections connections"
                                } else {
                                    errorMessage = "Failed to load graph"
                                }
                                isLoading = false
                            } else {
                                errorMessage = "Please enter a project path"
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading
                    ) {
                        Text("Load")
                    }

                    Button(
                        onClick = {
                            projectPath = ""
                            appGraph = null
                            errorMessage = ""
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear")
                    }
                }

                Button(
                    onClick = {
                        val graph = appGraph
                        if (graph != null) {
                            coroutineScope.launch {
                                exportGraphAsImage(graph, projectPath)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = appGraph != null
                ) {
                    Text("Save as Image")
                }

                Button(
                    onClick = {
                        val graph = appGraph
                        if (graph != null) {
                            coroutineScope.launch {
                                exportGraphAsDrawio(graph, projectPath)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = appGraph != null
                ) {
                    Text("Export to draw.io")
                }

                if (errorMessage.isNotEmpty()) {
                    Text(
                        errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (errorMessage.contains("Failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                    )
                }

                HorizontalDivider()

                if (appGraph != null) {
                    val graph = appGraph!!
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
