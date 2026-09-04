package com.jay.fxi.data.remote

import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class MutationOneShotInterceptorTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .addInterceptor(MutationOneShotInterceptor())
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun post503RetryAfterZero_isNotReplayed_andPreservesFirstResponse() {
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Retry-After", "0")
                .setBody("first failure")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("must not be reached"))
        val payload = """{"device_token":"token","platform":"android"}"""
        val request = Request.Builder()
            .url(server.url("/api/register-device"))
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(503, response.code)
            assertEquals("0", response.header("Retry-After"))
            assertEquals("first failure", response.body?.string())
        }

        assertEquals(1, server.requestCount)
        val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals(payload, recorded.body.readUtf8())
    }

    @Test
    fun bodylessDelete503RetryAfterZero_isNotReplayed_andPreservesFirstResponse() {
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Retry-After", "0")
                .setBody("first failure")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("must not be reached"))
        val request = Request.Builder()
            .url(server.url("/api/register-device?device_token=opaque-token"))
            .method("DELETE", null)
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(503, response.code)
            assertEquals("0", response.header("Retry-After"))
            assertEquals("first failure", response.body?.string())
        }

        assertEquals(1, server.requestCount)
        val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("DELETE", recorded.method)
        assertEquals(0L, recorded.bodySize)
    }

    @Test
    fun put408RetryAfterZero_isNotReplayed_andPreservesBody() {
        server.enqueue(MockResponse().setResponseCode(408).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(200))
        val payload = """{"is_enabled":false}"""
        val request = Request.Builder()
            .url(server.url("/api/notification-settings/7"))
            .put(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().close()

        assertEquals(1, server.requestCount)
        assertEquals(payload, server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
    }

    @Test
    fun deleteRedirect_isNotFollowed_andQueryIsUnchanged() {
        server.enqueue(
            MockResponse()
                .setResponseCode(308)
                .setHeader("Location", server.url("/redirected"))
        )
        server.enqueue(MockResponse().setResponseCode(200))
        val request = Request.Builder()
            .url(server.url("/api/register-device?device_token=opaque-token"))
            .delete()
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(308, response.code)
            assertSame(request.body, response.request.body)
        }

        assertEquals(1, server.requestCount)
        val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("DELETE", recorded.method)
        assertEquals("/api/register-device?device_token=opaque-token", recorded.path)
        assertEquals(0L, recorded.bodySize)
    }

    @Test
    fun post307Redirect_isNotFollowed() {
        server.enqueue(
            MockResponse()
                .setResponseCode(307)
                .setHeader("Location", server.url("/redirected"))
        )
        server.enqueue(MockResponse().setResponseCode(200))
        val request = Request.Builder()
            .url(server.url("/api/notification-settings"))
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(307, response.code)
        }

        assertEquals(1, server.requestCount)
        assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
    }

    @Test
    fun mutation401_doesNotInvokeAuthenticatorOrReplay() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        server.enqueue(MockResponse().setResponseCode(200))
        val request = Request.Builder()
            .url(server.url("/api/user/me"))
            .delete()
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(401, response.code)
            assertEquals("unauthorized", response.body?.string())
        }

        assertEquals(1, server.requestCount)
    }
}
