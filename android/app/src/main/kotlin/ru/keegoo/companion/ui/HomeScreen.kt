package ru.keegoo.companion.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ru.keegoo.companion.BuildConfig
import ru.keegoo.companion.data.api.model.SignalDto
import ru.keegoo.companion.domain.forecast.ForecastFact
import ru.keegoo.companion.notifications.EveningCopies
import ru.keegoo.companion.notifications.MorningCopies
import ru.keegoo.companion.notifications.showCheckinNotification
import ru.keegoo.companion.notifications.showMorningNotification
import ru.keegoo.companion.ui.home.CheckInSection
import ru.keegoo.companion.ui.home.HomeUiState
import ru.keegoo.companion.ui.home.HomeViewModel
import ru.keegoo.companion.ui.home.StatKind
import ru.keegoo.companion.ui.home.StatSheet
import ru.keegoo.companion.ui.home.StatsRow
import ru.keegoo.companion.ui.home.statEntries
import ru.keegoo.companion.ui.motion.BackdropScene
import ru.keegoo.companion.ui.motion.CardBoundsTransform
import ru.keegoo.companion.ui.motion.CompanionOrb
import ru.keegoo.companion.ui.motion.LocalBackdrop
import ru.keegoo.companion.ui.motion.OrbBoundsTransform
import ru.keegoo.companion.ui.motion.OrbMode
import ru.keegoo.companion.ui.motion.SharedKeys
import ru.keegoo.companion.ui.theme.AppShapes
import ru.keegoo.companion.ui.theme.Primary
import java.time.LocalTime

private enum class HomeBlock { Morning, Stats, CheckIn }

// M3 "emphasized decelerate" — for things arriving on screen
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalAnimationApi::class)
@Composable
fun HomeScreen(
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    onOpenProfile: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val backdrop = LocalBackdrop.current

    LaunchedEffect(Unit) { backdrop.scene = BackdropScene.Home }
    LaunchedEffect(state.checkedIn) { backdrop.feel = state.checkedIn }

    // Fresh numbers every time the person comes back to the app
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Scrolling kicks the waves, like on the landing
    val waveKick = remember(backdrop) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                backdrop.kick(consumed.y)
                return Offset.Zero
            }
        }
    }

    // Morning: forecast first. Evening: the check-in moves to the top.
    var evening by rememberSaveable { mutableStateOf(isEveningNow()) }
    var openStat by rememberSaveable { mutableStateOf<StatKind?>(null) }
    var lastStat by rememberSaveable { mutableStateOf(StatKind.Screen) }
    BackHandler(enabled = openStat != null) { openStat = null }

    val blocks = if (evening) {
        listOf(HomeBlock.CheckIn, HomeBlock.Morning, HomeBlock.Stats)
    } else {
        listOf(HomeBlock.Morning, HomeBlock.Stats, HomeBlock.CheckIn)
    }
    val bars = WindowInsets.systemBars.asPaddingValues()
    val entries = statEntries(state)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(waveKick),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = bars.calculateTopPadding() + 16.dp,
                bottom = bars.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "top") {
                TopBar(
                    sharedScope = sharedScope,
                    animatedScope = animatedScope,
                    greeting = greeting(evening, state.name),
                    hasQuestions = state.unansweredQuestions > 0,
                    onOrbClick = onOpenProfile,
                )
            }
            items(blocks, key = { it.name }) { block ->
                // Blocks below the hero card rise in one after another once the orb has landed.
                val enterDelay = if (block == HomeBlock.Stats) 220 else 300
                val rise = with(animatedScope) {
                    Modifier.animateEnterExit(
                        enter = fadeIn(tween(360, delayMillis = enterDelay)) +
                            slideInVertically(tween(460, delayMillis = enterDelay, easing = EmphasizedDecelerate)) { it / 4 },
                        exit = fadeOut(tween(120)),
                    )
                }
                Box(Modifier.animateItem()) {
                    when (block) {
                        HomeBlock.Morning -> with(sharedScope) {
                            MorningCard(
                                modifier = Modifier.sharedBounds(
                                    rememberSharedContentState(SharedKeys.HERO_CARD),
                                    animatedVisibilityScope = animatedScope,
                                    boundsTransform = CardBoundsTransform,
                                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                                    clipInOverlayDuringTransition = OverlayClip(AppShapes.cardHero),
                                ),
                                state = state,
                                onOpenUsageAccess = {
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                    } catch (_: ActivityNotFoundException) {
                                        // some OEM builds hide this screen; nothing else to open
                                    }
                                },
                            )
                        }
                        HomeBlock.Stats -> StatsRow(
                            modifier = rise,
                            sharedScope = sharedScope,
                            entries = entries,
                            openStat = openStat,
                            onOpen = { kind -> lastStat = kind; openStat = kind },
                        )
                        HomeBlock.CheckIn -> CheckInSection(
                            modifier = rise,
                            selected = state.checkedIn,
                            tags = state.tags,
                            highlighted = evening,
                            onSelect = vm::onCheckIn,
                            onToggleTag = vm::onToggleTag,
                        )
                    }
                }
            }
            if (BuildConfig.DEBUG) {
                item(key = "debug") {
                    DebugPanel(
                        evening = evening,
                        onToggleEvening = { evening = !evening },
                        onResetCheckIn = vm::resetCheckIn,
                    )
                }
            }
        }

        // ── Stat detail: the tile grows into this sheet (container transform) ──
        AnimatedVisibility(
            visible = openStat != null,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = .28f))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { openStat = null }
            )
        }
        AnimatedVisibility(
            visible = openStat != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 10.dp, end = 10.dp, bottom = bars.calculateBottomPadding() + 10.dp),
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(300)),
        ) {
            val entry = entries.first { it.kind == lastStat }
            with(sharedScope) {
                StatSheet(
                    modifier = Modifier.sharedBounds(
                        rememberSharedContentState(SharedKeys.stat(lastStat.name)),
                        animatedVisibilityScope = this@AnimatedVisibility,
                        boundsTransform = CardBoundsTransform,
                        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                        clipInOverlayDuringTransition = OverlayClip(AppShapes.sheet),
                    ),
                    entry = entry,
                    detail = state.details[lastStat],
                    onClose = { openStat = null },
                )
            }
        }
    }
}

