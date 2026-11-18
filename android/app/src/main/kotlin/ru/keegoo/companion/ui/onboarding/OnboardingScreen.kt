package ru.keegoo.companion.ui.onboarding

import ru.keegoo.companion.ui.theme.Primary
import androidx.compose.ui.graphics.Brush
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
import android.content.Context
import androidx.compose.material3.Switch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.keegoo.companion.data.collector.UsageStatsCollector
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

// Health Connect is asked once, for whatever the person switched on in «Сделать прогноз точнее?».
// The background permission rides with it: the collector reads from a worker, not from the open
// app, and Android 14+ returns nothing to background reads without it.
private val STEPS_READ = HealthPermission.getReadPermission(StepsRecord::class)
private val SLEEP_READ = HealthPermission.getReadPermission(SleepSessionRecord::class)

/** What the extra card under the copy shows. */
private enum class Panel { None, Sample, Reveal, Notification, Accuracy }

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
    /** Text under the button — the app works without this one. */
    val skipLabel: String? = null,
    val panel: Panel = Panel.None,
)

// v3 flow (web/onboarding-reveal/v3). Usage access is the one mandatory step — without it there
// is no product — so it has no «Пропустить», only a «Позже» inside the help sheet. The
// «what I already see» peek is opt-in: showing analytics right after the permission looked
// suspicious to the designer. Steps and sleep moved to one optional screen at the end.
private val steps = listOf(
    Step(
        "Познакомимся?",
        "Каждое утро — одна фраза о том, чего ждать от дня.\nБез дневников и ручного ввода.",
        "Дальше", OrbMode.Calm,
        panel = Panel.Sample,
    ),
    Step(
        "Один доступ — и начнём",
        "Нужно видеть, сколько минут был включён экран.",
        "Разрешить", OrbMode.Data,
        note = "Ни переписок, ни фото.",
    ),
    Step(
        "Доступ есть",
        "Android хранит историю экрана, поэтому ждать неделю не нужно.\nПоказать, что я уже вижу?",
        "Показать, что я уже вижу", OrbMode.Ping,
        skipLabel = "Не сейчас",
    ),
    Step(
        "", // title depends on the time of day, see revealTitle()
        "Утром из таких дней я буду собирать прогноз — по твоим закономерностям.",
        "Дальше", OrbMode.Data,
        panel = Panel.Reveal,
    ),
    Step(
        "Три уведомления в день",
        "Прогноз утром, вечером спрошу, как прошёл день, и пришлю разбор. Больше не будет.",
        "Разрешить", OrbMode.Ping,
        note = "Следом система спросит про работу в фоне — почти не тратит батарею, но без неё уведомления не придут.",
        skipLabel = "Не сейчас",
        panel = Panel.Notification,
    ),
    Step(
        "Сделать прогноз точнее?",
        "По желанию. Передаём только число шагов и время сна — без пульса и стадий.\nБез них прогноз чуть грубее, но работает.",
        "Добавить и начать", OrbMode.Data,
        skipLabel = "Пропустить",
        panel = Panel.Accuracy,
    ),
    Step(
        "Смотрю твои данные",
        "Экран, разблокировки и сон. Это пара секунд.",
        "Готовлю главный экран", OrbMode.Thinking,
    ),
)

private const val STEP_HELLO = 0
private const val STEP_ACCESS = 1
private const val STEP_TEASE = 2
private const val STEP_REVEAL = 3
private const val STEP_NOTIFICATIONS = 4
private const val STEP_ACCURACY = 5
private const val LAST_STEP = 6

/** Progress dots: the peek shares a dot with «Доступ есть» (5 dots, 7 screens). */
private val dotOf = intArrayOf(0, 1, 2, 2, 3, 4, 4)
private const val DOT_COUNT = 5

private const val SAMPLE_FORECAST =
    "После спокойных вечеров у тебя обычно лёгкое утро — сегодня хороший день для важных дел."

/**
 * Stable slugs for the funnel: step indexes alone are unreadable in a report a year later.
 * v3 reorders and renames the screens (sample/steps/sleep are gone, tease/reveal/accuracy are
 * new), so funnels before and after 1.1.0 are not comparable by index.
 */
private val stepNames = listOf("intro", "usage_access", "tease", "reveal", "notifications", "accuracy", "collecting")

