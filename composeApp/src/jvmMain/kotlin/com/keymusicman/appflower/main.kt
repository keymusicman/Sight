package com.keymusicman.appflower

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Message
import co.touchlab.kermit.MessageStringFormatter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.Tag
import co.touchlab.kermit.platformLogWriter
import com.keymusicman.appflower.recents.RecentGraphsManager
import com.keymusicman.appflower.recents.deriveDisplayName
import com.keymusicman.appflower.utils.openGraphFilePicker
import java.io.File
import kotlin.time.Clock

fun main(args: Array<String>) {
    configureLogger()

    // Resolve initial file: CLI arg → last recent → null (show welcome)
    val initialFile: File? = when {
        args.isNotEmpty() -> File(args[0]).takeIf { it.exists() }
        else -> RecentGraphsManager.getLast()?.let { File(it.path) }?.takeIf { it.exists() }
    }

    application {
        var recents by remember { mutableStateOf(RecentGraphsManager.getAll()) }
        var currentFile by remember { mutableStateOf(initialFile) }

        fun openFile(file: File) {
            RecentGraphsManager.add(file)
            recents = RecentGraphsManager.getAll()
            currentFile = file
        }

        if (currentFile == null) {
            WelcomeWindow(
                recents = recents,
                onOpenFile = { openFile(it) },
                onExit = ::exitApplication
            )
        } else {
            val graphFile = currentFile!!
            val projectName = remember(graphFile) { deriveDisplayName(graphFile) }

            Window(
                onCloseRequest = ::exitApplication,
                title = "AppFlower – $projectName",
                state = WindowState(
                    position = WindowPosition.Aligned(Alignment.Center),
                    placement = WindowPlacement.Maximized
                )
            ) {
                MenuBar {
                    Menu("File") {
                        Item(
                            "Open…",
                            shortcut = androidx.compose.ui.input.key.KeyShortcut(Key.O, meta = true)
                        ) {
                            openGraphFilePicker()?.let { openFile(it) }
                        }
                        Separator()
                        Menu("Recent Graphs") {
                            if (recents.isEmpty()) {
                                Item("No recent graphs", enabled = false, onClick = {})
                            } else {
                                recents.forEach { recent ->
                                    Item(recent.displayName) {
                                        val file = File(recent.path)
                                        if (file.exists()) openFile(file)
                                    }
                                }
                            }
                        }
                    }
                }

                App(graphFile = graphFile)
            }
        }
    }
}

private fun configureLogger() {
    Logger.setLogWriters(platformLogWriter(object : MessageStringFormatter {
        override fun formatMessage(severity: Severity?, tag: Tag?, message: Message): String {
            if (severity == null && tag == null) return message.message
            val sb = StringBuilder()
            sb.append(Clock.System.now().toString()).append(" ")
            if (severity != null) sb.append(formatSeverity(severity)).append(" ")
            if (tag != null && tag.tag.isNotEmpty()) sb.append(formatTag(tag)).append(" ")
            sb.append(message.message)
            return sb.toString()
        }
    }))
}
