package com.keymusicman.appflower.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keymusicman.appflower.viewmodel.GraphViewModel

@Composable
fun GraphNavigationPanel(
    viewModel: GraphViewModel,
    modifier: Modifier = Modifier,
) {
    val appGraph = viewModel.appGraphState.value

    val colors = LocalAppColors.current
    var searchQuery by remember { mutableStateOf("") }
    var expandedSubgraphs by remember { mutableStateOf(emptySet<String>()) }

    Column(modifier = modifier.background(colors.panelBackground)) {
        // Search field
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .background(colors.surface, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    textStyle = TextStyle(color = colors.panelText, fontSize = 13.sp),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (searchQuery.isEmpty()) {
                                BasicText(
                                    "Search\u2026",
                                    style = TextStyle(color = colors.muted, fontSize = 13.sp)
                                )
                            }
                            inner()
                        }
                    }
                )
                if (searchQuery.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    BasicText(
                        "\u00D7",
                        style = TextStyle(color = colors.muted, fontSize = 14.sp),
                        modifier = Modifier.clickable { searchQuery = "" }
                    )
                }
            }
        }

        if (appGraph == null) {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                BasicText("No graph loaded", style = TextStyle(color = colors.muted, fontSize = 12.sp))
            }
            return@Column
        }

        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                "Subgraphs",
                style = TextStyle(color = colors.panelText, fontSize = 12.sp)
            )
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .background(colors.divider, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                BasicText(
                    "${appGraph.subgraphs.size}",
                    style = TextStyle(color = colors.muted, fontSize = 11.sp)
                )
            }
        }

        // Build filtered tree
        val query = searchQuery.trim().lowercase()

        data class SubgraphEntry(
            val key: String,
            val screenIds: List<String>,  // node IDs: "$key:$screenId"
            val screenLabels: List<String>,
            val isExpanded: Boolean,
        )

        val entries: List<SubgraphEntry> = appGraph.subgraphs.entries.mapNotNull { (key, subgraph) ->
            val allScreenNodeIds = subgraph.screens.map { "$key:${it.id}" }
            val allLabels = allScreenNodeIds.map { formatNodeLabel(it) }

            if (query.isEmpty()) {
                SubgraphEntry(
                    key = key,
                    screenIds = allScreenNodeIds,
                    screenLabels = allLabels,
                    isExpanded = expandedSubgraphs.contains(key),
                )
            } else {
                val subgraphMatches = key.lowercase().contains(query)
                if (subgraphMatches) {
                    SubgraphEntry(
                        key = key,
                        screenIds = allScreenNodeIds,
                        screenLabels = allLabels,
                        isExpanded = true,
                    )
                } else {
                    val matchedIndices = allLabels.indices.filter { i ->
                        allLabels[i].lowercase().contains(query) ||
                                allScreenNodeIds[i].lowercase().contains(query)
                    }
                    if (matchedIndices.isEmpty()) null
                    else SubgraphEntry(
                        key = key,
                        screenIds = matchedIndices.map { allScreenNodeIds[it] },
                        screenLabels = matchedIndices.map { allLabels[it] },
                        isExpanded = true,
                    )
                }
            }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            entries.forEach { entry ->
                item(key = "sg_${entry.key}") {
                    SubgraphRow(
                        subgraphKey = entry.key,
                        screenCount = entry.screenIds.size,
                        isExpanded = entry.isExpanded,
                        onToggle = {
                            expandedSubgraphs = if (expandedSubgraphs.contains(entry.key))
                                expandedSubgraphs - entry.key
                            else
                                expandedSubgraphs + entry.key
                        }
                    )
                }
                if (entry.isExpanded) {
                    items(
                        items = entry.screenIds.zip(entry.screenLabels),
                        key = { (nodeId, _) -> nodeId }
                    ) { (nodeId, label) ->
                        ScreenRow(
                            label = label,
                            onClick = { viewModel.panToNode(nodeId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubgraphRow(
    subgraphKey: String,
    screenCount: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalAppColors.current
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (hovered) colors.hover else colors.subgraphRow)
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = if (isExpanded) "\u25BE" else "\u25B8",
            style = TextStyle(color = colors.muted, fontSize = 11.sp),
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        BasicText(
            text = subgraphKey,
            style = TextStyle(color = colors.panelText, fontSize = 12.sp),
            modifier = Modifier.weight(1f)
        )
        BasicText(
            text = "$screenCount",
            style = TextStyle(color = colors.muted, fontSize = 11.sp),
        )
    }
}

@Composable
private fun ScreenRow(
    label: String,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (hovered) colors.hover else Color.Transparent)
            .clickable { onClick() }
            .padding(start = 28.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = colors.panelText, fontSize = 12.sp),
        )
    }
}
