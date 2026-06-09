package io.github.keymusicman.sight.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class AppColors(
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val primary: Color,
    val labelBackground: Color,
    val stateSelected: Color,
    val nodeSelected: Color,
    val panelBackground: Color,
    val panelText: Color,
    val muted: Color,
    val hover: Color,
    val subgraphRow: Color,
    val divider: Color,
    val edgeDefault: Color,
    val edgeHover: Color,
    val imagePlaceholder: Color,
)

fun lightAppColors() = AppColors(
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF212121),
    surface = Color(0xFFFFFFFF),
    primary = Color(0xFF2196F3),
    labelBackground = Color(0xE6F5F5F5),
    stateSelected = Color(0xFFFFB3B3),
    nodeSelected = Color(0xFF2196F3),
    panelBackground = Color(0xFFF8F8F8),
    panelText = Color(0xFF212121),
    muted = Color(0xFF888888),
    hover = Color(0xFFE3F2FD),
    subgraphRow = Color(0xFFEEEEEE),
    divider = Color(0xFFDDDDDD),
    edgeDefault = Color(0x66888888),
    edgeHover = Color(0xFF2196F3),
    imagePlaceholder = Color(0xFFF0F0F0),
)

fun darkAppColors() = AppColors(
    background = Color(0xFF1E1E1E),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF2D2D2D),
    primary = Color(0xFF42A5F5),
    labelBackground = Color(0xE61E1E1E),
    stateSelected = Color(0xFFCF6679),
    nodeSelected = Color(0xFF42A5F5),
    panelBackground = Color(0xFF252525),
    panelText = Color(0xFFE0E0E0),
    muted = Color(0xFF9E9E9E),
    hover = Color(0xFF1A3A5C),
    subgraphRow = Color(0xFF2A2A2A),
    divider = Color(0xFF444444),
    edgeDefault = Color(0x66AAAAAA),
    edgeHover = Color(0xFF42A5F5),
    imagePlaceholder = Color(0xFF3A3A3A),
)

val LocalAppColors = staticCompositionLocalOf<AppColors> { lightAppColors() }

@Composable
fun AppTheme(isDark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAppColors provides if (isDark) darkAppColors() else lightAppColors(),
        content = content
    )
}
