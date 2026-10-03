package ru.keegoo.companion.ui.home

import ru.keegoo.companion.data.prefs.checkInDay
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.keegoo.companion.analytics.Events
import ru.keegoo.companion.data.auth.DeviceCredentials
import ru.keegoo.companion.data.local.SleepSource
import ru.keegoo.companion.data.prefs.claimFirstForecast
import ru.keegoo.companion.data.prefs.markMorningDelivered
import ru.keegoo.companion.data.prefs.profileAnswersNow
import ru.keegoo.companion.data.prefs.saveProfileAnswer
import ru.keegoo.companion.data.prefs.MaxOrbTapSlotsPerDay
import ru.keegoo.companion.data.prefs.advanceOrbTapSlot
import ru.keegoo.companion.data.prefs.orbTapSlotToday
import ru.keegoo.companion.data.local.TodayData
import ru.keegoo.companion.data.local.TodayRepository
import ru.keegoo.companion.data.collector.hasUsageAccess
import ru.keegoo.companion.data.prefs.clearCheckIn
import ru.keegoo.companion.data.prefs.isBackfilled
import ru.keegoo.companion.work.DailyCollectWorker
import ru.keegoo.companion.work.ReviewNotificationWorker
import ru.keegoo.companion.data.prefs.profileAnswers
import ru.keegoo.companion.data.prefs.saveCheckIn
import ru.keegoo.companion.data.prefs.todayCheckIn
import ru.keegoo.companion.data.api.model.ExploreAnswer
import ru.keegoo.companion.data.api.model.ExploreQuestion
import ru.keegoo.companion.data.api.model.FollowupQuestionDto
import ru.keegoo.companion.data.api.model.HistoryItem
import ru.keegoo.companion.data.api.model.ReviewResponse
import ru.keegoo.companion.data.api.model.SignalDto
import retrofit2.HttpException
import ru.keegoo.companion.data.repository.CompanionRepository
import ru.keegoo.companion.domain.forecast.ForecastFact
import ru.keegoo.companion.domain.forecast.buildLocalForecast
import ru.keegoo.companion.domain.forecast.formatMinutes
import ru.keegoo.companion.domain.model.DayFeel
import ru.keegoo.companion.domain.profile.Depth
import ru.keegoo.companion.domain.profile.ProfileIds
import ru.keegoo.companion.domain.profile.LocalOnlyProfileIds
import ru.keegoo.companion.domain.profile.composeProfileForServer
import ru.keegoo.companion.domain.profile.ProfileQuestions
import ru.keegoo.companion.domain.profile.genQuestionId
import kotlinx.coroutines.flow.onEach
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

enum class StatKind { Screen, Sleep, Unlocks }

/** Insight kinds, as the server names them. */
object Insights {
    const val MIDDAY = "midday"
    const val STAT = "stat"
    const val RETRO = "retro"
    const val TAG = "tag"
    const val QUESTION = "question"
    const val PROFILE = "profile"
    const val PROFILE_CLARIFY = "profile_clarify"
    const val ORBTAP = "orbtap"
}

/**
 * Said over the orb when a real, grounded reaction isn't available — the daily slots are used
 * up, there isn't enough data yet, or the call simply failed. These are also the exact phrases
 * from the original pitch for this feature; the model's job is to occasionally do better than
 * this list, not to replace it.
 */
val OrbReactionPool = listOf(
    "Тебе скучно?", "Хочешь поговорить?", "В хорошем настроении?", "Как день?",
    "Нравится антистресс?", "Что-то на уме?", "Все нормально?", "Задумался о чём-то?",
    "Ещё разок?", "Щекотно, да?",
)

/** Shown once, after nine idle seconds, as an invitation rather than a reaction. */
val OrbInvitePool = listOf("Тапни на меня", "Можно потрогать", "Поиграй со мной")

/** The key one insight is held under, mirroring the server's cache key. */
internal fun insightKey(kind: String, arg: String = ""): String =
    if (arg.isEmpty()) kind else "$kind:$arg"

/**
 * One line about one slice of the data: waiting, written, or not available.
 *
 * [failed] covers both the error and «not enough data» — in both cases the line simply does not
 * appear. These sit inside screens that already say something without them, so a visible error
 * would be worse than silence.
 */