/** What the opt-in peek shows. [forToday]: evening shows «so far today», morning shows yesterday. */
private data class RevealData(val forToday: Boolean, val screenMinutes: Int, val unlocks: Int)

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
    var wantSteps by rememberSaveable { mutableStateOf(true) }
    var wantSleep by rememberSaveable { mutableStateOf(true) }
    var reveal by remember { mutableStateOf<RevealData?>(null) }
    // «Читаю историю…» between the permission and the next screen
    var reading by remember { mutableStateOf(false) }

    LaunchedEffect(step) {
        // The waves only speed up for «Смотрю твои данные»; the middle screens stay calm.
        backdrop.scene = BackdropScene.Onboarding(if (step == LAST_STEP) 3 else step.coerceAtMost(2))
        Events.onboardingStep(step, stepNames.getOrElse(step) { "step_$step" })
    }

    val healthAvailable = remember {
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    /** After notifications: the accuracy screen, or straight to the end if Health Connect is missing. */
    fun afterNotifications() {
        if (healthAvailable) {
            step = STEP_ACCURACY
        } else {
            Events.onboardingPermission("health", Events.PermissionResult.Unavailable)
            step = LAST_STEP
        }
    }

    val healthLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        fun report(slug: String, wanted: Boolean, perm: String) = Events.onboardingPermission(
            slug,
            when {
                !wanted -> Events.PermissionResult.Skipped
                granted.contains(perm) -> Events.PermissionResult.Granted
                else -> Events.PermissionResult.Denied
            },
        )
        report("steps", wantSteps, STEPS_READ)
        report("sleep", wantSleep, SLEEP_READ)
        step = LAST_STEP
    }

    // Usage access lives in system settings. Back with access → «Читаю историю…». Back without it →
    // most likely Android's «restricted settings» for sideloaded apps: show how to unlock it
    // (or «Позже») instead of silently moving on without the main data source.
    var usageBlocked by rememberSaveable { mutableStateOf(false) }
    var usageHelp by rememberSaveable { mutableStateOf(false) }

    fun usageGranted() {
        Events.onboardingPermission("usage_access", Events.PermissionResult.Granted)
        usageBlocked = false
        usageHelp = false
        reading = true
    }

    val usageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (context.hasUsageAccess()) {
            usageGranted()
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
        afterNotifications()
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
            afterNotifications()
        } else {
            runCatching { batteryLauncher.launch(batteryOptimizationIntent(context.packageName)) }
                .onFailure {
                    Events.onboardingPermission("battery", Events.PermissionResult.Unavailable)
                    afterNotifications()
                }
        }
    }

    // «Читаю историю…»: a beat of reading, then the «Доступ есть» screen.
    LaunchedEffect(reading) {
        if (reading) {
            delay(if (still) 300L else 1200L)
            reading = false
            step = STEP_TEASE
        }
    }

    // The opt-in peek: what Android already recorded. Before noon today is nearly empty, so show
    // yesterday; from noon show today so far. Read off the main thread; the screen is waiting on
    // nothing — until the numbers arrive the card simply says it is reading.
    LaunchedEffect(step) {
        if (step == STEP_REVEAL && reveal == null) {
            reveal = withContext(Dispatchers.Default) { readRevealData(context) }
        }
    }

    // «Смотрю твои данные» — a short thinking moment, then the orb flies into Home.
    LaunchedEffect(step) {
        if (step == LAST_STEP) {
            // Reported here, before the upload, not after it. The person has answered every step
            // by now — onboarding is done from their side. Reporting it after the wait below made
            // anyone who left during «Смотрю твои данные» look like they never finished: in the
            // first week 20 people saw a forecast while only 13 were counted as having finished,
            // and that is the step the whole acquisition funnel is measured against.
            Events.onboardingFinished()

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
    val ctaText = when {
        reading -> "Читаю историю…"
        step == STEP_ACCURACY && !wantSteps && !wantSleep -> "Начать"
        else -> current.cta
    }

    // Filled in where the main button is built (it needs the launchers declared around it);
    // the orb above it in the layout calls whatever is here at the moment of the tap.
    val primaryAction = remember { arrayOf<() -> Unit>({}) }
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
                    mode = if (reading) OrbMode.Thinking else current.orb,
                    modifier = Modifier
                        .sharedElement(
                            rememberSharedContentState(SharedKeys.ORB),
                            animatedVisibilityScope = animatedScope,
                            boundsTransform = OrbBoundsTransform,
                        )
                        .size(148.dp)
                        // The orb does what the main button does: on a small screen the button
                        // can end up below the fold, and the orb is always in view.
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) { primaryAction[0]() },
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        StepDots(active = dotOf[step], modifier = Modifier.graphicsLayer { alpha = if (step == LAST_STEP) 0f else 1f })

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
                    text = if (s == STEP_REVEAL) revealTitle(reveal) else st.title,
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
                when (st.panel) {
                    Panel.Sample -> {
                        Spacer(Modifier.height(18.dp))
                        ForecastPanel("Прогноз на сегодня", SAMPLE_FORECAST)
                    }
                    Panel.Reveal -> {
                        Spacer(Modifier.height(18.dp))
                        RevealPanel(reveal)
                    }
                    Panel.Notification -> {
                        Spacer(Modifier.height(18.dp))
                        NotificationMock(SAMPLE_FORECAST)
                    }
                    Panel.Accuracy -> {
                        Spacer(Modifier.height(18.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OptionRow("Шаги", "сколько ты двигался за день", wantSteps) { wantSteps = it }
                            OptionRow("Сон", "точное время вместо пауз экрана", wantSleep) { wantSleep = it }
                        }
                    }
                    Panel.None -> Unit
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
                    if (s == STEP_ACCESS) {
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
        }

        Spacer(Modifier.weight(1f))

        // «Подсказать, как» is always one tap away on the access step; the sheet also opens by
        // itself when the person comes back from settings without having switched it on.
        AnimatedVisibility(
            visible = step == STEP_ACCESS && !(usageBlocked || usageHelp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Text(
                text = "Не получается разрешить? Подсказать как",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { usageHelp = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        AnimatedVisibility(
            visible = step == STEP_ACCESS && (usageBlocked || usageHelp),
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
                    // Mandatory step: no «Пропустить», only «Позже». Home then says plainly that
                    // there will be no forecast and keeps a «Разрешить» card for later.
                    Text(
                        text = "Позже — без доступа прогноз не заработает",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                Events.onboardingPermission("usage_access", Events.PermissionResult.Skipped)
                                usageBlocked = false
                                usageHelp = false
                                Events.onboardingFinished()
                                onFinish()
                            }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }

        val btnInteraction = remember { MutableInteractionSource() }
        val btnPressed by btnInteraction.collectIsPressedAsState()
        val btnScale by animateFloatAsState(
            targetValue = if (btnPressed) 0.97f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessHigh, dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "btnScale",
        )
        val filling = step == LAST_STEP || reading
        val progress by animateFloatAsState(
            targetValue = if (filling) 1f else 0f,
            animationSpec = when {
                reading -> tween(if (still) 300 else 1200, easing = FastOutSlowInEasing)
                step == LAST_STEP -> tween(1700, easing = FastOutSlowInEasing)
                else -> tween(0)
            },
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
                val onPrimary: () -> Unit = primary@{
                        if (reading || step == LAST_STEP) return@primary
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        when (step) {
                            STEP_HELLO -> step = STEP_ACCESS
                            STEP_ACCESS -> if (context.hasUsageAccess()) {
                                // Already granted — a reinstall, or it was given earlier. The
                                // launcher never runs, so the funnel has to be told here or this
                                // person silently disappears from the step.
                                usageGranted()
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
                                        step = STEP_TEASE
                                    }
                                }
                            }
                            STEP_TEASE -> step = STEP_REVEAL
                            STEP_REVEAL -> step = STEP_NOTIFICATIONS
                            STEP_NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                afterNotifications()
                            }
                            STEP_ACCURACY -> {
                                val perms = buildSet {
                                    if (wantSteps) add(STEPS_READ)
                                    if (wantSleep) add(SLEEP_READ)
                                }
                                if (perms.isEmpty()) {
                                    Events.onboardingPermission("steps", Events.PermissionResult.Skipped)
                                    Events.onboardingPermission("sleep", Events.PermissionResult.Skipped)
                                    step = LAST_STEP
                                } else {
                                    healthLauncher.launch(perms + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
                                }
                            }
                        }
                    }
                primaryAction[0] = onPrimary
                Button(
                    onClick = onPrimary,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = AppShapes.button,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    interactionSource = btnInteraction,
                ) {
                    Text(
                        text = ctaText,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
                // «Готовлю…» / «Читаю…» fill that runs while the orb is thinking
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

        // A text button only where the app genuinely works without the answer. Interviews
        // named a forced walkthrough as a reason to uninstall, and an optional step that cannot
        // be declined is a forced one. Usage access is the exception and has none here.
        val skip = steps[step].skipLabel
        AnimatedVisibility(
            visible = skip != null && !reading,
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
                            STEP_TEASE -> step = STEP_NOTIFICATIONS
                            STEP_NOTIFICATIONS -> {
                                Events.onboardingPermission("notifications", Events.PermissionResult.Skipped)
                                afterNotifications()
                            }
                            STEP_ACCURACY -> {
                                Events.onboardingPermission("steps", Events.PermissionResult.Skipped)
                                Events.onboardingPermission("sleep", Events.PermissionResult.Skipped)
                                step = LAST_STEP
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

// ─── The opt-in peek ─────────────────────────────────────────────────────────

private fun readRevealData(context: Context): RevealData? {
    val zone = ZoneId.systemDefault()
    val now = LocalDateTime.now()
    val forToday = now.hour >= 12
    val todayStart = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
    val from = if (forToday) todayStart else LocalDate.now().minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val to = if (forToday) System.currentTimeMillis() else todayStart
    val snap = UsageStatsCollector(context).collect(from, to) ?: return null
    return RevealData(forToday, snap.screenMinutes, snap.unlocks)
}

private fun revealTitle(r: RevealData?): String =
    if (r == null || r.forToday) "Вот твой сегодняшний день" else "Вот твой вчерашний день"

private fun formatScreenTime(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h == 0) "$m мин" else "$h ч ${m.toString().padStart(2, '0')} мин"
}

private fun unlocksText(n: Int): String {
    val word = when {
        n % 100 in 11..14 -> "разблокировок"
        n % 10 == 1 -> "разблокировка"
        n % 10 in 2..4 -> "разблокировки"
        else -> "разблокировок"
    }
    return "$n $word"
}

// ─── Panels ──────────────────────────────────────────────────────────────────

/**
 * The brand-gradient card the real forecast uses, not colorScheme.primary: that is the CTA's
 * colour, so the two merged into one block — and its onPrimary is dark in the dark theme while
 * this card's text is white. The gradient is fixed in both themes, so white stays correct and
 * the sample looks like what actually arrives.
 */
@Composable
private fun PanelCard(kicker: String, chip: String?, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.cardHero)
            .background(Brush.linearGradient(listOf(Primary, Color(0xFF8B7CF8))))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = kicker,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = .75f),
                fontWeight = FontWeight.SemiBold,
            )
            if (chip != null) {
                Surface(shape = RoundedCornerShape(20.dp), color = Color.White.copy(alpha = .22f)) {
                    Text(
                        text = chip,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
        content()
    }
}

@Composable
private fun ForecastPanel(kicker: String, text: String) {
    PanelCard(kicker, chip = "пример") {
        Text(text = text, style = MaterialTheme.typography.bodyLarge, color = Color.White, lineHeight = 24.sp)
    }
}

/** The one number Android already recorded. No chart, no pattern — a light touch on purpose. */
@Composable
private fun RevealPanel(data: RevealData?) {
    PanelCard(
        kicker = if (data == null || data.forToday) "Сегодня, до сих пор" else "Вчера",
        chip = null,
    ) {
        if (data == null || (data.screenMinutes == 0 && data.unlocks == 0)) {
            Text(
                text = "Android пока отдал мало истории — картина начнёт собираться с сегодняшнего дня.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                lineHeight = 24.sp,
            )
        } else {
            Text(
                text = formatScreenTime(data.screenMinutes),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
            )
            Text(
                text = "у экрана · ${unlocksText(data.unlocks)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = .9f),
            )
            // Not «data stays on the phone»: the worker sends daily snapshots to the server.
            Text(
                text = "Это Android записал сам, я только прочитал историю на телефоне.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = .8f),
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun NotificationMock(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(16.dp).clip(RoundedCornerShape(50)).background(Primary))
                Text(
                    text = "Companion · сейчас",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Прогноз на сегодня",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onChange(!checked) },
        shape = RoundedCornerShape(16.dp),
        color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun StepDots(active: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (0 until DOT_COUNT).forEach { i ->
            val on = i == active
            val dotWidth by animateDpAsState(
                targetValue = if (on) 20.dp else 8.dp,
                animationSpec = spring(dampingRatio = .6f, stiffness = Spring.StiffnessMedium),
                label = "dot$i",
            )
            Box(
                modifier = Modifier
                    .size(dotWidth, 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
            )
        }
    }
}
