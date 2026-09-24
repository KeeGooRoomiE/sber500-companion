package ru.keegoo.companion.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ru.keegoo.companion.BuildConfig
import ru.keegoo.companion.domain.model.DayFeel
import ru.keegoo.companion.notifications.showCheckinNotification
import ru.keegoo.companion.notifications.showMorningNotification
import ru.keegoo.companion.ui.home.CheckInTags
import ru.keegoo.companion.ui.home.ForecastFact
import ru.keegoo.companion.ui.home.HomeUiState
import ru.keegoo.companion.ui.home.HomeViewModel
import ru.keegoo.companion.ui.home.StatDetail
import ru.keegoo.companion.ui.home.StatKind
import ru.keegoo.companion.ui.motion.BackdropScene
import ru.keegoo.companion.ui.motion.CardBoundsTransform
import ru.keegoo.companion.ui.motion.CompanionOrb
import ru.keegoo.companion.ui.motion.LocalBackdrop
import ru.keegoo.companion.ui.motion.OrbBoundsTransform
import ru.keegoo.companion.ui.motion.OrbMode
import ru.keegoo.companion.ui.motion.SharedKeys
import ru.keegoo.companion.ui.theme.AppShapes
import ru.keegoo.companion.ui.theme.FeelingHard
import ru.keegoo.companion.ui.theme.FeelingMeh
import ru.keegoo.companion.ui.theme.FeelingOk
import ru.keegoo.companion.ui.theme.HealthLevel
import ru.keegoo.companion.ui.theme.Primary
import ru.keegoo.companion.ui.theme.color
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

private enum class HomeBlock { Morning, Stats, CheckIn }

