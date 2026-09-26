package ru.keegoo.companion.ui.home

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
import ru.keegoo.companion.data.local.SleepSource
import ru.keegoo.companion.data.local.TodayData
import ru.keegoo.companion.data.local.TodayRepository
import ru.keegoo.companion.data.collector.hasUsageAccess
import ru.keegoo.companion.data.prefs.clearCheckIn
import ru.keegoo.companion.data.prefs.isBackfilled
import ru.keegoo.companion.work.DailyCollectWorker
import ru.keegoo.companion.data.prefs.profileAnswers
import ru.keegoo.companion.data.prefs.saveCheckIn
import ru.keegoo.companion.data.prefs.todayCheckIn
import ru.keegoo.companion.data.repository.CompanionRepository
import ru.keegoo.companion.domain.forecast.ForecastFact
import ru.keegoo.companion.domain.forecast.buildLocalForecast
import ru.keegoo.companion.domain.forecast.formatMinutes
import ru.keegoo.companion.domain.model.DayFeel
import ru.keegoo.companion.domain.profile.ProfileIds
import ru.keegoo.companion.domain.profile.LocalOnlyProfileIds
import ru.keegoo.companion.domain.profile.ProfileQuestions
import kotlinx.coroutines.flow.onEach
import java.time.LocalDate
import javax.inject.Inject

enum class StatKind { Screen, Sleep, Unlocks }

/** 7 values, oldest first; the last one is today / last night. Null = no data that day. */
data class StatDetail(val week: List<Int?>, val note: String?)

data class HomeUiState(
    val isLoading: Boolean = true,
    val hasUsageAccess: Boolean = true,
    val forecast: String? = null,
    val facts: List<ForecastFact> = emptyList(),
    val screenMin: Int? = null,
    val sleepMin: Int? = null,
    val sleepLabel: String = "Сон",
    val unlocks: Int? = null,
    val details: Map<StatKind, StatDetail> = emptyMap(),
    val checkedIn: DayFeel? = null,
    val tags: Set<String> = emptySet(),
    val name: String? = null,
    val unansweredQuestions: Int = 0,
)

val CheckInTags = listOf("Работа", "Люди", "Спорт", "Сон", "Дорога", "Телефон")

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val today: TodayRepository,
    private val repository: CompanionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    private var checkInJob: Job? = null
    private var refreshJob: Job? = null

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
                    )
                }
            }
        }
    }

    /** Re-read today's data — on start and every time Home comes back to the foreground. */
    fun refresh() {
        viewModelScope.launch { repository.ping() }
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            val data = runCatching { today.load() }.getOrNull()
            _state.update { s -> if (data == null) s.copy(isLoading = false) else s.withData(data) }
            // Fetch LLM morning forecast in parallel; local forecast is already shown as fallback.
            viewModelScope.launch {
                // Usage access granted later than onboarding (e.g. from the card's button):
                // send the week of history once before asking for the forecast.
                if (context.hasUsageAccess() && !context.isBackfilled()) {
                    DailyCollectWorker.runNowAndWait(context, pastDays = 7, timeoutMs = 15_000)
                }
                repository.getMorning()
                    .onSuccess { resp -> _state.update { it.copy(forecast = resp.message) } }
                // Silently ignore failures — local forecast stays visible.
            }
        }
    }

    private var profileJob: Job? = null
    private var lastSentProfile: Map<String, String>? = null

    private fun syncProfile(answers: Map<String, String>) {
        val forServer = answers - LocalOnlyProfileIds
        if (forServer == lastSentProfile) return
        profileJob?.cancel()
        profileJob = viewModelScope.launch {
            delay(1_000)
            if (repository.putProfile(forServer).isSuccess) lastSentProfile = forServer
        }
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

    // Tags are tapped in bursts — save once after the person stops tapping.
    private fun save(debounceMs: Long) {
        val feel = _state.value.checkedIn ?: return
        checkInJob?.cancel()
        checkInJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val tags = _state.value.tags
            context.saveCheckIn(feel, tags)
            repository.postCheckIn(LocalDate.now(), feel, CheckInTags.filter { it in tags })
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
