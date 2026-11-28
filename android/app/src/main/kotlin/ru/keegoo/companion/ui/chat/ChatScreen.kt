package ru.keegoo.companion.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filterNotNull
import ru.keegoo.companion.data.local.chat.ChatMessage
import ru.keegoo.companion.ui.motion.CompanionOrb
import ru.keegoo.companion.ui.motion.OrbMode
import ru.keegoo.companion.ui.theme.AppShapes

@Composable
fun ChatScreen(
    entry: String?,
    dailyQuestion: String?,
    onBack: () -> Unit,
    dailyOptions: List<String> = emptyList(),
    vm: ChatViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    var draft by rememberSaveable { mutableStateOf("") }
    var area by remember { mutableStateOf(Rect.Zero) }
    val bubbles = remember { mutableStateMapOf<Long, Rect>() }
    val density = LocalDensity.current
    val safeProgress by remember(density) {
        derivedStateOf {
            val middle = area.center.x
            val radius = with(density) { 36.dp.toPx() }
            val nearest = bubbles.values.filter {
                it.width > 0 && it.height > 0 && it.bottom > area.top &&
                    it.right > middle - radius && it.left < middle + radius
            }.minOfOrNull { it.top }
            val free = if (nearest == null) 72f else with(density) {
                (nearest - area.top).coerceAtLeast(0f).toDp().value
            }
            // The orb's lower edge moves from -12 dp (docked) to 72 dp (expanded).
            ((free + 12f) / 84f).coerceIn(0f, 1f)
        }
    }
    val motion by animateFloatAsState(safeProgress, tween(230), label = "chatOrbDock")
    // Shrink immediately when an answer arrives. On the way back, ease only through clear space.
    val orbProgress = minOf(motion, safeProgress)

    LaunchedEffect(entry, dailyQuestion, dailyOptions) { vm.open(entry, dailyQuestion, dailyOptions) }
    LaunchedEffect(state.jump, state.ready) {
        val anchor = state.jump ?: return@LaunchedEffect
        val index = state.messages.indexOfFirst { it.seq == anchor }
        if (index >= 0 && state.ready) {
            list.scrollToItem(index, state.jumpOffset)
            vm.jumped(anchor)
        }
    }
    LaunchedEffect(list, vm) {
        snapshotFlow {
            if (!state.ready || state.jump != null || list.isScrollInProgress) return@snapshotFlow null
            val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }
            (info?.key as? Long)?.let { it to list.firstVisibleItemScrollOffset }
        }.filterNotNull().collect { (seq, offset) -> vm.rememberAnchor(seq, offset) }
    }
    LaunchedEffect(list, vm) {
        snapshotFlow {
            val seen = list.layoutInfo.visibleItemsInfo
            val count = list.layoutInfo.totalItemsCount
            if (!state.ready || state.jump != null || seen.isEmpty() || count != state.messages.size) return@snapshotFlow null
            when {
                state.older && seen.maxOf { it.index } >= count - 4 -> true to state.messages.last().seq
                state.newer && seen.minOf { it.index } <= 3 -> false to state.messages.first().seq
                else -> null
            }
        }.filterNotNull().collect { (older, _) -> vm.page(older) }
    }

    Column(
        Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Назад") }
            Text("Обсудим?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 12.dp))
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).onGloballyPositioned { area = it.boundsInRoot() },
        ) {
            LazyColumn(
                state = list, reverseLayout = true,
                modifier = Modifier.fillMaxSize().clipToBounds(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 82.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
            ) {
                items(state.messages, key = { it.seq }) { message ->
                    DisposableEffect(message.seq) { onDispose { bubbles.remove(message.seq) } }
                    MessageBubble(
                        message = message,
                        waiting = state.activeRequest == message.requestId,
                        canRetry = !state.sending,
                        onRetry = { vm.retry(message) },
                        onBounds = { bubbles[message.seq] = it },
                    )
                }
            }
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(22.dp)
                    .background(Brush.verticalGradient(listOf(colors.background, Color.Transparent))),
            )
            if (area != Rect.Zero) {
                CompanionOrb(
                    mode = if (state.activeRequest != null) OrbMode.Thinking else OrbMode.Calm,
                    modifier = Modifier.align(Alignment.TopCenter)
                        .offset(y = (-40f + 40f * orbProgress).dp)
                        .size((28f + 44f * orbProgress).dp),
                )
            }
            if (!state.ready) {
                Box(Modifier.align(Alignment.Center)) {
                    if (state.openingError) TextButton(onClick = { vm.open(entry, dailyQuestion, dailyOptions) }) {
                        Text("Не удалось открыть чат. Повторить")
                    } else CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        }
        state.notice?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = colors.error) }
        if (state.suggestions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.suggestions.forEach { pill ->
                    AssistChip(
                        onClick = { vm.send(pill, "pill") }, enabled = state.ready && !state.sending,
                        label = { Text(pill, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.codePointCount(0, it.length) <= CHAT_INPUT_MAX) draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Сообщение") }, minLines = 1, maxLines = 5,
                shape = AppShapes.card,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            Spacer(Modifier.width(4.dp))
            TextButton(
                onClick = {
                    val submitted = draft
                    vm.send(submitted, "typed") { if (draft == submitted) draft = "" }
                },
                enabled = state.ready && !state.sending && draft.isNotBlank(),
            ) { Text("Отправить") }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage, waiting: Boolean, canRetry: Boolean, onRetry: () -> Unit, onBounds: (Rect) -> Unit,
) {
    val mine = message.role == "user"
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            modifier = Modifier.widthIn(max = 420.dp).padding(start = if (mine) 40.dp else 0.dp, end = if (mine) 0.dp else 32.dp)
                .onGloballyPositioned { onBounds(it.boundsInRoot()) },
            shape = AppShapes.card,
            color = if (mine) colors.primaryContainer else colors.surfaceContainer,
            contentColor = if (mine) colors.onPrimaryContainer else colors.onSurface,
        ) {
            if (message.state == "done") {
                SelectionContainer { Text(message.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge) }
            } else if (waiting) {
                CircularProgressIndicator(
                    Modifier.padding(12.dp).size(20.dp).semantics { contentDescription = "Companion готовит ответ" },
                    strokeWidth = 2.dp,
                )
            } else {
                Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ответ не получен", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRetry, enabled = canRetry) { Text("Повторить") }
                }
            }
        }
    }
}
