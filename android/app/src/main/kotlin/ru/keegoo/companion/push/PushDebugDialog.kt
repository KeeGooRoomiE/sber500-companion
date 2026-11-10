package ru.keegoo.companion.push

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import ru.keegoo.companion.BuildConfig
import ru.rustore.sdk.core.feature.model.FeatureAvailabilityResult
import ru.rustore.sdk.pushclient.RuStorePushClient

/** Push-test panel: token (copyable) + availability check. Opened by long-pressing a title. */
@Composable
fun PushDebugDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var token by remember { mutableStateOf("…") }
    var availability by remember { mutableStateOf("…") }

    remember {
        if (BuildConfig.RUSTORE_PUSH_PROJECT_ID.isEmpty()) {
            token = "project_id не задан в сборке"
            availability = "—"
        } else {
            RuStorePushClient.getToken()
                .addOnSuccessListener { token = it }
                .addOnFailureListener { token = "ошибка: $it" }
            RuStorePushClient.checkPushAvailability()
                .addOnSuccessListener { r ->
                    availability = when (r) {
                        is FeatureAvailabilityResult.Available -> "доступны"
                        is FeatureAvailabilityResult.Unavailable -> "недоступны: ${r.cause}"
                    }
                }
                .addOnFailureListener { availability = "ошибка: $it" }
        }
        true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Push (тест)") },
        text = {
            SelectionContainer {
                Text("Пуши: $availability\n\nТокен:\n$token\n\nВерсия ${BuildConfig.VERSION_NAME}")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("push token", token))
            }) { Text("Скопировать токен") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