data class InsightUi(val text: String? = null, val failed: Boolean = false) {
    val loading: Boolean get() = text == null && !failed
}

/** 7 values, oldest first; the last one is today / last night. Null = no data that day. */
data class StatDetail(val week: List<Int?>, val note: String?)

data class HomeUiState(
    val isLoading: Boolean = true,
    val hasUsageAccess: Boolean = true,
    val forecast: String? = null,
    val facts: List<ForecastFact> = emptyList(),
    /** Server-computed evidence for the LLM forecast: «что было заметно вчера». */
    val signals: List<SignalDto> = emptyList(),
    /** Date of the server (LLM) forecast; null while only the local one is shown — nothing to rate. */
    val forecastDate: String? = null,
    /** «Совпало / Не совсем» for today's forecast: null, "hit" or "miss". */
    val morningFeedback: String? = null,
    val screenMin: Int? = null,
    val sleepMin: Int? = null,
    val sleepLabel: String = "Сон",
    val unlocks: Int? = null,
    val details: Map<StatKind, StatDetail> = emptyMap(),
    val checkedIn: DayFeel? = null,
    val tags: Set<String> = emptySet(),
    val name: String? = null,
    val unansweredQuestions: Int = 0,
    /** How much to show. Drives which blocks Home renders and whether a daily review is sent. */
    val depth: Depth = Depth.Full,
    /** Today's screen minutes per hour — the day timeline. */
    val hourlyScreen: List<Int>? = null,
    /**
     * The server is still writing today's forecast while the local one is on screen. Shown as a
     * hint so the text does not simply change under someone mid-read.
     */
    val refining: Boolean = false,
    /** Past morning forecasts, newest first (panels at the bottom). */
    val history: List<HistoryItem> = emptyList(),
    /** The open «Разбор дня» / «Итоги недели» sheet, if any. */
    val review: ReviewUi? = null,
    /** The open «Хочу ещё» sheet, if any. */
    val explore: ExploreUi? = null,
    /** Lines about single slices of the data, by [insightKey]. */
    val insights: Map<String, InsightUi> = emptyMap(),
    /**
     * Today's generated question, when it has arrived and has not been answered yet — drives
     * the banner above the forecast. Separate from [insights]: this one has to disappear the
     * moment the question is answered (anywhere — Home or the orb), not just once the call
     * that fetched it succeeds.
     */
    val dailyQuestion: String? = null,
    /**
     * The orb's current reaction bubble, or null when quiet. Set by a tap flurry and cleared
     * automatically a moment later — this is the only insight here with a lifetime shorter than
     * "until the screen changes".
     */
    val orbReaction: String? = null,
)

/** One question in «Хочу ещё»: waiting for the answer, answered, or failed. */
data class ExploreItemUi(
    val id: String,
    val question: String,
    val text: String? = null,
    val facts: List<String> = emptyList(),
    val error: String? = null,
) {
    val loading: Boolean get() = text == null && error == null
}

data class ExploreUi(
    val loading: Boolean = true,
    val items: List<ExploreItemUi> = emptyList(),
    val next: List<ExploreQuestion> = emptyList(),
    val left: Int = 0,
    val error: String? = null,
)

enum class ReviewKind { Day, Week }

data class ReviewUi(
    val kind: ReviewKind,
    val title: String,
    val loading: Boolean = true,
    val text: String? = null,
    val signals: List<SignalDto> = emptyList(),
    val error: String? = null,
    /** The reviewed date from the server — needed to rate the text. */
    val date: String? = null,
    /** «Похоже на правду?»: null, "hit" or "miss". */
    val feedback: String? = null,
    /** Chips not tapped yet. The catalogue comes from the server, never from the app. */
    val followups: List<FollowupQuestionDto> = emptyList(),
    /** Answered chips, in the order they were tapped — the thread under the insight. */
    val answers: List<FollowupAnswerUi> = emptyList(),
    /** Question id currently being answered; the chip shows a spinner. */
    val followupLoading: String? = null,
)

/** One tapped chip and what came back. */
data class FollowupAnswerUi(val questionId: String, val label: String, val text: String)

/** The window «как идёт день» makes sense in: after lunch, before the evening check-in. */
private const val MIDDAY_FROM = 12
private const val MIDDAY_UNTIL = 18

