package com.keymusicman.appflower

import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Message
import co.touchlab.kermit.MessageStringFormatter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.Tag
import co.touchlab.kermit.platformLogWriter
import kotlin.time.Clock

fun main(args: Array<String>) = application {
    val projectPath = if (args.isNotEmpty()) args[0] else ""

    Logger.setLogWriters(platformLogWriter(object : MessageStringFormatter {
        override fun formatMessage(severity: Severity?, tag: Tag?, message: Message): String {
            // Optimize for Android
            if (severity == null && tag == null)
                return message.message

            val sb = StringBuilder()
            sb.append(Clock.System.now().toString()).append(" ")
            if (severity != null) sb.append(formatSeverity(severity))
                .append(" ")
            if (tag != null && tag.tag.isNotEmpty()) sb.append(formatTag(tag))
                .append(" ")
            sb.append(message.message)

            return sb.toString()
        }
    }))

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