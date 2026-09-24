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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.keegoo.companion.domain.model.DayFeel
import ru.keegoo.companion.ui.theme.*

@Composable
fun HomeScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TopBar()
        MorningCard(message = null) // null = ещё не сгенерировано
        StatsRow(screenMin = null, sleepMin = null, unlocks = null)
        CheckInSection()
    }
}

@Composable
private fun TopBar() {
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
            Text(
                text = "Компаньон",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(PrimaryFaint),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "✦", fontSize = 20.sp)
        }
    }
}

@Composable
private fun MorningCard(message: String?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(listOf(Primary, Color(0xFF8B7CF8)))
            )
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Прогноз на сегодня",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.75f),
                fontWeight = FontWeight.SemiBold,
            )
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    lineHeight = 24.sp,
                )
            } else {
                Text(
                    text = "Первый прогноз появится завтра утром — после того как соберём данные за сегодня.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    lineHeight = 22.sp,
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .padding(top = 4.dp),
                    color = Color.White.copy(alpha = 0.6f),
                    trackColor = Color.White.copy(alpha = 0.2f),
                )
            }
        }
    }
}

@Composable
private fun StatsRow(screenMin: Int?, sleepMin: Int?, unlocks: Int?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatCard(
            modifier = Modifier.weight(1f),
            emoji = "📱",
            label = "Экран",
            value = screenMin?.let { "${it / 60}ч ${it % 60}м" } ?: "—",
        )
        StatCard(
            modifier = Modifier.weight(1f),
            emoji = "🌙",
            label = "Сон",
            value = sleepMin?.let { "${it / 60}ч" } ?: "—",
        )
        StatCard(
            modifier = Modifier.weight(1f),
            emoji = "🔓",
            label = "Разблокировок",
            value = unlocks?.toString() ?: "—",
        )
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
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CheckInSection() {
    var selected by remember { mutableStateOf<DayFeel?>(null) }

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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FeelButton(
                modifier = Modifier.weight(1f),
                emoji = "😊",
                label = "Отлично",
                feel = DayFeel.OK,
                selected = selected,
                color = FeelingOk,
                onSelect = { selected = it },
            )
            FeelButton(
                modifier = Modifier.weight(1f),
                emoji = "😐",
                label = "Нормально",
                feel = DayFeel.MEH,
                selected = selected,
                color = FeelingMeh,
                onSelect = { selected = it },
            )
            FeelButton(
                modifier = Modifier.weight(1f),
                emoji = "😮‍💨",
                label = "Тяжело",
                feel = DayFeel.HARD,
                selected = selected,
                color = FeelingHard,
                onSelect = { selected = it },
            )
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
    modifier: Modifier,
    emoji: String,
    label: String,
    feel: DayFeel,
    selected: DayFeel?,
    color: Color,
    onSelect: (DayFeel) -> Unit,
) {
    val isSelected = selected == feel
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) color.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (isSelected) Modifier.padding(2.dp) else Modifier
            )
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) color.copy(alpha = 0.12f) else Color.Transparent)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(
            onClick = { onSelect(feel) },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(0.dp),
        ) {
            Column(
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
    }
}
