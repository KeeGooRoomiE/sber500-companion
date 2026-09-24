package ru.keegoo.companion.ui.motion

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Rect

/** Keys shared between Onboarding and Home for shared-element transitions. */
object SharedKeys {
    const val ORB = "companion-orb"
    const val HERO_CARD = "hero-card"
    fun stat(name: String) = "stat-$name"
}

/** Slightly bouncy flight for the orb: onboarding centre → avatar in the top bar. */
@OptIn(ExperimentalSharedTransitionApi::class)
val OrbBoundsTransform = BoundsTransform { _, _ ->
    spring<Rect>(dampingRatio = .78f, stiffness = 320f, visibilityThreshold = Rect.VisibilityThreshold)
}

/** Calm, non-bouncy resize for cards (button → morning card, tile → detail sheet). */
@OptIn(ExperimentalSharedTransitionApi::class)
val CardBoundsTransform = BoundsTransform { _, _ ->
    spring<Rect>(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = Rect.VisibilityThreshold)
}
