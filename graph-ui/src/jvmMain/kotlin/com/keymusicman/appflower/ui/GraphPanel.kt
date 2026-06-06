package com.keymusicman.appflower.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import com.keymusicman.appflower.viewmodel.GraphViewModel

@Composable
fun GraphPanel(
    viewModel: GraphViewModel,
    modifier: Modifier = Modifier,
    onViewSource: ((nodeId: String) -> Unit)? = null,
    onRefreshNode: ((nodeId: String) -> Unit)? = null,
    onNodeContextMenu: ((NodeContextMenuRequest) -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    Row(modifier) {
        GraphNavigationPanel(viewModel, Modifier.width(240.dp).fillMaxHeight())
        // Vertical divider
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(colors.divider)
        )
        GraphVisualizer(
            viewModel = viewModel,
            modifier = Modifier.weight(1f).fillMaxSize().clipToBounds(),
            onViewSource = onViewSource,
            onRefreshNode = onRefreshNode,
            onNodeContextMenu = onNodeContextMenu,
        )
    }
}
