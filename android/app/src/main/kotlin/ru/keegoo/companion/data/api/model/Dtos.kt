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
    /** Follow-up chips the server will answer for this insight. */
    val followups: List<FollowupQuestionDto>? = null,
)

/** One thing that stood out yesterday, computed on the server from the data (evidence for the forecast). */
data class SignalDto(val key: String, val title: String, val detail: String, val positive: Boolean = false)

/** Profile answers used as forecast context; the name is never included. */
data class ProfileRequest(val answers: Map<String, String>)

data class HistoryItem(val date: String, val message: String, val feedback: String? = null)

/** «Хочу ещё»: a follow-up question the person's data can answer. */
data class ExploreQuestion(val id: String, val text: String)

data class ExploreAnswer(val id: String, val question: String, val text: String, val facts: List<String>? = null)

data class ExploreResponse(
    val answered: List<ExploreAnswer>? = null,
    val next: List<ExploreQuestion>? = null,
    val left: Int = 0,
    val answer: ExploreAnswer? = null,
)

data class ExploreRequest(val id: String)

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
    val followups: List<FollowupQuestionDto>? = null,
)

/**
 * One follow-up chip under an insight. The catalogue lives on the server so the two cannot
 * drift: the app only ever sends back an id it was given.
 */
data class FollowupQuestionDto(
    @SerializedName("id") val id: String,
    @SerializedName("label") val label: String,
)

data class FollowupRequest(
    @SerializedName("kind") val kind: String,          // "morning" | "day" | "week"
    @SerializedName("date") val date: String?,
    @SerializedName("question_id") val questionId: String,
)

data class FollowupResponse(
    @SerializedName("kind") val kind: String,
    @SerializedName("date") val date: String,
    @SerializedName("question_id") val questionId: String,
    @SerializedName("text") val text: String,
)

data class EventRequest(val type: String)

/** Sent with every ping: the server cannot otherwise tell which build a phone is on. */
data class PingRequest(@SerializedName("app_version") val appVersion: String)

/**
 * One line about a single slice of the person's own data: how today is going, one tile, a past
 * forecast, a tag from the check-in, or a question generated for them.
 *
 * One request shape for all five on purpose — they differ only in which facts the server puts
 * in, and five endpoints would have meant five places for the cache and the budget to drift.
 */
data class InsightRequest(
    @SerializedName("kind") val kind: String,  // midday | stat | retro | tag | question
    @SerializedName("arg") val arg: String,    // tile name, date, tag, or "1".."3" for question slot
)

data class InsightResponse(
    @SerializedName("kind") val kind: String,
    @SerializedName("arg") val arg: String,
    @SerializedName("text") val text: String,
    /** Answer options for kind=question, when the model produced a real choice. */
    @SerializedName("options") val options: List<String>? = null,
)
