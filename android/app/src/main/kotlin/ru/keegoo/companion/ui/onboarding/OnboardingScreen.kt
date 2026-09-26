package ru.keegoo.companion.ui.onboarding

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import kotlinx.coroutines.delay
import ru.keegoo.companion.work.DailyCollectWorker
import ru.keegoo.companion.data.collector.hasUsageAccess
import ru.keegoo.companion.ui.motion.BackdropScene
import ru.keegoo.companion.ui.motion.CardBoundsTransform
import ru.keegoo.companion.ui.motion.CompanionOrb
import ru.keegoo.companion.ui.motion.LocalBackdrop
import ru.keegoo.companion.ui.motion.OrbBoundsTransform
import ru.keegoo.companion.ui.motion.OrbMode
import ru.keegoo.companion.ui.motion.SharedKeys
import ru.keegoo.companion.ui.motion.rememberReducedMotion
import ru.keegoo.companion.ui.theme.AppShapes
import ru.keegoo.companion.ui.theme.CompanionTheme
import androidx.compose.foundation.clickable
import androidx.compose.animation.shrinkVertically
import ru.keegoo.companion.ui.permissions.RestrictedSettingsSteps
import ru.keegoo.companion.ui.permissions.appInfoIntent
import ru.keegoo.companion.ui.permissions.usageAccessIntent

private val HEALTH_PERMISSIONS = setOf(
    HealthPermission.getReadPermission(SleepSessionRecord::class),
    HealthPermission.getReadPermission(StepsRecord::class),
)

private data class Step(val title: String, val body: String, val cta: String, val orb: OrbMode)

private val steps = listOf(
    Step(
        "Познакомимся?",
        "Каждое утро — короткий прогноз на основе твоих реальных данных.\nНичего не нужно вводить вручную.",
        "Начать", OrbMode.Calm,
    ),
    Step(
        "Что мы смотрим",
        "Время экрана, сон, шаги — и вечером одно касание о том, как прошёл день. Сначала откроются настройки «Доступ к истории использования».",
        "Дать доступ к данным", OrbMode.Data,
    ),
    Step(
        "Уведомления",
        "Утренний прогноз и вечерний чек-ин придут как пуши. Ответить можно прямо из уведомления.",
        "Разрешить и начать", OrbMode.Ping,
    ),
    Step(
        "Смотрю твои данные",
        "Экран, разблокировки и сон. Это пара секунд.",
        "Готовлю главный экран", OrbMode.Thinking,
    ),
)

private const val LAST_STEP = 3

