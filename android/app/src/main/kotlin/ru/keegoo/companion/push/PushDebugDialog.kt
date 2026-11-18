package ru.keegoo.companion.push

import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.health.connect.client.HealthConnectClient
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import ru.keegoo.companion.BuildConfig
import ru.keegoo.companion.data.auth.DeviceCredentials
import ru.keegoo.companion.data.collector.hasUsageAccess
import ru.keegoo.companion.data.collector.isIgnoringBatteryOptimizations
import ru.keegoo.companion.data.prefs.ActivityMarker
import ru.keegoo.companion.data.prefs.ReminderConfig
import ru.keegoo.companion.data.prefs.checkInCount
import ru.rustore.sdk.core.feature.model.FeatureAvailabilityResult
import ru.rustore.sdk.pushclient.RuStorePushClient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How many taps on «Расскажи о себе» open the diagnostics: far more than anyone taps by accident. */
const val DIAGNOSTICS_TAPS = 25

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DiagnosticsEntryPoint {
    fun credentials(): DeviceCredentials
}

private val stamp = DateTimeFormatter.ofPattern("dd.MM HH:mm")

private fun Long.asStamp(): String = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(stamp)

private fun yesNo(v: Boolean) = if (v) "да" else "нет"

/** One line per fact, «name: value», so it reads the same in the dialog and in a letter. */
internal fun diagnosticsText(lines: List<Pair<String, String>>): String =
    lines.joinToString("\n") { (k, v) -> "$k: $v" }

/** Everything that can be read at once, without waiting on the push client or Health Connect. */
private fun staticLines(context: Context): List<Pair<String, String>> {
    val userId = runCatching {
        EntryPointAccessors.fromApplication(context.applicationContext, DiagnosticsEntryPoint::class.java)
            .credentials().userId
    }.getOrNull()
    val exactAlarms = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    val midday = ReminderConfig.midday(context)
    val synced = ReminderConfig.syncedAt(context)
    return listOf(
        "Версия" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), источник ${BuildConfig.INSTALL_SOURCE}",
        "Телефон" to "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        "Часовой пояс" to ZoneId.systemDefault().id,
        "Снято" to System.currentTimeMillis().asStamp(),
        "ID" to (userId ?: "не зарегистрирован"),
        "Доступ к статистике экрана" to yesNo(context.hasUsageAccess()),
        "Уведомления разрешены" to yesNo(NotificationManagerCompat.from(context).areNotificationsEnabled()),
        "Работа в фоне без ограничений" to yesNo(context.isIgnoringBatteryOptimizations()),
        "Точные будильники" to yesNo(exactAlarms),
        "Последняя активность" to (ActivityMarker.lastActivityMs(context)?.asStamp() ?: "нет отметок"),
        "Настройки с сервера" to if (synced > 0) "получены ${synced.asStamp()}" else "не получены",
        "Дневное уведомление" to if (midday.enabled) {
            "%02d:%02d ± %d мин".format(midday.centerMin / 60, midday.centerMin % 60, midday.deltaMin)
        } else "выключено",
    )
}

/**
 * Diagnostics a person can send to the author: what the phone allows, what the app sees and
 * whether pushes reach it. Opened by [DIAGNOSTICS_TAPS] taps on the «Расскажи о себе» title.
 * Nothing here is about the person's day: no screen time, no answers, no name.
 */
@Composable
fun PushDebugDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val base = remember { staticLines(context) }
    var token by remember { mutableStateOf("…") }
    var availability by remember { mutableStateOf("…") }
    var health by remember { mutableStateOf("…") }
    var checkIns by remember { mutableStateOf("…") }

    LaunchedEffect(Unit) {
        checkIns = runCatching { context.checkInCount().first().toString() }.getOrDefault("?")
        health = runCatching {
            if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
                "нет на телефоне"
            } else {
                val n = HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().size
                if (n > 0) "есть, разрешений: $n" else "есть, разрешений нет"
            }
        }.getOrDefault("ошибка")
    }
    remember {
        if (BuildConfig.RUSTORE_PUSH_PROJECT_ID.isEmpty()) {
            token = "нет (сборка без пушей)"
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

    val text = diagnosticsText(
        base + listOf(
            "Health Connect" to health,
            "Отметок дня на этом телефоне" to checkIns,
            "Пуши RuStore" to availability,
            "Пуш-токен" to token,
            "Полученные пуши" to context.pushLogRead().ifBlank { "ничего" },
        ),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Диагностика") },
        text = {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Диагностика", text))
                    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                }) { Text("Скопировать") }
                TextButton(onClick = {
                    val target = ReminderConfig.texts(context, "contact").firstOrNull()
                        ?.takeIf { "://" !in it } ?: ru.keegoo.companion.ui.profile.AUTHOR_CONTACT_DEFAULT
                    val uri = "mailto:$target?subject=" + Uri.encode("Компаньон: диагностика") + "&body=" + Uri.encode(text)
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure {
                        // No mail app: the text is at least in the clipboard for any messenger.
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Диагностика", text))
                        Toast.makeText(context, "Почты нет, текст скопирован", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Отправить автору") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
