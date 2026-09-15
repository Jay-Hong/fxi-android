package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.di.NetworkModule
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * One attempt, over a real socket.
 *
 * `runTest` cannot be used here and the reason is the subject: the attempt's budget is **elapsed
 * time**, and a virtual clock advances the moment a coroutine parks on a socket — every call would
 * time out before the server answered. So these run on the real clock, and the budget is shortened
 * rather than faked.
 */
class TopicSnapshotBootstrapServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: FakeAuthTokenSource
    private lateinit var api: AuthenticatedApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        source = FakeAuthTokenSource()
        val provider = AuthTokenProvider(source, orders = AccessOrderSequence())
        val http = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .addInterceptor(AuthSnapshotInterceptor(provider))
            .addInterceptor(MutationOneShotInterceptor())
            .addNetworkInterceptor(TopicUseNetworkInterceptor(provider))
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(http)
            .addConverterFactory(
                NetworkModule.provideWireJson().asConverterFactory("application/json".toMediaType())
            )
            .build()
        api = AuthenticatedApiClient(
            retrofit.create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { true }),
            NetworkModule.provideWireJson()
        )
    }

    @After
    fun tearDown() = server.shutdown()

    /** The ordinary answer, and the topic is the one that was asked for. */
    @Test
    fun `a snapshot for the requested topic is delivered`() = runBlocking {
        server.enqueue(ok(TETHER_SNAPSHOT))

        val outcome = service().bootstrap(owner(), TETHER)

        assertTrue(outcome is TopicSnapshotOutcome.Delivered)
        assertTrue((outcome as TopicSnapshotOutcome.Delivered).frame is DecodedTopicFrame.Tether)
    }

    /**
     * One topic per call, sent once, and spelled the way the server reads it.
     *
     * A `@Query` declared as a collection would ask for one topic while looking like it asked for
     * several, and the server keeps only the last value — the mistake would be invisible in the
     * response.
     */
    @Test
    fun `the request names one topic exactly once`() = runBlocking {
        server.enqueue(ok(TETHER_SNAPSHOT))

        service().bootstrap(owner(), TETHER)

        val recorded = server.takeRequest()
        val url = recorded.requestUrl!!
        assertEquals("/api/v2/topics/snapshot", url.encodedPath)
        assertEquals(listOf(TETHER), url.queryParameterValues("topic"))
        assertEquals(1, url.querySize)
    }

    /**
     * A snapshot for a different topic is refused rather than merged.
     *
     * The socket decoder dispatches on the topic the *body* declares and has no request to compare
     * it with; over one REST answer there is a request, and it is the only chance to notice.
     */
    @Test
    fun `a snapshot for another topic is refused`() = runBlocking {
        server.enqueue(ok(TETHER_SNAPSHOT))

        val outcome = service().bootstrap(owner(), USD)

        assertTrue("다른 topic 의 스냅샷을 받아들였다", outcome is TopicSnapshotOutcome.Malformed)
    }

    /**
     * The socket's ordinary non-answers are failures here.
     *
     * On a socket an acknowledgement, a pong or the legacy `rates` payload mean "nothing to do, the
     * connection is fine" — there is always a next frame. This response *is* the answer, so each of
     * them is the answer being absent.
     */
    @Test
    fun `a body that is not a snapshot is refused rather than ignored`() = runBlocking {
        val notAnswers = listOf(
            """{"type":"subscription_ack","request_id":"r1","operation":"subscribe",""" +
                """"accepted_topics":[],"rejected_topics":[],"removed_topics":[],"active_subscriptions":[]}""",
            """{"type":"pong"}""",
            """{"type":"rates","data":{}}""",
            """{"type":"snapshot","version":1,"topic":"weather:seoul","data":{}}"""
        )

        notAnswers.forEach { body ->
            server.enqueue(ok(body))
            val outcome = service().bootstrap(owner(), TETHER)
            assertTrue("$body 을 배달로 받아들였다", outcome is TopicSnapshotOutcome.Malformed)
        }
    }

    /** An unreadable body has no next frame to recover on, so it is a failure, not a survival. */
    @Test
    fun `an undecodable body is refused`() = runBlocking {
        server.enqueue(ok("{ this is not json"))

        val outcome = service().bootstrap(owner(), TETHER)

        assertTrue(outcome is TopicSnapshotOutcome.Malformed)
    }

    /** The wire path reaches the same verdicts the mapper is unit-tested for. */
    @Test
    fun `typed refusals arrive as topic verdicts`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":"unknown_topic","supported_topics":["usdt:krw"]}""")
        )
        server.enqueue(
            MockResponse().setResponseCode(503).setBody("""{"error":"temporarily_unavailable"}""")
        )

        assertEquals(
            TopicSnapshotOutcome.Unsupported(listOf(TETHER)),
            service().bootstrap(owner(), KRX)
        )
        assertEquals(TopicSnapshotOutcome.TemporarilyUnavailable, service().bootstrap(owner(), TETHER))
    }

    /**
     * One logical attempt is one send — unless the credential was stale, and then exactly two.
     *
     * The replay belongs to the transport and corrects the credential; it is not the bootstrap
     * retrying. A bootstrap that added a retry of its own would show up here as a third send.
     */
    @Test
    fun `one attempt is one send, and a stale credential adds exactly one more`() = runBlocking {
        server.enqueue(ok(TETHER_SNAPSHOT))
        service().bootstrap(owner(), TETHER)
        assertEquals("정상 응답인데 두 번 보냈다", 1, server.requestCount)
        assertEquals(0, source.forceRefreshCount)

        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}"""))
        server.enqueue(ok(TETHER_SNAPSHOT))
        val outcome = service().bootstrap(owner(), TETHER)

        assertTrue(outcome is TopicSnapshotOutcome.Delivered)
        assertEquals("401 재생이 한 번이 아니다", 3, server.requestCount)
        assertEquals(1, source.forceRefreshCount)
    }

    /**
     * The budget covers the whole attempt, replay included.
     *
     * Each answer is held back longer than half the budget and shorter than all of it, so an attempt
     * with no budget at all — or one that only guarded the first send — would deliver, and this
     * asserts it does not.
     *
     * A budget placed *per send* is not among the things this can catch, and measurement said so:
     * the replay lives inside the client call, so any window at or above it already spans both.
     * Reaching a per-send window would mean changing the shared transport, which this slice does not.
     */
    @Test
    fun `the budget spans the whole attempt, replay included`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}""")
                .setBodyDelay(700, TimeUnit.MILLISECONDS)
        )
        server.enqueue(ok(TETHER_SNAPSHOT).setBodyDelay(700, TimeUnit.MILLISECONDS))

        val outcome = service(budget = 1000.milliseconds).bootstrap(owner(), TETHER)

        assertEquals("401 재생이 예산을 다시 시작했다", TopicSnapshotOutcome.TimedOut, outcome)
    }

    /**
     * A withdrawal by the caller is not the attempt's own deadline.
     *
     * Both arrive as a `CancellationException`, and so does an identity change. Catching the
     * supertype would turn a screen being closed into a quiet timeout and stop the cancellation
     * from reaching the scope that issued it.
     */
    @Test
    fun `a caller's cancellation is not swallowed as a timeout`() = runBlocking {
        server.enqueue(ok(TETHER_SNAPSHOT).setBodyDelay(1500, TimeUnit.MILLISECONDS))
        var outcome: TopicSnapshotOutcome? = null
        var withdrawn = false

        val job = launch(Dispatchers.IO) {
            try {
                outcome = service(budget = 30.seconds).bootstrap(owner(), TETHER)
            } catch (cancelled: CancellationException) {
                withdrawn = true
                throw cancelled
            }
        }
        delay(200)
        job.cancelAndJoin()

        assertTrue("호출자의 취소가 예산 만료로 삼켜졌다", withdrawn)
        assertNull(outcome)
    }

    /**
     * An answer authorised for a previous account never becomes this one's.
     *
     * The credential is checked at send time by the interceptor, and again by the transport once the
     * response has arrived — that second check is what refuses a body authorised for somebody else.
     * What this locks is that the refusal reaches *this* path: the identity change keeps travelling
     * out of the bootstrap instead of being folded into an outcome, so a 403 earned by a previous
     * account never drives a premium re-check for the current one.
     */
    @Test
    fun `an answer authorised for a previous account is not applied`() = runBlocking {
        server.enqueue(ok(TETHER_SNAPSHOT).setBodyDelay(400, TimeUnit.MILLISECONDS))
        var moved = false
        var outcome: TopicSnapshotOutcome? = null

        val job = launch(Dispatchers.IO) {
            try {
                outcome = service().bootstrap(owner(), TETHER)
            } catch (changed: AuthIdentityChangedException) {
                moved = true
            }
        }
        delay(150)
        source.identity = AuthIdentity("user-b", 1)
        job.join()

        assertTrue("계정이 바뀌었는데 이전 답을 적용했다", moved)
        assertNull(outcome)
    }

    /**
     * A request issued for one grant is not quietly re-authorised as the next.
     *
     * Attaching an owner to the *answer* and binding the *request* to it are different things, and
     * this is the half a capture taken at execution time would skip: the job runs later than the
     * decision, so reading whoever is signed in by then would authenticate as `user-b` and come
     * back with a perfectly ordinary answer nobody asked for. Nothing is sent at all.
     */
    @Test
    fun `a request is not re-authorised as whoever is signed in now`() = runBlocking {
        server.enqueue(ok(TETHER_SNAPSHOT))
        val issued = owner()
        source.identity = AuthIdentity("user-b", 1)
        var moved = false

        try {
            service().bootstrap(issued, TETHER)
        } catch (changed: AuthIdentityChangedException) {
            moved = true
        }

        assertTrue("이전 grant 의 요청이 새 계정으로 나갔다", moved)
        assertEquals("보내지 않았어야 할 요청이 나갔다", 0, server.requestCount)
    }

    /**
     * A send refused because the access it serves was withheld leaves the attempt as that refusal (L-4e E2a).
     *
     * It is not an unreachable server and not a timeout: nothing was asked, and the session decides what a withheld use means.
     * The refusal travels out as a cancellation, like an identity change, so no outcome is formed for it.
     */
    @Test
    fun `a withheld use leaves the attempt as a refusal, not as an outcome`() = runBlocking {
        server.enqueue(ok(TETHER_SNAPSHOT))
        var outcome: TopicSnapshotOutcome? = null

        val refused = runCatching { outcome = service().bootstrap(owner(), TETHER) { false } }.exceptionOrNull()

        assertTrue("the withheld use became something else: $refused", refused is TopicUseWithheldException)
        assertNull(outcome)
        assertEquals("a withheld send reached the server", 0, server.requestCount)
    }

    /** A use still admitted at every exchange delivers as it always did, the server's immediate repeat included. */
    @Test
    fun `an admitted use delivers through the server's immediate repeat`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(ok(TETHER_SNAPSHOT))
        var checks = 0

        val outcome = service().bootstrap(owner(), TETHER) { checks += 1; true }

        assertTrue("an admitted use did not deliver: $outcome", outcome is TopicSnapshotOutcome.Delivered)
        assertEquals(2, server.requestCount)
        assertEquals("each exchange was not checked", 2, checks)
    }

    /**
     * A deadline the caller set is the caller's, not this attempt's.
     *
     * Both arrive as the same exception class, so catching by class hands an ordinary outcome back
     * into a coroutine that is already cancelled — and everything the caller does next with it runs.
     * Reproduced by review before this test existed.
     */
    @Test
    fun `a deadline set by the caller is not converted into an outcome`() = runBlocking {
        server.enqueue(ok(TETHER_SNAPSHOT).setBodyDelay(1500, TimeUnit.MILLISECONDS))
        var returned: TopicSnapshotOutcome? = null

        val outer = runCatching {
            withTimeout(200) { returned = service(budget = 30.seconds).bootstrap(owner(), TETHER) }
        }

        assertTrue(outer.exceptionOrNull() is TimeoutCancellationException)
        assertNull("상위 timeout 을 자기 만료로 바꿔 정상 반환했다", returned)
    }

    /**
     * The budget stops the waiting; it does not merely judge it afterwards.
     *
     * A deadline that only classified the answer at the end would still hold the caller for the
     * server's full delay. This asks for an answer that takes ten times the budget and requires the
     * attempt back long before it.
     */
    @Test
    fun `the budget stops waiting rather than judging afterwards`() = runBlocking {
        server.enqueue(ok(TETHER_SNAPSHOT).setBodyDelay(3, TimeUnit.SECONDS))
        val startedAt = System.nanoTime()

        val outcome = service(budget = 300.milliseconds).bootstrap(owner(), TETHER)
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(TopicSnapshotOutcome.TimedOut, outcome)
        assertTrue("예산이 지났는데 응답을 끝까지 기다렸다 (${elapsedMillis}ms)", elapsedMillis < 1_500)
    }

    /**
     * A held-up loop still ends the attempt at its deadline.
     *
     * The loop that would process both the response and the cancellation is taken away from 12ms to
     * ~1200ms, well past a 300ms budget. Measured: the attempt still comes back `TimedOut`, because
     * the cancellation here does not have to wait for that loop.
     *
     * **What this does not do is show an answer being accepted late** — the cancellation wins here,
     * measured. `a reply handled after the deadline is refused` is the one that shows it, and the
     * difference between them is the whole mechanism: only a `ScheduledExecutorService`-backed
     * dispatcher keeps `invokeOnTimeout` on the thread being held. This one is kept because it
     * still proves the attempt ends at its deadline when its loop is taken away.
     */
    @Test
    fun `a held-up loop still ends the attempt at its deadline`() = runBlocking {
        server.enqueue(ok(TETHER_SNAPSHOT).setBodyDelay(100, TimeUnit.MILLISECONDS))
        var outcome: TopicSnapshotOutcome? = null

        val job = launch { outcome = service(budget = 300.milliseconds).bootstrap(owner(), TETHER) }
        // Proof the attempt is in flight before the loop is taken away, rather than a sleep that
        // hopes so. `takeRequest` cannot be used for it: it blocks, and the loop it would block is
        // the one the attempt has not started on yet.
        withTimeout(2_000) { while (server.requestCount == 0) delay(5) }
        Thread.sleep(1_200)
        job.join()

        assertEquals("예산을 넘긴 응답을 받아들였다", TopicSnapshotOutcome.TimedOut, outcome)
    }

    /**
     * A reply that arrived in time but was handled after the deadline is still refused.
     *
     * The cancellation is a task, and it only queues behind the reply when the dispatcher's executor
     * is a `ScheduledExecutorService` — otherwise `invokeOnTimeout` is delegated away and fires on a
     * thread this test cannot hold. That is why the worker here is a `ScheduledThreadPoolExecutor`,
     * and it is why two earlier arrangements of this test proved nothing: an executor-backed
     * dispatcher and `runBlocking`'s own loop both delegate, so the cancellation won regardless.
     *
     * The order is forced rather than raced: the worker is occupied before the server answers, so
     * the reply is queued while nothing can run it, and the deadline falls later still. When the
     * worker frees, the queue hands back the reply first — and completing normally disposes the
     * timeout, so nothing else will ever notice. Only re-reading the clock does.
     */
    @Test
    fun `a reply handled after the deadline is refused`() = runBlocking {
        val budget = 500.milliseconds
        val occupied = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = ScheduledThreadPoolExecutor(1)
        val dispatcher = executor.asCoroutineDispatcher()

        // Held until the worker is occupied, so the reply cannot be handled the instant it lands.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                if (occupied.await(3, TimeUnit.SECONDS)) ok(TETHER_SNAPSHOT) else MockResponse().setResponseCode(500)
        }

        val startedAt = TimeSource.Monotonic.markNow()
        val pending = async(dispatcher) { service(budget = budget).bootstrap(owner(), TETHER) }
        // Queued behind the attempt's first dispatch, so it takes the worker the moment the attempt
        // parks on the socket.
        executor.submit {
            occupied.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "시험이 worker 점유를 풀지 않았다" }
        }

        try {
            assertTrue("worker 점유가 시작되지 않았다", occupied.await(2, TimeUnit.SECONDS))

            // Fixture validity, not the subject: the reply has to be waiting in the queue before the
            // deadline falls, or this proves nothing either way.
            var replyQueued = false
            while (startedAt.elapsedNow() < budget) {
                replyQueued = executor.queue.any { (it as? Delayed)?.getDelay(TimeUnit.NANOSECONDS)?.let { d -> d <= 0L } == true }
                if (replyQueued) break
                Thread.sleep(2)
            }
            assertTrue("응답 재개가 예산 전에 큐에 들어오지 않았다 — fixture 실패", replyQueued)
            assertTrue("점유 중인데 시도가 끝났다", !pending.isCompleted)

            Thread.sleep(1_200)
            release.countDown()

            assertEquals(
                "제때 도착했지만 절대 마감 뒤 처리된 응답을 수용했다",
                TopicSnapshotOutcome.TimedOut,
                withTimeout(5_000) { pending.await() }
            )
        } finally {
            occupied.countDown()
            release.countDown()
            pending.cancel()
            dispatcher.close()
        }
    }

    /**
     * The grant a call binds itself to, read at the moment of the call.
     *
     * Taken from the transport rather than built by hand, so a test that moves the identity
     * afterwards is moving it away from what the request actually captured.
     */
    private fun owner() = api.captureIdentityFence()

    private fun service(budget: Duration = 30.seconds) =
        TopicSnapshotBootstrapService(api, TopicFrameDecoder(NetworkModule.provideWireJson()), budget)

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

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

    private companion object {
        const val TETHER = "usdt:krw"
        const val USD = "fx:usd-krw"
        const val KRX = "krx:usd-krw-futures"

        val TETHER_SNAPSHOT = """
            {"type":"snapshot","version":1,"topic":"usdt:krw","data":{
              "usdt_krw":[{"source":"upbit","asset":"usdt-krw","rate":1400.0,
                           "timestamp":"2026-08-31T10:20:00+09:00"}],
              "usd_krw_banks":[]}}
        """.trimIndent()
    }
}