// M3 "emphasized decelerate" — for things arriving on screen
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalAnimationApi::class)
@Composable
fun HomeScreen(
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val backdrop = LocalBackdrop.current

    LaunchedEffect(Unit) { backdrop.scene = BackdropScene.Home }
    LaunchedEffect(state.checkedIn) { backdrop.feel = state.checkedIn }

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
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = bars.calculateTopPadding() + 16.dp,
                bottom = bars.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "top") {
                TopBar(sharedScope, animatedScope, greeting(evening), isMock = BuildConfig.DEBUG)
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
                                onToggleAction = vm::onToggleAction,
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
                    DebugCard(
                        evening = evening,
                        onToggleEvening = { evening = !evening },
                        onMorning = { showMorningNotification(context) },
                        onCheckin = { showCheckinNotification(context) },
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

private fun greeting(evening: Boolean): String {
    if (evening) return "Добрый вечер"
    val h = LocalTime.now().hour
    return when {
        h < 5 -> "Доброй ночи"
        h < 12 -> "Доброе утро"
        h < 18 -> "Добрый день"
        else -> "Добрый вечер"
    }
}

// ─── TopBar ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun TopBar(
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    greeting: String,
    isMock: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Компаньон",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isMock) {
                    Surface(shape = AppShapes.tag, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            text = "MOCK",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        // The onboarding orb lands here.
        with(sharedScope) {
            CompanionOrb(
                mode = OrbMode.Calm,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .sharedElement(
                        rememberSharedContentState(SharedKeys.ORB),
                        animatedVisibilityScope = animatedScope,
                        boundsTransform = OrbBoundsTransform,
                    )
                    .size(44.dp),
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
    onToggleAction: () -> Unit,
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
            state.morningMessage != null -> Text(
                text = state.morningMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Первый прогноз — завтра утром",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = 0.95f),
                )
                Text(
                    text = "Носи телефон сегодня, чтобы собрались данные за день.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.80f),
                )
            }
        }

        if (state.action != null) {
            ActionRow(text = state.action, done = state.actionDone, onToggle = onToggleAction)
        }

        if (state.facts.isNotEmpty()) {
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
                    state.facts.forEachIndexed { i, fact ->
                        FactRow(
                            fact = fact,
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
private fun ActionRow(text: String, done: Boolean, onToggle: () -> Unit) {
    val view = LocalView.current
    val progress by animateFloatAsState(if (done) 1f else 0f, tween(320, easing = FastOutSlowInEasing), label = "tick")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = .16f))
            .clickable {
                if (!done) view.confirmHaptic()
                onToggle()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = 1.8.dp.toPx()
            drawCircle(Color.White.copy(alpha = .25f * progress), radius = size.minDimension / 2f)
            drawCircle(Color.White.copy(alpha = .75f), radius = size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
            // Tick drawn progressively: two segments, first then second
            val p1 = Offset(size.width * .30f, size.height * .52f)
            val p2 = Offset(size.width * .45f, size.height * .66f)
            val p3 = Offset(size.width * .72f, size.height * .36f)
            val w = 2.2.dp.toPx()
            val first = (progress / .4f).coerceIn(0f, 1f)
            val second = ((progress - .4f) / .6f).coerceIn(0f, 1f)
            if (first > 0f) drawLine(Color.White, p1, p1 + (p2 - p1) * first, w, StrokeCap.Round)
            if (second > 0f) drawLine(Color.White, p2, p2 + (p3 - p2) * second, w, StrokeCap.Round)
        }
        Column {
            Text(text = text, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            Text(
                text = if (done) "Сделано · вечером спрошу, как было" else "Одно действие на сегодня",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = .75f),
            )
        }
    }
}

@Composable
private fun FactRow(fact: ForecastFact, modifier: Modifier = Modifier) {
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
            val r = CornerRadius(size.height / 2f)
            drawRoundRect(Color.White.copy(alpha = .2f), cornerRadius = r)
            drawRoundRect(Color.White, size = Size(size.width * fact.fraction, size.height), cornerRadius = r)
            val mx = size.width * fact.usualFraction
            drawLine(Color.White.copy(alpha = .75f), Offset(mx, -3.dp.toPx()), Offset(mx, size.height + 3.dp.toPx()), 2.dp.toPx())
        }
    }
}

// ─── Stats ────────────────────────────────────────────────────────────────────

private data class StatEntry(
    val kind: StatKind,
    val label: String,
    val value: Int?,
    val isTime: Boolean,
    val warnAbove: Int? = null,
    val warnBelow: Int? = null,
)

private fun statEntries(s: HomeUiState) = listOf(
    StatEntry(StatKind.Screen, "Экран", s.screenMin, isTime = true, warnAbove = 300),
    StatEntry(StatKind.Sleep, "Сон", s.sleepMin, isTime = true, warnBelow = 420),
    StatEntry(StatKind.Unlocks, "Разблок.", s.unlocks, isTime = false, warnAbove = 80),
)

private fun StatEntry.level(v: Int? = value): HealthLevel? {
    if (v == null) return null
    return when {
        warnAbove != null && v > (warnAbove * 1.3f).toInt() -> HealthLevel.Bad
        warnAbove != null && v > warnAbove -> HealthLevel.Warn
        warnBelow != null && v < (warnBelow * 0.7f).toInt() -> HealthLevel.Bad
        warnBelow != null && v < warnBelow -> HealthLevel.Warn
        else -> HealthLevel.Good
    }
}

private fun formatValue(v: Int, isTime: Boolean): String =
    if (isTime) "${v / 60}ч ${v % 60}м" else v.toString()

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun StatsRow(
    modifier: Modifier,
    sharedScope: SharedTransitionScope,
    entries: List<StatEntry>,
    openStat: StatKind?,
    onOpen: (StatKind) -> Unit,
) {
    val view = LocalView.current
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEachIndexed { index, entry ->
            // Count-up lives outside AnimatedVisibility, so it doesn't replay after the sheet closes.
            var target by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(entry.value) {
                if (entry.value != null) {
                    delay(350L + index * 90L)
                    target = entry.value.toFloat()
                }
            }
            val shown by animateFloatAsState(target, tween(900, easing = FastOutSlowInEasing), label = "statVal$index")

            Box(Modifier.weight(1f)) {
                StatSlot(
                    sharedScope = sharedScope,
                    entry = entry,
                    visible = openStat != entry.kind,
                    displayValue = shown.toInt(),
                    onOpen = {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onOpen(entry.kind)
                    },
                )
            }
        }
    }
}

// A separate function on purpose: inside Row the RowScope overload of AnimatedVisibility
// would be picked. The tile hides while its sheet is open so the two can share bounds.
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun StatSlot(
    sharedScope: SharedTransitionScope,
    entry: StatEntry,
    visible: Boolean,
    displayValue: Int,
    onOpen: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200, delayMillis = 100)),
        exit = fadeOut(tween(120)),
    ) {
        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.95f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessHigh, dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "tileScale",
        )
        StatTile(
            modifier = with(sharedScope) {
                Modifier.sharedBounds(
                    rememberSharedContentState(SharedKeys.stat(entry.kind.name)),
                    animatedVisibilityScope = this@AnimatedVisibility,
                    boundsTransform = CardBoundsTransform,
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                    clipInOverlayDuringTransition = OverlayClip(AppShapes.card),
                )
            }
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clickable(interactionSource = source, indication = null, onClick = onOpen),
            entry = entry,
            displayValue = displayValue,
        )
    }
}

