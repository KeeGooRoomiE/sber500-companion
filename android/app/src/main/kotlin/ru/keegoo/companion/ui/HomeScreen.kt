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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import ru.keegoo.companion.domain.profile.Depth
import ru.keegoo.companion.data.api.model.SignalDto
import ru.keegoo.companion.domain.forecast.ForecastFact
import ru.keegoo.companion.ui.home.CheckInSection
import ru.keegoo.companion.ui.home.CheckInTags
import ru.keegoo.companion.ui.home.InsightUi
import ru.keegoo.companion.ui.home.Insights
import ru.keegoo.companion.ui.home.DailyQuestionBanner
import ru.keegoo.companion.ui.home.MiddayCard
import ru.keegoo.companion.ui.home.insightKey
import ru.keegoo.companion.ui.permissions.RestrictedSettingsSteps
import ru.keegoo.companion.ui.permissions.appInfoIntent
import ru.keegoo.companion.ui.permissions.usageAccessIntent
import ru.keegoo.companion.ui.home.DayTimelineCard
import ru.keegoo.companion.ui.home.ExploreSheet
import ru.keegoo.companion.ui.home.ExploreUi
import ru.keegoo.companion.ui.home.FeedbackRow
import ru.keegoo.companion.ui.home.HistorySection
import ru.keegoo.companion.ui.home.ReviewSheet
import ru.keegoo.companion.ui.home.ReviewUi
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
import ru.keegoo.companion.ui.motion.rememberOrbGesture
import ru.keegoo.companion.ui.home.OrbBubble
import ru.keegoo.companion.ui.home.OrbInvitePool
import ru.keegoo.companion.ui.motion.SharedKeys
import ru.keegoo.companion.ui.theme.AppShapes
import ru.keegoo.companion.ui.theme.Primary
import java.time.LocalDate
import java.util.Locale
import java.time.LocalTime

