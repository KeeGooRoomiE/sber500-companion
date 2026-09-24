package ru.keegoo.companion.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.keegoo.companion.ui.theme.Primary
import ru.keegoo.companion.ui.theme.PrimaryFaint

private data class Step(
    val emoji: String,
    val title: String,
    val body: String,
    val cta: String,
)

private val steps = listOf(
    Step(
        emoji = "✦",
        title = "Познакомимся?",
        body = "Каждое утро — короткий прогноз на основе твоих реальных данных.\nНичего не нужно вводить вручную.",
        cta = "Начать",
    ),
    Step(
        emoji = "📊",
        title = "Что мы смотрим",
        body = "Время экрана, сон, шаги — и вечером одно касание о том, как прошёл день.",
        cta = "Понятно",
    ),
    Step(
        emoji = "🔔",
        title = "Уведомления",
        body = "Утренний прогноз и вечерний чек-ин придут как пуши. Ответить можно прямо из уведомления.",
        cta = "Разрешить и начать",
    ),
)

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val current = steps[step]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))

        // illustration placeholder
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(listOf(PrimaryFaint, Color(0xFFF7F6FF)))
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = current.emoji, fontSize = 64.sp)
        }

        Spacer(Modifier.height(40.dp))

        // step dots
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.indices.forEach { i ->
                Box(
                    modifier = Modifier
                        .size(if (i == step) 20.dp else 8.dp, 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (i == step) Primary
                            else MaterialTheme.colorScheme.outline
                        ),
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = current.title,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = current.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 26.sp,
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = { if (step < steps.lastIndex) step++ else onFinish() },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary),
        ) {
            Text(
                text = current.cta,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}
