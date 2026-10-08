package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessEnd
import com.jay.fxi.data.entitlements.TopicAccessEndReason
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicGraphCandidate
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.data.remote.TopicGraphPath
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAttribution
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.data.remote.dto.GraphV2CatalogPeriod
import com.jay.fxi.data.remote.dto.GraphV2CatalogResponse
import com.jay.fxi.data.remote.dto.GraphV2CatalogTab
import com.jay.fxi.data.remote.dto.GraphV2Metadata
import com.jay.fxi.data.remote.dto.GraphV2Point
import com.jay.fxi.data.remote.dto.GraphV2Provenance
import com.jay.fxi.data.remote.dto.GraphV2Range
import com.jay.fxi.data.remote.dto.GraphV2Series
import com.jay.fxi.data.remote.dto.GraphV2TabResponse
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.time.AppClock
import java.io.IOException
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 RT03b-1b contract r2 (JVM): the coordinator's 1d recovery budget, rows P01-P16 of the agreed design
 * R4c/S4 rt03b1b_api_agreed.r4 (r3, agreed with Codex, plus the contract review's spec fixes and rows), on the RT03b split
 * and decisions of rt03b_api_agreed.r3. The design's D row (no production reach, no new timer or collector) is checked at
 * commit review, not here. Oracles: ANDROID_V2_PLAN.md :1331-1334 (recovery demands survive cache hits, failures and
 * cancellation; only supplied buckets are released) and the RT01 retry contract (rt01_design_codex.r2 P6: an initial round
 * and at most five more, 3/6/12/24/48 s after a completion, the shared Retry-After floor and 429 jitter, no reset by a P4
 * round trip, a re-approval, a capability re-issue or a lost catalog, the demand kept at the cap).
 *
 * Rules (the observable part of the agreed design):
 *  - Only a coordinator with a recorder absorbs a 1d key's cold ladder into a budget per (data scope, tab). Without a
 *    recorder, and for other periods, the cold, flip and midnight owners stay as they are (existing contracts).
 *  - A budget is created by an admitted success or retryable completion of the active key's request when a demand remains:
 *    the entry has no confirmed online stamp, or the recorder's query is anything but None. That request is the first round
 *    (counted when it was sent); the next round is due 3 s after it (plus the jitter after a 429).
 *  - A due round is issued at most once per round from the coordinator's timer, past the shared floor, only while its key is
 *    active and supported. A fresh entry does not stop it while a closed demand remains. Before sending, the demand is read
 *    again: None removes the budget without a request; Unreadable holds the budget until the context or the activation
 *    changes. A budget at six counted rounds whose open round was never counted stops instead of issuing it again.
 *  - A round is counted when its first request (tab or catalog) passes the send boundary, always on the budget that issued
 *    it; it has at most one tab and one catalog. A request withdrawn before it is sent frees its slot in the same round. A
 *    sent request withdrawn later spends its slot and moves no rung. A request of a round not yet counted is withdrawn at
 *    the boundary once its budget counts six (since RT03b-2b no row reaches it: a newer round waits for an older round's
 *    catalog). A round's tab withdrawn without an applicable completion in the same context and activation holds the
 *    budget there.
 *  - An applicable completion of the round's tab settles it: a success or retryable failure moves the rung (a join with an
 *    outside request does not) and sets the next deadline from that completion; a terminal or diagnostic answer stops the
 *    budget. Six counted rounds or six settled rungs stop it with the demand kept. A stopped budget issues nothing more
 *    until a valid RT05 trigger (RT03b-3a).
 *  - While a budget waits, unforced requests of its key are not sent and its key starts no independent flip; a stopped
 *    budget still lets outside requests go, which neither restart the ladder nor start a flip of their own.
 *  - The budget survives a temporarily absent context or fence, a new lifetime, grant or capability of the same data scope,
 *    a key change and an unsupported catalog. A real USER end and a non-null other data scope discard it; retireScopes
 *    removes a selected scope's budget (REMOVED even when it was the only holding).
 *  - A failed event handling stops every budget and withdraws every unsent request a budget issued, even one whose budget
 *    was already removed as satisfied; an outside request a round joined goes on. A satisfied budget's unsent catalog
 *    otherwise goes on, counted only for its own round, never for a newer budget of the key.
 *  - The test clock fails a row whose timer re-arms more than 1000 times within one virtual millisecond.
 *
 * S4 RT03b-2a (rows K01-K06, rt03b2_api_agreed.r3 §2.1): with a recorder, a context change within the data scope of the
 * last context since the last USER end asks for no catalog, now or deferred to the floor; another scope, a USER end and the
 * first context keep the initial request. A synchronization without a context keeps that scope only for its own scope, so a
 * scope seen first without a context stays initial. Without a recorder a context change still asks, as before.
 *
 * S4 RT03b-2b (rows C01, C03, C04, C06-C09, rt03b2_api_agreed.r4 §2.2-§2.3): a round whose catalog is absent or past its
 * TTL sends no tab until a catalog check ends. It joins the catalog already out without sending, counting or binding it,
 * or sends its own once. The check ends with an applicable completion of that catalog - an adopted one lets the tab
 * capture, a failed one lets it go without (or with a kept older catalog) - or with the round's catalog slot already spent
 * while no catalog is out; it is not repeated in the round. The tab then goes at the next Wake, no earlier than the
 * round's deadline, after the catalog is published and the demand read again. A catalog withdrawn before an applicable
 * completion - including the failures that withdraw a tab (offline, identity change) - holds the budget in that context
 * and activation, like a withdrawn tab.
 * While it waits, the round still joins an outside tab already out (1b), which can settle it before its own catalog goes.
 * Other periods do not wait. Known limitation, not pinned: a check ended by an adopted catalog is not reopened when a
 * same-scope context change drops that catalog, so that round's tab goes without captures.
 *
 * S4 RT03b-3a (rows T01-T08, rt03b3_api_agreed.r2 §3): an RT05 trigger is valid only with a sequence above every one handled
 * before, for the published fence and the synchronized context's fence and lifetime, with an admitted use; its sequence
 * counts even when invalid. A valid trigger acts on the current scope's budgets: it releases an Unreadable hold of a waiting
 * one (no reset), resumes a paused one with its counters and deadline (a new cycle once a counter reached six), opens a new
 * six-round cycle in a new budget for an exhausted one and leaves a terminal one; a failed event pauses only a waiting
 * budget. With no budget for the active absorbed key, a remaining demand opens one due at once (an Unreadable one opens it
 * held). A trigger sends nothing itself. A first round that joins an outside tab keeps rung 0 and uses the first gap; an old
 * cycle's departed tab still out is joined the same way (its answer settles that round as a join).
 *
 * Not here: the P7 permit (RT03b-3b), the unconfirmed 200 of a cache-port coordinator (P09,
 * in GraphV2RequestCoordinatorReGateTest). The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphRecoveryBudgetContractTest {

    private companion object {
        /** 2026-10-05 12:00 KST. */
        val NOON: Instant = Instant.parse("2026-10-05T03:00:00Z")
        val USD_1D = GraphKey("usd", GraphPeriod.ONE_DAY)
        val USD_3M = GraphKey("usd", GraphPeriod.THREE_MONTHS)
        val JPY_1D = GraphKey("jpy", GraphPeriod.ONE_DAY)
        val B1: Instant = Instant.parse("2026-10-05T02:20:00Z")
        val B2: Instant = Instant.parse("2026-10-05T02:30:00Z")
        val SCOPE = GraphDataScope("u1", "e1")
        val KB = GraphObservationSeriesKey(SCOPE, "kb.usd")

        fun sessionFence(epoch: String? = "e1", grant: Long = 7) =
            TopicSessionFence(AuthIdentityFence("u1", 1L), epoch, TopicGrantToken(grant))
    }

    private class Sent(val kind: String, val key: GraphKey?, val admittedAtSend: Boolean, val atMs: Long) {
        val tab = CompletableDeferred<AuthenticatedHttpResponse<GraphV2TabResponse>>()
        val catalog = CompletableDeferred<AuthenticatedHttpResponse<GraphV2CatalogResponse>>()
    }

    /** GraphV2RecoveryRequestContractTest's fixture with one access supplier for all three readers, plus capture control. */
    private class Fixture(test: TestScope, withRecorder: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        private val base = test.testScheduler.currentTime
        var fence: TopicSessionFence? = sessionFence()
        var allowed = true
        var invalidations = 0L
        var userEnd: Long? = null
        var jitter: Duration = Duration.ZERO
        /** The recorder's admission alone; the coordinator's protected admission stays open. */
        var recorderOpen = true
        /** The coordinator's next access read throws once, failing that event's handling. */
        var accessThrowsOnce = false
        /** The recorder's next catalog read throws once; only its demand query reads it while a tab completes. */
        var catalogThrowsOnce = false
        /** Credential captures started, numbered from 1, and their virtual times. */
        var captures = 0
        val captureTimes = mutableListOf<Long>()
        /** Capture number to the throwable it ends with, after any hold. */
        var captureFails: (Int) -> Throwable? = { null }
        private val holds = mutableMapOf<Int, CompletableDeferred<Unit>>()
        var autoTab: ((Sent) -> Unit)? = null
        var autoCatalog: ((Sent) -> Unit)? = null
        val sent = mutableListOf<Sent>()
        val failures = mutableListOf<Throwable>()
        private val now: () -> Long = { test.testScheduler.currentTime - base }

        /** Capture [n] waits until the returned deferred completes. */
        fun hold(n: Int): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { holds[n] = it }

        val uses = object : TopicUseAuthority {
            override fun acquire(fence: TopicSessionFence): TopicUseLifetime? =
                if (allowed && fence == this@Fixture.fence) TopicUseLifetime(fence.grant, invalidations) else null

            override fun admits(lifetime: TopicUseLifetime): Boolean =
                allowed && this@Fixture.fence?.grant == lifetime.grant && invalidations == lifetime.invalidations
        }

        val owners = object : GraphOwnerSource {
            override fun currentIdentity(): AuthIdentityFence? = fence?.identity

            override suspend fun capture(expected: AuthIdentityFence): AuthSnapshot {
                val n = ++captures
                captureTimes += now()
                holds[n]?.await()
                captureFails(n)?.let { throw it }
                if (currentIdentity() != expected) throw AuthIdentityChangedException()
                return AuthSnapshot(expected.uid, expected.authGeneration, "token")
            }
        }

        val fetcher = object : GraphV2Fetching {
            override suspend fun catalog(
                owner: AuthSnapshot,
                useAdmitted: () -> Boolean
            ): AuthenticatedHttpResponse<GraphV2CatalogResponse> {
                val s = Sent("catalog", null, useAdmitted(), now())
                sent += s
                autoCatalog?.invoke(s)
                return s.catalog.await()
            }

            override suspend fun tab(
                owner: AuthSnapshot,
                key: GraphKey,
                useAdmitted: () -> Boolean
            ): AuthenticatedHttpResponse<GraphV2TabResponse> {
                val s = Sent("tab", key, useAdmitted(), now())
                sent += s
                autoTab?.invoke(s)
                return s.tab.await()
            }
        }

        /** The virtual millisecond at which the clock was read over 1000 times: a timer that re-arms without progress. */
        var loopAt: Long? = null
        private var readsAt = -1L
        private var reads = 0
        val clock = AppClock {
            val at = now()
            if (at != readsAt) {
                readsAt = at
                reads = 0
            }
            // The throw fails the event's handling, which ends the loop; budgetTest reports it with the row's name.
            if (++reads > 1_000) {
                loopAt = at
                throw IllegalStateException("timer loop at $at ms")
            }
            NOON + at.milliseconds
        }

        val snapshot: () -> TopicAccessSnapshot = {
            TopicAccessSnapshot(
                revision = 1L,
                facts = TopicAccessFacts.NONE.copy(
                    token = fence?.grant,
                    binding = EntitlementsIdentity("u1", 1L),
                    tokenStanding = allowed,
                    userBlocks = emptySet<TopicAccessBlock>(),
                    capabilityBlocks = emptySet()
                ),
                userInvalidations = invalidations,
                lastUserEnd = userEnd?.let { TopicAccessEnd(it, TopicAccessEndReason.IDENTITY_CHANGED, null, null, null) },
                lastCapabilityEnd = null
            )
        }

        lateinit var coordinator: GraphV2RequestCoordinator

        val gate = GraphV2AccessGate({ owners.currentIdentity() }, { fence }, snapshot, { recorderOpen })
        val recorder = GraphRecorder(
            scope, MutableStateFlow(1L), snapshot, { fence },
            {
                if (catalogThrowsOnce) {
                    catalogThrowsOnce = false
                    throw IllegalStateException("catalog")
                }
                coordinator.state.value.catalog
            },
            gate, clock
        )

        init {
            coordinator = GraphV2RequestCoordinator(
                fetcher = fetcher,
                owners = owners,
                currentAccessFence = { fence },
                uses = uses,
                protectedAdmission = { true },
                accessSnapshot = {
                    if (accessThrowsOnce) {
                        accessThrowsOnce = false
                        throw IllegalStateException("access")
                    }
                    snapshot()
                },
                scope = scope,
                clock = clock,
                rateLimitJitter = { jitter },
                onEventFailure = { failures += it },
                recorder = if (withRecorder) recorder else null
            )
        }

        val state get() = coordinator.state.value
        fun tabs(key: GraphKey = USD_1D) = sent.filter { it.kind == "tab" && it.key == key }
        fun tabTimes(key: GraphKey = USD_1D) = tabs(key).filter { it.admittedAtSend }.map { it.atMs }
        fun catalogTimes() = sent.filter { it.kind == "catalog" && it.admittedAtSend }.map { it.atMs }
        fun series(key: GraphObservationSeriesKey) = recorder.state.value.series.getValue(key)

        /** Answers tab sends in order; the last step repeats. */
        fun tabSequence(vararg steps: (Sent) -> Unit) {
            var next = 0
            autoTab = { s -> steps[minOf(next++, steps.size - 1)](s) }
        }

        /** Answers catalog sends in order; the last step repeats. */
        fun catalogSequence(vararg steps: (Sent) -> Unit) {
            var next = 0
            autoCatalog = { s -> steps[minOf(next++, steps.size - 1)](s) }
        }

        fun lifetime() = TopicUseLifetime(checkNotNull(fence).grant, invalidations)

        fun quote(source: String, at: Instant) = TopicGraphInput.Observations(
            1L, "fx:usd-krw", TopicGraphPath.WS,
            TopicUseAttribution(Any(), checkNotNull(fence), 1L, lifetime()), 1L,
            listOf(TopicGraphCandidate.Quote(source, "usd-krw", 1390.0, at, null))
        )

        /** The gate refuses the quote, so the existing series keeps a HANDOVER_LOSS for its bucket. */
        fun lose(source: String, at: Instant) {
            recorderOpen = false
            recorder.observe(quote(source, at))
            recorderOpen = true
        }

        fun close() = scope.cancel()
    }

    // --- responses -------------------------------------------------------------------------------------------------

    private fun <T> ok(body: T) = AuthenticatedHttpResponse(200, Headers.headersOf(), body, null, byteArrayOf(1))

    private fun <T> status(code: Int, retryAfter: String? = null): AuthenticatedHttpResponse<T> {
        val headers = if (retryAfter == null) Headers.headersOf() else Headers.headersOf("Retry-After", retryAfter)
        return AuthenticatedHttpResponse(
            code, headers, null,
            AuthenticatedHttpFailure(code, headers, byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP),
            byteArrayOf(1)
        )
    }

    /** usd lists kb and hana for 1d and kb for 3m; jpy lists kb for 1d. [usdOneDay] false drops usd's 1d. */
    private fun catalog(usdOneDay: Boolean = true, ttlSeconds: Int = 172800) = GraphV2CatalogResponse(
        tabs = listOf(
            GraphV2CatalogTab(
                "usd", "usd", emptyMap(), listOfNotNull(
                    ("1d" to GraphV2CatalogPeriod(listOf("kb.usd", "hana.usd"), listOf("kb.usd"))).takeIf { usdOneDay },
                    "3m" to GraphV2CatalogPeriod(listOf("kb.usd"), listOf("kb.usd"))
                ).toMap()
            ),
            GraphV2CatalogTab("jpy", "jpy", emptyMap(), mapOf("1d" to GraphV2CatalogPeriod(listOf("kb.jpy"), listOf("kb.jpy"))))
        ),
        version = "2026-05-27",
        supportedPeriods = listOf("1w", "3m", "1y"),
        cacheTtlSeconds = ttlSeconds
    )

    /** A 10-minute usd 1d tab whose series carry closed points at the given starts. */
    private fun dayTab(points: Map<String, List<Instant>>) = GraphV2TabResponse(
        tab = "usd",
        period = GraphPeriod.ONE_DAY.code,
        series = points.map { (id, starts) ->
            GraphV2Series(
                id, id, "krw", "KRW", 2, starts.map { GraphV2Point(it, 1390.0, "x") }, GraphV2Provenance(false, emptyList()), null
            )
        },
        metadata = GraphV2Metadata(NOON - 1.hours, "10min", GraphV2Range("2026-10-04", "2026-10-05"))
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

    private val fail503: (Sent) -> Unit = { it.tab.complete(status(503)) }
    private val catalog503: (Sent) -> Unit = { it.catalog.complete(status(503)) }
    private val catalogOk: (Sent) -> Unit = { it.catalog.complete(ok(catalog())) }
    /** A usd 1d answer supplying kb at B1 only, so kb's closed B2 demand stays. */
    private val partial: (Sent) -> Unit = { it.tab.complete(ok(dayTab(mapOf("kb.usd" to listOf(B1))))) }
    /** Answers by key: usd 3m succeeds, every 1d request fails with 503. */
    private val byKey503: (Sent) -> Unit = {
        if (it.key == USD_3M) it.tab.complete(ok(threeMonthTab())) else it.tab.complete(status(503))
    }

    private fun secs(vararg s: Int) = s.map { it * 1000L }

    private fun TestScope.step(d: Duration) {
        advanceTimeBy(d.inWholeMilliseconds)
        runCurrent()
    }

    private val opened = mutableListOf<Fixture>()

    private fun budgetTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        try {
            body()
            opened.forEach { assertEquals("a timer loop", null, it.loopAt) }
        } finally {
            opened.forEach { it.close() }
            opened.clear()
        }
    }

    /** Started, with the catalog adopted through a settled usd 3m request; kb exists with closed losses at B1 and B2. */
    private fun TestScope.ready(withRecorder: Boolean = true, catalogTtl: Int = 172800): Fixture = Fixture(this, withRecorder).also { f ->
        opened += f
        f.autoCatalog = { it.catalog.complete(ok(catalog(ttlSeconds = catalogTtl))) }
        f.coordinator.start(); runCurrent()
        f.coordinator.onActivated(USD_3M); runCurrent()
        f.tabs(USD_3M).single().tab.complete(ok(threeMonthTab())); runCurrent()
        assertNotNull("premise: catalog adopted", f.state.catalog)
        f.recorder.observe(f.quote("kb", NOON - 5.seconds))
        f.lose("kb", B1 + 60.seconds)
        f.lose("kb", B2 + 60.seconds)
        assertTrue("premise: kb has losses at B1 and B2", B1 in f.series(KB).pending && B2 in f.series(KB).pending)
        // The rows number captures from their own first request.
        f.captures = 0
        f.captureTimes.clear()
    }

    /** Started with a recorder and no catalog; every catalog fails with 503 unless the row says otherwise. */
    private fun TestScope.bare(): Fixture = Fixture(this, withRecorder = true).also { f ->
        opened += f
        f.autoCatalog = catalog503
        f.coordinator.start(); runCurrent()
    }

    /** The use is withheld and released again under a new lifetime of the same fence (a P4 round trip). */
    private fun TestScope.roundTrip(f: Fixture) {
        f.allowed = false
        f.coordinator.onContextChanged(); runCurrent()
        f.allowed = true
        f.invalidations++
        f.coordinator.onContextChanged(); runCurrent()
    }

    // --- S4 RT03b-2a: catalogs on a context change (rows K01-K06, rt03b2_api_agreed.r3 §2.1) ---------------------------

    /**
     * K01: a context change within the last data scope - a P4 round trip, a new grant, a scope gone and back - asks for no
     * catalog; a later Refresh does. Without a recorder a context change still asks, as before.
     */
    @Test fun K01_aSameScopeContextChangeAsksNoCatalog() = budgetTest {
        val f = ready()
        val before = f.catalogTimes().size
        roundTrip(f)
        f.fence = sessionFence(grant = 8); f.coordinator.onContextChanged(); runCurrent()
        f.fence = null; f.coordinator.onContextChanged(); runCurrent()
        f.fence = sessionFence(grant = 8); f.coordinator.onContextChanged(); runCurrent()
        // Two steps without a context in a row (the use withheld, then no fence) keep the scope too.
        f.allowed = false; f.coordinator.onContextChanged(); runCurrent()
        f.fence = null; f.coordinator.onContextChanged(); runCurrent()
        f.allowed = true; f.fence = sessionFence(grant = 9); f.coordinator.onContextChanged(); runCurrent()
        assertNull("premise: the catalog was dropped", f.state.catalog)
        assertEquals("no catalog from a same-scope change", before, f.catalogTimes().size)
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("a refresh asks for it", before + 1, f.catalogTimes().size)

        val none = ready(withRecorder = false)
        val n = none.catalogTimes().size
        roundTrip(none)
        assertEquals("without a recorder a context change still asks", n + 1, none.catalogTimes().size)
    }

    /**
     * K02: another non-null scope, a USER end - also one read while no scope is published - and the first context keep the
     * initial request with its catalog.
     */
    @Test fun K02_aNewScopeAUserEndAndTheFirstContextAskForTheCatalog() = budgetTest {
        // Grant renewals change the context in one synchronization, so they show which scope was kept.
        val f = ready()
        val n0 = f.catalogTimes().size
        f.fence = sessionFence(epoch = "e2"); f.coordinator.onContextChanged(); runCurrent()
        assertEquals("another scope", n0 + 1, f.catalogTimes().size)
        f.fence = sessionFence(epoch = "e2", grant = 8); f.coordinator.onContextChanged(); runCurrent()
        assertEquals("then a grant renewal within it", n0 + 1, f.catalogTimes().size)
        f.fence = sessionFence(epoch = "e1"); f.coordinator.onContextChanged(); runCurrent()
        assertEquals("an earlier scope again is another scope", n0 + 2, f.catalogTimes().size)
        f.userEnd = 1L; f.coordinator.onContextChanged(); runCurrent()
        assertEquals("a USER end in the same scope", n0 + 3, f.catalogTimes().size)
        f.fence = sessionFence(epoch = "e1", grant = 9); f.coordinator.onContextChanged(); runCurrent()
        assertEquals("the scope is kept again after a USER end", n0 + 3, f.catalogTimes().size)
        f.fence = null; f.coordinator.onContextChanged(); runCurrent()
        assertNull("premise: no scope before the USER end", f.state.dataScope)
        f.userEnd = 2L; f.coordinator.onContextChanged(); runCurrent()
        f.fence = sessionFence(epoch = "e1"); f.coordinator.onContextChanged(); runCurrent()
        assertEquals("a USER end read while no scope resets the earlier same scope", n0 + 4, f.catalogTimes().size)
        f.fence = sessionFence(epoch = "e1", grant = 10); f.coordinator.onContextChanged(); runCurrent()
        assertEquals("and that scope is kept", n0 + 4, f.catalogTimes().size)

        val g = Fixture(this, withRecorder = true).also { opened += it }
        g.autoCatalog = catalogOk
        g.fence = null
        g.coordinator.start(); runCurrent()
        g.coordinator.onActivated(USD_3M); runCurrent()
        assertEquals("premise: nothing without a context", 0, g.catalogTimes().size)
        g.fence = sessionFence(); g.coordinator.onContextChanged(); runCurrent()
        assertEquals("the first context", 1, g.catalogTimes().size)
        g.fence = sessionFence(grant = 8); g.coordinator.onContextChanged(); runCurrent()
        assertEquals("the first context's scope is kept", 1, g.catalogTimes().size)
    }

    /**
     * K03: a same-scope context change under the floor defers its tab only: the Wake at the floor sends no catalog. An
     * unforced Refresh deferred after it under the same floor brings the catalog back.
     */
    @Test fun K03_aSameScopeChangeUnderTheFloorDefersNoCatalog() = budgetTest {
        val f = shortCatalog()
        f.catalogSequence(
            { it.catalog.complete(ok(catalog(ttlSeconds = 1))) },
            { it.catalog.complete(status(429, "5")) },
            { it.catalog.complete(ok(catalog(ttlSeconds = 1))) }
        )
        f.autoTab = { it.tab.complete(ok(threeMonthTab())) }
        f.coordinator.onActivated(USD_3M); runCurrent()
        step(1_500.milliseconds)
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("premise: the refresh's catalog met a 429 (floor at 6.5 s)", listOf(0L, 1_500L), f.catalogTimes())
        step(1_500.milliseconds)
        // The scope gone and back clears the entry, so the same-scope change needs the tab (deferred to the floor).
        f.fence = null; f.coordinator.onContextChanged(); runCurrent()
        f.fence = sessionFence(); f.coordinator.onContextChanged(); runCurrent()
        assertEquals("premise: nothing under the floor", listOf(0L, 1_500L), f.tabTimes(USD_3M))
        step(4.seconds)
        assertEquals("the deferred tab at the floor", listOf(0L, 1_500L, 6_500L), f.tabTimes(USD_3M))
        assertEquals("without a catalog", listOf(0L, 1_500L), f.catalogTimes())
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("a refresh asks for it", listOf(0L, 1_500L, 7_000L), f.catalogTimes())

        // An unforced Refresh deferred after it under the same floor brings the catalog back into the deferred demand.
        val g = shortCatalog()
        g.catalogSequence(
            { it.catalog.complete(ok(catalog(ttlSeconds = 1))) },
            { it.catalog.complete(status(429, "5")) },
            { it.catalog.complete(ok(catalog(ttlSeconds = 1))) }
        )
        g.autoTab = { it.tab.complete(ok(threeMonthTab())) }
        g.coordinator.onActivated(USD_3M); runCurrent()
        step(1_500.milliseconds)
        g.coordinator.onRefreshRequested(force = false); runCurrent()
        step(1_500.milliseconds)
        g.fence = null; g.coordinator.onContextChanged(); runCurrent()
        g.fence = sessionFence(); g.coordinator.onContextChanged(); runCurrent()
        step(500.milliseconds)
        g.coordinator.onRefreshRequested(force = false); runCurrent()
        step(3_500.milliseconds)
        assertEquals("the merged demand at the floor", listOf(0L, 1_500L, 6_500L), g.tabTimes(USD_3M))
        assertEquals("with the Refresh's catalog", listOf(0L, 1_500L, 6_500L), g.catalogTimes())

        // With the tab still fresh, a round trip under the floor defers nothing at all.
        val h = ready()
        h.coordinator.onRefreshRequested(force = true); runCurrent()
        h.tabs(USD_3M).last().tab.complete(status(429, "2")); runCurrent()
        roundTrip(h)
        val c = h.catalogTimes()
        step(3.seconds)
        assertEquals("no catalog at the floor", c, h.catalogTimes())
    }

    /** K04: repeated round trips ask for no catalog; the budget's next round asks for one in its own slot. */
    @Test fun K04_roundTripsAskNoCatalogAndARoundUsesItsSlot() = budgetTest {
        val f = ready()
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        val n0 = f.catalogTimes().size
        step(1.seconds)
        repeat(5) { roundTrip(f) }
        assertEquals("no catalog storm", n0, f.catalogTimes().size)
        step(2.seconds)
        assertEquals("the round's own catalog", n0 + 1, f.catalogTimes().size)
        assertEquals("at the round", 3_000L, f.catalogTimes().last())
        assertEquals(secs(0, 3), f.tabTimes())
    }

    /**
     * K05: only the context change notification is silent. An Activate after it, and a Refresh or a budget round's Wake that
     * synchronizes the change itself, ask for the catalog under their own conditions.
     */
    @Test fun K05_otherTriggersStillAskForTheCatalog() = budgetTest {
        val f = ready()
        val n = f.catalogTimes().size
        roundTrip(f)
        f.coordinator.onActivated(USD_3M); runCurrent()
        assertEquals("an Activate after a silent change", n + 1, f.catalogTimes().size)

        val g = ready()
        val m = g.catalogTimes().size
        g.fence = sessionFence(grant = 8); g.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("a Refresh that reads the change itself", m + 1, g.catalogTimes().size)
        g.coordinator.onContextChanged(); runCurrent()
        assertEquals("the later notification finds nothing changed", m + 1, g.catalogTimes().size)

        val h = ready()
        h.tabSequence(fail503)
        h.coordinator.onActivated(USD_1D); runCurrent()
        val k = h.catalogTimes().size
        step(1.seconds)
        h.invalidations++
        step(2.seconds)
        assertEquals("a round whose Wake reads the change", k + 1, h.catalogTimes().size)
        assertEquals(secs(0, 3), h.tabTimes())
    }

    /**
     * K06: only a synchronization that obtains a context records its scope. A scope first seen while the use is withheld, a
     * USER end read while it is withheld, and another scope seen only without a context leave the next context initial. A
     * withheld step of the kept scope keeps it (K01).
     */
    @Test fun K06_aScopeSeenWithoutAContextLeavesTheNextContextInitial() = budgetTest {
        val f = Fixture(this, withRecorder = true).also { opened += it }
        f.autoCatalog = catalogOk
        f.allowed = false
        f.coordinator.start(); runCurrent()
        f.coordinator.onActivated(USD_3M); runCurrent()
        assertEquals("premise: the scope is published", SCOPE, f.state.dataScope)
        assertNull("premise: without a context", f.state.source)
        assertEquals("premise: nothing without a context", 0, f.catalogTimes().size)
        f.allowed = true; f.coordinator.onContextChanged(); runCurrent()
        assertEquals("the first context of a scope seen without one", 1, f.catalogTimes().size)
        f.fence = sessionFence(grant = 8); f.coordinator.onContextChanged(); runCurrent()
        assertEquals("then that scope is kept", 1, f.catalogTimes().size)

        val g = ready()
        val n = g.catalogTimes().size
        g.allowed = false; g.userEnd = 1L; g.coordinator.onContextChanged(); runCurrent()
        assertEquals("premise: the scope is published", SCOPE, g.state.dataScope)
        assertNull("premise: the USER end read without a context", g.state.source)
        g.allowed = true; g.invalidations++; g.coordinator.onContextChanged(); runCurrent()
        assertEquals("a USER end read while the use is withheld", n + 1, g.catalogTimes().size)
        g.fence = sessionFence(grant = 8); g.coordinator.onContextChanged(); runCurrent()
        assertEquals("then that scope is kept", n + 1, g.catalogTimes().size)

        val h = ready()
        val m = h.catalogTimes().size
        h.allowed = false; h.fence = sessionFence(epoch = "e2"); h.coordinator.onContextChanged(); runCurrent()
        assertNull("premise: e2 seen without a context", h.state.source)
        h.allowed = true; h.coordinator.onContextChanged(); runCurrent()
        assertEquals("another scope seen first without a context", m + 1, h.catalogTimes().size)
        // e2 is the kept scope; e1 seen without a context in between makes the next e2 context initial again.
        h.allowed = false; h.fence = sessionFence(epoch = "e1"); h.coordinator.onContextChanged(); runCurrent()
        assertNull("premise: e1 seen without a context", h.state.source)
        h.allowed = true; h.fence = sessionFence(epoch = "e2", grant = 9); h.coordinator.onContextChanged(); runCurrent()
        assertEquals("the kept scope again after another seen without a context", m + 2, h.catalogTimes().size)
        h.fence = sessionFence(epoch = "e2", grant = 10); h.coordinator.onContextChanged(); runCurrent()
        assertEquals("then that scope is kept", m + 2, h.catalogTimes().size)
    }

    // --- S4 RT03b-2b: a round's catalog first (rows C01, C03, C04, C06-C09, rt03b2_api_agreed.r4 §2.2-§2.3) ----------

    /**
     * ready(), with the catalog then dropped by a same-scope context change that asks for none (RT03b-2a). Catalogs are
     * answered by the row; the next Activate asks for one with its tab.
     */
    private fun TestScope.dropped(): Fixture = ready().also { f ->
        f.autoCatalog = null
        f.fence = sessionFence(grant = 8); f.coordinator.onContextChanged(); runCurrent()
        assertNull("premise: the catalog was dropped", f.state.catalog)
    }

    private fun Fixture.catalogs() = sent.filter { it.kind == "catalog" }

    /** A usd 1d answer supplying kb at B1 and B2: a captured request meets the closed demand. */
    private val full: (Sent) -> Unit = { it.tab.complete(ok(dayTab(mapOf("kb.usd" to listOf(B1, B2))))) }

    /**
     * C01 (Q01): a first 1d request out without a catalog cannot meet the demand. (a) A catalog adopted while it is out lets
     * the next round, 3 s after its completion, go with captures and meet it. (b) A catalog still out at the round's
     * deadline is joined: the round's tab goes right after its adoption, or at the deadline if adopted earlier.
     */
    @Test fun C01_aRoundAfterALateCatalogGoesWithCaptures() = budgetTest {
        val f = dropped()
        f.coordinator.onActivated(USD_1D); runCurrent()
        val activation = f.catalogTimes().size
        step(1.seconds)
        f.catalogs().last().catalog.complete(ok(catalog())); runCurrent()
        step(1.seconds)
        f.tabs().single().tab.complete(ok(dayTab(mapOf("kb.usd" to listOf(B1, B2))))); runCurrent()
        assertTrue("premise: the first tab had no captures", B2 in f.series(KB).pending)
        f.autoTab = full
        step(3.seconds)
        assertEquals("(a) the round 3 s after the first completion", secs(0, 5), f.tabTimes())
        assertTrue("(a) with captures", B1 !in f.series(KB).pending && B2 !in f.series(KB).pending)
        step(500.seconds)
        assertEquals(secs(0, 5), f.tabTimes())
        assertEquals("(a) a fresh catalog: the round asks for none", activation, f.catalogTimes().size)

        for (adoptAt in listOf(5_000L, 1_000L)) {
            val label = "(b) adopted at $adoptAt ms"
            val g = dropped()
            g.coordinator.onActivated(USD_1D); runCurrent()
            val n = g.catalogTimes().size
            g.tabs().single().tab.complete(status(503)); runCurrent()
            g.autoTab = full
            step(adoptAt.milliseconds)
            assertEquals("$label premise: nothing before the adoption", listOf(0L), g.tabTimes())
            g.catalogs().last().catalog.complete(ok(catalog())); runCurrent()
            assertEquals("$label: the round sent no catalog of its own", n, g.catalogTimes().size)
            step(5.seconds)
            assertEquals(label, listOf(0L, maxOf(adoptAt, 3_000L)), g.tabTimes())
            assertTrue("$label: with captures", B2 !in g.series(KB).pending)
        }
    }

    /**
     * C03: a round that needs a catalog sends it alone; its tab waits for that catalog's completion and then goes with
     * captures. The round counts once.
     */
    @Test fun C03_aRoundSendsItsCatalogFirst() = budgetTest {
        val f = dropped()
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        val n = f.catalogTimes().size
        f.catalogs().last().catalog.complete(status(503)); runCurrent()
        step(3.seconds)
        assertEquals("the round's catalog alone", 3_000L, f.catalogTimes().last())
        assertEquals(n + 1, f.catalogTimes().size)
        assertEquals("its tab waits", listOf(0L), f.tabTimes())
        step(2.seconds)
        assertEquals(listOf(0L), f.tabTimes())
        f.autoTab = full
        f.catalogs().last().catalog.complete(ok(catalog())); runCurrent()
        assertEquals("then its tab", secs(0, 5), f.tabTimes())
        assertTrue("with captures", B2 !in f.series(KB).pending)
        step(500.seconds)
        assertEquals(secs(0, 5), f.tabTimes())
        assertEquals(n + 1, f.catalogTimes().size)
    }

    /**
     * C04: the catalog check ends once per round. (a) The round's own catalog failing: its tab goes at once without
     * captures and the round asks for no second catalog; the next round asks again. (b) A joined catalog failing: the round
     * sends no catalog of its own. (c) The round's catalog capture failing before its send: no catalog goes, the tab goes
     * at once, and the round counts once, at the tab. (d) An existing catalog past its TTL whose refresh fails stays, so
     * the round's tab captures with it.
     */
    @Test fun C04_aFailedCatalogEndsTheRoundsCheck() = budgetTest {
        val f = dropped()
        f.tabSequence(fail503)
        f.catalogSequence(catalog503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        val n = f.catalogTimes().size
        f.autoTab = { it.tab.complete(ok(dayTab(mapOf("kb.usd" to listOf(B1, B2))))) }
        step(3.seconds)
        assertEquals("(a) the round's catalog and then its tab", secs(0, 3), f.tabTimes())
        assertTrue("(a) without captures", B2 in f.series(KB).pending)
        f.autoTab = fail503
        step(500.seconds)
        assertEquals("(a) one catalog per round", n + 5, f.catalogTimes().size)
        assertEquals(secs(0, 3, 9, 21, 45, 93), f.tabTimes())

        val g = dropped()
        g.coordinator.onActivated(USD_1D); runCurrent()
        val m = g.catalogTimes().size
        g.tabs().single().tab.complete(status(503)); runCurrent()
        g.autoTab = fail503
        step(4.seconds)
        g.catalogs().last().catalog.complete(status(503)); runCurrent()
        assertEquals("(b) the round's tab after the joined catalog failed", secs(0, 4), g.tabTimes())
        assertEquals("(b) no catalog of its own", m, g.catalogTimes().size)
        step(6.seconds)
        assertEquals("(b) the next round, 6 s after, asks again", 10_000L, g.catalogTimes().last())

        val h = dropped()
        h.tabSequence(fail503)
        h.catalogSequence(catalog503)
        h.coordinator.onActivated(USD_1D); runCurrent()
        val k = h.catalogTimes().size
        // Captures: 1-2 the Activate's catalog and tab, 3 the round's catalog.
        h.captureFails = { c -> if (c == 3) IOException("reset") else null }
        step(3.seconds)
        assertEquals("(c) no catalog went", k, h.catalogTimes().size)
        assertEquals("(c) the tab at once", secs(0, 3), h.tabTimes())
        h.captureFails = { null }
        step(500.seconds)
        assertEquals("(c) counted once per round: six tabs", secs(0, 3, 9, 21, 45, 93), h.tabTimes())
        assertEquals("(c) the later rounds' catalogs", k + 4, h.catalogTimes().size)

        val e = ready(catalogTtl = 1)
        e.autoCatalog = catalog503
        e.tabSequence(fail503, full)
        val c = e.catalogTimes().size
        e.coordinator.onActivated(USD_1D); runCurrent()
        step(3.seconds)
        assertEquals("(d) the round asked for the stale catalog first", c + 1, e.catalogTimes().size)
        assertEquals(3_000L, e.catalogTimes().last())
        assertNotNull("premise: the stale catalog stays after a failed refresh", e.state.catalog)
        assertEquals(secs(0, 3), e.tabTimes())
        assertTrue("(d) the round's tab captured with it", B2 !in e.series(KB).pending)
    }

    /**
     * C06: only an absorbed 1d round waits for its catalog. A 3m request does not wait for the catalog that is out, and a
     * 1d budget asks for nothing while 3m is active.
     */
    @Test fun C06_aLongPeriodDoesNotWaitForTheCatalog() = budgetTest {
        val f = dropped()
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        f.catalogs().last().catalog.complete(status(503)); runCurrent()
        step(1.seconds)
        f.autoTab = { it.tab.complete(ok(threeMonthTab())) }
        val n = f.catalogTimes().size
        f.coordinator.onActivated(USD_3M); runCurrent()
        assertEquals("3m: its catalog", n + 1, f.catalogTimes().size)
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        assertEquals("a forced 3m tab while that catalog is out", 1_000L, f.tabTimes(USD_3M).last())
        f.catalogs().last().catalog.complete(ok(catalog())); runCurrent()
        step(500.seconds)
        assertEquals("the 1d budget asks for nothing meanwhile", n + 1, f.catalogTimes().size)
        assertEquals(listOf(0L), f.tabTimes())
    }

    /**
     * C07: a round's catalog withdrawn before an applicable completion - at its capture or at its completion, its own or
     * one it joined - holds the budget in that context (no timer loop); after a round trip the round asks for its catalog
     * again, an unsent slot being free. A sent catalog lost in a round trip spends its slot: the round's tab then goes
     * without a catalog. A capture that goes offline withdraws like a tab's (not an applicable failure).
     */
    @Test fun C07_aWithdrawnRoundCatalogHoldsTheBudget() = budgetTest {
        val f = dropped()
        f.tabSequence(fail503)
        f.catalogSequence(catalog503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        val n = f.catalogTimes().size
        // Captures: 1-2 the Activate's catalog and tab, 3 the round's catalog, withdrawn by the use going away unseen.
        val held = f.hold(3)
        step(3.seconds)
        f.allowed = false
        held.complete(Unit); runCurrent()
        f.allowed = true
        step(30.seconds)
        assertEquals("held: nothing more in the same context", n, f.catalogTimes().size)
        assertEquals(listOf(0L), f.tabTimes())
        roundTrip(f)
        assertEquals("the round's catalog again after a round trip", n + 1, f.catalogTimes().size)
        assertEquals("then its tab", secs(0, 33), f.tabTimes())

        val g = dropped()
        g.tabSequence(fail503)
        g.coordinator.onActivated(USD_1D); runCurrent()
        g.catalogs().last().catalog.complete(status(503)); runCurrent()
        val m = g.catalogTimes().size
        step(3.seconds)
        assertEquals("premise: the round's catalog went", m + 1, g.catalogTimes().size)
        step(1.seconds)
        roundTrip(g)
        assertEquals("a spent catalog slot: no catalog again", m + 1, g.catalogTimes().size)
        assertEquals("the round's tab without one", secs(0, 4), g.tabTimes())

        // (c) A joined catalog withdrawn at its completion holds the round too; joining spent no slot.
        val h = dropped()
        h.tabSequence(fail503)
        h.coordinator.onActivated(USD_1D); runCurrent()
        val k = h.catalogTimes().size
        step(3.seconds)
        assertEquals("premise: the round joined the Activate's catalog", k, h.catalogTimes().size)
        h.allowed = false
        h.catalogs().last().catalog.complete(ok(catalog())); runCurrent()
        h.allowed = true
        assertNull("premise: withdrawn at its completion, not adopted", h.state.catalog)
        step(30.seconds)
        assertEquals("held", listOf(0L), h.tabTimes())
        h.autoCatalog = catalog503
        roundTrip(h)
        assertEquals("then the round's own catalog", k + 1, h.catalogTimes().size)
        assertEquals(secs(0, 33), h.tabTimes())

        // (d) The failures that withdraw a tab withdraw the round's catalog capture as well: offline or an identity change
        // holds the round, and after a round trip it asks for its catalog again. Captures: 1-2 the Activate's catalog and
        // tab, 3 the round's.
        for (failure in listOf<Throwable>(AuthUnavailableException("offline"), AuthIdentityChangedException())) {
            val label = "(d) ${failure::class.simpleName}"
            val e = dropped()
            e.tabSequence(fail503)
            e.catalogSequence(catalog503)
            e.coordinator.onActivated(USD_1D); runCurrent()
            val q = e.catalogTimes().size
            e.captureFails = { c -> if (c == 3) failure else null }
            step(3.seconds)
            assertEquals("$label premise: the round's catalog capture failed", 3, e.captures)
            step(30.seconds)
            assertEquals("$label held: no tab without its catalog", listOf(0L), e.tabTimes())
            roundTrip(e)
            assertEquals("$label its catalog again", q + 1, e.catalogTimes().size)
            assertEquals(label, secs(0, 33), e.tabTimes())
        }

        // (e) The hold belongs to the withdrawn catalog's own activation. The round's catalog capture is held across a key
        // switch and back; an outside tab's 429 then makes it wait for the floor, and its capture ending under the floor in
        // another activation withdraws it. In the current activation the round is not held: it asks again at the floor.
        // Captures: 1-2 the Activate's catalog and tab, 3 the round's catalog, 4 the forced tab.
        val r = dropped()
        r.tabSequence(fail503)
        r.catalogSequence(catalog503)
        r.coordinator.onActivated(USD_1D); runCurrent()
        val w = r.catalogTimes().size
        val capture = r.hold(3)
        step(3.seconds)
        r.coordinator.onActivated(USD_3M); runCurrent()
        r.coordinator.onActivated(USD_1D); runCurrent()
        r.autoTab = { it.tab.complete(status(429, "2")) }
        r.coordinator.onRefreshRequested(force = true); runCurrent()
        assertEquals("(e) premise: the forced tab met the 429 (floor at 5 s)", secs(0, 3), r.tabTimes())
        r.autoTab = fail503
        capture.complete(Unit); runCurrent()
        assertEquals("(e) premise: the round's catalog was withdrawn unsent", w, r.catalogTimes().size)
        step(2.seconds)
        assertEquals("(e) the round's catalog again at the floor", 5_000L, r.catalogTimes().last())
        assertEquals("(e) then its tab", secs(0, 3, 5), r.tabTimes())
    }

    /**
     * C08: after an applicable catalog completion the round is evaluated again at its Wake, after the catalog is published:
     * a demand the adopted catalog shows to be None removes the budget without a tab; a round of a key no longer active
     * waits until its key is active again.
     */
    @Test fun C08_aRoundReadsItsDemandAgainAfterItsCatalog() = budgetTest {
        val f = bare()
        f.tabSequence({ it.tab.complete(ok(dayTab(emptyMap()))) })
        f.coordinator.onActivated(USD_1D); runCurrent()
        assertNotNull("premise: a fresh entry", f.state.entries.getValue(USD_1D).online200At)
        f.autoCatalog = null
        step(3.seconds)
        assertEquals("premise: the round's catalog alone (CatalogRequired remained)", secs(0, 3), f.catalogTimes())
        assertEquals(listOf(0L), f.tabTimes())
        f.catalogs().last().catalog.complete(ok(catalog())); runCurrent()
        step(500.seconds)
        assertEquals("None once the catalog is published: no tab", listOf(0L), f.tabTimes())
        assertEquals(secs(0, 3), f.catalogTimes())

        val g = dropped()
        g.tabSequence(fail503)
        g.coordinator.onActivated(USD_1D); runCurrent()
        g.catalogs().last().catalog.complete(status(503)); runCurrent()
        step(3.seconds)
        assertEquals("premise: the round's own catalog is out", 3_000L, g.catalogTimes().last())
        g.autoTab = { it.tab.complete(ok(threeMonthTab())) }
        g.coordinator.onActivated(USD_3M); runCurrent()
        g.catalogs().last().catalog.complete(ok(catalog())); runCurrent()
        step(10.seconds)
        assertEquals("not while usd 3m is active", listOf(0L), g.tabTimes())
        g.autoTab = full
        g.coordinator.onActivated(USD_1D); runCurrent()
        assertEquals("the round's tab once its key is active", secs(0, 13), g.tabTimes())
        assertTrue("with captures", B2 !in g.series(KB).pending)
    }

    /**
     * C09: a round whose own catalog slot is spent (sent and then lost) still waits for a catalog already out; but once its
     * check has ended with no catalog out, it does not wait for a later catalog in that round.
     */
    @Test fun C09_aSpentSlotWaitsOnlyForACatalogAlreadyOut() = budgetTest {
        val f = dropped()
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        f.catalogs().last().catalog.complete(status(503)); runCurrent()
        step(3.seconds)
        assertEquals("premise: the round's own catalog went", 3_000L, f.catalogTimes().last())
        step(1.seconds)
        // A round trip whose second half is a Refresh: the sent catalog is lost, and the Refresh asks for a catalog.
        f.allowed = false; f.coordinator.onContextChanged(); runCurrent()
        f.allowed = true; f.invalidations++; f.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("premise: the Refresh's catalog is out", 4_000L, f.catalogTimes().last())
        assertEquals("(a) a spent slot still waits for the catalog out", listOf(0L), f.tabTimes())
        f.autoTab = full
        f.catalogs().last().catalog.complete(ok(catalog())); runCurrent()
        assertEquals("(a) then its tab", secs(0, 4), f.tabTimes())
        assertTrue("(a) with captures", B2 !in f.series(KB).pending)

        // Captures: 1-2 the Activate's catalog and tab, 3 the round's catalog, 4 its tab (offline), 5 the Refresh's catalog.
        val g = dropped()
        g.tabSequence(fail503)
        g.coordinator.onActivated(USD_1D); runCurrent()
        g.catalogs().last().catalog.complete(status(503)); runCurrent()
        step(3.seconds)
        step(1.seconds)
        g.captureFails = { c -> if (c == 4) AuthUnavailableException("offline") else null }
        roundTrip(g)
        assertEquals("premise: the round's tab went offline after its check ended", 4, g.captures)
        assertEquals(listOf(0L), g.tabTimes())
        g.allowed = false; g.coordinator.onContextChanged(); runCurrent()
        g.allowed = true; g.invalidations++; g.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("premise: the Refresh's catalog is out", 4_000L, g.catalogTimes().last())
        assertEquals("(b) the ended check does not wait for it", secs(0, 4), g.tabTimes())
    }

    // --- S4 RT03b-3a: triggers and cycles (rows T01-T08, rt03b3_api_agreed.r2 §3) --------------------------------------

    /** Delivers an RT05 trigger for [f]'s published fence and current use unless the row says otherwise. */
    private fun TestScope.fire(
        f: Fixture,
        sequence: Long,
        fence: TopicSessionFence = checkNotNull(f.fence),
        lifetime: TopicUseLifetime = f.lifetime()
    ) {
        f.coordinator.onRecoveryTrigger(GraphRecoveryTrigger(GraphRecoveryTrigger.Kind.FOREGROUND_RETURN, sequence, fence, lifetime))
        runCurrent()
    }

    /** ready(), with usd 1d's budget exhausted after six failed rounds at 0/3/9/21/45/93 s; the clock stands at 200 s. */
    private fun TestScope.exhausted(): Fixture = ready().also { f ->
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(200.seconds)
        assertEquals("premise: six rounds, then exhausted", secs(0, 3, 9, 21, 45, 93), f.tabTimes())
    }

    /**
     * T01: a trigger is valid only with a sequence above every one handled before, for the published fence and the
     * synchronized context's fence and lifetime, with an admitted use. An invalid one changes nothing, but its sequence
     * still counts: a duplicate or an older one is ignored later.
     */
    @Test fun T01_onlyAValidTriggerActs() = budgetTest {
        val f = exhausted()
        fire(f, 1, fence = sessionFence(grant = 8))
        fire(f, 2, lifetime = TopicUseLifetime(checkNotNull(f.fence).grant, f.invalidations + 1))
        f.allowed = false
        fire(f, 3)
        f.allowed = true
        // Published but not yet synchronized: the context still holds grant 7.
        f.fence = sessionFence(grant = 8)
        fire(f, 4)
        // Each check alone: a regenerated identity with the same grant keeps the lifetime equal and admitted.
        val regenerated = TopicSessionFence(AuthIdentityFence("u1", 2L), "e1", TopicGrantToken(7))
        f.fence = regenerated
        fire(f, 5, fence = sessionFence())
        fire(f, 6)
        f.fence = sessionFence()
        f.invalidations++
        fire(f, 7)
        f.invalidations--
        step(30.seconds)
        assertEquals("invalid triggers open nothing", secs(0, 3, 9, 21, 45, 93), f.tabTimes())
        fire(f, 7)
        assertEquals("a sequence an invalid trigger used is ignored", 6, f.tabTimes().size)
        fire(f, 8)
        assertEquals("a valid one opens a new cycle at once", 230_000L, f.tabTimes().last())
        step(200.seconds)
        assertEquals(secs(0, 3, 9, 21, 45, 93, 230, 233, 239, 251, 275, 323), f.tabTimes())
        fire(f, 8)
        fire(f, 3)
        fire(f, 5)
        step(30.seconds)
        assertEquals("a duplicate or an older sequence is ignored, whatever came between", 12, f.tabTimes().size)
        fire(f, 9)
        assertEquals("the next one opens again", 460_000L, f.tabTimes().last())

        // Without a context the trigger is invalid, and its sequence still counts.
        val g = exhausted()
        g.allowed = false; g.coordinator.onContextChanged(); runCurrent()
        fire(g, 1)
        g.allowed = true; g.coordinator.onContextChanged(); runCurrent()
        val n = g.tabTimes().size
        fire(g, 1)
        step(30.seconds)
        assertEquals("a sequence handled without a context is ignored", n, g.tabTimes().size)
        fire(g, 2)
        assertEquals(230_000L, g.tabTimes().last())
    }

    /**
     * T02: a trigger resumes a budget paused by a failed event with its counters and deadline (a hold left before the pause
     * goes too); the round cut off by the failure is not restored. A paused budget with six counted opens a new cycle in a
     * new budget; its first round joins the cut-off round's tab still out (rung 0, first gap), and the new cycle still sends
     * six counted rounds of its own.
     */
    @Test fun T02_aTriggerResumesAPausedBudget() = budgetTest {
        val f = ready()
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(4.seconds)
        f.accessThrowsOnce = true
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("premise: the handling failed", 1, f.failures.size)
        step(30.seconds)
        assertEquals("premise: paused", secs(0, 3), f.tabTimes())
        fire(f, 1)
        assertEquals("resumed at once: its deadline (9 s) is past", secs(0, 3, 34), f.tabTimes())
        step(200.seconds)
        assertEquals("the remaining rounds of the same budget", secs(0, 3, 34, 46, 70, 118), f.tabTimes())

        val g = ready()
        g.tabSequence(fail503)
        g.coordinator.onActivated(USD_1D); runCurrent()
        step(45.seconds)
        g.autoTab = null
        step(48.seconds)
        assertEquals("premise: the sixth round is out", secs(0, 3, 9, 21, 45, 93), g.tabTimes())
        g.accessThrowsOnce = true
        g.coordinator.onRefreshRequested(force = false); runCurrent()
        step(10.seconds)
        fire(g, 1)
        assertEquals("six counted: the new cycle's first round joins the cut-off tab still out", 6, g.tabTimes().size)
        g.autoTab = fail503
        g.tabs()[5].tab.complete(status(503)); runCurrent()
        step(200.seconds)
        assertEquals("then six rounds of its own, the first 3 s after the joined answer",
            secs(0, 3, 9, 21, 45, 93, 106, 109, 115, 127, 151, 199), g.tabTimes())

        val h = ready()
        h.tabSequence(fail503)
        h.coordinator.onActivated(USD_1D); runCurrent()
        h.captureFails = { n -> if (n == 2) AuthUnavailableException("offline") else null }
        step(3.seconds)
        assertEquals("premise: the round's capture went offline (held)", 2, h.captures)
        h.accessThrowsOnce = true
        h.coordinator.onRefreshRequested(force = false); runCurrent()
        step(30.seconds)
        fire(h, 1)
        assertEquals("the hold left before the pause goes with it", secs(0, 33), h.tabTimes())

        val k = ready()
        k.tabSequence(fail503)
        k.coordinator.onActivated(USD_1D); runCurrent()
        step(4.seconds)
        k.accessThrowsOnce = true
        k.coordinator.onRefreshRequested(force = false); runCurrent()
        fire(k, 1)
        assertEquals("resumed, not before its kept deadline", secs(0, 3), k.tabTimes())
        step(5.seconds)
        assertEquals(secs(0, 3, 9), k.tabTimes())

        // The fifth round's tab is out when the failure cuts it off; the resumed budget's next round joins it.
        val m = ready()
        m.tabSequence(fail503)
        m.coordinator.onActivated(USD_1D); runCurrent()
        step(21.seconds)
        m.autoTab = null
        step(24.seconds)
        assertEquals("premise: the fifth round is out", secs(0, 3, 9, 21, 45), m.tabTimes())
        m.accessThrowsOnce = true
        m.coordinator.onRefreshRequested(force = false); runCurrent()
        step(10.seconds)
        fire(m, 1)
        m.autoTab = fail503
        m.tabs()[4].tab.complete(status(503)); runCurrent()
        step(100.seconds)
        assertEquals("its answer settles the resumed round as a join", secs(0, 3, 9, 21, 45, 79), m.tabTimes())
    }

    /**
     * T03: an exhausted budget opens a new six-round cycle only on a valid trigger; a Refresh or an Activate sends its own
     * request without restarting the ladder.
     */
    @Test fun T03_onlyATriggerOpensANewCycle() = budgetTest {
        val f = exhausted()
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        f.coordinator.onActivated(USD_3M); runCurrent()
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(300.seconds)
        assertEquals("their own requests, no ladder", secs(0, 3, 9, 21, 45, 93, 200, 200), f.tabTimes())
        fire(f, 1)
        step(200.seconds)
        assertEquals(secs(0, 3, 9, 21, 45, 93, 200, 200, 500, 503, 509, 521, 545, 593), f.tabTimes())
    }

    /** T04: a terminal budget is not opened by a trigger, not even after a failed event that pauses waiting budgets. */
    @Test fun T04_aTerminalBudgetStaysClosed() = budgetTest {
        val f = ready()
        f.tabSequence(fail503, { it.tab.complete(status(404)) }, fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(3.seconds)
        assertEquals("premise: the round's 404 stopped it", secs(0, 3), f.tabTimes())
        fire(f, 1)
        step(100.seconds)
        assertEquals(secs(0, 3), f.tabTimes())
        f.accessThrowsOnce = true
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("premise: the handling failed", 1, f.failures.size)
        fire(f, 2)
        step(100.seconds)
        assertEquals("a failure does not turn it into a paused one", secs(0, 3), f.tabTimes())
    }

    /**
     * T05: a trigger does not reset a waiting budget. It releases only an Unreadable hold, so the due round reads its demand
     * again and the budget goes on from its second round; a hold left by a withdrawal stays until the activation changes.
     */
    @Test fun T05_aWaitingBudgetIsNotReset() = budgetTest {
        val f = ready()
        f.tabSequence(partial)
        f.recorderOpen = false
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(60.seconds)
        assertEquals("premise: held at the due round (Unreadable)", listOf(0L), f.tabTimes())
        f.recorderOpen = true
        fire(f, 1)
        assertEquals("released: the second round goes", secs(0, 60), f.tabTimes())
        step(200.seconds)
        assertEquals("the same budget goes on", secs(0, 60, 66, 78, 102, 150), f.tabTimes())

        val g = ready()
        g.tabSequence(fail503)
        g.coordinator.onActivated(USD_1D); runCurrent()
        g.captureFails = { n -> if (n == 2) AuthUnavailableException("offline") else null }
        step(3.seconds)
        assertEquals("premise: the round's capture went offline (held)", 2, g.captures)
        fire(g, 1)
        step(60.seconds)
        assertEquals("a withdrawal's hold stays", 2, g.captures)
        g.coordinator.onActivated(USD_3M); runCurrent()
        g.coordinator.onActivated(USD_1D); runCurrent()
        assertEquals("until the activation changes", secs(0, 63), g.tabTimes())

        // Neither the round out nor the kept deadline is reset.
        val k = ready()
        k.tabSequence(fail503)
        k.coordinator.onActivated(USD_1D); runCurrent()
        k.autoTab = null
        step(3.seconds)
        fire(k, 1)
        k.autoTab = fail503
        k.tabs()[1].tab.complete(status(503)); runCurrent()
        step(1.seconds)
        fire(k, 2)
        assertEquals("neither trigger adds a request", secs(0, 3), k.tabTimes())
        step(5.seconds)
        assertEquals("the round out settles as its own; the kept deadline holds", secs(0, 3, 9), k.tabTimes())

        // A withdrawn catalog's hold is a withdrawal too. Captures: 1-2 the Activate's catalog and tab, 3 the round's.
        val c = dropped()
        c.tabSequence(fail503)
        c.catalogSequence(catalog503)
        c.coordinator.onActivated(USD_1D); runCurrent()
        c.captureFails = { n -> if (n == 3) AuthUnavailableException("offline") else null }
        step(3.seconds)
        assertEquals("premise: the round's catalog capture went offline", 3, c.captures)
        fire(c, 1)
        step(30.seconds)
        assertEquals("a withdrawn catalog's hold stays", 3, c.captures)
    }

    /**
     * T06: with no budget, a trigger opens one for the active absorbed key when a demand remains, its first round due at once;
     * None opens nothing, and an Unreadable demand opens it held until a later trigger. A first round that joins an outside
     * tab keeps rung 0 and uses the first 3 s gap.
     */
    @Test fun T06_aTriggerOpensTheFirstRecovery() = budgetTest {
        val f = ready()
        f.tabSequence({ it.tab.complete(status(404)) }, fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(30.seconds)
        assertEquals("premise: a 404 first answer creates no budget", listOf(0L), f.tabTimes())
        fire(f, 1)
        assertEquals("the first round at once", secs(0, 30), f.tabTimes())
        step(200.seconds)
        assertEquals("six rounds from there", secs(0, 30, 33, 39, 51, 75, 123), f.tabTimes())

        val g = ready()
        g.tabSequence(full)
        g.coordinator.onActivated(USD_1D); runCurrent()
        assertTrue("premise: met at once", B2 !in g.series(KB).pending)
        fire(g, 1)
        step(100.seconds)
        assertEquals("None: nothing", listOf(0L), g.tabTimes())

        val h = ready()
        h.coordinator.onActivated(USD_1D); runCurrent()
        h.coordinator.onActivated(USD_3M); runCurrent()
        h.tabs().single().tab.complete(ok(dayTab(mapOf("kb.usd" to listOf(B1))))); runCurrent()
        h.coordinator.onActivated(USD_1D); runCurrent()
        assertEquals("premise: answered while usd 3m was active, so no budget; fresh", listOf(0L), h.tabTimes())
        h.recorderOpen = false
        fire(h, 1)
        step(30.seconds)
        assertEquals("Unreadable opens it held", listOf(0L), h.tabTimes())
        h.recorderOpen = true
        h.autoTab = partial
        fire(h, 2)
        assertEquals("a later trigger releases the hold", secs(0, 30), h.tabTimes())

        // The held budget exists: its key's own unforced requests wait, and a new activation releases the round.
        val k = ready()
        k.coordinator.onActivated(USD_1D); runCurrent()
        k.coordinator.onActivated(USD_3M); runCurrent()
        k.tabs().single().tab.complete(ok(dayTab(mapOf("kb.usd" to listOf(B1))))); runCurrent()
        k.coordinator.onActivated(USD_1D); runCurrent()
        k.recorderOpen = false
        fire(k, 1)
        k.recorderOpen = true
        k.autoTab = partial
        step(30.seconds)
        assertEquals("premise: held", listOf(0L), k.tabTimes())
        k.coordinator.onActivated(USD_3M); runCurrent()
        k.coordinator.onActivated(USD_1D); runCurrent()
        assertEquals("a new activation releases the held budget's round", secs(0, 30), k.tabTimes())

        // Under a floor the difference shows. None opens nothing, so the stale entry's own Refresh is deferred to the floor.
        val q = shortCatalog()
        q.autoTab = { it.tab.complete(ok(dayTab(emptyMap()))) }
        q.coordinator.onActivated(USD_1D); runCurrent()
        step(2.seconds)
        q.autoTab = { it.tab.complete(status(429, "10")) }
        q.coordinator.onRefreshRequested(force = true); runCurrent()
        q.autoTab = { it.tab.complete(ok(dayTab(emptyMap()))) }
        fire(q, 1)
        q.coordinator.onRefreshRequested(force = false); runCurrent()
        step(10.seconds)
        assertEquals("None: the key's own request goes at the floor", secs(0, 2, 12), q.tabTimes())

        // An Unreadable demand opens the budget held: a demand readable again by the floor sends nothing there.
        val u = ready()
        u.coordinator.onActivated(USD_1D); runCurrent()
        u.coordinator.onActivated(USD_3M); runCurrent()
        u.tabs().single().tab.complete(ok(dayTab(mapOf("kb.usd" to listOf(B1))))); runCurrent()
        u.autoTab = { it.tab.complete(status(429, "10")) }
        u.coordinator.onRefreshRequested(force = true); runCurrent()
        u.coordinator.onActivated(USD_1D); runCurrent()
        u.recorderOpen = false
        fire(u, 1)
        u.recorderOpen = true
        u.autoTab = partial
        step(20.seconds)
        assertEquals("held: nothing at the floor though the demand is readable again", listOf(0L), u.tabTimes())

        val j = ready()
        j.tabSequence({ it.tab.complete(status(404)) })
        j.coordinator.onActivated(USD_1D); runCurrent()
        j.autoTab = null
        step(10.seconds)
        j.coordinator.onRefreshRequested(force = true); runCurrent()
        fire(j, 1)
        assertEquals("premise: the first round joined the outside tab", secs(0, 10), j.tabTimes())
        j.autoTab = fail503
        step(2.seconds)
        j.tabs().last().tab.complete(status(503)); runCurrent()
        step(3.seconds)
        assertEquals("the next round 3 s after the joined completion", secs(0, 10, 15), j.tabTimes())
        assertEquals("no failure from a rung-0 settlement", 0, j.failures.size)
    }

    /** T07: a trigger sends nothing itself: the new cycle's first round waits for the floor, and for its key to be active. */
    @Test fun T07_aTriggerSendsNothingItself() = budgetTest {
        val f = exhausted()
        f.autoTab = { it.tab.complete(status(429, "10")) }
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        f.autoTab = fail503
        fire(f, 1)
        assertEquals("nothing under the floor", 7, f.tabTimes().size)
        step(10.seconds)
        assertEquals("the first round at the floor", 210_000L, f.tabTimes().last())

        val g = exhausted()
        g.coordinator.onActivated(USD_3M); runCurrent()
        fire(g, 1)
        step(30.seconds)
        assertEquals("not while usd 3m is active", 6, g.tabTimes().size)
        g.coordinator.onActivated(USD_1D); runCurrent()
        assertEquals("then at once", 230_000L, g.tabTimes().last())
        step(5.seconds)
        assertEquals("a round of the new cycle, not the key's own request", 233_000L, g.tabTimes().last())

        // A trigger reopens another tab's budget of the scope as well.
        val j = ready()
        j.tabSequence(fail503)
        j.coordinator.onActivated(JPY_1D); runCurrent()
        step(200.seconds)
        assertEquals("premise: jpy exhausted", secs(0, 3, 9, 21, 45, 93), j.tabTimes(JPY_1D))
        j.coordinator.onActivated(USD_3M); runCurrent()
        fire(j, 1)
        step(30.seconds)
        j.coordinator.onActivated(JPY_1D); runCurrent()
        step(5.seconds)
        assertEquals("another tab's budget reopened too", secs(0, 3, 9, 21, 45, 93, 230, 233), j.tabTimes(JPY_1D))
    }

    /**
     * T08: a new cycle is apart from the old one's requests. The old sixth round's catalog, sent before the pause, is out
     * when the new cycle's first round needs a catalog: the new round joins it, its completion lets the new round's tab go,
     * and the new cycle still has six rounds of its own.
     */
    @Test fun T08_aNewCycleIsApartFromTheOldRequests() = budgetTest {
        val f = dropped()
        f.tabSequence(fail503)
        f.catalogSequence(catalog503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(45.seconds)
        f.autoCatalog = null
        step(48.seconds)
        assertEquals("premise: the sixth round's catalog is out, counted", 93_000L, f.catalogTimes().last())
        assertEquals(secs(0, 3, 9, 21, 45), f.tabTimes())
        f.accessThrowsOnce = true
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        step(7.seconds)
        val catalogs = f.catalogTimes().size
        fire(f, 1)
        assertEquals("the new round joined the old catalog: none of its own", catalogs, f.catalogTimes().size)
        assertEquals(secs(0, 3, 9, 21, 45), f.tabTimes())
        step(5.seconds)
        f.catalogs().last().catalog.complete(ok(catalog())); runCurrent()
        assertEquals("its completion lets the new round go", 105_000L, f.tabTimes().last())
        step(200.seconds)
        assertEquals(secs(0, 3, 9, 21, 45, 105, 108, 114, 126, 150, 198), f.tabTimes())
    }

    // --- P01 -----------------------------------------------------------------------------------------------------

    /**
     * P01: a fresh 1d answer leaving kb's closed B2 demand arms one round 3 s after it (not a millisecond earlier). Without a
     * closed demand, with only a current-bucket demand, or without a recorder, nothing follows.
     */
    @Test fun P01_aClosedDemandLeftByAFreshAnswerArmsTheNextRound() = budgetTest {
        val f = ready()
        f.tabSequence(partial)
        f.coordinator.onActivated(USD_1D); runCurrent()
        assertNotNull("premise: a fresh entry", f.state.entries.getValue(USD_1D).online200At)
        step(2_999.milliseconds)
        assertEquals(listOf(0L), f.tabTimes())
        step(1.milliseconds)
        assertEquals(listOf(0L, 3_000L), f.tabTimes())

        val all = ready()
        all.tabSequence({ it.tab.complete(ok(dayTab(mapOf("kb.usd" to listOf(B1, B2))))) })
        all.coordinator.onActivated(USD_1D); runCurrent()
        step(300.seconds)
        assertEquals("every closed demand supplied", listOf(0L), all.tabTimes())

        val current = ready()
        current.lose("kb", NOON)
        assertTrue("premise: a current-bucket demand", NOON in current.series(KB).pending)
        current.tabSequence({ it.tab.complete(ok(dayTab(mapOf("kb.usd" to listOf(B1, B2))))) })
        current.coordinator.onActivated(USD_1D); runCurrent()
        step(300.seconds)
        assertEquals("a current-bucket demand alone", listOf(0L), current.tabTimes())
        assertTrue(NOON in current.series(KB).pending)

        val none = ready(withRecorder = false)
        none.tabSequence(partial)
        none.coordinator.onActivated(USD_1D); runCurrent()
        step(300.seconds)
        assertEquals("no recorder, no budget", listOf(0L), none.tabTimes())
    }

    // --- P02 -----------------------------------------------------------------------------------------------------

    /**
     * P02: 503 answers and partial answers both run rounds at 0/3/9/21/45/93 s and stop there with B2 kept; a 429 adds the
     * jitter to each deadline; a long Retry-After floor holds the next round to the floor.
     */
    @Test fun P02_theLadderRunsSixRoundsAndKeepsTheDemand() = budgetTest {
        for ((label, answer) in listOf("503" to fail503, "partial" to partial)) {
            val f = ready()
            f.tabSequence(answer)
            f.coordinator.onActivated(USD_1D); runCurrent()
            step(500.seconds)
            assertEquals(label, secs(0, 3, 9, 21, 45, 93), f.tabTimes())
            assertTrue("$label: B2 kept", B2 in f.series(KB).pending)
        }

        val j = ready()
        j.jitter = 500.milliseconds
        j.tabSequence({ it.tab.complete(status(429)) })
        j.coordinator.onActivated(USD_1D); runCurrent()
        step(500.seconds)
        assertEquals("jitter after each 429", listOf(0L, 3_500L, 10_000L, 22_500L, 47_000L, 95_500L), j.tabTimes())

        val short = ready()
        short.jitter = 500.milliseconds
        short.tabSequence({ it.tab.complete(status(429, "1")) }, fail503)
        short.coordinator.onActivated(USD_1D); runCurrent()
        step(3.seconds)
        assertEquals("a floor at 1.5 s does not hide the first deadline's jitter", listOf(0L), short.tabTimes())
        step(500.milliseconds)
        assertEquals(listOf(0L, 3_500L), short.tabTimes())

        val floor = ready()
        floor.tabSequence({ it.tab.complete(status(503, "120")) }, fail503)
        floor.coordinator.onActivated(USD_1D); runCurrent()
        step(119_999.milliseconds)
        assertEquals("nothing before the floor", listOf(0L), floor.tabTimes())
        step(400.seconds)
        assertEquals(secs(0, 120, 126, 138, 162, 210), floor.tabTimes())
    }

    // --- P03 -----------------------------------------------------------------------------------------------------

    /**
     * P03: a round whose tab is withdrawn before it is sent (a P4 round trip while its capture is held) is sent again as the
     * same round: six requests are sent in all.
     */
    @Test fun P03_aRoundWithdrawnBeforeItsSendIsNotCounted() = budgetTest {
        val f = ready()
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        val held = f.hold(2)
        step(3.seconds)
        assertEquals("premise: the second capture is held", 2, f.captures)
        roundTrip(f)
        assertEquals("the same round goes again at once", secs(0, 3), f.tabTimes())
        step(500.seconds)
        assertEquals(secs(0, 3, 9, 21, 45, 93), f.tabTimes())
        held.complete(Unit); runCurrent()
        assertEquals("the withdrawn capture sends nothing", 6, f.tabs().size)
    }

    /**
     * P03b: a round's capture ending in an AuthUnavailableException (withdrawn) holds the budget: no capture repeats in the
     * same context and activation; each new activation resumes one capture. (A context change always starts a new
     * activation; it also asks for the dropped catalog, so the rows use a key switch.)
     */
    @Test fun P03b_aWithdrawnCaptureHoldsTheBudget() = budgetTest {
        val f = ready()
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        f.captureFails = { n -> if (n >= 2) AuthUnavailableException("offline") else null }
        step(3.seconds)
        assertEquals(2, f.captures)
        step(300.seconds)
        assertEquals("held: no capture repeats", 2, f.captures)
        f.coordinator.onActivated(USD_3M); runCurrent()
        f.coordinator.onActivated(USD_1D); runCurrent()
        assertEquals("a new activation resumes one capture", 3, f.captures)
        step(300.seconds)
        assertEquals(3, f.captures)
        f.captureFails = { null }
        f.coordinator.onActivated(USD_3M); runCurrent()
        f.coordinator.onActivated(USD_1D); runCurrent()
        assertEquals(4, f.captures)
        assertEquals("the round is finally sent", secs(0, 603), f.tabTimes())
    }

    /**
     * P03c: a capture failing with an IOException is an applicable retryable completion: it climbs the ladder at 3/9/21/45/93
     * s without sending and stops at the sixth rung.
     */
    @Test fun P03c_aRetryableCaptureFailureClimbsTheLadderWithoutSending() = budgetTest {
        val f = ready()
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        f.captureFails = { n -> if (n >= 2) IOException("reset") else null }
        step(500.seconds)
        assertEquals(secs(0, 3, 9, 21, 45, 93), f.captureTimes)
        assertEquals("only the first request was sent", listOf(0L), f.tabTimes())
        assertEquals("the sixth rung stops without a failure", emptyList<Throwable>(), f.failures)
    }

    /**
     * P03 (floor waits): a round's tab withdrawn before its send while it waits for the floor - its capture ending after a
     * key switch, or a key switch while it waits - is sent again at the floor as the same round: six requests in all.
     */
    @Test fun P03_aRoundWithdrawnAtTheFloorIsNotCounted() = budgetTest {
        val w = ready()
        w.autoTab = { if (it.key == USD_3M) it.tab.complete(status(429, "30")) else it.tab.complete(status(503)) }
        w.coordinator.onActivated(USD_1D); runCurrent()
        val held = w.hold(2)
        step(3.seconds)
        assertEquals("premise: the round's capture is held", 2, w.captures)
        w.coordinator.onActivated(USD_3M); runCurrent()
        w.coordinator.onRefreshRequested(force = true); runCurrent()
        held.complete(Unit); runCurrent()
        assertEquals("premise: nothing sent", listOf(0L), w.tabTimes())
        assertTrue("premise: withdrawn under the floor", USD_1D !in w.state.inFlight)
        w.coordinator.onActivated(USD_1D); runCurrent()
        step(500.seconds)
        assertEquals("the same round at the floor; six in all", secs(0, 33, 39, 51, 75, 123), w.tabTimes())

        // Captures: 1 the first tab, 2 round 2's tab, 3 round 3's own catalog (the round trip dropped the catalog and a
        // same-scope change asks for none, S4 RT03b-2a), 4 round 3's tab.
        val v = ready()
        v.coordinator.onActivated(USD_1D); runCurrent()
        v.tabs()[0].tab.complete(status(503)); runCurrent()
        step(3.seconds)
        assertEquals("premise: round 2 is out", secs(0, 3), v.tabTimes())
        val late = v.hold(4)
        roundTrip(v)
        assertEquals("premise: round 3's tab capture is held", 4, v.captures)
        assertEquals("premise: round 3's own catalog went", listOf(0L, 3_000L), v.catalogTimes())
        v.tabs()[1].tab.complete(status(429, "30")); runCurrent()
        late.complete(Unit); runCurrent()
        assertTrue("premise: round 3's tab waits for the floor", USD_1D in v.state.inFlight)
        assertEquals(secs(0, 3), v.tabTimes())
        v.autoTab = fail503
        v.coordinator.onActivated(USD_3M); runCurrent()
        assertTrue("premise: withdrawn by the key switch", USD_1D !in v.state.inFlight)
        v.coordinator.onActivated(USD_1D); runCurrent()
        step(500.seconds)
        assertEquals(
            "round 3's tab again at the floor, the round counted once by its catalog",
            secs(0, 3, 33, 39, 51, 75), v.tabTimes()
        )
    }

    /**
     * P03d: rounds count sends only. A budget created by an unsent (capture-failed) completion counts nothing for it, and a
     * round settled by a capture failure is not counted: with later rounds lost in round trips, six sends reach the cap.
     */
    @Test fun P03d_onlySentRoundsAreCounted() = budgetTest {
        val f = ready()
        f.captureFails = { n -> if (n == 1) IOException("reset") else null }
        f.coordinator.onActivated(USD_1D); runCurrent()
        assertEquals("premise: nothing sent", emptyList<Long>(), f.tabTimes())
        step(3.seconds)
        assertEquals("premise: the first send", secs(3), f.tabTimes())
        repeat(5) { roundTrip(f) }
        assertEquals("six sends", List(6) { 3_000L }, f.tabTimes())

        val g = ready()
        g.coordinator.onActivated(USD_1D); runCurrent()
        g.tabs()[0].tab.complete(status(503)); runCurrent()
        g.captureFails = { n -> if (n == 2) IOException("reset") else null }
        step(9.seconds)
        assertEquals("premise: round 2 settled unsent, round 3 is out", secs(0, 9), g.tabTimes())
        repeat(4) { roundTrip(g) }
        assertEquals(listOf(0L) + List(5) { 9_000L }, g.tabTimes())
    }

    // --- P04 -----------------------------------------------------------------------------------------------------

    /**
     * P04: a P4 round trip, a new grant and a data scope gone and back before the first deadline keep it and the ladder:
     * unforced context changes send nothing early.
     */
    @Test fun P04_contextRoundTripsKeepTheBudget() = budgetTest {
        val f = ready()
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(1.seconds)
        roundTrip(f)
        step(500.milliseconds)
        f.fence = sessionFence(grant = 8); f.coordinator.onContextChanged(); runCurrent()
        step(500.milliseconds)
        f.fence = null; f.coordinator.onContextChanged(); runCurrent()
        f.fence = sessionFence(grant = 8); f.coordinator.onContextChanged(); runCurrent()
        step(999.milliseconds)
        assertEquals("nothing early", listOf(0L), f.tabTimes())
        step(1.milliseconds)
        assertEquals(secs(0, 3), f.tabTimes())
        step(500.seconds)
        assertEquals(secs(0, 3, 9, 21, 45, 93), f.tabTimes())
    }

    /** P04b: a sent round lost in a P4 round trip is spent: the next round goes at once, and six are sent in all. */
    @Test fun P04b_aSentRoundLostInARoundTripIsSpent() = budgetTest {
        val f = ready()
        f.coordinator.onActivated(USD_1D); runCurrent()
        f.tabs().single().tab.complete(status(503)); runCurrent()
        step(3.seconds)
        assertEquals("premise: the second round is out", 2, f.tabs().size)
        // The fifth round trip finds six counted rounds and stops; a later one would send the key's own request.
        repeat(5) { roundTrip(f) }
        assertEquals(listOf(0L) + List(5) { 3_000L }, f.tabTimes())
        step(500.seconds)
        f.tabs().forEach { it.tab.complete(status(503)) }; runCurrent()
        step(500.seconds)
        assertEquals("late answers restart nothing", 6, f.tabs().size)
    }

    /** P04c: a settled sixth round stops the budget at once, so the key's own requests go before any further deadline. */
    @Test fun P04c_aSettledSixthRoundStopsAtOnce() = budgetTest {
        val f = ready()
        f.coordinator.onActivated(USD_1D); runCurrent()
        f.tabs()[0].tab.complete(status(503)); runCurrent()
        step(3.seconds)
        repeat(4) { roundTrip(f) }
        assertEquals("premise: six rounds counted, the sixth out", listOf(0L) + List(5) { 3_000L }, f.tabTimes())
        f.tabs().last().tab.complete(status(503)); runCurrent()
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("stopped: the key's own request goes", listOf(0L) + List(6) { 3_000L }, f.tabTimes())
    }

    // --- P05 -----------------------------------------------------------------------------------------------------

    /** P05: a USER end discards the budget, so the context change sends the key's own request at once. */
    @Test fun P05_aUserEndDiscardsTheBudget() = budgetTest {
        val f = ready()
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(1.seconds)
        f.userEnd = 1L
        f.coordinator.onContextChanged(); runCurrent()
        assertEquals(secs(0, 1), f.tabTimes())
        step(3.seconds)
        assertEquals("a new budget from that request", secs(0, 1, 4), f.tabTimes())
    }

    /**
     * P05b: another data scope discards the old scope's budget (retireScopes then finds nothing of e1); retireScopes removes a
     * selected budget held alone (REMOVED, then NOTHING_TO_REMOVE) and keeps one it did not select.
     */
    @Test fun P05b_otherScopesAndRetirement() = budgetTest {
        val f = ready()
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(1.seconds)
        f.fence = sessionFence(epoch = "e2"); f.coordinator.onContextChanged(); runCurrent()
        assertEquals("e2's own request", secs(0, 1), f.tabTimes())
        assertEquals("e1's budget went with e2", GraphRuntimeRetirement.NOTHING_TO_REMOVE, f.coordinator.retireScopes { it == SCOPE })

        val g = ready()
        g.tabSequence(fail503)
        g.coordinator.onActivated(USD_1D); runCurrent()
        step(1.seconds)
        g.fence = null; g.coordinator.onContextChanged(); runCurrent()
        assertEquals(GraphRuntimeRetirement.REMOVED, g.coordinator.retireScopes { it == SCOPE })
        assertEquals(GraphRuntimeRetirement.NOTHING_TO_REMOVE, g.coordinator.retireScopes { it == SCOPE })

        val h = ready()
        h.tabSequence(fail503)
        h.coordinator.onActivated(USD_1D); runCurrent()
        step(1.seconds)
        h.fence = null; h.coordinator.onContextChanged(); runCurrent()
        assertEquals(GraphRuntimeRetirement.NOTHING_TO_REMOVE, h.coordinator.retireScopes { it.userAccessEpoch == "e9" })
        h.fence = sessionFence(); h.coordinator.onContextChanged(); runCurrent()
        step(1_999.milliseconds)
        assertEquals("the unselected budget still waits", listOf(0L), h.tabTimes())
        step(1.milliseconds)
        assertEquals(secs(0, 3), h.tabTimes())

        val closed = ready()
        closed.tabSequence(fail503)
        closed.coordinator.onActivated(USD_1D); runCurrent()
        closed.fence = null; closed.coordinator.onContextChanged(); runCurrent()
        closed.coordinator.close()
        assertEquals("close removed the budget held alone",
            GraphRuntimeRetirement.NOTHING_TO_REMOVE,
            closed.coordinator.retireScopes { it == SCOPE })
    }

    /** P05c: Deactivate and retireCapabilities keep the budget; a USER end clears another tab's budget too. */
    @Test fun P05c_keptAcrossDeactivationAndClearedForEveryTab() = budgetTest {
        val f = ready()
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(1.seconds)
        f.coordinator.onDeactivated(); runCurrent()
        assertEquals(GraphRuntimeRetirement.NOTHING_TO_REMOVE, f.coordinator.retireCapabilities { true })
        step(1.seconds)
        f.coordinator.onActivated(USD_1D); runCurrent()
        assertEquals("kept: nothing early", listOf(0L), f.tabTimes())
        step(1.seconds)
        assertEquals(secs(0, 3), f.tabTimes())

        val g = ready()
        g.autoTab = byKey503
        g.coordinator.onActivated(JPY_1D); runCurrent()
        g.coordinator.onActivated(USD_1D); runCurrent()
        assertEquals("premise: jpy's first request", listOf(0L), g.tabTimes(JPY_1D))
        step(1.seconds)
        g.userEnd = 1L
        g.coordinator.onContextChanged(); runCurrent()
        g.coordinator.onActivated(JPY_1D); runCurrent()
        assertEquals("jpy's budget went too: its own request at once", secs(0, 1), g.tabTimes(JPY_1D))
    }

    // --- P06 -----------------------------------------------------------------------------------------------------

    /** P06: usd's budget sends nothing while usd 3m or jpy 1d is active, and goes at once when usd 1d is active again. */
    @Test fun P06_aBudgetSendsOnlyWhileItsKeyIsActive() = budgetTest {
        val f = ready()
        f.autoTab = byKey503
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(1.seconds)
        f.coordinator.onActivated(USD_3M); runCurrent()
        step(59.seconds)
        assertEquals(listOf(0L), f.tabTimes())
        f.coordinator.onActivated(USD_1D); runCurrent()
        assertEquals(secs(0, 60), f.tabTimes())

        val g = ready()
        g.autoTab = byKey503
        g.coordinator.onActivated(USD_1D); runCurrent()
        step(1.seconds)
        g.coordinator.onActivated(JPY_1D); runCurrent()
        step(29.seconds)
        assertEquals(listOf(0L), g.tabTimes(USD_1D))
        assertEquals("jpy's own budget", secs(1, 4, 10, 22), g.tabTimes(JPY_1D))
    }

    /** P06b: with a recorder, a long period keeps its cold ladder. */
    @Test fun P06b_aLongPeriodKeepsItsColdLadderWithARecorder() = budgetTest {
        val f = Fixture(this, withRecorder = true).also { opened += it }
        f.autoCatalog = catalogOk
        f.tabSequence(fail503)
        f.coordinator.start(); runCurrent()
        f.coordinator.onActivated(USD_3M); runCurrent()
        step(100.seconds)
        assertEquals(secs(0, 3, 9, 21, 45, 93), f.tabTimes(USD_3M))
    }

    // --- P07 -----------------------------------------------------------------------------------------------------

    /**
     * P07: while the budget waits, an unforced Refresh, a repeated Activate and an unchanged context send nothing; a forced
     * Refresh goes, the due round joins it without a send, and the joined answer re-uses the last interval (4 + 3 s).
     */
    @Test fun P07_aWaitingBudgetSuppressesUnforcedRequestsAndAJoinKeepsTheRung() = budgetTest {
        val f = ready()
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(1.seconds)
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        f.coordinator.onActivated(USD_1D); runCurrent()
        f.coordinator.onContextChanged(); runCurrent()
        step(1_999.milliseconds)
        assertEquals(listOf(0L), f.tabTimes())

        val g = ready()
        g.coordinator.onActivated(USD_1D); runCurrent()
        g.tabs().single().tab.complete(ok(dayTab(mapOf("kb.usd" to listOf(B1))))); runCurrent()
        step(1.seconds)
        g.coordinator.onRefreshRequested(force = true); runCurrent()
        step(2.seconds)
        assertEquals("joined, not sent", secs(0, 1), g.tabTimes())
        step(1.seconds)
        g.tabs()[1].tab.complete(ok(dayTab(mapOf("kb.usd" to listOf(B1))))); runCurrent()
        g.tabSequence(partial)
        step(500.seconds)
        assertEquals(secs(0, 1, 7, 13, 25, 49, 97), g.tabTimes())
    }

    /** P07b: once stopped, an outside Activate or forced Refresh still goes, and neither restarts the ladder. */
    @Test fun P07b_aStoppedBudgetLetsOutsideRequestsGoWithoutALadder() = budgetTest {
        val f = ready()
        f.autoTab = byKey503
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(100.seconds)
        assertEquals(secs(0, 3, 9, 21, 45, 93), f.tabTimes())
        f.coordinator.onActivated(USD_3M); runCurrent()
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(100.seconds)
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        step(300.seconds)
        assertEquals(secs(0, 3, 9, 21, 45, 93, 100, 200), f.tabTimes())
    }

    /**
     * P07c: an outside answer no round joined leaves a waiting budget as it is; at the due round the demand is read again,
     * and None removes the budget without a request.
     */
    @Test fun P07c_unboundAnswersAndNoneAtTheDueRound() = budgetTest {
        val f = ready()
        f.tabSequence(partial, { it.tab.complete(ok(dayTab(mapOf("kb.usd" to listOf(B1, B2))))) })
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(1.seconds)
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        assertTrue("premise: the outside answer supplied B2", B2 !in f.series(KB).pending)
        step(300.seconds)
        assertEquals("None at 3 s: no request", secs(0, 1), f.tabTimes())

        val g = ready()
        g.coordinator.onActivated(USD_1D); runCurrent()
        g.tabs()[0].tab.complete(status(503)); runCurrent()
        step(1.seconds)
        g.coordinator.onRefreshRequested(force = true); runCurrent()
        step(1.seconds)
        g.tabs()[1].tab.complete(status(503)); runCurrent()
        g.autoTab = fail503
        step(1.seconds)
        assertEquals("the deadline did not move", secs(0, 1, 3), g.tabTimes())
    }

    // --- P08 -----------------------------------------------------------------------------------------------------

    /**
     * P08: a 404 or a diagnostic failure of a round stops the budget with B2 kept (the diagnostic is reported); a 404 of the
     * first request creates none. A catalog the stopped round still holds in its capture is not sent.
     */
    @Test fun P08_aTerminalOrDiagnosticAnswerStopsTheBudget() = budgetTest {
        val f = ready()
        f.tabSequence(partial, partial, { it.tab.complete(status(404)) })
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(500.seconds)
        assertEquals(secs(0, 3, 9), f.tabTimes())
        assertTrue(B2 in f.series(KB).pending)
        f.coordinator.onActivated(USD_3M); runCurrent()
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(300.seconds)
        assertEquals("stopped, not held", secs(0, 3, 9), f.tabTimes())

        val g = ready()
        g.tabSequence(partial, { it.tab.completeExceptionally(IllegalStateException("decoder")) })
        g.coordinator.onActivated(USD_1D); runCurrent()
        step(500.seconds)
        assertEquals(secs(0, 3), g.tabTimes())
        assertTrue("reported", g.failures.any { it.message == "decoder" })
        g.coordinator.onActivated(USD_3M); runCurrent()
        g.coordinator.onActivated(USD_1D); runCurrent()
        step(300.seconds)
        assertEquals("stopped, not held", secs(0, 3), g.tabTimes())

        val h = ready()
        h.tabSequence({ it.tab.complete(status(404)) })
        h.coordinator.onActivated(USD_1D); runCurrent()
        step(500.seconds)
        assertEquals(listOf(0L), h.tabTimes())

        // (c) A round whose own catalog capture is held joins an outside tab, whose 404 stops the budget; the held catalog is
        // then withdrawn at the send boundary. Captures: 1-2 the first catalog and tab, 3-4 the forced Refresh's catalog and
        // tab, 5 the round's catalog.
        val c = bare()
        c.tabSequence(fail503)
        c.coordinator.onActivated(USD_1D); runCurrent()
        c.autoTab = null
        step(2_500.milliseconds)
        c.coordinator.onRefreshRequested(force = true); runCurrent()
        val held = c.hold(5)
        step(500.milliseconds)
        assertEquals("premise: the round's catalog capture is held", 5, c.captures)
        c.tabs().last().tab.complete(status(404)); runCurrent()
        held.complete(Unit); runCurrent()
        step(500.seconds)
        assertEquals("the stopped round's catalog is not sent", listOf(0L, 2_500L), c.catalogTimes())
        assertEquals(listOf(0L, 2_500L), c.tabTimes())
    }

    /** P08b: a withdrawn or diagnostic first completion, or one of a key no longer active, creates no budget. */
    @Test fun P08b_completionsThatCreateNoBudget() = budgetTest {
        val f = ready()
        f.captureFails = { n -> if (n == 1) AuthUnavailableException("offline") else null }
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(300.seconds)
        assertEquals("withdrawn", emptyList<Long>(), f.tabTimes())

        val g = ready()
        g.tabSequence({ it.tab.completeExceptionally(IllegalStateException("decoder")) }, fail503)
        g.coordinator.onActivated(USD_1D); runCurrent()
        step(300.seconds)
        assertEquals("diagnostic", listOf(0L), g.tabTimes())

        val h = ready()
        h.coordinator.onActivated(USD_1D); runCurrent()
        h.coordinator.onActivated(USD_3M); runCurrent()
        step(1.seconds)
        h.tabs()[0].tab.complete(status(503)); runCurrent()
        step(9.seconds)
        h.autoTab = fail503
        h.coordinator.onActivated(USD_1D); runCurrent()
        step(30.seconds)
        assertEquals("a budget only from the request at 10 s", secs(0, 10, 13, 19, 31), h.tabTimes())
    }

    // --- P10 -----------------------------------------------------------------------------------------------------

    /** P10: without a catalog each round sends a catalog and a tab and counts once: six of each. */
    @Test fun P10_aRoundCountsItsCatalogAndTabOnce() = budgetTest {
        val f = bare()
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(500.seconds)
        assertEquals(secs(0, 3, 9, 21, 45, 93), f.tabTimes())
        assertEquals(secs(0, 3, 9, 21, 45, 93), f.catalogTimes())
    }

    /**
     * P10b: a round whose catalog went and whose tab capture was withdrawn sends its tab again after a context change, without
     * a second catalog (even with the catalog still absent) and without counting again.
     */
    @Test fun P10b_aRoundWhoseCatalogWentResendsOnlyItsTab() = budgetTest {
        val f = bare()
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        // Captures: 1-2 the first catalog and tab, 3-4 the second round's. A same-scope context change asks for no catalog
        // (S4 RT03b-2a), so the catalog stays absent when the round's tab goes again.
        f.captureFails = { n -> if (n == 4) AuthUnavailableException("offline") else null }
        step(3.seconds)
        assertEquals("premise: the round's catalog went, its tab did not", secs(0, 3), f.catalogTimes())
        assertEquals(listOf(0L), f.tabTimes())
        step(1.seconds)
        roundTrip(f)
        assertEquals("only the tab goes again", secs(0, 3), f.catalogTimes())
        assertEquals(secs(0, 4), f.tabTimes())
        step(500.seconds)
        assertEquals(secs(0, 4, 10, 22, 46, 94), f.tabTimes())
        assertEquals(secs(0, 3, 10, 22, 46, 94), f.catalogTimes())
    }

    /**
     * P10c: a round whose own catalog capture is held joins an outside tab, which settles it first. That catalog, sent later,
     * counts for that round only: with every later round lost in a round trip, the budget stops after six counts.
     */
    @Test fun P10c_aLateCatalogCountsForItsOwnRound() = budgetTest {
        // Captures: 1-2 the first catalog and tab, 3-4 the forced Refresh's catalog and tab, 5 round 2's catalog.
        val f = bare()
        f.catalogSequence(catalog503, catalog503, catalogOk)
        f.coordinator.onActivated(USD_1D); runCurrent()
        f.tabs().single().tab.complete(status(503)); runCurrent()
        step(2_500.milliseconds)
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        val catalog = f.hold(5)
        step(500.milliseconds)
        assertEquals("premise: round 2's catalog capture is held", 5, f.captures)
        f.tabs().last().tab.complete(status(503)); runCurrent()
        step(2.seconds)
        catalog.complete(Unit); runCurrent()
        assertEquals("premise: the late catalog went", 5_000L, f.catalogTimes().last())
        step(1.seconds)
        assertEquals("premise: round 3, 3 s after the joined completion, is out", listOf(0L, 2_500L, 6_000L), f.tabTimes())
        // The fourth round trip finds six counted rounds and stops.
        repeat(4) { roundTrip(f) }
        assertEquals(listOf(0L, 2_500L) + List(4) { 6_000L }, f.tabTimes())
    }

    /**
     * P10d: the late catalog of a round settled by a joined outside tab goes while the next round waits for it, and makes
     * six: that next round, still uncounted, is not issued - the budget stops, so the key's own requests go. (The next round
     * cannot send first: a newer round of the key waits for that same catalog, RT03b-2b.)
     */
    @Test fun P10d_theSixthCountWithdrawsAnUncountedRound() = budgetTest {
        val f = bare()
        f.coordinator.onActivated(USD_1D); runCurrent()
        f.tabs()[0].tab.complete(status(503)); runCurrent()
        step(3.seconds)
        f.tabs()[1].tab.complete(status(503)); runCurrent()
        step(6.seconds)
        assertEquals("premise: round 3 is out", secs(0, 3, 9), f.tabTimes())
        roundTrip(f)
        roundTrip(f)
        assertEquals("premise: rounds 3 and 4 lost, round 5 out", secs(0, 3, 9, 9, 9), f.tabTimes())
        f.tabs()[4].tab.complete(status(503)); runCurrent()
        step(11_500.milliseconds)
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        val catalog = f.hold(f.captures + 1)
        step(500.milliseconds)
        assertEquals("premise: round 6 joined the forced tab at 20.5 s", 20_500L, f.tabTimes().last())
        f.tabs().last().tab.complete(status(503)); runCurrent()
        f.autoCatalog = catalogOk
        step(12.seconds)
        assertEquals("premise: round 7 waits for round 6's catalog", listOf(0L, 3_000L, 9_000L, 9_000L, 9_000L, 20_500L),
            f.tabTimes())
        catalog.complete(Unit); runCurrent()
        assertEquals("premise: round 6's late catalog went", 33_000L, f.catalogTimes().last())
        assertEquals("the sixth count stops round 7 before its tab", listOf(0L, 3_000L, 9_000L, 9_000L, 9_000L, 20_500L),
            f.tabTimes())
        f.coordinator.onActivated(USD_3M); runCurrent()
        f.coordinator.onActivated(USD_1D); runCurrent()
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("stopped: the key's own request goes", 33_000L, f.tabTimes().last())
    }

    /**
     * P10e: a round whose own catalog capture still runs sends no tab, across a key switch; its tab goes once that catalog
     * completes.
     */
    @Test fun P10e_aRoundWaitsForItsOwnCatalog() = budgetTest {
        val f = bare()
        f.autoTab = byKey503
        f.coordinator.onActivated(USD_1D); runCurrent()
        val catalog = f.hold(3)
        step(3.seconds)
        assertEquals("premise: only the round's catalog is captured", 3, f.captures)
        assertEquals(listOf(0L), f.tabTimes())
        f.coordinator.onActivated(USD_3M); runCurrent()
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(10.seconds)
        assertEquals("nothing while the round's catalog runs", listOf(0L), f.tabTimes())
        catalog.complete(Unit); runCurrent()
        assertEquals("then its tab once", secs(0, 13), f.tabTimes())
    }

    // --- P11 -----------------------------------------------------------------------------------------------------

    /**
     * P11: a failed event handling stops the budget whether the round's capture is held, its tab waits for the floor or it
     * is out: no recovery request follows; outside requests still go, and the key held by the withdrawn round is free.
     */
    @Test fun P11_aFailedEventStopsTheBudget() = budgetTest {
        val f = ready()
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        val held = f.hold(2)
        step(3.seconds)
        step(1.seconds)
        f.accessThrowsOnce = true
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("premise: the handling failed", 1, f.failures.size)
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        assertEquals("the forced Refresh is not swallowed by the withdrawn round", secs(0, 4), f.tabTimes())
        held.complete(Unit); runCurrent()
        step(500.seconds)
        assertEquals(secs(0, 4), f.tabTimes())
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        f.coordinator.onContextChanged(); runCurrent()
        step(500.seconds)
        assertEquals("an outside request, no ladder", secs(0, 4, 504), f.tabTimes())

        // The round's catalog fails, so its tab goes; while that tab's capture is held, a Refresh's own catalog meets a 429
        // and the tab, captured after it, waits registered for the floor. Captures: 1-2 the first catalog and tab, 3 the
        // round's catalog, 4 its tab, 5 the Refresh's catalog.
        val g = bare()
        g.tabSequence(fail503)
        g.catalogSequence(catalog503, catalog503, { it.catalog.complete(status(429, "10")) })
        g.coordinator.onActivated(USD_1D); runCurrent()
        val tab = g.hold(4)
        step(3.seconds)
        g.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("premise: the Refresh's catalog met the 429", secs(0, 3, 3), g.catalogTimes())
        tab.complete(Unit); runCurrent()
        assertEquals("premise: the tab waits for the floor", listOf(0L), g.tabTimes())
        assertTrue("premise: registered while it waits", USD_1D in g.state.inFlight)
        step(1.seconds)
        g.accessThrowsOnce = true
        g.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("premise: the handling failed", 1, g.failures.size)
        assertTrue("the floor waiter is withdrawn", USD_1D !in g.state.inFlight)
        // An unchanged context sends nothing, but its handling re-arms the timer the failure cancelled.
        g.coordinator.onContextChanged(); runCurrent()
        step(500.seconds)
        assertEquals(listOf(0L), g.tabTimes())

        val h = ready()
        h.coordinator.onActivated(USD_1D); runCurrent()
        h.tabs().single().tab.complete(status(503)); runCurrent()
        step(3.seconds)
        step(1.seconds)
        h.accessThrowsOnce = true
        h.coordinator.onRefreshRequested(force = false); runCurrent()
        step(1.seconds)
        h.tabs()[1].tab.complete(status(503)); runCurrent()
        step(500.seconds)
        assertEquals(secs(0, 3), h.tabTimes())
    }

    /** P11b: an outside request a round joined is not withdrawn by a failed event handling, and restarts nothing. */
    @Test fun P11b_aJoinedOutsideRequestSurvivesTheFailure() = budgetTest {
        val f = ready()
        f.coordinator.onActivated(USD_1D); runCurrent()
        f.tabs().single().tab.complete(status(503)); runCurrent()
        step(1.seconds)
        val held = f.hold(2)
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        step(2.seconds)
        assertEquals("premise: the forced Refresh's capture is still held", 2, f.captures)
        assertEquals("premise: the due round joined it", listOf(0L), f.tabTimes())
        step(1.seconds)
        f.accessThrowsOnce = true
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("premise: the handling failed", 1, f.failures.size)
        held.complete(Unit); runCurrent()
        assertEquals("the joined outside request still goes", secs(0, 4), f.tabTimes())
        f.tabs()[1].tab.complete(status(503)); runCurrent()
        step(500.seconds)
        assertEquals("and restarts nothing", secs(0, 4), f.tabTimes())

        val g = ready()
        g.coordinator.onActivated(USD_1D); runCurrent()
        g.tabs()[0].tab.complete(status(503)); runCurrent()
        step(1.seconds)
        g.coordinator.onRefreshRequested(force = true); runCurrent()
        step(2.seconds)
        assertEquals("premise: the due round joined the outside request", secs(0, 1), g.tabTimes())
        step(1.seconds)
        g.accessThrowsOnce = true
        g.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("premise: the handling failed", 1, g.failures.size)
        g.tabs()[1].tab.complete(ok(dayTab(mapOf("kb.usd" to listOf(B1, B2))))); runCurrent()
        assertTrue("premise: the surviving outside answer supplied both losses",
            B1 !in g.series(KB).pending && B2 !in g.series(KB).pending)

        g.autoTab = fail503
        g.fence = null; g.coordinator.onContextChanged(); runCurrent()
        g.fence = sessionFence(); g.coordinator.onContextChanged(); runCurrent()
        assertEquals("the restored context's own request goes", secs(0, 1, 4), g.tabTimes())
        step(300.seconds)
        assertEquals("the stopped budget survived the outside success and scope round trip",
            secs(0, 1, 4), g.tabTimes())
    }

    /** P11c: a failed event handling stops every budget, not only the active key's. */
    @Test fun P11c_aFailedEventStopsEveryBudget() = budgetTest {
        val f = ready()
        f.autoTab = byKey503
        f.coordinator.onActivated(JPY_1D); runCurrent()
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(1.seconds)
        f.accessThrowsOnce = true
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("premise: the handling failed", 1, f.failures.size)
        f.coordinator.onActivated(JPY_1D); runCurrent()
        step(300.seconds)
        assertEquals("jpy's own request, then no ladder", secs(0, 1), f.tabTimes(JPY_1D))
    }

    /**
     * P17: a Wake for another reason - a dropped catalog's demand deferred to the floor - issues no round that the
     * budget's own timer would not: not (i) before its deadline, (ii) once stopped, or (iii) while held.
     */
    @Test fun P17_anotherWakeIssuesNoRoundTheBudgetWouldNot() = budgetTest {
        // A forced outside request's 429 sets the floor; after a round trip drops the catalog, an unforced Refresh under the
        // floor defers the catalog's request (a same-scope context change itself asks for none, S4 RT03b-2a).
        fun TestScope.deferUnderTheFloor(f: Fixture, retryAfter: String) {
            f.coordinator.onRefreshRequested(force = true); runCurrent()
            f.tabs().last().tab.complete(status(429, retryAfter)); runCurrent()
            step(500.milliseconds)
            roundTrip(f)
            f.coordinator.onRefreshRequested(force = false); runCurrent()
        }

        val i = ready()
        i.coordinator.onActivated(USD_1D); runCurrent()
        partial(i.tabs()[0]); runCurrent()
        step(500.milliseconds)
        deferUnderTheFloor(i, "2")
        step(1_999.milliseconds)
        assertEquals("(i) premise: the deferred catalog went at the floor", 2_500L, i.catalogTimes().last())
        assertEquals("(i) nothing before the deadline", secs(0) + 500L, i.tabTimes())
        step(1.milliseconds)
        assertEquals("(i) the round at its deadline", listOf(0L, 500L, 3_000L), i.tabTimes())

        val ii = ready()
        ii.tabSequence(partial, { it.tab.complete(status(404)) })
        ii.coordinator.onActivated(USD_1D); runCurrent()
        step(3.seconds)
        assertEquals("(ii) premise: stopped by a 404", secs(0, 3), ii.tabTimes())
        ii.autoTab = null
        step(1.seconds)
        deferUnderTheFloor(ii, "2")
        val capturesBeforeWake = ii.captures
        step(10.seconds)
        assertEquals("(ii) premise: the deferred catalog went at the floor", 6_000L, ii.catalogTimes().last())
        assertEquals("(ii) only the deferred catalog is captured", capturesBeforeWake + 1, ii.captures)
        assertEquals("(ii) no round once stopped", secs(0, 3, 4), ii.tabTimes())

        val iii = ready()
        iii.tabSequence(partial)
        iii.coordinator.onActivated(USD_1D); runCurrent()
        step(1.seconds)
        iii.recorderOpen = false
        step(2.seconds)
        assertEquals("(iii) premise: held at the due round", listOf(0L), iii.tabTimes())
        iii.autoTab = null
        iii.coordinator.onRefreshRequested(force = true); runCurrent()
        iii.tabs().last().tab.complete(status(429, "2")); runCurrent()
        // Readable again before the floor's Wake, so only the hold can keep that Wake from issuing a round.
        iii.recorderOpen = true
        step(500.milliseconds)
        iii.coordinator.onRefreshRequested(force = true); runCurrent()
        step(1_500.milliseconds)
        assertEquals("(iii) premise: the forced request deferred to the floor went", secs(0, 3, 5), iii.tabTimes())
        iii.tabs().last().tab.complete(status(503)); runCurrent()
        step(60.seconds)
        assertEquals("(iii) still held: the deferred request was not the round's", secs(0, 3, 5), iii.tabTimes())
    }

    // --- P12 -----------------------------------------------------------------------------------------------------

    /**
     * P12: a lost round's late answer moves no deadline (the next round still goes 6 s after the current one's answer); its
     * Retry-After still lifts the shared floor.
     */
    @Test fun P12_aLostRoundsLateAnswerOnlyRecordsItsFloor() = budgetTest {
        for (late in listOf("partial", "429")) {
            val f = ready()
            f.coordinator.onActivated(USD_1D); runCurrent()
            f.tabs()[0].tab.complete(status(503)); runCurrent()
            step(3.seconds)
            step(1.seconds)
            roundTrip(f)
            assertEquals("$late: premise", secs(0, 3, 4), f.tabTimes())
            step(1.seconds)
            if (late == "partial") partial(f.tabs()[1]) else f.tabs()[1].tab.complete(status(429, "60"))
            runCurrent()
            step(1.seconds)
            f.tabs()[2].tab.complete(status(503)); runCurrent()
            step(100.seconds)
            val expected = if (late == "partial") secs(0, 3, 4, 12) else secs(0, 3, 4, 65)
            assertEquals(late, expected, f.tabTimes().take(4))
        }
    }

    // --- P13 -----------------------------------------------------------------------------------------------------

    /**
     * Started with a recorder; every catalog succeeds with a one-second TTL, so a round 3 s later refreshes the catalog while
     * the adopted one still maps the tab (the recorder can then answer None).
     */
    private fun TestScope.shortCatalog(): Fixture = Fixture(this, withRecorder = true).also { f ->
        opened += f
        f.autoCatalog = { it.catalog.complete(ok(catalog(ttlSeconds = 1))) }
        f.coordinator.start(); runCurrent()
    }

    /**
     * P13: (a) a budget satisfied, through an outside tab its round joined, while that round's catalog capture is held
     * leaves that catalog to go, and its key's own requests go again; (b) a newer budget's rounds are not changed by an old
     * round's late catalog; (c) a failed event handling withdraws such a catalog even so.
     */
    @Test fun P13_aSatisfiedBudgetsCatalog() = budgetTest {
        // Captures: 1-2 the first catalog and tab, 3-4 the forced Refresh's catalog and tab, 5 the round's catalog.
        val f = shortCatalog()
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        assertNotNull("premise: catalog adopted", f.state.catalog)
        f.autoTab = null
        step(1_500.milliseconds)
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        val a = f.hold(5)
        step(1_500.milliseconds)
        assertEquals("premise: the round's catalog capture is held", 5, f.captures)
        f.tabs().last().tab.complete(ok(dayTab(emptyMap()))); runCurrent()
        step(1.seconds)
        a.complete(Unit); runCurrent()
        assertEquals("(a) the catalog goes", listOf(0L, 1_500L, 4_000L), f.catalogTimes())
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("no budget waits: the stale entry's own request goes", listOf(0L, 1_500L, 4_000L), f.tabTimes())
        step(500.seconds)
        assertEquals(listOf(0L, 1_500L, 4_000L), f.tabTimes())

        // (b) The old round joined an outside tab and holds its own catalog, so the old round is still uncounted when a newer
        // budget exists. Captures: 1-2 the first catalog and tab, 3 the forced tab, 4 the round's catalog, 5 the newer tab.
        val g = shortCatalog()
        g.tabSequence(fail503)
        g.coordinator.onActivated(USD_1D); runCurrent()
        g.autoTab = null
        step(500.milliseconds)
        g.coordinator.onRefreshRequested(force = true); runCurrent()
        val b = g.hold(4)
        step(2_500.milliseconds)
        assertEquals("premise: the due round joined the outside tab", listOf(0L, 500L), g.tabTimes())
        g.tabs()[1].tab.complete(ok(dayTab(emptyMap()))); runCurrent()
        g.recorder.observe(g.quote("kb", NOON + 3.seconds))
        g.lose("kb", B1 + 60.seconds)
        g.autoTab = fail503
        g.coordinator.onRefreshRequested(force = true); runCurrent()
        assertEquals("premise: the newer budget's creating request", listOf(0L, 500L, 3_000L), g.tabTimes())
        step(1.seconds)
        b.complete(Unit); runCurrent()
        assertEquals("premise: the old round's catalog went", 4_000L, g.catalogTimes().last())
        step(500.seconds)
        assertEquals("(b) the newer budget's six sends", listOf(0L, 500L) + secs(3, 6, 12, 24, 48, 96), g.tabTimes())

        val h = shortCatalog()
        h.tabSequence(fail503, { it.tab.complete(ok(dayTab(emptyMap()))) })
        h.coordinator.onActivated(USD_1D); runCurrent()
        val c = h.hold(3)
        step(3.seconds)
        h.accessThrowsOnce = true
        h.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("premise: the handling failed", 1, h.failures.size)
        c.complete(Unit); runCurrent()
        step(500.seconds)
        assertEquals("(c) withdrawn", listOf(0L), h.catalogTimes())
    }

    // --- P14 -----------------------------------------------------------------------------------------------------

    /**
     * P14: a catalog without usd's 1d keeps the budget waiting without a request (and without a timer loop); once the context
     * change drops that catalog, the same budget goes on from its second round.
     */
    @Test fun P14_anUnsupportedKeyKeepsTheBudget() = budgetTest {
        val f = bare()
        f.autoCatalog = null
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(1.seconds)
        f.sent.single { it.kind == "catalog" }.catalog.complete(ok(catalog(usdOneDay = false))); runCurrent()
        step(59.seconds)
        assertEquals(listOf(0L), f.tabTimes())
        // The round trip drops the catalog; each later round's own catalog fails, so its tab goes without one.
        f.autoCatalog = catalog503
        roundTrip(f)
        step(200.seconds)
        assertEquals(secs(0, 60, 66, 78, 102, 150), f.tabTimes())

        val g = ready()
        g.tabSequence(partial)
        g.coordinator.onActivated(USD_1D); runCurrent()
        step(1.seconds)
        g.autoCatalog = { it.catalog.complete(ok(catalog(usdOneDay = false, ttlSeconds = 1))) }
        roundTrip(g)
        g.coordinator.onRefreshRequested(force = false); runCurrent()
        assertTrue("premise: usd 1d is unsupported",
            GraphPeriod.ONE_DAY !in g.state.catalog!!.tabs.getValue("usd").periods)

        g.autoCatalog = null
        g.autoTab = null
        g.coordinator.onActivated(USD_3M); runCurrent()
        g.tabs(USD_3M).last().tab.complete(status(429, "2")); runCurrent()
        g.coordinator.onActivated(USD_1D); runCurrent()
        step(1.seconds)
        g.coordinator.onRefreshRequested(force = true); runCurrent()
        g.recorderOpen = false
        step(1.seconds)
        assertEquals("premise: only the deferred catalog went at the floor",
            3_000L, g.catalogTimes().last())
        assertEquals(listOf(0L), g.tabTimes())

        g.recorderOpen = true
        g.sent.last { it.kind == "catalog" }.catalog.complete(ok(catalog())); runCurrent()
        assertEquals("Unsupported did not create a hold; support returns in the same context",
            secs(0, 3), g.tabTimes())
    }

    // --- P15 -----------------------------------------------------------------------------------------------------

    /**
     * P15: an Unreadable demand counts as remaining when a completion is settled, and holds the due round in the same context
     * and activation, even once readable again. A key switch in the same context reads it again with the kept deadline.
     */
    @Test fun P15_anUnreadableDemandRemainsAndHolds() = budgetTest {
        val f = ready()
        f.tabSequence(partial)
        f.recorderOpen = false
        f.coordinator.onActivated(USD_1D); runCurrent()
        step(60.seconds)
        assertEquals("held at the due round", listOf(0L), f.tabTimes())
        f.recorderOpen = true
        step(60.seconds)
        assertEquals("still held", listOf(0L), f.tabTimes())
        f.coordinator.onActivated(USD_3M); runCurrent()
        f.coordinator.onActivated(USD_1D); runCurrent()
        assertEquals("a new activation reads again and goes", secs(0, 120), f.tabTimes())
    }

    // --- P16 -----------------------------------------------------------------------------------------------------

    /**
     * P16: anything but None remains. A CatalogRequired answer (no catalog) arms the next round; a query that throws is
     * reported and counts as remaining, so a budget waits until its due round reads None.
     */
    @Test fun P16_anythingButNoneRemains() = budgetTest {
        val f = bare()
        f.tabSequence({ it.tab.complete(ok(dayTab(emptyMap()))) })
        f.coordinator.onActivated(USD_1D); runCurrent()
        assertNotNull("premise: a fresh entry", f.state.entries.getValue(USD_1D).online200At)
        step(3.seconds)
        assertEquals("CatalogRequired remains", secs(0, 3), f.tabTimes())

        val g = shortCatalog()
        g.coordinator.onActivated(USD_1D); runCurrent()
        assertNotNull("premise: catalog adopted", g.state.catalog)
        g.catalogThrowsOnce = true
        g.tabs()[0].tab.complete(ok(dayTab(emptyMap()))); runCurrent()
        assertTrue("reported", g.failures.any { it.message == "catalog" })
        step(2.seconds)
        g.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("a budget waits: the stale entry's request is held back", listOf(0L), g.tabTimes())
        step(1.seconds)
        g.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("None at the due round removed it", secs(0, 3), g.tabTimes())
    }
}
