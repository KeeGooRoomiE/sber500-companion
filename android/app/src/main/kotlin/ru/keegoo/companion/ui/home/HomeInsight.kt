package ru.keegoo.companion.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.keegoo.companion.ui.theme.AppShapes

/**
 * One line the model wrote about one slice of the data.
 *
 * Fades in when it arrives and is absent otherwise — including on failure. These lines live
 * inside screens that already say something without them (a tile has its chart, a past forecast
 * has its text), so an error message here would take up more room than the line it replaces.
 */
@Composable
internal fun InsightLine(insight: InsightUi?, modifier: Modifier = Modifier, label: String? = null) {
    AnimatedVisibility(
        visible = insight != null && !insight.failed,
        enter = fadeIn(tween(300)) + expandVertically(spring(dampingRatio = 1f, stiffness = 300f)),
    ) {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (label != null) {
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            val text = insight?.text
            if (text != null) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                ThinkingLines(lines = 2)
            }
        }
    }
}

/** Shimmering stand-ins while the model writes — one or two lines, not the full placeholder. */
@Composable
internal fun ThinkingLines(lines: Int) {
    val t = rememberInfiniteTransition(label = "insight")
    val x by t.animateFloat(
        -400f, 900f,
        infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "ix",
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        listOf(1f, .6f).take(lines).forEach { w ->
            Box(
                Modifier
                    .fillMaxWidth(w)
                    .height(12.dp)
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

// MiddayCard (a standalone «Как идёт день» card) was removed: once the question it used to
// hold got pulled out as a bug fix, a bare paragraph sitting alone between the hero card and
// the stat tiles read as clutter. «Как идёт день» now lives inside MorningCard itself, in
// HomeScreen.kt, as a second section of the same card.

/**
 * «Есть вопрос по сегодня» — a thin strip above the forecast, not a card of its own: it is a
 * doorway into the orb, not a second thing to read on Home. Visible and labelled on purpose —
 * idea 1 from the custdev review was to make this call honest rather than a hidden trigger, so
 * it says plainly that there is something to tap, same as the orb's own unanswered-questions
 * dot already does for the fixed list.
 */
@Composable
internal fun DailyQuestionBanner(modifier: Modifier = Modifier, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShapes.chip)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = "Можешь ответить на дополнительный вопрос про сегодня",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        Text("→", color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/**
 * A small speech bubble over the orb: the one-way "хочешь поговорить?" reaction, or the
 * nine-second idle invitation. No tail — a plain rounded card reads as a bubble clearly enough
 * at this size, and a drawn pointer would need exact anchoring this call site doesn't have.
 */
@Composable
internal fun OrbBubble(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .widthIn(max = 170.dp)
            .shadow(3.dp, AppShapes.chip)
            .clip(AppShapes.chip)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
