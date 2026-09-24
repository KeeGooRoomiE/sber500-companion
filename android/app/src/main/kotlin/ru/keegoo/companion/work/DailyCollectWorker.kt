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

    override suspend fun doWork(): Result {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant()
        val dayEnd = Instant.now()

        // Without usage access we still send Health Connect data; usage fields go as null.
        val usage = usageStats.collect(dayStart.toEpochMilli(), dayEnd.toEpochMilli())

        val sleep = healthConnect.collectSleep(
            from = today.minusDays(1).atStartOfDay(zone).toInstant(),
            to = dayEnd,
        )

        val steps = healthConnect.collectSteps(from = dayStart, to = dayEnd)

        val battery = applicationContext.batteryLevel()

        val snapshot = DailySnapshot(
            date = today, usage = usage, sleep = sleep,
            battery = battery, steps = steps.takeIf { it > 0 },
        )

        return when (repository.postDailySnapshot(snapshot).isSuccess) {
            true  -> Result.success()
            false -> Result.retry()
        }
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
