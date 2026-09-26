package ru.keegoo.companion.ui.home

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.keegoo.companion.data.api.model.HistoryItem
import ru.keegoo.companion.ui.theme.AppShapes
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Ru = Locale("ru")
private val DayLabel = DateTimeFormatter.ofPattern("EE, d MMMM", Ru)

// ─── Day timeline ─────────────────────────────────────────────────────────────

/**
 * «Сегодня по часам»: screen minutes for each hour of today, the current hour highlighted,
 * plus the entry points to the LLM day review (yesterday / today so far).
 */
@Composable
internal fun DayTimelineCard(
    modifier: Modifier,
    hourlyScreen: List<Int>?,
    onReviewYesterday: () -> Unit,
    onReviewToday: () -> Unit,
) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(hourlyScreen) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val nowHour = LocalTime.now().hour
    val bar = MaterialTheme.colorScheme.primaryContainer
    val barNow = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outline.copy(alpha = .35f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShapes.cardHero)
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Сегодня по часам",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "экран, минут в час",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hourlyScreen == null) {
            Text(
                text = "Появится, когда будет доступ к истории использования.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                val gap = 2.dp.toPx()
                val w = (size.width - gap * 23) / 24f
                hourlyScreen.forEachIndexed { h, minutes ->
                    val x = h * (w + gap)
                    if (h > nowHour) {
                        // hours still ahead: a thin baseline
                        drawRoundRect(empty, Offset(x, size.height - 2.dp.toPx()), Size(w, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
                        return@forEachIndexed
                    }
                    val p = ((grow.value - h / 48f) * 1.6f).coerceIn(0f, 1f)
                    val hgt = (size.height * (minutes.coerceIn(0, 60) / 60f) * p).coerceAtLeast(2.dp.toPx())
                    drawRoundRect(
                        color = if (h == nowHour) barNow else bar,
                        topLeft = Offset(x, size.height - hgt),
                        size = Size(w, hgt),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("0", "6", "12", "18", "24").forEachIndexed { i, label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = when (i) { 0 -> TextAlign.Start; 4 -> TextAlign.End; else -> TextAlign.Center },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Разбор вчера", Modifier.weight(1f), filled = true, onClick = onReviewYesterday)
            PillButton("Разбор сегодня", Modifier.weight(1f), filled = false, onClick = onReviewToday)
        }
    }
}

@Composable
internal fun PillButton(text: String, modifier: Modifier = Modifier, filled: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .clip(AppShapes.button)
            .background(if (filled) primary else Color.Transparent)
            .border(1.dp, primary, AppShapes.button)
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (filled) MaterialTheme.colorScheme.onPrimary else primary,
        )
    }
}

// ─── History ──────────────────────────────────────────────────────────────────

/** Past forecasts as small panels (tap to read in full) + «Итоги недели». */
@Composable
internal fun HistorySection(modifier: Modifier, history: List<HistoryItem>, onWeekly: () -> Unit) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Прошлые прогнозы",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            PillButton("Итоги недели", filled = false, onClick = onWeekly, modifier = Modifier.padding(start = 12.dp))
        }
        // Today's forecast is already in the card above
        val past = history.filter { it.date != LocalDate.now().toString() }
        if (past.isEmpty()) {
            Text(
                text = "Здесь будут прогнозы прошлых дней — можно будет вернуться и сравнить.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        past.forEach { HistoryPanel(it) }
    }
}

