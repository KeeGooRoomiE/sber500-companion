package ru.keegoo.companion.data.collector

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.keegoo.companion.domain.model.SleepSnapshot
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthConnectCollector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun collectSleep(from: Instant, to: Instant): SleepSnapshot? {
        if (!isAvailable()) return null
        return try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                )
            )
            val session = response.records
                .maxByOrNull { it.endTime.epochSecond - it.startTime.epochSecond }
                ?: return null

            val durationMin = ((session.endTime.epochSecond - session.startTime.epochSecond) / 60).toInt()
            val zone = ZoneId.systemDefault()
            SleepSnapshot(
                durationMinutes = durationMin,
                bedtime = session.startTime.atZone(zone).toLocalTime(),
                wakeup = session.endTime.atZone(zone).toLocalTime(),
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Steps per local hour of one day (24 values) and their total. Health Connect's aggregation
     * de-duplicates phone + watch counting the same walk, unlike summing raw records.
     * Null without Health Connect, permission or any steps.
     */
    suspend fun collectStepsByHour(from: Instant, to: Instant, zone: ZoneId): StepsDay? {
        if (!isAvailable()) return null
        return try {
            val groups = client.aggregateGroupByDuration(
                AggregateGroupByDurationRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    timeRangeSlicer = Duration.ofHours(1),
                )
            )
            val hours = IntArray(24)
            groups.forEach { g ->
                val n = g.result[StepsRecord.COUNT_TOTAL] ?: return@forEach
                hours[g.startTime.atZone(zone).hour] += n.toInt()
            }
            val total = hours.sum()
            if (total > 0) StepsDay(total, hours.toList()) else null
        } catch (_: Exception) {
            // Older Health Connect or no permission: fall back to the plain daily sum
            collectSteps(from, to).takeIf { it > 0 }?.let { StepsDay(it, emptyList()) }
        }
    }

    suspend fun collectSteps(from: Instant, to: Instant): Int {
        if (!isAvailable()) return 0
        return try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                )
            )
            response.records.sumOf { it.count }.toInt()
        } catch (_: Exception) {
            0
        }
    }
}

/** One day of steps: [total] and, when Health Connect can aggregate, 24 hourly values. */
data class StepsDay(val total: Int, val byHour: List<Int>)
