package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeResult
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.purge.GraphRecorderPurgeAdapter
import com.jay.fxi.data.entitlements.purge.ManifestScopePurger
import com.jay.fxi.data.entitlements.purge.PurgeCause
import com.jay.fxi.data.entitlements.purge.PurgeClassification
import com.jay.fxi.data.entitlements.purge.PurgeManifest
import com.jay.fxi.data.entitlements.purge.PurgeRequest
import com.jay.fxi.data.entitlements.purge.PurgeTarget
import com.jay.fxi.data.entitlements.purge.TargetOutcome
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicGraphCandidate
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.data.remote.TopicGraphOffer
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 F2d contract r1 (JVM): the Graph V2 request coordinator handing recovery to the graph recorder - which
 * registration captures recovery requests, applying a successful tab to the recorder synchronously, replaying held inputs
 * once a catalog is adopted, and keeping recorder failures away from the coordinator's own requests and retry owners.
 *
 * Oracles: ANDROID_V2_PLAN.md :1322 (single-flight per scope·authority·tab·period), :1331-1334 (recovery demands survive cache
 * hits, failures and cancellation; only buckets actually supplied are released, by request scope, generation, series and
 * bucket; a gap during a request is a new generation), :1336-1337 (control, observations and responses at one consuming
 * boundary). Design: R4c/S4 f2d_design_codex.r1 cut down by f2d_review_claude.r1 after a three-lens verification
 * (f2d_design_verify_workflow.result.json), agreed as signatures and rows R1-R5 in f2d_design_codex.r2.
 *
 * Rules:
 *  - The coordinator takes an optional, immutable `recorder` (default null: nothing below happens, no supplier is read).
 *  - Only `startTab` captures, once, for a ONE_DAY key with a recorder and an adopted catalog: the keys are the tab's 1d
 *    `allSeries` in the registration's own data scope, captured with the registration's own fence and lifetime and kept on
 *    the registration. Joining an existing registration, waiting under the rate-limit floor and non-1d keys capture nothing;
 *    a registration that found no catalog keeps an empty list and is never captured again.
 *  - On a successful tab completion, after the coordinator's own adoption and before its publish, each kept request is
 *    applied with the adopted tab and the original fence and lifetime, one call per request. Cache hits, failures,
 *    rejections and cancellation apply nothing.
 *  - After an event that adopted a catalog, right after the coordinator publishes it, the recorder's `replayPending()` runs.
 *    A catalog completion sends no extra request. `replayPending()` on a closed recorder reads nothing and changes nothing.
 *  - A recorder failure is isolated per call: a failed capture becomes an empty list and the request goes on; a failed apply
 *    does not stop the coordinator's adoption, its publish or the next request; a failed replay does not undo the catalog.
 *    Each is reported through `onEventFailure`; none cancels the coordinator's retry owners.
 *  - The coordinator loop, every recorder method and the recorder's suppliers run on one serial executor (RT01 re-checks the
 *    production wiring). The recorder's catalog supplier here reads the coordinator's published state.
 *
 * Not here: when recovery requests are triggered, retried or scheduled (RT03/RT05), the recorder's own rules (F1-F2b
 * contracts). The implementation thread reads but does not edit this file.
 *
 * r2 adds the S4 F2e purge rows R6-R9 (design R4c/S4 f2e_design_codex.r1, cut down by f2e_review_claude.r1 after a five-lens
 * verification and agreed in f2e_design_codex.r2; oracles ANDROID_V2_PLAN.md :1025 (the user purge covers the session-memory
 * graph recorder and its recovery requests), :323-324, ScopePurger.kt and ManifestScopePurger.kt). They live in this file so
 * the real coordinator, recorder and gate of its fixture are reused rather than copied:
 *  - `purgeRecoveryCaptures(selects)` drops each registered tab request's captures whose series scope is selected and says
 *    whether any went. It releases, cancels and publishes nothing, and runs on the coordinator's executor.
 *  - `GraphRecorderPurgeAdapter(recorder, sink, coordinator, serialDispatcher)` answers for the manifest target
 *    `memory:graph_recorder` (DERIVED_HERE, user axis only). Before touching anything it answers Failed for a target other
 *    than the manifest's own (compared whole), an axis other than USER or an entry without USER, a null or blank owner or a
 *    namespace owner that differs, and a retired epoch equal to a non-null kept one. A blank epoch is not refused.
 *  - Then it enters `serialDispatcher` with `withContext` - from that executor this does not dispatch again - and, in one
 *    block without suspension, asks the recorder, then the sink, then the coordinator with one selector: the entry's owner and
 *    any epoch but the namespace's current one (every epoch when there is none); the entry's own epoch narrows nothing. A
 *    recorder refusal for a live selected scope is Failed with the sink and the coordinator untouched. Each holder's answer is
 *    taken on its own: Removed when any removed something, NothingToRemove otherwise. Exceptions are not caught; the manifest
 *    purger turns a throw into Failed and lets a cancellation through.
 *  - The three holders come from one assembly on one executor; nothing checks that at run time (RT01 does).
 *  - Completion covers the recorder state, the sink's queue and ledger and the registered captures at that moment. A released
 *    registration still held by an in-flight request, the coordinator's entries and protected slots, a screen holder's
 *    publication and the topic session's graph loss record are runtime cleanup, not this target.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphV2RecoveryRequestContractTest {

    private companion object {
        /** 2026-10-05 12:00 KST. */
        val NOON: Instant = Instant.parse("2026-10-05T03:00:00Z")
        val USD_1D = GraphKey("usd", GraphPeriod.ONE_DAY)
        val USD_3M = GraphKey("usd", GraphPeriod.THREE_MONTHS)
        val B1: Instant = Instant.parse("2026-10-05T02:20:00Z")
        val B2: Instant = Instant.parse("2026-10-05T02:30:00Z")
        val B3: Instant = Instant.parse("2026-10-05T02:40:00Z")
        val SCOPE = GraphDataScope("u1", "e1")
        val KB = GraphObservationSeriesKey(SCOPE, "kb.usd")
        val HANA = GraphObservationSeriesKey(SCOPE, "hana.usd")
        val KB_JPY = GraphObservationSeriesKey(SCOPE, "kb.jpy")

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

    /** GraphV2RequestCoordinatorTest's fixture, field for field, plus a recorder on the same suppliers and executor. */
    private class Fixture(test: TestScope, start: Instant = NOON, withRecorder: Boolean = true) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
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

        val clock = AppClock { start + (test.testScheduler.currentTime - base).milliseconds + skew }

        // --- the recorder, on the same fence and lifetime meaning --------------------------------------------------

        var protectedOpen = true
        /** The next gate admission check throws once. */
        var admissionThrowsOnce = false
        /** The next recorder snapshot read throws once. */
        var snapshotThrowsOnce = false
        /** Every recorder supplier read, after a recorder's close check. */
        var recorderReads = 0

        private fun readSnapshot(): TopicAccessSnapshot {
            recorderReads++
            if (snapshotThrowsOnce) {
                snapshotThrowsOnce = false
                throw IllegalStateException("snapshot")
            }
            return TopicAccessSnapshot(
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
        }

        private fun readAdmission(): Boolean {
            if (admissionThrowsOnce) {
                admissionThrowsOnce = false
                throw IllegalStateException("admission")
            }
            return protectedOpen
        }

        lateinit var coordinator: GraphV2RequestCoordinator

        val gate = GraphV2AccessGate({ owners.currentIdentity() }, { fence }, { readSnapshot() }, { readAdmission() })
        val recorder = GraphRecorder(
            scope, MutableStateFlow(1L), { readSnapshot() }, { recorderReads++; fence },
            { recorderReads++; coordinator.state.value.catalog }, gate, AppClock { recorderReads++; clock.now() }
        )

        init {
            coordinator = GraphV2RequestCoordinator(
                fetcher = fetcher,
                owners = owners,
                currentAccessFence = { fence },
                uses = uses,
                scope = scope,
                clock = clock,
                rateLimitJitter = { Duration.ZERO },
                onEventFailure = { failures += it },
                recorder = if (withRecorder) recorder else null
            )
        }

        /** Built on first use only, so rows without a purge keep their executor queue as it was. */
        val sink by lazy { GraphRecorderTopicSink(scope, recorder) }
        val adapter by lazy { GraphRecorderPurgeAdapter(recorder, sink, coordinator, dispatcher) }

        val state get() = coordinator.state.value
        fun tabs(key: GraphKey? = null) = sent.filter { it.kind == "tab" && (key == null || it.key == key) }
        fun catalogs() = sent.filter { it.kind == "catalog" }
        fun rec() = recorder.state.value
        fun series(key: GraphObservationSeriesKey) = rec().series.getValue(key)

        fun lifetime() = TopicUseLifetime(checkNotNull(fence).grant, invalidations)

        /** A quote of [source] for [currency] at [at], under the current fence and lifetime. */
        fun quote(source: String, at: Instant, currency: String = "usd", rate: Double = 1390.0) =
            TopicGraphInput.Observations(
                1L, "fx:$currency-krw", TopicGraphPath.WS,
                TopicUseAttribution(Any(), checkNotNull(fence), 1L, lifetime()), 1L,
                listOf(TopicGraphCandidate.Quote(source, "$currency-krw", rate, at, null))
            )

        /** A usd quote of [source] owned by [owner], with the lifetime the fixture would give that owner now. */
        fun quoteAs(owner: TopicSessionFence, source: String, at: Instant, rate: Double = 1390.0) = TopicGraphInput.Observations(
            1L, "fx:usd-krw", TopicGraphPath.WS,
            TopicUseAttribution(Any(), owner, 1L, TopicUseLifetime(owner.grant, invalidations)), 1L,
            listOf(TopicGraphCandidate.Quote(source, "usd-krw", rate, at, null))
        )

        /** The gate refuses the quote, so an existing series keeps a HANDOVER_LOSS for its bucket. */
        fun lose(source: String, at: Instant) {
            protectedOpen = false
            recorder.observe(quote(source, at))
            protectedOpen = true
        }

        fun close() = scope.cancel()
    }

    // --- responses -------------------------------------------------------------------------------------------------

    private fun <T> ok(body: T) = AuthenticatedHttpResponse(200, Headers.headersOf(), body, null, byteArrayOf(1))

    private fun <T> status(code: Int, body: T? = null): AuthenticatedHttpResponse<T> {
        val headers = Headers.headersOf()
        val failure = if (code in 200..299) null
        else AuthenticatedHttpFailure(code, headers, byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP)
        return AuthenticatedHttpResponse(code, headers, body, failure, byteArrayOf(1))
    }

    /** usd lists kb and hana for 1d (kb alone shown by default) and kb for 3m; jpy lists kb for 1d. */
    private fun catalog() = GraphV2CatalogResponse(
        tabs = listOf(
            GraphV2CatalogTab(
                "usd", "usd", emptyMap(), mapOf(
                    "1d" to GraphV2CatalogPeriod(listOf("kb.usd", "hana.usd"), listOf("kb.usd")),
                    "3m" to GraphV2CatalogPeriod(listOf("kb.usd"), listOf("kb.usd"))
                )
            ),
            GraphV2CatalogTab("jpy", "jpy", emptyMap(), mapOf("1d" to GraphV2CatalogPeriod(listOf("kb.jpy"), listOf("kb.jpy"))))
        ),
        version = "2026-05-27",
        supportedPeriods = listOf("1w", "3m", "1y"),
        cacheTtlSeconds = 172800
    )

    /** A 10-minute usd 1d tab whose series carry closed points at the given starts. */
    private fun dayTab(points: Map<String, List<Instant>>) = GraphV2TabResponse(
        tab = "usd",
        period = GraphPeriod.ONE_DAY.code,
        series = points.map { (id, starts) ->
            GraphV2Series(
                id, id, "krw", "KRW", 2,
                starts.map { GraphV2Point(it, 1390.0, "x") },
                GraphV2Provenance(false, emptyList()), null
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

    private val opened = mutableListOf<Fixture>()

    private fun recoveryTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        try {
            body()
        } finally {
            opened.forEach { it.close() }
            opened.clear()
        }
    }

    /** Started, with the catalog adopted through a settled usd 3m request; kb exists with losses at B1 and B2. */
    private fun TestScope.ready(withRecorder: Boolean = true): Fixture = Fixture(this, withRecorder = withRecorder).also { f ->
        opened += f
        f.autoCatalog = { s -> s.catalog.complete(ok(catalog())) }
        f.coordinator.start(); runCurrent()
        f.coordinator.onActivated(USD_3M); runCurrent()
        f.tabs(USD_3M).single().tab.complete(ok(threeMonthTab())); runCurrent()
        assertNotNull("premise: catalog adopted", f.state.catalog)
        f.recorder.observe(f.quote("kb", NOON - 5.seconds))
        f.lose("kb", B1 + 60.seconds)
        f.lose("kb", B2 + 60.seconds)
        assertTrue("premise: kb has losses at B1 and B2", B1 in f.series(KB).pending && B2 in f.series(KB).pending)
    }

    // --- R1 ----------------------------------------------------------------------------------------------------------

    /**
     * R1: a usd 1d registration captures every usd 1d catalog series with a demand - hana included although not shown by
     * default - and nothing of jpy; a usd 3m registration with the catalog present captures nothing.
     */
    @Test fun R1_aOneDayRegistrationCapturesItsTabsPendingSeriesOnly() = recoveryTest {
        val f = ready()
        f.recorder.observe(f.quote("hana", NOON - 4.seconds))
        f.recorder.observe(f.quote("kb", NOON - 3.seconds, currency = "jpy"))
        assertTrue("premise: hana and kb.jpy have demands", f.series(HANA).pending.isNotEmpty() && f.series(KB_JPY).pending.isNotEmpty())

        val before3m = f.rec().nextVersion
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        assertEquals("premise: a new 3m request", 2, f.tabs(USD_3M).size)
        assertEquals("a 3m registration captures nothing", before3m, f.rec().nextVersion)

        f.coordinator.onActivated(USD_1D); runCurrent()
        assertEquals(1, f.tabs(USD_1D).size)
        assertEquals("kb.usd and hana.usd, not kb.jpy", before3m + 2, f.rec().nextVersion)
    }

    // --- R2 ----------------------------------------------------------------------------------------------------------

    /**
     * R2: the requests are those captured at registration. A newer demand (B3), a new series and a join while the credential
     * capture is held change neither the requests nor the issued version; a response with B1 and B3 but not B2 releases B1
     * only, and the recorder has it before the coordinator first publishes the new entry.
     */
    @Test fun R2_theRegistrationsOwnCaptureIsAppliedBeforeThePublish() = recoveryTest {
        val f = ready()
        f.holdCaptureNumber = f.captures + 1
        f.coordinator.onActivated(USD_1D); runCurrent()
        assertTrue("premise: the credential capture is held", f.tabs(USD_1D).isEmpty())
        val captured = f.series(KB).generation
        val issued = f.rec().nextVersion

        f.lose("kb", B3 + 60.seconds)
        f.recorder.observe(f.quote("hana", NOON - 4.seconds))
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        assertEquals("the join captured nothing", issued, f.rec().nextVersion)
        assertTrue("premise: B1 and B2 at or below the capture", f.series(KB).pending.getValue(B1).generation <= captured &&
            f.series(KB).pending.getValue(B2).generation <= captured)
        assertTrue("premise: B3 after the capture", f.series(KB).pending.getValue(B3).generation > captured)

        val atFirstPublish = mutableListOf<Set<Instant>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.coordinator.state.collect { s ->
                if (USD_1D in s.entries && atFirstPublish.isEmpty()) atFirstPublish += f.series(KB).pending.keys
            }
        }
        f.heldCapture.complete(Unit); runCurrent()
        f.tabs(USD_1D).single().tab.complete(ok(dayTab(mapOf("kb.usd" to listOf(B1, B3), "hana.usd" to emptyList())))); runCurrent()

        val pending = f.series(KB).pending.keys
        assertFalse("B1 supplied at the captured generation", B1 in pending)
        assertTrue("B2 not supplied", B2 in pending)
        assertTrue("B3 newer than the capture", B3 in pending)
        assertTrue("hana was created after the capture", f.series(HANA).pending.isNotEmpty())
        assertEquals("applied before the first publish", 1, atFirstPublish.size)
        assertFalse(B1 in atFirstPublish.single())
    }

    // --- R3 ----------------------------------------------------------------------------------------------------------

    /** R3: a cache hit, a network failure, HTTP 500, a rejected tab and a cancellation apply nothing to the recorder. */
    @Test fun R3_noSuccessNoApplication() = recoveryTest {
        for ((label, fail) in listOf<Pair<String, (Sent) -> Unit>>(
            "network" to { it.tab.completeExceptionally(IOException("reset")) },
            "500" to { it.tab.complete(status(500)) },
            "rejected" to { it.tab.complete(ok(threeMonthTab())) },
            "cancelled" to { it.tab.completeExceptionally(CancellationException("cancelled")) }
        )) {
            val f = ready()
            f.coordinator.onActivated(USD_1D); runCurrent()
            val afterCapture = f.rec()
            fail(f.tabs(USD_1D).single()); runCurrent()
            assertSame(label, afterCapture, f.rec())
        }

        val f = ready()
        f.coordinator.onActivated(USD_1D); runCurrent()
        f.tabs(USD_1D).single().tab.complete(ok(dayTab(mapOf("kb.usd" to listOf(B1))))); runCurrent()
        f.lose("kb", B3 + 60.seconds)
        f.coordinator.onActivated(USD_3M); runCurrent()
        val beforeHit = f.rec()
        val sends = f.tabs(USD_1D).size
        f.coordinator.onActivated(USD_1D); runCurrent()
        assertEquals("premise: a fresh entry, no request", sends, f.tabs(USD_1D).size)
        assertSame("a cache hit", beforeHit, f.rec())
    }

    // --- R4 ----------------------------------------------------------------------------------------------------------

    /**
     * R4: an input held before the catalog is replayed with the adopted catalog once the coordinator has published it, and the
     * completion sends nothing more; a closed recorder stays empty and reads nothing through a catalog and a tab completion.
     */
    @Test fun R4_aCatalogReplaysHeldInputsAndAClosedRecorderStaysShut() = recoveryTest {
        val f = Fixture(this).also { opened += it }
        f.coordinator.start(); runCurrent()
        f.recorder.observe(f.quote("kb", NOON - 5.seconds))
        assertEquals("premise: held without a catalog", 1, f.rec().pending.inputs.size)
        f.coordinator.onActivated(USD_1D); runCurrent()
        val tabSends = f.tabs().size
        f.catalogs().single().catalog.complete(ok(catalog())); runCurrent()
        assertTrue("replayed", f.rec().pending.inputs.isEmpty())
        assertTrue("with the adopted catalog", KB in f.rec().series)
        assertEquals("a catalog completion sends nothing more", tabSends, f.tabs().size)

        val g = Fixture(this).also { opened += it }
        g.coordinator.start(); runCurrent()
        g.recorder.close()
        val shut = g.rec()
        val reads = g.recorderReads
        g.coordinator.onActivated(USD_1D); runCurrent()
        g.catalogs().single().catalog.complete(ok(catalog())); runCurrent()
        g.tabs(USD_1D).single().tab.complete(ok(dayTab(mapOf("kb.usd" to listOf(B1))))); runCurrent()
        assertTrue("the coordinator adopted the tab", USD_1D in g.state.entries)
        assertEquals("nothing read after close", reads, g.recorderReads)
        assertSame("the closed recorder's state is untouched", shut, g.rec())
        assertTrue(shut.series.isEmpty() && shut.pending.inputs.isEmpty())
    }

    // --- R5 ----------------------------------------------------------------------------------------------------------

    /**
     * R5: a failed capture still sends and releases its key; a failed apply neither stops the coordinator's adoption and publish
     * nor the next request; a failed replay keeps the catalog and leaves a cold retry owner armed. Each failure is reported.
     */
    @Test fun R5_recorderFailuresStayInTheRecorder() = recoveryTest {
        ready().let { f ->
            f.admissionThrowsOnce = true
            f.coordinator.onActivated(USD_1D); runCurrent()
            assertEquals("the request is sent", 1, f.tabs(USD_1D).size)
            assertEquals(1, f.failures.size)
            f.tabs(USD_1D).single().tab.complete(ok(dayTab(mapOf("kb.usd" to listOf(B1))))); runCurrent()
            assertTrue(USD_1D in f.state.entries)
            assertTrue("nothing was captured, so nothing applied", B1 in f.series(KB).pending)
            f.coordinator.onRefreshRequested(force = true); runCurrent()
            assertEquals("the key was released", 2, f.tabs(USD_1D).size)
        }
        ready().let { f ->
            f.recorder.observe(f.quote("hana", NOON - 4.seconds))
            f.lose("hana", B1 + 60.seconds)
            f.coordinator.onActivated(USD_1D); runCurrent()
            val published = mutableListOf<Boolean>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.coordinator.state.collect { s -> published += USD_1D in s.entries }
            }
            f.admissionThrowsOnce = true
            f.tabs(USD_1D).single().tab.complete(ok(dayTab(mapOf("kb.usd" to listOf(B1), "hana.usd" to listOf(B1))))); runCurrent()
            assertTrue("the coordinator adopted and published", USD_1D in f.state.entries && published.last())
            assertEquals(1, f.failures.size)
            val released = listOf(KB, HANA).count { B1 !in f.series(it).pending }
            assertEquals("one apply failed, the other request was still applied", 1, released)
        }
        Fixture(this).also { opened += it }.let { f ->
            f.coordinator.start(); runCurrent()
            f.coordinator.onActivated(USD_1D); runCurrent()
            f.tabs(USD_1D).single().tab.complete(status(503)); runCurrent()
            f.snapshotThrowsOnce = true
            f.catalogs().single().catalog.complete(ok(catalog())); runCurrent()
            assertNotNull("the catalog stays adopted", f.state.catalog)
            assertEquals(1, f.failures.size)
            advanceTimeBy(3_000); runCurrent()
            assertEquals("the cold retry still runs", 2, f.tabs(USD_1D).size)
        }
    }

    // --- F2e: purge ----------------------------------------------------------------------------------------------------

    private val memoryTarget: PurgeTarget get() = checkNotNull(PurgeManifest.byId(GraphRecorderPurgeAdapter.TARGET_ID))

    /** A user-axis request retiring u1's e1 while e2 is current, unless told otherwise. */
    private fun request(
        owner: String? = "u1",
        keep: String? = "e2",
        pendingEpoch: String? = "e1",
        axis: PurgeScope = PurgeScope.USER,
        scopes: Set<PurgeScope> = setOf(PurgeScope.USER),
        namespaceOwner: String? = owner,
        target: PurgeTarget = memoryTarget
    ) = PurgeRequest(
        target, axis, PurgeCause.UNKNOWN,
        PurgeNamespace(namespaceOwner, keep, null, PendingPurge(owner, pendingEpoch, null, scopes))
    )

    /**
     * Offers [inputs] and then purges with each of [requests] in one task on the fixture's executor, so the sink's worker, woken
     * by the offers, runs only after them.
     */
    private fun TestScope.inOneTurn(
        f: Fixture,
        vararg requests: PurgeRequest,
        inputs: List<TopicGraphInput> = emptyList()
    ): List<TargetOutcome> {
        val run = f.scope.async {
            inputs.forEach { assertEquals(TopicGraphOffer.ENQUEUED, f.sink.tryOffer(it)) }
            requests.map { f.adapter.purge(it) }
        }
        runCurrent()
        return run.getCompleted()
    }

    // --- R6 ----------------------------------------------------------------------------------------------------------

    /**
     * R6 (F2e C4): a purge drops the registered captures whose series scope is selected and nothing else - the registration,
     * its in-flight key and the coordinator's own adoption stay, and the recorder gets nothing applied; an unselected purge drops
     * nothing and the capture is applied as usual; a coordinator without a recorder has nothing to drop.
     */
    @Test fun R6_aPurgeDropsTheSelectedCapturesOnly() = recoveryTest {
        ready().let { f ->
            f.coordinator.onActivated(USD_1D); runCurrent()
            assertEquals("premise: sent", 1, f.tabs(USD_1D).size)
            assertTrue(f.coordinator.purgeRecoveryCaptures { it == SCOPE })
            assertFalse("nothing selected is left", f.coordinator.purgeRecoveryCaptures { it == SCOPE })
            assertEquals("no new request", 1, f.tabs(USD_1D).size)
            assertTrue("still in flight", USD_1D in f.state.inFlight)
            f.tabs(USD_1D).single().tab.complete(ok(dayTab(mapOf("kb.usd" to listOf(B1))))); runCurrent()
            assertTrue("the coordinator still adopts its answer", USD_1D in f.state.entries)
            assertTrue("nothing applied to the recorder", B1 in f.series(KB).pending)
        }
        ready().let { f ->
            f.coordinator.onActivated(USD_1D); runCurrent()
            assertFalse(f.coordinator.purgeRecoveryCaptures { it.userAccessEpoch == "e2" })
            f.tabs(USD_1D).single().tab.complete(ok(dayTab(mapOf("kb.usd" to listOf(B1))))); runCurrent()
            assertFalse("applied as usual", B1 in f.series(KB).pending)
        }
        ready(withRecorder = false).let { f ->
            f.coordinator.onActivated(USD_1D); runCurrent()
            assertFalse(f.coordinator.purgeRecoveryCaptures { true })
        }
    }

    // --- R7 ----------------------------------------------------------------------------------------------------------

    /**
     * R7 (F2e A1): another or a forged target, another axis, an entry without the user axis, a missing, blank or mismatched
     * owner, or a retired epoch equal to the kept one is refused before any holder is touched. A current fence naming a selected
     * scope - read when the purge runs on the executor, not when it is called - is refused before the sink is touched. Each time
     * a valid purge afterwards still finds what was there.
     */
    @Test fun R7_refusalsTouchNothing() = recoveryTest {
        val refused = listOf(
            "another target" to request(target = checkNotNull(PurgeManifest.byId("file:graph_v2_general"))),
            "a forged target" to request(target = memoryTarget.copy(classification = PurgeClassification.NOT_USER_DATA)),
            "the capability axis" to request(axis = PurgeScope.CAPABILITY),
            "an entry without the user axis" to request(scopes = setOf(PurgeScope.CAPABILITY)),
            "no owner" to request(owner = null),
            "an empty owner" to request(owner = ""),
            "a blank owner" to request(owner = " "),
            "another namespace owner" to request(namespaceOwner = "u2"),
            "the kept epoch as retired" to request(keep = "e2", pendingEpoch = "e2")
        )
        // Only the sink holds e1.
        Fixture(this).also { opened += it }.let { f ->
            f.fence = null
            for ((label, bad) in refused) {
                val (first, second) = inOneTurn(f, bad, request(), inputs = listOf(f.quoteAs(sessionFence(), "kb", NOON - 5.seconds)))
                assertTrue("$label: refused, was $first", first is TargetOutcome.Failed)
                assertEquals("$label: the sink kept its input", TargetOutcome.Removed, second)
            }
        }
        // Only the recorder holds e1.
        ready().let { f ->
            f.fence = null
            val before = f.rec()
            for ((label, bad) in refused) {
                assertTrue(label, inOneTurn(f, bad).single() is TargetOutcome.Failed)
                assertSame("$label: the recorder is untouched", before, f.rec())
            }
            assertEquals(listOf(TargetOutcome.Removed), inOneTurn(f, request()))
        }
        // Only a registered capture holds e1.
        ready().let { f ->
            f.coordinator.onActivated(USD_1D); runCurrent()
            f.fence = sessionFence(epoch = "e2")
            f.recorder.replayPending()
            assertTrue("premise: the recorder moved to e2 with nothing",
                f.rec().scope == GraphDataScope("u1", "e2") && f.rec().series.isEmpty())
            for ((label, bad) in refused) assertTrue(label, inOneTurn(f, bad).single() is TargetOutcome.Failed)
            assertEquals("the capture outlived every refusal", listOf(TargetOutcome.Removed), inOneTurn(f, request()))
        }
        // The current fence still names e1, or any epoch of u1 when nothing is kept.
        Fixture(this).also { opened += it }.let { f ->
            val item = f.quoteAs(sessionFence(), "kb", NOON - 5.seconds)
            val run = f.scope.async {
                assertEquals(TopicGraphOffer.ENQUEUED, f.sink.tryOffer(item))
                val live = f.adapter.purge(request())
                val noKeep = f.adapter.purge(request(keep = null, pendingEpoch = "e0"))
                f.fence = null
                listOf(live, noKeep, f.adapter.purge(request()))
            }
            runCurrent()
            val (live, noKeep, after) = run.getCompleted()
            assertTrue("a live retired scope, was $live", live is TargetOutcome.Failed)
            assertTrue("a live scope of the owner with nothing kept, was $noKeep", noKeep is TargetOutcome.Failed)
            assertEquals("the sink kept its input", TargetOutcome.Removed, after)
        }
        // The fence turns live after the call and before the purge runs on the executor.
        Fixture(this).also { opened += it }.let { f ->
            f.fence = null
            f.scope.launch { f.fence = sessionFence() }
            val call = async(UnconfinedTestDispatcher(testScheduler)) { f.adapter.purge(request()) }
            assertFalse("premise: the purge waits for the executor", call.isCompleted)
            runCurrent()
            assertTrue("judged when it ran, was ${call.getCompleted()}", call.getCompleted() is TargetOutcome.Failed)
        }
    }

    // --- R8 ----------------------------------------------------------------------------------------------------------

    /**
     * R8 (F2e A2): one purge answers for the recorder, the sink and the registered captures together. It removes what any of
     * them holds for any retired epoch of the owner - the entry's named epoch or not, and every one for an entry without an epoch
     * - and keeps the current one: Removed when anything went, NothingToRemove otherwise. An immediate second purge finds
     * nothing, so no holder was skipped.
     */
    @Test fun R8_onePurgeAnswersForAllThreeHolders() = recoveryTest {
        val e2 = sessionFence(epoch = "e2")
        val kbE2 = GraphObservationSeriesKey(GraphDataScope("u1", "e2"), "kb.usd")
        val twice = listOf(TargetOutcome.Removed, TargetOutcome.NothingToRemove)
        // All three hold e1 and there is no current fence.
        ready().let { f ->
            f.coordinator.onActivated(USD_1D); runCurrent()
            val item = f.quoteAs(sessionFence(), "hana", NOON - 2.seconds)
            f.fence = null
            assertEquals(twice, inOneTurn(f, request(), request(), inputs = listOf(item)))
            assertNull(f.rec().scope)
            assertTrue(f.rec().series.isEmpty())
            assertTrue("the registration stays", USD_1D in f.state.inFlight)
        }
        // Only the sink holds e1; the recorder's e2 data is not touched.
        ready().let { f ->
            f.fence = e2
            f.recorder.observe(f.quote("kb", NOON - 1.seconds))
            assertTrue("premise: kb under e2", kbE2 in f.rec().series)
            val before = f.rec()
            assertEquals(twice, inOneTurn(f, request(), request(), inputs = listOf(f.quoteAs(sessionFence(), "kb", NOON - 1.seconds))))
            assertSame(before, f.rec())
        }
        // Only a registered capture holds e1.
        ready().let { f ->
            f.coordinator.onActivated(USD_1D); runCurrent()
            f.fence = e2
            f.recorder.replayPending()
            assertTrue("premise: the recorder moved to e2 with nothing",
                f.rec().scope == GraphDataScope("u1", "e2") && f.rec().series.isEmpty())
            assertEquals(twice, inOneTurn(f, request(), request()))
            assertTrue("the registration stays", USD_1D in f.state.inFlight)
        }
        // Nothing of a retired epoch anywhere.
        ready().let { f ->
            f.fence = e2
            f.recorder.observe(f.quote("kb", NOON - 1.seconds))
            val before = f.rec()
            assertEquals(listOf(TargetOutcome.NothingToRemove), inOneTurn(f, request()))
            assertSame(before, f.rec())
        }
        // The entry names e1 but the recorder holds e3.
        ready().let { f ->
            f.fence = sessionFence(epoch = "e3")
            f.recorder.observe(f.quote("kb", NOON - 1.seconds))
            f.fence = null
            assertEquals(twice, inOneTurn(f, request(), request()))
            assertNull(f.rec().scope)
        }
        // A blank retired epoch is not refused; it narrows nothing either.
        ready().let { f ->
            f.fence = null
            assertEquals(listOf(TargetOutcome.Removed), inOneTurn(f, request(pendingEpoch = " ")))
            assertNull(f.rec().scope)
        }
        // The current fence belongs to another user: not a scope this purge selects, so it goes on.
        Fixture(this).also { opened += it }.let { f ->
            f.fence = sessionFence(uid = "u2")
            val item = f.quoteAs(sessionFence(), "kb", NOON - 5.seconds)
            assertEquals(listOf(TargetOutcome.Removed), inOneTurn(f, request(), inputs = listOf(item)))
        }
        // An entry without an epoch: e1 goes; the current e2 and another user's input stay.
        ready().let { f ->
            f.fence = e2
            f.recorder.observe(f.quote("kb", NOON - 3.seconds))
            val unnamed = request(pendingEpoch = null)
            val inputs = listOf(
                f.quoteAs(sessionFence(), "kb", NOON - 2.seconds),
                f.quoteAs(e2, "kb", NOON - 1.seconds, rate = 1391.0),
                f.quoteAs(sessionFence(uid = "u2"), "kb", NOON - 1.seconds)
            )
            assertEquals(twice, inOneTurn(f, unnamed, unnamed, inputs = inputs))
            assertTrue("the kept input was delivered",
                f.rec().series.getValue(kbE2).data.app.observations.any { it.rate == 1391.0 })
        }
    }

    // --- R9 ----------------------------------------------------------------------------------------------------------

    /**
     * R9 (F2e A3): the manifest purger counts the memory target for itself only. Beside an unregistered disk target the user
     * purge stays Deferred, naming the disk target and not this one, while this target's data is gone; alone it completes; the
     * capability axis never reaches it.
     */
    @Test fun R9_theManifestPurgerCountsTheMemoryTargetForItselfOnly() = recoveryTest {
        val general = checkNotNull(PurgeManifest.byId("file:graph_v2_general"))
        val namespace = request().namespace
        ready().let { f ->
            f.fence = null
            val result = ManifestScopePurger(mapOf(GraphRecorderPurgeAdapter.TARGET_ID to f.adapter), listOf(memoryTarget, general))
                .purgeUserScope(namespace)
            assertTrue("deferred, was $result", result is PurgeResult.Deferred)
            val reason = (result as PurgeResult.Deferred).reason
            assertTrue(reason, "file:graph_v2_general(no adapter)" in reason)
            assertFalse(reason, GraphRecorderPurgeAdapter.TARGET_ID in reason)
            assertNull("this target's data is gone", f.rec().scope)
        }
        ready().let { f ->
            f.fence = null
            assertEquals(
                PurgeResult.Completed,
                ManifestScopePurger(mapOf(GraphRecorderPurgeAdapter.TARGET_ID to f.adapter), listOf(memoryTarget)).purgeUserScope(namespace)
            )
        }
        ready().let { f ->
            f.fence = null
            val before = f.rec()
            val both = namespace.copy(
                pending = namespace.pending.copy(krxCapabilityEpoch = "k1", scopes = setOf(PurgeScope.USER, PurgeScope.CAPABILITY))
            )
            val result = ManifestScopePurger(mapOf(GraphRecorderPurgeAdapter.TARGET_ID to f.adapter)).purgeCapabilityScope(both)
            assertTrue("not a failure, was $result", result !is PurgeResult.Failed)
            assertSame("the capability axis never reaches the recorder", before, f.rec())
        }
    }
}
