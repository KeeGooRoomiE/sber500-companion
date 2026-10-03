package ru.keegoo.companion.ui.profile

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ripple
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.keegoo.companion.data.local.AppOption
import ru.keegoo.companion.data.prefs.profileAnswers
import ru.keegoo.companion.data.prefs.saveProfileAnswer
import ru.keegoo.companion.domain.profile.GenQuestionPrefix
import ru.keegoo.companion.domain.profile.GenQuestionTextPrefix
import ru.keegoo.companion.domain.profile.genQuestionId
import ru.keegoo.companion.domain.profile.ProfileIds
import ru.keegoo.companion.domain.profile.ProfileQuestion
import ru.keegoo.companion.domain.profile.ProfileQuestions
import ru.keegoo.companion.domain.profile.parseTime
import ru.keegoo.companion.notifications.NotificationScheduler
import ru.keegoo.companion.notifications.ReminderKind
import ru.keegoo.companion.ui.home.ThinkingLines
import ru.keegoo.companion.ui.motion.CompanionOrb
import ru.keegoo.companion.ui.motion.OrbBoundsTransform
import ru.keegoo.companion.ui.motion.OrbMode
import ru.keegoo.companion.ui.motion.SharedKeys
import ru.keegoo.companion.ui.home.OrbBubble
import ru.keegoo.companion.ui.motion.OrbInvitePool
import ru.keegoo.companion.ui.motion.rememberOrbGesture
import ru.keegoo.companion.ui.theme.AppShapes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import kotlin.random.Random
import java.time.LocalTime

/** How long an answered one-time question stays on screen (with «Учту ✓») before folding away. */
private const val FOLD_AWAY_MS = 5_000L

