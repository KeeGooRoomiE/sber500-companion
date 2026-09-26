package ru.keegoo.companion.ui.home

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.keegoo.companion.data.api.model.ExploreQuestion
import ru.keegoo.companion.ui.theme.AppShapes

/**
 * «Хочу ещё»: questions about the person's own data, chosen by the server from what the data can
 * answer (similar days first). Each answer brings the next questions — it feels like a
 * conversation, but every answer stands on computed facts, shown under «На чём основано».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExploreSheet(modifier: Modifier, explore: ExploreUi, onAsk: (ExploreQuestion) -> Unit, onClose: () -> Unit) {
    val scroll = rememberScrollState()
    // New question or answer → keep the latest in view
    LaunchedEffect(explore.items.size, explore.items.lastOrNull()?.loading) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .clip(AppShapes.sheet)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(remember { MutableInteractionSource() }, indication = null) {} // swallow taps
            .padding(20.dp)
            .animateContentSize(spring(dampingRatio = 1f, stiffness = 300f)),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Хочу ещё", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                if (!explore.loading && explore.error == null) {
                    Text(
                        text = if (explore.left > 0) "Осталось вопросов на сегодня: ${explore.left}" else "На сегодня всё — завтра будут новые",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
            modifier = Modifier.weight(1f, fill = false).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            when {
                explore.loading -> WritingPlaceholder()
                explore.error != null -> Text(
                    explore.error,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    explore.items.forEach { ExploreItem(it) }
                    if (explore.items.isEmpty() && explore.next.isEmpty()) {
                        Text(
                            text = "Пока мало данных для вопросов — загляни через пару дней, когда накопится история.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        // The next questions — always at the bottom, next to the thumb
        AnimatedVisibility(
            visible = explore.next.isNotEmpty() && explore.items.none { it.loading },
            enter = expandVertically(spring(dampingRatio = 1f, stiffness = 400f)) + fadeIn(tween(220, delayMillis = 120)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(120)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (explore.items.isEmpty()) "О чём рассказать?" else "Ещё можно спросить",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    explore.next.forEach { q -> QuestionChip(q.text) { onAsk(q) } }
                }
            }
        }
    }
}

@Composable
private fun ExploreItem(item: ExploreItemUi) {
    var why by rememberSaveable(item.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = item.question,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        when {
            item.loading -> WritingPlaceholder()
            item.error != null -> Text(item.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                Text(item.text.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                if (item.facts.isNotEmpty()) {
                    Text(
                        text = if (why) "Скрыть, на чём основано" else "На чём основано",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(AppShapes.tag).clickable { why = !why }.padding(vertical = 2.dp),
                    )
                    AnimatedVisibility(
                        visible = why,
                        enter = expandVertically(spring(dampingRatio = 1f, stiffness = 400f)) + fadeIn(),
                        exit = shrinkVertically(tween(200)) + fadeOut(tween(120)),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            item.facts.forEach { f ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(f, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionChip(text: String, onClick: () -> Unit) {
    val view = LocalView.current
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .clip(AppShapes.chip)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f))
            .border(1.dp, primary.copy(alpha = .6f), AppShapes.chip)
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = primary)
    }
}
