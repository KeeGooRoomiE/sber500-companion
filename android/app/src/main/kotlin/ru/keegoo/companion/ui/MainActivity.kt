package ru.keegoo.companion.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import ru.keegoo.companion.data.repository.CompanionRepository
import ru.keegoo.companion.notifications.EXTRA_NOTIF_SOURCE
import ru.keegoo.companion.ui.theme.CompanionTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var repository: CompanionRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
