package ru.keegoo.companion.ui.profile

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
import ru.keegoo.companion.data.local.AppOption
import ru.keegoo.companion.data.local.TodayRepository
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
import ru.keegoo.companion.ui.motion.ORB_REACTION_MS
import ru.keegoo.companion.ui.motion.resolveOrbReaction
import javax.inject.Inject

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

    private val _profileSummary = MutableStateFlow(ProfileSummaryUi())
    /**
     * «Твой профиль» — one line, read fresh once a day. «Уточнить» spends a second call only
     * if it is actually answered: the question the model offers, then the regenerated text
     * with that answer folded in, both cached the same way as everything else here.
     */
    val profileSummary: StateFlow<ProfileSummaryUi> = _profileSummary

    private val _orbReaction = MutableStateFlow<String?>(null)
    /**
     * The orb's current reaction bubble, or null when quiet. The big orb on this screen is the
     * only one with the poke mechanic — see [onOrbFlurry] and `ui/motion/OrbGesture.kt`.
     */
    val orbReaction: StateFlow<String?> = _orbReaction
    private var orbReactionJob: Job? = null
    private var orbResolving = false

    init {
        viewModelScope.launch { _topApps.value = runCatching { today.weekTopApps() }.getOrDefault(emptyList()) }
        viewModelScope.launch {
            _profileSummary.update { it.copy(clarifyAvailable = !context.wasProfileClarifiedToday()) }
            fetchProfileSummary("1")
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

    /** Three taps on the orb inside five seconds — poking the companion, not a question to it. */
    fun onOrbFlurry() {
        // A phrase is being fetched: let it arrive. Cancelling here would throw away a server
        // line whose daily slot is already spent, and fast tapping would burn all five unseen.
        if (orbResolving) return
        orbReactionJob?.cancel()
        orbReactionJob = viewModelScope.launch {
            orbResolving = true
            val phrase = try {
                resolveOrbReaction(context, repository)
            } finally {
                orbResolving = false
            }
            // A phrase already on screen is simply replaced by the next one.
            _orbReaction.value = phrase
            delay(ORB_REACTION_MS)
            _orbReaction.value = null
        }
    }
}
