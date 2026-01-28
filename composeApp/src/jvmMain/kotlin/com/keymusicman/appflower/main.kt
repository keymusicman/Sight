package com.keymusicman.appflower

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main(args: Array<String>) = application {
    val projectPath = if (args.isNotEmpty()) args[0] else ""
    
    Window(
        onCloseRequest = ::exitApplication,
        title = "Navigation Graph Visualizer",
    ) {
        App()
    }
}