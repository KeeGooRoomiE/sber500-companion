package ru.keegoo.companion.ui.home

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.keegoo.companion.domain.model.DayFeel
import ru.keegoo.companion.ui.theme.AppShapes
import ru.keegoo.companion.ui.theme.FeelingHard
import ru.keegoo.companion.ui.theme.FeelingMeh
import ru.keegoo.companion.ui.theme.FeelingOk
import ru.keegoo.companion.ui.theme.HealthLevel
import ru.keegoo.companion.ui.theme.color

private data class FeelOption(val feel: DayFeel, val emoji: String, val label: String, val color: Color)

private val FeelOptions = listOf(
    FeelOption(DayFeel.OK, "😊", "Отлично", FeelingOk),
    FeelOption(DayFeel.MEH, "😐", "Нормально", FeelingMeh),
    FeelOption(DayFeel.HARD, "😮‍💨", "Тяжело", FeelingHard),
)

/** Pause after both answers are in, before the card folds into one line. */
private const val COLLAPSE_DELAY_MS = 4_000L

@Composable
internal fun CheckInSection(
    modifier: Modifier,
    selected: DayFeel?,
    tags: Set<String>,
    highlighted: Boolean,
    onSelect: (DayFeel) -> Unit,
    onToggleTag: (String) -> Unit,
) {
    val ringColor = MaterialTheme.colorScheme.primary
    val ring by animateFloatAsState(if (highlighted) 1f else 0f, tween(400), label = "ring")

    var collapsed by rememberSaveable { mutableStateOf(false) }
    // Touched in this visit: then wait before folding. Answered earlier: fold right away.
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(selected, tags) {
        if (selected != null && tags.isNotEmpty() && !collapsed) {
            if (touched) delay(COLLAPSE_DELAY_MS)
            collapsed = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShapes.cardHero)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.5.dp, ringColor.copy(alpha = ring), AppShapes.cardHero),
    ) {
        AnimatedContent(
            targetState = collapsed && selected != null,
            transitionSpec = {
                (fadeIn(tween(260, delayMillis = 120)) togetherWith fadeOut(tween(160)))
                    .using(SizeTransform(clip = true) { _, _ -> spring(dampingRatio = 1f, stiffness = 260f) })
            },
            label = "checkinFold",
        ) { folded ->
            if (folded && selected != null) {
                CheckInSummary(selected, tags, onEdit = { collapsed = false })
            } else {
                CheckInForm(
                    selected = selected,
                    tags = tags,
                    onSelect = { touched = true; onSelect(it) },
                    onToggleTag = { touched = true; onToggleTag(it) },
                )
            }
        }
    }
}

@Composable
private fun CheckInSummary(selected: DayFeel, tags: Set<String>, onEdit: () -> Unit) {
    val option = FeelOptions.first { it.feel == selected }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(option.emoji, fontSize = 22.sp)
        Column(Modifier.weight(1f)) {
            Text(
                text = "Сегодня — ${option.label.lowercase()}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (tags.isNotEmpty()) {
                Text(
                    text = CheckInTags.filter { it in tags }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Text(
            text = "Изменить",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalAnimationApi::class)
@Composable
private fun CheckInForm(
    selected: DayFeel?,
    tags: Set<String>,
    onSelect: (DayFeel) -> Unit,
    onToggleTag: (String) -> Unit,
) {
    val view = LocalView.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
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
                    text = "Что повлияло?",
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
                    text = if (tags.isEmpty()) "Сохранено ✓ · отметь, что повлияло, и карточка свернётся"
                    else "Сохранено ✓ · учту в завтрашнем прогнозе",
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
internal fun TagChip(text: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
internal fun View.confirmHaptic() {
    performHapticFeedback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.CLOCK_TICK
    )
}
