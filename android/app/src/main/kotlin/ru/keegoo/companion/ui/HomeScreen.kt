package ru.keegoo.companion.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ru.keegoo.companion.BuildConfig
import ru.keegoo.companion.domain.model.DayFeel
import ru.keegoo.companion.notifications.showCheckinNotification
import ru.keegoo.companion.notifications.showMorningNotification
import ru.keegoo.companion.ui.home.HomeViewModel
import ru.keegoo.companion.ui.theme.AppShapes
import ru.keegoo.companion.ui.theme.CompanionTheme
import ru.keegoo.companion.ui.theme.FeelingHard
import ru.keegoo.companion.ui.theme.FeelingMeh
import ru.keegoo.companion.ui.theme.FeelingOk
import ru.keegoo.companion.ui.theme.HealthBad
import ru.keegoo.companion.ui.theme.HealthGood
import ru.keegoo.companion.ui.theme.HealthWarn
import ru.keegoo.companion.ui.theme.Primary
import ru.keegoo.companion.ui.theme.PrimaryFaint

@Composable
fun HomeScreen(vm: HomeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TopBar(isMock = BuildConfig.DEBUG)
        MorningCard(message = state.morningMessage, isLoading = state.isLoading)
        StatsRow(screenMin = state.screenMin, sleepMin = state.sleepMin, unlocks = state.unlocks)
        CheckInSection(selected = state.checkedIn, onSelect = vm::onCheckIn)
        if (BuildConfig.DEBUG) {
            NotifDebugCard(
                onMorning = { showMorningNotification(context) },
                onCheckin  = { showCheckinNotification(context) },
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, name = "Home — mock data")
@Composable
private fun HomeScreenPreview() {
    CompanionTheme { HomeScreen() }
}

// ─── TopBar ───────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(isMock: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Greeting is the MOMENT — displaySmall, not a subtitle
            Text(
                text = "Доброе утро",
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
                    Surface(shape = AppShapes.tag, color = PrimaryFaint) {
                        Text(
                            text = "MOCK",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier.size(44.dp).clip(AppShapes.circle).background(PrimaryFaint),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "✦", fontSize = 20.sp)
        }
    }
}

// ─── Shimmer ──────────────────────────────────────────────────────────────────

@Composable
private fun ShimmerBox(modifier: Modifier, baseColor: Color = MaterialTheme.colorScheme.surfaceVariant) {
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
            .background(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0.0f to baseColor,
                        0.4f to baseColor.copy(alpha = 0.55f),
                        0.5f to baseColor.copy(alpha = 0.30f),
                        0.6f to baseColor.copy(alpha = 0.55f),
                        1.0f to baseColor,
                    ),
                    start = Offset(x, 0f),
                    end   = Offset(x + 500f, 0f),
                )
            )
    )
}

// ─── MorningCard ──────────────────────────────────────────────────────────────

@Composable
private fun MorningCard(message: String?, isLoading: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.cardHero)
            .background(Brush.linearGradient(listOf(Primary, Color(0xFF8B7CF8))))
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Прогноз на сегодня",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.75f),
                fontWeight = FontWeight.SemiBold,
            )
            when {
                isLoading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val sh = Color.White.copy(alpha = 0.25f)
                    ShimmerBox(Modifier.fillMaxWidth().height(14.dp), sh)
                    ShimmerBox(Modifier.fillMaxWidth(0.88f).height(14.dp), sh)
                    ShimmerBox(Modifier.fillMaxWidth(0.65f).height(14.dp), sh)
                }
                message != null -> Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "✦  Первый прогноз — завтра утром",
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
        }
    }
}

// ─── Stats ────────────────────────────────────────────────────────────────────

private data class StatEntry(
    val emoji: String,
    val label: String,
    val value: Int?,
    val isTime: Boolean,
    val warnAbove: Int? = null,
    val warnBelow: Int? = null,
)

private fun statColor(value: Int?, warnAbove: Int?, warnBelow: Int?): Color? {
    if (value == null) return null
    return when {
        warnAbove != null && value > (warnAbove * 1.3f).toInt() -> HealthBad
        warnAbove != null && value > warnAbove                   -> HealthWarn
        warnBelow != null && value < (warnBelow * 0.7f).toInt() -> HealthBad
        warnBelow != null && value < warnBelow                   -> HealthWarn
        else -> HealthGood
    }
}