private enum class HomeBlock { Morning, Midday, Stats, Timeline, CheckIn }

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
    val evening = remember { isEveningNow() }
    // «Как прошёл день?» only makes sense once there is a day to speak of. Between 05:00 and
    // the evening there is nothing to report yet, and someone opening the app at 00:01 was
    // being asked about a day one minute old — that one now counts as yesterday (checkInDay),
    // so the question is still worth asking. An answer already given stays on screen either
    // way, otherwise it would look lost.
    val askCheckIn = remember(state.checkedIn) { evening || state.checkedIn != null }
    var openStat by rememberSaveable { mutableStateOf<StatKind?>(null) }
    var lastStat by rememberSaveable { mutableStateOf(StatKind.Screen) }
    BackHandler(enabled = openStat != null) { openStat = null }
    BackHandler(enabled = state.review != null) { vm.closeReview() }
    BackHandler(enabled = state.explore != null) { vm.closeExplore() }
    var shownExplore by remember { mutableStateOf<ExploreUi?>(null) }
    if (state.explore != null) shownExplore = state.explore
    // Keep the last review around so the sheet can animate out after it's closed
    var shownReview by remember { mutableStateOf<ReviewUi?>(null) }
    if (state.review != null) shownReview = state.review

    // «Коротко» drops the tiles and the timeline: interview #17 described a profile for whom a
    // daily readout of their own numbers is the stressor itself, and softer wording does not
    // fix that — less of it does. The check-in stays in both modes, since without it there is
    // nothing to build a week out of, and notifications are untouched either way.
    val detailed = state.depth == Depth.Full
    val baseBlocks = when {
        evening && detailed -> listOf(HomeBlock.CheckIn, HomeBlock.Morning, HomeBlock.Stats, HomeBlock.Timeline)
        evening -> listOf(HomeBlock.CheckIn, HomeBlock.Morning)
        askCheckIn && detailed -> listOf(HomeBlock.Morning, HomeBlock.Stats, HomeBlock.Timeline, HomeBlock.CheckIn)
        askCheckIn -> listOf(HomeBlock.Morning, HomeBlock.CheckIn)
        detailed -> listOf(HomeBlock.Morning, HomeBlock.Stats, HomeBlock.Timeline)
        else -> listOf(HomeBlock.Morning)
    }
    // «Как идёт день» sits straight under the forecast it follows on from, and only exists in
    // the afternoon window the view model asks for it in.
    val midday = state.insights[insightKey(Insights.MIDDAY)]
    val blocks = if (midday == null || midday.failed) baseBlocks else {
        baseBlocks.toMutableList().apply { add(indexOf(HomeBlock.Morning) + 1, HomeBlock.Midday) }
    }
    val bars = WindowInsets.systemBars.asPaddingValues()
    // A tile with nothing in it is just a dash taking up space — most often sleep, when Health
    // Connect was never connected. Dropping it is the honest version: the offer to connect
    // lives in «Расскажи о себе», not as a prompt over the forecast.
    val entries = statEntries(state).filter { it.value != null }

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
                    orbBubble = state.orbReaction,
                    onOrbSingleTap = onOpenProfile,
                    onOrbFlurry = vm::onOrbFlurry,
                )
            }
            items(blocks, key = { it.name }) { block ->
                // Blocks below the hero card rise in one after another once the orb has landed.
                val enterDelay = when (block) {
                    HomeBlock.Midday -> 200
                    HomeBlock.Stats -> 220
                    HomeBlock.Timeline -> 260
                    else -> 300
                }
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
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                // Right above the forecast it follows into on a tap — same spot
                                // regardless of whether CheckIn sits before or after Morning.
                                state.dailyQuestion?.let { DailyQuestionBanner(modifier = rise, onClick = onOpenProfile) }
                                MorningCard(
                                    modifier = Modifier.sharedBounds(
                                        rememberSharedContentState(SharedKeys.HERO_CARD),
                                        animatedVisibilityScope = animatedScope,
                                        boundsTransform = CardBoundsTransform,
                                        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                                        clipInOverlayDuringTransition = OverlayClip(AppShapes.cardHero),
                                    ),
                                    state = state,
                                    onRate = vm::rateMorning,
                                    onMore = vm::openExplore,
                                    onOpenUsageAccess = {
                                        try {
                                            context.startActivity(context.usageAccessIntent(direct = true))
                                        } catch (_: ActivityNotFoundException) {
                                            try {
                                                context.startActivity(context.usageAccessIntent(direct = false))
                                            } catch (_: ActivityNotFoundException) {
                                                // some OEM builds hide this screen; nothing else to open
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        HomeBlock.Midday -> MiddayCard(modifier = rise, insight = midday ?: InsightUi())
                        HomeBlock.Stats -> StatsRow(
                            modifier = rise,
                            sharedScope = sharedScope,
                            entries = entries,
                            openStat = openStat,
                            onOpen = { kind ->
                                lastStat = kind
                                openStat = kind
                                vm.loadInsight(Insights.STAT, kind.name.lowercase(Locale.ROOT))
                            },
                        )
                        HomeBlock.Timeline -> DayTimelineCard(
                            modifier = rise,
                            hourlyScreen = state.hourlyScreen,
                            onReviewYesterday = { vm.openDayReview(LocalDate.now().minusDays(1)) },
                            onReviewToday = { vm.openDayReview(LocalDate.now()) },
                        )
                        HomeBlock.CheckIn -> CheckInSection(
                            modifier = rise,
                            selected = state.checkedIn,
                            tags = state.tags,
                            highlighted = evening,
                            insight = CheckInTags.firstOrNull { it in state.tags }
                                ?.let { state.insights[insightKey(Insights.TAG, it)] },
                            onSelect = vm::onCheckIn,
                            onToggleTag = vm::onToggleTag,
                        )
                    }
                }
            }
            item(key = "history") {
                HistorySection(
                    modifier = Modifier.animateItem(),
                    history = state.history,
                    insights = state.insights,
                    onWeekly = vm::openWeekReview,
                    onOpenPast = { date -> vm.loadInsight(Insights.RETRO, date) },
                )
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
                    insight = state.insights[insightKey(Insights.STAT, lastStat.name.lowercase(Locale.ROOT))],
                    onClose = { openStat = null },
                )
            }
        }

        // ── «Разбор дня» / «Итоги недели» sheet ──
        AnimatedVisibility(
            visible = state.review != null,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = .28f))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { vm.closeReview() }
            )
        }
        AnimatedVisibility(
            visible = state.review != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 10.dp, end = 10.dp, bottom = bars.calculateBottomPadding() + 10.dp),
            enter = fadeIn(tween(300)) +
                slideInVertically(tween(420, easing = EmphasizedDecelerate)) { it / 3 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(260)) { it / 4 },
        ) {
            shownReview?.let {
                ReviewSheet(modifier = Modifier, review = it, onClose = vm::closeReview,
                    onRate = vm::rateReview, onFollowup = vm::askFollowup)
            }
        }

        // ── «Хочу ещё» sheet ──
        AnimatedVisibility(
            visible = state.explore != null,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = .28f))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { vm.closeExplore() }
            )
        }
        AnimatedVisibility(
            visible = state.explore != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 10.dp, end = 10.dp, bottom = bars.calculateBottomPadding() + 10.dp),
            enter = fadeIn(tween(300)) +
                slideInVertically(tween(420, easing = EmphasizedDecelerate)) { it / 3 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(260)) { it / 4 },
        ) {
            shownExplore?.let {
                ExploreSheet(modifier = Modifier, explore = it, onAsk = vm::askExplore, onClose = vm::closeExplore)
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

// How long the orb sits unpoked before it invites a tap. Matches the pulse's own minimum, so
// the first pulse and the invitation can land close enough together to read as one nudge.
private const val ORB_INVITE_DELAY_MS = 9_000L
private const val ORB_PULSE_MIN_MS = 6_000L
private const val ORB_PULSE_MAX_MS = 9_000L

// A plain function call: inside Row the RowScope overload of AnimatedVisibility would be
// picked (same reason StatSlot in HomeStats.kt is its own function).
@Composable
private fun OrbBubbleSlot(text: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(150)),
        modifier = modifier,
    ) {
        text?.let { OrbBubble(it) }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun TopBar(
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    greeting: String,
    hasQuestions: Boolean,
    orbBubble: String?,
    onOrbSingleTap: () -> Unit,
    onOrbFlurry: () -> Unit,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    var hasBeenTapped by remember { mutableStateOf(false) }
    // Chosen once, when the invitation actually appears — not re-rolled on every unrelated
    // recomposition while it happens to be on screen.
    var invitePhrase by remember { mutableStateOf<String?>(null) }

    val onTap = rememberOrbGesture(
        onTap = {
            hasBeenTapped = true
            invitePhrase = null
            scope.launch {
                scale.animateTo(0.82f, tween(70))
                scale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 300f))
            }
        },
        onSingle = {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onOrbSingleTap()
        },
        onFlurry = {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onOrbFlurry()
        },
    )

    // A bigger, slower "look at me" pulse on top of the orb's own idle breathing — the nudge
    // to tap it at all. Keeps going for as long as Home is on screen; a tap never stops it,
    // since the point is to invite the *next* one just as much as this one.
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(ORB_PULSE_MIN_MS, ORB_PULSE_MAX_MS))
            scale.animateTo(1.14f, tween(260, easing = FastOutSlowInEasing))
            scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 180f))
        }
    }
    // The one-time invitation: only if nine seconds pass with nothing tapped yet.
    LaunchedEffect(Unit) {
        delay(ORB_INVITE_DELAY_MS)
        if (!hasBeenTapped) invitePhrase = OrbInvitePool.random()
    }

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
        Box(contentAlignment = Alignment.TopEnd) {
            // The onboarding orb lands here; a lone tap opens «Расскажи о себе», three taps
            // inside five seconds poke it instead (see rememberOrbGesture).
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
                        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                        // No clip: the question badge sits on the orb's edge and would be cut
                        // off. An unbounded round ripple keeps the touch feedback circular.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = 28.dp),
                            onClick = onTap,
                        ),
                )
            }
            // The reaction takes priority — a flurry answers the invitation, so there is
            // never a reason to show both at once.
            val bubbleText = orbBubble ?: invitePhrase
            OrbBubbleSlot(text = bubbleText, modifier = Modifier.offset(x = (-150).dp, y = (-6).dp))
        }
    }
}

