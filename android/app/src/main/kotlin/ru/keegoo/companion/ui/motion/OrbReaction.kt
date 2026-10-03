package ru.keegoo.companion.ui.motion

import android.content.Context
import ru.keegoo.companion.data.prefs.MaxOrbTapSlotsPerDay
import ru.keegoo.companion.data.prefs.advanceOrbTapSlot
import ru.keegoo.companion.data.prefs.orbTapSlotToday
import ru.keegoo.companion.data.repository.CompanionRepository
import ru.keegoo.companion.ui.home.Insights

/** How long the orb's reaction bubble stays up — long enough to read ≤60 characters, no more. */
const val ORB_REACTION_MS = 2_200L

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

/**
 * Resolves one orb-tap reaction: spends today's next slot if any remain — advancing it either
 * way, success or not, since the attempt is already spent — or a line from [OrbReactionPool]
 * once the day's five slots are gone or the call failed.
 */
suspend fun resolveOrbReaction(context: Context, repository: CompanionRepository): String {
    val slot = context.orbTapSlotToday()
    if (slot > MaxOrbTapSlotsPerDay) return OrbReactionPool.random()
    context.advanceOrbTapSlot()
    return repository.insight(Insights.ORBTAP, slot.toString())
        .getOrNull()?.text?.trim()?.takeIf(String::isNotEmpty)
        ?: OrbReactionPool.random()
}
