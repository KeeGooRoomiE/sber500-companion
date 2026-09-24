package ru.keegoo.companion.data.repository

import android.util.Log
import ru.keegoo.companion.data.api.CompanionApi
import ru.keegoo.companion.data.api.model.AppUsageDto
import ru.keegoo.companion.data.api.model.CheckInRequest
import ru.keegoo.companion.data.api.model.MorningMessageResponse
import ru.keegoo.companion.data.api.model.PassiveDataRequest
import ru.keegoo.companion.domain.model.DailySnapshot
import ru.keegoo.companion.domain.model.DayFeel
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

@Singleton
class CompanionRepository @Inject constructor(
    private val api: CompanionApi,
) {
    suspend fun postDailySnapshot(snapshot: DailySnapshot): Result<Unit> = runCatching {
        api.postPassiveData(
            PassiveDataRequest(
                date           = snapshot.date.format(DATE_FMT),
                screen_min     = snapshot.usage.screenMinutes,
                unlocks        = snapshot.usage.unlocks,
                first_unlock   = snapshot.usage.firstUnlock?.format(TIME_FMT),
                last_unlock    = snapshot.usage.lastUnlock?.format(TIME_FMT),
                top_apps       = snapshot.usage.topApps.map { AppUsageDto(it.packageName, it.minutes) },
                sleep_min      = snapshot.sleep?.durationMinutes,
                bedtime        = snapshot.sleep?.bedtime?.format(TIME_FMT),
                wakeup         = snapshot.sleep?.wakeup?.format(TIME_FMT),
                steps          = null,
                battery_morning = snapshot.battery.levelPercent,
            )
        )
        Log.d("CompanionRepo", "daily snapshot posted: ${snapshot.date}")
    }

    suspend fun postCheckIn(date: java.time.LocalDate, feel: DayFeel): Result<Unit> = runCatching {
        api.postCheckIn(
            CheckInRequest(
                date     = date.format(DATE_FMT),
                day_feel = feel.name.lowercase(),
            )
        )
    }

    suspend fun getMorning(): Result<MorningMessageResponse> = runCatching {
        api.getMorning()
    }
}
