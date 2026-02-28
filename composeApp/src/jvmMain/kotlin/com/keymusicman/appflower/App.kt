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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import com.keymusicman.appflower.model.Graph
import com.keymusicman.appflower.ui.GraphVisualizer
import com.keymusicman.appflower.utils.GraphLoader
import com.keymusicman.appflower.utils.exportGraphAsImage
import com.keymusicman.appflower.viewmodel.GraphViewModel

@Composable
fun App() {
    var projectPath by remember { mutableStateOf("/Users/keymusicman/example/android") }
    // use ViewModel to build and hold graph
    val viewModel = remember { GraphViewModel() }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var graph by remember { mutableStateOf<Graph?>(null) }

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
                                val appGraphV2 = GraphLoader.loadGraphFromProject(projectPath)
                                if (appGraphV2 != null) {
                                    // delegate to ViewModel to build the Graph and expose it
                                    viewModel.buildFromAppGraphV2(appGraphV2, projectPath)
                                    graph = viewModel.graphState.value
                                    val subgraphCount = appGraphV2.subgraphs.size
                                    val totalScreens = appGraphV2.subgraphs.values.sumOf { it.screens.size }
                                    val totalConnections = appGraphV2.subgraphs.values.sumOf { it.connections.size }
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
                            graph = null
                            errorMessage = ""
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear")
                    }
                }

                Row {
                    // zoom controls - simple buttons
                    androidx.compose.material3.Button(onClick = { viewModel.zoom(1.2f) }) {
                        Text("+")
                    }
                    androidx.compose.material3.Button(onClick = { viewModel.zoom(1f / 1.2f) }) {
                        Text("-")
                    }
                }

                Button(
                    onClick = { exportGraphAsImage(graph!!, projectPath) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = graph != null
                ) {
                    Text("Save as Image")
                }

                if (errorMessage.isNotEmpty()) {
                    Text(
                        errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (errorMessage.contains("Failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                    )
                }

                HorizontalDivider()

                if (graph != null) {
                    Text(
                        "Nodes: ${graph!!.nodes.size}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Edges: ${graph!!.edges.size}",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Text("Nodes List:", style = MaterialTheme.typography.labelMedium)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        graph!!.nodes.forEach { node ->
                            Text(
                                node.id,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            VerticalDivider()

            GraphVisualizer(
                graph,
                modifier = Modifier
                    .clipToBounds()
                    .weight(1f),
                viewModel = viewModel
            )
        }
    }
}
