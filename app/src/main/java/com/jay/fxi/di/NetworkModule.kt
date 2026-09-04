package com.jay.fxi.di

import com.jay.fxi.BuildConfig
import com.jay.fxi.admission.ReleaseAdmissionInterceptor
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.ClientMetadataInterceptor
import com.jay.fxi.data.remote.FXiApiService
import com.jay.fxi.data.remote.MutationOneShotInterceptor
import com.jay.fxi.util.ApiConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    @WireJson
    fun provideWireJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = false
        isLenient = false
        explicitNulls = true
    }

    @Provides
    @Singleton
    @StorageJson
    fun provideStorageJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = true
    }

    @Provides
    @Singleton
    @PublicRest
    internal fun providePublicOkHttpClient(): OkHttpClient = commonRestClientBuilder()
        .build()

    @Provides
    @Singleton
    @ProtectedRest
    internal fun provideProtectedOkHttpClient(
        authSnapshotInterceptor: AuthSnapshotInterceptor
    ): OkHttpClient = commonRestClientBuilder(
        beforeLogging = { addInterceptor(authSnapshotInterceptor) },
        afterLogging = {
            // Must remain the final application interceptor, closest to retry/follow-up.
            addInterceptor(MutationOneShotInterceptor())
        }
    )
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .build()

    @Provides
    @Singleton
    @PublicRest
    fun providePublicRetrofit(
        @PublicRest okHttpClient: OkHttpClient,
        @WireJson json: Json
    ): Retrofit = retrofit(okHttpClient, json)

    @Provides
    @Singleton
    @ProtectedRest
    internal fun provideProtectedRetrofit(
        @ProtectedRest okHttpClient: OkHttpClient,
        @WireJson json: Json
    ): Retrofit = retrofit(okHttpClient, json)

    @Provides
    @Singleton
    fun provideFXiApiService(@PublicRest retrofit: Retrofit): FXiApiService {
        return retrofit.create(FXiApiService::class.java)
    }

    @Provides
    @Singleton
    internal fun provideAuthenticatedApiClient(
        @ProtectedRest retrofit: Retrofit,
        tokenProvider: AuthTokenProvider,
        @WireJson wireJson: Json
    ): AuthenticatedApiClient = AuthenticatedApiClient(
        service = retrofit.create(AuthenticatedApiService::class.java),
        transport = AuthenticatedTransport(tokenProvider),
        wireJson = wireJson
    )

    private fun commonRestClientBuilder(
        beforeLogging: OkHttpClient.Builder.() -> Unit = {},
        afterLogging: OkHttpClient.Builder.() -> Unit = {}
    ): OkHttpClient.Builder = OkHttpClient.Builder()
        // D24 must run before auth token lookup, metadata, DNS, or socket work.
        .addInterceptor(ReleaseAdmissionInterceptor())
        .addInterceptor(ClientMetadataInterceptor()) // ADR-039 관측 헤더 (X-Client-*)
        .apply(beforeLogging)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BODY
                        redactHeader("Authorization")
                    }
                )
            }
        }
        .apply(afterLogging)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)

    private fun retrofit(okHttpClient: OkHttpClient, json: Json): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }
}
