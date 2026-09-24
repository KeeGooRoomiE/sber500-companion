package ru.keegoo.companion.ui.motion

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import ru.keegoo.companion.domain.model.DayFeel
import kotlin.math.sin
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

// ─── Shared backdrop ──────────────────────────────────────────────────────────
// One background for the whole app, drawn above NavHost: the landing's slow waves.
// Screens don't paint their own background — they only tell the backdrop where they
// are (scene) and how the day went (feel). Waves then rise/settle on a spring and
// shift toward the feel color instead of restarting, so navigation never "blinks".

sealed interface BackdropScene {
    data class Onboarding(val step: Int) : BackdropScene
    data object Home : BackdropScene
}

@Stable
class BackdropState {
    var scene: BackdropScene by mutableStateOf(BackdropScene.Onboarding(0))
    var feel: DayFeel? by mutableStateOf(null)

    // Scroll kicks the waves like on the landing; decays quickly. Not snapshot state: read per frame.
    private var boost = 0f

    fun kick(scrollDeltaPx: Float) {
        boost = min(boost + abs(scrollDeltaPx) * .02f, 12f)
    }

    internal fun consumeBoost(dt: Float): Float {
        val current = boost
        boost *= exp(-dt * 7f)
        return current
    }
}

val LocalBackdrop = staticCompositionLocalOf { BackdropState() }

private data class SceneSpec(val yStart: Float, val pace: Float)

// Waves sit lower in onboarding (room for the orb) and rise on Home.
private fun BackdropScene.spec(): SceneSpec = when (this) {
    is BackdropScene.Onboarding -> when (step) {
        0 -> SceneSpec(.46f, 1f)
        1 -> SceneSpec(.42f, 1f)
        2 -> SceneSpec(.38f, 1f)
        else -> SceneSpec(.34f, 2.4f)   // «Смотрю твои данные» — waves speed up
    }
    BackdropScene.Home -> SceneSpec(.22f, 1f)
}

fun DayFeel?.accent(): Color = when (this) {
    DayFeel.OK   -> Color(0xFF2EAA6E)
    DayFeel.MEH  -> Color(0xFFE89628)
    DayFeel.HARD -> Color(0xFFD6607A)
    null         -> Color(0xFF6B5CE7)
}

// Same numbers as the landing's PlayStation XMB waves (web/index.html), in dp.
private const val WAVES = 8
private const val BASE_SPEED = .12f      // rad/s (landing: 0.002 per frame at 60 fps)
private const val SPEED_STEP = .048f
private const val BASE_AMP = 10f         // dp
private const val AMP_STEP = 9f
private const val Y_STEP = .108f
private const val PHASE_OFFSET = 1.2f
private const val SECONDARY = .45f

private fun waveColor(i: Int, dark: Boolean): Color {
    // hue 339 (pink) at the top drifting toward violet at the bottom, deeper = more saturated/darker
    val hue = ((339f - 12f * i) % 360f + 360f) % 360f
    val sat = (62f + 2.5f * i) / 100f
    val light = (68f - 2f * i) / 100f
    return Color.hsl(hue, sat, if (dark) light - .12f else light)
}

@Composable
fun CompanionBackdrop(modifier: Modifier = Modifier) {
    val state = LocalBackdrop.current
    val spec = state.scene.spec()
    val still = rememberReducedMotion()
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f

    val yStart by animateFloatAsState(spec.yStart, spring(stiffness = Spring.StiffnessVeryLow), label = "yStart")
    // «Тяжело» — the sea calms down
    val pace = animateFloatAsState(spec.pace * if (state.feel == DayFeel.HARD) .45f else 1f, tween(1200), label = "pace")
    val tint by animateColorAsState(state.feel.accent(), tween(900), label = "tint")
    val tintAmount by animateFloatAsState(if (state.feel != null) .35f else 0f, tween(900), label = "tintA")
    val alpha = if (dark) .10f else .065f

    // Phases advance per wave (deeper waves faster), plus a boost from scrolling, like on the landing.
    val phases = remember { FloatArray(WAVES) { i -> i * 7.3f } }
    val frame = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = (now - last) / 1_000_000_000f
                last = now
                val boost = state.consumeBoost(dt)
                for (i in 0 until WAVES) {
                    val speed = BASE_SPEED + i * SPEED_STEP
                    phases[i] += dt * speed * pace.value * (1f + boost * (.4f + i * .37f))
                }
                frame.floatValue += dt
            }
        }
    }

    val path = remember { Path() }
    Canvas(modifier) {
        frame.floatValue // redraw every frame; phases live outside snapshot state
        val stepPx = 4.dp.toPx()
        for (i in 0 until WAVES) {
            val amp = (BASE_AMP + i * AMP_STEP).dp.toPx()
            val yBase = size.height * (yStart + i * Y_STEP)
            val phase = phases[i] + i * PHASE_OFFSET
            path.reset()
            var x = 0f
            while (x <= size.width + stepPx) {
                val xDp = x / density
                val y = yBase + sin(xDp * .008f + phase) * amp + sin(xDp * .004f + phase * .6f) * amp * SECONDARY
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += stepPx
            }
            path.lineTo(size.width, size.height)
            path.lineTo(0f, size.height)
            path.close()
            val color = lerp(waveColor(i, dark), tint, tintAmount)
            drawPath(path, color.copy(alpha = alpha))
        }
    }
}

/** Seconds since start, advanced every frame and scaled by [rate]. Read it in draw lambdas. */
@Composable
fun rememberFrameSeconds(running: Boolean, rate: State<Float>? = null): FloatState {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                time.floatValue += (now - last) / 1_000_000_000f * (rate?.value ?: 1f)
                last = now
            }
        }
    }
    return time
}

/** True when the system setting «Удалить анимации» (animator scale = 0) is on. */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
