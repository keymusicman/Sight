package com.keymusicman.appflower.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.keymusicman.appflower.viewmodel.GraphView
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
    var subgraphsExpanded by remember { mutableStateOf(true) }
    var viewsExpanded by remember { mutableStateOf(true) }
    var pendingViewType by remember { mutableStateOf<String?>(null) }
    var viewName by remember(pendingViewType) { mutableStateOf("") }
    var viewToDelete by remember { mutableStateOf<GraphView?>(null) }

    val views = viewModel.views.value
    val activeViewId = viewModel.activeViewId.value
    val selection = viewModel.selectedNodeIds.value

    Column(modifier = modifier.background(colors.panelBackground)) {
        // Search field
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .background(colors.surface, RoundedCornerShape(6.dp))
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

        // Build filtered tree
        val query = searchQuery.trim().lowercase()

        data class SubgraphEntry(
            val key: String,
            val screenIds: List<String>,
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
            // Subgraphs collapsible header
            item(key = "sg_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { subgraphsExpanded = !subgraphsExpanded }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        text = if (subgraphsExpanded) "\u25BE" else "\u25B8",
                        style = TextStyle(color = colors.muted, fontSize = 11.sp),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    BasicText(
                        "Subgraphs",
                        style = TextStyle(color = colors.panelText, fontSize = 12.sp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(colors.divider, RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        BasicText(
                            "${appGraph.subgraphs.size}",
                            style = TextStyle(color = colors.muted, fontSize = 11.sp)
                        )
                    }
                }
            }

            if (subgraphsExpanded) {
                entries.forEach { entry ->
                    item(key = "sg_${entry.key}") {
                        ContextMenuArea(items = {
                            listOf(ContextMenuItem("View this subgraph") {
                                viewModel.createSubgraphView(entry.key)
                            })
                        }) {
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

            // Divider between Subgraphs and Views
            item(key = "divider") {
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
            }

            // Views collapsible header
            item(key = "views_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewsExpanded = !viewsExpanded }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        text = if (viewsExpanded) "\u25BE" else "\u25B8",
                        style = TextStyle(color = colors.muted, fontSize = 11.sp),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    BasicText(
                        "Views",
                        style = TextStyle(color = colors.panelText, fontSize = 12.sp)
                    )
                }
            }

            if (viewsExpanded) {
                // "All" row
                item(key = "view_all") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (activeViewId == null) colors.hover else Color.Transparent)
                            .clickable { viewModel.activateView(null) }
                            .padding(start = 28.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            text = "All",
                            style = TextStyle(color = colors.panelText, fontSize = 12.sp),
                        )
                    }
                }

                // Named view rows
                items(views, key = { it.id }) { view ->
                    val isActive = activeViewId == view.id
                    ContextMenuArea(items = {
                        listOf(ContextMenuItem("Delete") { viewToDelete = view })
                    }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isActive) colors.hover else Color.Transparent)
                                .clickable { viewModel.activateView(view.id) }
                                .padding(start = 28.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(
                                text = view.name,
                                style = TextStyle(color = colors.panelText, fontSize = 12.sp),
                            )
                        }
                    }
                }
            }
        }

        // Action buttons when nodes are selected
        if (selection.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(8.dp)
            ) {
                BasicText(
                    "${selection.size} node(s) selected",
                    style = TextStyle(color = colors.muted, fontSize = 11.sp)
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.primary, RoundedCornerShape(6.dp))
                        .clickable { pendingViewType = "view" }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText("Create view", style = TextStyle(color = Color.White, fontSize = 12.sp))
                }
                if (selection.size == 2) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.primary, RoundedCornerShape(6.dp))
                            .clickable { pendingViewType = "path" }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText("Create path", style = TextStyle(color = Color.White, fontSize = 12.sp))
                    }
                }
            }
        }
    }

    // Name input dialog
    if (pendingViewType != null) {
        DialogWindow(
            onCloseRequest = { pendingViewType = null },
            onPreviewKeyEvent = {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                    pendingViewType = null
                    true
                } else false
            },
            title = if (pendingViewType == "path") "Name Path View" else "Name View",
            state = rememberDialogState(size = DpSize(380.dp, 180.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(20.dp),
            ) {
                Column {
                    BasicText(
                        "View name:",
                        style = TextStyle(color = colors.panelText, fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.panelBackground, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        BasicTextField(
                            value = viewName,
                            onValueChange = { viewName = it },
                            textStyle = TextStyle(color = colors.panelText, fontSize = 13.sp),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.align(Alignment.End)) {
                        Box(
                            modifier = Modifier
                                .background(colors.divider, RoundedCornerShape(6.dp))
                                .clickable { pendingViewType = null }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText("Cancel", style = TextStyle(color = colors.panelText, fontSize = 12.sp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(colors.primary, RoundedCornerShape(6.dp))
                                .clickable {
                                    if (viewName.isNotBlank()) {
                                        val type = pendingViewType
                                        pendingViewType = null
                                        if (type == "path") viewModel.createPathView(viewName)
                                        else viewModel.createView(viewName)
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText("Create", style = TextStyle(color = Color.White, fontSize = 12.sp))
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    val vtd = viewToDelete
    if (vtd != null) {
        DialogWindow(
            onCloseRequest = { viewToDelete = null },
            onPreviewKeyEvent = {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                    viewToDelete = null
                    true
                } else false
            },
            title = "Delete View",
            state = rememberDialogState(size = DpSize(340.dp, 160.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(20.dp),
            ) {
                Column {
                    BasicText(
                        "Delete \"${vtd.name}\"?",
                        style = TextStyle(color = colors.panelText, fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.align(Alignment.End)) {
                        Box(
                            modifier = Modifier
                                .background(colors.divider, RoundedCornerShape(6.dp))
                                .clickable { viewToDelete = null }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText("Cancel", style = TextStyle(color = colors.panelText, fontSize = 12.sp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(colors.primary, RoundedCornerShape(6.dp))
                                .clickable {
                                    viewModel.deleteView(vtd.id)
                                    viewToDelete = null
                                }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText("Delete", style = TextStyle(color = Color.White, fontSize = 12.sp))
                        }
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
