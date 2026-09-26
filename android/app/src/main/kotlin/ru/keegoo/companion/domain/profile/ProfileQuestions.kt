package ru.keegoo.companion.domain.profile

import java.time.LocalTime

/**
 * Questions from the orb («Расскажи о себе»). Answers (except the name) go to the server and
 * become context for the forecast; notification times also reschedule reminders.
 *
 * [oneTime] questions are answered once: after the answer they fold away for good (there is a
 * "change past answers" link). [multi] lets the person pick several options. Options of
 * [ProfileIds.WORK_APPS] are the person's own top apps, filled in at runtime.
 */
data class ProfileQuestion(
    val id: String,
    val title: String,
    val hint: String? = null,
    val options: List<String> = emptyList(),
    val freeText: Boolean = false,
    val multi: Boolean = false,
    val oneTime: Boolean = false,
    /** Besides the ready-made options, «Своё время…» opens an hour/minute picker; stored as "HH:MM". */
    val timePick: Boolean = false,
    /** Where the picker starts when there is no answer yet. */
    val defaultTime: LocalTime? = null,
)

object ProfileIds {
    const val NAME = "name"
    const val WORK_APPS = "work_apps"
    const val WORK = "work_place"
    const val TRIGGERS = "triggers"
    const val BEDTIME = "bedtime"
    const val WAKE = "wake"
    const val WEARABLE = "wearable"
    const val MORNING_TIME = "morning_time"
    const val EVENING_TIME = "evening_time"
    const val TONE = "tone"
    const val GOAL = "goal"
}

/** Stays on the phone; everything else is sent to the server as forecast context. */
val LocalOnlyProfileIds = setOf(ProfileIds.NAME)

// Declared before ProfileQuestions: top-level vals initialise in file order
val DefaultMorningTime: LocalTime = LocalTime.of(7, 40)
val DefaultEveningTime: LocalTime = LocalTime.of(20, 30)

val ProfileQuestions = listOf(
    ProfileQuestion(ProfileIds.NAME, "Как к тебе обращаться?", "Имя остаётся на телефоне — только для приветствия", freeText = true),
    ProfileQuestion(
        ProfileIds.WORK_APPS, "Какие из твоих приложений — рабочие?",
        "Так я отличу напряжённый рабочий день от обычного залипания", multi = true,
    ),
    ProfileQuestion(ProfileIds.WORK, "Где ты обычно работаешь или учишься?", null, listOf("Из дома", "В офисе", "По-разному", "Сейчас не работаю"), oneTime = true),
    ProfileQuestion(
        ProfileIds.TRIGGERS, "Что чаще всего выбивает из колеи?", "Можно несколько — на это я буду смотреть в первую очередь",
        listOf("Работа и звонки", "Недосып", "Погода", "Люди", "Нагрузка и спорт", "Ничего особенного"), multi = true, oneTime = true,
    ),
    ProfileQuestion(
        ProfileIds.BEDTIME, "Во сколько обычно ложишься?", null, listOf("До 23:00", "23:00–00:00", "После полуночи", "По-разному"),
        oneTime = true, timePick = true, defaultTime = LocalTime.of(23, 30),
    ),
    ProfileQuestion(
        ProfileIds.WAKE, "Во сколько обычно встаёшь?", null, listOf("До 7:00", "7:00–8:00", "8:00–9:00", "Позже"),
        oneTime = true, timePick = true, defaultTime = LocalTime.of(7, 30),
    ),
    ProfileQuestion(ProfileIds.WEARABLE, "Носишь часы или фитнес-браслет?", "С ними сон считается точнее", listOf("Да, каждый день", "Иногда", "Нет"), oneTime = true),
    ProfileQuestion(
        ProfileIds.GOAL, "Что хочется понять или изменить?", "Можно несколько",
        listOf("Меньше телефона", "Лучше спать", "Меньше стресса", "Больше движения", "Понять, что влияет на настроение", "Просто наблюдать"),
        multi = true, oneTime = true,
    ),
    ProfileQuestion(
        ProfileIds.MORNING_TIME, "Когда присылать утренний прогноз?", null, listOf("7:00", "7:40", "8:30", "9:30"),
        timePick = true, defaultTime = DefaultMorningTime,
    ),
    ProfileQuestion(
        ProfileIds.EVENING_TIME, "Когда спрашивать, как прошёл день?", null, listOf("19:30", "20:30", "21:30", "22:30"),
        timePick = true, defaultTime = DefaultEveningTime,
    ),
    ProfileQuestion(ProfileIds.TONE, "Как тебе удобнее, чтобы я говорил?", null, listOf("Мягко, с поддержкой", "Коротко и по делу")),
)


fun parseTime(value: String?): LocalTime? = value?.let {
    runCatching {
        val (h, m) = it.split(":").map(String::toInt)
        LocalTime.of(h, m)
    }.getOrNull()
}
