package ru.keegoo.companion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9B8AFF),
    onPrimary = Color(0xFF1A1A2E),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E2E),
    onBackground = Color(0xFFE8E8F0),
    onSurface = Color(0xFFE8E8F0),
)

@Composable
fun CompanionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content,
    )
}
