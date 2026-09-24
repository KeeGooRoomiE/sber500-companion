package ru.keegoo.companion.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.keegoo.companion.data.repository.CompanionRepository
import ru.keegoo.companion.domain.model.DayFeel
import java.time.LocalDate
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CheckInEntryPoint {
    fun repository(): CompanionRepository
}

class CheckInReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECKIN) return
        val feelStr = intent.getStringExtra(EXTRA_FEEL) ?: return

        val feel = when (feelStr) {
            "OK"   -> DayFeel.OK
            "MEH"  -> DayFeel.MEH
            "HARD" -> DayFeel.HARD
            else   -> return
        }

        NotificationManagerCompat.from(context).cancel(NOTIF_ID_CHECKIN)

        val label = when (feel) {
            DayFeel.OK   -> "Отлично — записано ✓"
            DayFeel.MEH  -> "Нормально — записано ✓"
            DayFeel.HARD -> "Тяжело — записано ✓"
        }
        Toast.makeText(context, label, Toast.LENGTH_SHORT).show()

        val repo = EntryPointAccessors
            .fromApplication(context.applicationContext, CheckInEntryPoint::class.java)
            .repository()

        CoroutineScope(Dispatchers.IO).launch {
            repo.postCheckIn(LocalDate.now(), feel)
        }
    }
}
