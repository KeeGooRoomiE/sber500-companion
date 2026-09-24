package ru.keegoo.companion.ui.profile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import kotlinx.coroutines.launch
import ru.keegoo.companion.data.prefs.profileAnswers
import ru.keegoo.companion.data.prefs.saveProfileAnswer
import ru.keegoo.companion.domain.profile.ProfileIds
import ru.keegoo.companion.domain.profile.ProfileQuestion
import ru.keegoo.companion.domain.profile.ProfileQuestions
import ru.keegoo.companion.domain.profile.parseTime
import ru.keegoo.companion.notifications.NotificationScheduler
import ru.keegoo.companion.notifications.ReminderKind
import ru.keegoo.companion.ui.motion.CompanionOrb
import ru.keegoo.companion.ui.motion.OrbBoundsTransform
import ru.keegoo.companion.ui.motion.OrbMode
import ru.keegoo.companion.ui.motion.SharedKeys
import ru.keegoo.companion.ui.theme.AppShapes

private fun Context.isOnWifi(): Boolean {
    val cm = getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}

/**
 * «Расскажи о себе» — opened by tapping the orb. An accordion of short questions:
 * answering one folds it and opens the next unanswered. Everything stays on the phone.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalAnimationApi::class)
@Composable
fun ProfileScreen(
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val answersFlow = remember { context.profileAnswers() }
    val loaded by answersFlow.collectAsState(initial = null)
    val answers = loaded.orEmpty()
    val onWifi = remember { context.isOnWifi() }
    val questions = remember(onWifi) { ProfileQuestions.filter { !it.onlyOnWifi || onWifi } }

    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var initialized by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(loaded) {
        // Open the first unanswered question once answers have loaded
        if (!initialized && loaded != null) {
            expanded = questions.firstOrNull { it.id !in answers }?.id
            initialized = true
        }
    }

    fun answer(q: ProfileQuestion, value: String) {
        scope.launch {
            context.saveProfileAnswer(q.id, value)
            when (q.id) {
                ProfileIds.MORNING_TIME -> parseTime(value)?.let { NotificationScheduler.reschedule(context, ReminderKind.Morning, it) }
                ProfileIds.EVENING_TIME -> parseTime(value)?.let { NotificationScheduler.reschedule(context, ReminderKind.Evening, it) }
            }
        }
        val idx = questions.indexOf(q)
        expanded = questions.drop(idx + 1).firstOrNull { it.id !in answers }?.id
    }

    val answered = questions.count { it.id in answers }
    val progress by animateFloatAsState(
        if (questions.isEmpty()) 0f else answered / questions.size.toFloat(),
        spring(dampingRatio = .8f, stiffness = 200f),
        label = "progress",
    )
    val bars = WindowInsets.systemBars.asPaddingValues()

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
                                .size(104.dp),
                        )
                    }
                }
                Text(
                    text = "Расскажи о себе",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Пара коротких вопросов — так я точнее разберу твои дни. Ответы хранятся только на телефоне.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                ProgressLine(progress = progress, label = "$answered из ${questions.size}")
                Spacer(Modifier.height(6.dp))
            }
        }
        itemsIndexed(questions, key = { _, q -> q.id }) { i, q ->
            val rise = with(animatedScope) {
                Modifier.animateEnterExit(
                    enter = fadeIn(tween(300, delayMillis = 150 + i * 40)) +
                        slideInVertically(tween(400, delayMillis = 150 + i * 40)) { it / 3 },
                    exit = fadeOut(tween(100)),
                )
            }
            QuestionCard(
                modifier = rise,
                question = q,
                answer = answers[q.id],
                expanded = expanded == q.id,
                onToggle = { expanded = if (expanded == q.id) null else q.id },
                onAnswer = { answer(q, it) },
            )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(
    modifier: Modifier,
    question: ProfileQuestion,
    answer: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAnswer: (String) -> Unit,
) {
    val view = LocalView.current
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
                Text(
                    text = answer ?: "Не отвечено",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (answer != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                if (question.freeText) {
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
                    Button(
                        onClick = { submit() },
                        enabled = text.isNotBlank(),
                        shape = AppShapes.button,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) { Text("Готово") }
                } else {
                    FlowRow(
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
                    }
                }
            }
        }
    }
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
