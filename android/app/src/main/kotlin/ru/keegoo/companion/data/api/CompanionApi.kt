package ru.keegoo.companion.data.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import ru.keegoo.companion.data.api.model.CheckInRequest
import ru.keegoo.companion.data.api.model.MorningMessageResponse
import ru.keegoo.companion.data.api.model.PassiveDataRequest

interface CompanionApi {
    @POST("api/v1/data/passive")
    suspend fun postPassiveData(@Body body: PassiveDataRequest)

    @POST("api/v1/checkin")
    suspend fun postCheckIn(@Body body: CheckInRequest)

    @POST("api/v1/ping")
    suspend fun ping()

    @GET("api/v1/morning")
    suspend fun getMorning(): MorningMessageResponse
}
