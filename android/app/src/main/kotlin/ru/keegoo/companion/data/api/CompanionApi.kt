package ru.keegoo.companion.data.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import ru.keegoo.companion.data.api.model.ProfileRequest
import ru.keegoo.companion.data.api.model.DayReviewRequest
import ru.keegoo.companion.data.api.model.FeedbackRequest
import ru.keegoo.companion.data.api.model.HistoryResponse
import ru.keegoo.companion.data.api.model.ReviewResponse
import ru.keegoo.companion.data.api.model.CheckInRequest
import ru.keegoo.companion.data.api.model.MorningMessageResponse
import ru.keegoo.companion.data.api.model.PassiveDataRequest

interface CompanionApi {
    @POST("api/v1/data/passive")
    suspend fun postPassiveData(@Body body: PassiveDataRequest)

    @POST("api/v1/checkin")
    suspend fun postCheckIn(@Body body: CheckInRequest)

    @PUT("api/v1/profile")
    suspend fun putProfile(@Body body: ProfileRequest)

    @POST("api/v1/ping")
    suspend fun ping()

    @GET("api/v1/history")
    suspend fun getHistory(): HistoryResponse

    @POST("api/v1/review/day")
    suspend fun postDayReview(@Body body: DayReviewRequest): ReviewResponse

    @POST("api/v1/review/week")
    suspend fun postWeekReview(): ReviewResponse

    @POST("api/v1/feedback")
    suspend fun postFeedback(@Body body: FeedbackRequest)

    @GET("api/v1/morning")
    suspend fun getMorning(): MorningMessageResponse
}