/** How long the orb's reaction bubble stays up — long enough to read ≤60 characters, no more. */
private const val ORB_REACTION_MS = 2_200L

val CheckInTags = listOf("Работа", "Люди", "Спорт", "Сон", "Дорога", "Телефон")

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val today: TodayRepository,
    private val repository: CompanionRepository,
    private val credentials: DeviceCredentials,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    private var checkInJob: Job? = null
    private var refreshJob: Job? = null
    /** Raw text of today's slot-1 question, independent of whether it is still unanswered. */
    private var dailyQuestionText: String? = null
    private var orbReactionJob: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            context.todayCheckIn().collect { saved ->
                _state.update { it.copy(checkedIn = saved?.feel, tags = saved?.tags.orEmpty()) }
            }
        }
        viewModelScope.launch {
            context.profileAnswers()
                // Send to the server (everything except the name) after the person stops tapping.
                .onEach { answers -> syncProfile(answers) }
                .collect { answers ->
                _state.update {
                    it.copy(
                        name = answers[ProfileIds.NAME]?.takeIf(String::isNotBlank),
                        unansweredQuestions = ProfileQuestions.count { q -> q.id !in answers },
                        depth = Depth.from(answers[ProfileIds.DEPTH]),
                        // Re-checked on every profile change: answering it in the orb clears
                        // the banner here without a second call.
                        dailyQuestion = dailyQuestionText?.takeIf { q -> genQuestionId(q) !in answers },
                    )
                }
            }
        }
        viewModelScope.launch { loadDailyQuestion() }
    }

    /**
     * Idea 1: one extra, visible, opt-in-looking call a day — a banner above the forecast that
     * leads straight into the same generated question the orb already asks. Slot "1" always,
     * so it shares the one call a day with the orb flow rather than spending a second one: the
     * first screen to ask (Home or Profile) pays for it, the other reads the cache.
     */
    private suspend fun loadDailyQuestion() {
        val text = repository.insight(Insights.QUESTION, "1").getOrNull()?.text?.trim()
        if (text.isNullOrEmpty()) return
        dailyQuestionText = text
        val answers = context.profileAnswersNow()
        _state.update { it.copy(dailyQuestion = text.takeIf { q -> genQuestionId(q) !in answers }) }
    }

    /** Re-read today's data — on start and every time Home comes back to the foreground. */
    fun refresh() {
        viewModelScope.launch { repository.ping() }
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            val data = runCatching { today.load() }.getOrNull()
            _state.update { s -> if (data == null) s.copy(isLoading = false) else s.withData(data) }
            viewModelScope.launch { loadMiddayIfDue() }
            // End of the onboarding funnel: the app has delivered what it promised. Reported on
            // whichever text got there first — the local one usually wins by several seconds.
            if (_state.value.forecast != null && context.claimFirstForecast()) {
                Events.firstForecastShown(local = true)
            }
            // Fetch LLM morning forecast in parallel; local forecast is already shown as fallback.
            viewModelScope.launch {
                // Usage access granted later than onboarding (e.g. from the card's button):
                // send the week of history once before asking for the forecast.
                if (context.hasUsageAccess() && !context.isBackfilled()) {
                    DailyCollectWorker.runNowAndWait(context, pastDays = 7, timeoutMs = 15_000)
                }
                repository.getHistory().onSuccess { h -> _state.update { it.copy(history = h.items) } }
                // Reinstalled on a phone the server knows: bring back «Расскажи о себе»
                if (credentials.restorePending) restoreProfile()
                _state.update { it.copy(refining = true) }
                repository.getMorning()
                    .onSuccess { resp ->
                        // Seen in the app — the «first unlock» notification isn't needed today
                        if (resp.message.isNotBlank()) {
                            context.markMorningDelivered()
                            if (context.claimFirstForecast()) Events.firstForecastShown(local = false)
                        }
                        _state.update {
                            it.copy(
                                forecast = resp.message,
                                signals = resp.signals.orEmpty(),
                                forecastDate = resp.date,
                                morningFeedback = resp.feedback?.takeIf(String::isNotBlank),
                            )
                        }
                    }
                // Cleared either way: on failure the local forecast simply stays, and a hint
                // left hanging would promise an update that is not coming.
                _state.update { it.copy(refining = false) }
                // Silently ignore failures — local forecast stays visible.
            }
        }
    }

    /**
     * Ask for one line about one slice of the data. Idempotent per key for the lifetime of the
     * screen: the server caches it for the day anyway, and this keeps a re-composition or a
     * second tap from spending a call.
     */
    fun loadInsight(kind: String, arg: String = "") {
        val key = insightKey(kind, arg)
        if (key in _state.value.insights) return
        _state.update { it.copy(insights = it.insights + (key to InsightUi())) }
        viewModelScope.launch {
            val result = repository.insight(kind, arg)
            val ui = result.fold(
                onSuccess = { r ->
                    val text = r.text.trim()
                    if (text.isEmpty()) InsightUi(failed = true) else InsightUi(text = text)
                },
                onFailure = { InsightUi(failed = true) },
            )
            _state.update { it.copy(insights = it.insights + (key to ui)) }
        }
    }

    /**
     * «Как идёт день» — only in the afternoon, and only once there is something of today to
     * compare. In the morning the forecast has just been read and there is nothing new to say;
     * in the evening the check-in and the day review take over.
     */
    private suspend fun loadMiddayIfDue() {
        val hour = LocalTime.now().hour
        if (hour !in MIDDAY_FROM until MIDDAY_UNTIL) return
        if (_state.value.screenMin == null) return
        if (_state.value.insights.containsKey(insightKey(Insights.MIDDAY))) return
        // Today's row on the server is whatever the morning alarm uploaded, so without this the
        // «как идёт день» line would describe the morning. Send today first, then ask.
        DailyCollectWorker.runNowAndWait(context, pastDays = 1, timeoutMs = 12_000)
        loadInsight(Insights.MIDDAY)
    }

    private suspend fun restoreProfile() {
        val remote = repository.getProfile().getOrNull() ?: return
        val local = context.profileAnswersNow()
        remote.filterKeys { it !in local && it !in LocalOnlyProfileIds }
            .forEach { (id, answer) -> context.saveProfileAnswer(id, answer) }
        credentials.restoreDone()
    }

    private var profileJob: Job? = null
    private var lastSentProfile: Map<String, String>? = null

    private fun syncProfile(answers: Map<String, String>) {
        val forServer = composeProfileForServer(answers)
        if (forServer == lastSentProfile) return
        profileJob?.cancel()
        profileJob = viewModelScope.launch {
            delay(1_000)
            if (repository.putProfile(forServer).isSuccess) lastSentProfile = forServer
        }
    }

    private var reviewJob: Job? = null

    /** «Разбор дня»: yesterday by default, or today while it's still going. */
    fun openDayReview(date: LocalDate) {
        val today = LocalDate.now()
        val title = when (date) {
            today -> "Разбор сегодняшнего дня"
            today.minusDays(1) -> "Разбор вчерашнего дня"
            else -> "Разбор дня"
        }
        runReview(ReviewUi(ReviewKind.Day, title)) { repository.dayReview(date) }
    }

    /** «Итоги недели»: the last 7 days summed up. */
    fun openWeekReview() = runReview(ReviewUi(ReviewKind.Week, "Итоги недели")) { repository.weekReview() }

    fun closeReview() {
        reviewJob?.cancel()
        _state.update { it.copy(review = null) }
    }

    private fun runReview(initial: ReviewUi, call: suspend () -> Result<ReviewResponse>) {
        reviewJob?.cancel()
        _state.update { it.copy(review = initial) }
        reviewJob = viewModelScope.launch {
            val result = call()
            _state.update { s ->
                val current = s.review ?: return@update s
                s.copy(
                    review = result.fold(
                        onSuccess = { r ->
                            current.copy(
                                loading = false, text = r.text, signals = r.signals.orEmpty(),
                                date = r.date, feedback = r.feedback?.takeIf(String::isNotBlank),
                                followups = r.followups.orEmpty(),
                            )
                        },
                        onFailure = { e -> current.copy(loading = false, error = reviewErrorText(e)) },
                    )
                )
            }
        }
    }

    /**
     * A follow-up chip under the open review. One tap = one LLM call, and the server caches the
     * answer per (insight, date, question), so tapping the same chip again costs nothing.
     */
    fun askFollowup(questionId: String) {
        val review = _state.value.review ?: return
        val date = review.date ?: return
        if (review.followupLoading != null) return
        val chip = review.followups.firstOrNull { it.id == questionId } ?: return
        val kind = if (review.kind == ReviewKind.Week) "week" else "day"

        _state.update { it.copy(review = it.review?.copy(followupLoading = questionId)) }
        viewModelScope.launch {
            val result = repository.followup(kind, date, questionId)
            _state.update { s ->
                val cur = s.review ?: return@update s
                s.copy(
                    review = result.fold(
                        onSuccess = { r ->
                            cur.copy(
                                followupLoading = null,
                                followups = cur.followups.filterNot { it.id == questionId },
                                answers = cur.answers + FollowupAnswerUi(questionId, chip.label, r.text),
                            )
                        },
                        // Keep the chip on failure so the tap can be retried.
                        onFailure = { cur.copy(followupLoading = null) },
                    )
                )
            }
        }
    }

    /** «Совпало» / «Не совсем» under today's forecast. Optimistic: the mark shows at once. */
    fun rateMorning(hit: Boolean) {
        val date = _state.value.forecastDate ?: return
        val verdict = if (hit) "hit" else "miss"
        _state.update { s ->
            s.copy(
                morningFeedback = verdict,
                history = s.history.map { h -> if (h.date == date) h.copy(feedback = verdict) else h },
            )
        }
        viewModelScope.launch { repository.feedback("morning", date, hit) }
    }

    /** «Похоже на правду?» under a day / week review. */
    fun rateReview(hit: Boolean) {
        val review = _state.value.review ?: return
        val date = review.date ?: return
        _state.update { it.copy(review = review.copy(feedback = if (hit) "hit" else "miss")) }
        val kind = if (review.kind == ReviewKind.Week) "week" else "day"
        viewModelScope.launch { repository.feedback(kind, date, hit) }
    }

    private var exploreJob: Job? = null

    /** «Хочу ещё»: open the sheet with today's answers and the questions the data can answer. */
    fun openExplore() {
        exploreJob?.cancel()
        _state.update { it.copy(explore = ExploreUi()) }
        exploreJob = viewModelScope.launch {
            val result = repository.exploreState()
            _state.update { s ->
                val cur = s.explore ?: return@update s
                s.copy(
                    explore = result.fold(
                        onSuccess = { r ->
                            cur.copy(
                                loading = false,
                                items = r.answered.orEmpty().map { it.toUi() },
                                next = r.next.orEmpty(),
                                left = r.left,
                            )
                        },
                        onFailure = { cur.copy(loading = false, error = "Не получилось связаться с сервером. Попробуй чуть позже.") },
                    )
                )
            }
        }
    }

    fun askExplore(q: ExploreQuestion) {
        val cur = _state.value.explore ?: return
        if (cur.items.any { it.loading }) return
        _state.update { it.copy(explore = cur.copy(items = cur.items + ExploreItemUi(q.id, q.text), next = emptyList())) }
        exploreJob = viewModelScope.launch {
            val result = repository.exploreAsk(q.id)
            _state.update { s ->
                val e = s.explore ?: return@update s
                s.copy(
                    explore = result.fold(
                        onSuccess = { r ->
                            val a = r.answer
                            e.copy(
                                items = e.items.map { if (it.id == q.id && a != null) a.toUi() else it },
                                next = r.next.orEmpty(),
                                left = r.left,
                            )
                        },
                        onFailure = { err ->
                            e.copy(
                                items = e.items.map { if (it.id == q.id && it.loading) it.copy(error = exploreErrorText(err)) else it },
                                // let the person try another question (or the same one again)
                                next = cur.next,
                            )
                        },
                    )
                )
            }
        }
    }

    fun closeExplore() {
        exploreJob?.cancel()
        _state.update { it.copy(explore = null) }
    }

    private fun ExploreAnswer.toUi() = ExploreItemUi(id, question, text, facts.orEmpty())

    private fun exploreErrorText(e: Throwable): String = when ((e as? HttpException)?.code()) {
        404 -> "На этот вопрос пока не хватает данных."
        429 -> "На сегодня вопросов достаточно — завтра будут новые."
        503 -> "ИИ временно недоступен — попробуй чуть позже."
        else -> "Не получилось связаться с сервером. Попробуй чуть позже."
    }

    private fun reviewErrorText(e: Throwable): String = when ((e as? HttpException)?.code()) {
        404 -> "Пока мало данных для разбора — загляни через день-другой."
        429 -> "На сегодня разборов достаточно — завтра можно снова."
        503 -> "ИИ временно недоступен — попробуй чуть позже."
        else -> "Не получилось связаться с сервером. Попробуй чуть позже."
    }

    fun onCheckIn(feel: DayFeel) {
        _state.update { it.copy(checkedIn = feel) }
        save(debounceMs = 0)
    }

    fun onToggleTag(tag: String) {
        _state.update { s -> s.copy(tags = if (tag in s.tags) s.tags - tag else s.tags + tag) }
        save(debounceMs = 800)
    }

    fun resetCheckIn() {
        viewModelScope.launch { context.clearCheckIn() }
    }

    /**
     * Three taps on the orb inside five seconds — poking the companion, not opening it.
     *
     * Each of today's five slots is tied to a different fact on the server, so this spends a
     * call only on an actual flurry and never twice for the same slot. Past slot 5, or on any
     * failure, the reaction is a line from [OrbReactionPool] instead — free, instant, and the
     * exact phrasing this feature was pitched with.
     */
    fun onOrbFlurry() {
        orbReactionJob?.cancel()
        orbReactionJob = viewModelScope.launch {
            val slot = context.orbTapSlotToday()
            val text = if (slot > MaxOrbTapSlotsPerDay) null else {
                context.advanceOrbTapSlot()
                repository.insight(Insights.ORBTAP, slot.toString()).getOrNull()?.text?.trim()?.takeIf(String::isNotEmpty)
            }
            _state.update { it.copy(orbReaction = text ?: OrbReactionPool.random()) }
            delay(ORB_REACTION_MS)
            _state.update { it.copy(orbReaction = null) }
        }
    }

    // Tags are tapped in bursts — save once after the person stops tapping.
    private fun save(debounceMs: Long) {
        val feel = _state.value.checkedIn ?: return
        checkInJob?.cancel()
        checkInJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val tags = _state.value.tags
            context.saveCheckIn(feel, tags)
            repository.postCheckIn(checkInDay(), feel, CheckInTags.filter { it in tags })
            // «Что у тебя за такие дни» — about the first tag of the check-in. The server needs a
            // couple of days with the tag and a couple without, so early on this stays silent.
            CheckInTags.firstOrNull { it in tags }?.let { loadInsight(Insights.TAG, it) }
            // Same «разбор дня» push as the notification check-in; the worker dedups per day.
            ReviewNotificationWorker.enqueue(context)
        }
    }
}

