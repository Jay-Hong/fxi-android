package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.remote.AuthenticatedBodyDecodingException
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 A2a contract r3 (r1 battery survivors N02·N08·N14·N25·N39·N45 closed by fixtures; r2 adds the
 * disposal of a completed request's use, Codex counterexample to N16): the Graph V2 request owner without timers.
 *
 * Oracles: ANDROID_V2_PLAN.md S4 (:1288 online 200 alone records freshness, :448 D15 TTL ∧ same KST day,
 * :1317-1320 single-flight per scope·authority·tab·period, late answers never applied) and its DoD
 * (single-flight, 23:59→00:00, online-200-only freshness, TTL override); iOS a36682f GraphV2ViewModel.swift
 * :133-134 (TTL), :392-404 and :423-458 (active key, cache gate, in-flight guard, online success stamps
 * freshness), :407-413 (catalog failure isolated), :487-508 (refresh when stale); the access meaning of
 * SnapshotTopicUseAuthority.kt:14-27. Design: R4c/S4 a2_design_codex.r1 as corrected by a2_review_claude.r1
 * (S1 no capture event — the use check travels with every send; S2 no transport change; S3 A2a/A2b split) and
 * a2_verdict_codex.r1 (the check names the live identity and the captured owner; a context change inside one data
 * scope carries no catalog, registration or failure across; failures only from current, admitted requests).
 *
 * The fake transport records what `useAdmitted()` answered at the moment of each send. A send it recorded
 * as refused is one the production transport would not have put on the wire.
 */
class GraphV2RequestCoordinatorTest {

    private companion object {
        /** 2026-10-05 12:00 KST. */
        val NOON: Instant = Instant.parse("2026-10-05T03:00:00Z")
        val USD_1D = GraphKey("usd", GraphPeriod.ONE_DAY)
        val USD_3M = GraphKey("usd", GraphPeriod.THREE_MONTHS)
        val USD_1Y = GraphKey("usd", GraphPeriod.ONE_YEAR)
        val JPY_3M = GraphKey("jpy", GraphPeriod.THREE_MONTHS)
        const val SERIES = "investing.usd-krw"

        fun sessionFence(uid: String = "u1", generation: Long = 1, epoch: String? = "e1", grant: Long = 7) =
            TopicSessionFence(AuthIdentityFence(uid, generation), epoch, TopicGrantToken(grant))
    }

    private class Sent(
        val kind: String,
        val key: GraphKey?,
        val owner: AuthSnapshot,
        val admittedAtSend: Boolean,
        val useAdmitted: () -> Boolean
    ) {
        val tab = CompletableDeferred<AuthenticatedHttpResponse<GraphV2TabResponse>>()
        val catalog = CompletableDeferred<AuthenticatedHttpResponse<GraphV2CatalogResponse>>()
    }

    private class Fixture(test: TestScope, start: Instant = NOON) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        /** Virtual time already spent by earlier fixtures in the same test; this fixture's clock starts at [start]. */
        private val base = test.testScheduler.currentTime
        var fence: TopicSessionFence? = sessionFence()
        var allowed = true
        var invalidations = 0L
        var skew: Duration = Duration.ZERO
        var captureGate: CompletableDeferred<Unit>? = null
        /** The live credential session when it differs from the access fence (the fence can lag behind it). */
        var liveIdentity: AuthIdentityFence? = null
        /** A capture that hands back somebody else's credential. */
        var captureReturns: AuthSnapshot? = null
        /** Credential captures started — a capture can refresh a token, so it is a side effect of its own. */
        var captures = 0
        val sent = mutableListOf<Sent>()
        val failures = mutableListOf<Throwable>()

        val uses = object : TopicUseAuthority {
            override fun acquire(fence: TopicSessionFence): TopicUseLifetime? =
                if (allowed && fence == this@Fixture.fence) TopicUseLifetime(fence.grant, invalidations) else null

            override fun admits(lifetime: TopicUseLifetime): Boolean =
                allowed && this@Fixture.fence?.grant == lifetime.grant && invalidations == lifetime.invalidations
        }

        val owners = object : GraphOwnerSource {
            override fun currentIdentity(): AuthIdentityFence? = liveIdentity ?: fence?.identity

            override suspend fun capture(expected: AuthIdentityFence): AuthSnapshot {
                captures++
                captureGate?.await()
                if (currentIdentity() != expected) throw AuthIdentityChangedException()
                return captureReturns ?: AuthSnapshot(expected.uid, expected.authGeneration, "token")
            }
        }

        val fetcher = object : GraphV2Fetching {
            override suspend fun catalog(
                owner: AuthSnapshot,
                useAdmitted: () -> Boolean
            ): AuthenticatedHttpResponse<GraphV2CatalogResponse> {
                val s = Sent("catalog", null, owner, useAdmitted(), useAdmitted)
                sent += s
                return s.catalog.await()
            }

            override suspend fun tab(
                owner: AuthSnapshot,
                key: GraphKey,
                useAdmitted: () -> Boolean
            ): AuthenticatedHttpResponse<GraphV2TabResponse> {
                val s = Sent("tab", key, owner, useAdmitted(), useAdmitted)
                sent += s
                return s.tab.await()
            }
        }

        val coordinator = GraphV2RequestCoordinator(
            fetcher = fetcher,
            owners = owners,
            currentAccessFence = { fence },
            uses = uses,
            scope = scope,
            clock = AppClock { start + (test.testScheduler.currentTime - base).milliseconds + skew },
            onEventFailure = { failures += it }
        )

        val state get() = coordinator.state.value
        fun tabs(key: GraphKey? = null) = sent.filter { it.kind == "tab" && (key == null || it.key == key) }
        fun admittedTabs(key: GraphKey? = null) = tabs(key).filter { it.admittedAtSend }
        fun catalogs() = sent.filter { it.kind == "catalog" }
        fun close() = scope.cancel()
    }

    // --- responses ---------------------------------------------------------------------------

    private fun <T> ok(body: T) = AuthenticatedHttpResponse(200, Headers.headersOf(), body, null, byteArrayOf(1))

    private fun <T> status(code: Int, body: T? = null): AuthenticatedHttpResponse<T> {
        val headers = Headers.headersOf()
        val failure = if (code in 200..299) null
        else AuthenticatedHttpFailure(code, headers, byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP)
        return AuthenticatedHttpResponse(code, headers, body, failure, byteArrayOf(1))
    }

    private fun decodeFailure(): AuthenticatedBodyDecodingException {
        val raw = "{".toByteArray()
        return AuthenticatedBodyDecodingException(
            AuthenticatedHttpResponse(200, Headers.headersOf(), raw, null, raw), IllegalArgumentException("bad body")
        )
    }

    private fun tabDto(
        key: GraphKey,
        rate: Double = 1390.0,
        ids: List<String> = listOf(SERIES),
        empty: Boolean = false,
        fetchedAt: Instant = NOON - 1.hours
    ) = GraphV2TabResponse(
        tab = key.tab,
        period = key.period.code,
        series = ids.map { id ->
            GraphV2Series(
                id, id, "krw", "KRW", 2,
                if (empty) emptyList() else listOf(GraphV2Point(NOON - 1.days, rate, "x")),
                GraphV2Provenance(empty, emptyList()), null
            )
        },
        metadata = GraphV2Metadata(
            fetchedAt, if (key.period == GraphPeriod.ONE_DAY) "10min" else "1d",
            GraphV2Range("2026-07-05", "2026-10-05")
        )
    )

    private fun catalogDto(ttl: Int, tab: String = "usd", periods: Map<String, List<String>>) = GraphV2CatalogResponse(
        tabs = listOf(GraphV2CatalogTab(tab, tab, emptyMap(), periods.mapValues { (_, ids) -> GraphV2CatalogPeriod(ids, ids) })),
        version = "2026-05-27",
        supportedPeriods = listOf("1w", "3m", "1y"),
        cacheTtlSeconds = ttl
    )

    private fun rateOf(f: Fixture, key: GraphKey) = f.state.entries.getValue(key).tab.graph.series.first().points.first().rate

    private fun TestScope.step(d: Duration) {
        advanceTimeBy(d.inWholeMilliseconds)
        runCurrent()
    }

    private fun TestScope.started(start: Instant = NOON): Fixture =
        Fixture(this, start).also { it.coordinator.start(); runCurrent() }

    // --- A2a-01 active key, lazily ----------------------------------------------------------

    /** Only the active key is fetched. Switching away does not discard the earlier answer, which lands in its own slot only. */
    @Test fun A2a01_onlyTheActiveSupportedKeyIsFetched() = runTest {
        val f = started()
        f.coordinator.onActivated(USD_1D); runCurrent()
        assertEquals(listOf(USD_1D), f.tabs().map { it.key })
        f.coordinator.onActivated(JPY_3M); runCurrent()
        assertEquals(listOf(USD_1D, JPY_3M), f.tabs().map { it.key })

        f.tabs(USD_1D).single().tab.complete(ok(tabDto(USD_1D))); runCurrent()
        assertEquals(setOf(USD_1D), f.state.entries.keys)
        assertEquals(setOf(JPY_3M), f.state.inFlight)

        // Deactivate with the active key settled, so single-flight cannot be what stops the next request.
        f.tabs(JPY_3M).single().tab.complete(status(503)); runCurrent()
        assertEquals(emptySet<GraphKey>(), f.state.inFlight)
        f.coordinator.onDeactivated()
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        assertEquals("nothing is fetched while nothing is active", 2, f.tabs().size)
        f.close()

        // A catalog that does not list a key: activating it sends nothing.
        val g = started()
        g.coordinator.onActivated(USD_3M); runCurrent()
        g.catalogs().single().catalog.complete(ok(catalogDto(3600, periods = mapOf("3m" to listOf(SERIES))))); runCurrent()
        g.tabs(USD_3M).single().tab.complete(ok(tabDto(USD_3M))); runCurrent()
        g.coordinator.onActivated(USD_1D); runCurrent()
        g.coordinator.onActivated(GraphKey("eur", GraphPeriod.THREE_MONTHS)); runCurrent()
        assertEquals(listOf(USD_3M), g.tabs().map { it.key })
        g.close()
    }

    // --- A2a-02 single-flight ---------------------------------------------------------------

    /** One request per request context and key; another key or another context is its own request. */
    @Test fun A2a02_oneRequestPerContextAndKey() = runTest {
        val f = started()
        f.coordinator.onActivated(USD_3M); runCurrent()
        f.coordinator.onActivated(USD_3M)
        f.coordinator.onRefreshRequested(force = true)
        f.coordinator.onRefreshRequested(force = false)
        runCurrent()
        assertEquals(1, f.tabs(USD_3M).size)

        f.coordinator.onActivated(USD_1Y); runCurrent()
        assertEquals(1, f.tabs(USD_1Y).size)
        f.coordinator.onActivated(USD_3M); runCurrent()
        assertEquals("back to a key still in flight: no second request", 1, f.tabs(USD_3M).size)

        // A new grant is a new request context; the old request does not absorb it.
        f.fence = sessionFence(grant = 8); f.coordinator.onContextChanged(); runCurrent()
        assertEquals(2, f.tabs(USD_3M).size)

        // Once answered, a forced refresh starts again.
        val completed = f.tabs(USD_3M)[1]
        completed.tab.complete(ok(tabDto(USD_3M))); runCurrent()
        assertFalse("a completed request no longer owns an admitted use", completed.useAdmitted())
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        assertEquals(3, f.tabs(USD_3M).size)
        f.close()
    }

    // --- A2a-03 identity and namespace -------------------------------------------------------

    /** An answer minted under another uid, auth generation or access epoch is never applied, nor does it release the newer request. */
    @Test fun A2a03_aLateAnswerFromAnotherIdentityOrNamespaceIsDiscarded() = runTest {
        for ((label, moved) in listOf(
            "uid" to sessionFence(uid = "u2"),
            "auth generation" to sessionFence(generation = 2),
            "access epoch" to sessionFence(epoch = "e2")
        )) {
            val f = started()
            f.coordinator.onActivated(USD_3M); runCurrent()
            val r1 = f.tabs(USD_3M).single()
            f.fence = moved
            assertFalse("$label: a send of the old request is refused before any notice", r1.useAdmitted())
            f.coordinator.onContextChanged(); runCurrent()
            val r2 = f.tabs(USD_3M).last()
            assertTrue(label, r2 !== r1)
            assertEquals(label, moved.identity.uid, r2.owner.uid)
            assertEquals(label, moved.identity.authGeneration, r2.owner.authGeneration)
            assertEquals(label, GraphDataScope(moved.identity.uid, moved.userAccessEpoch!!), f.state.dataScope)

            r1.tab.complete(ok(tabDto(USD_3M, rate = 1111.0))); runCurrent()
            assertNull(label, f.state.entries[USD_3M])
            assertEquals(label, setOf(USD_3M), f.state.inFlight)

            r2.tab.complete(ok(tabDto(USD_3M, rate = 2222.0))); runCurrent()
            assertEquals(label, 2222.0, rateOf(f, USD_3M), 0.0)
            assertEquals(label, emptySet<GraphKey>(), f.state.inFlight)
            f.close()
        }
    }

    /**
     * Entries belong to (uid, access epoch): leaving it clears them. A change inside it keeps last-good entries and
     * their stamps, but no catalog and no failure crosses into the new request context.
     */
    @Test fun A2a03b_entriesBelongToTheDataScope() = runTest {
        for ((label, moved, kept) in listOf(
            Triple("uid", sessionFence(uid = "u2"), false),
            Triple("access epoch", sessionFence(epoch = "e2"), false),
            Triple("auth generation", sessionFence(generation = 2), true),
            Triple("grant", sessionFence(grant = 8), true)
        )) {
            val f = started()
            f.coordinator.onActivated(USD_3M); runCurrent()
            f.catalogs().single().catalog.complete(ok(catalogDto(3600, periods = mapOf("3m" to listOf(SERIES))))); runCurrent()
            f.tabs(USD_3M).single().tab.complete(ok(tabDto(USD_3M, rate = 1390.0))); runCurrent()
            f.coordinator.onRefreshRequested(force = true); runCurrent()
            f.tabs(USD_3M).last().tab.complete(status(503)); runCurrent()
            assertTrue(label, f.state.failures.containsKey(USD_3M) && f.state.catalog != null)

            f.fence = moved; f.coordinator.onContextChanged(); runCurrent()
            if (kept) {
                assertEquals(label, 1390.0, rateOf(f, USD_3M), 0.0)
                assertEquals(label, NOON, f.state.entries.getValue(USD_3M).online200At)
            } else {
                assertEquals(label, emptyMap<GraphKey, GraphEntry>(), f.state.entries)
            }
            assertNull(label, f.state.catalog)
            assertEquals(label, emptyMap<GraphKey, Throwable>(), f.state.failures)
            f.close()
        }
    }

    /** A credential captured while the identity moved sends nothing as the old identity and leaves no registration behind. */
    @Test fun A2a03c_anIdentityChangeDuringCaptureSendsNothingAndLeavesNothingInFlight() = runTest {
        val f = started()
        f.captureGate = CompletableDeferred()
        f.coordinator.onActivated(USD_3M); runCurrent()
        f.fence = sessionFence(generation = 2); f.coordinator.onContextChanged(); runCurrent()
        f.captureGate!!.complete(Unit); runCurrent()

        assertTrue(f.admittedTabs(USD_3M).none { it.owner.authGeneration == 1L })
        assertFalse("an abandoned capture is not a failure", f.state.failures.containsKey(USD_3M))
        val current = f.tabs(USD_3M).single { it.owner.authGeneration == 2L }
        assertEquals(setOf(USD_3M), f.state.inFlight)
        current.tab.complete(ok(tabDto(USD_3M))); runCurrent()
        assertEquals(emptySet<GraphKey>(), f.state.inFlight)
        assertTrue(f.state.entries.containsKey(USD_3M))
        f.close()

        // The same, with no notice at all: the abandoned capture still releases its own registration.
        val g = started()
        g.captureGate = CompletableDeferred()
        g.coordinator.onActivated(USD_3M); runCurrent()
        g.fence = sessionFence(generation = 2)
        g.captureGate!!.complete(Unit); runCurrent()
        assertEquals(emptyList<Sent>(), g.admittedTabs())
        assertEquals(emptySet<GraphKey>(), g.state.inFlight)
        assertFalse(g.state.failures.containsKey(USD_3M))
        g.close()
    }

    // --- A2a-04 access lifetime ---------------------------------------------------------------

    /** No protected read starts without access, and an answer is applied only while the use that started it is still admitted. */
    @Test fun A2a04_theUseLifetimeGatesEverySendAndEveryApplication() = runTest {
        // (a) No access, and (a2) no access epoch: nothing is sent at all, catalog included.
        // (a3) The live session is ahead of the access fence: nothing starts either.
        for (setup in listOf<(Fixture) -> Unit>(
            { it.allowed = false },
            { it.fence = sessionFence(epoch = null) },
            { it.liveIdentity = AuthIdentityFence("u1", 2) }
        )) {
            val f = started()
            setup(f); f.coordinator.onContextChanged()
            f.coordinator.onActivated(USD_3M); runCurrent()
            assertEquals(emptyList<Sent>(), f.sent)
            assertEquals("no credential is captured", 0, f.captures)
            f.close()
        }

        // (b) Withdrawn while the credential is being captured: no admitted send, nothing applied.
        run {
            val f = started()
            f.captureGate = CompletableDeferred()
            f.coordinator.onActivated(USD_3M); runCurrent()
            f.allowed = false; f.coordinator.onContextChanged()
            f.captureGate!!.complete(Unit); runCurrent()
            assertEquals(emptyList<Sent>(), f.admittedTabs())
            f.tabs().forEach { it.tab.complete(ok(tabDto(USD_3M))) }; runCurrent()
            assertEquals(emptyMap<GraphKey, GraphEntry>(), f.state.entries)
            f.close()
        }

        // (c) Withdrawn and given back under the same grant while in flight: the old use stays refused.
        run {
            val f = started()
            f.coordinator.onActivated(USD_3M); runCurrent()
            val r1 = f.tabs(USD_3M).single()
            assertTrue(r1.admittedAtSend)
            f.allowed = false; f.coordinator.onContextChanged(); runCurrent()
            f.invalidations = 1; f.allowed = true; f.coordinator.onContextChanged(); runCurrent()
            assertFalse("a replay of the old use would be refused", r1.useAdmitted())
            val r2 = f.tabs(USD_3M).last()
            assertTrue(r2 !== r1 && r2.admittedAtSend)
            r1.tab.complete(status(503)); runCurrent()
            assertFalse("the old use's failure is not reported", f.state.failures.containsKey(USD_3M))
            r2.tab.complete(ok(tabDto(USD_3M, rate = 2222.0))); runCurrent()
            assertEquals(2222.0, rateOf(f, USD_3M), 0.0)
            f.close()
        }

        // (c2) A new lifetime under the same fence, noticed only once: still a new request context.
        run {
            val f = started()
            f.coordinator.onActivated(USD_3M); runCurrent()
            val r1 = f.tabs(USD_3M).single()
            f.invalidations = 1; f.coordinator.onContextChanged(); runCurrent()
            assertFalse(r1.useAdmitted())
            val r2 = f.tabs(USD_3M).last()
            assertTrue(r2 !== r1 && r2.admittedAtSend)
            r1.tab.complete(ok(tabDto(USD_3M, rate = 1111.0))); runCurrent()
            assertNull(f.state.entries[USD_3M])
            r2.tab.complete(ok(tabDto(USD_3M, rate = 2222.0))); runCurrent()
            assertEquals(2222.0, rateOf(f, USD_3M), 0.0)
            f.close()
        }

        // (d) Withdrawn after the answer arrived but before it is applied, with no notice: checked again at application.
        run {
            val f = started()
            f.coordinator.onActivated(USD_3M); runCurrent()
            f.tabs(USD_3M).single().tab.complete(ok(tabDto(USD_3M)))
            f.allowed = false
            runCurrent()
            assertNull(f.state.entries[USD_3M])
            f.close()
        }

        // (f) The live session moved while the access fence still names the old one: refused, at send and at application.
        run {
            val f = started()
            f.coordinator.onActivated(USD_3M); runCurrent()
            val r1 = f.tabs(USD_3M).single()
            f.liveIdentity = AuthIdentityFence("u1", 2)
            assertFalse(r1.useAdmitted())
            r1.tab.complete(ok(tabDto(USD_3M))); runCurrent()
            assertNull(f.state.entries[USD_3M])
            f.close()
        }

        // (g) A capture that hands back another identity's credential: nothing is sent with it, nothing applied.
        run {
            val f = started()
            f.captureReturns = AuthSnapshot("u2", 1, "token")
            f.coordinator.onActivated(USD_3M); runCurrent()
            assertTrue(f.sent.none { it.owner.uid == "u2" })
            assertEquals(emptyMap<GraphKey, GraphEntry>(), f.state.entries)
            f.close()
        }

        // (e) A context notice that changes nothing the use depends on: no new request, and the answer is applied.
        run {
            val f = started()
            f.coordinator.onActivated(USD_3M); runCurrent()
            f.coordinator.onContextChanged(); runCurrent()
            assertEquals(1, f.tabs(USD_3M).size)
            f.tabs(USD_3M).single().tab.complete(ok(tabDto(USD_3M))); runCurrent()
            assertTrue(f.state.entries.containsKey(USD_3M))
            f.close()
        }
    }

    // --- A2a-05 online 200 alone is freshness -------------------------------------------------

    /**
     * Freshness is the time an admitted online 200 was applied — not the server's fetched_at, not a cache hit,
     * not any failure. A failure keeps last-good and is reported until the key succeeds again.
     */
    @Test fun A2a05_onlyAnAppliedOnline200StampsFreshness() = runTest {
        val f = started()
        f.coordinator.onActivated(USD_3M); runCurrent()
        step(5.seconds)
        f.tabs(USD_3M).single().tab.complete(ok(tabDto(USD_3M, rate = 1390.0))); runCurrent()
        val stamped = NOON + 5.seconds
        assertEquals(stamped, f.state.entries.getValue(USD_3M).online200At)

        step(10.seconds)
        f.coordinator.onActivated(USD_3M)
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("a fresh entry is a cache hit", 1, f.tabs(USD_3M).size)

        val refusals = listOf<(Sent) -> Unit>(
            { it.tab.complete(status(503)) },
            { it.tab.complete(status(201, tabDto(USD_3M, rate = 9999.0))) },
            { it.tab.completeExceptionally(decodeFailure()) },
            { it.tab.complete(ok(tabDto(JPY_3M, rate = 9999.0))) }
        )
        for ((i, refuse) in refusals.withIndex()) {
            f.coordinator.onRefreshRequested(force = true); runCurrent()
            refuse(f.tabs(USD_3M).last()); runCurrent()
            assertEquals("refusal $i keeps last-good", 1390.0, rateOf(f, USD_3M), 0.0)
            assertEquals("refusal $i keeps the stamp", stamped, f.state.entries.getValue(USD_3M).online200At)
            assertTrue("refusal $i is reported", f.state.failures.containsKey(USD_3M))
        }
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("still fresh: a cache hit", 5, f.tabs(USD_3M).size)
        assertTrue("a cache hit is not a success", f.state.failures.containsKey(USD_3M))

        // An insufficient-history 200 with no points is a success.
        step(1.seconds)
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        f.tabs(USD_3M).last().tab.complete(ok(tabDto(USD_3M, empty = true))); runCurrent()
        val entry = f.state.entries.getValue(USD_3M)
        assertEquals(NOON + 16.seconds, entry.online200At)
        assertTrue(entry.tab.graph.series.single().points.isEmpty())
        assertFalse(f.state.failures.containsKey(USD_3M))
        f.close()
    }

    // --- A2a-06 TTL ---------------------------------------------------------------------------

    /** Without a catalog the TTL is an hour; a catalog's TTL replaces it at once without re-stamping; a clock that ran backwards is not fresh. */
    @Test fun A2a06_theTtlDecidesStaleness() = runTest {
        // No catalog: an hour.
        run {
            val f = started()
            f.coordinator.onActivated(USD_3M); runCurrent()
            f.catalogs().single().catalog.complete(status(503))
            f.tabs(USD_3M).single().tab.complete(ok(tabDto(USD_3M))); runCurrent()
            step(3599.seconds); f.coordinator.onRefreshRequested(force = false); runCurrent()
            assertEquals(1, f.tabs(USD_3M).size)
            step(1.seconds); f.coordinator.onRefreshRequested(force = false); runCurrent()
            assertEquals(2, f.tabs(USD_3M).size)
            f.close()
        }
        // A catalog TTL of 120 seconds.
        run {
            val f = started()
            f.coordinator.onActivated(USD_3M); runCurrent()
            f.catalogs().single().catalog.complete(ok(catalogDto(120, periods = mapOf("3m" to listOf(SERIES))))); runCurrent()
            f.tabs(USD_3M).single().tab.complete(ok(tabDto(USD_3M))); runCurrent()
            step(119.seconds); f.coordinator.onRefreshRequested(force = false); runCurrent()
            assertEquals(1, f.tabs(USD_3M).size)
            step(1.seconds); f.coordinator.onRefreshRequested(force = false); runCurrent()
            assertEquals(2, f.tabs(USD_3M).size)
            f.close()
        }
        // A catalog that arrives later shortens the TTL of an entry already stamped, without re-stamping it.
        run {
            val f = started()
            f.coordinator.onActivated(USD_3M); runCurrent()
            f.tabs(USD_3M).single().tab.complete(ok(tabDto(USD_3M))); runCurrent()
            step(200.seconds)
            f.catalogs().single().catalog.complete(ok(catalogDto(120, periods = mapOf("3m" to listOf(SERIES))))); runCurrent()
            f.coordinator.onRefreshRequested(force = false); runCurrent()
            assertEquals(2, f.tabs(USD_3M).size)
            assertEquals(NOON, f.state.entries.getValue(USD_3M).online200At)
            f.close()
        }
        // A clock that moved backwards: age is negative, which is not fresh.
        run {
            val f = started()
            f.coordinator.onActivated(USD_3M); runCurrent()
            f.tabs(USD_3M).single().tab.complete(ok(tabDto(USD_3M))); runCurrent()
            f.skew = (-10).seconds
            f.coordinator.onRefreshRequested(force = false); runCurrent()
            assertEquals(2, f.tabs(USD_3M).size)
            f.close()
        }
    }

    // --- A2a-07 23:59 → 00:00 KST --------------------------------------------------------------

    /** Every period, 1d included: an answer from before KST midnight is stale after it, inside the TTL. */
    @Test fun A2a07_aNewKstDayIsStaleForEveryPeriod() = runTest {
        val before = Instant.parse("2026-10-05T14:59:00Z") // 23:59 KST, 14:59 UTC: the same UTC day either side
        for (period in GraphPeriod.entries) {
            val key = GraphKey("usd", period)
            val f = started(before)
            f.coordinator.onActivated(key); runCurrent()
            f.tabs(key).single().tab.complete(ok(tabDto(key))); runCurrent()
            step(30.seconds); f.coordinator.onRefreshRequested(force = false); runCurrent()
            assertEquals("$period 23:59:30 is the same day", 1, f.tabs(key).size)
            step(30.seconds); f.coordinator.onRefreshRequested(force = false); runCurrent()
            assertEquals("$period 00:00 is a new day", 2, f.tabs(key).size)
            f.close()
        }
    }

    // --- A2a-08 catalog lifetime and isolation ------------------------------------------------

    /**
     * The tab never waits for the catalog. The catalog is fetched when absent, expired or of another context, on a
     * trigger — never because a fetch failed — and a failed refresh keeps the catalog and its success time. The tab
     * is admitted against the catalog of its own context.
     */
    @Test fun A2a08_theCatalogLivesBesideTheTab() = runTest {
        val f = started()
        f.coordinator.onActivated(USD_3M); runCurrent()
        assertEquals(1, f.catalogs().size)
        assertEquals("the tab does not wait for the catalog", 1, f.tabs(USD_3M).size)

        f.catalogs().single().catalog.complete(ok(catalogDto(120, periods = mapOf("3m" to listOf(SERIES))))); runCurrent()
        val first = f.state.catalog
        f.tabs(USD_3M).single().tab.complete(ok(tabDto(USD_3M, ids = listOf(SERIES, "unlisted.usd-krw")))); runCurrent()
        assertEquals(listOf(SERIES), f.state.entries.getValue(USD_3M).tab.graph.series.map { it.seriesId })

        step(119.seconds); f.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals(1, f.catalogs().size)
        step(1.seconds); f.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals(2, f.catalogs().size)
        f.catalogs().last().catalog.complete(status(503)); runCurrent()
        assertEquals("a failed refresh keeps the catalog", first, f.state.catalog)
        assertEquals("a failure does not refetch by itself", 2, f.catalogs().size)
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals("still expired: the next trigger fetches again", 3, f.catalogs().size)

        f.fence = sessionFence(uid = "u2"); f.coordinator.onContextChanged(); runCurrent()
        assertNull("another context's catalog is never used", f.state.catalog)
        assertEquals(4, f.catalogs().size)
        assertEquals("u2", f.catalogs().last().owner.uid)
        f.catalogs()[2].catalog.complete(ok(catalogDto(3600, periods = mapOf("3m" to listOf(SERIES))))); runCurrent()
        assertNull("nor is the previous context's late answer", f.state.catalog)
        f.close()
    }
}
