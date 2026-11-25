package com.jay.fxi.ui.alert

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.jay.fxi.data.alerts.*
import com.jay.fxi.data.auth.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.remote.*
import com.jay.fxi.data.remote.dto.ComparisonAlertHistoryItem
import com.jay.fxi.data.repository.*
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.di.NetworkModule
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit

private typealias Answer = AuthBoundResult<List<ComparisonAlertHistoryItem>>

@OptIn(ExperimentalCoroutinesApi::class)
class ComparisonAlertHistorySessionTest {
    private val identity = AuthIdentityFence("user-a", 1)
    private val owner = TopicDisplayOwner(identity, 1)
    private val json = NetworkModule.provideWireJson()
    private val queries = listOf("usd" to "absolute", "jpy" to "absolute", "eur" to "absolute",
        "tether" to "signed", "tether" to "absolute")

    private class Pending(val guard: () -> Boolean, val continuation: Continuation<Answer>)

    private inner class Rig(val test: TestScope) {
        val fence = TopicSessionFence(identity, "epoch", TopicGrantToken(11))
        @Volatile var snapshot = TopicAccessSnapshot.INITIAL.copy(revision = 1,
            facts = TopicAccessFacts.NONE.copy(token = fence.grant, tokenStanding = true, userBlocks = emptySet()))
        @Volatile var release = true
        var premium = OwnedPremiumAccess(identity.uid, identity.authGeneration, PremiumAccessState.PremiumConfirmed)
        val deletions = DeletionAdmissionStore()
        val accessRev = MutableStateFlow(0L)
        val deleteRev = MutableStateFlow(0L)
        private val base = SnapshotTopicUseAuthority { snapshot }
        var acquired = 0
        private val uses = object : TopicUseAuthority by base {
            override fun acquire(fence: TopicSessionFence): TopicUseLifetime? {
                acquired++
                return base.acquire(fence)
            }
        }
        val access = AlertSettingsAccess({ identity }, { fence }, uses, { premium }, { release },
            GraphProtectedAdmission({ identity }, deletions))
        var display: TopicDisplayOwner? = owner
        var selected = "usd"
        val listeners = mutableSetOf<() -> Unit>()
        var stops = 0
        var retired = 0
        var folds = 0
        var beforeFold: () -> Unit = {}
        val pending = mutableListOf<Pending>()
        var read = ComparisonHistoryRead { _, _, _, guard ->
            suspendCoroutine { pending += Pending(guard, it) }
        }
        val requests = ComparisonRequestSlot()
        fun session(tab: String = selected, kind: String = "absolute") =
            ComparisonAlertHistorySession(owner, tab, kind, ComparisonHistoryWiring(access, read, accessRev, deleteRev),
                test.backgroundScope, requests, { display == owner && selected == tab }, { retired++ })
        fun start(session: ComparisonAlertHistorySession, leaveNow: Boolean = false) {
            session.open { end ->
                val slot = ComparisonSlot(owner, FreeTab.valueOf(selected.uppercase(java.util.Locale.ROOT)))
                val publish: () -> Unit = { if (!slot.matches(display, FreeTab.valueOf(selected.uppercase(java.util.Locale.ROOT)))) end() }
                listeners += publish
                if (leaveNow) end() else publish()
                val stop: () -> Unit = { assertTrue(listeners.remove(publish)); stops++ }
                stop
            }
            test.runCurrent()
        }
        fun answer(result: Result<List<ComparisonAlertHistoryItem>>, call: Pending = pending.last()) {
            call.continuation.resume(AuthBoundResult({ folds++; beforeFold() }, result))
            test.runCurrent()
        }
        fun ok(rows: List<ComparisonAlertHistoryItem>) = answer(Result.success(rows))
        fun observed(session: ComparisonAlertHistorySession): List<ComparisonHistoryBody?> {
            val events = mutableListOf<ComparisonHistoryBody?>()
            test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) { session.state.toList(events) }
            return events
        }
    }

    private fun http(status: Int, retry: String? = null): Exception {
        val headers = if (retry == null) headersOf() else headersOf("Retry-After", retry)
        return mapAlertHttpFailure(AuthenticatedHttpFailure(status, headers, byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP))
    }

    private fun adapter(server: MockWebServer, beforeExchange: () -> Unit = {}): ComparisonHistoryRead {
        val source = object : AuthTokenSource {
            override fun currentIdentity() = AuthIdentity(identity.uid, identity.authGeneration)
            override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean) = if (forceRefresh) "fresh" else "first"
        }
        val provider = AuthTokenProvider(source, orders = AccessOrderSequence())
        val client = OkHttpClient.Builder().addInterceptor(AuthSnapshotInterceptor(provider))
            .addNetworkInterceptor { chain ->
                if (chain.request().tag(TopicUseTag::class.java) == null) throw IOException("missing original use guard")
                beforeExchange()
                chain.proceed(chain.request())
            }.addNetworkInterceptor(TopicUseNetworkInterceptor(provider)).build()
        val api = Retrofit.Builder().baseUrl(server.url("/")).client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
            .create(AuthenticatedApiService::class.java)
        return comparisonHistoryRead(AlertRepository(AuthenticatedApiClient(api, AuthenticatedTransport(provider, admitted = { true }), json)))
    }

    private fun TestScope.settleWire(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!done() && System.nanoTime() < deadline) {
            runCurrent()
            Thread.sleep(1)
        }
        assertTrue("wire completion", done())
        runCurrent()
    }

    @Test fun HJ01_queries() = runTest {
        val rig = Rig(this)
        MockWebServer().use { server ->
            val delivered = queries.mapIndexed { i, (tab, kind) -> delivery(20 - i, tab, kind) }
            val counts = mutableMapOf<Pair<String, String>, Int>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = checkNotNull(request.requestUrl)
                    val q = url.queryParameter("tab").orEmpty() to url.queryParameter("diff_type").orEmpty()
                    if (url.encodedPath != "/api/comparison-notification-logs" || request.method != "GET" || request.bodySize != 0L ||
                        url.querySize != 3 || url.queryParameterNames != setOf("tab", "diff_type", "limit") ||
                        url.queryParameter("limit") != "100" || q !in queries) return MockResponse().setResponseCode(400)
                    val n = counts.getOrDefault(q, 0) + 1
                    counts[q] = n
                    if (n == 1) return MockResponse().setResponseCode(503)
                    val expected = delivered[queries.indexOf(q)]
                    val rows = listOf(expected.copy(leftSource = "krx"), expected, expected.copy(rightAsset = "unknown")) +
                        delivered.filter { it.tab != expected.tab || it.diffType != expected.diffType }.map { it.copy(id = it.id + 1000) }
                    return MockResponse().setBody("""{"logs":${json.encodeToString(rows)},"total_count":${rows.size}}""")
                }
            }
            val production = adapter(server)
            var logical = 0
            rig.read = ComparisonHistoryRead { o, t, k, guard ->
                assertEquals(owner, o)
                assertTrue(guard())
                logical++
                production.read(o, t, k, guard)
            }
            assertEquals(0, server.requestCount)
            for ((i, q) in queries.withIndex()) {
                rig.selected = q.first
                val session = rig.session(q.first, q.second)
                rig.start(session)
                settleWire { session.state.value is ComparisonHistoryBody.Failed }
                assertEquals(ComparisonHistoryBody.Failed(true), session.currentState())
                session.retry()
                settleWire { session.state.value is ComparisonHistoryBody.Loaded }
                val rows = ComparisonHistoryBody.Loaded(listOf(delivered[i]))
                assertEquals(rows, session.currentState())
                session.refresh()
                settleWire { session.state.value == rows }
                session.close()
                repeat(3) {
                    val request = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                    val url = checkNotNull(request.requestUrl)
                    assertEquals("GET", request.method)
                    assertEquals(0L, request.bodySize)
                    assertEquals("/api/comparison-notification-logs", url.encodedPath)
                    assertEquals(mapOf("tab" to listOf(q.first), "diff_type" to listOf(q.second), "limit" to listOf("100")),
                        url.queryParameterNames.associateWith(url::queryParameterValues))
                }
                val expectedCount = 3 * (i + 1)
                assertEquals(expectedCount, server.requestCount)
                assertEquals(expectedCount, logical)
                assertEquals(expectedCount, rig.acquired)
            }
        }
    }

    @Test fun HJ02_adoption() = runTest {
        for ((tab, kind) in queries) {
            val rig = Rig(this)
            rig.selected = tab
            val session = rig.session(tab, kind)
            val base = delivery(81, tab, kind)
            val kept = listOf(base, base.copy(leftRate = 8.0, spread = -5.0, isRepeat = true)) +
                (0..31).map { base.copy(id = 50 - it, settingId = it,
                    sentAt = kotlinx.datetime.Instant.fromEpochMilliseconds(base.sentAt.toEpochMilliseconds() + (it % 3) * 1000L)) }
            val invalid = listOf(base.copy(tab = "news"), base.copy(diffType = if (kind == "signed") "absolute" else "signed"),
                base.copy(leftSource = "krx"), base.copy(rightSource = "krx"), base.copy(leftSource = "krx", rightSource = "krx"), base.copy(leftAsset = "wrong"),
                base.copy(rightAsset = "wrong"), base.copy(leftSource = base.rightSource, leftAsset = base.rightAsset))
            val reversed = if (kind == "signed") listOf(base.copy(leftSource = base.rightSource, leftAsset = base.rightAsset,
                rightSource = base.leftSource, rightAsset = base.leftAsset)) else emptyList()
            rig.start(session)
            rig.ok(invalid + kept.first() + reversed + kept.drop(1))
            assertEquals(ComparisonHistoryBody.Loaded(kept), session.currentState())
            session.refresh(); runCurrent()
            rig.ok(invalid + reversed)
            assertEquals(ComparisonHistoryBody.Empty, session.currentState())
            session.close()
        }
    }

    @Test fun HJ03_actions() = runTest {
        val rig = Rig(this)
        val session = rig.session()
        rig.start(session); rig.start(session)
        session.refresh(); session.retry(); runCurrent()
        assertEquals(1, rig.pending.size)
        var calls = 1
        rig.answer(Result.failure(http(429, "600")))
        assertEquals(ComparisonHistoryBody.Failed(false), session.state.value)
        assertEquals(calls, rig.pending.size)
        session.retry(); runCurrent()
        assertEquals(++calls, rig.pending.size)
        assertEquals(ComparisonHistoryBody.Loading, session.state.value)

        val rows = listOf(delivery(11), delivery(2))
        for ((error, temporary) in listOf(http(503) to true, http(429, "600") to false, http(403) to false,
            http(401) to false, IOException("network") to false, IllegalArgumentException("decode") to false)) {
            rig.answer(Result.failure(error))
            val failed = session.currentState() as ComparisonHistoryBody.Failed
            assertEquals(temporary, failed.temporary)
            assertEquals(if (temporary) "구독 확인 중입니다. 잠시 후 다시 시도해주세요." else "히스토리를 불러오지 못했습니다.", failed.message)
            session.refresh(); advanceTimeBy(900_000); runCurrent()
            assertEquals(calls, rig.pending.size)
            session.retry(); session.retry(); runCurrent()
            assertEquals(++calls, rig.pending.size)
            assertEquals(ComparisonHistoryBody.Loading, session.state.value)
            rig.ok(rows)
            session.retry(); runCurrent()
            assertEquals(calls, rig.pending.size)
            session.refresh(); session.refresh(); runCurrent()
            assertEquals(++calls, rig.pending.size)
            assertEquals(ComparisonHistoryBody.Loaded(rows, true), session.state.value)
            session.refresh(); runCurrent()
            assertEquals(calls, rig.pending.size)
            assertEquals(ComparisonHistoryBody.Loaded(rows, true), session.state.value)
        }
        rig.ok(emptyList()); session.refresh(); session.retry(); runCurrent()
        assertEquals(ComparisonHistoryBody.Empty, session.state.value)
        assertEquals(calls, rig.pending.size)
        session.close()
    }

    @Test fun HJ04_retirement() = runTest {
        for (reopen in listOf(false, true)) for (success in listOf(false, true)) {
            val rig = Rig(this)
            val a = rig.session()
            val events = rig.observed(a)
            rig.start(a)
            assertEquals(1, rig.accessRev.subscriptionCount.value)
            val pending = rig.pending.single()
            a.close(); runCurrent()
            assertFalse(pending.guard())
            assertEquals(listOf(1, 0, 0, 0), listOf(rig.stops, rig.listeners.size,
                rig.accessRev.subscriptionCount.value, rig.deleteRev.subscriptionCount.value))
            val b = rig.session()
            if (reopen) rig.start(b)
            if (success) rig.answer(Result.success(listOf(delivery())), pending) else {
                pending.continuation.resumeWithException(IOException("late failure"))
                runCurrent()
            }
            a.retry(); a.refresh(); a.close(); rig.start(a)
            assertEquals(listOf(null, ComparisonHistoryBody.Loading, null), events)
            assertEquals(0, rig.folds)
            assertNull(a.state.value)
            if (reopen) {
                assertEquals(ComparisonHistoryBody.Loading, b.state.value)
                assertTrue(rig.pending.last().guard())
                rig.ok(listOf(delivery()))
                assertEquals(ComparisonHistoryBody.Loaded(listOf(delivery())), b.currentState())
                b.close()
            }
            assertEquals(if (reopen) 2 else 1, rig.pending.size)
        }
    }

    @Test fun HJ05_originalUse() = runTest {
        val losses = listOf<Rig.() -> Unit>(
            { display = TopicDisplayOwner(AuthIdentityFence("other", 1), 1) },
            { display = TopicDisplayOwner(AuthIdentityFence(identity.uid, 2), 1) },
            { display = TopicDisplayOwner(identity, 2) }, { selected = "jpy" },
            { release = false }, { premium = premium.copy(state = PremiumAccessState.FreeConfirmed) },
            { deletions.begin(identity, "op") }, { snapshot = snapshot.copy(userInvalidations = snapshot.userInvalidations + 1) }
        )
        for ((index, lose) in losses.withIndex()) for (phase in listOf("before", "sent", "fold", "display")) {
            for (success in listOf(true, false)) {
                val rig = Rig(this)
                val a = rig.session()
                if (phase == "before") lose(rig)
                rig.start(a)
                if (phase == "before") {
                    if (index == losses.lastIndex) {
                        // A use issued after this revision is valid.
                        assertEquals(1, rig.pending.size)
                        rig.ok(emptyList())
                    } else assertEquals(0, rig.pending.size)
                    a.close()
                    continue
                }
                fun withdraw() {
                    lose(rig)
                    if (index < 4) {
                        rig.listeners.toList().forEach { it() }
                        rig.display = owner; rig.selected = "usd"
                    }
                }
                val outcome = if (success) Result.success(listOf(delivery())) else Result.failure(IOException())
                when (phase) {
                    "display" -> {
                        rig.answer(outcome)
                        assertNotNull(a.state.value)
                        withdraw()
                        rig.accessRev.value++; rig.deleteRev.value++; runCurrent()
                    }
                    "fold" -> { rig.beforeFold = ::withdraw; rig.answer(outcome) }
                    else -> { withdraw(); rig.answer(outcome); assertEquals(0, rig.folds) }
                }
                assertNull(a.state.value)
                assertFalse(rig.pending.single().guard())
                assertEquals(1, rig.retired)
                rig.beforeFold = {}
                rig.release = true
                rig.premium = OwnedPremiumAccess(identity.uid, identity.authGeneration, PremiumAccessState.PremiumConfirmed)
                rig.deletions.releaseUnsent("op")
                val b = rig.session()
                rig.start(b); rig.ok(listOf(delivery()))
                assertEquals(ComparisonHistoryBody.Loaded(listOf(delivery())), b.currentState())
                b.close()
            }
        }
    }

    @Test fun HJ06_wireLifetime() = runTest {
        for (scenario in listOf("503", "replay", "first denied", "replay denied", "replay closed")) {
            val rig = Rig(this)
            val waiting = CountDownLatch(1)
            val releaseBarrier = CountDownLatch(1)
            val exchanges = AtomicInteger()
            var logical = 0
            var returned = false
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(if (scenario == "503") 503 else 401))
                server.enqueue(MockResponse().setBody("""{"logs":[],"total_count":0}"""))
                val production = adapter(server) {
                    val number = exchanges.incrementAndGet()
                    if (number == 1 && scenario == "first denied" || number == 2 && scenario.startsWith("replay ")) {
                        waiting.countDown()
                        check(releaseBarrier.await(12, TimeUnit.SECONDS))
                    }
                }
                rig.read = ComparisonHistoryRead { o, t, k, guard ->
                    logical++
                    try { withContext(NonCancellable) { production.read(o, t, k, guard) } }
                    finally { returned = true }
                }
                val a = rig.session()
                try {
                    rig.start(a)
                    if (scenario.endsWith("denied") || scenario.endsWith("closed")) {
                        settleWire { waiting.count == 0L }
                        if (scenario.endsWith("closed")) a.close() else rig.release = false
                        releaseBarrier.countDown()
                    }
                    settleWire { returned }
                    assertEquals(1, logical)
                    when (scenario) {
                        "503" -> { assertEquals(1, server.requestCount); assertEquals(ComparisonHistoryBody.Failed(true), a.state.value) }
                        "replay" -> { assertEquals(2, server.requestCount); assertEquals(ComparisonHistoryBody.Empty, a.state.value) }
                        else -> { assertEquals(if (scenario == "first denied") 0 else 1, server.requestCount); assertNull(a.state.value) }
                    }
                } finally { releaseBarrier.countDown(); a.close() }
            }
        }
        val rig = Rig(this)
        val cancelled = rig.session()
        rig.start(cancelled)
        rig.pending.single().continuation.resumeWithException(CancellationException("cancelled read"))
        runCurrent()
        assertNull(cancelled.state.value)
        assertEquals(0, rig.listeners.size)
        assertFalse(rig.pending.single().guard())
        val gone = rig.session()
        rig.start(gone, leaveNow = true)
        assertEquals(listOf(2, 1, 0), listOf(rig.stops, rig.acquired, rig.accessRev.subscriptionCount.value))
        rig.read = ComparisonHistoryRead { _, _, _, _ -> AuthBoundResult({}, Result.success(emptyList())) }
        val immediate = rig.session()
        rig.start(immediate)
        assertEquals(ComparisonHistoryBody.Empty, immediate.currentState())
        assertNull(rig.requests.active)
        immediate.close(); runCurrent()
        assertEquals(listOf(3, 0, 0), listOf(rig.stops, rig.listeners.size, rig.deleteRev.subscriptionCount.value))
    }
}
