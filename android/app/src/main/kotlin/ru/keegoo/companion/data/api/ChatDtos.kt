package ru.keegoo.companion.data.api

import com.google.gson.annotations.SerializedName

data class ChatRequest(
    val id: String,
    val text: String,
    val source: String,
    val history: List<ChatTurn>,
    @SerializedName("daily_question_id") val dailyQuestionId: String? = null,
    val date: String? = null,
)

data class ChatTurn(val role: String, val text: String)

data class ChatResponse(
    val text: String,
    val pills: List<String>,
    val kind: String,
)
