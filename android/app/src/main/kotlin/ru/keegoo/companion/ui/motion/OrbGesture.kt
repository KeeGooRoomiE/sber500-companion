package ru.keegoo.companion.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val FLURRY_WINDOW_MS = 5_000L
private const val FLURRY_THRESHOLD = 3

/** A lone tap waits this long for a second one before it commits to opening the profile — the
 *  same disambiguation a double-tap gesture needs, just with a three-tap flurry on the other side. */
private const val SINGLE_TAP_DELAY_MS = 280L

/** What one more tap on the orb amounts to. */
internal enum class OrbTap { Flurry, MaybeSingle, StillPoking }

/**
 * Counts taps into flurries: every [FLURRY_THRESHOLD] taps inside [FLURRY_WINDOW_MS] is one
 * flurry, and the count starts over right after it. So someone who keeps tapping gets a new
 * reaction every three taps for as long as they like. It used to fire once and then stay silent
 * until the taps stopped for five seconds, which in practice meant «until you leave the screen».
 *
 * A tap that lands soon after a flurry is still poking, not a request to navigate: it must not
 * open the profile just because the count has started over.
 */
internal class OrbTapCounter {
    private val taps = mutableListOf<Long>()
    private var lastFlurry = Long.MIN_VALUE / 2

    fun onTap(now: Long): OrbTap {
        taps.add(now)
        taps.removeAll { now - it > FLURRY_WINDOW_MS }
        if (taps.size >= FLURRY_THRESHOLD) {
            taps.clear()
            lastFlurry = now
            return OrbTap.Flurry
        }
        return if (now - lastFlurry <= FLURRY_WINDOW_MS) OrbTap.StillPoking else OrbTap.MaybeSingle
    }
}

/**
 * Tells a single tap on the orb (open «Расскажи о себе») apart from a flurry — three taps
 * inside five seconds, which pokes the companion instead of navigating anywhere.
 *
 * [onTap] fires on every touch, flurry or not — that is the squish/haptic feedback, and it
 * never waits on the disambiguation. [onSingle] fires only once a tap has gone unanswered by a
 * second one for [SINGLE_TAP_DELAY_MS]; [onFlurry] fires on every third tap of a run (see
 * [OrbTapCounter]).
 */
@Composable
fun rememberOrbGesture(
    onTap: () -> Unit,
    onSingle: () -> Unit,
    onFlurry: () -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val counter = remember { OrbTapCounter() }
    var navJob by remember { mutableStateOf<Job?>(null) }

    return {
        onTap()
        navJob?.cancel()
        when (counter.onTap(System.currentTimeMillis())) {
            OrbTap.Flurry -> onFlurry()
            OrbTap.StillPoking -> Unit
            OrbTap.MaybeSingle -> navJob = scope.launch {
                delay(SINGLE_TAP_DELAY_MS)
                onSingle()
            }
        }
    }
}
