package ru.keegoo.companion.ui.onboarding

import android.net.Uri
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
import ru.keegoo.companion.data.collector.batteryOptimizationIntent
import ru.keegoo.companion.analytics.Events
import ru.keegoo.companion.data.collector.hasUsageAccess
import ru.keegoo.companion.data.collector.isIgnoringBatteryOptimizations
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

// Asked one module at a time. A single dialog for «sleep and steps» made people decide about
// the most private signal they have while deciding about step counts — interviews balked at
// sleep specifically, and bundling cost both.
//
// The background permission rides with each: the collector reads from a worker, not from the
// open app, and Android 14+ returns nothing to background reads without it. Health Connect
// ignores whichever of the two is already granted.
private val STEPS_PERMISSIONS = setOf(
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
)

private val SLEEP_PERMISSIONS = setOf(
    HealthPermission.getReadPermission(SleepSessionRecord::class),
    HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
)

/**
 * One onboarding screen.
 *
 * [note] is the line that says what the app does *not* see. Permission screens lead with the
 * limit rather than the ask: every interview that balked did so over what might be read, not
 * over what the feature gives.
 */
private data class Step(
    val title: String,
    val body: String,
    val cta: String,
    val orb: OrbMode,
    val note: String? = null,
    /** «Пропустить» under the button — the app works without this one. */
    val skipLabel: String? = null,
    /** Shows the sample forecast instead of plain copy. */
    val sample: Boolean = false,
)

private val steps = listOf(
    Step(
        "Познакомимся?",
        "Каждое утро — короткая мысль о дне, собранная из того, что телефон уже о себе пишет.\nНичего не нужно вводить вручную.",
        "Дальше", OrbMode.Calm,
    ),
    Step(
        "Вот что приходит утром",
        "Через неделю приложение начнёт замечать то, что сам за собой не видишь.",
        "Хочу так же — дать доступ", OrbMode.Data,
        sample = true,
    ),
    Step(
        "Экран — основа",
        "Видим только, сколько минут был включён экран и какие приложения открывались.",
        "Разрешить", OrbMode.Data,
        note = "Не видим, что на экране, — ни переписок, ни фото.",
    ),
    Step(
        "Шаги — точнее",
        "По желанию. Передаём только число шагов за день.",
        "Разрешить", OrbMode.Data,
        note = "Без них прогноз чуть грубее.",
        skipLabel = "Пропустить",
    ),
    Step(
        "Сон — точнее",
        "По желанию. Передаём только длительность и время сна — без пульса и стадий.",
        "Разрешить", OrbMode.Data,
        note = "Без него считаем сон по самой длинной паузе без экрана.",
        skipLabel = "Пропустить",
    ),
    Step(
        "Три уведомления в день",
        "Прогноз утром, вечером спрошу, как прошёл день, и пришлю разбор. Больше не будет.",
        "Разрешить и начать", OrbMode.Ping,
        note = "Следом система спросит про работу в фоне — почти не тратит батарею, но без неё уведомления не придут.",
    ),
    Step(
        "Смотрю твои данные",
        "Экран, разблокировки и сон. Это пара секунд.",
        "Готовлю главный экран", OrbMode.Thinking,
    ),
)

private const val STEP_SAMPLE = 1
private const val STEP_USAGE = 2
private const val STEP_STEPS = 3
private const val STEP_SLEEP = 4
private const val STEP_NOTIFICATIONS = 5
private const val LAST_STEP = 6

