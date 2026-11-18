package ru.keegoo.companion.ui

import ru.keegoo.companion.data.collector.hasUsageAccess
import androidx.health.connect.client.HealthConnectClient
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import ru.keegoo.companion.analytics.Events
import ru.keegoo.companion.data.auth.DeviceCredentials
import ru.keegoo.companion.data.prefs.ActivityMarker
import ru.keegoo.companion.data.repository.CompanionRepository
import ru.keegoo.companion.notifications.EXTRA_NOTIF_SOURCE
import ru.keegoo.companion.ui.theme.CompanionTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var repository: CompanionRepository
    @Inject lateinit var credentials: DeviceCredentials

    override fun onStart() {
        super.onStart()
        // Every open leaves a mark; analytics hears only about a new session (30 min apart).
        if (ActivityMarker.markOpen(this)) {
            val source = intent.getStringExtra(EXTRA_NOTIF_SOURCE)
                ?: if (intent.data?.scheme == "companion") "link" else "launcher"
            val usage = hasUsageAccess()
            lifecycleScope.launch {
                // Any Health Connect read permission granted counts: the question is «is there a data source».
                val health = runCatching {
                    HealthConnectClient.getOrCreate(this@MainActivity).permissionController
                        .getGrantedPermissions().isNotEmpty()
                }.getOrNull()
                Events.appOpen(source, usage, health)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // companion://install?src=<channel>&c=<campaign>: the channel link says where this person
        // came from. Must land before the first request, which registers the device.
        intent.data?.takeIf { it.scheme == "companion" && it.host == "install" }?.let {
            credentials.captureInstallSource(it.getQueryParameter("src"), it.getQueryParameter("c"))
        }

        intent.getStringExtra(EXTRA_NOTIF_SOURCE)?.let { source ->
            lifecycleScope.launch { repository.postEvent("notification_opened:$source") }
        }

        setContent {
            CompanionTheme {
                CompanionNavHost()
            }
        }
    }
}
