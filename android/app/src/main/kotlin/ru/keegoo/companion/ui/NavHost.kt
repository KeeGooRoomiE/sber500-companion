package ru.keegoo.companion.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ru.keegoo.companion.ui.onboarding.OnboardingScreen

@Composable
fun CompanionNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "onboarding") {
        composable("onboarding") {
            OnboardingScreen(onFinish = { navController.navigate("home") { popUpTo("onboarding") { inclusive = true } } })
        }
        composable("home") {
            HomeScreen()
        }
    }
}
