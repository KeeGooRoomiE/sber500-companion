package ru.keegoo.companion.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ru.keegoo.companion.BuildConfig
import ru.keegoo.companion.data.api.CompanionApi
import ru.keegoo.companion.data.auth.AuthInterceptor
import ru.keegoo.companion.data.auth.DeviceCredentials
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    @Provides
    @Singleton
    fun provideOkHttp(credentials: DeviceCredentials): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(credentials))
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                            else HttpLoggingInterceptor.Level.NONE
                    redactHeader("Authorization")
                }
            )
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides
    @Singleton
    fun provideCompanionApi(retrofit: Retrofit): CompanionApi =
        retrofit.create(CompanionApi::class.java)
}
