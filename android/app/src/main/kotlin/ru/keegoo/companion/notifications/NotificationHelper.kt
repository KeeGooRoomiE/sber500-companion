package ru.keegoo.companion.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ru.keegoo.companion.ui.MainActivity
import java.time.LocalDate

const val CHANNEL_MORNING  = "ch_morning"
const val CHANNEL_CHECKIN  = "ch_checkin"

const val EXTRA_FEEL = "feel"
const val ACTION_CHECKIN = "ru.keegoo.companion.ACTION_CHECKIN"

const val NOTIF_ID_MORNING = 1001
const val NOTIF_ID_CHECKIN = 1002

data class NotificationCopy(val title: String, val body: String)

// Morning mockups until the LLM forecast is wired: no invented numbers, only tone and one idea.
val MorningCopies = listOf(
    NotificationCopy("Доброе утро", "Прогноз на сегодня готов. Загляни — там пара цифр про вчера и одна мысль на день."),
    NotificationCopy("Новый день", "Самые сложные задачи лучше ставить на первые 2–3 часа, пока внимание свежее."),
    NotificationCopy("Утро без спешки", "Попробуй первые 30 минут не брать телефон — день начнётся спокойнее."),
    NotificationCopy("Как спалось?", "Посмотри, сколько получилось сна и во сколько ты отложил телефон вчера."),
    NotificationCopy("Прогноз на сегодня", "Если вчера было тяжело — сегодня хватит одной большой задачи. Остальное подождёт."),
    NotificationCopy("Доброе утро", "Стакан воды до кофе — самый простой способ проснуться чуть бодрее."),
    NotificationCopy("План на день", "Запиши три дела, которые зависят только от тебя. С ними проще начать."),
    NotificationCopy("Утро", "Короткая прогулка днём снижает стресс сильнее, чем кажется. 15 минут достаточно."),
    NotificationCopy("Новый день", "Переключение между задачами съедает много времени — старайся закрывать блоки целиком."),
    NotificationCopy("Доброе утро", "Вчерашний день уже в цифрах. Посмотри, что получилось, и сравни со своей обычной неделей."),
    NotificationCopy("Прогноз готов", "Телефон экраном вниз на столе заметно снижает желание проверять его без причины."),
    NotificationCopy("Утро", "Хорошее начало — это не про продуктивность. Начни с чего-то приятного и простого."),
)

// Evening check-in: answered right from the notification with three buttons.
val EveningCopies = listOf(
    NotificationCopy("Как прошёл день?", "Нажми — и готово"),
    NotificationCopy("Вечер", "Одно касание: как сегодня?"),
    NotificationCopy("День подходит к концу", "Каким он был? Это поможет завтрашнему прогнозу"),
    NotificationCopy("Пара секунд на себя", "Как ты сегодня? Ответ прямо из уведомления"),
    NotificationCopy("Подведём итог", "Отлично, нормально или тяжело — выбери, и всё"),
)

/** Deterministic pick per day, so the same text doesn't repeat two days in a row. */
fun <T> List<T>.forToday(): T = this[LocalDate.now().dayOfYear % size]

fun createNotificationChannels(context: Context) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_MORNING, "Утренний прогноз", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Ежедневный прогноз на основе данных"
        }
    )
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_CHECKIN, "Вечерний чек-ин", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Быстрая оценка дня"
        }
    )
}

private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context, 0,
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

fun showMorningNotification(context: Context, copy: NotificationCopy = MorningCopies.forToday()) {
    val notif = NotificationCompat.Builder(context, CHANNEL_MORNING)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(copy.title)
        .setContentText(copy.body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(copy.body))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(openAppIntent(context))
        .setAutoCancel(true)
        .build()

    runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID_MORNING, notif) }
}

fun showCheckinNotification(context: Context, copy: NotificationCopy = EveningCopies.forToday()) {
    fun actionIntent(feel: String): PendingIntent {
        val intent = Intent(ACTION_CHECKIN).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_FEEL, feel)
        }
        val reqCode = feel.hashCode() and 0xFFFF
        return PendingIntent.getBroadcast(
            context, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    val notif = NotificationCompat.Builder(context, CHANNEL_CHECKIN)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(copy.title)
        .setContentText(copy.body)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(openAppIntent(context))
        .setAutoCancel(true)
        .addAction(0, "😊 Отлично",   actionIntent("OK"))
        .addAction(0, "😐 Нормально", actionIntent("MEH"))
        .addAction(0, "😮‍💨 Тяжело",   actionIntent("HARD"))
        .build()

    // POST_NOTIFICATIONS may be denied — notify() then throws SecurityException on some OEMs
    runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID_CHECKIN, notif) }
}
