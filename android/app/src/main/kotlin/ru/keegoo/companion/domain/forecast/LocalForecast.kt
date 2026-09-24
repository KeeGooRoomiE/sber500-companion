package ru.keegoo.companion.domain.forecast

import ru.keegoo.companion.data.local.SleepSource
import ru.keegoo.companion.data.local.TodayData
import kotlin.math.abs
import kotlin.math.max

/** One line under «Почему такой прогноз»: today vs the person's own usual. */
data class ForecastFact(
    val label: String,
    val value: String,
    val usual: String,
    val fraction: Float,       // today, 0..1 of the bar
    val usualFraction: Float,  // usual marker, 0..1; negative = no usual yet
)

data class LocalForecast(val text: String, val facts: List<ForecastFact>)

fun formatMinutes(min: Int): String = when {
    min < 60 -> "$min м"
    min % 60 == 0 -> "${min / 60} ч"
    else -> "${min / 60} ч ${min % 60} м"
}

/**
 * Short rule-based summary of today until the LLM forecast is wired in.
 * Two or three plain sentences: last night, the screen so far, the top app.
 */
fun buildLocalForecast(d: TodayData): LocalForecast? {
    val sentences = mutableListOf<String>()
    val facts = mutableListOf<ForecastFact>()

    if (d.sleepMin != null) {
        val what = if (d.sleepSource == SleepSource.PhoneFree) "Ночью без телефона" else "Спал"
        val usual = d.usualSleep
        sentences += when {
            usual == null -> "$what ${formatMinutes(d.sleepMin)}."
            abs(d.sleepMin - usual) < 20 -> "$what ${formatMinutes(d.sleepMin)} — как обычно."
            d.sleepMin < usual -> "$what ${formatMinutes(d.sleepMin)} — на ${formatMinutes(usual - d.sleepMin)} меньше обычного."
            else -> "$what ${formatMinutes(d.sleepMin)} — на ${formatMinutes(d.sleepMin - usual)} больше обычного."
        }
        facts += fact(if (d.sleepSource == SleepSource.PhoneFree) "Без телефона" else "Сон", d.sleepMin, usual)
    }

    if (d.screenMin != null) {
        val usual = d.usualScreenSoFar
        sentences += when {
            usual == null || usual < 10 -> "Экрана сегодня пока ${formatMinutes(d.screenMin)}, разблокировок ${d.unlocks ?: 0}."
            d.screenMin < usual * 0.85 -> "Экрана пока ${formatMinutes(d.screenMin)} — меньше, чем обычно к этому часу."
            d.screenMin > usual * 1.15 -> "Экрана уже ${formatMinutes(d.screenMin)} — больше, чем обычно к этому часу (${formatMinutes(usual)})."
            else -> "Экрана пока ${formatMinutes(d.screenMin)} — примерно как обычно."
        }
        facts += fact("Экран", d.screenMin, usual)
        if (d.unlocks != null) {
            facts += fact("Разблокировки", d.unlocks, d.usualUnlocksSoFar, asCount = true)
        }
    }

    d.topApp?.takeIf { it.minutes >= 15 }?.let { app ->
        sentences += "Больше всего — ${app.label}, ${formatMinutes(app.minutes)}."
        facts += fact(app.label, app.minutes, app.usualMinutes)
    }

    if (sentences.isEmpty()) return null
    return LocalForecast(sentences.take(3).joinToString(" "), facts)
}

private fun fact(label: String, value: Int, usual: Int?, asCount: Boolean = false): ForecastFact {
    val max = max(value, usual ?: 0).coerceAtLeast(1) * 1.15f
    fun fmt(v: Int) = if (asCount) v.toString() else formatMinutes(v)
    return ForecastFact(
        label = label,
        value = fmt(value),
        usual = usual?.let { "обычно ${fmt(it)}" } ?: "пока без сравнения",
        fraction = value / max,
        usualFraction = usual?.let { it / max } ?: -1f,  // -1: no marker
    )
}
