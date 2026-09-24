package ru.keegoo.companion.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.tooling.preview.Preview
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import ru.keegoo.companion.ui.theme.Primary
import ru.keegoo.companion.ui.theme.PrimaryFaint

private val HEALTH_PERMISSIONS = setOf(
    HealthPermission.getReadPermission(SleepSessionRecord::class),
    HealthPermission.getReadPermission(StepsRecord::class),
)

@Preview(showBackground = true, showSystemUi = true, name = "Onboarding — step 1")
@Composable
private fun OnboardingPreview() {
    ru.keegoo.companion.ui.theme.CompanionTheme { OnboardingScreen(onFinish = {}) }
}

private data class Step(val emoji: String, val title: String, val body: String, val cta: String)

private val steps = listOf(
    Step("✦", "Познакомимся?",
        "Каждое утро — короткий прогноз на основе твоих реальных данных.\nНичего не нужно вводить вручную.",
        "Начать"),
    Step("📊", "Что мы смотрим",
        "Время экрана, сон, шаги — и вечером одно касание о том, как прошёл день.\nНужен доступ к Health Connect.",
        "Дать доступ к данным"),
    Step("🔔", "Уведомления",
        "Утренний прогноз и вечерний чек-ин придут как пуши. Ответить можно прямо из уведомления.",
        "Разрешить и начать"),
)

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }

    // Health Connect permission launcher (step 2)
    val healthLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { _ -> step++ }   // advance regardless — app works without it

    // POST_NOTIFICATIONS launcher (step 3, API 33+)
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> onFinish() }

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

        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(PrimaryFaint, Color(0xFFF7F6FF)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = current.emoji, fontSize = 64.sp)
        }

        Spacer(Modifier.height(40.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.indices.forEach { i ->
                Box(
                    modifier = Modifier
                        .size(if (i == step) 20.dp else 8.dp, 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (i == step) Primary else MaterialTheme.colorScheme.outline
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
            onClick = {
                when (step) {
                    0 -> step++
                    1 -> {
                        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
                            healthLauncher.launch(HEALTH_PERMISSIONS)
                        } else {
                            step++
                        }
                    }
                    2 -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onFinish()
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
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
