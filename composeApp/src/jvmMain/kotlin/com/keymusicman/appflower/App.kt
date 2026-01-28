package com.keymusicman.appflower

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.keymusicman.appflower.model.Graph
import com.keymusicman.appflower.ui.GraphVisualizer
import com.keymusicman.appflower.utils.GraphLoader

@Composable
fun App() {
    var projectPath by remember { mutableStateOf("/Users/keymusicman/example/android/app") }
    var graph by remember { mutableStateOf<Graph?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val zoomState = remember { mutableStateOf(1f) }

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
                                val appGraph = GraphLoader.loadGraphFromProject(projectPath)
                                if (appGraph != null) {
                                    graph = Graph.from(appGraph, projectPath)
                                    errorMessage =
                                        "Graph loaded: ${appGraph.transitions.size} transitions"
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
                    androidx.compose.material3.Button(onClick = { zoomState.value *= 1.2f }) {
                        Text("+")
                    }
                    androidx.compose.material3.Button(onClick = { zoomState.value /= 1.2f }) {
                        Text("-")
                    }
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
                modifier = Modifier.weight(1f),
                zoomState = zoomState
            )
        }
    }
}
