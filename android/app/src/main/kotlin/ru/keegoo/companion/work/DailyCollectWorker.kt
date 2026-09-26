package ru.keegoo.companion.work

import android.content.Context
import android.os.BatteryManager
import androidx.hilt.work.HiltWorker
import androidx.work.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import ru.keegoo.companion.data.prefs.setBackfilled
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

    // Every run sends yesterday in full (the evening after the last run is otherwise lost, and the
    // server builds the morning forecast from it) and today so far. A backfill run (KEY_BACKFILL_DAYS)
    // sends the last N days instead of one — right after onboarding, so the day-0 forecast has history.
    override suspend fun doWork(): Result {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val now = Instant.now()
        val pastDays = inputData.getInt(KEY_BACKFILL_DAYS, 1).coerceIn(1, 7)

        val snapshots = (pastDays downTo 1).map { back ->
            val date = today.minusDays(back.toLong())
            snapshotFor(date, end = date.plusDays(1).atStartOfDay(zone).toInstant(), zone, battery = null)
        } + snapshotFor(today, end = now, zone, battery = applicationContext.batteryLevel())

        // A day with nothing collected (no usage access, no Health Connect) is not sent at all:
        // an all-empty row would only look like "no activity" to the forecast.
        val toSend = snapshots.filter { it.usage != null || it.sleep != null || it.steps != null }
        val ok = toSend.all { repository.postDailySnapshot(it).isSuccess }

        if (ok && pastDays > 1 && toSend.any { it.usage != null }) applicationContext.setBackfilled()
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
        private const val NOW_WORK_NAME = "daily_collect_now"
        const val KEY_BACKFILL_DAYS = "backfill_days"

        /**
         * Collect and send right now (with [pastDays] of history) and wait for it, at most [timeoutMs].
         * Returns true if the upload finished successfully in time.
         */
        suspend fun runNowAndWait(context: Context, pastDays: Int = 7, timeoutMs: Long = 10_000): Boolean {
            val request = OneTimeWorkRequestBuilder<DailyCollectWorker>()
                .setInputData(workDataOf(KEY_BACKFILL_DAYS to pastDays))
                .build()
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            val info = withTimeoutOrNull(timeoutMs) {
                wm.getWorkInfoByIdFlow(request.id).first { it?.state?.isFinished == true }
            }
            return info?.state == WorkInfo.State.SUCCEEDED
        }

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
