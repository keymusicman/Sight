package com.keymusicman.appflower

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import com.keymusicman.appflower.loader.GraphLoader
import com.keymusicman.appflower.recents.RecentGraph
import com.keymusicman.appflower.utils.openGraphFilePicker
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun WelcomeWindow(
    recents: List<RecentGraph>,
    onOpenFile: (File) -> Unit,
    onExit: () -> Unit
) {
    Window(
        onCloseRequest = onExit,
        title = "Navigation Graph Visualizer",
        state = WindowState(
            position = WindowPosition.Aligned(Alignment.Center),
            width = 560.dp,
            height = 480.dp
        ),
        resizable = false
    ) {
        MaterialTheme {
            WelcomeContent(recents = recents, onOpenFile = onOpenFile)
        }
    }
}

@Composable
private fun WelcomeContent(
    recents: List<RecentGraph>,
    onOpenFile: (File) -> Unit
) {
    var pastedPath by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Navigation Graph Visualizer", style = MaterialTheme.typography.headlineMedium)

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    openGraphFilePicker()?.let { onOpenFile(it) }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Graph…")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = pastedPath,
                    onValueChange = { pastedPath = it; errorMessage = "" },
                    label = { Text("Paste path") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = {
                        val file = resolveGraphFile(pastedPath.trim())
                        if (file != null) {
                            onOpenFile(file)
                        } else {
                            errorMessage = "app-graph.json not found at that path"
                        }
                    },
                    enabled = pastedPath.isNotBlank()
                ) {
                    Text("Open")
                }
            }

            if (errorMessage.isNotEmpty()) {
                Text(
                    errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (recents.isNotEmpty()) {
                HorizontalDivider()
                Text("Recent", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(recents) { recent ->
                        RecentItem(recent = recent, onClick = {
                            val file = File(recent.path)
                            if (file.exists()) onOpenFile(file)
                            else errorMessage = "File no longer exists: ${recent.path}"
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentItem(recent: RecentGraph, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    recent.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    recent.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(16.dp))
            Text(
                formatRelativeTime(recent.lastOpened),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Resolves an app-graph.json File from a user-provided path (file or project directory). */
fun resolveGraphFile(path: String): File? {
    if (path.isBlank()) return null
    val file = File(path)
    return when {
        file.isFile && file.name.endsWith(".json") -> file
        file.isDirectory -> GraphLoader.findGraphFile(path)
        else -> null
    }
}

private fun formatRelativeTime(epochMillis: Long): String {
    val ageMs = System.currentTimeMillis() - epochMillis
    return when {
        ageMs < 60_000 -> "just now"
        ageMs < 3_600_000 -> "${ageMs / 60_000}m ago"
        ageMs < 86_400_000 -> "${ageMs / 3_600_000}h ago"
        ageMs < 7 * 86_400_000L -> "${ageMs / 86_400_000}d ago"
        else -> {
            val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
            formatter.format(Instant.ofEpochMilli(epochMillis))
        }
    }
}
