package com.keymusicman.appflower

import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main(args: Array<String>) = application {
    val projectPath = if (args.isNotEmpty()) args[0] else ""
    
    Window(
        onCloseRequest = ::exitApplication,
        title = "Navigation Graph Visualizer",
        state = androidx.compose.ui.window.WindowState(
            position = androidx.compose.ui.window.WindowPosition.Aligned(Alignment.Center),
            placement = androidx.compose.ui.window.WindowPlacement.Maximized,
        )
    ) {
        App()
    }
}