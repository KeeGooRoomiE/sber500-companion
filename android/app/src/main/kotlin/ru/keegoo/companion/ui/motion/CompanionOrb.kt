package ru.keegoo.companion.ui.motion

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
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

// A tap spawns one wave: a thin ring that answers immediately, and a soft filled one that
// catches up a beat later. Several can be in flight at once (a tap flurry layers them).
private const val WAVE_RING_DURATION = 0.28f
private const val WAVE_FILL_DELAY = 0.12f
private const val WAVE_FILL_DURATION = 0.7f
private const val WAVE_MAX_AGE = WAVE_FILL_DELAY + WAVE_FILL_DURATION

/**
 * Draws a softly breathing sphere. The canvas draws glow and satellites outside
 * its own bounds on purpose, so the orb keeps its layout size in shared transitions.
 *
 * [speed] multiplies how fast everything here moves — breathing, glow, the wobble — not just
 * a squish on top of it; the caller eases it (e.g. a quick kick on tap), this just applies it.
 * [waveTrigger] spawns one tap-wave each time it changes (any change, not just an increment).
 */
@Composable
fun CompanionOrb(
    mode: OrbMode,
    modifier: Modifier = Modifier,
    badge: Boolean = false,
    speed: Float = 1f,
    waveTrigger: Int = 0,
    // How much rounder-or-sprawlier the wobble is — 1 is normal, >1 spreads it out, as if the
    // droplet were being squeezed. The caller eases it; this just multiplies with it.
    wobbleBoost: Float = 1f,
    // 0 = the mode's own colour, 1 = fully black. For sinking into the status-bar/camera area,
    // where a purple dot would look wrong next to real camera hardware.
    blackout: Float = 0f,
    // Current size as a fraction of the orb's full/expanded size — 1 normally. Tap waves only
    // draw at 0.7 and above: a wave spreading out of an orb that has already shrunk most of the
    // way down (or gone black near the camera) reads as wrong, not lively.
    sizeFraction: Float = 1f,
) {
    val blob = remember { Path() }
    val badgeColor = MaterialTheme.colorScheme.primary
    val palette = LocalBackdrop.current.feel.orbPalette()
    val baseLight by animateColorAsState(palette.light, tween(900), label = "orbLight")
    val baseMid by animateColorAsState(palette.mid, tween(900), label = "orbMid")
    val baseDeep by animateColorAsState(palette.deep, tween(900), label = "orbDeep")
    val light = lerp(baseLight, Color.Black, blackout)
    val mid = lerp(baseMid, Color.Black, blackout)
    val deep = lerp(baseDeep, Color.Black, blackout)

    val dataA by animateFloatAsState(if (mode == OrbMode.Data) 1f else 0f, tween(300), label = "dataA")
    val pingA by animateFloatAsState(if (mode == OrbMode.Ping) 1f else 0f, tween(300), label = "pingA")
    val thinkA by animateFloatAsState(if (mode == OrbMode.Thinking) 1f else 0f, tween(300), label = "thinkA")

    val still = rememberReducedMotion()
    val speedState = rememberUpdatedState(speed)
    val time = rememberFrameSeconds(running = !still, rate = speedState)

    // Each wave remembers the orb's own clock reading at the moment it was spawned, so its age
    // is always just "now minus that", independent of anything else going on.
    val waves = remember { mutableStateListOf<Float>() }
    LaunchedEffect(waveTrigger) {
        waves.removeAll { time.floatValue - it > WAVE_MAX_AGE }
        waves.add(time.floatValue)
    }

    Canvas(modifier) {
        val t = time.floatValue
        val c = center
        // badge sits on the edge — keep the breathing radius for everything else
        val breathe = 1f + .02f * (1f + sin(t * 1.5f))
        val r = min(size.width, size.height) / 2f * breathe

        // glow — slow pulse, a bit out of phase with breathing
        val glowPulse = 1f + .06f * sin(t * .9f + 1f)
        val glowC = c + Offset(0f, r * .25f)
        drawCircle(
            Brush.radialGradient(listOf(mid.copy(alpha = .35f), mid.copy(alpha = 0f)), glowC, r * 1.4f * glowPulse),
            radius = r * 1.4f * glowPulse, center = glowC,
        )

        // body — a softly wobbling blob instead of a perfect circle
        blob.reset()
        val steps = 72
        for (i in 0..steps) {
            val a = i / steps.toFloat() * 2f * PI.toFloat()
            val wobble = 1f +
                wobbleBoost * .028f * sin(3f * a + t * .8f) +
                wobbleBoost * .018f * sin(5f * a - t * 1.1f + 1.7f) +
                wobbleBoost * .010f * sin(2f * a + t * .5f)
            val p = c + Offset(cos(a) * r * wobble, sin(a) * r * wobble)
            if (i == 0) blob.moveTo(p.x, p.y) else blob.lineTo(p.x, p.y)
        }
        blob.close()

        // highlight drifts slowly over the surface, like light on a glass ball — blended toward
        // black along with everything else, so full blackout reads as a flat black dot and not
        // a black sphere with a glint still sitting on it.
        val hl = -2.25f + .35f * sin(t * .45f)
        val hlCenter = c + Offset(cos(hl) * r * .5f, sin(hl) * r * .5f)
        val glint = lerp(Color.White, Color.Black, blackout)
        drawPath(
            blob,
            Brush.radialGradient(
                colorStops = arrayOf(
                    0f to glint.copy(alpha = .95f),
                    .08f to glint.copy(alpha = .85f),
                    .30f to light,
                    .62f to mid,
                    1f to deep,
                ),
                center = hlCenter,
                radius = r * 1.75f,
            ),
        )
        // inner shimmer: a faint light band sweeping around
        val sweep = t * .6f
        val shimmerC = c + Offset(cos(sweep) * r * .35f, sin(sweep) * r * .35f)
        drawPath(
            blob,
            Brush.radialGradient(listOf(light.copy(alpha = .28f), light.copy(alpha = 0f)), shimmerC, r * .7f),
        )

        // Tap waves: a thin ring answering right away, a soft fill catching up behind it. Drawn
        // behind the badge/mode extras so those still read as sitting on top of the orb — and
        // only while the orb is still close to its full size.
        for (t0 in if (sizeFraction >= 0.7f) waves else emptyList()) {
            val age = t - t0
            if (age < 0f || age > WAVE_MAX_AGE) continue

            if (age <= WAVE_RING_DURATION) {
                val p = age / WAVE_RING_DURATION
                drawCircle(
                    mid.copy(alpha = (1f - p) * .55f),
                    radius = r * (1f + .35f * p),
                    center = c,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }

            val fillAge = age - WAVE_FILL_DELAY
            if (fillAge in 0f..WAVE_FILL_DURATION) {
                val p = fillAge / WAVE_FILL_DURATION
                // Grows to 2x the orb's own radius; fade is tied to size, not time — it barely
                // dims while still small, then drops away fast as it finishes expanding.
                val fade = (1f - p) * (1f - p)
                drawCircle(mid.copy(alpha = .06f * fade), radius = r * (1f + p), center = c)
            }
        }

        if (badge) {
            val br = r * .22f
            val bc = c + Offset(r * .72f, -r * .72f)
            drawCircle(Color.White, radius = br * 1.25f, center = bc)
            drawCircle(badgeColor, radius = br, center = bc)
        }

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