private fun isEveningNow(): Boolean = LocalTime.now().hour.let { it >= 18 || it < 5 }

private fun greeting(evening: Boolean, name: String?): String {
    val h = LocalTime.now().hour
    val base = when {
        evening -> "Добрый вечер"
        h < 5 -> "Доброй ночи"
        h < 12 -> "Доброе утро"
        h < 18 -> "Добрый день"
        else -> "Добрый вечер"
    }
    return if (name != null) "$base,\n$name" else base
}

// ─── TopBar ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun TopBar(
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    greeting: String,
    hasQuestions: Boolean,
    onOrbClick: () -> Unit,
) {
    val view = LocalView.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = greeting,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        // The onboarding orb lands here; tapping it opens «Расскажи о себе».
        with(sharedScope) {
            CompanionOrb(
                mode = OrbMode.Calm,
                badge = hasQuestions,
                modifier = Modifier
                    .padding(top = 4.dp, start = 12.dp)
                    .sharedElement(
                        rememberSharedContentState(SharedKeys.ORB),
                        animatedVisibilityScope = animatedScope,
                        boundsTransform = OrbBoundsTransform,
                    )
                    .size(48.dp)
                    .clip(AppShapes.circle)
                    .clickable {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onOrbClick()
                    },
            )
        }
    }
}

// ─── Shimmer ──────────────────────────────────────────────────────────────────

@Composable
private fun ShimmerBox(modifier: Modifier, baseColor: Color) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        initialValue = -500f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer_x",
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .drawBehind {
                drawRect(
                    Brush.linearGradient(
                        colorStops = arrayOf(
                            0.0f to baseColor,
                            0.4f to baseColor.copy(alpha = 0.55f),
                            0.5f to baseColor.copy(alpha = 0.30f),
                            0.6f to baseColor.copy(alpha = 0.55f),
                            1.0f to baseColor,
                        ),
                        start = Offset(x, 0f),
                        end = Offset(x + 500f, 0f),
                    )
                )
            }
    )
}

