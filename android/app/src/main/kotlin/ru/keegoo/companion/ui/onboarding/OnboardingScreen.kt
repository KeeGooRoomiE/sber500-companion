package ru.keegoo.companion.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import ru.keegoo.companion.ui.theme.AppShapes
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
        "Время экрана, сон, шаги — и вечером одно касание о том, как прошёл день.",
        "Дать доступ к данным"),
    Step("🔔", "Уведомления",
        "Утренний прогноз и вечерний чек-ин придут как пуши. Ответить можно прямо из уведомления.",
        "Разрешить и начать"),
)

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var hcUnavailable by remember { mutableStateOf(false) }

    val healthLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { _ -> step++ }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> onFinish() }

    val current = steps[step]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        OnboardingOrbBackground(Modifier.fillMaxSize())

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                val dotWidth by animateDpAsState(
                    targetValue = if (i == step) 20.dp else 8.dp,
                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                    label = "dot$i",
                )
                Box(
                    modifier = Modifier
                        .size(dotWidth, 8.dp)
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

        if (hcUnavailable && step == 2) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = "⚠️ Health Connect недоступен на этом устройстве — сон и шаги собираться не будут. Остальное работает.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        val btnInteraction = remember { MutableInteractionSource() }
        val btnPressed by btnInteraction.collectIsPressedAsState()
        val btnScale by animateFloatAsState(
            targetValue = if (btnPressed) 0.97f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessHigh, dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "btnScale",
        )
        val haptic = LocalHapticFeedback.current

        Box(modifier = Modifier.fillMaxWidth().scale(btnScale)) {
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    when (step) {
                        0 -> step++
                        1 -> {
                            if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
                                hcUnavailable = false
                                healthLauncher.launch(HEALTH_PERMISSIONS)
                            } else {
                                hcUnavailable = true
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
                shape = AppShapes.button,
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                interactionSource = btnInteraction,
            ) {
                Text(
                    text = current.cta,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
    } // end Box
}

// ─── Onboarding orb background ───────────────────────────────────────────────

@Composable
private fun OnboardingOrbBackground(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orbs_ob")
    val o1x by t.animateFloat(0.05f, 0.50f, infiniteRepeatable(tween(15000, easing = LinearEasing), RepeatMode.Reverse), "ob1x")
    val o1y by t.animateFloat(0.02f, 0.30f, infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Reverse), "ob1y")
    val o2x by t.animateFloat(0.50f, 0.95f, infiniteRepeatable(tween(20000, easing = LinearEasing), RepeatMode.Reverse), "ob2x")
    val o2y by t.animateFloat(0.50f, 0.90f, infiniteRepeatable(tween(13000, easing = LinearEasing), RepeatMode.Reverse), "ob2y")
    Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.radialGradient(
                listOf(Color(0xFF6B5CE7).copy(alpha = 0.22f), Color.Transparent),
                center = Offset(size.width * o1x, size.height * o1y),
                radius = size.width * 0.65f,
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                listOf(Color(0xFF8B7CF8).copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * o2x, size.height * o2y),
                radius = size.width * 0.55f,
            )
        )
    }
}