@Composable
private fun StatTile(modifier: Modifier, entry: StatEntry, displayValue: Int) {
    val level = entry.level()
    val levelColor = level?.color()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(2.dp, AppShapes.card)
            .clip(AppShapes.card)
            .background(MaterialTheme.colorScheme.surface)
            .background(levelColor?.copy(alpha = .10f) ?: Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(AppShapes.circle)
                .background(levelColor ?: MaterialTheme.colorScheme.outline)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (entry.value == null) "—" else formatValue(displayValue, entry.isTime),
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "\"tnum\"",
            ),
            color = levelColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = entry.label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

// ─── Stat sheet ───────────────────────────────────────────────────────────────

private val RuLocale = Locale("ru")

@Composable
private fun StatSheet(modifier: Modifier, entry: StatEntry, detail: StatDetail?, onClose: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShapes.sheet)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(remember { MutableInteractionSource() }, indication = null) {} // swallow taps
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.label.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(32.dp)
                    .clip(AppShapes.circle)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text("×", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = entry.value?.let { formatValue(it, entry.isTime) } ?: "—",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "\"tnum\"",
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "вчера",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        val week = detail?.week.orEmpty()
        if (week.size == 7) {
            WeekChart(week = week, isTime = entry.isTime)
        } else {
            Text(
                text = "История по дням появится через несколько дней.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        detail?.note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WeekChart(week: List<Int>, isTime: Boolean) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(week) {
        grow.snapTo(0f)
        delay(150)
        grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    val max = week.max() * 1.12f
    val avg = week.average().toFloat()
    val barColor = MaterialTheme.colorScheme.primaryContainer
    val todayColor = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val days = (7 downTo 1).map { back ->
        LocalDate.now().minusDays(back.toLong()).dayOfWeek
            .getDisplayName(TextStyle.SHORT, RuLocale)
            .replaceFirstChar { it.titlecase(RuLocale) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "среднее за неделю ${formatValue(avg.toInt(), isTime)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
        )
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            val gap = 8.dp.toPx()
            val barW = (size.width - gap * 6) / 7f
            week.forEachIndexed { i, v ->
                // Staggered growth: each bar starts slightly after the previous one
                val p = ((grow.value - i * .05f) / .7f).coerceIn(0f, 1f)
                val h = size.height * (v / max) * p
                drawRoundRect(
                    color = if (i == 6) todayColor else barColor,
                    topLeft = Offset(i * (barW + gap), size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                )
            }
            val y = size.height * (1f - avg / max)
            drawLine(
                color = lineColor.copy(alpha = .6f),
                start = Offset(0f, y), end = Offset(size.width, y),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            days.forEach {
                Text(
                    text = it,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ─── Check-in ─────────────────────────────────────────────────────────────────

private data class FeelOption(val feel: DayFeel, val emoji: String, val label: String, val color: Color)

private val FeelOptions = listOf(
    FeelOption(DayFeel.OK, "😊", "Отлично", FeelingOk),
    FeelOption(DayFeel.MEH, "😐", "Нормально", FeelingMeh),
    FeelOption(DayFeel.HARD, "😮‍💨", "Тяжело", FeelingHard),
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalAnimationApi::class)
@Composable
private fun CheckInSection(
    modifier: Modifier,
    selected: DayFeel?,
    tags: Set<String>,
    highlighted: Boolean,
    onSelect: (DayFeel) -> Unit,
    onToggleTag: (String) -> Unit,
) {
    val view = LocalView.current
    val ringColor = MaterialTheme.colorScheme.primary
    val ring by animateFloatAsState(if (highlighted) 1f else 0f, tween(400), label = "ring")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShapes.cardHero)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.5.dp, ringColor.copy(alpha = ring), AppShapes.cardHero)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Как прошёл день?",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FeelOptions.forEach { option ->
                // The chosen answer takes the room, the others shrink to their emoji.
                val weight by animateFloatAsState(
                    targetValue = when (selected) {
                        null -> 1f
                        option.feel -> 2.4f
                        else -> .55f
                    },
                    animationSpec = spring(dampingRatio = .7f, stiffness = 500f),
                    label = "w${option.feel}",
                )
                FeelButton(
                    modifier = Modifier.weight(weight),
                    option = option,
                    selected = selected,
                    onSelect = {
                        view.confirmHaptic()
                        onSelect(it)
                    },
                )
            }
        }
        AnimatedVisibility(
            visible = selected != null,
            enter = expandVertically(spring(dampingRatio = 1f, stiffness = 400f)) + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Что повлияло? Можно пропустить",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CheckInTags.forEachIndexed { i, tag ->
                        TagChip(
                            text = tag,
                            on = tag in tags,
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onToggleTag(tag)
                            },
                            modifier = Modifier.animateEnterExit(
                                enter = fadeIn(tween(240, delayMillis = 120 + i * 35)) +
                                    slideInVertically(spring(dampingRatio = .7f, stiffness = 500f)) { it / 2 },
                            ),
                        )
                    }
                }
                Text(
                    text = "Сохранено ✓ · учту в завтрашнем прогнозе",
                    style = MaterialTheme.typography.labelMedium,
                    color = HealthLevel.Good.color(),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun FeelButton(
    modifier: Modifier,
    option: FeelOption,
    selected: DayFeel?,
    onSelect: (DayFeel) -> Unit,
) {
    val isSelected = selected == option.feel
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "feelScale",
    )
    val emojiScale by animateFloatAsState(
        targetValue = when {
            isSelected -> 1.12f
            selected != null -> .82f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = .5f, stiffness = 400f),
        label = "emojiScale",
    )
    val labelAlpha by animateFloatAsState(
        targetValue = if (selected == null || isSelected) 1f else 0f,
        animationSpec = tween(200),
        label = "labelAlpha",
    )

    Column(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(AppShapes.chip)
            .background(
                if (isSelected) option.color.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(interactionSource = interactionSource, indication = null) { onSelect(option.feel) }
            .padding(vertical = 14.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = option.emoji,
            fontSize = 26.sp,
            modifier = Modifier.graphicsLayer { scaleX = emojiScale; scaleY = emojiScale },
        )
        Text(
            text = option.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) option.color else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.graphicsLayer { alpha = labelAlpha },
        )
    }
}

@Composable
private fun TagChip(text: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .clip(AppShapes.chip)
            .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .border(1.dp, if (on) primary else MaterialTheme.colorScheme.outline, AppShapes.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = if (on) primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** CONFIRM on Android 11+, a light tick before that. */
private fun android.view.View.confirmHaptic() {
    performHapticFeedback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.CLOCK_TICK
    )
}

// ─── Debug ────────────────────────────────────────────────────────────────────

@Composable
private fun DebugCard(
    evening: Boolean,
    onToggleEvening: () -> Unit,
    onMorning: () -> Unit,
    onCheckin: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Отладка",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DebugButton("🌅 Прогноз", Modifier.weight(1f), onMorning)
            DebugButton("🌙 Чек-ин", Modifier.weight(1f), onCheckin)
        }
        DebugButton(if (evening) "Показать утро" else "Показать вечер", Modifier.fillMaxWidth(), onToggleEvening)
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
