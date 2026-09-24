package ru.keegoo.companion.domain.profile

import java.time.LocalTime

/**
 * Questions from the orb («Расскажи о себе»). Answers make the forecast more precise
 * and set the notification times. Options are what the person taps; [freeText] asks for input.
 */
data class ProfileQuestion(
    val id: String,
    val title: String,
    val hint: String? = null,
    val options: List<String> = emptyList(),
    val freeText: Boolean = false,
    val onlyOnWifi: Boolean = false,
)

object ProfileIds {
    const val NAME = "name"
    const val WIFI = "wifi_home"
    const val WORK = "work_place"
    const val BEDTIME = "bedtime"
    const val WAKE = "wake"
    const val WEARABLE = "wearable"
    const val MORNING_TIME = "morning_time"
    const val EVENING_TIME = "evening_time"
    const val TONE = "tone"
    const val GOAL = "goal"
}

val ProfileQuestions = listOf(
    ProfileQuestion(ProfileIds.NAME, "Как к тебе обращаться?", "Имя будет в приветствии", freeText = true),
    ProfileQuestion(
        ProfileIds.WIFI, "Ты сейчас в Wi‑Fi. Это домашняя сеть?",
        "Так я отличу дни дома от дней в дороге",
        listOf("Да, домашняя", "Рабочая", "Другая"), onlyOnWifi = true,
    ),
    ProfileQuestion(ProfileIds.WORK, "Где ты обычно работаешь или учишься?", null, listOf("Из дома", "В офисе", "По-разному", "Сейчас не работаю")),
    ProfileQuestion(ProfileIds.BEDTIME, "Во сколько обычно ложишься?", null, listOf("До 23:00", "23:00–00:00", "После полуночи", "По-разному")),
    ProfileQuestion(ProfileIds.WAKE, "Во сколько обычно встаёшь?", null, listOf("До 7:00", "7:00–8:00", "8:00–9:00", "Позже")),
    ProfileQuestion(ProfileIds.WEARABLE, "Носишь часы или фитнес-браслет?", "С ними сон считается точнее", listOf("Да, каждый день", "Иногда", "Нет")),
    ProfileQuestion(ProfileIds.MORNING_TIME, "Когда присылать утренний прогноз?", null, listOf("7:00", "7:40", "8:30", "9:30")),
    ProfileQuestion(ProfileIds.EVENING_TIME, "Когда спрашивать, как прошёл день?", null, listOf("19:30", "20:30", "21:30", "22:30")),
    ProfileQuestion(ProfileIds.TONE, "Как тебе удобнее, чтобы я говорил?", null, listOf("Мягко, с поддержкой", "Коротко и по делу")),
    ProfileQuestion(ProfileIds.GOAL, "Что хочется изменить?", null, listOf("Меньше телефона", "Лучше спать", "Больше двигаться", "Просто наблюдать")),
)

val DefaultMorningTime: LocalTime = LocalTime.of(7, 40)
val DefaultEveningTime: LocalTime = LocalTime.of(20, 30)

fun parseTime(value: String?): LocalTime? = value?.let {
    runCatching {
        val (h, m) = it.split(":").map(String::toInt)
        LocalTime.of(h, m)
    }.getOrNull()
}