@OptIn(ExperimentalSharedTransitionApi::class)
@Preview(showBackground = true, showSystemUi = true, name = "Onboarding — step 1")
@Composable
private fun OnboardingPreview() {
    CompanionTheme {
        SharedTransitionLayout {
            AnimatedVisibility(visible = true) {
                OnboardingScreen(this@SharedTransitionLayout, this, onFinish = {})
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun OnboardingScreen(
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val backdrop = LocalBackdrop.current
    val still = rememberReducedMotion()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var hcUnavailable by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(step) { backdrop.scene = BackdropScene.Onboarding(step) }

    val healthLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { _ -> step = 2 }

    fun requestHealth() {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            hcUnavailable = false
            healthLauncher.launch(HEALTH_PERMISSIONS)
        } else {
            hcUnavailable = true
            step = 2
        }
    }

    // Usage access lives in system settings. Back with access → next step. Back without it →
    // most likely Android's «restricted settings» for sideloaded apps: show how to unlock it
    // (or skip) instead of silently moving on without the main data source.
    var usageBlocked by rememberSaveable { mutableStateOf(false) }
    val usageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (context.hasUsageAccess()) {
            usageBlocked = false
            requestHealth()
        } else {
            usageBlocked = true
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> step = LAST_STEP }

    // «Смотрю твои данные» — a short thinking moment, then the orb flies into Home.
    LaunchedEffect(step) {
        if (step == LAST_STEP) {
            // Send the last 7 days now (permissions are granted at this point), so the very first
            // forecast on Home can be about the person's real week. The orb "thinks" at least 1.8 s
            // and waits for the upload up to 10 s; Home falls back to the local summary anyway.
            val started = System.currentTimeMillis()
            DailyCollectWorker.runNowAndWait(context, pastDays = 7, timeoutMs = 10_000)
            val minShow = if (still) 600L else 1800L
            delay((minShow - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            onFinish()
        }
    }

    val current = steps[step]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))

        // Extra room around the orb for satellites and the ping ring
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            with(sharedScope) {
                CompanionOrb(
                    mode = current.orb,
                    modifier = Modifier
                        .sharedElement(
                            rememberSharedContentState(SharedKeys.ORB),
                            animatedVisibilityScope = animatedScope,
                            boundsTransform = OrbBoundsTransform,
                        )
                        .size(148.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        StepDots(step = step, modifier = Modifier.graphicsLayer { alpha = if (step == LAST_STEP) 0f else 1f })

        Spacer(Modifier.height(24.dp))

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(spring(dampingRatio = .85f, stiffness = 400f)) { dir * it / 5 } +
                    fadeIn(tween(220, delayMillis = 60)))
                    .togetherWith(slideOutHorizontally(tween(160)) { -dir * it / 6 } + fadeOut(tween(120)))
                    .using(SizeTransform(clip = false))
            },
            contentAlignment = Alignment.TopCenter,
            label = "stepCopy",
        ) { s ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = steps[s].title,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = steps[s].body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 26.sp,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        AnimatedVisibility(
            visible = usageBlocked && step == 1,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RestrictedSettingsSteps(
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                        onOpenAppInfo = {
                            try {
                                context.startActivity(context.appInfoIntent())
                            } catch (_: ActivityNotFoundException) {
                                // no app-info screen on this build — the steps still explain the way
                            }
                        },
                    )
                    Text(
                        text = "Пропустить — без этого доступа прогноз будет беднее",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { usageBlocked = false; requestHealth() }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = hcUnavailable && step == 2,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut(),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = "Health Connect на этом телефоне нет. Прогноз будет строиться по экрану, разблокировкам и твоим отметкам.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val btnInteraction = remember { MutableInteractionSource() }
        val btnPressed by btnInteraction.collectIsPressedAsState()
        val btnScale by animateFloatAsState(
            targetValue = if (btnPressed) 0.97f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessHigh, dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "btnScale",
        )
        val progress by animateFloatAsState(
            targetValue = if (step == LAST_STEP) 1f else 0f,
            animationSpec = if (step == LAST_STEP) tween(1700, easing = FastOutSlowInEasing) else tween(0),
            label = "ctaProgress",
        )

        // The CTA becomes the morning card on Home (same shared bounds key).
        with(sharedScope) {
            Box(
                modifier = Modifier
                    .sharedBounds(
                        rememberSharedContentState(SharedKeys.HERO_CARD),
                        animatedVisibilityScope = animatedScope,
                        boundsTransform = CardBoundsTransform,
                        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                        clipInOverlayDuringTransition = OverlayClip(AppShapes.button),
                    )
                    .fillMaxWidth()
                    .graphicsLayer { scaleX = btnScale; scaleY = btnScale },
            ) {
                Button(
                    onClick = {
                        if (step != LAST_STEP) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        when (step) {
                            0 -> step = 1
                            1 -> if (context.hasUsageAccess()) {
                                usageBlocked = false
                                requestHealth()
                            } else {
                                // Straight to our own switch where the phone supports it
                                try {
                                    usageLauncher.launch(context.usageAccessIntent(direct = true))
                                } catch (_: ActivityNotFoundException) {
                                    try {
                                        usageLauncher.launch(context.usageAccessIntent(direct = false))
                                    } catch (_: ActivityNotFoundException) {
                                        requestHealth()
                                    }
                                }
                            }
                            2 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                step = LAST_STEP
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = AppShapes.button,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    interactionSource = btnInteraction,
                ) {
                    Text(
                        text = current.cta,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
                // «Готовлю…» fill that runs while the orb is thinking
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(AppShapes.button)
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(Color.White.copy(alpha = .22f))
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun StepDots(step: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (0 until LAST_STEP).forEach { i ->
            val active = i == step.coerceAtMost(LAST_STEP - 1)
            val dotWidth by animateDpAsState(
                targetValue = if (active) 20.dp else 8.dp,
                animationSpec = spring(dampingRatio = .6f, stiffness = Spring.StiffnessMedium),
                label = "dot$i",
            )
            Box(
                modifier = Modifier
                    .size(dotWidth, 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
            )
        }
    }
}
