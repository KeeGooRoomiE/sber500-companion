package ru.keegoo.companion.ui.motion

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import ru.keegoo.companion.domain.model.DayFeel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ─── Companion orb ────────────────────────────────────────────────────────────
// The app's one recurring "hero": big in onboarding, the avatar on Home.
// Replaces emoji (they render differently on every OEM) and changes form per step.

enum class OrbMode { Calm, Data, Ping, Thinking }

private data class OrbPalette(val light: Color, val mid: Color, val deep: Color)

private fun DayFeel?.orbPalette(): OrbPalette = when (this) {
    DayFeel.OK   -> OrbPalette(Color(0xFF7FE0B8), Color(0xFF2E9E6B), Color(0xFF1B5E44))
    DayFeel.MEH  -> OrbPalette(Color(0xFFFFD08A), Color(0xFFE0922A), Color(0xFF8A4B0B))
    DayFeel.HARD -> OrbPalette(Color(0xFFF4B3C2), Color(0xFFC9607A), Color(0xFF6E2A3E))
    null         -> OrbPalette(Color(0xFF9B8AFF), Color(0xFF6B5CE7), Color(0xFF3A2F8A))
}

private val SatelliteColors = listOf(Color(0xFFB3A6FF), Color(0xFF6FA8FF), Color(0xFF4CC9B0))

/**
 * Draws a softly breathing sphere. The canvas draws glow and satellites outside
 * its own bounds on purpose, so the orb keeps its layout size in shared transitions.
 */
@Composable
fun CompanionOrb(mode: OrbMode, modifier: Modifier = Modifier) {
    val palette = LocalBackdrop.current.feel.orbPalette()
    val light by animateColorAsState(palette.light, tween(900), label = "orbLight")
    val mid by animateColorAsState(palette.mid, tween(900), label = "orbMid")
    val deep by animateColorAsState(palette.deep, tween(900), label = "orbDeep")

    val dataA by animateFloatAsState(if (mode == OrbMode.Data) 1f else 0f, tween(300), label = "dataA")
    val pingA by animateFloatAsState(if (mode == OrbMode.Ping) 1f else 0f, tween(300), label = "pingA")
    val thinkA by animateFloatAsState(if (mode == OrbMode.Thinking) 1f else 0f, tween(300), label = "thinkA")

    val still = rememberReducedMotion()
    val time = rememberFrameSeconds(running = !still)

    Canvas(modifier) {
        val t = time.floatValue
        val c = center
        val breathe = 1f + .02f * (1f + sin(t * 1.5f))
        val r = min(size.width, size.height) / 2f * breathe

        // glow
        val glowC = c + Offset(0f, r * .25f)
        drawCircle(
            Brush.radialGradient(listOf(mid.copy(alpha = .35f), mid.copy(alpha = 0f)), glowC, r * 1.4f),
            radius = r * 1.4f, center = glowC,
        )
        // sphere
        drawCircle(
            Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color.White.copy(alpha = .95f),
                    .08f to Color.White.copy(alpha = .85f),
                    .30f to light,
                    .62f to mid,
                    1f to deep,
                ),
                center = c + Offset(-r * .32f, -r * .40f),
                radius = r * 1.75f,
            ),
            radius = r, center = c,
        )

        // Data: three satellites — screen, sleep, steps
        if (dataA > 0f) {
            val periods = floatArrayOf(5f, -7f, 6f)
            val orbits = floatArrayOf(1.30f, 1.40f, 1.22f)
            for (i in 0..2) {
                val a = (t / periods[i] * 2f * PI.toFloat()) + i * 2.1f
                val p = c + Offset(cos(a) * r * orbits[i], sin(a) * r * orbits[i])
                drawCircle(SatelliteColors[i].copy(alpha = dataA), radius = r * .08f, center = p)
            }
        }
        // Ping: an expanding ring, like a notification arriving
        if (pingA > 0f) {
            val p = (t % 1.8f) / 1.8f
            drawCircle(
                mid.copy(alpha = (1f - p) * .7f * pingA),
                radius = r * (1.05f + .35f * p),
                center = c,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        // Thinking: a comet arc spinning around
        if (thinkA > 0f) {
            val ringR = r * 1.14f
            val start = (t * 330f) % 360f
            val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            val topLeft = c - Offset(ringR, ringR)
            val arcSize = Size(ringR * 2, ringR * 2)
            // faint tail, then the bright head
            drawArc(light.copy(alpha = .3f * thinkA), start - 70f, 70f, false, topLeft, arcSize, style = stroke)
            drawArc(light.copy(alpha = thinkA), start, 60f, false, topLeft, arcSize, style = stroke)
        }
    }
}
