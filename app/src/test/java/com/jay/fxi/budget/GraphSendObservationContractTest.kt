package com.jay.fxi.budget

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.graph.AuthenticatedGraphV2Fetcher
import com.jay.fxi.data.graph.GraphDataScope
import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphObservationSeriesKey
import com.jay.fxi.data.graph.GraphOwnerSource
import com.jay.fxi.data.graph.GraphRecorder
import com.jay.fxi.data.graph.GraphV2AccessGate
import com.jay.fxi.data.graph.GraphV2Fetching
import com.jay.fxi.data.graph.GraphV2RequestCoordinator
import com.jay.fxi.data.graph.LateBound
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicGraphCandidate
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.data.remote.TopicGraphPath
import com.jay.fxi.data.remote.TopicGraphRecoveryPermit
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAttribution
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.remote.TopicUseContext
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import com.jay.fxi.data.remote.TopicUseTag
import com.jay.fxi.data.remote.WithheldUseIOException
import com.jay.fxi.data.remote.dto.GraphV2CatalogPeriod
import com.jay.fxi.data.remote.dto.GraphV2CatalogResponse
import com.jay.fxi.data.remote.dto.GraphV2CatalogTab
import com.jay.fxi.data.remote.dto.GraphV2Metadata
import com.jay.fxi.data.remote.dto.GraphV2Point
import com.jay.fxi.data.remote.dto.GraphV2Provenance
import com.jay.fxi.data.remote.dto.GraphV2Range
import com.jay.fxi.data.remote.dto.GraphV2Series
import com.jay.fxi.data.remote.dto.GraphV2TabResponse
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.GraphPeriod
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.jay.fxi.time.AppClock
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

