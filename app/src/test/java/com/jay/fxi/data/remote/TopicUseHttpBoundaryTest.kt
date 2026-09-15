package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.auth.HttpExchangeEvidence
import com.jay.fxi.di.NetworkModule
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit

/**
 * L-4e E2a: a topic snapshot read is refused a send once the access it serves no longer admits it, and a refusal keeps
 * every response the read had already seen (`l4e_e2a_design_v4.md` §2.4).
 *
 * The socket tests run on the real clock, as `TopicSnapshotBootstrapServiceTest` does, over a client built the way the
 * protected one is. A misdirected 421 is repeated by OkHttp only on a coalesced HTTP/2 connection, which this fixture does
 * not set up; that chain is exercised on the interceptor directly instead, and says so.
 */
class TopicUseHttpBoundaryTest {

    private lateinit var server: MockWebServer
    private lateinit var source: FakeAuthTokenSource
    private lateinit var provider: AuthTokenProvider

    /** Runs on each exchange before the use check; where a test moves the identity at a chosen send. */
    private var beforeUseCheck: (TopicUseTag) -> Unit = {}

    /** Runs before the application interceptor's identity check, after the transport's own. */
    private var beforeAuthCheck: (AuthRequestTag) -> Unit = {}

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        source = FakeAuthTokenSource()
        provider = AuthTokenProvider(source, orders = AccessOrderSequence())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun api(): AuthenticatedApiClient {
        val http = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .addInterceptor { chain ->
                chain.request().tag(AuthRequestTag::class.java)?.let(beforeAuthCheck)
                chain.proceed(chain.request())
            }
            .addInterceptor(AuthSnapshotInterceptor(provider))
            .addInterceptor(MutationOneShotInterceptor())
            .addNetworkInterceptor { chain ->
                chain.request().tag(TopicUseTag::class.java)?.let(beforeUseCheck)
                chain.proceed(chain.request())
            }
            .addNetworkInterceptor(TopicUseNetworkInterceptor(provider))
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(http)
            .addConverterFactory(NetworkModule.provideWireJson().asConverterFactory("application/json".toMediaType()))
            .build()
        return AuthenticatedApiClient(
            retrofit.create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { true }),
            NetworkModule.provideWireJson()
        )
    }

    /** Answers the use check from a list, one entry per call, and counts the calls. */
    private class UseAnswers(vararg answers: Boolean) {
        private val remaining = ArrayDeque(answers.toList())
        var calls = 0
            private set

        fun next(): Boolean {
            calls += 1
            return remaining.removeFirstOrNull() ?: error("the use check was asked more often than the test expected")
        }
    }

    private suspend fun AuthenticatedApiClient.read(use: UseAnswers) =
        getTopicSnapshot(captureSnapshot(captureIdentityFence()), TETHER, use::next)

    // --- the sends --------------------------------------------------------------------------------------------------------

    @Test
    fun `a first send is not written once the use is withheld`() = runBlocking {
        server.enqueue(ok())
        val use = UseAnswers(false)

        val refused = runCatching { api().read(use) }.exceptionOrNull()

        assertTrue("the send was not refused: $refused", refused is TopicUseWithheldException)
        assertEquals(emptyList<HttpExchangeEvidence>(), (refused as TopicUseWithheldException).exchanges)
        assertEquals("a withheld send reached the server", 0, server.requestCount)
    }

    @Test
    fun `a replay the refresh made possible is not sent once the use is withheld, and carries the 401`() = runBlocking {
        server.enqueue(unauthorized(retryAfter = "5"))
        server.enqueue(ok())
        // The first send, then the check before the replay.
        val use = UseAnswers(true, false)

        val refused = runCatching { api().read(use) }.exceptionOrNull()

        assertTrue("the replay was not refused: $refused", refused is TopicUseWithheldException)
        assertEquals(listOf(HttpExchangeEvidence(1, 1, 401, "5")), (refused as TopicUseWithheldException).exchanges)
        assertEquals("the withheld replay reached the server", 1, server.requestCount)
        assertEquals(1, source.forceRefreshCount)
        assertEquals(2, use.calls)
    }

    @Test
    fun `a replay that passed the check is still refused at the wire, and carries the 401`() = runBlocking {
        server.enqueue(unauthorized(retryAfter = "5"))
        server.enqueue(ok())
        // The first send, the check before the replay, then the replay's own send.
        val use = UseAnswers(true, true, false)

        val refused = runCatching { api().read(use) }.exceptionOrNull()

        assertTrue("the replay was not refused at the wire: $refused", refused is TopicUseWithheldException)
        assertEquals(listOf(HttpExchangeEvidence(1, 1, 401, "5")), (refused as TopicUseWithheldException).exchanges)
        assertEquals("the withheld replay reached the server", 1, server.requestCount)
        assertEquals(3, use.calls)
    }

    @Test
    fun `an identity that moved with the use withheld at the replay refuses as an identity change, and carries the 401`() =
        runBlocking {
            server.enqueue(unauthorized(retryAfter = "5"))
            server.enqueue(ok())
            val use = UseAnswers(true, true, false)
            beforeUseCheck = { tag -> if (tag.send == 2) source.identity = AuthIdentity("user-b", 1) }

            val refused = runCatching { api().read(use) }.exceptionOrNull()

            assertTrue("the identity change did not come first: $refused", refused is AuthIdentityChangedException)
            assertEquals(listOf(HttpExchangeEvidence(1, 1, 401, "5")), (refused as AuthIdentityChangedException).exchanges)
            assertEquals("the replay reached the server", 1, server.requestCount)
            assertEquals("the use was asked about after the identity refused", 2, use.calls)
        }

    @Test
    fun `an identity that moved after the transport's check refuses the replay at the application interceptor, and carries the 401`() =
        runBlocking {
            server.enqueue(unauthorized(retryAfter = "5"))
            server.enqueue(ok())
            // The first send, then the check before the replay; the replay never reaches the network stage.
            val use = UseAnswers(true, true)
            beforeAuthCheck = { tag -> if (tag.use?.send == 2) source.identity = AuthIdentity("user-b", 1) }

            val refused = runCatching { api().read(use) }.exceptionOrNull()

            assertTrue("the replay was not refused as an identity change: $refused", refused is AuthIdentityChangedException)
            assertEquals(listOf(HttpExchangeEvidence(1, 1, 401, "5")), (refused as AuthIdentityChangedException).exchanges)
            assertEquals("the replay reached the server", 1, server.requestCount)
            assertEquals(2, use.calls)
        }

    @Test
    fun `a 503 that asks for an immediate repeat does not get it once the use is withheld`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(ok())
        val use = UseAnswers(true, false)

        val refused = runCatching { api().read(use) }.exceptionOrNull()

        assertTrue("the repeat was not refused: $refused", refused is TopicUseWithheldException)
        assertEquals(listOf(HttpExchangeEvidence(1, 1, 503, "0")), (refused as TopicUseWithheldException).exchanges)
        assertEquals("OkHttp's repeat reached the server", 1, server.requestCount)
    }

    @Test
    fun `a refused repeat of the replay carries the 401 and the replay's own response, told apart by send`() = runBlocking {
        server.enqueue(unauthorized(retryAfter = "5"))
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(ok())
        // The first send, the check before the replay, the replay's send, then OkHttp's repeat of the replay.
        val use = UseAnswers(true, true, true, false)

        val refused = runCatching { api().read(use) }.exceptionOrNull()

        assertTrue("the repeat was not refused: $refused", refused is TopicUseWithheldException)
        assertEquals(
            listOf(HttpExchangeEvidence(1, 1, 401, "5"), HttpExchangeEvidence(2, 1, 503, "0")),
            (refused as TopicUseWithheldException).exchanges
        )
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a repeat that is still admitted goes out, and its answer is returned as before`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(ok())
        val use = UseAnswers(true, true)

        val response = api().read(use)

        assertEquals(200, response.statusCode)
        assertEquals(2, server.requestCount)
        assertEquals("each exchange was checked", 2, use.calls)
    }

    @Test
    fun `a read without a use check is not touched by the network interceptor`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(ok())
        val api = api()

        val response = api.getTopicSnapshot(api.captureSnapshot(api.captureIdentityFence()), TETHER)

        assertEquals(200, response.statusCode)
        assertEquals(2, server.requestCount)
        assertEquals("the request did not keep its credential", "Bearer old-token", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    // --- the interceptor on its own -------------------------------------------------------------------------------------

    /**
     * 421 → 503 → a refused repeat. OkHttp's 503 branch stops only a 503 that follows a 503, so both follow-ups can happen in
     * one call; each response is kept, in order, and neither replaces the other.
     */
    @Test
    fun `a misdirected 421 then a 503 then a refused repeat hand over both responses, in order`() {
        val interceptor = TopicUseNetworkInterceptor(provider)
        val use = UseAnswers(true, true, false)
        val context = TopicUseContext(use::next)
        val snapshot = AuthSnapshot("user-a", 1, "old-token")
        val request = Request.Builder().url("https://example.invalid/api/v2/topics/snapshot")
            .tag(TopicUseTag::class.java, TopicUseTag(snapshot, 1, context)).build()

        interceptor.intercept(FixedChain(request, status = 421, retryAfter = "120")).close()
        interceptor.intercept(FixedChain(request, status = 503, retryAfter = "0")).close()
        val refused = runCatching { interceptor.intercept(FixedChain(request, status = 200, retryAfter = null)) }.exceptionOrNull()

        assertTrue("the third exchange was not refused: $refused", refused is WithheldUseIOException)
        assertEquals(
            listOf(HttpExchangeEvidence(1, 1, 421, "120"), HttpExchangeEvidence(1, 2, 503, "0")),
            (refused as WithheldUseIOException).exchanges
        )
    }

    // --- what the transport owns -----------------------------------------------------------------------------------------

    @Test
    fun `a replay refused before it is sent closes the 401 it holds`() = runTest {
        val tokens = FakeAuthTokenSource()
        val transport = AuthenticatedTransport(AuthTokenProvider(tokens, backgroundScope, AccessOrderSequence()), admitted = { true })
        val body = TrackedBody("expired")
        var calls = 0

        val refused = runCatching {
            transport.executeRead(transport.captureSnapshot(), useAdmitted = { false }) {
                calls += 1
                errorResponse<String>(401, body, mapOf("Retry-After" to "5"))
            }
        }.exceptionOrNull()

        assertTrue("the replay was not refused: $refused", refused is TopicUseWithheldException)
        assertEquals(
            "without the network interceptor the 401 is still carried",
            listOf(HttpExchangeEvidence(1, 1, 401, "5")),
            (refused as TopicUseWithheldException).exchanges
        )
        assertTrue("the 401's body was left open", body.closed)
        assertEquals(1, calls)
    }

    @Test
    fun `a replayed 401 whose rejection cannot be recorded is closed before the failure goes on`() = runTest {
        val tokens = FakeAuthTokenSource()
        val transport = AuthenticatedTransport(AuthTokenProvider(tokens, backgroundScope, AccessOrderSequence()), admitted = { true })
        val replayBody = TrackedBody("still expired")
        var calls = 0

        val failure = runCatching {
            transport.executeRead(transport.captureSnapshot(), useAdmitted = { true }) {
                calls += 1
                if (calls == 1) {
                    errorResponse(401, TrackedBody("expired"))
                } else {
                    // The transport reads the identity once more after the answer, and the rejection record reads it again.
                    tokens.moveIdentityOnRead = 2
                    errorResponse<String>(401, replayBody)
                }
            }
        }.exceptionOrNull()

        assertTrue("the rejection record did not fail: $failure", failure is AuthIdentityChangedException)
        assertTrue("the replay's body was left open", replayBody.closed)
        assertEquals(2, calls)
    }

    @Test
    fun `an identity that moved just before the replay refuses it as an identity change, and carries the 401`() = runTest {
        val tokens = FakeAuthTokenSource()
        val transport = AuthenticatedTransport(AuthTokenProvider(tokens, backgroundScope, AccessOrderSequence()), admitted = { true })
        var calls = 0

        val refused = runCatching {
            transport.executeRead(
                transport.captureSnapshot(),
                // Asked once, before the replay: the use still stands, and the account moves at that moment.
                useAdmitted = { tokens.identity = AuthIdentity("user-b", 1); true }
            ) {
                calls += 1
                errorResponse<String>(401, TrackedBody("expired"), mapOf("Retry-After" to "5"))
            }
        }.exceptionOrNull()

        assertTrue("the replay was not refused as an identity change: $refused", refused is AuthIdentityChangedException)
        assertEquals(listOf(HttpExchangeEvidence(1, 1, 401, "5")), (refused as AuthIdentityChangedException).exchanges)
        assertEquals("the replay was sent", 1, calls)
    }

    @Test
    fun `an answer refused because the identity moved while it was out keeps the responses before it`() = runTest {
        val tokens = FakeAuthTokenSource()
        val transport = AuthenticatedTransport(AuthTokenProvider(tokens, backgroundScope, AccessOrderSequence()), admitted = { true })
        var calls = 0

        val refused = runCatching {
            transport.executeRead(transport.captureSnapshot(), useAdmitted = { true }) {
                calls += 1
                if (calls == 1) {
                    errorResponse(401, TrackedBody("expired"), mapOf("Retry-After" to "5"))
                } else {
                    tokens.identity = AuthIdentity("user-b", 1)
                    errorResponse<String>(429, TrackedBody("limited"), mapOf("Retry-After" to "120"))
                }
            }
        }.exceptionOrNull()

        assertTrue("the answer was not refused: $refused", refused is AuthIdentityChangedException)
        refused as AuthIdentityChangedException
        assertEquals(429, refused.statusCode)
        assertEquals("120", refused.retryAfter)
        assertEquals(
            listOf(HttpExchangeEvidence(1, 1, 401, "5"), HttpExchangeEvidence(2, 1, 429, "120")),
            refused.exchanges
        )
    }

    // --- fixtures ---------------------------------------------------------------------------------------------------------

    private fun ok() = MockResponse().setResponseCode(200).setBody(TETHER_SNAPSHOT)

    private fun unauthorized(retryAfter: String) =
        MockResponse().setResponseCode(401).setHeader("Retry-After", retryAfter).setBody("""{"detail":"expired"}""")

    private class TrackedBody(text: String) : ResponseBody() {
        private val delegate = text.toResponseBody()
        var closed = false
            private set

        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun source(): BufferedSource = delegate.source()
        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private fun <T> errorResponse(code: Int, body: ResponseBody, headers: Map<String, String> = emptyMap()): Response<T> =
        Response.error(
            body,
            okhttp3.Response.Builder()
                .request(Request.Builder().url("https://example.invalid/test").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .headers(headers.toHeaders())
                .body("".toResponseBody())
                .build()
        )

    /** A chain whose one exchange answers [status]; nothing else about a chain is used by the interceptor. */
    private class FixedChain(private val request: Request, private val status: Int, private val retryAfter: String?) :
        Interceptor.Chain {
        override fun request(): Request = request
        override fun proceed(request: Request): okhttp3.Response = okhttp3.Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_2)
            .code(status)
            .message("test")
            .apply { retryAfter?.let { header("Retry-After", it) } }
            .body("".toResponseBody())
            .build()

        override fun connection(): Connection? = null
        override fun call(): Call = error("not used")
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    private class FakeAuthTokenSource : AuthTokenSource {
        var identity = AuthIdentity("user-a", 1)
        private var token = "old-token"
        var forceRefreshCount = 0

        /** When set, the identity moves to another account on that many reads from now. */
        var moveIdentityOnRead: Int? = null

        override fun currentIdentity(): AuthIdentity {
            moveIdentityOnRead?.let { reads ->
                if (reads <= 1) {
                    identity = AuthIdentity("user-b", 1)
                    moveIdentityOnRead = null
                } else {
                    moveIdentityOnRead = reads - 1
                }
            }
            return identity
        }

        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            if (forceRefresh) {
                forceRefreshCount += 1
                token = "fresh-token"
            }
            return token
        }
    }

    private companion object {
        const val TETHER = "usdt:krw"
        const val TETHER_SNAPSHOT = """{"type":"snapshot","topic":"usdt:krw","data":{"sources":[]}}"""
    }
}