// ─── MorningCard ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun MorningCard(
    modifier: Modifier,
    state: HomeUiState,
    onOpenUsageAccess: () -> Unit,
) {
    var whyOpen by rememberSaveable { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (whyOpen) 180f else 0f, tween(280), label = "chevron")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShapes.cardHero)
            .background(Brush.linearGradient(listOf(Primary, Color(0xFF8B7CF8))))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Прогноз на сегодня",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.75f),
            fontWeight = FontWeight.SemiBold,
        )
        when {
            state.isLoading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val sh = Color.White.copy(alpha = 0.25f)
                ShimmerBox(Modifier.fillMaxWidth().height(14.dp), sh)
                ShimmerBox(Modifier.fillMaxWidth(0.88f).height(14.dp), sh)
                ShimmerBox(Modifier.fillMaxWidth(0.65f).height(14.dp), sh)
            }
            !state.hasUsageAccess && state.forecast == null -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Чтобы видеть экран и разблокировки, нужен доступ к истории использования.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                )
                Box(
                    Modifier
                        .clip(AppShapes.button)
                        .border(1.dp, Color.White.copy(alpha = .6f), AppShapes.button)
                        .clickable(onClick = onOpenUsageAccess)
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text("Открыть настройки", style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
            }
            state.forecast != null -> Text(
                text = state.forecast,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            else -> Text(
                text = "Данных за сегодня пока мало. Загляни чуть позже — здесь появится сводка дня.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = .9f),
            )
        }

        if (state.facts.isNotEmpty() || state.signals.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { whyOpen = !whyOpen }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Почему такой прогноз",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = .9f),
                )
                Text(
                    text = "▾",
                    color = Color.White.copy(alpha = .9f),
                    modifier = Modifier.graphicsLayer { rotationZ = chevron },
                )
            }
            AnimatedVisibility(
                visible = whyOpen,
                enter = expandVertically(spring(dampingRatio = 1f, stiffness = 400f)) + fadeIn(),
                exit = shrinkVertically(tween(220)) + fadeOut(tween(150)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // What the server found in yesterday's data — the same list the model got
                    if (state.signals.isNotEmpty()) {
                        Text(
                            text = "Что было заметно вчера",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = .75f),
                        )
                        state.signals.forEachIndexed { i, s ->
                            SignalRow(
                                signal = s,
                                modifier = Modifier.animateEnterExit(
                                    enter = fadeIn(tween(260, delayMillis = i * 60)) +
                                        slideInVertically(tween(360, delayMillis = i * 60, easing = EmphasizedDecelerate)) { it / 2 },
                                ),
                            )
                        }
                        if (state.facts.isNotEmpty()) {
                            Text(
                                text = "Сегодня к этому часу",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = .75f),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    state.facts.forEachIndexed { i, fact ->
                        FactRow(
                            fact = fact,
                            index = i,
                            modifier = Modifier.animateEnterExit(
                                enter = fadeIn(tween(260, delayMillis = i * 60)) +
                                    slideInVertically(tween(360, delayMillis = i * 60, easing = EmphasizedDecelerate)) { it / 2 },
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalRow(signal: SignalDto, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (signal.positive) "＋" else "•",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = if (signal.positive) 1f else .8f),
            fontWeight = FontWeight.Bold,
        )
        Column {
            Text(
                text = signal.title,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = signal.detail,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = .8f),
            )
        }
    }
}

/** Bars grow from zero to their value each time the panel opens, one after another. */
@Composable
private fun FactRow(fact: ForecastFact, index: Int, modifier: Modifier = Modifier) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(fact) {
        grow.snapTo(0f)
        delay(150L + index * 90L)
        grow.animateTo(1f, spring(dampingRatio = .75f, stiffness = 120f))
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = fact.label + "  ",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = .9f),
            )
            Text(
                text = fact.value,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = fact.usual,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = .75f),
            )
        }
        Canvas(Modifier.fillMaxWidth().height(4.dp)) {
            val p = grow.value
            val r = CornerRadius(size.height / 2f)
            drawRoundRect(Color.White.copy(alpha = .2f), cornerRadius = r)
            drawRoundRect(Color.White, size = Size(size.width * fact.fraction * p, size.height), cornerRadius = r)
            if (fact.usualFraction >= 0f) {
                val mx = size.width * fact.usualFraction
                drawLine(
                    Color.White.copy(alpha = .75f * p.coerceIn(0f, 1f)),
                    Offset(mx, -3.dp.toPx()), Offset(mx, size.height + 3.dp.toPx()),
                    2.dp.toPx(),
                )
            }
        }
    }
}

// ─── Debug ────────────────────────────────────────────────────────────────────

@Composable
private fun DebugPanel(
    evening: Boolean,
    onToggleEvening: () -> Unit,
    onResetCheckIn: () -> Unit,
) {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (open) 180f else 0f, tween(280), label = "debugChevron")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .8f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Отладка",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "▾",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { rotationZ = chevron },
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(spring(dampingRatio = 1f, stiffness = 400f)) + fadeIn(),
            exit = shrinkVertically(tween(220)) + fadeOut(tween(150)),
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DebugButton("🌅 Утреннее", Modifier.weight(1f)) {
                        showMorningNotification(context, MorningCopies.random())
                    }
                    DebugButton("🌙 Вечернее", Modifier.weight(1f)) {
                        showCheckinNotification(context, EveningCopies.random())
                    }
                }
                // Server-issued id — put it into DEV_USER_IDS on the server to exclude this phone from metrics
                val userId = remember {
                    context.getSharedPreferences("device_auth", android.content.Context.MODE_PRIVATE)
                        .getString("user_id", null) ?: "ещё не зарегистрирован"
                }
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        text = "user_id: $userId",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DebugButton(if (evening) "Показать утро" else "Показать вечер", Modifier.weight(1f), onToggleEvening)
                    DebugButton("Сбросить чек-ин", Modifier.weight(1f), onResetCheckIn)
                }
            }
        }
    }
}

@Composable
private fun DebugButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = AppShapes.button,
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}
