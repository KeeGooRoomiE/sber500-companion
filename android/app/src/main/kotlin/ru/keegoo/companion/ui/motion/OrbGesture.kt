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

/**
 * Tells a single tap on the orb (open «Расскажи о себе») apart from a flurry — three taps
 * inside five seconds, which pokes the companion instead of navigating anywhere.
 *
 * [onTap] fires on every touch, flurry or not — that is the squish/haptic feedback, and it
 * never waits on the disambiguation. [onSingle] fires only once a tap has gone unanswered by a
 * second one for [SINGLE_TAP_DELAY_MS]; [onFlurry] fires once, the moment a third tap lands
 * inside the five-second window, and will not fire again until the flurry has actually gone
 * quiet (not on every tap after the third).
 */
@Composable
fun rememberOrbGesture(
    onTap: () -> Unit,
    onSingle: () -> Unit,
    onFlurry: () -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val taps = remember { mutableListOf<Long>() }
    var navJob by remember { mutableStateOf<Job?>(null) }
    var inFlurry by remember { mutableStateOf(false) }

    return {
        onTap()
        val now = System.currentTimeMillis()
        taps.add(now)
        taps.removeAll { now - it > FLURRY_WINDOW_MS }
        navJob?.cancel()

        if (taps.size >= FLURRY_THRESHOLD) {
            if (!inFlurry) {
                inFlurry = true
                onFlurry()
            }
        } else {
            inFlurry = false
            navJob = scope.launch {
                delay(SINGLE_TAP_DELAY_MS)
                onSingle()
            }
        }
    }
}
