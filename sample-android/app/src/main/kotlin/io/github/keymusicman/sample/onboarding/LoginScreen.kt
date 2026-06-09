package io.github.keymusicman.sample.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.keymusicman.sight.SightScreen

@Composable
fun LoginScreen(
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onLogin: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Sign In", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(value = "", onValueChange = {}, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = "", onValueChange = {}, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
        errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("Sign In") }
        }
    }
}

@SightScreen(subgraph = "onboarding", id = "Login")
@Preview(name = "Default", showBackground = true)
@Composable
private fun LoginPreview() {
    MaterialTheme { Surface { LoginScreen() } }
}

@Preview(name = "Loading", showBackground = true)
@Composable
private fun LoginLoadingPreview() {
    MaterialTheme { Surface { LoginScreen(isLoading = true) } }
}

@Preview(name = "Error", showBackground = true)
@Composable
private fun LoginErrorPreview() {
    MaterialTheme { Surface { LoginScreen(errorMessage = "Invalid email or password") } }
}
