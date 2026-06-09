package io.github.keymusicman.sample.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
fun ProfileScreen(
    userName: String = "User",
    email: String = "user@example.com",
    onLogOut: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Surface(
            modifier = Modifier.size(80.dp).clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
        ) { }
        Spacer(Modifier.height(16.dp))
        Text(userName, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onLogOut,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) { Text("Log Out") }
    }
}

@SightScreen(subgraph = "profile", id = "Profile")
@Preview(name = "Default", showBackground = true)
@Composable
private fun ProfilePreview() {
    MaterialTheme { Surface { ProfileScreen() } }
}

@Preview(name = "Long email", showBackground = true)
@Composable
private fun ProfileLongEmailPreview() {
    MaterialTheme { Surface { ProfileScreen(email = "christopher.maximilian.johnson@organization.com") } }
}

@Preview(name = "Dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ProfileDarkPreview() {
    MaterialTheme { Surface { ProfileScreen() } }
}
