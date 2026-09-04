package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.remote.dto.DeviceRequest
import com.jay.fxi.di.NetworkModule
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class AuthenticatedApiClientIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var source: FakeAuthTokenSource
    private lateinit var apiClient: AuthenticatedApiClient
    private lateinit var httpClient: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        source = FakeAuthTokenSource()
        val provider = AuthTokenProvider(source)
        httpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .addInterceptor(AuthSnapshotInterceptor(provider))
            .addInterceptor(MutationOneShotInterceptor())
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(httpClient)
            .addConverterFactory(
                NetworkModule.provideWireJson()
                    .asConverterFactory("application/json".toMediaType())
            )
            .build()
        val service = retrofit.create(AuthenticatedApiService::class.java)
        apiClient = AuthenticatedApiClient(
            service,
            AuthenticatedTransport(provider, admitted = { true }),
            NetworkModule.provideWireJson()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun pushRegistration_usesBoundCredential_withoutChangingPayload() = runTest {
        val successBody = """{ "success": true, "message": "registered", "device_id": 7 }"""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Retry-After", "3")
                .addHeader("Retry-After", "7")
                .setBody(successBody)
        )

        val response = apiClient.registerDevice(
            apiClient.captureSnapshot(),
            DeviceRequest(deviceToken = "opaque-device", platform = "android")
        )

        assertEquals(200, response.code())
        assertEquals(successBody, response.rawBodyText)
        assertEquals(listOf("3", "7"), response.headers.values("Retry-After"))
        val request = server.takeRequest()
        assertEquals("Bearer old-token", request.getHeader("Authorization"))
        assertEquals("/api/register-device", request.path)
        assertEquals(
            """{"device_token":"opaque-device","platform":"android"}""",
            request.body.readUtf8()
        )
    }

    @Test
    fun pushUnregister_usesBoundCredential_andKeepsLegacyQueryContract() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val response = apiClient.unregisterDevice(apiClient.captureSnapshot(), "opaque-device")

        assertEquals(200, response.code())
        val request = server.takeRequest()
        assertEquals("Bearer old-token", request.getHeader("Authorization"))
        assertEquals("/api/register-device?device_token=opaque-device", request.path)
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun protectedGet401_refreshesAndReplaysExactlyOnce() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"settings":[],"total_count":0}""")
        )

        val response = apiClient.getNotificationSettings(apiClient.captureSnapshot())
        val settings = response.requireBody("GET /api/notification-settings")

        assertEquals(0, settings.totalCount)
        assertEquals("""{"settings":[],"total_count":0}""", response.rawBodyText)
        assertEquals(2, server.requestCount)
        assertEquals("Bearer old-token", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh-token", server.takeRequest().getHeader("Authorization"))
        assertEquals(1, source.forceRefreshCount)
    }

    @Test
    fun protectedFailure_preservesStatusRetryAfterAndUnknownBody() = runTest {
        val rawBody = "<html>proxy miss</html>"
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Retry-After", "11")
                .setBody(rawBody)
        )

        try {
            apiClient.getNotificationSettings(apiClient.captureSnapshot())
                .requireBody("GET /api/notification-settings")
            fail("non-success response must throw with preserved evidence")
        } catch (error: AuthenticatedApiException) {
            assertEquals(404, error.failure.statusCode)
            assertEquals("11", error.failure.retryAfter)
            assertEquals(rawBody, error.failure.rawBodyText)
            assertEquals(AuthenticatedFailureKind.UNKNOWN_NOT_FOUND, error.failure.kind)
        }
    }

    @Test
    fun untaggedProtectedRequest_failsClosedBeforeNetwork() {
        val request = Request.Builder().url(server.url("/api/news")).build()

        val failure = runCatching { httpClient.newCall(request).execute().close() }.exceptionOrNull()

        assertTrue(failure is MissingAuthRequestTagException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun destructiveFlow_neverSubstitutesAnotherUsersCredential() = runTest {
        val owner = apiClient.captureSnapshot()
        source.identity = AuthIdentity("user-b", 2)

        val failure = runCatching { apiClient.deleteUser(owner) }.exceptionOrNull()

        assertTrue(failure is AuthIdentityChangedException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun destructiveFlow_rejectsSameUidFromAnotherAuthGeneration() = runTest {
        val owner = apiClient.captureSnapshot()
        source.identity = AuthIdentity("user-a", 2)

        val failure = runCatching { apiClient.deleteUser(owner) }.exceptionOrNull()

        assertTrue(failure is AuthIdentityChangedException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun intentFence_rejectsTokenCaptureAfterSameUidRelogin() = runTest {
        val intent = apiClient.captureIdentityFence()
        source.identity = AuthIdentity("user-a", 2)

        val failure = runCatching { apiClient.captureSnapshot(intent) }.exceptionOrNull()

        assertTrue(failure is AuthIdentityChangedException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun interceptorIdentityRace_returnsCancellationWithoutSendingRequest() = runTest {
        val provider = AuthTokenProvider(source)
        val racingClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .addInterceptor { chain ->
                source.identity = AuthIdentity("user-b", 2)
                chain.proceed(chain.request())
            }
            .addInterceptor(AuthSnapshotInterceptor(provider))
            .addInterceptor(MutationOneShotInterceptor())
            .build()
        val racingRetrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(racingClient)
            .addConverterFactory(
                NetworkModule.provideWireJson()
                    .asConverterFactory("application/json".toMediaType())
            )
            .build()
        val racingApi = AuthenticatedApiClient(
            racingRetrofit.create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { true }),
            NetworkModule.provideWireJson()
        )
        val owner = racingApi.captureSnapshot()

        val failure = runCatching { racingApi.deleteUser(owner) }.exceptionOrNull()

        assertTrue(failure is AuthIdentityChangedException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun malformedSuccessfulBody_retainsStatusHeadersAndExactBytes() = runTest {
        val rawBody = """{ "settings": [ }"""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Retry-After", "5")
                .addHeader("Retry-After", "9")
                .setBody(rawBody)
        )

        val failure = runCatching {
            apiClient.getNotificationSettings(apiClient.captureSnapshot())
        }.exceptionOrNull()

        assertTrue(failure is AuthenticatedBodyDecodingException)
        failure as AuthenticatedBodyDecodingException
        assertEquals(200, failure.response.statusCode)
        assertEquals(rawBody, failure.response.rawBodyText)
        assertEquals(listOf("5", "9"), failure.response.headers.values("Retry-After"))
    }

    @Test
    fun empty204Success_isPreservedForUnitEndpoint() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val response = apiClient.unregisterDevice(apiClient.captureSnapshot(), "opaque-device")

        assertTrue(response.isSuccessful)
        assertEquals(204, response.statusCode)
        assertEquals(0, response.rawBodyBytes().size)
        assertEquals(Unit, response.body)
    }

    private class FakeAuthTokenSource : AuthTokenSource {
        var identity = AuthIdentity("user-a", 1)
        private var token = "old-token"
        var forceRefreshCount = 0

        override fun currentIdentity(): AuthIdentity = identity

        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            if (forceRefresh) {
                forceRefreshCount += 1
                token = "fresh-token"
            }
            return token
        }
    }
}