// ─── Shimmer ──────────────────────────────────────────────────────────────────

/**
 * «идёт уточнение…» over the forecast card: the local text is on screen and the server's is
 * still being written, so it is about to be replaced.
 *
 * Held back for a moment on purpose. The server usually answers in about a second, and a hint
 * that appears and vanishes that fast reads as a glitch rather than as work in progress — so it
 * only shows when the wait is long enough to be worth explaining.
 */
@Composable
private fun RefiningHint(active: Boolean, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!active) {
            show = false
        } else {
            delay(HINT_DELAY_MS)
            show = true
        }
    }
    AnimatedVisibility(
        modifier = modifier,
        visible = show && active,
        enter = fadeIn(tween(260)),
        exit = fadeOut(tween(180)),
    ) {
        val breathe = rememberInfiniteTransition(label = "refining")
        val alpha by breathe.animateFloat(
            initialValue = .45f,
            targetValue = .85f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
            label = "refining-alpha",
        )
        Text(
            text = "идёт уточнение…",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val HINT_DELAY_MS = 700L

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
    onRate: (Boolean) -> Unit,
    onMore: () -> Unit,
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Прогноз на сегодня",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.75f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            // Only while there is already something to read — otherwise the shimmer above
            // is saying the same thing. If the row ever runs out of width it is the hint that
            // gives way, never the heading.
            RefiningHint(
                active = state.refining && state.forecast != null,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
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
                // Sideloaded APK on Android 13+: the switch is blocked until «restricted settings» are allowed
                var showHelp by rememberSaveable { mutableStateOf(false) }
                Text(
                    text = if (showHelp) "Скрыть подсказку" else "Android не даёт включить?",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = .85f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showHelp = !showHelp }
                        .padding(vertical = 2.dp),
                )
                AnimatedVisibility(
                    visible = showHelp,
                    enter = expandVertically(spring(dampingRatio = 1f, stiffness = 400f)) + fadeIn(),
                    exit = shrinkVertically(tween(220)) + fadeOut(tween(150)),
                ) {
                    val ctx = LocalContext.current
                    RestrictedSettingsSteps(
                        content = Color.White,
                        accent = Color.White,
                        onOpenAppInfo = {
                            try {
                                ctx.startActivity(ctx.appInfoIntent())
                            } catch (_: ActivityNotFoundException) {
                            }
                        },
                    )
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

        // «Хочу ещё» — the first real user asked for it: more about their own data, on demand
        if (state.forecastDate != null) {
            val view = LocalView.current
            Box(
                Modifier
                    .clip(AppShapes.button)
                    .background(Color.White.copy(alpha = .16f))
                    .border(1.dp, Color.White.copy(alpha = .5f), AppShapes.button)
                    .clickable {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onMore()
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text("Спросить о себе", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }

        // «Совпало?» moved to the day-review notification, which arrives right after the
        // evening check-in. This row only reached people who opened the app after 14:00, so
        // most forecasts went unrated. An already given verdict still shows, so the answer is
        // visible where the forecast is.
        if (state.morningFeedback != null) {
            FeedbackRow(
                question = "Совпало с днём?",
                feedback = state.morningFeedback,
                onRate = onRate,
                text = Color.White.copy(alpha = .9f),
                accent = Color.White,
            )
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
