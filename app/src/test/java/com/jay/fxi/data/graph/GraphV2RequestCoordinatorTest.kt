package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.auth.HttpExchangeEvidence
import com.jay.fxi.data.entitlements.TopicAccessEnd
import com.jay.fxi.data.entitlements.TopicAccessEndReason
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.remote.AuthenticatedBodyDecodingException
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.data.remote.TopicUseWithheldException
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
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
 * S4 A2b-1 contract r3 (r1 battery survivors B17·B18·B22·B25·B28 closed by fixtures; r2 adds Codex counterexamples
 * to B14·B34·B36) adds the cold retry ladder, failure classification, the shared rate-limit floor and the
 * cancellation of cold timers (A2b1 rows; design a2b_design_codex.r1 A2-09/10/13/14 as trimmed by a2b_review_claude.r1).
 * S4 A2b-2 contract r3 (r2 fixtures kill P16·P17; r3 adds Codex counterexamples to P22·P23) adds the midnight refresh of fixed-start periods: the next 00:02 KST, the 20/40/80 backoff
 * of a confirmed key, the cold ladder for an unconfirmed one, joining a request in flight, and its cancellation
 * (A2b2 rows; design a2b_design_codex.r1 A2-11/12/02 and A2-13/14 midnight rows).
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
 *
 * S4 RT03a (design R4c/S4 rt_next_codex.r1, agreed from rt_next_request.r1) adds rows RT03a1·RT03a2: a required
 * `protectedAdmission` supplier joins every use check - the credential capture, the send guard and the completion - and
 * is read live on each check. A send the transport withheld (`TopicUseWithheldException`) raises the shared floor from
 * each response it had already seen, in order, as an identity refusal does; with nothing seen there is no floor. The other
 * coordinator fixtures pass an always-open supplier; the production supplier, shared with the gate, is RT01's assembly.
 *
 * S4 RT01-A3 (API agreed in R4c/S4 rt01a3_api_codex.r3 from rt01a3_api_proposal.r3) adds rows A3c1·A3c2: an idempotent
 * `close()`, called on the coordinator's serial executor, that works whether or not the loop was ever entered. It refuses later
 * events, discards pending ones, and releases requests, writes, the seed and timer owners, publishing before it returns.
 * The runtime assembly calls it before cancelling its own job, since a cancelled launch may never run its finally. These rows
 * also fix that close() is not suspending and does not cancel the supplied scope, and that start() after close() returns
 * normally. GraphV2RequestCoordinatorWriteTest Y04 adds that close() cancels a pending writer before it returns.
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
        val useAdmitted: () -> Boolean,
        /** Virtual milliseconds since the fixture was built. */
        val atMs: Long = 0
    ) {
        val tab = CompletableDeferred<AuthenticatedHttpResponse<GraphV2TabResponse>>()
        val catalog = CompletableDeferred<AuthenticatedHttpResponse<GraphV2CatalogResponse>>()
    }

    private class Fixture(test: TestScope, start: Instant = NOON, jitter: (String) -> Duration = { Duration.ZERO }) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        /** Virtual time already spent by earlier fixtures in the same test; this fixture's clock starts at [start]. */
        private val base = test.testScheduler.currentTime
        var fence: TopicSessionFence? = sessionFence()
        var allowed = true
        /** The protected-admission supplier the coordinator reads on every use check (S4 RT03a). */
        var protectedOpen = true
        var invalidations = 0L
        /** The issuer's latest user end the coordinator reads (S4 RT01-A1); null before any end. */
        var userEnd: Long? = null
        var skew: Duration = Duration.ZERO
        var captureGate: CompletableDeferred<Unit>? = null
        /** The live credential session when it differs from the access fence (the fence can lag behind it). */
        var liveIdentity: AuthIdentityFence? = null
        /** A capture that hands back somebody else's credential. */
        var captureReturns: AuthSnapshot? = null
        /** Credential captures started — a capture can refresh a token, so it is a side effect of its own. */
        var captures = 0
        /** When set, the capture with this number (1-based) waits for [heldCapture]. */
        var holdCaptureNumber: Int? = null
        val heldCapture = CompletableDeferred<Unit>()
        /** Answers each send at once when set; otherwise the test completes it by hand. */
        var autoTab: ((Sent) -> Unit)? = null
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
            override fun currentIdentity(): AuthIdentityFence? = liveIdentity ?: fence?.identity

            override suspend fun capture(expected: AuthIdentityFence): AuthSnapshot {
                captures++
                if (captures == holdCaptureNumber) heldCapture.await()
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
                val s = Sent("catalog", null, owner, useAdmitted(), useAdmitted, test.testScheduler.currentTime - base)
                sent += s
                autoCatalog?.invoke(s)
                return s.catalog.await()
            }

            override suspend fun tab(
                owner: AuthSnapshot,
                key: GraphKey,
                useAdmitted: () -> Boolean
            ): AuthenticatedHttpResponse<GraphV2TabResponse> {
                val s = Sent("tab", key, owner, useAdmitted(), useAdmitted, test.testScheduler.currentTime - base)
                sent += s
                autoTab?.invoke(s)
                return s.tab.await()
            }
        }

        val coordinator = GraphV2RequestCoordinator(
            fetcher = fetcher,
            owners = owners,
            currentAccessFence = { fence },
            uses = uses,
            protectedAdmission = { protectedOpen },
            accessSnapshot = {
                TopicAccessSnapshot(
                    0L, TopicAccessFacts.NONE, invalidations,
                    userEnd?.let { TopicAccessEnd(it, TopicAccessEndReason.IDENTITY_CHANGED, null, null, null) }, null
                )
            },
            scope = scope,
            clock = AppClock { start + (test.testScheduler.currentTime - base).milliseconds + skew },
            rateLimitJitter = jitter,
            onEventFailure = { failures += it }
        )

        val state get() = coordinator.state.value
        fun tabs(key: GraphKey? = null) = sent.filter { it.kind == "tab" && (key == null || it.key == key) }
        fun admittedTabs(key: GraphKey? = null) = tabs(key).filter { it.admittedAtSend }
        fun catalogs() = sent.filter { it.kind == "catalog" }
        /** Admitted tab sends for [key], as virtual milliseconds. */
        fun tabTimes(key: GraphKey? = null) = admittedTabs(key).map { it.atMs }

        /** Answers tab sends in order; the last step repeats. */
        fun tabSequence(vararg steps: (Sent) -> Unit) {
            var next = 0
            autoTab = { s -> steps[minOf(next++, steps.size - 1)](s) }
        }
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

    // --- A2b-1 fixture --------------------------------------------------------------------------

    /** A catalog listing every period of usd and jpy, valid for two days so it never expires mid-row. */
    private fun wideCatalog() = GraphV2CatalogResponse(
        tabs = listOf("usd", "jpy").map { tab ->
            GraphV2CatalogTab(tab, tab, emptyMap(), listOf("1d", "1w", "3m", "1y").associateWith { GraphV2CatalogPeriod(listOf(SERIES), listOf(SERIES)) })
        },
        version = "2026-05-27",
        supportedPeriods = listOf("1w", "3m", "1y"),
        cacheTtlSeconds = 172800
    )

    private fun TestScope.ladder(jitter: Duration = Duration.ZERO, start: Instant = NOON): Fixture =
        Fixture(this, start) { jitter }.also {
            it.autoCatalog = { s -> s.catalog.complete(ok(wideCatalog())) }
            it.coordinator.start(); runCurrent()
        }

    private fun <T> limited(code: Int, retryAfter: String?): AuthenticatedHttpResponse<T> {
        val headers = if (retryAfter == null) Headers.headersOf() else Headers.headersOf("Retry-After", retryAfter)
        return AuthenticatedHttpResponse(
            code, headers, null,
            AuthenticatedHttpFailure(code, headers, byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP),
            byteArrayOf()
        )
    }

    private fun emptyBodyFailure(): AuthenticatedBodyDecodingException = AuthenticatedBodyDecodingException(
        AuthenticatedHttpResponse(200, Headers.headersOf(), byteArrayOf(), null, byteArrayOf()), IllegalArgumentException("empty body")
    )

    private val fail503: (Sent) -> Unit = { it.tab.complete(status(503)) }
    private fun succeed(key: GraphKey): (Sent) -> Unit = { it.tab.complete(ok(tabDto(key))) }
    private val hold: (Sent) -> Unit = { }
    private fun secs(vararg s: Int) = s.map { it * 1000L }

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

    /**
     * A2a03c (S4 RT01-A1): a new user end retires the coordinator's entries, catalog and failures even when the data scope
     * stays the same - with a new generation, or with only the use lifetime moved. The same end seen again retires nothing
     * more: a later context change without a new end keeps what was adopted since (A2a03b).
     */
    @Test fun A2a03c_aUserEndRetiresTheSameDataScopeOnce() = runTest {
        for ((label, move) in listOf<Pair<String, Fixture.() -> Unit>>(
            "a new generation" to { fence = sessionFence(generation = 2) },
            "only the lifetime" to { invalidations += 1 }
        )) {
            val f = started()
            f.coordinator.onActivated(USD_3M); runCurrent()
            f.catalogs().single().catalog.complete(ok(catalogDto(3600, periods = mapOf("3m" to listOf(SERIES))))); runCurrent()
            f.tabs(USD_3M).single().tab.complete(ok(tabDto(USD_3M, rate = 1390.0))); runCurrent()
            f.coordinator.onRefreshRequested(force = true); runCurrent()
            f.tabs(USD_3M).last().tab.complete(status(503)); runCurrent()
            assertTrue(label, f.state.failures.containsKey(USD_3M) && f.state.catalog != null)

            f.userEnd = 1L
            f.move()
            f.coordinator.onContextChanged(); runCurrent()
            assertEquals(label, emptyMap<GraphKey, GraphEntry>(), f.state.entries)
            assertNull(label, f.state.catalog)
            assertEquals(label, emptyMap<GraphKey, Throwable>(), f.state.failures)

            f.catalogs().last().catalog.complete(ok(catalogDto(3600, periods = mapOf("3m" to listOf(SERIES))))); runCurrent()
            f.tabs(USD_3M).last().tab.complete(ok(tabDto(USD_3M, rate = 1391.0))); runCurrent()
            assertEquals("$label: premise, adopted again", 1391.0, rateOf(f, USD_3M), 0.0)
            f.fence = sessionFence(generation = 3); f.coordinator.onContextChanged(); runCurrent()
            assertEquals("$label: the same end retires nothing more", 1391.0, rateOf(f, USD_3M), 0.0)
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

    // --- A2b1-09 failure classification ---------------------------------------------------------

    /** Only 408, 429, 5xx, a network failure and an empty 200 body arm the cold ladder; its first rung is three seconds later. */
    @Test fun A2b1_09a_retryableFailuresArmTheLadder() = runTest {
        for ((label, fail) in listOf<Pair<String, (Sent) -> Unit>>(
            "408" to { it.tab.complete(status(408)) },
            "429 without a header" to { it.tab.complete(limited(429, null)) },
            "500" to { it.tab.complete(status(500)) },
            "599" to { it.tab.complete(status(599)) },
            "network" to { it.tab.completeExceptionally(IOException("reset")) },
            "empty 200 body" to { it.tab.completeExceptionally(emptyBodyFailure()) }
        )) {
            val f = ladder()
            f.tabSequence(fail, succeed(USD_3M))
            f.coordinator.onActivated(USD_3M); runCurrent()
            assertEquals(label, 1, f.admittedTabs(USD_3M).size)
            step(2999.milliseconds); assertEquals(label, 1, f.admittedTabs(USD_3M).size)
            step(1.milliseconds); assertEquals(label, 2, f.admittedTabs(USD_3M).size)
            step(297.seconds); assertEquals("$label: settled after the retry succeeded", 2, f.admittedTabs(USD_3M).size)
            f.close()
        }
    }

    /** Any other status, a non-empty undecodable body and an A1 refusal are terminal: no retry. */
    @Test fun A2b1_09b_terminalFailuresDoNotRetry() = runTest {
        for ((label, fail) in listOf<Pair<String, (Sent) -> Unit>>(
            "401" to { it.tab.complete(status(401)) },
            "403" to { it.tab.complete(status(403)) },
            "404" to { it.tab.complete(status(404)) },
            "201" to { it.tab.complete(status(201, tabDto(USD_3M))) },
            "204" to { it.tab.complete(status(204)) },
            "undecodable body" to { it.tab.completeExceptionally(decodeFailure()) },
            "A1 refusal" to { it.tab.complete(ok(tabDto(JPY_3M))) }
        )) {
            val f = ladder()
            f.tabSequence(fail)
            f.coordinator.onActivated(USD_3M); runCurrent()
            step(300.seconds)
            assertEquals(label, 1, f.admittedTabs(USD_3M).size)
            f.close()
        }
    }

    /** Cancellation, an identity change and an unavailable credential are not failures to retry. */
    @Test fun A2b1_09c_cancellationAndAuthenticationEndingsDoNotArm() = runTest {
        for ((label, end) in listOf<Pair<String, (Sent) -> Unit>>(
            "cancelled" to { it.tab.completeExceptionally(CancellationException("gone")) },
            "identity changed" to { it.tab.completeExceptionally(AuthIdentityChangedException()) },
            "credential unavailable" to { it.tab.completeExceptionally(AuthUnavailableException("signed out")) }
        )) {
            val f = ladder()
            f.tabSequence(end)
            f.coordinator.onActivated(USD_3M); runCurrent()
            step(300.seconds)
            assertEquals(label, 1, f.admittedTabs(USD_3M).size)
            f.close()
        }
    }

    /** A failed refresh of an entry already confirmed online is not a cold start: last-good stands, nothing retries. */
    @Test fun A2b1_09d_aConfirmedEntrysFailedRefreshDoesNotArm() = runTest {
        val f = ladder()
        f.tabSequence(succeed(USD_3M), fail503)
        f.coordinator.onActivated(USD_3M); runCurrent()
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        assertEquals(2, f.admittedTabs(USD_3M).size)
        step(300.seconds)
        assertEquals(2, f.admittedTabs(USD_3M).size)
        f.close()

        // A ladder wrongly armed by that failure would hold back the next day's ordinary refresh until its wake.
        val g = ladder(start = Instant.parse("2026-10-05T14:59:58Z")) // 23:59:58 KST
        g.tabSequence(succeed(USD_3M), fail503, succeed(USD_3M))
        g.coordinator.onActivated(USD_3M); runCurrent()
        step(1.seconds); g.coordinator.onRefreshRequested(force = true); runCurrent()
        step(1.seconds); g.coordinator.onRefreshRequested(force = false); runCurrent() // 00:00:00, a new day
        assertEquals(secs(0, 1, 2), g.tabTimes(USD_3M))
        g.close()
    }

    // --- A2b1-10 the cold ladder ------------------------------------------------------------------

    /** 3/6/12/24/48 seconds, each after the previous failure completed; six requests in all, then it stops. */
    @Test fun A2b1_10a_theLadderRunsOnceAndStops() = runTest {
        val f = ladder()
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_3M); runCurrent()
        step(300.seconds)
        assertEquals(secs(0, 3, 9, 21, 45, 93), f.tabTimes(USD_3M))
        f.close()
    }

    /** Spent, it does not re-arm itself; a new outside failure arms it again. */
    @Test fun A2b1_10b_aSpentLadderReArmsOnlyOnANewOutsideFailure() = runTest {
        val f = ladder()
        f.tabSequence(fail503, fail503, fail503, fail503, fail503, fail503, fail503, succeed(USD_3M))
        f.coordinator.onActivated(USD_3M); runCurrent()
        step(300.seconds)
        assertEquals(6, f.admittedTabs(USD_3M).size)
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals(7, f.admittedTabs(USD_3M).size)
        step(3.seconds)
        assertEquals(8, f.admittedTabs(USD_3M).size)
        step(300.seconds)
        assertEquals(8, f.admittedTabs(USD_3M).size)
        f.close()
    }

    /** A success ends the ladder; so does a terminal answer in the middle of it. */
    @Test fun A2b1_10c_successOrATerminalAnswerEndsTheLadder() = runTest {
        val f = ladder()
        f.tabSequence(fail503, fail503, fail503, succeed(USD_3M))
        f.coordinator.onActivated(USD_3M); runCurrent()
        step(300.seconds)
        assertEquals(secs(0, 3, 9, 21), f.tabTimes(USD_3M))
        // The success ended the ladder for good: once the entry is stale (a new KST day), an ordinary refresh goes out.
        step(12.hours)
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals(5, f.admittedTabs(USD_3M).size)
        f.close()

        val g = ladder()
        g.tabSequence(fail503, { it.tab.complete(status(404)) }, succeed(USD_3M))
        g.coordinator.onActivated(USD_3M); runCurrent()
        step(300.seconds)
        assertEquals(secs(0, 3), g.tabTimes(USD_3M))
        // The ended ladder no longer stands in for the key: an ordinary refresh of a stale key goes out.
        g.coordinator.onRefreshRequested(force = false); runCurrent()
        assertEquals(secs(0, 3, 300), g.tabTimes(USD_3M))
        g.close()
    }

    /** While the ladder waits, activating the same key or a non-forced refresh neither cancels nor advances it. */
    @Test fun A2b1_10d_repeatedTriggersKeepTheDeadline() = runTest {
        val f = ladder()
        f.tabSequence(fail503, succeed(USD_3M))
        f.coordinator.onActivated(USD_3M); runCurrent()
        step(1.seconds)
        f.coordinator.onActivated(USD_3M)
        f.coordinator.onRefreshRequested(force = false); runCurrent()
        step(1999.milliseconds); assertEquals(1, f.admittedTabs(USD_3M).size)
        step(1.milliseconds); assertEquals(2, f.admittedTabs(USD_3M).size)
        f.close()
    }

    /** A forced refresh goes at once, and its failure does not restart the ladder. */
    @Test fun A2b1_10e_aForcedFailureDoesNotRestartTheLadder() = runTest {
        val f = ladder()
        f.tabSequence(fail503)
        f.coordinator.onActivated(USD_3M); runCurrent()
        step(1.seconds)
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        step(2500.milliseconds)
        assertEquals(secs(0, 1, 3), f.tabTimes(USD_3M))
        f.close()
    }

    /** The interval counts from when the failure completed, not from when its request left. */
    @Test fun A2b1_10f_theIntervalCountsFromTheCompletion() = runTest {
        val f = ladder()
        f.tabSequence(fail503, fail503, hold, fail503)
        f.coordinator.onActivated(USD_3M); runCurrent()
        step(9.seconds)
        assertEquals(secs(0, 3, 9), f.tabTimes(USD_3M))
        step(1.seconds)
        f.admittedTabs(USD_3M).last().tab.complete(status(503)); runCurrent()
        step(11999.milliseconds); assertEquals(3, f.admittedTabs(USD_3M).size)
        step(1.milliseconds); assertEquals(secs(0, 3, 9, 22), f.tabTimes(USD_3M))
        f.close()
    }

    // --- A2b1-13 the shared rate-limit floor --------------------------------------------------------

    /** Retry-After plus this tab's jitter is a floor every graph GET waits for; waiting spends no rung. */
    @Test fun A2b1_13a_aRetryAfterFloorHoldsTheLadder() = runTest {
        val f = ladder(jitter = 10.seconds)
        f.tabSequence({ it.tab.complete(limited(429, "120")) }, fail503)
        f.coordinator.onActivated(USD_3M); runCurrent()
        step(300.seconds)
        assertEquals(secs(0, 130, 136, 148, 172, 220), f.tabTimes(USD_3M))
        f.close()
    }

    /** Neither another key, another account nor a forced refresh gets under the floor; the waiting demand leaves at the floor. */
    @Test fun A2b1_13b_nothingGetsUnderTheFloor() = runTest {
        val f = ladder(jitter = 10.seconds)
        f.tabSequence({ it.tab.complete(limited(429, "120")) }, succeed(JPY_3M))
        f.coordinator.onActivated(USD_3M); runCurrent()
        step(1.seconds); f.coordinator.onActivated(JPY_3M); runCurrent()
        step(1.seconds); f.fence = sessionFence(uid = "u2"); f.coordinator.onContextChanged(); runCurrent()
        step(1.seconds); f.coordinator.onRefreshRequested(force = true); runCurrent()
        step(126999.milliseconds)
        assertEquals("no tab under the floor", 1, f.tabs().size)
        assertEquals("no catalog under the floor", 1, f.catalogs().size)
        step(1.milliseconds)
        assertEquals(listOf(USD_3M, JPY_3M), f.tabs().map { it.key })
        assertEquals("u2", f.tabs().last().owner.uid)
        assertEquals(2, f.catalogs().size)
        assertEquals("u2", f.catalogs().last().owner.uid)
        f.close()
    }

    /** A 429 without a usable header is a floor of three seconds plus jitter, not a terminal answer. */
    @Test fun A2b1_13c_aHeaderless429IsAShortFloor() = runTest {
        for (header in listOf<String?>(null, "soon")) {
            val f = ladder(jitter = 10.seconds)
            f.tabSequence({ it.tab.complete(limited(429, header)) }, succeed(JPY_3M))
            f.coordinator.onActivated(USD_3M); runCurrent()
            step(1.seconds); f.coordinator.onActivated(JPY_3M); runCurrent()
            step(11999.milliseconds); assertEquals("$header", 1, f.tabs().size)
            step(1.milliseconds); assertEquals("$header", listOf(USD_3M, JPY_3M), f.tabs().map { it.key })
            f.close()
        }
    }

    /** A 429 rung carries the jitter too: with Retry-After 1 the floor is 11 seconds, the rung 3 + 10 = 13. */
    @Test fun A2b1_13h_a429RungCarriesTheJitter() = runTest {
        val f = ladder(jitter = 10.seconds)
        f.tabSequence({ it.tab.complete(limited(429, "1")) }, succeed(USD_3M))
        f.coordinator.onActivated(USD_3M); runCurrent()
        step(12999.milliseconds); assertEquals(1, f.admittedTabs(USD_3M).size)
        step(1.milliseconds); assertEquals(secs(0, 13), f.tabTimes(USD_3M))
        f.close()
    }

    /** A long floor is not shortened. */
    @Test fun A2b1_13d_aLongFloorIsNotCapped() = runTest {
        val f = ladder(jitter = 10.seconds)
        f.tabSequence({ it.tab.complete(limited(429, "259200")) }, succeed(USD_3M))
        f.coordinator.onActivated(USD_3M); runCurrent()
        step(259209999.milliseconds); assertEquals(1, f.admittedTabs(USD_3M).size)
        step(1.milliseconds); assertEquals(2, f.admittedTabs(USD_3M).size)
        f.close()
    }

    /** Answers no longer owned still carry the floor — it belongs to the transport, not to the request. */
    @Test fun A2b1_13e_supersededAnswersStillRaiseTheFloor() = runTest {
        val f = ladder(jitter = 10.seconds)
        f.coordinator.onActivated(USD_3M); runCurrent()
        f.coordinator.onActivated(USD_1Y); runCurrent()
        f.coordinator.onActivated(JPY_3M); runCurrent()
        val (r1, r2, r3) = f.tabs()
        f.fence = sessionFence(grant = 8); f.coordinator.onContextChanged(); runCurrent()
        val r4 = f.tabs().last()
        assertEquals(4, f.tabs().size)

        r1.tab.complete(limited(429, "120")); runCurrent()
        step(1.seconds); r2.tab.completeExceptionally(AuthIdentityChangedException(statusCode = 429, retryAfter = "300")); runCurrent()
        step(1.seconds); r3.tab.complete(limited(429, "60")); runCurrent()
        step(1.seconds); r4.tab.complete(status(503)); runCurrent()
        // 1 + 300 + 10 = 311: the identity change carried the largest floor, and the later 2 + 60 + 10 does not lower it.
        step(307999.milliseconds); assertEquals(4, f.tabs().size)
        step(1.milliseconds)
        assertEquals(5, f.tabs().size)
        assertEquals(JPY_3M, f.tabs().last().key)
        f.close()
    }

    /** A request whose credential was still being captured when the floor arrived waits for it too. */
    @Test fun A2b1_13f_aRequestStillCapturingWaitsForTheFloor() = runTest {
        val f = Fixture(this, NOON) { 10.seconds }
        f.autoCatalog = { it.catalog.complete(limited(429, "120")) }
        f.holdCaptureNumber = 2
        f.coordinator.start(); runCurrent()
        f.coordinator.onActivated(USD_3M); runCurrent()
        assertEquals(1, f.catalogs().size)
        assertEquals(0, f.tabs().size)
        step(1.seconds); f.heldCapture.complete(Unit); runCurrent()
        step(128999.milliseconds); assertEquals(0, f.admittedTabs().size)
        step(1.milliseconds); assertEquals(1, f.admittedTabs(USD_3M).size)
        f.close()

        // Deactivated while it waits for the floor, the unsent request is dropped: it never leaves.
        val g = Fixture(this, NOON) { 10.seconds }
        g.autoCatalog = { it.catalog.complete(limited(429, "120")) }
        g.holdCaptureNumber = 2
        g.coordinator.start(); runCurrent()
        g.coordinator.onActivated(USD_3M); runCurrent()
        step(1.seconds); g.heldCapture.complete(Unit); runCurrent()
        step(1.seconds); g.coordinator.onDeactivated(); runCurrent()
        step(300.seconds)
        assertEquals(0, g.tabs().size)
        g.close()
    }

    /** The floor running out is not a reason to fetch: with the tab answered, nothing more is sent. */
    @Test fun A2b1_13g_theFloorEndingStartsNothing() = runTest {
        val f = Fixture(this, NOON) { 10.seconds }
        f.autoCatalog = { it.catalog.complete(limited(429, "120")) }
        f.tabSequence(succeed(USD_3M))
        f.coordinator.start(); runCurrent()
        f.coordinator.onActivated(USD_3M); runCurrent()
        step(300.seconds)
        assertEquals(1, f.tabs().size)
        assertEquals(1, f.catalogs().size)
        f.close()
    }

    // --- A2b1-14 cold timers and their ownership ----------------------------------------------------

    /** Switching keys ends the old ladder; the new key's ladder is its own. */
    @Test fun A2b1_14a_aSwitchEndsTheOldLadder() = runTest {
        val usd = USD_1D
        val jpy = GraphKey("jpy", GraphPeriod.ONE_DAY)
        val f = ladder()
        f.tabSequence(fail503)
        f.coordinator.onActivated(usd); runCurrent()
        step(1.seconds); f.coordinator.onActivated(jpy); runCurrent()
        step(9.seconds)
        assertEquals(secs(0), f.tabTimes(usd))
        assertEquals(secs(1, 4, 10), f.tabTimes(jpy))
        f.close()
    }

    /** Deactivating, or losing access, ends a waiting ladder. */
    @Test fun A2b1_14b_deactivationOrWithdrawalEndsTheLadder() = runTest {
        for ((label, end) in listOf<Pair<String, (Fixture) -> Unit>>(
            "deactivated" to { it.coordinator.onDeactivated() },
            "access withdrawn" to { it.allowed = false; it.coordinator.onContextChanged() }
        )) {
            val f = ladder()
            f.tabSequence(fail503)
            f.coordinator.onActivated(USD_3M); runCurrent()
            step(1.seconds); end(f); runCurrent()
            step(300.seconds)
            assertEquals(label, 1, f.admittedTabs(USD_3M).size)
            f.close()
        }

        // A new request context ends the old ladder, so the new context's failure arms its own.
        val g = ladder()
        g.tabSequence(fail503, fail503, succeed(USD_3M))
        g.coordinator.onActivated(USD_3M); runCurrent()
        step(1.seconds); g.fence = sessionFence(grant = 8); g.coordinator.onContextChanged(); runCurrent()
        step(2999.milliseconds); assertEquals(secs(0, 1), g.tabTimes(USD_3M))
        step(1.milliseconds); assertEquals(secs(0, 1, 4), g.tabTimes(USD_3M))
        step(300.seconds); assertEquals(secs(0, 1, 4), g.tabTimes(USD_3M))
        g.close()
    }

    /** Another key's late success does not end the current key's ladder. */
    @Test fun A2b1_14c_anotherKeysSuccessLeavesTheLadderAlone() = runTest {
        val usd = USD_1D
        val jpy = GraphKey("jpy", GraphPeriod.ONE_DAY)
        val f = ladder()
        f.tabSequence(hold, fail503)
        f.coordinator.onActivated(usd); runCurrent()
        step(1.seconds); f.coordinator.onActivated(jpy); runCurrent()
        step(1.seconds); f.tabs(usd).single().tab.complete(ok(tabDto(usd))); runCurrent()
        assertTrue(f.state.entries.containsKey(usd))
        step(8.seconds)
        assertEquals(secs(1, 4, 10), f.tabTimes(jpy))
        f.close()
    }

    // --- A2b2-11 the midnight target ---------------------------------------------------------------

    /** 23:50 KST on 2026-10-05, as UTC. */
    private val BEFORE_MIDNIGHT: Instant = Instant.parse("2026-10-05T14:50:00Z")
    private val LONG = listOf(GraphPeriod.ONE_WEEK, GraphPeriod.THREE_MONTHS, GraphPeriod.ONE_YEAR)

    /** A confirmed long-period key, started at [start], answering every request with [answer]. */
    private fun TestScope.confirmed(key: GraphKey, start: Instant, vararg answers: (Sent) -> Unit): Fixture =
        Fixture(this, start).also { f ->
            f.autoCatalog = { s -> s.catalog.complete(ok(wideCatalog())) }
            f.tabSequence(succeed(key), *answers)
            f.coordinator.start(); runCurrent()
            f.coordinator.onActivated(key); runCurrent()
            assertEquals(1, f.admittedTabs(key).size)
        }

    /** Each fixed-start period refreshes at the next 00:02 KST, then again a day later; a 1d key never schedules. */
    @Test fun A2b2_11a_theNextZeroZeroTwoAndTheDayAfter() = runTest {
        for (period in LONG) {
            val key = GraphKey("usd", period)
            val f = confirmed(key, BEFORE_MIDNIGHT, succeed(key))
            step(719999.milliseconds); assertEquals("$period", 1, f.admittedTabs(key).size)
            step(1.milliseconds); assertEquals("$period 00:02", 2, f.admittedTabs(key).size)
            step(86400.seconds); assertEquals("$period the next 00:02", 3, f.admittedTabs(key).size)
            f.close()
        }
        val f = confirmed(USD_1D, BEFORE_MIDNIGHT, succeed(USD_1D))
        step(48.hours)
        assertEquals("1d never schedules", 1, f.admittedTabs(USD_1D).size)
        f.close()
    }

    /** Inside today's window the target is today's 00:02; at 00:02 itself it is tomorrow's. */
    @Test fun A2b2_11b_todaysWindowIsIncluded() = runTest {
        val key = GraphKey("usd", GraphPeriod.ONE_WEEK)
        val f = confirmed(key, Instant.parse("2026-10-05T15:01:00Z"), succeed(key)) // 00:01 KST
        step(59999.milliseconds); assertEquals(1, f.admittedTabs(key).size)
        step(1.milliseconds); assertEquals(2, f.admittedTabs(key).size)
        f.close()
        val g = confirmed(key, Instant.parse("2026-10-05T15:02:00Z"), succeed(key)) // 00:02 KST exactly
        step(86399999.milliseconds); assertEquals(1, g.admittedTabs(key).size)
        step(1.milliseconds); assertEquals(2, g.admittedTabs(key).size)
        g.close()
    }

    /** A fixed-start key the catalog does not offer schedules nothing: no midnight request of any kind. */
    @Test fun A2b2_11d_anUnsupportedKeySchedulesNoMidnight() = runTest {
        val supported = USD_3M
        val unsupported = GraphKey("usd", GraphPeriod.ONE_WEEK)
        val f = Fixture(this, BEFORE_MIDNIGHT)
        f.autoCatalog = { s -> s.catalog.complete(ok(catalogDto(60, periods = mapOf("3m" to listOf(SERIES))))) }
        f.tabSequence(succeed(supported))
        f.coordinator.start(); runCurrent()
        f.coordinator.onActivated(supported); runCurrent()
        f.coordinator.onActivated(unsupported); runCurrent()
        step(1.hours)
        assertEquals("no catalog refresh at 00:02 for a key that is not offered", 1, f.catalogs().size)
        assertEquals(0, f.tabs(unsupported).size)
        f.close()
    }

    /** A wake that finds the wall clock short of the target waits the rest; it does not fetch early. */
    @Test fun A2b2_11c_aClockThatFellBackWaitsTheRest() = runTest {
        val key = GraphKey("usd", GraphPeriod.THREE_MONTHS)
        val f = confirmed(key, BEFORE_MIDNIGHT, succeed(key))
        step(60.seconds); f.skew = (-60).seconds
        step(660.seconds); assertEquals("the virtual deadline passed, the wall clock did not", 1, f.admittedTabs(key).size)
        step(60.seconds); assertEquals(2, f.admittedTabs(key).size)
        f.close()
    }

    // --- A2b2-12 midnight backoff and its owner ------------------------------------------------------

    /** A confirmed key's midnight failures retry at 20, 40 and 80 seconds after each completion, then wait for the next day. */
    @Test fun A2b2_12a_aConfirmedKeysMidnightBackoff() = runTest {
        for ((label, fail) in listOf<Pair<String, (Sent) -> Unit>>(
            "503" to fail503,
            "404" to { it.tab.complete(status(404)) },
            "undecodable" to { it.tab.completeExceptionally(decodeFailure()) },
            "A1 refusal" to { it.tab.complete(ok(tabDto(JPY_3M))) }
        )) {
            val key = GraphKey("usd", GraphPeriod.THREE_MONTHS)
            val f = confirmed(key, BEFORE_MIDNIGHT, fail)
            step(720.seconds)
            step(260.seconds) // to 00:06:20
            assertEquals(label, secs(0, 720, 740, 780, 860), f.tabTimes(key))
            step(86400.seconds - 260.seconds)
            assertEquals("$label: the next day", 6, f.admittedTabs(key).size)
            f.close()
        }
    }

    /** A success ends the backoff. */
    @Test fun A2b2_12b_aSuccessEndsTheBackoff() = runTest {
        val key = GraphKey("usd", GraphPeriod.ONE_WEEK)
        val f = confirmed(key, BEFORE_MIDNIGHT, fail503, fail503, succeed(key))
        step(720.seconds); step(300.seconds)
        assertEquals(secs(0, 720, 740, 780), f.tabTimes(key))
        f.close()
    }

    /**
     * While the midnight backoff waits, a non-forced refresh does not go out on its own, and a forced refresh that
     * fails leaves the backoff's schedule and budget as they were.
     */
    @Test fun A2b2_12e_theBackoffKeepsItsScheduleAgainstOutsideRefreshes() = runTest {
        val key = GraphKey("usd", GraphPeriod.THREE_MONTHS)
        val f = confirmed(key, BEFORE_MIDNIGHT, fail503)
        step(730.seconds); f.coordinator.onRefreshRequested(force = false); runCurrent()
        step(300.seconds)
        assertEquals(secs(0, 720, 740, 780, 860), f.tabTimes(key))
        f.close()

        val g = confirmed(key, BEFORE_MIDNIGHT, fail503)
        step(730.seconds); g.coordinator.onRefreshRequested(force = true); runCurrent()
        step(300.seconds)
        assertEquals(secs(0, 720, 730, 740, 780, 860), g.tabTimes(key))
        g.close()
    }

    /** An unconfirmed key's midnight failure belongs to the cold ladder; there is no midnight backoff on top. */
    @Test fun A2b2_12c_anUnconfirmedKeysMidnightFailureIsCold() = runTest {
        val key = GraphKey("usd", GraphPeriod.ONE_YEAR)
        val f = Fixture(this, BEFORE_MIDNIGHT)
        f.autoCatalog = { s -> s.catalog.complete(ok(wideCatalog())) }
        f.tabSequence({ it.tab.complete(status(404)) }, fail503)
        f.coordinator.start(); runCurrent()
        f.coordinator.onActivated(key); runCurrent()
        step(720.seconds); step(300.seconds)
        assertEquals(secs(0, 720, 723, 729, 741, 765, 813), f.tabTimes(key))
        // Handing the failure to the cold ladder does not drop the next day's midnight.
        step(86399999.milliseconds - 300000.milliseconds); assertEquals(7, f.admittedTabs(key).size)
        step(1.milliseconds); assertEquals(8, f.admittedTabs(key).size)
        f.close()
    }

    /** An unconfirmed key whose midnight request is refused outright has no ladder, yet tomorrow's midnight stays. */
    @Test fun A2b2_12f_anUnconfirmedTerminalMidnightKeepsTomorrow() = runTest {
        val key = GraphKey("usd", GraphPeriod.ONE_YEAR)
        val f = Fixture(this, BEFORE_MIDNIGHT)
        f.autoCatalog = { s -> s.catalog.complete(ok(wideCatalog())) }
        f.tabSequence({ it.tab.complete(status(404)) })
        f.coordinator.start(); runCurrent()
        f.coordinator.onActivated(key); runCurrent()
        step(720.seconds)
        assertEquals(secs(0, 720), f.tabTimes(key))
        step(86399999.milliseconds); assertEquals(2, f.admittedTabs(key).size)
        step(1.milliseconds); assertEquals(secs(0, 720, 87120), f.tabTimes(key))
        f.close()
    }

    /** A header-less 429 at midnight adds this tab's jitter to the midnight interval; a later 503 does not. */
    @Test fun A2b2_12d_aMidnight429CarriesTheJitter() = runTest {
        val key = GraphKey("usd", GraphPeriod.THREE_MONTHS)
        val f = Fixture(this, BEFORE_MIDNIGHT) { 10.seconds }
        f.autoCatalog = { s -> s.catalog.complete(ok(wideCatalog())) }
        f.tabSequence(succeed(key), { it.tab.complete(limited(429, null)) }, fail503)
        f.coordinator.start(); runCurrent()
        f.coordinator.onActivated(key); runCurrent()
        step(720.seconds); step(100.seconds)
        assertEquals(secs(0, 720, 750, 790), f.tabTimes(key))
        f.close()
    }

    // --- A2b2-14 midnight timers and their ownership -------------------------------------------------

    /** Deactivating, or switching to 1d, ends a waiting midnight backoff. */
    @Test fun A2b2_14_aTransitionEndsTheMidnightBackoff() = runTest {
        val key = GraphKey("usd", GraphPeriod.THREE_MONTHS)
        val f = confirmed(key, BEFORE_MIDNIGHT, fail503)
        step(730.seconds); f.coordinator.onDeactivated(); runCurrent()
        step(300.seconds)
        assertEquals(secs(0, 720), f.tabTimes(key))
        f.close()

        val g = confirmed(key, BEFORE_MIDNIGHT, fail503)
        g.tabSequence(fail503, succeed(USD_1D))
        step(730.seconds); g.coordinator.onActivated(USD_1D); runCurrent()
        step(300.seconds)
        assertEquals(secs(0, 720), g.tabTimes(key))
        assertEquals(secs(730), g.tabTimes(USD_1D))
        g.close()
    }

    // --- A2b2-02 joining a request already in flight -------------------------------------------------

    /** At 00:02 a request already in flight is joined: its success meets today's refresh, its failure starts the backoff from its completion. */
    @Test fun A2b2_02_midnightJoinsTheRequestInFlight() = runTest {
        val key = GraphKey("usd", GraphPeriod.THREE_MONTHS)
        for ((label, after, succeeds) in listOf<Triple<String, Int, Boolean>>(
            Triple("success at 00:02:05", 5, true),
            Triple("failure at 00:02:05", 5, false),
            Triple("failure at 00:02:50", 50, false)
        )) {
            val f = confirmed(key, BEFORE_MIDNIGHT, hold, fail503)
            step(719.seconds); f.coordinator.onRefreshRequested(force = true); runCurrent() // 00:01:59
            val r = f.admittedTabs(key).last()
            step(1.seconds) // 00:02:00 due
            assertEquals("$label: the due joins", 2, f.admittedTabs(key).size)
            step(after.seconds)
            if (succeeds) r.tab.complete(ok(tabDto(key))) else r.tab.complete(status(503))
            runCurrent()
            step(600.seconds)
            val expected = if (succeeds) secs(0, 719) else secs(0, 719, 720 + after + 20, 720 + after + 60, 720 + after + 140)
            assertEquals(label, expected, f.tabTimes(key))
            f.close()
        }
    }

    // --- S4 RT03a: protected admission and withheld evidence --------------------------------------------------------

    /**
     * RT03a1: protected admission is part of every use check and is read live. Closed at activation, nothing is captured
     * or sent, catalog included. Closed while a catalog and a tab are out, the wire guard refuses both and their late
     * answers are not adopted.
     */
    @Test fun RT03a1_protectedAdmissionGatesEveryUseCheck() = runTest {
        run {
            val f = started()
            f.protectedOpen = false
            f.coordinator.onActivated(USD_3M); runCurrent()
            assertEquals(emptyList<Sent>(), f.sent)
            assertEquals("no credential is captured", 0, f.captures)
            f.close()
        }
        run {
            val f = started()
            f.coordinator.onActivated(USD_3M); runCurrent()
            val c = f.catalogs().single()
            val t = f.tabs(USD_3M).single()
            assertTrue("premise: both admitted while open", c.admittedAtSend && t.admittedAtSend)
            f.protectedOpen = false
            assertFalse("the catalog's wire guard reads it live", c.useAdmitted())
            assertFalse("the tab's wire guard reads it live", t.useAdmitted())
            c.catalog.complete(ok(wideCatalog()))
            t.tab.complete(ok(tabDto(USD_3M)))
            runCurrent()
            assertNull("no catalog adopted", f.state.catalog)
            assertNull("no tab adopted", f.state.entries[USD_3M])
            f.close()
        }
    }

    /**
     * RT03a2: a withheld send hands over the responses it had already seen, and each one's Retry-After raises the shared
     * floor in order - here only the 429's 120 seconds counts, plus jitter, past the earlier 401 and under the later 503's
     * shorter wait. With nothing seen, a withheld send leaves no floor.
     */
    @Test fun RT03a2_aWithheldSendRaisesTheFloorFromWhatItSaw() = runTest {
        run {
            val f = ladder(jitter = 10.seconds)
            val seen = listOf(
                HttpExchangeEvidence(1, 1, 401, null),
                HttpExchangeEvidence(2, 1, 429, "120"),
                HttpExchangeEvidence(2, 2, 503, "5")
            )
            f.tabSequence({ it.tab.completeExceptionally(TopicUseWithheldException(seen)) }, succeed(JPY_3M))
            f.coordinator.onActivated(USD_3M); runCurrent()
            step(1.seconds); f.coordinator.onActivated(JPY_3M); runCurrent()
            step(128999.milliseconds); assertEquals("nothing under the floor", 1, f.tabs().size)
            step(1.milliseconds); assertEquals(listOf(USD_3M, JPY_3M), f.tabs().map { it.key })
            f.close()
        }
        run {
            val f = ladder(jitter = 10.seconds)
            f.tabSequence({ it.tab.completeExceptionally(TopicUseWithheldException(emptyList())) }, succeed(JPY_3M))
            f.coordinator.onActivated(USD_3M); runCurrent()
            step(1.seconds); f.coordinator.onActivated(JPY_3M); runCurrent()
            assertEquals("nothing seen, no floor", listOf(USD_3M, JPY_3M), f.tabs().map { it.key })
            f.close()
        }
    }

    /**
     * A3c1 (S4 RT01-A3): close() on the serial executor ends a running coordinator at once - before it returns and with no
     * dispatch, the request in flight is refused at the send guard and is gone from the published state - and a late answer
     * applies nothing. A second close, and the loop's own finally running after it, change nothing more and report no
     * failure; no later event is acted on; the supplied scope is not cancelled.
     */
    @Test fun A3c1_closeReleasesARunningCoordinatorAtOnce() = runTest {
        val f = started()
        f.coordinator.onActivated(USD_3M); runCurrent()
        f.catalogs().single().catalog.complete(ok(catalogDto(3600, periods = mapOf("3m" to listOf(SERIES))))); runCurrent()
        val out = f.tabs(USD_3M).single()
        assertTrue("premise: a request in flight, admitted", USD_3M in f.state.inFlight && out.useAdmitted())

        f.coordinator.close()
        assertFalse("refused at the send guard before close returns", out.useAdmitted())
        assertTrue("published before close returns", f.state.inFlight.isEmpty())
        f.coordinator.close()
        out.tab.complete(ok(tabDto(USD_3M))); runCurrent()
        assertFalse("a late answer applies nothing", f.state.entries.containsKey(USD_3M))
        f.coordinator.onActivated(USD_3M); runCurrent()
        assertEquals("no later event is acted on - not even a new request for the released key", 1, f.tabs().size)
        assertTrue("no failure is reported", f.failures.isEmpty())
        assertTrue("close does not cancel the supplied scope", f.scope.isActive)
        f.close()
    }

    /**
     * A3c2 (S4 RT01-A3): close() ends the input whether or not the loop has run. An event enqueued before close is discarded
     * - with the loop never started, and with it started but not yet dispatched - and an event offered after close is never
     * taken, even by a loop that had been started before close. An event taken would publish the context it read, so the
     * published data scope stays empty. start() after close is used here only to give a loop the chance to take an event.
     */
    @Test fun A3c2_closeEndsTheInputWhetherOrNotTheLoopRan() = runTest {
        for ((label, steps) in listOf<Pair<String, Fixture.() -> Unit>>(
            "enqueued, never started" to {
                coordinator.onActivated(USD_3M); coordinator.close(); coordinator.start()
            },
            "started, enqueued before the first dispatch" to {
                coordinator.start(); coordinator.onActivated(USD_3M); coordinator.close()
            },
            "started, offered after close" to {
                coordinator.start(); coordinator.close(); coordinator.onActivated(USD_3M)
            }
        )) {
            val f = Fixture(this)
            f.steps(); runCurrent()
            assertNull("$label: no event was handled - not even the context it would have read", f.state.dataScope)
            assertTrue("$label: nothing is sent", f.sent.isEmpty())
            assertTrue("$label: nothing is in flight", f.state.inFlight.isEmpty())
            assertTrue("$label: no failure is reported", f.failures.isEmpty())
            f.close()
        }
    }
}
