package ru.keegoo.companion.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.keegoo.companion.data.local.AppOption
import ru.keegoo.companion.data.local.TodayRepository
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val today: TodayRepository,
) : ViewModel() {
    private val _topApps = MutableStateFlow<List<AppOption>>(emptyList())
    /** The person's own most used apps — options for the «рабочие приложения» question. */
    val topApps: StateFlow<List<AppOption>> = _topApps

    init {
        viewModelScope.launch { _topApps.value = runCatching { today.weekTopApps() }.getOrDefault(emptyList()) }
    }
}