/**
 * S6-4a-obs r5 (scope r27, report.design.r42 §2~4, r44, r46, r47, r49): [GraphSendObservation] against the real GraphV2RequestCoordinator and
 * the real TopicUseNetworkInterceptor. The fixture follows GraphV2RecoveryRequestContractTest's (field for field where used) with
 * the permit supplier wired as the production builder wires it (`{ slot.require()() }`, ProcessGraphBuilder:121), and gets its
 * four callbacks - activation catalog and tab, round catalog and tab - by GraphRecoveryBudgetContractTest C03's steps.
 *
 * The fake fetcher keeps each callback and never invokes it: only the guard calls it. Changing the fixture's permit between the
 * round's issue and the guard call is a BOUNDARY_INPUT, not a reproduction of a production path, a performance claim or the CI
 * failure. Responsibility prefixes: `contract(OBS/<name>)`; a scenario that cannot be reached fails with `precondition(OBS/...)`.
 * OBS05 and OBS06 make headers with [GraphSendObservation.stamp], the step the six cutover rows use; OBS07 checks the completeness
 * validator on hand-built receipts (validator branches, not product behaviour); OBS08 the unobserved path and the port.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphSendObservationContractTest {

    private companion object {
        /** 2026-10-05 12:00 KST. */
        val NOON: Instant = Instant.parse("2026-10-05T03:00:00Z")
        val USD_1D = GraphKey("usd", GraphPeriod.ONE_DAY)
        val USD_3M = GraphKey("usd", GraphPeriod.THREE_MONTHS)
        val B1: Instant = Instant.parse("2026-10-05T02:20:00Z")
        val B2: Instant = Instant.parse("2026-10-05T02:30:00Z")
        val KB = GraphObservationSeriesKey(GraphDataScope("u1", "e1"), "kb.usd")
        const val GRAPH_TAB = """{"tab":"usd","period":"1d","series":[],"metadata":{"fetched_at":"2026-09-29T10:00:00+09:00",""" +
            """"bucket_size":"10min","range":{"start":"2026-09-28","end":"2026-09-29"}}}"""
        /** The transport-side credential the real guard checks; unrelated to the coordinator's owners. */
        val SNAPSHOT = AuthSnapshot("user-a", 1, "old-token")

        fun sessionFence(grant: Long = 7) = TopicSessionFence(AuthIdentityFence("u1", 1L), "e1", TopicGrantToken(grant))

        fun permit(fence: TopicSessionFence, automatic: Boolean, revision: Long = 1) =
            TopicGraphRecoveryPermit("session", revision, fence, 1L, false, null, null, automatic)
    }

    private class Sent(val kind: String, val key: GraphKey?, val callback: () -> Boolean) {
        val tab = CompletableDeferred<AuthenticatedHttpResponse<GraphV2TabResponse>>()
        val catalog = CompletableDeferred<AuthenticatedHttpResponse<GraphV2CatalogResponse>>()
    }

    private class Source : AuthTokenSource {
        var refreshes = 0
        override fun currentIdentity(): AuthIdentity = AuthIdentity("user-a", 1)
        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            if (forceRefresh) refreshes++
            return if (refreshes > 0) "fresh-token" else "old-token"
        }
    }

    /** One exchange of a chain answering [status]; [onProceed] runs where the header interceptor would. */
    private class TestChain(
        private val request: Request,
        private val status: Int = 200,
        private val retryAfter: String? = null,
        private val onProceed: (Request) -> Unit = {}
    ) : Interceptor.Chain {
        var proceeds = 0
        var last: Response? = null
        override fun request(): Request = request
        override fun proceed(request: Request): Response {
            proceeds++
            onProceed(request)
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .apply { retryAfter?.let { header("Retry-After", it) } }
                .body("".toResponseBody()).build().also { last = it }
        }

        override fun connection(): Connection? = null
        override fun call(): Call = error("not used")
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    private class Fixture(test: TestScope, val observer: GraphSendObservation) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val base = test.testScheduler.currentTime
        var fence: TopicSessionFence? = sessionFence()
        var allowed = true
        var invalidations = 0L
        /** The session's P7 permit as the original slot's supplier returns it. */
        var permit: TopicGraphRecoveryPermit? = null
        /** Calls of the original slot's supplier. */
        var permitReads = 0
        var autoCatalog: ((Sent) -> Unit)? = null
        val sent = mutableListOf<Sent>()
        val failures = mutableListOf<Throwable>()

        val uses = object : TopicUseAuthority {
            override fun acquire(fence: TopicSessionFence): TopicUseLifetime? =
                if (allowed && fence == this@Fixture.fence) TopicUseLifetime(fence.grant, invalidations) else null

            override fun admits(lifetime: TopicUseLifetime): Boolean =
                allowed && this@Fixture.fence?.grant == lifetime.grant && invalidations == lifetime.invalidations
        }

        val owners = object : GraphOwnerSource {
            override fun currentIdentity(): AuthIdentityFence? = fence?.identity

            override suspend fun capture(expected: AuthIdentityFence): AuthSnapshot {
                if (currentIdentity() != expected) throw AuthIdentityChangedException()
                return AuthSnapshot(expected.uid, expected.authGeneration, "token")
            }
        }

        val fetcher = object : GraphV2Fetching {
            override suspend fun catalog(
                owner: AuthSnapshot,
                useAdmitted: () -> Boolean
            ): AuthenticatedHttpResponse<GraphV2CatalogResponse> {
                val s = Sent("catalog", null, useAdmitted)
                sent += s
                autoCatalog?.invoke(s)
                return s.catalog.await()
            }

            override suspend fun tab(
                owner: AuthSnapshot,
                key: GraphKey,
                useAdmitted: () -> Boolean
            ): AuthenticatedHttpResponse<GraphV2TabResponse> {
                val s = Sent("tab", key, useAdmitted)
                sent += s
                return s.tab.await()
            }
        }

        val clock = AppClock { NOON + (test.testScheduler.currentTime - base).milliseconds }

        private fun snapshot() = TopicAccessSnapshot(
            revision = 1L,
            facts = TopicAccessFacts.NONE.copy(
                token = fence?.grant,
                binding = EntitlementsIdentity("u1", 1L),
                tokenStanding = allowed,
                userBlocks = emptySet<TopicAccessBlock>(),
                capabilityBlocks = emptySet()
            ),
            userInvalidations = invalidations,
            lastUserEnd = null,
            lastCapabilityEnd = null
        )

        var protectedOpen = true
        lateinit var coordinator: GraphV2RequestCoordinator
        val gate = GraphV2AccessGate({ owners.currentIdentity() }, { fence }, { snapshot() }, { protectedOpen })
        val recorder = GraphRecorder(scope, MutableStateFlow(1L), { snapshot() }, { fence }, { coordinator.state.value.catalog }, gate, clock)

        /** The runtime's slot, set once as ProcessGraphStarter.bindRuntime does; the coordinator reads the observed one. */
        val original = LateBound<() -> TopicGraphRecoveryPermit?>("graph permit").also { slot ->
            slot.set {
                permitReads++
                permit
            }
        }
        val observed = observer.recordingSlot(original)

        init {
            coordinator = GraphV2RequestCoordinator(
                fetcher = fetcher,
                owners = owners,
                currentAccessFence = { fence },
                uses = uses,
                protectedAdmission = { true },
                accessSnapshot = { TopicAccessSnapshot(1L, TopicAccessFacts.NONE, invalidations, null, null) },
                scope = scope,
                clock = clock,
                rateLimitJitter = { Duration.ZERO },
                onEventFailure = { failures += it },
                recorder = recorder,
                recoveryPermit = { observed.require()() }
            )
        }

        val state get() = coordinator.state.value
        fun tabs(key: GraphKey) = sent.filter { it.kind == "tab" && it.key == key }
        fun catalogs() = sent.filter { it.kind == "catalog" }
        fun lifetime() = TopicUseLifetime(checkNotNull(fence).grant, invalidations)

        fun quote(source: String, at: Instant) = TopicGraphInput.Observations(
            1L, "fx:usd-krw", TopicGraphPath.WS,
            TopicUseAttribution(Any(), checkNotNull(fence), 1L, lifetime()), 1L,
            listOf(TopicGraphCandidate.Quote(source, "usd-krw", 1390.0, at, null))
        )

        fun lose(source: String, at: Instant) {
            protectedOpen = false
            recorder.observe(quote(source, at))
            protectedOpen = true
        }

        fun close() = scope.cancel()
    }

    /** The four callbacks of one round, in the order they were handed to the fetcher. */
    private class Callbacks(val activationCatalog: Sent, val activationTab: Sent, val roundCatalog: Sent, val roundTab: Sent)

    private fun <T> ok(body: T) = AuthenticatedHttpResponse(200, Headers.headersOf(), body, null, byteArrayOf(1))

    private fun <T> status(code: Int): AuthenticatedHttpResponse<T> {
        val headers = Headers.headersOf()
        val failure = AuthenticatedHttpFailure(code, headers, byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP)
        return AuthenticatedHttpResponse(code, headers, null, failure, byteArrayOf(1))
    }

    private fun catalog() = GraphV2CatalogResponse(
        tabs = listOf(
            GraphV2CatalogTab(
                "usd", "usd", emptyMap(), mapOf(
                    "1d" to GraphV2CatalogPeriod(listOf("kb.usd", "hana.usd"), listOf("kb.usd")),
                    "3m" to GraphV2CatalogPeriod(listOf("kb.usd"), listOf("kb.usd"))
                )
            )
        ),
        version = "2026-05-27",
        supportedPeriods = listOf("1w", "3m", "1y"),
        cacheTtlSeconds = 172800
    )

    private fun threeMonthTab() = GraphV2TabResponse(
        tab = "usd",
        period = GraphPeriod.THREE_MONTHS.code,
        series = listOf(
            GraphV2Series(
                "kb.usd", "kb.usd", "krw", "KRW", 2, listOf(GraphV2Point(Instant.parse("2026-10-04T00:00:00Z"), 1390.0, "x")),
                GraphV2Provenance(false, emptyList()), null
            )
        ),
        metadata = GraphV2Metadata(NOON - 1.hours, "1d", GraphV2Range("2026-07-05", "2026-10-05"))
    )

    private val opened = mutableListOf<Fixture>()

    private fun obsTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        try {
            body()
        } finally {
            opened.forEach { it.close() }
            opened.clear()
        }
    }

    private fun TestScope.step(d: Duration) {
        advanceTimeBy(d.inWholeMilliseconds)
        runCurrent()
    }

    /** Started; the catalog adopted through a settled usd 3m request; kb exists with closed losses at B1 and B2. */
    private fun TestScope.ready(observer: GraphSendObservation): Fixture = Fixture(this, observer).also { f ->
        opened += f
        f.permit = permit(checkNotNull(f.fence), automatic = true)
        f.autoCatalog = { it.catalog.complete(ok(catalog())) }
        f.coordinator.start(); runCurrent()
        f.coordinator.onActivated(USD_3M); runCurrent()
        f.tabs(USD_3M).single().tab.complete(ok(threeMonthTab())); runCurrent()
        assertNotNull("precondition(OBS/fixture): catalog adopted", f.state.catalog)
        f.recorder.observe(f.quote("kb", NOON - 5.seconds))
        f.lose("kb", B1 + 60.seconds)
        f.lose("kb", B2 + 60.seconds)
        assertTrue("precondition(OBS/fixture): kb has losses at B1 and B2", B1 in f.recorder.state.value.series.getValue(KB).pending)
    }

    /**
     * GraphRecoveryBudgetContractTest C03's steps under an open permit: the catalog dropped by a new grant, a 1d activation whose
     * tab and catalog fail, then the round's own catalog 3 s later and, once it is adopted, the round's tab. The round's tab is
     * left in flight, so its registration is still live when a guard is called.
     */
    private fun TestScope.round(observer: GraphSendObservation): Pair<Fixture, Callbacks> {
        val f = ready(observer)
        f.autoCatalog = null
        f.fence = sessionFence(grant = 8); f.coordinator.onContextChanged(); runCurrent()
        assertNull("precondition(OBS/round): the catalog was dropped", f.state.catalog)
        f.permit = permit(checkNotNull(f.fence), automatic = true)
        f.coordinator.onActivated(USD_1D); runCurrent()
        val activationCatalog = f.catalogs().last()
        val activationTab = f.tabs(USD_1D).singleOrNull()
        assertNotNull("precondition(OBS/round): one activation tab", activationTab)
        activationTab!!.tab.complete(status(503)); runCurrent()
        activationCatalog.catalog.complete(status(503)); runCurrent()
        step(3.seconds)
        val roundCatalog = f.catalogs().last()
        assertNotSame("precondition(OBS/round): the round's own catalog went", activationCatalog, roundCatalog)
        roundCatalog.catalog.complete(ok(catalog())); runCurrent()
        val roundTab = f.tabs(USD_1D).last()
        assertNotSame("precondition(OBS/round): the round's tab went", activationTab, roundTab)
        return f to Callbacks(activationCatalog, activationTab, roundCatalog, roundTab)
    }

    private fun graphRequest(context: TopicUseContext, send: Int = 1, path: String = "/api/v2/graph/tab?tab=usd&period=1d") =
        Request.Builder().url("https://example.invalid$path").tag(TopicUseTag::class.java, TopicUseTag(SNAPSHOT, send, context)).build()

    private fun guard() = TopicUseNetworkInterceptor(AuthTokenProvider(Source(), orders = AccessOrderSequence()))

    // --- OBS01 -------------------------------------------------------------------------------------------------------

    /** OBS01: the recording slot reads the original slot lazily, once per read, and hands back exactly what it returned. */
    @Test fun obs01_slotRemainsLazyAndReturnsOriginal() {
        val observer = GraphSendObservation("probe")
        val unset = LateBound<() -> TopicGraphRecoveryPermit?>("graph permit")
        val slot = observer.recordingSlot(unset)
        val refused = runCatching { slot.require()() }.exceptionOrNull()
        assertTrue("contract(OBS/lazy): an unset original still refuses: $refused", refused is IllegalStateException)
        assertEquals("contract(OBS/lazy): with the original's own message", "graph permit is not bound", refused?.message)

        val original = LateBound<() -> TopicGraphRecoveryPermit?>("graph permit")
        val slot2 = observer.recordingSlot(original)
        var calls = 0
        var answer: TopicGraphRecoveryPermit? = permit(sessionFence(), automatic = true)
        val thrown = IllegalStateException("supplier")
        var throwNext = false
        original.set {
            calls++
            if (throwNext) throw thrown
            answer
        }
        assertSame("contract(OBS/single-read): the original's object", answer, slot2.require()())
        assertEquals("contract(OBS/single-read): one original read", 1, calls)
        answer = null
        assertNull("contract(OBS/single-read): null passes through", slot2.require()())
        throwNext = true
        assertSame("contract(OBS/single-read): the supplier's own throwable", thrown, runCatching { slot2.require()() }.exceptionOrNull())
        assertEquals("contract(OBS/single-read): one original read per call", 3, calls)
        assertTrue("contract(OBS/inactive): no frame, nothing recorded", observer.attempts.isEmpty())
    }

    // --- OBS02 -------------------------------------------------------------------------------------------------------

    /**
     * OBS02: the wrapper delegates to the real guard exactly once and passes its response or throwable through unchanged; the
     * frame lives only inside the call and an outer frame comes back after an inner one. A callback without a captured
     * registration is recorded as an origin fault, not as a non-round send.
     */
    @Test fun obs02_realGuardDelegatesOnceAndRestoresFrame() {
        val observer = GraphSendObservation("probe")
        val wrapped = observer.wrap(guard())

        val admitted = TestChain(graphRequest(TopicUseContext { true }))
        val response = wrapped.intercept(admitted)
        assertEquals("contract(OBS/delegate): an admitted send proceeds once", 1, admitted.proceeds)
        assertSame("contract(OBS/delegate): the guard's response unchanged", admitted.last, response)
        assertNull("contract(OBS/frame): no frame after the call", observer.current())

        val refusedChain = TestChain(graphRequest(TopicUseContext { false }))
        val refused = runCatching { wrapped.intercept(refusedChain) }.exceptionOrNull()
        assertTrue("contract(OBS/delegate): a refused send throws the guard's refusal: $refused", refused is WithheldUseIOException)
        assertEquals("contract(OBS/delegate): a refused send never proceeds", 0, refusedChain.proceeds)
        assertNull("contract(OBS/frame): no frame after a refusal", observer.current())

        val failure = IllegalStateException("use check")
        val throwing = runCatching { wrapped.intercept(TestChain(graphRequest(TopicUseContext { throw failure }))) }.exceptionOrNull()
        assertSame("contract(OBS/delegate): the use check's own throwable", failure, throwing)
        assertNull("contract(OBS/frame): no frame after a throw", observer.current())

        assertEquals("contract(OBS/delegate): one attempt per call", 3, observer.attempts.size)
        assertTrue(
            "contract(OBS/origin): a callback without a registration is an origin fault",
            observer.attempts.all { it.origin == null && it.originFault != null }
        )

        var innerSawOuter: GraphSendObservation.Attempt? = null
        var afterInner: GraphSendObservation.Attempt? = null
        val nested = observer.wrap(Interceptor { chain ->
            val outer = observer.current()
            observer.wrap(guard()).intercept(TestChain(graphRequest(TopicUseContext { true }, send = 2)))
            innerSawOuter = outer
            afterInner = observer.current()
            TestChain(chain.request()).proceed(chain.request())
        })
        nested.intercept(TestChain(graphRequest(TopicUseContext { true })))
        assertNotNull("precondition(OBS/frame): the outer frame was open", innerSawOuter)
        assertSame("contract(OBS/frame): the outer frame comes back after the inner call", innerSawOuter, afterInner)
        assertNull("contract(OBS/frame): no frame after the outer call", observer.current())

        val plain = TestChain(Request.Builder().url("https://example.invalid/api/v2/topics/snapshot").build())
        wrapped.intercept(plain)
        assertEquals("contract(OBS/inactive): an untagged request passes through", 1, plain.proceeds)
        assertEquals("contract(OBS/inactive): and is not an attempt", 5, observer.attempts.size)
    }

    // --- OBS03 -------------------------------------------------------------------------------------------------------

    /**
     * OBS03: the origin comes from the registration the coordinator captured in each callback - activation catalog and tab are
     * not rounds, the round's catalog and tab are - and from nothing the guard read.
     */
    @Test fun obs03_originalRoundDoesNotComeFromPermitPresence() = obsTest {
        val observer = GraphSendObservation("probe")
        val (f, c) = round(observer)
        val readsBeforeOrigin = f.permitReads
        val marks = listOf(c.activationCatalog, c.activationTab, c.roundCatalog, c.roundTab).map {
            GraphSendObservation.originOf(TopicUseContext(it.callback))
        }
        assertEquals("contract(OBS/origin): keys", listOf("catalog", "usd/1d", "catalog", "usd/1d"), marks.map { it.key })
        assertEquals("contract(OBS/round-origin): round flags", listOf(false, false, true, true), marks.map { it.automatic })
        assertEquals("contract(OBS/origin): four registrations", 4, marks.map { it.requestId }.toSet().size)
        assertTrue("contract(OBS/origin): the round's own fence", marks.drop(2).all { it.fence == f.fence })
        assertTrue("contract(OBS/origin): the round's own lifetime", marks.drop(2).all { it.lifetime == f.lifetime() })
        assertEquals("contract(OBS/origin): reading an origin reads no permit", readsBeforeOrigin, f.permitReads)
        assertTrue("contract(OBS/inactive): origin lookup opens no frame", observer.attempts.isEmpty())
        assertTrue("precondition(OBS/round): no coordinator failure", f.failures.isEmpty())
    }

    // --- OBS04 -------------------------------------------------------------------------------------------------------

    /**
     * OBS04: a round issued under an open permit, sent after the permit closed, is refused at the final guard: one read, the
     * closed permit, nothing proceeds. A non-round send under the same closed permit goes and reads nothing.
     */
    @Test fun obs04_closedRoundIsRefusedAtFinalGuard() = obsTest {
        val observer = GraphSendObservation("probe")
        val (f, c) = round(observer)
        val wrapped = observer.wrap(guard())
        f.permit = permit(checkNotNull(f.fence), automatic = false, revision = 2)

        val roundChain = TestChain(graphRequest(TopicUseContext(c.roundTab.callback))) {
            observer.beforeHeader()
        }
        val refused = runCatching { wrapped.intercept(roundChain) }.exceptionOrNull()
        val roundAttempt = observer.attempts.single()
        assertTrue("contract(OBS/origin): the round's origin: ${roundAttempt.originFault}", roundAttempt.origin?.automatic == true)
        assertEquals("contract(OBS/guard-read): one read at the final guard", 1, roundAttempt.reads.size)
        assertEquals("contract(OBS/guard-read): the closed permit", false, roundAttempt.reads.single()?.automatic)
        assertTrue("contract(OBS/round-permit): refused: $refused", refused is WithheldUseIOException)
        assertEquals("contract(OBS/round-permit): nothing proceeds", 0, roundChain.proceeds)
        assertFalse("contract(OBS/round-permit): never past the guard", roundAttempt.guardPassed)

        val fence = checkNotNull(f.fence)
        val refusals = listOf(
            "no permit" to null,
            "another fence" to permit(sessionFence(grant = 9), automatic = true, revision = 3),
            "re-approved without a connection" to TopicGraphRecoveryPermit("session", 4, fence, 1L, true, null, null, true),
            "re-approved on another lifetime" to
                TopicGraphRecoveryPermit("session", 5, fence, 1L, true, 5L, TopicUseLifetime(fence.grant, 99), true)
        )
        for ((name, refusing) in refusals) {
            f.permit = refusing
            val chain = TestChain(graphRequest(TopicUseContext(c.roundTab.callback))) { observer.beforeHeader() }
            val thrown = runCatching { wrapped.intercept(chain) }.exceptionOrNull()
            val attempt = observer.attempts.last()
            assertEquals("contract(OBS/guard-read): $name: one read", 1, attempt.reads.size)
            assertSame("contract(OBS/guard-read): $name: the permit read is the one supplied", refusing, attempt.reads.single())
            assertTrue("contract(OBS/round-permit): $name: refused: $thrown", thrown is WithheldUseIOException)
            assertTrue("contract(OBS/round-permit): $name: nothing proceeds", chain.proceeds == 0 && !attempt.guardPassed)
        }
        f.permit = TopicGraphRecoveryPermit("session", 6, fence, 1L, true, 5L, f.lifetime(), true)
        val ownConnection = TestChain(graphRequest(TopicUseContext(c.roundTab.callback))) { observer.beforeHeader() }
        wrapped.intercept(ownConnection)
        assertTrue(
            "contract(OBS/round-permit): re-approved on its own live connection goes",
            ownConnection.proceeds == 1 && observer.attempts.last().guardPassed && observer.attempts.last().reads.size == 1
        )

        // A fresh fixture whose 3m activation tab is still in flight: a live non-round registration under the closed permit.
        val g = Fixture(this, observer).also { opened += it }
        g.permit = permit(checkNotNull(g.fence), automatic = false)
        g.autoCatalog = { it.catalog.complete(ok(catalog())) }
        g.coordinator.start(); runCurrent()
        g.coordinator.onActivated(USD_3M); runCurrent()
        val nonRound = g.tabs(USD_3M).singleOrNull()
        assertNotNull("precondition(OBS/nonround): one 3m activation tab in flight", nonRound)
        val plainChain = TestChain(graphRequest(TopicUseContext(nonRound!!.callback), path = "/api/v2/graph/tab?tab=usd&period=3m")) {
            observer.beforeHeader()
        }
        wrapped.intercept(plainChain)
        val plainAttempt = observer.attempts.last()
        assertEquals("precondition(OBS/nonround): a non-round 3m send", false, plainAttempt.origin?.automatic)
        assertEquals("contract(OBS/guard-read): a non-round send reads no permit", 0, plainAttempt.reads.size)
        assertTrue("contract(OBS/delegate): and goes", plainAttempt.guardPassed && plainChain.proceeds == 1)
    }

    // --- OBS05 -------------------------------------------------------------------------------------------------------

    /**
     * OBS05: the guard reads the open permit; downstream, before the header is made, the permit closes. The receipt keeps the
     * guard's own read - the open permit, the same object - and a live read downstream adds nothing to it.
     */
    @Test fun obs05_headerUsesTheGuardReadAcrossWithdrawal() = obsTest {
        val observer = GraphSendObservation("probe")
        val (f, c) = round(observer)
        val wrapped = observer.wrap(guard())
        val open = checkNotNull(f.permit)
        val closed = permit(checkNotNull(f.fence), automatic = false, revision = 2)
        var header: String? = null
        var join: String? = null
        var live: TopicGraphRecoveryPermit? = null
        val supplierCallsBefore = f.permitReads
        val chain = TestChain(graphRequest(TopicUseContext(c.roundTab.callback))) { request ->
            val attempt = observer.beforeHeader()
            f.permit = closed
            live = f.observed.require()()
            val stamped = observer.stamp(request, attempt)
            header = stamped.header(GraphSendObservation.PERMIT_HEADER)
            join = stamped.header(GraphSendObservation.JOIN_HEADER)
        }
        wrapped.intercept(chain)
        val attempt = observer.attempts.single()
        assertTrue("precondition(OBS/same-read): the round went through its guard", attempt.guardPassed && chain.proceeds == 1)
        assertEquals("contract(OBS/same-read): one guard read", 1, attempt.reads.size)
        assertSame("contract(OBS/same-read): the guard's own permit object", open, attempt.reads.single())
        assertEquals("contract(OBS/same-read): the header from the guard's read", "open", header)
        assertEquals("contract(OBS/same-read): the join id of that attempt", observer.joinHeader(attempt), join)
        assertEquals("contract(OBS/same-read): the facts a hook reads", GraphSendObservation.SendFacts(true, true, false), observer.factsForHeader(join))
        assertSame("precondition(OBS/same-read): a live read downstream sees the closed permit", closed, live)
        assertEquals("contract(OBS/same-read): only the guard and the deliberate downstream probe read the supplier",
            2, f.permitReads - supplierCallsBefore)
    }

    // --- OBS06 -------------------------------------------------------------------------------------------------------

    /**
     * OBS06: each exchange is its own attempt. (a) OkHttp's real repeat of a 503 with Retry-After: 0 enters the guard again with the
     * same context and send; a second call this test makes itself with send 2 stands in for a replay (a hand-made boundary input).
     * (b) AuthenticatedTransport's real 401 replay through the production client chain: the transport's own send 2 of the same
     * context. (c) The direct 421 -> 503 -> refused chain on the real guard (TopicUseHttpBoundaryTest:362's inputs; a coalesced
     * HTTP/2 421 is not reproduced). (d) A re-entry after the guard threw. Every entry gets a new id and a per-send ordinal, a join
     * header an earlier exchange left is replaced, and the frame is gone after each call. (e) A real round callback on one context,
     * entered four times (sends 1, 1, 2, 2) with a fresh permit supplied each time (a hand-made boundary input, review r1 R2): every
     * entry reads the supplier once and records that entry's own permit, never an earlier one; the closed fourth is refused.
     */
    @Test fun obs06_eachFollowupAndReplayGetsItsOwnAttempt() {
        val observer = GraphSendObservation("probe")
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val client = OkHttpClient.Builder()
                .addNetworkInterceptor(observer.wrap(guard()))
                .addNetworkInterceptor { chain -> chain.proceed(observer.stamp(chain.request(), observer.beforeHeader())) }
                .build()
            val context = TopicUseContext { true }
            fun call(send: Int, stale: String?): Int {
                val builder = Request.Builder().url(server.url("/api/v2/graph/tab?tab=usd&period=1d"))
                    .tag(TopicUseTag::class.java, TopicUseTag(SNAPSHOT, send, context))
                if (stale != null) builder.header(GraphSendObservation.JOIN_HEADER, stale)
                return client.newCall(builder.build()).execute().use { it.code }
            }
            assertEquals("precondition(OBS/exchange): the repeated first send ends 200", 200, call(1, "s0/999"))
            assertEquals("precondition(OBS/exchange): the hand-made send 2 ends 200", 200, call(2, null))
            val received = (1..3).map { server.takeRequest(5, TimeUnit.SECONDS)?.getHeader(GraphSendObservation.JOIN_HEADER) }
            val attempts = observer.attempts.toList()
            assertEquals("contract(OBS/exchange): three guard entries", 3, attempts.size)
            assertEquals("contract(OBS/exchange): sends", listOf(1, 1, 2), attempts.map { it.send })
            assertEquals("contract(OBS/exchange): ordinals per send", listOf(1, 2, 1), attempts.map { it.ordinal })
            assertEquals("contract(OBS/exchange): one id per entry, as the server got them", attempts.map { observer.joinHeader(it) }, received)
            assertEquals("contract(OBS/exchange): distinct ids", 3, received.toSet().size)
            assertTrue("contract(OBS/exchange): the stale id was replaced", "s0/999" !in received)
            assertNull("contract(OBS/frame): no frame after the calls", observer.current())

            // (b) the transport's real 401 replay: send 1 answered 401, the refresh, then the transport's own send 2
            val source = Source()
            val provider = AuthTokenProvider(source, orders = AccessOrderSequence())
            val replayer = OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .addInterceptor(AuthSnapshotInterceptor(provider))
                .addNetworkInterceptor(observer.wrap(TopicUseNetworkInterceptor(provider)))
                .addNetworkInterceptor { chain -> chain.proceed(observer.stamp(chain.request(), observer.beforeHeader())) }
                .build()
            val api = AuthenticatedApiClient(
                Retrofit.Builder().baseUrl(server.url("/")).client(replayer)
                    .addConverterFactory(NetworkModule.provideWireJson().asConverterFactory("application/json".toMediaType())).build()
                    .create(AuthenticatedApiService::class.java),
                AuthenticatedTransport(provider, admitted = { true }),
                NetworkModule.provideWireJson()
            )
            server.enqueue(MockResponse().setResponseCode(401).setHeader("Retry-After", "5").setBody("""{"detail":"expired"}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody(GRAPH_TAB))
            val before = observer.attempts.size
            val status = runBlocking { AuthenticatedGraphV2Fetcher(api).tab(SNAPSHOT, USD_1D) { true }.statusCode }
            assertEquals("precondition(OBS/exchange): the replay ends 200", 200, status)
            assertEquals("precondition(OBS/exchange): the transport refreshed once", 1, source.refreshes)
            val replayed = observer.attempts.drop(before)
            val replayHeaders = (1..2).map { server.takeRequest(5, TimeUnit.SECONDS)?.getHeader(GraphSendObservation.JOIN_HEADER) }
            assertEquals("contract(OBS/exchange): the 401 and its replay are two entries", 2, replayed.size)
            assertSame("contract(OBS/exchange): of one logical read", replayed[0].context, replayed[1].context)
            assertEquals("contract(OBS/exchange): the transport's sends", listOf(1, 2), replayed.map { it.send })
            assertEquals("contract(OBS/exchange): each send's first entry", listOf(1, 1), replayed.map { it.ordinal })
            assertEquals("contract(OBS/exchange): one id per entry, as the server got them", replayed.map { observer.joinHeader(it) }, replayHeaders)
            assertNull("contract(OBS/frame): no frame after the replay", observer.current())
        } finally {
            server.shutdown()
        }

        // (c) the direct 421 -> 503 -> refused chain on the real guard
        val chainObserver = GraphSendObservation("probe")
        val wrapped = chainObserver.wrap(guard())
        val answers = ArrayDeque(listOf(true, true, false))
        val request = graphRequest(TopicUseContext { answers.removeFirst() })
        wrapped.intercept(TestChain(request, 421, "120") { chainObserver.beforeHeader() }).close()
        wrapped.intercept(TestChain(request, 503, "0") { chainObserver.beforeHeader() }).close()
        val refused = runCatching { wrapped.intercept(TestChain(request) { chainObserver.beforeHeader() }) }.exceptionOrNull()
        val chained = chainObserver.attempts.toList()
        assertTrue("precondition(OBS/exchange): the third exchange was refused: $refused", refused is WithheldUseIOException)
        assertEquals("contract(OBS/exchange): three entries of one send", listOf(1, 1, 1), chained.map { it.send })
        assertEquals("contract(OBS/exchange): ordinals in order", listOf(1, 2, 3), chained.map { it.ordinal })
        assertEquals("contract(OBS/exchange): the first two went, the refused one did not", listOf(true, true, false), chained.map { it.guardPassed })
        assertEquals("contract(OBS/exchange): three distinct ids", 3, chained.map { it.id }.toSet().size)
        assertNull("contract(OBS/frame): no frame after the chain", chainObserver.current())

        // (d) a re-entry after the guard threw
        val againObserver = GraphSendObservation("probe")
        val againWrapped = againObserver.wrap(guard())
        val transient = IllegalStateException("transient")
        var throwNext = true
        val again = graphRequest(TopicUseContext { if (throwNext) { throwNext = false; throw transient } else true })
        val first = runCatching { againWrapped.intercept(TestChain(again) { againObserver.beforeHeader() }) }.exceptionOrNull()
        assertSame("precondition(OBS/exchange): the first entry threw the guard's own failure", transient, first)
        assertNull("contract(OBS/frame): no frame after the throw", againObserver.current())
        var stamped: Request? = null
        againWrapped.intercept(TestChain(again) { stamped = againObserver.stamp(it, againObserver.beforeHeader()) })
        val (thrownEntry, secondEntry) = againObserver.attempts.toList()
        assertEquals("contract(OBS/exchange): the thrown entry did not go", false, thrownEntry.guardPassed)
        assertEquals("contract(OBS/exchange): the re-entry is the same send's second entry", 2, secondEntry.ordinal)
        assertTrue("contract(OBS/exchange): and goes", secondEntry.guardPassed && secondEntry.id != thrownEntry.id)
        assertEquals("contract(OBS/exchange): stamped with the re-entry's id", againObserver.joinHeader(secondEntry),
            stamped?.header(GraphSendObservation.JOIN_HEADER))
        assertNull("contract(OBS/frame): no frame after the re-entry", againObserver.current())

        // BOUNDARY_INPUT: a real round callback, repeated context/send entries, and a fresh permit for each entry.
        obsTest {
            val permitObserver = GraphSendObservation("exchange-read")
            val (fixture, callbacks) = round(permitObserver)
            val context = TopicUseContext(callbacks.roundTab.callback)
            val observedGuard = permitObserver.wrap(guard())
            val reads = mutableListOf<TopicGraphRecoveryPermit?>()
            for ((index, send) in listOf(1, 1, 2, 2).withIndex()) {
                val supplied = permit(checkNotNull(fixture.fence), automatic = index < 3, revision = 10L + index)
                fixture.permit = supplied
                val before = fixture.permitReads
                val chain = TestChain(graphRequest(context, send)) { permitObserver.beforeHeader() }
                val result = runCatching { observedGuard.intercept(chain) }
                result.getOrNull()?.close()
                val entry = permitObserver.attempts.last()
                assertSame("contract(OBS/exchange): the same logical context", context, entry.context)
                assertEquals("contract(OBS/exchange): one fresh supplier call per entry", 1, fixture.permitReads - before)
                assertEquals("contract(OBS/exchange): one guard read per entry", 1, entry.reads.size)
                assertSame("contract(OBS/exchange): this entry's supplied permit", supplied, entry.reads.single())
                assertTrue("contract(OBS/exchange): no earlier entry's permit", reads.none { it === entry.reads.single() })
                reads += entry.reads.single()
                if (index < 3) {
                    assertTrue("contract(OBS/exchange): the open entry goes", result.isSuccess && entry.guardPassed && chain.proceeds == 1)
                } else {
                    assertTrue("contract(OBS/exchange): withdrawal is read on re-entry",
                        result.exceptionOrNull() is WithheldUseIOException && !entry.guardPassed && chain.proceeds == 0)
                }
                assertNull("contract(OBS/frame): the entry closed its frame", permitObserver.current())
            }
            assertEquals("contract(OBS/exchange): per-send ordinals", listOf(1, 2, 1, 2), permitObserver.attempts.map { it.ordinal })
            assertEquals("contract(OBS/exchange): fresh attempt ids", 4, permitObserver.attempts.map { it.id }.toSet().size)
        }
    }

    // --- OBS07 -------------------------------------------------------------------------------------------------------

    private class Built(val observer: GraphSendObservation) {
        private var next = 0L
        fun attempt(
            automatic: Boolean?,
            reads: Int,
            passed: Boolean = true,
            outcome: String? = "response 200",
            key: String = "usd/1d",
            read: TopicGraphRecoveryPermit? = permit(sessionFence(), automatic = true)
        ): GraphSendObservation.Attempt {
            val id = ++next
            // The same types and values a real registration carries, so a sound read admits it (r47).
            val origin = automatic?.let { GraphSendObservation.OriginMark(Any(), id, key, it, sessionFence(), TopicUseLifetime(sessionFence().grant, 0)) }
            val attempt = GraphSendObservation.Attempt(id, TopicUseContext { true }, 1, 1, origin, if (origin == null) "no registration" else null)
            repeat(reads) { attempt.reads += read }
            attempt.guardPassed = passed
            attempt.outcome = outcome
            observer.attempts += attempt
            return attempt
        }

        fun receive(attempt: GraphSendObservation.Attempt, kind: String = "tab") = observer.recordServerHeader(observer.joinHeader(attempt), kind)
    }

    private fun case(expected: String, apply: Built.() -> Unit): Pair<String, Built.() -> Unit> = expected to apply

    /** A complete pair - one round and one non-round send, each received once - with the case applied on top. */
    private fun completeWith(case: Built.() -> Unit): List<String> = Built(GraphSendObservation("probe")).run {
        receive(attempt(automatic = true, reads = 1))
        receive(attempt(automatic = false, reads = 0))
        case()
        observer.problems()
    }

    /**
     * OBS07: the completeness validator refuses every missing or forged join and every receipt whose guard evidence does not
     * match its origin; the facts a hook reads come from the receipt alone. Hand-built receipts: validator branches only.
     */
    @Test fun obs07_missingOrForgedServerJoinsFail() {
        assertEquals("contract(OBS/complete): a complete pair has no problem", emptyList<String>(), completeWith {})
        val cases = listOf(
            case("without a join header") { observer.recordServerHeader(null, "tab") },
            case("foreign or unknown join header s0/1") { observer.recordServerHeader("s0/1", "tab") },
            case("foreign or unknown join header") { observer.recordServerHeader("${observer.session}/99", "tab") },
            case("twice") { receive(observer.attempts.first()) },
            case("made 0 guard reads") { receive(attempt(automatic = true, reads = 0)) },
            case("made 2 guard reads") { receive(attempt(automatic = true, reads = 2)) },
            case("non-round attempt") { receive(attempt(automatic = false, reads = 1)) },
            case("has no origin") { receive(attempt(automatic = null, reads = 0)) },
            case("without passing its guard") { receive(attempt(automatic = false, reads = 0, passed = false)) },
            case("received as tab") { receive(attempt(automatic = false, reads = 0, key = "catalog"), "tab") },
            case("answered without a server receipt") { attempt(automatic = false, reads = 0) },
            case("does not admit it") { receive(attempt(automatic = true, reads = 1, read = permit(sessionFence(), automatic = false))) },
            case("does not admit it") { receive(attempt(automatic = true, reads = 1, read = permit(sessionFence(grant = 9), automatic = true))) },
            case("does not admit it") {
                receive(attempt(automatic = true, reads = 1, read = TopicGraphRecoveryPermit("session", 1, sessionFence(), 1L, true, null, null, true)))
            },
            case("does not admit it") {
                receive(attempt(automatic = true, reads = 1,
                    read = TopicGraphRecoveryPermit("session", 1, sessionFence(), 1L, true, 5L, TopicUseLifetime(sessionFence().grant, 99), true)))
            },
            case("has no origin") { attempt(automatic = null, reads = 0, outcome = "java.io.IOException") },
            case("does not admit it") { attempt(automatic = true, reads = 1, outcome = "java.io.IOException", read = permit(sessionFence(), automatic = false)) },
            case("more than ${GraphSendObservation.CAPACITY} attempts") { repeat(GraphSendObservation.CAPACITY) { attempt(automatic = false, reads = 0, outcome = null) } }
        )
        for ((expected, case) in cases) {
            val problems = completeWith(case)
            assertTrue("contract(OBS/complete): \"$expected\" is reported: $problems", problems.any { expected in it })
        }
        val noRound = Built(GraphSendObservation("probe")).run { receive(attempt(automatic = false, reads = 0)); observer.problems() }
        assertTrue("contract(OBS/complete): no round joined is reported: $noRound", noRound.any { "no round send joined" in it })
        val noPlain = Built(GraphSendObservation("probe")).run { receive(attempt(automatic = true, reads = 1)); observer.problems() }
        assertTrue("contract(OBS/complete): no non-round joined is reported: $noPlain", noPlain.any { "no non-round send joined" in it })
        val withdrawn = completeWith { attempt(automatic = false, reads = 0, outcome = "java.io.IOException") }
        val ownConnection = completeWith {
            receive(attempt(automatic = true, reads = 1,
                read = TopicGraphRecoveryPermit("session", 1, sessionFence(), 1L, true, 5L, TopicUseLifetime(sessionFence().grant, 0), true)))
        }
        assertEquals("contract(OBS/complete): a re-approved read on its own live connection admits", emptyList<String>(), ownConnection)
        assertEquals("contract(OBS/complete): an exchange that ended in an exception may miss the server", emptyList<String>(), withdrawn)

        val facts = Built(GraphSendObservation("probe"))
        val open = facts.attempt(automatic = true, reads = 1)
        val closedRound = facts.attempt(automatic = true, reads = 0)
        val plain = facts.attempt(automatic = false, reads = 0)
        val o = facts.observer
        assertEquals("contract(OBS/join): an open round", GraphSendObservation.SendFacts(true, true, false), o.factsForHeader(o.joinHeader(open)))
        assertEquals("contract(OBS/join): a round without its read", GraphSendObservation.SendFacts(true, false, false), o.factsForHeader(o.joinHeader(closedRound)))
        assertEquals("contract(OBS/join): a non-round send", GraphSendObservation.SendFacts(false, null, true), o.factsForHeader(o.joinHeader(plain)))
        val foreignFence = facts.attempt(automatic = true, reads = 1, read = permit(sessionFence(grant = 9), automatic = true))
        assertEquals("contract(OBS/join): a round read of another fence", GraphSendObservation.SendFacts(true, false, false),
            o.factsForHeader(o.joinHeader(foreignFence)))
        assertEquals("contract(OBS/join): a missing join", GraphSendObservation.SendFacts(null, null, false), o.factsForHeader(null))
        assertEquals("contract(OBS/join): a foreign join", GraphSendObservation.SendFacts(null, null, false), o.factsForHeader("s0/${open.id}"))
        val thrown = runCatching { Built(GraphSendObservation("row-x")).observer.assertComplete("row-x") }.exceptionOrNull()
        assertTrue("contract(OBS/complete): an empty observation fails with its prefix: $thrown",
            thrown is AssertionError && thrown.message!!.startsWith("contract(OBS/row-x/complete): "))
    }

    // --- OBS08 -------------------------------------------------------------------------------------------------------

    /**
     * OBS08: the acceptance harness's own client built without an observer keeps the original guard instance at its original index
     * and its original header step (one delegate and one permitAtSend read for a graph request, none for a non-graph one, no join
     * header; review r1 R3); an unobserved rig hands its builder its own slot; the port takes one observed rig and only that rig releases it; a
     * frame is never seen on another thread and a read there is not recorded; stamping without an attempt leaves the request.
     * A selected wrapper beside these still records its attempt, so a dead observer cannot pass.
     */
    @Test fun obs08_unselectedClientsKeepTheOriginalPath() {
        PremiumGraphCutoverAcceptanceTest().withInactiveObservationClientContract { client, guard, permitCalls ->
            assertEquals("contract(OBS/inactive): original network chain size", 3, client.networkInterceptors.size)
            assertSame("contract(OBS/inactive): the original guard at its original index", guard, client.networkInterceptors[0])
            val header = client.networkInterceptors[1]
            var sent: Request? = null
            val graph = graphRequest(TopicUseContext { true })
            val graphChain = TestChain(graph) { sent = it }
            header.intercept(graphChain).close()
            assertEquals("contract(OBS/inactive): one original header delegate", 1, graphChain.proceeds)
            assertEquals("contract(OBS/inactive): one original permitAtSend call", 1, permitCalls.get())
            assertEquals("contract(OBS/inactive): the original permit header", "closed", sent?.header(GraphSendObservation.PERMIT_HEADER))
            assertNull("contract(OBS/inactive): no observation header", sent?.header(GraphSendObservation.JOIN_HEADER))
            val nonGraph = graphRequest(TopicUseContext { true }, path = "/api/v2/topics/snapshot")
            val nonGraphChain = TestChain(nonGraph) { sent = it }
            header.intercept(nonGraphChain).close()
            assertEquals("contract(OBS/inactive): one non-graph delegate", 1, nonGraphChain.proceeds)
            assertEquals("contract(OBS/inactive): no non-graph permit call", 1, permitCalls.get())
            assertSame("contract(OBS/inactive): non-graph request unchanged", nonGraph, sent)
        }
        var calls = 0
        val original = LateBound<() -> TopicGraphRecoveryPermit?>("graph permit").also { slot -> slot.set { calls++; null } }
        assertSame("contract(OBS/inactive): an unobserved rig's builder gets its own slot", original, GraphSendObservation.slotOrOriginal(null, original))
        val observer = GraphSendObservation("probe")
        assertNotSame("precondition(OBS/inactive): an observed rig's builder gets the recording slot", original,
            GraphSendObservation.slotOrOriginal(observer, original))

        val port = GraphSendObservation.Port()
        val other = GraphSendObservation("other")
        port.bind(observer)
        assertTrue("contract(OBS/inactive): a second bind is refused", runCatching { port.bind(other) }.exceptionOrNull() is IllegalStateException)
        port.release(other)
        assertSame("contract(OBS/inactive): only the binder releases", observer, port.current())
        port.release(observer)
        assertNull("contract(OBS/inactive): released", port.current())

        val slot = observer.recordingSlot(original)
        var elsewhere: GraphSendObservation.Attempt? = null
        var elsewhereRan = false
        val wrapped = observer.wrap(Interceptor { chain ->
            thread {
                elsewhere = observer.current()
                slot.require()()
                elsewhereRan = true
            }.join()
            TestChain(chain.request()).proceed(chain.request())
        })
        wrapped.intercept(TestChain(graphRequest(TopicUseContext { true })))
        assertTrue("precondition(OBS/inactive): the other thread ran", elsewhereRan)
        assertNull("contract(OBS/inactive): a frame is not seen on another thread", elsewhere)
        assertEquals("contract(OBS/inactive): a selected wrapper records its attempt", 1, observer.attempts.size)
        assertEquals("contract(OBS/inactive): a read on another thread is not this attempt's", 0, observer.attempts.single().reads.size)
        assertEquals("contract(OBS/single-read): that read still reached the original once", 1, calls)
        val request = graphRequest(TopicUseContext { true })
        assertSame("contract(OBS/inactive): no attempt, no stamp", request, observer.stamp(request, null))
    }

    // --- OBS09 -------------------------------------------------------------------------------------------------------

    /**
     * OBS09 (r47 R47-01): an observed row's end runs the body, then finish once and the release with the final check whatever
     * finish did. Every combination of body, finish and close failing: each runs once in order, the port is released, the first
     * failure stays first and the later ones are suppressed onto it.
     */
    @Test fun obs09_anObservedRowEndsWithTheReleaseAndTheCheckWhateverFinishDid() = runBlocking {
        for (sharing in listOf("distinct", "body-finish", "body-close", "finish-close", "all"))
        for (bodyFails in listOf(false, true)) for (finishFails in listOf(false, true)) for (closeFails in listOf(false, true)) {
            val label = "sharing=$sharing body=$bodyFails finish=$finishFails close=$closeFails"
            val bodyFailure = IllegalStateException("body")
            val finishFailure = if (sharing == "body-finish" || sharing == "all") bodyFailure else IllegalStateException("finish")
            val closeFailure: Throwable = when (sharing) {
                "body-close", "all" -> bodyFailure
                "finish-close" -> finishFailure
                else -> AssertionError("close")
            }
            val port = GraphSendObservation.Port()
            val observer = GraphSendObservation("row")
            port.bind(observer)
            val calls = mutableListOf<String>()
            val thrown = runCatching {
                GraphSendObservation.runRow(
                    { calls += "body"; if (bodyFails) throw bodyFailure },
                    { calls += "finish"; if (finishFails) throw finishFailure },
                    { calls += "close"; port.release(observer); if (closeFails) throw closeFailure }
                )
            }.exceptionOrNull()
            assertEquals("contract(OBS/row-end): $label: body, finish and close once each, in order", listOf("body", "finish", "close"), calls)
            assertNull("contract(OBS/row-end): $label: the port is released", port.current())
            val first = when {
                bodyFails -> bodyFailure
                finishFails -> finishFailure
                closeFails -> closeFailure
                else -> null
            }
            assertSame("contract(OBS/row-end): $label: the first failure stays first", first, thrown)
            val later = when {
                bodyFails -> listOfNotNull(finishFailure.takeIf { finishFails }, closeFailure.takeIf { closeFails })
                finishFails -> listOfNotNull(closeFailure.takeIf { closeFails })
                else -> emptyList()
            }
            assertEquals("contract(OBS/row-end): $label: the later failures are suppressed onto it",
                later.filterNot { it === first }, thrown?.suppressed?.toList().orEmpty())
        }
    }
}
