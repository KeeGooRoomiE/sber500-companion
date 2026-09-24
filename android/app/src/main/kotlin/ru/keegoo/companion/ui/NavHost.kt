package ru.keegoo.companion.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import ru.keegoo.companion.data.prefs.isOnboarded
import ru.keegoo.companion.data.prefs.setOnboarded
import ru.keegoo.companion.ui.motion.BackdropState
import ru.keegoo.companion.ui.motion.CompanionBackdrop
import ru.keegoo.companion.ui.motion.LocalBackdrop
import ru.keegoo.companion.ui.onboarding.OnboardingScreen

private const val ROUTE_ONBOARDING = "onboarding"
private const val ROUTE_HOME = "home"

// One backdrop and one SharedTransitionLayout above NavHost: the background never
// restarts between screens, and the orb / hero card can fly from one screen to the other.
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun CompanionNavHost() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backdrop = remember { BackdropState() }
    val onboarded by produceState<Boolean?>(initialValue = null) { value = context.isOnboarded() }

    CompositionLocalProvider(LocalBackdrop provides backdrop) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            CompanionBackdrop(Modifier.fillMaxSize())

            val start = onboarded
            if (start != null) {
                val navController = rememberNavController()
                SharedTransitionLayout {
                    NavHost(
                        navController = navController,
                        startDestination = if (start) ROUTE_HOME else ROUTE_ONBOARDING,
                        // Default is a 700 ms crossfade; shared elements carry the motion instead.
                        enterTransition = { fadeIn(tween(300, delayMillis = 120)) },
                        exitTransition = { fadeOut(tween(150)) },
                        popEnterTransition = { fadeIn(tween(300, delayMillis = 120)) },
                        popExitTransition = { fadeOut(tween(150)) },
                    ) {
                        composable(ROUTE_ONBOARDING) {
                            OnboardingScreen(
                                sharedScope = this@SharedTransitionLayout,
                                animatedScope = this@composable,
                                onFinish = {
                                    scope.launch { context.setOnboarded() }
                                    navController.navigate(ROUTE_HOME) {
                                        popUpTo(ROUTE_ONBOARDING) { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable(ROUTE_HOME) {
                            HomeScreen(
                                sharedScope = this@SharedTransitionLayout,
                                animatedScope = this@composable,
                            )
                        }
                    }
                }
            }
        }
    }
}