private fun HomeUiState.withData(d: TodayData): HomeUiState {
    val forecast = buildLocalForecast(d)
    val phoneFree = d.sleepSource == SleepSource.PhoneFree
    return copy(
        isLoading = false,
        hasUsageAccess = d.hasUsageAccess,
        forecast = forecast?.text,
        facts = forecast?.facts.orEmpty(),
        screenMin = d.screenMin,
        unlocks = d.unlocks,
        sleepMin = d.sleepMin,
        sleepLabel = if (phoneFree) "Без телефона" else "Сон",
        hourlyScreen = d.hourlyScreen,
        details = mapOf(
            StatKind.Screen to StatDetail(
                d.weekScreen,
                d.weekScreen.dropLast(1).filterNotNull().takeIf { it.isNotEmpty() }
                    ?.let { "Сегодня — пока что, день ещё идёт. В среднем за день у тебя ${formatMinutes(it.average().toInt())}." },
            ),
            StatKind.Sleep to StatDetail(
                d.weekSleep,
                if (phoneFree) {
                    "Это самая длинная пауза без экрана ночью. Если носишь часы или браслет, подключи Health Connect — сон будет точнее."
                } else {
                    "Сон из Health Connect: с часов, браслета или приложения, которое его записывает."
                },
            ),
            StatKind.Unlocks to StatDetail(
                d.weekUnlocks,
                d.usualUnlocksSoFar?.let { "К этому часу ты обычно разблокируешь телефон $it раз." },
            ),
        ),
    )
}