/** Stable slugs for the funnel: step indexes alone are unreadable in a report a year later. */
private val stepNames = listOf("intro", "sample", "usage_access", "steps", "sleep", "notifications", "collecting")

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

    LaunchedEffect(step) {
        backdrop.scene = BackdropScene.Onboarding(step)
        Events.onboardingStep(step, stepNames.getOrElse(step) { "step_$step" })
    }

    val healthAvailable = remember {
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    val stepsLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        Events.onboardingPermission(
            "steps",
            if (granted.contains(HealthPermission.getReadPermission(StepsRecord::class)))
                Events.PermissionResult.Granted else Events.PermissionResult.Denied,
        )
        step = STEP_SLEEP
    }

    val sleepLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        Events.onboardingPermission(
            "sleep",
            if (granted.contains(HealthPermission.getReadPermission(SleepSessionRecord::class)))
                Events.PermissionResult.Granted else Events.PermissionResult.Denied,
        )
        step = STEP_NOTIFICATIONS
    }

    /** Health Connect is missing on this phone: skip both of its steps, saying so once. */
    fun skipHealthEntirely() {
        hcUnavailable = true
        Events.onboardingPermission("health", Events.PermissionResult.Unavailable)
        step = STEP_NOTIFICATIONS
    }

    fun requestHealth() {
        if (healthAvailable) {
            hcUnavailable = false
            step = STEP_STEPS
        } else {
            skipHealthEntirely()
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
            Events.onboardingPermission("usage_access", Events.PermissionResult.Granted)
            usageBlocked = false
            requestHealth()
        } else {
            // Not final yet: the «restricted settings» panel is about to offer another try.
            usageBlocked = true
        }
    }

    // Permission to post notifications is not enough: Android throttles a rarely-opened app
    // into running background work about once a day, and several vendors stop it outright, so
    // the reminder would simply never be posted. Ask right after the notification dialog, while
    // the reason is still on screen. Either answer moves on — push covers a "no".
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // The dialog reports nothing, so ask the system what it decided.
        Events.onboardingPermission(
            "battery",
            if (context.isIgnoringBatteryOptimizations()) Events.PermissionResult.Granted
            else Events.PermissionResult.Denied,
        )
        step = LAST_STEP
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Events.onboardingPermission(
            "notifications",
            if (granted) Events.PermissionResult.Granted else Events.PermissionResult.Denied,
        )
        if (context.isIgnoringBatteryOptimizations()) {
            Events.onboardingPermission("battery", Events.PermissionResult.Granted)
            step = LAST_STEP
        } else {
            runCatching { batteryLauncher.launch(batteryOptimizationIntent(context.packageName)) }
                .onFailure {
                    Events.onboardingPermission("battery", Events.PermissionResult.Unavailable)
                    step = LAST_STEP
                }
        }
    }

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
            Events.onboardingFinished()
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
            val st = steps[s]
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = st.title,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = st.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 26.sp,
                )
                if (st.sample) {
                    Spacer(Modifier.height(18.dp))
                    SampleForecast()
                }
                st.note?.let { note ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f),
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Подробнее о данных",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        AnimatedVisibility(
            visible = usageBlocked && step == STEP_USAGE,
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
                            .clickable {
                                Events.onboardingPermission("usage_access", Events.PermissionResult.Skipped)
                                usageBlocked = false
                                requestHealth()
                            }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = hcUnavailable && step == STEP_NOTIFICATIONS,
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
                            0 -> step = STEP_SAMPLE
                            STEP_SAMPLE -> step = STEP_USAGE
                            STEP_STEPS -> if (healthAvailable) {
                                stepsLauncher.launch(STEPS_PERMISSIONS)
                            } else {
                                skipHealthEntirely()
                            }
                            STEP_SLEEP -> if (healthAvailable) {
                                sleepLauncher.launch(SLEEP_PERMISSIONS)
                            } else {
                                skipHealthEntirely()
                            }
                            STEP_USAGE -> if (context.hasUsageAccess()) {
                                // Already granted — a reinstall, or it was given earlier. The
                                // launcher never runs, so the funnel has to be told here or this
                                // person silently disappears from the step.
                                Events.onboardingPermission("usage_access", Events.PermissionResult.Granted)
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
                                        // No settings screen to send them to at all.
                                        Events.onboardingPermission("usage_access", Events.PermissionResult.Unavailable)
                                        requestHealth()
                                    }
                                }
                            }
                            STEP_NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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

        // «Пропустить» only where the app genuinely works without the permission. Interviews
        // named a forced walkthrough as a reason to uninstall, and an optional step that cannot
        // be declined is a forced one.
        val skip = steps[step].skipLabel
        AnimatedVisibility(
            visible = skip != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Text(
                text = skip.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        when (step) {
                            STEP_STEPS -> {
                                Events.onboardingPermission("steps", Events.PermissionResult.Skipped)
                                step = STEP_SLEEP
                            }
                            STEP_SLEEP -> {
                                Events.onboardingPermission("sleep", Events.PermissionResult.Skipped)
                                step = STEP_NOTIFICATIONS
                            }
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}


private const val PRIVACY_URL = "https://keegooroomie.github.io/sber500-companion/privacy.html"

/**
 * What a morning actually looks like, shown before anything is asked for.
 *
 * Permissions were the step people balked at, and they balked without knowing what they were
 * buying. So the ask comes after the answer: a real-shaped card, marked as an example, with the
 * «Почему» already open — because the point is not the number, it is the chain nobody sees in
 * their own week.
 */
@Composable
private fun SampleForecast(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primary,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Прогноз на сегодня",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = .75f),
                    fontWeight = FontWeight.SemiBold,
                )
                Surface(shape = RoundedCornerShape(20.dp), color = Color.White.copy(alpha = .22f)) {
                    Text(
                        text = "пример",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = "Два вечера подряд экран гас за полночь — завтра, скорее всего, будет тяжелее, чем кажется с утра.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                lineHeight = 24.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Почему",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = .75f),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Тяжесть дня на этой неделе решала не нагрузка и не день недели, а один рычаг — во сколько ночью гас экран.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = .9f),
                lineHeight = 20.sp,
            )
        }
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
