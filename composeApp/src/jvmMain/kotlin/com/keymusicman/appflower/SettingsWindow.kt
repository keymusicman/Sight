package com.keymusicman.appflower

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.keymusicman.appflower.settings.ThemePreference
import com.keymusicman.appflower.ui.LocalAppColors

@Composable
fun SettingsWindow(
    current: ThemePreference,
    onSelect: (ThemePreference) -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalAppColors.current
    DialogWindow(
        onCloseRequest = onClose,
        title = "Settings",
        state = rememberDialogState(size = DpSize(320.dp, 200.dp)),
        resizable = false,
    ) {
        Column(
            modifier = Modifier
                .background(colors.surface)
                .padding(24.dp)
        ) {
            BasicText("Theme", style = TextStyle(color = colors.onBackground, fontSize = 14.sp))
            Spacer(Modifier.height(16.dp))
            ThemePreference.entries.forEach { pref ->
                ThemeOption(
                    label = pref.name.lowercase()
                        .replaceFirstChar { it.uppercase() },
                    selected = pref == current,
                    onClick = { onSelect(pref) },
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Radio indicator
        val outerColor = if (selected) colors.primary else colors.muted
        Row(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(outerColor),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Spacer(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .align(Alignment.CenterVertically)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        BasicText(label, style = TextStyle(color = colors.onBackground, fontSize = 13.sp))
    }
}
