package ru.keegoo.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.keegoo.companion.BuildConfig
import ru.keegoo.companion.domain.model.DayFeel
import ru.keegoo.companion.notifications.showCheckinNotification
import ru.keegoo.companion.notifications.showMorningNotification
import ru.keegoo.companion.ui.home.HomeViewModel
import ru.keegoo.companion.ui.theme.*

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
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Доброе утро",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Компаньон",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (isMock) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = PrimaryFaint,
                    ) {
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
            modifier = Modifier.size(44.dp).clip(CircleShape).background(PrimaryFaint),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "✦", fontSize = 20.sp)
        }
    }
}

// ─── MorningCard ──────────────────────────────────────────────────────────────

@Composable
private fun MorningCard(message: String?, isLoading: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
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
                isLoading -> LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                    color = Color.White.copy(alpha = 0.6f),
                    trackColor = Color.White.copy(alpha = 0.2f),
                )
                message != null -> Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    lineHeight = 24.sp,
                )
                else -> Text(
                    text = "Первый прогноз появится завтра утром — после того как соберём данные за сегодня.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    lineHeight = 22.sp,
                )
            }
        }
    }
}

// ─── Stats ────────────────────────────────────────────────────────────────────

@Composable
private fun StatsRow(screenMin: Int?, sleepMin: Int?, unlocks: Int?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard(Modifier.weight(1f), "📱", "Экран",
            screenMin?.let { "${it / 60}ч ${it % 60}м" } ?: "—")
        StatCard(Modifier.weight(1f), "🌙", "Сон",
            sleepMin?.let { "${it / 60}ч ${it % 60}м" } ?: "—")
        StatCard(Modifier.weight(1f), "🔓", "Разблокировок",
            unlocks?.toString() ?: "—")
    }
}

@Composable
private fun StatCard(modifier: Modifier, emoji: String, label: String, value: String) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = emoji, fontSize = 22.sp)
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ─── Check-in ─────────────────────────────────────────────────────────────────

@Composable
private fun CheckInSection(selected: DayFeel?, onSelect: (DayFeel) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Как прошёл день?",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FeelButton(Modifier.weight(1f), "😊", "Отлично",   DayFeel.OK,   selected, FeelingOk,   onSelect)
            FeelButton(Modifier.weight(1f), "😐", "Нормально", DayFeel.MEH,  selected, FeelingMeh,  onSelect)
            FeelButton(Modifier.weight(1f), "😮‍💨", "Тяжело", DayFeel.HARD, selected, FeelingHard, onSelect)
        }
        if (selected != null) {
            Text(
                text = "Сохранено ✓",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FeelButton(
    modifier: Modifier, emoji: String, label: String,
    feel: DayFeel, selected: DayFeel?, color: Color, onSelect: (DayFeel) -> Unit,
) {
    val isSelected = selected == feel
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) color.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TextButton(
            onClick = { onSelect(feel) },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = emoji, fontSize = 26.sp)
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) color else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

// ─── Debug ────────────────────────────────────────────────────────────────────

@Composable
private fun NotifDebugCard(onMorning: () -> Unit, onCheckin: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
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
            OutlinedButton(onClick = onMorning, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                Text("🌅 Прогноз", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(onClick = onCheckin, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                Text("🌙 Чек-ин", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
