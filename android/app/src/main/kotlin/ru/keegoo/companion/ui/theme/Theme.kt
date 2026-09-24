package ru.keegoo.companion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Brand palette (landing: #6B5CE7 primary, #F7F6FF bg) ──────────────────────
val Primary      = Color(0xFF6B5CE7)
val PrimaryLight = Color(0xFF9B8AFF)
val PrimaryFaint = Color(0xFFEDE9FF)
val BgLight      = Color(0xFFF7F6FF)
val SurfaceLight = Color(0xFFFFFFFF)
val TextMain     = Color(0xFF1A1525)
val TextMuted    = Color(0xFF6E6A85)
val Outline      = Color(0xFFDDD9F8)

// ── Health semantic (Oura-pattern: color = state, separate from accent) ────────
val FeelingOk   = Color(0xFF2E8B57)   // green — "отлично"
val FeelingMeh  = Color(0xFFD97706)   // amber — "нормально"
val FeelingHard = Color(0xFFDC3545)   // red   — "тяжело"

val HealthGood   = Color(0xFF1F7A50)
val HealthGoodBg = Color(0xFFD4EDDF)
val HealthWarn   = Color(0xFFB35B00)
val HealthWarnBg = Color(0xFFFFF0DC)
val HealthBad    = Color(0xFFB22020)
val HealthBadBg  = Color(0xFFFDEAEA)

// ── Shape tokens — единый набор, нигде не отступаем ──────────────────────────
object AppShapes {
    val card     = RoundedCornerShape(16.dp)  // stat tiles, small cards
    val cardHero = RoundedCornerShape(20.dp)  // morning card, check-in block
    val button   = RoundedCornerShape(14.dp)  // filled/outlined buttons
    val chip     = RoundedCornerShape(10.dp)  // feel buttons
    val tag      = RoundedCornerShape(6.dp)   // MOCK badge, small labels
    val circle   = CircleShape
}

// ── Type scale — явно прописан, без "как получится" ──────────────────────────
// displaySmall: утреннее приветствие — это МОМЕНТ, не subtitle
// bodyLarge 16sp: текст прогноза обязателен для комфортного чтения
private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.Light,
        fontSize = 34.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
    ),
)

// ── M3 Shapes (параллельно с AppShapes — для MaterialTheme) ──────────────────
private val AppShapesM3 = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small      = RoundedCornerShape(8.dp),
    medium     = RoundedCornerShape(12.dp),
    large      = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// ── Color schemes ──────────────────────────────────────────────────────────────
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
    outlineVariant       = Color(0xFFEDE9FF),
    secondary            = PrimaryLight,
    onSecondary          = Color.White,
    secondaryContainer   = PrimaryFaint,
    onSecondaryContainer = TextMain,
)

// Tonal elevation работает автоматически в M3 dark colorScheme:
// каждый dp elevation добавляет overlay из primary-цвета на surface.
// Поэтому Card(elevation=2.dp) АВТОМАТИЧЕСКИ светлее фона в dark mode.
private val DarkScheme = darkColorScheme(
    primary              = PrimaryLight,
    onPrimary            = Color(0xFF1A1A2E),
    primaryContainer     = Color(0xFF3A2F8A),
    onPrimaryContainer   = PrimaryFaint,
    background           = Color(0xFF111019),  // чуть теплее, не #000000
    onBackground         = Color(0xFFE8E6F8),
    surface              = Color(0xFF1D1B2C),  // выше background на 1 tonal step
    onSurface            = Color(0xFFE8E6F8),
    surfaceVariant       = Color(0xFF2A2740),
    onSurfaceVariant     = Color(0xFFAEA8D0),
    outline              = Color(0xFF3D3860),
    outlineVariant       = Color(0xFF2D2B44),
)

@Composable
fun CompanionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography  = AppTypography,
        shapes      = AppShapesM3,
        content     = content,
    )
}
