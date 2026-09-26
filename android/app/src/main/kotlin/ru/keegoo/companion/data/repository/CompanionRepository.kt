package ru.keegoo.companion.data.repository

import android.util.Log
import ru.keegoo.companion.data.api.CompanionApi
import ru.keegoo.companion.data.api.model.AppUsageDto
import ru.keegoo.companion.data.api.model.CheckInRequest
import ru.keegoo.companion.data.api.model.EventRequest
import ru.keegoo.companion.data.api.model.MorningMessageResponse
import ru.keegoo.companion.data.api.model.PassiveDataRequest
import ru.keegoo.companion.data.api.model.ProfileRequest
import ru.keegoo.companion.data.api.model.DayReviewRequest
import ru.keegoo.companion.data.api.model.ExploreRequest
import ru.keegoo.companion.data.api.model.ExploreResponse
import ru.keegoo.companion.data.api.model.FeedbackRequest
import ru.keegoo.companion.data.api.model.VersionResponse
import ru.keegoo.companion.data.api.model.HistoryResponse
import ru.keegoo.companion.data.api.model.ReviewResponse
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
                screen_min     = snapshot.usage?.screenMinutes,
                unlocks        = snapshot.usage?.unlocks,
                first_unlock   = snapshot.usage?.firstUnlock?.format(TIME_FMT),
                last_unlock    = snapshot.usage?.lastUnlock?.format(TIME_FMT),
                top_apps       = snapshot.usage?.topApps.orEmpty().map { AppUsageDto(it.packageName, it.minutes) },
                sleep_min      = snapshot.sleep?.durationMinutes,
                bedtime        = snapshot.sleep?.bedtime?.format(TIME_FMT),
                wakeup         = snapshot.sleep?.wakeup?.format(TIME_FMT),
                steps          = snapshot.steps,
                battery_morning = snapshot.battery?.levelPercent,
                hourly_unlocks = snapshot.usage?.unlocksByHour?.takeIf { it.size == 24 },
                hourly_screen  = snapshot.usage?.screenMinutesByHour?.takeIf { it.size == 24 },
                hourly_steps   = snapshot.stepsByHour.takeIf { it.size == 24 },
            )
        )
        Log.d("CompanionRepo", "daily snapshot posted: ${snapshot.date}")
    }

    suspend fun postCheckIn(
        date: java.time.LocalDate,
        feel: DayFeel,
        tags: List<String> = emptyList(),
    ): Result<Unit> = runCatching {
        api.postCheckIn(
            CheckInRequest(
                date     = date.format(DATE_FMT),
                day_feel = feel.name.lowercase(),
                tags     = tags,
            )
        )
    }

    /** Profile answers → server (forecast context). The caller strips local-only answers (name). */
    suspend fun putProfile(answers: Map<String, String>): Result<Unit> = runCatching {
        api.putProfile(ProfileRequest(answers))
    }

    /** Answers the server kept (everything except the name) — restored after a reinstall. */
    suspend fun getProfile(): Result<Map<String, String>> = runCatching { api.getProfile().answers.orEmpty() }

    /** "The app is open" — counts the person as active today (DAU). Fire and forget. */
    suspend fun ping(): Result<Unit> = runCatching { api.ping() }

    suspend fun getHistory(): Result<HistoryResponse> = runCatching { api.getHistory() }

    suspend fun dayReview(date: java.time.LocalDate?): Result<ReviewResponse> = runCatching {
        api.postDayReview(DayReviewRequest(date?.format(DATE_FMT)))
    }

    suspend fun weekReview(): Result<ReviewResponse> = runCatching { api.postWeekReview() }

    /** «Хочу ещё»: today's answers + questions that can be asked now. */
    suspend fun exploreState(): Result<ExploreResponse> = runCatching { api.getExplore() }

    suspend fun exploreAsk(id: String): Result<ExploreResponse> = runCatching { api.postExplore(ExploreRequest(id)) }

    suspend fun latestVersion(): Result<VersionResponse> = runCatching { api.getVersion() }

    /** «Совпало / Не совсем»; a second call changes the answer. */
    suspend fun feedback(kind: String, date: String, hit: Boolean): Result<Unit> = runCatching {
        api.postFeedback(FeedbackRequest(kind, date, if (hit) "hit" else "miss"))
    }

    suspend fun getMorning(): Result<MorningMessageResponse> = runCatching {
        api.getMorning()
    }

    suspend fun postEvent(type: String): Result<Unit> = runCatching {
        api.postEvent(EventRequest(type))
    }
}
