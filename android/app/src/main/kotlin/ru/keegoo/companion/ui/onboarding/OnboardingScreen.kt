package ru.keegoo.companion.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }

    val steps = listOf(
        OnboardingStep(
            title = "Компаньон",
            body = "Каждое утро — короткий прогноз на основе твоих реальных данных.\nНичего не нужно вводить вручную.",
            cta = "Начать",
        ),
        OnboardingStep(
            title = "Что мы смотрим",
            body = "Время экрана, сон, шаги — и вечером одно касание о том, как прошёл день.",
            cta = "Дать доступ к данным",
        ),
        OnboardingStep(
            title = "Уведомления",
            body = "Утренний прогноз и вечерний чек-ин придут как пуши. Можно ответить прямо из уведомления.",
            cta = "Разрешить уведомления",
        ),
    )

    val current = steps[step]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = current.title,
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = current.body,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(modifier = Modifier.height(48.dp))
        Button(
            onClick = {
                if (step < steps.lastIndex) step++ else onFinish()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(current.cta)
        }
    }
}

private data class OnboardingStep(val title: String, val body: String, val cta: String)
