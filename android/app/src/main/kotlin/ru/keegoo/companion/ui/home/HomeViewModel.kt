package ru.keegoo.companion.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.keegoo.companion.BuildConfig
import ru.keegoo.companion.data.repository.CompanionRepository
import ru.keegoo.companion.domain.model.DayFeel
import java.time.LocalDate
import javax.inject.Inject

enum class StatKind { Screen, Sleep, Unlocks }

/** One line under «Почему такой прогноз»: yesterday vs the person's own usual. */
data class ForecastFact(
    val label: String,
    val value: String,
    val usual: String,
    val fraction: Float,       // yesterday, 0..1 of the bar
    val usualFraction: Float,  // usual marker, 0..1
)

/** Last 7 days, oldest first; the last value is yesterday. */
data class StatDetail(val week: List<Int>, val note: String?)

data class HomeUiState(
    val morningMessage: String? = null,
    val action: String? = null,
    val actionDone: Boolean = false,
    val facts: List<ForecastFact> = emptyList(),
    val screenMin: Int? = null,
    val sleepMin: Int? = null,
    val unlocks: Int? = null,
    val details: Map<StatKind, StatDetail> = emptyMap(),
    val checkedIn: DayFeel? = null,
    val tags: Set<String> = emptySet(),
    val isLoading: Boolean = true,
)

val CheckInTags = listOf("Работа", "Люди", "Спорт", "Сон", "Дорога", "Телефон")

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: CompanionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    private var checkInJob: Job? = null

    init {
        if (BuildConfig.DEBUG) loadMock() else loadReal()
    }

    private fun loadMock() {
        _state.value = HomeUiState(
            morningMessage = "Сон на 48 минут короче твоего обычного, зато экрана меньше обычного. Начни с простой задачи и выйди на 15 минут до обеда.",
            action = "Выйти на 15 минут до обеда",
            facts = listOf(
                ForecastFact("Сон", "6 ч 22 м", "обычно 7 ч 10 м", .74f, .83f),
                ForecastFact("Экран", "3 ч 34 м", "обычно 3 ч 50 м", .62f, .67f),
                ForecastFact("Instagram", "1 ч 12 м", "обычно 58 м", .55f, .44f),
            ),
            screenMin = 214,
            sleepMin = 382,
            unlocks = 47,
            details = mapOf(
                StatKind.Screen to StatDetail(
                    listOf(251, 198, 236, 276, 203, 230, 214),
                    "Вчера меньше твоего среднего. Больше всего экрана было в воскресенье: 4 ч 36 м, из них YouTube 1 ч 40 м.",
                ),
                StatKind.Sleep to StatDetail(
                    listOf(455, 440, 415, 470, 428, 420, 382),
                    "Вторую ночь подряд сон короче обычного. Последнее разблокирование вчера — в 00:47.",
                ),
                StatKind.Unlocks to StatDetail(
                    listOf(52, 44, 61, 49, 38, 55, 47),
                    "Обычный день. Реже всего телефон брал в понедельник: 38 раз.",
                ),
            ),
            isLoading = false,
        )
    }

    private fun loadReal() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            repository.getMorning()
                .onSuccess { resp -> _state.update { it.copy(morningMessage = resp.message, isLoading = false) } }
                .onFailure { _state.update { it.copy(isLoading = false) } }
        }
    }

    fun onCheckIn(feel: DayFeel) {
        _state.update { it.copy(checkedIn = feel) }
        sendCheckIn(debounceMs = 0)
    }

    fun onToggleTag(tag: String) {
        _state.update { s -> s.copy(tags = if (tag in s.tags) s.tags - tag else s.tags + tag) }
        sendCheckIn(debounceMs = 800)
    }

    fun onToggleAction() {
        _state.update { it.copy(actionDone = !it.actionDone) }
    }

    // Tags are tapped in bursts — send one upsert after the person stops tapping.
    private fun sendCheckIn(debounceMs: Long) {
        val feel = _state.value.checkedIn ?: return
        checkInJob?.cancel()
        checkInJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val tags = CheckInTags.filter { it in _state.value.tags }
            repository.postCheckIn(LocalDate.now(), feel, tags)
        }
    }
}
