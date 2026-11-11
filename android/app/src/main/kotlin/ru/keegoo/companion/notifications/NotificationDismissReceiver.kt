package ru.keegoo.companion.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.keegoo.companion.analytics.Events
import ru.keegoo.companion.data.prefs.ActivityMarker

const val ACTION_NOTIF_DISMISSED = "ru.keegoo.companion.ACTION_NOTIF_DISMISSED"
const val EXTRA_DISMISS_KIND = "kind"

/** A notification was swiped away: that is a sign of life, and the funnel wants to know. */
class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NOTIF_DISMISSED) return
        ActivityMarker.mark(context, ActivityMarker.Source.Dismiss)
        Events.notificationDismissed(intent.getStringExtra(EXTRA_DISMISS_KIND).orEmpty())
    }
}
