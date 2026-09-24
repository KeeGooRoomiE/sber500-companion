package ru.keegoo.companion.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

const val CHANNEL_MORNING  = "ch_morning"
const val CHANNEL_CHECKIN  = "ch_checkin"

const val EXTRA_FEEL = "feel"
const val ACTION_CHECKIN = "ru.keegoo.companion.ACTION_CHECKIN"

const val NOTIF_ID_MORNING = 1001
const val NOTIF_ID_CHECKIN = 1002

private val TIPS = listOf(
    "Попробуй первые 30 минут после пробуждения не брать телефон — мозг острее на свежем воздухе.",
    "Три коротких перерыва по 5 минут эффективнее одного длинного часового.",
    "Самые сложные задачи лучше делать в первые 2–3 часа работы, когда воля ещё не потрачена.",
    "Стакан воды до кофе — простой способ чуть ускорить метаболизм с утра.",
    "Если чувствуешь тревогу — запиши три конкретных дела, которые от тебя зависят сегодня.",
    "Вечером 10 минут на подготовку к завтрашнему дню экономят утром полчаса хаоса.",
    "Короткая прогулка (15–20 мин) снижает кортизол сильнее, чем большинство других перерывов.",
    "Переключение между задачами стоит ~23 минуты — старайся завершать блоки полностью.",
    "Телефон лицом вниз на столе реально снижает желание проверить его без причины.",
    "Если день ощущается тяжёлым — вспомни одно конкретное дело, которое сегодня сделал хорошо.",
    "Хорошее освещение рабочего места уменьшает усталость глаз и влияет на настроение.",
    "Плейлист без слов помогает концентрации лучше, чем привычная музыка с текстом.",
)

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

fun showMorningNotification(context: Context, text: String? = null) {
    val tip = text ?: TIPS.random()
    val notif = NotificationCompat.Builder(context, CHANNEL_MORNING)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Прогноз на сегодня")
        .setContentText(tip)
        .setStyle(NotificationCompat.BigTextStyle().bigText(tip))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .build()

    NotificationManagerCompat.from(context).notify(NOTIF_ID_MORNING, notif)
}

fun showCheckinNotification(context: Context) {
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
        .setContentTitle("Как прошёл день?")
        .setContentText("Нажми — и готово")
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .addAction(0, "😊 Отлично",   actionIntent("OK"))
        .addAction(0, "😐 Нормально", actionIntent("MEH"))
        .addAction(0, "😮‍💨 Тяжело",   actionIntent("HARD"))
        .build()

    NotificationManagerCompat.from(context).notify(NOTIF_ID_CHECKIN, notif)
}
