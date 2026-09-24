package ru.keegoo.companion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// --- palette (from landing: #6B5CE7 primary, #F7F6FF bg) ---
val Primary      = Color(0xFF6B5CE7)
val PrimaryLight = Color(0xFF9B8AFF)
val PrimaryFaint = Color(0xFFEDE9FF)
val BgLight      = Color(0xFFF7F6FF)
val SurfaceLight = Color(0xFFFFFFFF)
val TextMain     = Color(0xFF1A1525)
val TextMuted    = Color(0xFF6E6A85)
val Outline      = Color(0xFFDDD9F8)

val FeelingOk   = Color(0xFF4CAF82)
val FeelingMeh  = Color(0xFFFFB347)
val FeelingHard = Color(0xFFE05C5C)

private val LightScheme = lightColorScheme(
    primary              = Primary,
    onPrimary            = Color.White,
    primaryContainer     = PrimaryFaint,
    onPrimaryContainer   = Color(0xFF1A0559),
    background           = BgLight,
    onBackground         = TextMain,
    surface              = SurfaceLight,
    onSurface            = TextMain,
    surfaceVariant       = Color(0xFFF0EEFF),
    onSurfaceVariant     = TextMuted,
    outline              = Outline,
    secondary            = PrimaryLight,
    onSecondary          = Color.White,
    secondaryContainer   = PrimaryFaint,
    onSecondaryContainer = TextMain,
)

private val DarkScheme = darkColorScheme(
    primary              = PrimaryLight,
    onPrimary            = Color(0xFF1A1A2E),
    primaryContainer     = Color(0xFF3A2F8A),
    onPrimaryContainer   = PrimaryFaint,
    background           = Color(0xFF121018),
    onBackground         = Color(0xFFE8E6F8),
    surface              = Color(0xFF1E1C2E),
    onSurface            = Color(0xFFE8E6F8),
    surfaceVariant       = Color(0xFF2A2740),
    onSurfaceVariant     = Color(0xFFAEA8D0),
    outline              = Color(0xFF3D3860),
)

@Composable
fun CompanionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
