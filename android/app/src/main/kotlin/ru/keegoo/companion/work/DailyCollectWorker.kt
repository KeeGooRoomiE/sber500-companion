package ru.keegoo.companion.work

import android.content.Context
import android.os.BatteryManager
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import ru.keegoo.companion.data.collector.HealthConnectCollector
import ru.keegoo.companion.data.collector.UsageStatsCollector
import ru.keegoo.companion.data.repository.CompanionRepository
import ru.keegoo.companion.domain.model.BatterySnapshot
import ru.keegoo.companion.domain.model.DailySnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@HiltWorker
class DailyCollectWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val usageStats: UsageStatsCollector,
    private val healthConnect: HealthConnectCollector,
    private val repository: CompanionRepository,
) : CoroutineWorker(context, params) {

    // Every run sends two days: yesterday in full (the evening after the last run is otherwise
    // lost, and the server builds the morning forecast from it) and today so far.
    override suspend fun doWork(): Result {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val now = Instant.now()

        val yesterday = snapshotFor(today.minusDays(1), end = today.atStartOfDay(zone).toInstant(), zone, battery = null)
        val current = snapshotFor(today, end = now, zone, battery = applicationContext.batteryLevel())

        val ok = repository.postDailySnapshot(yesterday).isSuccess and
            repository.postDailySnapshot(current).isSuccess
        return if (ok) Result.success() else Result.retry()
    }

    /** Usage for [date] until [end]; sleep = the night that ended on the morning of [date]. */
    private suspend fun snapshotFor(date: LocalDate, end: Instant, zone: ZoneId, battery: BatterySnapshot?): DailySnapshot {
        val dayStart = date.atStartOfDay(zone).toInstant()
        // Without usage access we still send Health Connect data; usage fields go as null.
        val usage = usageStats.collect(dayStart.toEpochMilli(), end.toEpochMilli())
        val nightEnd = minOf(date.atTime(12, 0).atZone(zone).toInstant(), end)
        val sleep = healthConnect.collectSleep(
            from = date.minusDays(1).atTime(18, 0).atZone(zone).toInstant(),
            to = nightEnd,
        )
        val steps = healthConnect.collectSteps(from = dayStart, to = end)
        return DailySnapshot(
            date = date, usage = usage, sleep = sleep,
            battery = battery, steps = steps.takeIf { it > 0 },
        )
    }

    private fun Context.batteryLevel(): BatterySnapshot {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return BatterySnapshot(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
    }

    companion object {
        const val WORK_NAME = "daily_collect"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailyCollectWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }
    }
}