@Composable
private fun StatsRow(screenMin: Int?, sleepMin: Int?, unlocks: Int?) {
    val entries = listOf(
        StatEntry("📱", "Экран",         screenMin, isTime = true,  warnAbove = 300),
        StatEntry("🌙", "Сон",           sleepMin,  isTime = true,  warnBelow = 420),
        StatEntry("🔓", "Разблокировок", unlocks,   isTime = false, warnAbove = 80),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEachIndexed { index, entry ->
            // Staggered fade-in
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay((index * 80).toLong())
                visible = true
            }
            val animAlpha by animateFloatAsState(
                targetValue = if (visible) 1f else 0f,
                animationSpec = tween(300),
                label = "statAlpha$index",
            )

            // Counter animation: animates from 0 to actual value on first load
            var animTarget by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(entry.value) {
                if (entry.value != null) {
                    delay((index * 80L + 150L))
                    animTarget = entry.value.toFloat()
                }
            }
            val animVal by animateFloatAsState(
                targetValue = animTarget,
                animationSpec = tween(900, easing = FastOutSlowInEasing),
                label = "statVal$index",
            )

            StatCard(
                modifier = Modifier.weight(1f).alpha(animAlpha),
                emoji = entry.emoji,
                label = entry.label,
                rawValue = entry.value,
                displayValue = animVal.toInt(),
                isTime = entry.isTime,
                semanticColor = statColor(entry.value, entry.warnAbove, entry.warnBelow),
            )
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier,
    emoji: String,
    label: String,
    rawValue: Int?,
    displayValue: Int,
    isTime: Boolean,
    semanticColor: Color?,
) {
    val text = when {
        rawValue == null -> "—"
        isTime -> "${displayValue / 60}ч ${displayValue % 60}м"
        else -> displayValue.toString()
    }
    Column(
        modifier = modifier
            .clip(AppShapes.card)
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = emoji, fontSize = 22.sp)
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "\"tnum\"",
            ),
            color = semanticColor ?: MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─── Check-in ─────────────────────────────────────────────────────────────────

@Composable
private fun CheckInSection(selected: DayFeel?, onSelect: (DayFeel) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.cardHero)
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Как прошёл день?",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FeelButton(Modifier.weight(1f), "😊", "Отлично",   DayFeel.OK,   selected, FeelingOk,   onSelect)
            FeelButton(Modifier.weight(1f), "😐", "Нормально", DayFeel.MEH,  selected, FeelingMeh,  onSelect)
            FeelButton(Modifier.weight(1f), "😮‍💨", "Тяжело", DayFeel.HARD, selected, FeelingHard, onSelect)
        }
        AnimatedVisibility(
            visible = selected != null,
            enter = fadeIn(tween(200)) + slideInVertically(tween(200, easing = FastOutSlowInEasing)) { it / 2 },
            exit  = fadeOut(tween(150)),
        ) {
            Text(
                text = "Сохранено ✓",
                style = MaterialTheme.typography.labelMedium,
                color = FeelingOk,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun FeelButton(
    modifier: Modifier,
    emoji: String,
    label: String,
    feel: DayFeel,
    selected: DayFeel?,
    color: Color,
    onSelect: (DayFeel) -> Unit,
) {
    val isSelected = selected == feel
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "feelScale",
    )

    Column(
        modifier = modifier
            .scale(scale)
            .clip(AppShapes.chip)
            .background(
                if (isSelected) color.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSelect(feel)
            }
            .padding(vertical = 14.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = emoji, fontSize = 26.sp)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) color else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ─── Debug ────────────────────────────────────────────────────────────────────

@Composable
private fun NotifDebugCard(onMorning: () -> Unit, onCheckin: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Тест уведомлений",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onMorning,
                modifier = Modifier.weight(1f),
                shape = AppShapes.button,
                contentPadding = PaddingValues(vertical = 10.dp),
            ) {
                Text("🌅 Прогноз", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = onCheckin,
                modifier = Modifier.weight(1f),
                shape = AppShapes.button,
                contentPadding = PaddingValues(vertical = 10.dp),
            ) {
                Text("🌙 Чек-ин", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
