package com.keymusicman.sample.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.keymusicman.graph.AppFlowScreen
import com.keymusicman.graph.AppFlowTransition

@Composable
fun WelcomeScreen(onGetStarted: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Welcome", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(16.dp))
        Text("Get started by creating an account.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onGetStarted) { Text("Get Started") }
    }
}

@AppFlowScreen(subgraph = "onboarding", id = "Welcome", isRoot = true)
@AppFlowTransition(toSubgraph = "onboarding", toScreen = "Login", trigger = "cta_tap")
@Preview(name = "Default", showBackground = true)
@Composable
private fun WelcomePreview() {
    MaterialTheme { Surface { WelcomeScreen() } }
}

@Preview(name = "Dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WelcomeDarkPreview() {
    MaterialTheme { Surface { WelcomeScreen() } }
}

@Preview(name = "Large font", showBackground = true, fontScale = 1.5f)
@Composable
private fun WelcomeLargeFontPreview() {
    MaterialTheme { Surface { WelcomeScreen() } }
}
