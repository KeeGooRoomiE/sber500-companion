package ru.keegoo.companion.data.collector

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery optimisation: the difference between the reminders working and not existing.
 *
 * Android puts a rarely-opened app into an App Standby bucket where periodic work runs about
 * once a day at a time the system picks, and several vendors (Xiaomi, Honor, Samsung, Oppo,
 * Vivo) stop it outright. Measured on a real device before this was added: no background work
 * at all over 16 hours, so neither the daily collector nor the morning forecast ever ran.
 *
 * Push covers the case where the person says no, so this is an ask, never a wall.
 */

/** True when the OS already lets the app run in the background unthrottled. */
fun Context.isIgnoringBatteryOptimizations(): Boolean {
    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(packageName)
}

/**
 * The system dialog that asks to exempt the app.
 *
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is a Play-Store-policy-sensitive intent, which is fine
 * here: the app ships as an APK, and the exemption is the app's core promise (a daily
 * notification) rather than a convenience.
 */
@SuppressLint("BatteryLife")
fun batteryOptimizationIntent(packageName: String): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:$packageName"))

/**
 * Fire-and-forget version for callers that are not an Activity. Falls back to the settings
 * list, which is what some OEM builds offer instead of the direct dialog.
 */
fun Context.requestIgnoreBatteryOptimizations() {
    val direct = batteryOptimizationIntent(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(direct) }.onFailure {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
