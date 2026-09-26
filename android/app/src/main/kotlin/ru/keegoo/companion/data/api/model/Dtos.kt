package ru.keegoo.companion.data.api.model

import com.google.gson.annotations.SerializedName

/** Newest released APK — the app compares it with its own version (in-app update prompt). */
data class VersionResponse(
    val latest: String,
    @SerializedName("download_url") val downloadUrl: String,
)

data class PassiveDataRequest(
    val date: String,             // "2006-01-02"
    val screen_min: Int?,
    val unlocks: Int?,
    val first_unlock: String?,    // "HH:mm"
    val last_unlock: String?,
    val top_apps: List<AppUsageDto>,
    val sleep_min: Int?,
    val bedtime: String?,
    val wakeup: String?,
    val steps: Int?,
    val battery_morning: Int?,
    val fcm_token: String? = null,
    val hourly_unlocks: List<Int>? = null,  // 24 values, local hours
    val hourly_screen: List<Int>? = null,   // minutes per local hour
    val hourly_steps: List<Int>? = null,    // Health Connect steps per local hour
)

data class AppUsageDto(
    @SerializedName("package") val packageName: String,  // backend + top_apps JSONB use "package"
    val minutes: Int,
)

data class CheckInRequest(
    val date: String,             // "2006-01-02"
    val day_feel: String,         // "ok" | "meh" | "hard"
    val tags: List<String> = emptyList(),
    val note_text: String? = null,
)

data class MorningMessageResponse(
    val date: String,
    val message: String,
    val signals: List<SignalDto>? = null,
    /** "" | "hit" | "miss" — the person's «Совпало / Не совсем» for this forecast. */
    val feedback: String? = null,
)

/** One thing that stood out yesterday, computed on the server from the data (evidence for the forecast). */
data class SignalDto(val key: String, val title: String, val detail: String, val positive: Boolean = false)

/** Profile answers used as forecast context; the name is never included. */
data class ProfileRequest(val answers: Map<String, String>)

data class HistoryItem(val date: String, val message: String, val feedback: String? = null)

/** kind = "morning" | "day" | "week"; verdict = "hit" (Совпало) | "miss" (Не совсем). */
data class FeedbackRequest(val kind: String, val date: String, val verdict: String)
data class HistoryResponse(val items: List<HistoryItem>)

/** date = "yyyy-MM-dd"; the server defaults to yesterday when null. */
data class DayReviewRequest(val date: String? = null)

/** «Разбор дня» / «Итоги недели» — an on-demand LLM text. */
data class ReviewResponse(
    val kind: String,
    val date: String,
    val text: String,
    val signals: List<SignalDto>? = null,
    val feedback: String? = null,
)

data class EventRequest(val type: String)
