package ru.keegoo.companion.ui.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.keegoo.companion.data.local.AppOption
import ru.keegoo.companion.data.local.TodayRepository
import ru.keegoo.companion.data.prefs.MaxGenQuestionSlotsPerDay
import ru.keegoo.companion.data.prefs.advanceGenQuestionSlot
import ru.keegoo.companion.data.prefs.genQuestionSlotToday
import ru.keegoo.companion.data.prefs.markProfileClarified
import ru.keegoo.companion.data.prefs.profileAnswersNow
import ru.keegoo.companion.data.prefs.saveProfileAnswer
import ru.keegoo.companion.data.prefs.wasProfileClarifiedToday
import ru.keegoo.companion.data.repository.CompanionRepository
import ru.keegoo.companion.domain.profile.GenQuestionPrefix
import ru.keegoo.companion.domain.profile.GenQuestionTextPrefix
import ru.keegoo.companion.domain.profile.composeProfileForServer
import ru.keegoo.companion.domain.profile.genQuestionId
import ru.keegoo.companion.ui.home.Insights
import javax.inject.Inject

/** One generated question and its answer options, if the model gave a real choice. */
data class GeneratedQuestionUi(val text: String, val options: List<String> = emptyList())

/**
 * A clarifying question under «Твой профиль», and what happened to it.
 *
 * [question] null while it is loading, and stays null for good when there was nothing to ask
 * (not enough data, or the day's budget for it is already spent) — the «Уточнить» link then
 * just disappears instead of opening onto an error.
 */
data class ClarifyUi(
    val question: String? = null,
    val options: List<String> = emptyList(),
    val answered: Boolean = false,
    val failed: Boolean = false,
)

/** «Твой профиль»: the text, and the «Уточнить» flow hanging off it. */
data class ProfileSummaryUi(
    val text: String? = null,
    val clarify: ClarifyUi? = null,
    /** False once «Уточнить» has been answered today, or there is nothing to show it on. */
    val clarifyAvailable: Boolean = true,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val today: TodayRepository,
    private val repository: CompanionRepository,
) : ViewModel() {
    private val _topApps = MutableStateFlow<List<AppOption>>(emptyList())
    /** The person's own most used apps — options for the «рабочие приложения» question. */
    val topApps: StateFlow<List<AppOption>> = _topApps

    private val _generated = MutableStateFlow<GeneratedQuestionUi?>(null)
    /**
     * One question the model wrote after looking at this person's days — the only question here
     * that is not from the fixed list.
     *
     * The fixed questions run out, and after that the screen has nothing to ask; this is what
     * keeps «Расскажи о себе» worth opening on day ten. Cached on the server per slot per day,
     * so re-opening the screen shows the same question rather than a new one every time. Null
     * until it arrives, and null for good if there is not enough data yet — then the screen is
     * simply the list it was.
     */
    val generated: StateFlow<GeneratedQuestionUi?> = _generated

    private val _profileSummary = MutableStateFlow(ProfileSummaryUi())
    /**
     * «Твой профиль» — one line, read fresh once a day. «Уточнить» spends a second call only
     * if it is actually answered: the question the model offers, then the regenerated text
     * with that answer folded in, both cached the same way as everything else here.
     */
    val profileSummary: StateFlow<ProfileSummaryUi> = _profileSummary

    init {
        viewModelScope.launch { _topApps.value = runCatching { today.weekTopApps() }.getOrDefault(emptyList()) }
        viewModelScope.launch { fetchGenerated() }
        viewModelScope.launch {
            _profileSummary.update { it.copy(clarifyAvailable = !context.wasProfileClarifiedToday()) }
            fetchProfileSummary("1")
        }
    }

    private suspend fun fetchGenerated() {
        val slot = context.genQuestionSlotToday()
        repository.insight(Insights.QUESTION, slot.toString()).onSuccess { r ->
            val text = r.text.trim()
            _generated.value = text.takeIf(String::isNotEmpty)?.let { GeneratedQuestionUi(it, r.options.orEmpty()) }
        }
    }

    /**
     * Called right after the generated question on screen gets an answer. Tries for the next
     * one in today's budget — up to [MaxGenQuestionSlotsPerDay] — so an engaged visit can use
     * more than one slot; a visit with no answer never advances past the first.
     */
    fun onGeneratedAnswered() {
        viewModelScope.launch {
            if (context.advanceGenQuestionSlot() <= MaxGenQuestionSlotsPerDay) {
                _generated.value = null
                fetchGenerated()
            }
        }
    }

    private suspend fun fetchProfileSummary(slot: String) {
        repository.insight(Insights.PROFILE, slot).onSuccess { r ->
            _profileSummary.update { it.copy(text = r.text.trim().takeIf(String::isNotEmpty)) }
        }
    }

    /** Opens «Уточнить»: asks the server for one question about the text already on screen. */
    fun openClarify() {
        if (_profileSummary.value.clarify != null) return
        _profileSummary.update { it.copy(clarify = ClarifyUi()) }
        viewModelScope.launch {
            val result = repository.insight(Insights.PROFILE_CLARIFY)
            val clarify = result.fold(
                onSuccess = { r ->
                    val q = r.text.trim()
                    if (q.isEmpty() || r.options.orEmpty().size < 2) ClarifyUi(failed = true)
                    else ClarifyUi(question = q, options = r.options.orEmpty())
                },
                onFailure = { ClarifyUi(failed = true) },
            )
            _profileSummary.update { it.copy(clarify = clarify) }
        }
    }

    /**
     * Picks one of the clarify options: folds it into the profile the same way an answered
     * generated question does (a `gen_` id, the question text beside it), spends today's one
     * «Уточнить» call, and asks for the text again — now with this fact in it.
     *
     * Puts the answer to the server itself rather than waiting for HomeViewModel's debounced
     * sync: the next call is a request for a *different* summary, so the server has to have
     * the new fact before it, not a second later.
     */
    fun answerClarify(option: String) {
        val question = _profileSummary.value.clarify?.question ?: return
        viewModelScope.launch {
            val id = genQuestionId(question)
            context.saveProfileAnswer(id, option)
            context.saveProfileAnswer(GenQuestionTextPrefix + id.removePrefix(GenQuestionPrefix), question)
            context.markProfileClarified()
            _profileSummary.update {
                it.copy(clarify = it.clarify?.copy(answered = true), clarifyAvailable = false, text = null)
            }
            repository.putProfile(composeProfileForServer(context.profileAnswersNow()))
            fetchProfileSummary("2")
        }
    }
}
