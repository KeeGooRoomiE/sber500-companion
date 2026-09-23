package ru.keegoo.companion.data.collector

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.keegoo.companion.domain.model.SleepSnapshot
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
