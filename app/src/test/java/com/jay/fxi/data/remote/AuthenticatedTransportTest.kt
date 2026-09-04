package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response

class AuthenticatedTransportTest {

    @Test
    fun admissionRunsBeforeTokenLookup() = runTest {
        val source = FakeAuthTokenSource()
        val transport = AuthenticatedTransport(
            AuthTokenProvider(source, backgroundScope),
            admitted = { false }
        )

        try {
            transport.executeMutation<Unit>(AuthSnapshot("user-a", 1, "unused")) {
                error("request must not be created")
            }
            fail("closed admission must fail")
        } catch (error: IOException) {
            assertEquals("D24 release admission is OFF", error.message)
        }
        assertEquals(0, source.fetchCount)
    }

    @Test
    fun mutation401_isReturnedWithoutRefreshOrReplay() = runTest {
        val source = FakeAuthTokenSource()
        val transport = AuthenticatedTransport(
            AuthTokenProvider(source, backgroundScope),
            admitted = { true }
        )
        var calls = 0
        val expected = errorResponse<String>(401, "denied", mapOf("Retry-After" to "7"))
        val snapshot = transport.captureSnapshot()

        val actual = transport.executeMutation(snapshot) {
            calls += 1
            expected
        }

        assertSame(expected, actual)
        assertEquals("denied", actual.errorBody()!!.string())
        assertEquals("7", actual.headers()["Retry-After"])
        assertEquals(1, calls)
        assertEquals(0, source.forceRefreshCount)
    }

    @Test
    fun read401_refreshesOnce_andReplaysWithBoundFreshSnapshot() = runTest {
        val source = FakeAuthTokenSource()
        val provider = AuthTokenProvider(source, backgroundScope)
        val transport = AuthenticatedTransport(provider, admitted = { true })
        val seen = mutableListOf<String>()
        val snapshot = provider.currentSnapshot()

        val response = transport.executeRead(snapshot) { tag ->
            seen += tag.snapshot.token
            if (seen.size == 1) {
                errorResponse(401, "expired")
            } else {
                Response.success("ok")
            }
        }

        assertEquals("ok", response.body())
        assertEquals(listOf("old-token", "fresh-token"), seen)
        assertEquals(1, source.forceRefreshCount)
    }

    @Test
    fun replay401_marksFreshSnapshotRejected_andNeverStartsSecondRefresh() = runTest {
        val source = FakeAuthTokenSource()
        val provider = AuthTokenProvider(source, backgroundScope)
        val transport = AuthenticatedTransport(provider, admitted = { true })
        val snapshot = provider.currentSnapshot()

        val first = transport.executeRead<String>(snapshot) { errorResponse(401, "denied") }
        assertEquals(401, first.code())
        assertEquals(1, source.forceRefreshCount)

        val secondSnapshot = provider.currentSnapshot()
        val second = transport.executeRead<String>(secondSnapshot) {
            errorResponse(401, "denied-again")
        }
        assertEquals(401, second.code())
        assertEquals(1, source.forceRefreshCount)
    }

    @Test
    fun lateOld401_doesNotReplayCredentialAlreadyRejectedByConcurrentRequest() = runTest {
        val source = FakeAuthTokenSource()
        val provider = AuthTokenProvider(source, backgroundScope)
        val transport = AuthenticatedTransport(provider, admitted = { true })
        val firstFinished = CompletableDeferred<Unit>()
        var firstCalls = 0
        var lateCalls = 0
        val snapshot = provider.currentSnapshot()

        val first = async {
            transport.executeRead<String>(snapshot) {
                firstCalls += 1
                errorResponse(401, "denied")
            }.also { firstFinished.complete(Unit) }
        }
        val late = async {
            transport.executeRead<String>(snapshot) {
                lateCalls += 1
                if (lateCalls == 1) firstFinished.await()
                errorResponse(401, "late-denied")
            }
        }

        assertEquals(401, first.await().code())
        assertEquals(401, late.await().code())
        assertEquals(2, firstCalls)
        assertEquals(1, lateCalls)
        assertEquals(1, source.forceRefreshCount)
    }

    @Test
    fun identityChangeWhileRequestIsInFlight_discardsResponse() = runTest {
        val source = FakeAuthTokenSource()
        val transport = AuthenticatedTransport(
            AuthTokenProvider(source, backgroundScope),
            admitted = { true }
        )
        val snapshot = transport.captureSnapshot()

        try {
            transport.executeMutation(snapshot) {
                assertEquals("user-a", it.snapshot.uid)
                source.identity = AuthIdentity("user-b", 2)
                Response.success(Unit)
            }
            fail("response from a stale auth generation must be discarded")
        } catch (_: AuthIdentityChangedException) {
            // Expected.
        }
    }

    @Test
    fun authInterceptorUsesCapturedSnapshot_andRedactsItsTag() {
        val source = FakeAuthTokenSource()
        val provider = AuthTokenProvider(source)
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(AuthSnapshotInterceptor(provider))
                .build()
            val snapshot = AuthSnapshot("user-a", 1, "secret-bearer")
            val tag = AuthRequestTag(snapshot)
            val request = Request.Builder()
                .url(server.url("/protected"))
                .tag(AuthRequestTag::class.java, tag)
                .build()

            client.newCall(request).execute().close()

            val recorded = server.takeRequest()
            assertEquals("Bearer secret-bearer", recorded.getHeader("Authorization"))
            assertFalse(tag.toString().contains("user-a"))
            assertFalse(tag.toString().contains("secret-bearer"))
            assertTrue(tag.toString().contains("authGeneration=1"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun authInterceptorRejectsMissingSnapshot_beforeNetwork() {
        val provider = AuthTokenProvider(FakeAuthTokenSource())
        val server = MockWebServer()
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(AuthSnapshotInterceptor(provider))
                .build()
            val request = Request.Builder().url(server.url("/protected")).build()

            val failure = runCatching { client.newCall(request).execute().close() }.exceptionOrNull()

            assertTrue(failure is MissingAuthRequestTagException)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    private fun <T> errorResponse(
        code: Int,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): Response<T> = Response.error(
        body.toResponseBody(),
        headers.toHeaders().let { responseHeaders ->
            okhttp3.Response.Builder()
                .request(Request.Builder().url("https://example.invalid/test").build())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .headers(responseHeaders)
                .body(body.toResponseBody())
                .build()
        }
    )

    private class FakeAuthTokenSource : AuthTokenSource {
        var identity: AuthIdentity? = AuthIdentity("user-a", 1)
        var currentToken = "old-token"
        var fetchCount = 0
        var forceRefreshCount = 0

        override fun currentIdentity(): AuthIdentity? = identity

        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            fetchCount += 1
            if (forceRefresh) {
                forceRefreshCount += 1
                currentToken = "fresh-token"
            }
            return currentToken
        }
    }
}
