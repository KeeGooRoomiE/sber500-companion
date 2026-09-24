package ru.keegoo.companion.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.keegoo.companion.BuildConfig
import ru.keegoo.companion.data.repository.CompanionRepository
import ru.keegoo.companion.domain.model.DayFeel
import java.time.LocalDate
import javax.inject.Inject

data class HomeUiState(
    val morningMessage: String? = null,
    val screenMin: Int? = null,
    val sleepMin: Int? = null,
    val unlocks: Int? = null,
    val checkedIn: DayFeel? = null,
    val isLoading: Boolean = true,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: CompanionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    init {
        if (BuildConfig.DEBUG) loadMock() else loadReal()
    }

    private fun loadMock() {
        _state.value = HomeUiState(
            morningMessage = "Вчера было 3 встречи и 6 часов сна — сегодня постарайся не перегружать первую половину дня. Начни с чего-то простого.",
            screenMin = 214,
            sleepMin = 382,
            unlocks = 47,
            isLoading = false,
        )
    }

    private fun loadReal() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            repository.getMorning()
                .onSuccess { resp ->
                    _state.value = _state.value.copy(
                        morningMessage = resp.message,
                        isLoading = false,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(isLoading = false)
                }
        }
    }

    fun onCheckIn(feel: DayFeel) {
        _state.value = _state.value.copy(checkedIn = feel)
        viewModelScope.launch {
            repository.postCheckIn(LocalDate.now(), feel)
        }
    }
}
