package ru.keegoo.companion.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

class CheckInReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECKIN) return
        val feel = intent.getStringExtra(EXTRA_FEEL) ?: return

        NotificationManagerCompat.from(context).cancel(NOTIF_ID_CHECKIN)

        val label = when (feel) {
            "OK"   -> "Отлично — записано ✓"
            "MEH"  -> "Нормально — записано ✓"
            "HARD" -> "Тяжело — записано ✓"
            else   -> "Записано ✓"
        }
        Toast.makeText(context, label, Toast.LENGTH_SHORT).show()

        // TODO: передать feel в репозиторий → POST /api/v1/checkin
    }
}
