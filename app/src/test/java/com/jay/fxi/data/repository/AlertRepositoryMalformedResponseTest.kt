package com.jay.fxi.data.repository

import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedBodyDecodingException
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.MutationOneShotInterceptor
import com.jay.fxi.di.NetworkModule
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Retrofit

class AlertRepositoryMalformedResponseTest {

    @Test
    fun malformedSuccess_keepsTransportEvidenceThroughRepositoryBoundary() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val rawBody = """{ "settings": [ }"""
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Retry-After", "13")
                    .setBody(rawBody)
            )
            val source = object : AuthTokenSource {
                override fun currentIdentity() = AuthIdentity("user-a", 1)

                override suspend fun fetchToken(
                    identity: AuthIdentity,
                    forceRefresh: Boolean
                ): String = "credential"
            }
            val provider = AuthTokenProvider(source, backgroundScope)
            val client = OkHttpClient.Builder()
                .addInterceptor(AuthSnapshotInterceptor(provider))
                .addInterceptor(MutationOneShotInterceptor())
                .build()
            val wireJson = NetworkModule.provideWireJson()
            val retrofit = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .client(client)
                .addConverterFactory(
                    wireJson.asConverterFactory("application/json".toMediaType())
                )
                .build()
            val api = AuthenticatedApiClient(
                retrofit.create(AuthenticatedApiService::class.java),
                AuthenticatedTransport(provider, admitted = { true }),
                wireJson
            )
            val repository = AlertRepository(api)
            val owner = checkNotNull(repository.captureOwnerOrNull())

            val result = repository.getSettings(owner)
            var observed: Throwable? = null
            result.fold(
                onSuccess = { fail("malformed success must not reach the success callback") },
                onFailure = { observed = it }
            )

            assertTrue(observed is AlertRepositoryException)
            val mapped = observed as AlertRepositoryException
            assertEquals(200, mapped.responseEvidence?.statusCode)
            assertEquals("13", mapped.responseEvidence?.retryAfter)
            assertEquals(rawBody, mapped.responseEvidence?.rawBodyText)
            assertTrue(mapped.cause is AuthenticatedBodyDecodingException)
        } finally {
            server.shutdown()
        }
    }
}
