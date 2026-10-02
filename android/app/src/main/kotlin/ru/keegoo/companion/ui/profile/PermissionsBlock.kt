package ru.keegoo.companion.ui.profile

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ru.keegoo.companion.data.collector.batteryOptimizationIntent
import ru.keegoo.companion.data.collector.hasUsageAccess
import ru.keegoo.companion.data.collector.isIgnoringBatteryOptimizations
import ru.keegoo.companion.ui.permissions.usageAccessIntent
import ru.keegoo.companion.ui.theme.AppShapes

/**
 * What was skipped during onboarding, offered again here rather than on Home.
 *
 * Onboarding lets every optional step be declined, which is the point — a walkthrough that
 * cannot be refused is a reason to uninstall. But then there has to be a way back, and it
 * belongs next to the other questions about yourself, not as a banner over the forecast.
 *
 * Only what is actually missing is listed. With everything granted the block is not there at
 * all, so it never reads as a nag.
 */
@Composable
internal fun MissingPermissions(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var usage by remember { mutableStateOf(context.hasUsageAccess()) }
    var battery by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }
    var notifications by remember { mutableStateOf(context.hasNotificationPermission()) }
    var steps by remember { mutableStateOf(false) }
    var sleep by remember { mutableStateOf(false) }
    var healthReady by remember { mutableStateOf(false) }

    val healthAvailable = remember {
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    // System settings are a round trip out of the app, so the answers are re-read on return
    // instead of being assumed from what we asked for.
    var refresh by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refresh) {
        usage = context.hasUsageAccess()
        battery = context.isIgnoringBatteryOptimizations()
        notifications = context.hasNotificationPermission()
        if (healthAvailable) {
            val granted = runCatching {
                HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())
            steps = HealthPermission.getReadPermission(StepsRecord::class) in granted
            sleep = HealthPermission.getReadPermission(SleepSessionRecord::class) in granted
        }
        healthReady = true
    }

    val usageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh++ }
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh++ }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }
    val healthLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { refresh++ }

    val rows = buildList {
        if (!usage) add(
            PermissionRow(
                "Видеть экран и разблокировки?",
                "Основа прогноза. Видим минуты и какие приложения открывались — не то, что на экране.",
            ) {
                runCatching { usageLauncher.launch(context.usageAccessIntent(direct = true)) }
                    .recoverCatching { usageLauncher.launch(context.usageAccessIntent(direct = false)) }
            }
        )
        if (healthAvailable && healthReady && !steps) add(
            PermissionRow(
                "Считать шаги?",
                "Только число шагов за день. Без них прогноз чуть грубее.",
            ) {
                healthLauncher.launch(
                    setOf(
                        HealthPermission.getReadPermission(StepsRecord::class),
                        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
                    )
                )
            }
        )
        if (healthAvailable && healthReady && !sleep) add(
            PermissionRow(
                "Считать сон точнее?",
                "Только длительность и время — без пульса и стадий. Сейчас считаем по паузе без экрана.",
            ) {
                healthLauncher.launch(
                    setOf(
                        HealthPermission.getReadPermission(SleepSessionRecord::class),
                        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
                    )
                )
            }
        )
        if (!notifications) add(
            PermissionRow(
                "Присылать прогноз?",
                "Три уведомления в день: прогноз утром, вопрос вечером и разбор. Больше не будет.",
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        )
        if (!battery) add(
            PermissionRow(
                "Работать в фоне?",
                "Почти не тратит батарею. Без этого Android усыпляет приложение и уведомления не приходят.",
            ) {
                runCatching { batteryLauncher.launch(batteryOptimizationIntent(context.packageName)) }
            }
        )
    }

    if (rows.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Что ещё можно включить",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEach { row ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(AppShapes.card)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = row.question,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = row.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        Modifier
                            .clip(AppShapes.button)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(onClick = row.onGrant)
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Разрешить",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

private data class PermissionRow(
    val question: String,
    val detail: String,
    val onGrant: () -> Unit,
)

private fun Context.hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
