package ru.keegoo.companion.ui.motion

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import ru.keegoo.companion.domain.model.DayFeel
import kotlin.math.sin

// ─── Shared backdrop ──────────────────────────────────────────────────────────
// One background for the whole app, drawn above NavHost. Screens don't paint
// their own background — they only tell the backdrop where they are (scene)
// and how the day went (feel). The orbs then drift to new anchors on a spring
// instead of restarting, so navigation never "blinks".

sealed interface BackdropScene {
    data class Onboarding(val step: Int) : BackdropScene
    data object Home : BackdropScene
}

@Stable
class BackdropState {
    var scene: BackdropScene by mutableStateOf(BackdropScene.Onboarding(0))
    var feel: DayFeel? by mutableStateOf(null)
}

val LocalBackdrop = staticCompositionLocalOf { BackdropState() }

private data class SceneSpec(val anchors: List<Offset>, val pace: Float)

private fun BackdropScene.spec(): SceneSpec = when (this) {
    is BackdropScene.Onboarding -> when (step) {
        0 -> SceneSpec(listOf(Offset(.50f, .20f), Offset(.82f, .66f), Offset(.22f, .86f)), 1f)
        1 -> SceneSpec(listOf(Offset(.28f, .26f), Offset(.86f, .52f), Offset(.44f, .90f)), 1f)
        2 -> SceneSpec(listOf(Offset(.72f, .18f), Offset(.18f, .58f), Offset(.62f, .86f)), 1f)
        else -> SceneSpec(listOf(Offset(.50f, .26f), Offset(.46f, .34f), Offset(.54f, .30f)), 2.2f)
    }
    BackdropScene.Home -> SceneSpec(listOf(Offset(.22f, .12f), Offset(.86f, .50f), Offset(.34f, .92f)), 1f)
}

private val OrbViolet = Color(0xFF6B5CE7)
private val OrbLilac  = Color(0xFF8B7CF8)
private val OrbTeal   = Color(0xFF4CC9B0)

fun DayFeel?.accent(): Color = when (this) {
    DayFeel.OK   -> Color(0xFF2EAA6E)
    DayFeel.MEH  -> Color(0xFFE89628)
    DayFeel.HARD -> Color(0xFFD6607A)
    null         -> OrbTeal
}

@Composable
fun CompanionBackdrop(modifier: Modifier = Modifier) {
    val state = LocalBackdrop.current
    val spec = state.scene.spec()
    val still = rememberReducedMotion()

    val anchorSpring = spring<Offset>(stiffness = Spring.StiffnessVeryLow)
    val a1 by animateOffsetAsState(spec.anchors[0], anchorSpring, label = "a1")
    val a2 by animateOffsetAsState(spec.anchors[1], anchorSpring, label = "a2")
    val a3 by animateOffsetAsState(spec.anchors[2], anchorSpring, label = "a3")
    // «Тяжело» — фон заметно успокаивается
    val pace = animateFloatAsState(spec.pace * if (state.feel == DayFeel.HARD) .45f else 1f, tween(1200), label = "pace")
    val tint by animateColorAsState(state.feel.accent(), tween(900), label = "tint")
    val tintAlpha by animateFloatAsState(if (state.feel != null) .26f else .15f, tween(900), label = "tintA")

    val time = rememberFrameSeconds(running = !still, rate = pace)

    Canvas(modifier) {
        // All animated reads happen here, in the draw phase: a frame costs a redraw, not a recomposition.
        val t = time.floatValue
        orb(a1, t, OrbViolet, .26f, .78f, fx = .38f, fy = .29f, ph = 1.3f, ax = .20f, ay = .10f)
        orb(a2, t, OrbLilac, .20f, .64f, fx = .27f, fy = .41f, ph = 2.1f, ax = .16f, ay = .12f)
        orb(a3, t, tint, tintAlpha, .58f, fx = .33f, fy = .23f, ph = 4.0f, ax = .22f, ay = .08f)
    }
}

// Position = anchor + sine drift (a Lissajous figure): velocity changes smoothly,
// no hard turnaround like LinearEasing + RepeatMode.Reverse.
private fun DrawScope.orb(
    anchor: Offset, t: Float, color: Color, alpha: Float, radius: Float,
    fx: Float, fy: Float, ph: Float, ax: Float, ay: Float,
) {
    val center = Offset(
        size.width * (anchor.x + ax * sin(t * fx * 2f + ph)),
        size.height * (anchor.y + ay * sin(t * fy * 2f + ph * .7f)),
    )
    val r = size.width * radius
    drawCircle(
        brush = Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)), center, r),
        radius = r,
        center = center,
    )
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
