package io.github.keymusicman.sample.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.keymusicman.sight.SightScreen

@Composable
fun HomeScreen(
    userName: String = "User",
    onAvatarTap: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Hello, $userName!", style = MaterialTheme.typography.headlineMedium)
            Surface(
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onAvatarTap),
                color = MaterialTheme.colorScheme.primary,
            ) { }
        }
        Spacer(Modifier.height(24.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Dashboard", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Your content appears here.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@SightScreen(subgraph = "main", id = "Home", isRoot = true)
@Preview(name = "Default", showBackground = true)
@Composable
private fun HomePreview() {
    MaterialTheme { Surface { HomeScreen() } }
}

@Preview(name = "Long name", showBackground = true)
@Composable
private fun HomeLongNamePreview() {
    MaterialTheme { Surface { HomeScreen(userName = "Christopher Maximilian") } }
}

@Preview(name = "Dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeDarkPreview() {
    MaterialTheme { Surface { HomeScreen() } }
}
