package ru.keegoo.companion.ui.home

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.keegoo.companion.ui.motion.CardBoundsTransform
import ru.keegoo.companion.ui.motion.SharedKeys
import ru.keegoo.companion.ui.theme.AppShapes
import ru.keegoo.companion.ui.theme.HealthLevel
import ru.keegoo.companion.ui.theme.color
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

internal data class StatEntry(
    val kind: StatKind,
    val label: String,
    val value: Int?,
    val isTime: Boolean,
    val warnAbove: Int? = null,
    val warnBelow: Int? = null,
)

internal fun statEntries(s: HomeUiState) = listOf(
    StatEntry(StatKind.Screen, "Экран", s.screenMin, isTime = true, warnAbove = 300),
    StatEntry(StatKind.Sleep, s.sleepLabel, s.sleepMin, isTime = true, warnBelow = 420),
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
internal fun StatsRow(
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
    val levelColor = entry.level()?.color()
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
internal fun StatSheet(modifier: Modifier, entry: StatEntry, detail: StatDetail?, onClose: () -> Unit) {
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
                text = if (entry.kind == StatKind.Sleep) "прошлой ночью" else "сегодня",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        val week = detail?.week.orEmpty()
        if (week.size == 7 && week.count { it != null } >= 2) {
            WeekChart(week = week, isTime = entry.isTime)
        } else {
            Text(
                text = "История по дням появится через пару дней.",
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
private fun WeekChart(week: List<Int?>, isTime: Boolean) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(week) {
        grow.snapTo(0f)
        delay(150)
        grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    val known = week.filterNotNull()
    val max = known.max().coerceAtLeast(1) * 1.12f
    // Average of the previous days only: today is still in progress
    val avg = week.dropLast(1).filterNotNull().ifEmpty { known }.average().toFloat()
    val barColor = MaterialTheme.colorScheme.primaryContainer
    val todayColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.outline.copy(alpha = .5f)
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val days = (6 downTo 0).map { back ->
        if (back == 0) "Сег." else LocalDate.now().minusDays(back.toLong()).dayOfWeek
            .getDisplayName(TextStyle.SHORT, RuLocale)
            .replaceFirstChar { it.titlecase(RuLocale) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "в среднем ${formatValue(avg.toInt(), isTime)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
        )
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            val gap = 8.dp.toPx()
            val barW = (size.width - gap * 6) / 7f
            week.forEachIndexed { i, v ->
                if (v == null) {
                    // no data that day — a small stub so the gap reads as "missing", not "zero"
                    drawRoundRect(
                        color = emptyColor,
                        topLeft = Offset(i * (barW + gap), size.height - 4.dp.toPx()),
                        size = Size(barW, 4.dp.toPx()),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                    return@forEachIndexed
                }
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
