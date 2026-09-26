package ru.keegoo.companion.data.api.model

import com.google.gson.annotations.SerializedName

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
)

/** Profile answers used as forecast context; the name is never included. */
data class ProfileRequest(val answers: Map<String, String>)