/**
 * «Расскажи о себе» — opened by tapping the orb. An accordion of short questions; answering one
 * opens the next. One-time questions fold away 5 s after the answer and don't come back
 * (except through «Изменить прошлые ответы»).
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalAnimationApi::class)
@Composable
fun ProfileScreen(
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    onBack: () -> Unit,
    vm: ProfileViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val answersFlow = remember { context.profileAnswers() }
    val loaded by answersFlow.collectAsState(initial = null)
    val answers = loaded.orEmpty()
    val topApps by vm.topApps.collectAsStateWithLifecycle()
    val generated by vm.generated.collectAsStateWithLifecycle()
    val profileSummary by vm.profileSummary.collectAsStateWithLifecycle()
    val orbReaction by vm.orbReaction.collectAsStateWithLifecycle()

    // The poke mechanic lives only on this screen's big orb — squish on every tap, a bigger
    // "look at me" pulse every 6-9s, and a one-time invitation if nine seconds pass untouched.
    val orbScale = remember { Animatable(1f) }
    var orbTapped by remember { mutableStateOf(false) }
    var invitePhrase by remember { mutableStateOf<String?>(null) }
    val onOrbTap = rememberOrbGesture(
        onTap = {
            orbTapped = true
            invitePhrase = null
            scope.launch {
                orbScale.animateTo(0.82f, tween(70))
                orbScale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 300f))
            }
        },
        onSingle = {}, // already on the screen a single tap would have opened — nothing to do
        onFlurry = { vm.onOrbFlurry() },
    )
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(6_000, 9_000))
            orbScale.animateTo(1.14f, tween(260, easing = FastOutSlowInEasing))
            orbScale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 180f))
        }
    }
    LaunchedEffect(Unit) {
        delay(9_000)
        if (!orbTapped) invitePhrase = OrbInvitePool.random()
    }
    // The invitation fades on its own — unlike the reaction, nothing else clears it.
    LaunchedEffect(invitePhrase) {
        if (invitePhrase != null) {
            delay(3_500)
            invitePhrase = null
        }
    }

    // Work-apps question makes sense only when we can see the person's apps. The generated one
    // sits right before «Когда присылать прогноз» — late enough that it reads as "one more
    // thing", not the first thing this screen asks, but not buried after notification times
    // either, since that is also where the Home banner lands someone who tapped it.
    val questions = remember(topApps, generated) {
        val base = ProfileQuestions.filter { it.id != ProfileIds.WORK_APPS || topApps.isNotEmpty() }
        val genQuestion = generated?.let { g ->
            ProfileQuestion(
                id = genQuestionId(g.text),
                title = g.text,
                hint = "Вопрос на сегодня",
                options = g.options,
                freeText = g.options.isEmpty(), // model didn't give a real choice — fall back
                oneTime = true,
            )
        }
        if (genQuestion == null) base else {
            val at = base.indexOfFirst { it.id == ProfileIds.MORNING_TIME }.let { if (it < 0) base.size else it }
            base.toMutableList().apply { add(at, genQuestion) }
        }
    }

    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var showPast by rememberSaveable { mutableStateOf(false) }
    // One-time questions already answered when the screen opened — hidden from the start
    var doneAtOpen by remember { mutableStateOf<Set<String>?>(null) }
    // Answered during this visit: stay for FOLD_AWAY_MS, then fold away
    val foldedNow = remember { mutableStateListOf<String>() }

    LaunchedEffect(loaded) {
        if (doneAtOpen == null && loaded != null) {
            doneAtOpen = questions.filter { it.oneTime && it.id in answers }.map { it.id }.toSet()
            expanded = questions.firstOrNull { it.id !in answers }?.id
        }
    }
    // The generated question arrives after the list has already been laid out. Answered earlier
    // today it is hidden like any other one-time question — without this it would come back with
    // «Учту ✓» on every visit, as if it had just been answered.
    LaunchedEffect(generated, loaded) {
        val id = generated?.let { genQuestionId(it.text) } ?: return@LaunchedEffect
        if (loaded == null) return@LaunchedEffect
        if (id in answers) doneAtOpen = doneAtOpen.orEmpty() + id
        else if (expanded == null) expanded = id
    }

    fun visible(q: ProfileQuestion) = showPast || (q.id !in doneAtOpen.orEmpty() && q.id !in foldedNow)

    fun answer(q: ProfileQuestion, value: String) {
        scope.launch {
            context.saveProfileAnswer(q.id, value)
            // The key is only a hash — keep the question text beside it, or an answered card
            // would have nothing to show its own question as.
            if (q.id.startsWith(GenQuestionPrefix)) {
                context.saveProfileAnswer(GenQuestionTextPrefix + q.id.removePrefix(GenQuestionPrefix), q.title)
                vm.onGeneratedAnswered()
            }
            when (q.id) {
                ProfileIds.MORNING_TIME -> NotificationScheduler.applyMorning(context, value)
                ProfileIds.EVENING_TIME -> parseTime(value)?.let { NotificationScheduler.reschedule(context, ReminderKind.Evening, it) }
            }
            if (q.oneTime && !showPast) {
                delay(FOLD_AWAY_MS)
                foldedNow += q.id
            }
        }
        val idx = questions.indexOf(q)
        expanded = questions.drop(idx + 1).firstOrNull { it.id !in answers && visible(it) }?.id
    }

    val answered = questions.count { it.id in answers }
    val progress by animateFloatAsState(
        if (questions.isEmpty()) 0f else answered / questions.size.toFloat(),
        spring(dampingRatio = .8f, stiffness = 200f),
        label = "progress",
    )
    val hiddenCount = questions.count { !visible(it) }
    val bars = WindowInsets.systemBars.asPaddingValues()

    // «Твой профиль» sits right after the work-apps question — early enough to read as part of
    // getting to know this person, not buried at the bottom. Falls back to right after the name
    // when work apps aren't offered (no usage access yet), so it is never simply missing.
    val summaryAfterId = if (questions.any { it.id == ProfileIds.WORK_APPS }) ProfileIds.WORK_APPS else ProfileIds.NAME

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = bars.calculateTopPadding() + 8.dp,
            bottom = bars.calculateBottomPadding() + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(AppShapes.circle)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("←", fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground)
                    }
                }
                Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                    with(sharedScope) {
                        CompanionOrb(
                            mode = OrbMode.Calm,
                            modifier = Modifier
                                .sharedElement(
                                    rememberSharedContentState(SharedKeys.ORB),
                                    animatedVisibilityScope = animatedScope,
                                    boundsTransform = OrbBoundsTransform,
                                )
                                .size(104.dp)
                                .graphicsLayer { scaleX = orbScale.value; scaleY = orbScale.value }
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = false, radius = 60.dp),
                                    onClick = onOrbTap,
                                ),
                        )
                    }
                    // Reaction over invitation — a flurry is itself an answer to «тапни на меня».
                    // TopCenter of this 150dp box already sits in the gap above the orb's own
                    // visible edge (it is 104dp, centred) — no extra offset needed to clear the
                    // back arrow above; a small lift keeps it off the orb's rounded top.
                    OrbBubbleSlot(
                        text = orbReaction ?: invitePhrase,
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = (-4).dp),
                    )
                }
                Text(
                    text = "Расскажи о себе",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Пара коротких вопросов — так я точнее разберу твои дни. Имя остаётся на телефоне.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                // Gone once everything is answered: a full bar still asks for something, and
                // there is nothing left to do. The spacing goes with it — otherwise it leaves
                // a gap where the bar used to be, which is the empty middle that was noticed.
                if (answered < questions.size) {
                    Spacer(Modifier.height(16.dp))
                    ProgressLine(progress = progress, label = "$answered из ${questions.size}")
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        // Offered again here rather than on Home: onboarding lets any optional step be skipped,
        // so there has to be a way back — and it belongs among the questions about yourself,
        // not as a banner over the forecast. Absent entirely once nothing is missing.
        item(key = "permissions") { MissingPermissions() }
        items(questions, key = { it.id }) { q ->
            val i = questions.indexOf(q)
            val rise = with(animatedScope) {
                Modifier.animateEnterExit(
                    enter = fadeIn(tween(300, delayMillis = 150 + i * 40)) +
                        slideInVertically(tween(400, delayMillis = 150 + i * 40)) { it / 3 },
                    exit = fadeOut(tween(100)),
                )
            }
            // A plain function call: inside LazyItemScope a scoped AnimatedVisibility overload would not apply
            FoldAway(visible = visible(q)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuestionCard(
                        modifier = rise,
                        question = q,
                        answer = answers[q.id],
                        appOptions = topApps,
                        justAnswered = q.oneTime && q.id in answers && q.id !in doneAtOpen.orEmpty() && !showPast,
                        expanded = expanded == q.id,
                        onToggle = { expanded = if (expanded == q.id) null else q.id },
                        onAnswer = { answer(q, it) },
                    )
                    if (q.id == summaryAfterId) {
                        ProfileSummaryCard(
                            modifier = rise,
                            summary = profileSummary,
                            onClarify = vm::openClarify,
                            onAnswer = vm::answerClarify,
                        )
                    }
                }
            }
        }
        if (hiddenCount > 0 || showPast) {
            item(key = "past") {
                Text(
                    text = if (showPast) "Скрыть прошлые ответы" else "Изменить прошлые ответы ($hiddenCount)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(AppShapes.chip)
                        .clickable { showPast = !showPast }
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}

// A plain function call: inside Box/Column a scoped AnimatedVisibility overload could get
// picked instead of this one (same reason StatSlot in HomeStats.kt is its own function).
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

@Composable
private fun FoldAway(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(spring(dampingRatio = 1f, stiffness = 300f)) + fadeIn(),
        exit = fadeOut(tween(600)) + shrinkVertically(tween(700, delayMillis = 200)),
    ) { content() }
}

// ─── «Твой профиль» ───────────────────────────────────────────────────────────

/**
 * «Твой профиль»: how the model reads this person, read fresh once a day. Same accordion shape
 * as the questions around it — collapsed by default, an arrow opens it — but there is nothing
 * to answer here on open, only «Уточнить» underneath once the text has loaded.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileSummaryCard(
    modifier: Modifier,
    summary: ProfileSummaryUi,
    onClarify: () -> Unit,
    onAnswer: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(280), label = "summaryChevron")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Твой профиль", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = "То, как я тебя вижу на основе данных",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "▾",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { rotationZ = chevron },
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(dampingRatio = 1f, stiffness = 400f)) + fadeIn(),
            exit = shrinkVertically(tween(220)) + fadeOut(tween(150)),
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (summary.text == null) ThinkingLines(2) else {
                    Text(summary.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                when {
                    summary.clarify?.answered == true ->
                        Text("Учту ✓", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    summary.clarify == null ->
                        if (summary.clarifyAvailable && summary.text != null) {
                            Text(
                                text = "Уточнить",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable(onClick = onClarify),
                            )
                        }
                    summary.clarify.failed -> {} // nothing to ask right now — stays quiet
                    summary.clarify.question == null -> ThinkingLines(1)
                    else -> {
                        Text(
                            text = summary.clarify.question,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            summary.clarify.options.forEach { opt ->
                                AnswerChip(text = opt, on = false, onClick = { onAnswer(opt) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressLine(progress: Float, label: String) {
    val track = MaterialTheme.colorScheme.outline.copy(alpha = .5f)
    val fill = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .clip(AppShapes.circle)
                .background(track)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(AppShapes.circle)
                    .background(fill)
            )
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Human-readable answer: work apps are stored as package names. */
private fun displayAnswer(question: ProfileQuestion, answer: String?, apps: List<AppOption>): String? {
    if (answer == null || question.id != ProfileIds.WORK_APPS) return answer
    val labels = apps.associate { it.packageName to it.label }
    return answer.split(",").filter { it.isNotBlank() }
        .joinToString(", ") { labels[it] ?: it.substringAfterLast('.') }
        .ifBlank { "Нет рабочих" }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(
    modifier: Modifier,
    question: ProfileQuestion,
    answer: String?,
    appOptions: List<AppOption>,
    justAnswered: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAnswer: (String) -> Unit,
) {
    val view = LocalView.current
    var pickTime by rememberSaveable(question.id) { mutableStateOf(false) }
    if (pickTime) {
        TimePickDialog(
            title = question.title,
            initial = parseTime(answer) ?: question.defaultTime ?: LocalTime.of(8, 0),
            onDismiss = { pickTime = false },
            onConfirm = { t ->
                pickTime = false
                onAnswer("%02d:%02d".format(t.hour, t.minute))
            },
        )
    }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(280), label = "qChevron")
    val border by animateColorAsState(
        if (expanded) MaterialTheme.colorScheme.primary else Color.Transparent, tween(250), label = "qBorder",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.5.dp, border, AppShapes.card),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(question.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                val shown = displayAnswer(question, answer, appOptions)
                Text(
                    text = when {
                        justAnswered -> "Учту ✓  $shown"
                        shown != null -> shown
                        else -> "Не отвечено"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    // One accent, and only for the moment the answer lands. Painting every
                    // filled answer purple added a third text colour that said «отвечено» —
                    // which the answer being there already says.
                    color = if (justAnswered) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Text(
                "▾",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { rotationZ = chevron },
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(dampingRatio = 1f, stiffness = 400f)) + fadeIn(),
            exit = shrinkVertically(tween(220)) + fadeOut(tween(150)),
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                question.hint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    question.freeText -> FreeTextAnswer(question, answer, onAnswer)
                    question.multi -> MultiAnswer(question, answer, appOptions, onAnswer)
                    else -> FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        question.options.forEach { option ->
                            AnswerChip(
                                text = option,
                                on = option == answer,
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    onAnswer(option)
                                },
                            )
                        }
                        if (question.timePick) {
                            // An exact time the person picked themselves ("07:15"), shown as its own chip
                            val custom = answer?.takeIf { it !in question.options && parseTime(it) != null }
                            AnswerChip(
                                text = custom?.let { "$it ✎" } ?: "Своё время…",
                                on = custom != null,
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    pickTime = true
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Hour and minute on the Material dial (24 h). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickDialog(title: String, initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("Готово") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun FreeTextAnswer(question: ProfileQuestion, answer: String?, onAnswer: (String) -> Unit) {
    val view = LocalView.current
    var text by rememberSaveable(question.id) { mutableStateOf(answer.orEmpty()) }
    val submit = {
        if (text.isNotBlank()) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onAnswer(text.trim())
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(30) },
        singleLine = true,
        placeholder = { Text("Например, Саша") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth(),
    )
    DoneButton(enabled = text.isNotBlank(), onClick = { submit() })
}

/** Several options + «Готово». Work apps are the person's own apps; stored as package names. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiAnswer(question: ProfileQuestion, answer: String?, apps: List<AppOption>, onAnswer: (String) -> Unit) {
    val view = LocalView.current
    val isApps = question.id == ProfileIds.WORK_APPS
    // (value stored, label shown)
    val options = if (isApps) apps.map { it.packageName to it.label } else question.options.map { it to it }
    val separator = if (isApps) "," else ", "
    val picked = remember(question.id) {
        mutableStateListOf<String>().apply { answer?.split(separator)?.filter { it.isNotBlank() }?.let { addAll(it) } }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            AnswerChip(
                text = label,
                on = value in picked,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    if (value in picked) picked -= value else picked += value
                },
            )
        }
    }
    DoneButton(
        enabled = picked.isNotEmpty() || isApps,
        label = if (isApps && picked.isEmpty()) "Рабочих нет" else "Готово",
        onClick = { onAnswer(picked.joinToString(separator).ifEmpty { if (isApps) "," else "" }) },
    )
}

@Composable
private fun DoneButton(enabled: Boolean, label: String = "Готово", onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = AppShapes.button,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) { Text(label) }
}

@Composable
private fun AnswerChip(text: String, on: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .clip(AppShapes.chip)
            .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .border(1.dp, if (on) primary else MaterialTheme.colorScheme.outline, AppShapes.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = if (on) primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
