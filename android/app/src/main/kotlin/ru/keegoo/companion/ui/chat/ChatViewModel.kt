package ru.keegoo.companion.ui.chat

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.keegoo.companion.analytics.Events
import ru.keegoo.companion.data.api.ChatRequest
import ru.keegoo.companion.data.api.ChatTurn
import ru.keegoo.companion.data.auth.DeviceCredentials
import ru.keegoo.companion.data.local.chat.ChatHistory
import ru.keegoo.companion.data.local.chat.ChatMessage
import ru.keegoo.companion.data.prefs.profileAnswersNow
import ru.keegoo.companion.data.prefs.saveGeneratedProfileAnswer
import ru.keegoo.companion.data.repository.CompanionRepository
import ru.keegoo.companion.domain.profile.composeProfileForServer
import ru.keegoo.companion.domain.profile.genQuestionId
import ru.keegoo.companion.work.DailyCollectWorker
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

private const val PAGE = 32
private const val WINDOW = PAGE * 5
private const val STARTER = "Расскажи подробнее про сегодняшний день"
const val CHAT_INPUT_MAX = 1000

data class ChatState(
    val messages: List<ChatMessage> = emptyList(), // newest first, matching the reversed LazyColumn
    val older: Boolean = false,
    val newer: Boolean = false,
    val ready: Boolean = false,
    val openingError: Boolean = false,
    val sending: Boolean = false,
    val activeRequest: String? = null,
    val suggestions: List<String> = emptyList(),
    val notice: String? = null,
    val jump: Long? = null,
    val jumpOffset: Int = 0,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentials: DeviceCredentials,
    private val history: ChatHistory,
    private val repository: CompanionRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutable = MutableStateFlow(ChatState())
    val state = mutable.asStateFlow()
    private var owner: String? = null
    private var opening = false
    private var loadingPage = false
    private var windowRevision = 0
    private var daily: ChatMessage? = null
    private var openedEntry: Pair<String?, String?>? = null

    fun open(entry: String?, question: String?, options: List<String>) {
        val target = entry to question
        if (opening || openedEntry == target && mutable.value.ready) return
        opening = true
        mutable.update { it.copy(openingError = false) }
        viewModelScope.launch {
            try {
                val user = credentials.userId ?: withContext(Dispatchers.IO) {
                    credentials.token()
                    credentials.userId
                } ?: error("Registration unavailable")
                owner = user
                val day = LocalDate.now().toString()
                val previousEntry = saved.get<String>("chat_entry")
                val signature = "$entry|${question.orEmpty()}"
                val restored = saved.get<Long>("anchor")?.takeIf { previousEntry == signature }
                val prompt = if (entry == "daily" && !question.isNullOrBlank()) {
                    val id = genQuestionId(question)
                    val row = ChatMessage(
                        owner = user, messageKey = "question:$id", requestId = id,
                        role = "assistant", text = question, source = "daily_question", date = day,
                        dailyId = id, pillsJson = Gson().toJson(options.take(3)),
                    )
                    if (history.insert(row) > 0) Events.chatDailyQuestion(id)
                    history.byKey(user, row.messageKey)
                } else null
                daily = null
                val starter = if (prompt == null && entry == "starter" && previousEntry != signature) {
                    history.enqueue(user, "starter:$day", STARTER, "starter", day, null)
                } else null
                saved["chat_entry"] = signature
                val focus = restored ?: prompt?.seq
                showWindow(user, focus, if (restored != null) saved["offset"] ?: 0 else 0)
                val newest = history.older(user, Long.MAX_VALUE, 1).firstOrNull()
                val openQuestion = prompt ?: newest?.dailyId?.let { history.byKey(user, "question:$it") }
                daily = openQuestion?.takeUnless { history.hasPendingOrAcceptedDailyAnswer(user, it.requestId) }
                setSuggestions(newest)
                mutable.update { it.copy(ready = true) }
                openedEntry = target
                if (starter != null) {
                    Events.chatRequest(starter.requestId, starter.source, starter.previousCount, null)
                    deliver(starter)
                }
                syncPendingProfile(user)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.update { it.copy(openingError = true) }
            } finally {
                opening = false
            }
        }
    }

    private suspend fun showWindow(user: String, anchor: Long?, offset: Int = 0) {
        windowRevision++
        val recent = if (anchor == null) emptyList() else history.newer(user, anchor, PAGE + 1)
        val before = history.older(user, anchor?.plus(1) ?: Long.MAX_VALUE, PAGE + 1)
        val rows = recent.take(PAGE).asReversed() + before.take(PAGE)
        mutable.update { it.copy(
            messages = rows, newer = recent.size > PAGE, older = before.size > PAGE,
            jump = anchor?.takeIf { seq -> rows.any { row -> row.seq == seq } }, jumpOffset = offset,
        ) }
    }

    fun page(older: Boolean) {
        val user = owner ?: return
        val current = mutable.value
        if (loadingPage || !current.ready || (if (older) !current.older else !current.newer)) return
        val edge = (if (older) current.messages.lastOrNull() else current.messages.firstOrNull()) ?: return
        val revision = windowRevision
        loadingPage = true
        viewModelScope.launch {
            try {
                val found = if (older) history.older(user, edge.seq, PAGE + 1)
                    else history.newer(user, edge.seq, PAGE + 1)
                if (revision != windowRevision) return@launch
                mutable.update { now ->
                    if (older) {
                        val merged = now.messages + found.take(PAGE)
                        now.copy(messages = merged.takeLast(WINDOW), older = found.size > PAGE, newer = now.newer || merged.size > WINDOW)
                    } else {
                        val merged = found.take(PAGE).asReversed() + now.messages
                        now.copy(messages = merged.take(WINDOW), newer = found.size > PAGE, older = now.older || merged.size > WINDOW)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.update { it.copy(notice = "Не удалось загрузить историю") }
            } finally {
                loadingPage = false
            }
        }
    }

    fun rememberAnchor(seq: Long, offset: Int) {
        if (mutable.value.jump != null) return
        saved["anchor"] = seq
        saved["offset"] = offset
    }

    fun jumped(seq: Long) { mutable.update { if (it.jump == seq) it.copy(jump = null) else it } }

    fun send(raw: String, source: String, onStored: () -> Unit = {}) {
        val user = owner ?: return
        val text = raw.trim()
        if (user != credentials.userId || !mutable.value.ready || mutable.value.sending || text.isEmpty() ||
            text.codePointCount(0, text.length) > CHAT_INPUT_MAX) return
        val question = daily
        mutable.update { it.copy(sending = true, notice = null) }
        viewModelScope.launch {
            try {
                val id = question?.dailyId
                val key = UUID.randomUUID().toString()
                val msg = history.enqueue(user, key, text, source, LocalDate.now().toString(), id) ?: return@launch
                daily = null
                onStored()
                Events.chatRequest(msg.requestId, source, msg.previousCount, id)
                showWindow(user, null)
                mutable.update { it.copy(jump = it.messages.firstOrNull()?.seq, suggestions = emptyList()) }
                deliver(msg)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.update { it.copy(notice = "Не удалось открыть переписку. Попробуй ещё раз") }
            } finally {
                mutable.update { it.copy(sending = false) }
            }
        }
    }

    fun retry(message: ChatMessage) {
        if (mutable.value.sending || message.owner != owner) return
        mutable.update { it.copy(sending = true) }
        viewModelScope.launch {
            try {
                history.sent(message.owner, message.requestId)?.let { deliver(it) }
            } finally {
                mutable.update { it.copy(sending = false) }
            }
        }
    }

    private suspend fun deliver(msg: ChatMessage) {
        mutable.update { it.copy(sending = true, activeRequest = msg.requestId) }
        val started = SystemClock.elapsedRealtime()
        val key = "reply:${msg.requestId}"
        try {
            check(credentials.userId == msg.owner)
            if (msg.source == "starter") {
                DailyCollectWorker.runNowAndWait(context, pastDays = 1, timeoutMs = 5_000)
            }
            val recent = history.contextBefore(msg.owner, msg.seq, 8)
            val terms = msg.text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).distinct()
                .filter { it.length >= 4 && it !in stopWords }.sortedByDescending { it.length }.take(2)
            val matches = if (recent.size < 8) emptyList() else terms.flatMap {
                history.searchBefore(msg.owner, recent.last().seq, it)
            }
            val question = msg.dailyId?.let { history.byKey(msg.owner, "question:$it") }
            val turns = (matches.take(if (question == null) 4 else 3) + recent + listOfNotNull(question))
                .distinctBy { it.seq }.sortedBy { it.seq }.map { ChatTurn(it.role, it.text) }
            val response = repository.chat(ChatRequest(
                id = msg.requestId, text = msg.text, source = msg.source, date = msg.date,
                dailyQuestionId = msg.dailyId, history = turns,
            ))
            check(credentials.userId == msg.owner)
            val answer = response.text.trim()
            require(answer.isNotEmpty() && answer.codePointCount(0, answer.length) <= 1_000)
            val pills = response.pills.orEmpty().filter { it.isNotBlank() }.distinct().take(3)
            val acceptedDaily = msg.dailyId != null && response.kind == "answer"
            history.finish(msg.owner, key, response.kind, answer, answer.lowercase(), Gson().toJson(pills), !acceptedDaily)
            if (msg.dailyId != null && !acceptedDaily) daily = question
            history.byKey(msg.owner, key)?.let { reply ->
                mutable.update { state -> state.copy(messages = state.messages.map { if (it.seq == reply.seq) reply else it }) }
            }
            setSuggestions(history.older(msg.owner, Long.MAX_VALUE, 1).firstOrNull())
            Events.chatOutcome(msg.requestId, response.kind.ifBlank { "ok" }, SystemClock.elapsedRealtime() - started)
            if (acceptedDaily) syncPendingProfile(msg.owner)
        } catch (e: CancellationException) {
            Events.chatOutcome(msg.requestId, "cancelled", SystemClock.elapsedRealtime() - started)
            throw e
        } catch (_: Exception) {
            Events.chatOutcome(msg.requestId, "error", SystemClock.elapsedRealtime() - started)
        } finally {
            mutable.update { it.copy(sending = false, activeRequest = null) }
        }
    }

    private suspend fun syncPendingProfile(user: String) {
        try {
            syncProfile(user)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The durable unsynced row is retried when chat opens again.
        }
    }

    private suspend fun syncProfile(user: String) {
        if (user != credentials.userId) return
        for (reply in history.awaitingProfile(user)) {
            val id = reply.dailyId ?: continue
            val question = history.byKey(user, "question:$id") ?: continue
            val answer = history.sent(user, reply.requestId) ?: continue
            context.saveGeneratedProfileAnswer(question.text, answer.text, LocalDate.parse(question.date))
            if (repository.putProfile(composeProfileForServer(context.profileAnswersNow())).isSuccess) {
                history.markSynced(user, reply.messageKey)
            }
        }
    }

    private fun setSuggestions(latest: ChatMessage?) {
        val suggestions = daily?.pills() ?: latest?.takeIf { it.state == "done" }?.pills().orEmpty()
        mutable.update { it.copy(suggestions = suggestions) }
    }

    private companion object {
        val stopWords = setOf("расскажи", "почему", "вчера", "сегодня", "тогда", "данные", "только", "этого", "какие", "когда", "меня")
    }
}