@Composable
private fun HistoryPanel(item: HistoryItem) {
    var open by rememberSaveable(item.date) { mutableStateOf(false) }
    val date = runCatching { LocalDate.parse(item.date).format(DayLabel).replaceFirstChar { it.titlecase(Ru) } }
        .getOrDefault(item.date)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .85f))
            .clickable { open = !open }
            .animateContentSize(spring(dampingRatio = 1f, stiffness = 400f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = date,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            // The person's own verdict on that forecast
            when (item.feedback) {
                "hit" -> Text("совпало ✓", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                "miss" -> Text("не совсем", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            text = item.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (open) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ─── Review sheet ─────────────────────────────────────────────────────────────

/** The sheet for «Разбор дня» and «Итоги недели»: shimmer while the model writes, then text + evidence. */
@Composable
internal fun ReviewSheet(modifier: Modifier, review: ReviewUi, onClose: () -> Unit, onRate: (Boolean) -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .clip(AppShapes.sheet)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(remember { MutableInteractionSource() }, indication = null) {} // swallow taps
            .padding(20.dp)
            .animateContentSize(spring(dampingRatio = 1f, stiffness = 300f)),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = review.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
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
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                review.loading -> WritingPlaceholder()
                review.error != null -> Text(
                    text = review.error,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    text = review.text.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (review.signals.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "На чём основано",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                review.signals.forEach { s ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (s.positive) "＋" else "•",
                            color = if (s.positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                        )
                        Column {
                            Text(s.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Text(s.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (review.text != null && review.date != null) {
                key(review.kind, review.date) {
                    FeedbackRow(
                        question = "Похоже на правду?",
                        feedback = review.feedback,
                        onRate = onRate,
                        text = MaterialTheme.colorScheme.onSurfaceVariant,
                        accent = MaterialTheme.colorScheme.primary,
                        foldAfterMs = null,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

// ─── «Совпало / Не совсем» ────────────────────────────────────────────────────

/**
 * The person's verdict on a forecast or review — the accuracy metric and the signal for
 * prompt changes. After a tap the chips turn into a thank-you; with [foldAfterMs] the row
 * then folds away. A verdict given earlier (e.g. before the app was reopened) is not asked again.
 */
@Composable
internal fun FeedbackRow(
    question: String,
    feedback: String?,
    onRate: (Boolean) -> Unit,
    text: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    foldAfterMs: Long? = 4_000,
) {
    val ratedBefore = remember { feedback != null }
    var visible by remember { mutableStateOf(!ratedBefore || foldAfterMs == null) }
    LaunchedEffect(feedback) {
        if (feedback != null && !ratedBefore && foldAfterMs != null) {
            delay(foldAfterMs)
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandVertically(spring(dampingRatio = 1f, stiffness = 400f)) + fadeIn(),
        exit = shrinkVertically(tween(420)) + fadeOut(tween(300)),
    ) {
        AnimatedContent(
            targetState = feedback,
            transitionSpec = { fadeIn(tween(260, delayMillis = 80)) togetherWith fadeOut(tween(160)) },
            label = "feedback",
        ) { verdict ->
            if (verdict == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(question, style = MaterialTheme.typography.labelLarge, color = text, modifier = Modifier.weight(1f))
                    FeedbackChip("Совпало", accent, filled = true) { onRate(true) }
                    FeedbackChip("Не совсем", accent, filled = false) { onRate(false) }
                }
            } else {
                Text(
                    text = if (verdict == "hit") "Спасибо — учту ✓" else "Спасибо — разберусь, что было не так",
                    style = MaterialTheme.typography.labelLarge,
                    color = text,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun FeedbackChip(label: String, accent: Color, filled: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    Box(
        Modifier
            .clip(AppShapes.button)
            .background(if (filled) accent.copy(alpha = .18f) else Color.Transparent)
            .border(1.dp, accent.copy(alpha = .7f), AppShapes.button)
            .clickable {
                view.confirmHaptic()
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = accent)
    }
}

/** Three shimmering lines — the companion is writing. */
@Composable
internal fun WritingPlaceholder() {
    val t = rememberInfiniteTransition(label = "writing")
    val x by t.animateFloat(-400f, 900f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "wx")
    val base = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(
            text = "Смотрю на данные…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(1f, .92f, .7f).forEach { w ->
            Box(
                Modifier
                    .fillMaxWidth(w)
                    .height(14.dp)
                    .clip(AppShapes.tag)
                    .drawBehind {
                        drawRect(
                            Brush.linearGradient(
                                listOf(base, base.copy(alpha = .35f), base),
                                start = Offset(x, 0f),
                                end = Offset(x + 400f, 0f),
                            )
                        )
                    }
            )
        }
    }
}
