package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.graph.DefaultGraphV2AtomicFileIo
import com.jay.fxi.data.graph.FileGraphV2DiskStore
import com.jay.fxi.data.graph.GraphCapabilityScope
import com.jay.fxi.data.graph.GraphDataScope
import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphObservationSeriesKey
import com.jay.fxi.data.graph.GraphOwnerSource
import com.jay.fxi.data.graph.GraphRecorder
import com.jay.fxi.data.graph.GraphRuntimeAssembly
import com.jay.fxi.data.graph.GraphRuntimeRetirement
import com.jay.fxi.data.graph.GraphSeriesSelectionSession
import com.jay.fxi.data.graph.GraphV2AccessCapture
import com.jay.fxi.data.graph.GraphV2AccessGate
import com.jay.fxi.data.graph.GraphV2CachePorts
import com.jay.fxi.data.graph.GraphV2ComponentWriteOutcome
import com.jay.fxi.data.graph.GraphV2DiskComponent
import com.jay.fxi.data.graph.GraphV2DiskRead
import com.jay.fxi.data.graph.GraphV2DiskStore
import com.jay.fxi.data.graph.GraphV2Domain
import com.jay.fxi.data.graph.GraphV2Fetching
import com.jay.fxi.data.graph.GraphV2GeneralEnvelope
import com.jay.fxi.data.graph.GraphV2GeneralKey
import com.jay.fxi.data.graph.GraphV2IoAdmission
import com.jay.fxi.data.graph.GraphV2KrxKey
import com.jay.fxi.data.graph.GraphV2NamespacePreparation
import com.jay.fxi.data.graph.GraphV2PrepareWrite
import com.jay.fxi.data.graph.GraphV2RequestCoordinator
import com.jay.fxi.data.graph.GraphV2Validation
import com.jay.fxi.data.graph.GraphV2WritePorts
import com.jay.fxi.data.graph.GraphV2WritePreparation
import com.jay.fxi.data.graph.GraphV2WriteReport
import com.jay.fxi.data.graph.GraphV2WriteReservation
import com.jay.fxi.data.graph.GraphV2WriteTicket
import com.jay.fxi.data.graph.JsonGraphV2EnvelopeCodec
import com.jay.fxi.data.graph.joinGraphV2Components
import com.jay.fxi.data.graph.splitGraphV2ServerTab
import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionKey
import com.jay.fxi.data.local.GraphSelectionReadResult
import com.jay.fxi.data.local.GraphSelectionRecord
import com.jay.fxi.data.local.GraphSelectionStore
import com.jay.fxi.data.local.GraphSelectionWriteResult
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicGrantOrigin
import com.jay.fxi.data.remote.TopicGraphCandidate
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.data.remote.TopicGraphPath
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAttribution
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
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphSeriesSelection
import com.jay.fxi.domain.model.GraphTabAdmission
import com.jay.fxi.time.AppClock
import com.jay.fxi.ui.premium.graph.GraphRuntimeRetirementPort
import com.jay.fxi.ui.premium.graph.GraphV2Content
import com.jay.fxi.ui.premium.graph.GraphV2ScreenState
import com.jay.fxi.ui.premium.graph.GraphV2ScreenStateHolder
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.jay.fxi.data.remote.TopicGraphRecoveryPermit
import java.io.File

/** β r2 §4 ON pairs. A KRX-only rotation preserves GENERAL token/use and user scope.
 * Helpers are local copies of PATST assemblies; the OFF original is left byte-for-byte intact.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class PremiumAccessKrxOnConsumerContractTest {
    private val processJob = SupervisorJob()
    private val checked = mutableListOf<KrxBetaPac>()
    private var row = "ON-P"
    private fun contract(message: String) = "contract($row): $message"
    private val evidenceRoot = File(if (File("app/src/test").isDirectory) ".beta_evidence/b1a/tmp" else "../.beta_evidence/b1a/tmp").apply { mkdirs() }
    @get:Rule val folder = TemporaryFolder(evidenceRoot)
    private class Store(private val ids: EpochIdGenerator) : AccessEpochStore {
        var record = AccessEpochRecord()

        /** The next this many loads throw. */
        var loadFailures = 0

        /** The next this many rotations throw [rotationError] before writing. */
        var rotationFailures = 0

        /** The next this many rotations write and then throw [rotationError]. */
        var rotationLandsThenThrows = 0

        /** What a failing rotation throws: an I/O failure, or the store's own cancellation. */
        var rotationError: () -> Throwable = { IOException("rotation") }

        /** When a rotation throws, the load that reads it back throws [readBackError]. */
        var failTheReadBack = false
        var readBackError: () -> Throwable = { IOException("read-back") }

        /** Parks the next rotation before it writes. */
        var rotationGate: CompletableDeferred<Unit>? = null
        val rotationParked = CompletableDeferred<Unit>()

        /** Parks the next rotation after it has written, before it returns or throws. */
        var landedGate: CompletableDeferred<Unit>? = null
        val landedParked = CompletableDeferred<Unit>()

        /** When a rotation throws, parks the load that reads it back. */
        var readBackGate: CompletableDeferred<Unit>? = null
        val readBackParked = CompletableDeferred<Unit>()

        /** A rotation threw, so the next load is its read-back. */
        private var readBackDue = false

        /** Every load asked for, whatever it came to. */
        var loadCalls = 0
            private set

        override suspend fun load(): AccessEpochRecord {
            loadCalls += 1
            if (readBackDue) {
                readBackDue = false
                readBackGate?.let { gate ->
                    readBackGate = null
                    readBackParked.complete(Unit)
                    gate.await()
                }
                if (failTheReadBack) throw readBackError()
            }
            if (loadFailures > 0) {
                loadFailures -= 1
                throw IOException("load")
            }
            return record
        }
        override suspend fun bindOwner(uid: String) = AccessEpochTransitions.bindOwner(record, uid, ids).also { record = it }
        override suspend fun signOut() = AccessEpochTransitions.signOut(record, ids).also { record = it }
        override suspend fun retireUnverifiedStart() =
            AccessEpochTransitions.retireUnverifiedStart(record, ids).also { record = it }
        override suspend fun beginSignOut(uid: String) = AccessEpochTransitions.beginSignOut(record, uid).also { record = it }
        override suspend fun beginRotation(rotateUser: Boolean, rotateKrx: Boolean): AccessEpochRecord {
            rotationGate?.let { gate ->
                rotationGate = null
                rotationParked.complete(Unit)
                gate.await()
            }
            if (rotationFailures > 0) {
                rotationFailures -= 1
                readBackDue = true
                throw rotationError()
            }
            record = AccessEpochTransitions.rotate(record, rotateUser, rotateKrx, ids)
            landedGate?.let { gate ->
                landedGate = null
                landedParked.complete(Unit)
                gate.await()
            }
            if (rotationLandsThenThrows > 0) {
                rotationLandsThenThrows -= 1
                readBackDue = true
                throw rotationError()
            }
            return record
        }
        override suspend fun completePurges(completed: Collection<PendingPurge>) =
            AccessEpochTransitions.completePurges(record, completed).also { record = it }
        override suspend fun journalRetired(obligation: LossObligation) =
            AccessEpochTransitions.journalRetired(record, obligation).also { record = it }
        /** Every marking asked for, as (premium, krx) (S4 CUT-P2). */
        val markCalls = mutableListOf<Pair<Boolean, Boolean>>()

        /** Parks the next marking before it writes. */
        var markGate: CompletableDeferred<Unit>? = null
        val markParked = CompletableDeferred<Unit>()

        /** The next marking throws this before writing. */
        var markFailsBefore: Throwable? = null

        /** The next marking writes and then throws this. */
        var markFailsAfter: Throwable? = null

        override suspend fun markMayContainData(premium: Boolean, krx: Boolean): AccessEpochRecord {
            markCalls += premium to krx
            markGate?.let { gate ->
                markGate = null
                markParked.complete(Unit)
                gate.await()
            }
            markFailsBefore?.let {
                markFailsBefore = null
                throw it
            }
            record = AccessEpochTransitions.markMayContainData(record, premium, krx)
            markFailsAfter?.let {
                markFailsAfter = null
                throw it
            }
            return record
        }
    }

    private class Purger : UserScopePurger, CapabilityScopePurger {
        /** Runs inside the issuer's capability purge, under its lock, before it completes (S4 RT01-B2b-2). */
        var onCapability: suspend (PurgeNamespace) -> Unit = {}
        /** Runs inside the issuer's user purge, under its lock, and answers it (S4 RT01-B3). */
        var onUser: suspend (PurgeNamespace) -> PurgeResult = { PurgeResult.Completed }
        override suspend fun purgeUserScope(namespace: PurgeNamespace) = onUser(namespace)
        override suspend fun purgeCapabilityScope(namespace: PurgeNamespace): PurgeResult {
            onCapability(namespace)
            return PurgeResult.Completed
        }
    }

    private class Source(var next: () -> EntitlementsOutcome) : EntitlementsSource {
        var identity: EntitlementsIdentity? = EntitlementsIdentity(OWNER, 1L)
        var liveFence: AuthIdentityFence? = null

        /** Runs once, after the answer is formed and before it returns: where the decision read is made to fail. */
        var afterFetch: () -> Unit = {}

        /** When set, parks the next answer after it is formed, until completed (S4 U1a). */
        var fetchGate: CompletableDeferred<Unit>? = null

        /** Whether each fetch asked for a fresh premium answer (S4 U1a). */
        val freshRequests = mutableListOf<Boolean>()

        override suspend fun fetch(freshPremium: Boolean): EntitlementsResult {
            freshRequests += freshPremium
            val outcome = next()
            val owner = checkNotNull(identity)
            afterFetch().also { afterFetch = {} }
            fetchGate?.let { gate ->
                fetchGate = null
                gate.await()
            }
            return EntitlementsResult.Answered(owner, outcome)
        }
        override suspend fun currentIdentity() = identity
    }

    private class Hooks {
        /** Runs inside the coordinator when a loss re-approval is scheduled — an external callback under its lock. */
        var onReapproval: () -> Unit = {}
    }

    private class Harness(
        val store: Store,
        val source: Source,
        val coordinator: PremiumAccessCoordinator,
        val hooks: Hooks,
        val purger: Purger
    ) {
        val snapshot: TopicAccessSnapshot get() = coordinator.accessSnapshot
        val facts: TopicAccessFacts get() = snapshot.facts

        suspend fun assertCurrent(what: String) {
            assertTrue("$what: the published facts are not a recomputation", coordinator.accessFactsAreCurrent())
        }
    }

    private fun ids(): EpochIdGenerator {
        var n = 0
        return EpochIdGenerator { "epoch-${n++}" }
    }

    private fun TestScope.build(): Harness {
        val store = Store(ids())
        val source = Source { active(krx = true) }
        val hooks = Hooks()
        val purger = Purger()
        val pac = krxBetaPac(
            source = source,
            store = store,
            userPurger = purger,
            capabilityPurger = purger,
            scope = CoroutineScope(processJob + StandardTestDispatcher(testScheduler)),
            clock = { testScheduler.currentTime },
            jitter = ProbeJitter.None,
            persistenceRetryDelayMillis = RETRY,
            liveFence = { source.liveFence },
            onLossReapprovalScheduled = { _, _ -> hooks.onReapproval() },
            orders = AccessOrderSequence()
        )
        checked += pac
        return Harness(store, source, pac.coordinator, hooks, purger)
    }

    private suspend fun TestScope.granted(): Harness {
        val h = build()
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 1L)).applied()
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        h.store.record = h.store.record.copy(mayContainPremiumData = true, mayContainKrxData = true)
        val fence = checkNotNull(h.coordinator.topicGrantResult().fence) { "contract(ON-P): issued fence" }
        val s = h.snapshot
        assertEquals("contract(ON-P): initial OPEN", KrxContestPhase.OPEN, h.coordinator.krxContestView?.state?.phase)
        assertTrue("contract(ON-P): permit >= 1", s.krxPermitSeq >= 1)
        assertTrue("contract(ON-P): capability allowed", s.facts.capabilityAllowed)
        assertTrue("contract(ON-P): GENERAL use", s.acquireUse(fence) != null)
        assertTrue("contract(ON-P): initial C recorded", s.capabilityInvalidations >= 0)
        return h
    }

    private fun TestScope.settle() {
        advanceTimeBy(SETTLE)
        runCurrent()
    }

    /** Gates a test parks a write on. Released on the way out, so a failing assertion cannot leave a non-cancellable write waiting. */
    private val gates = mutableListOf<CompletableDeferred<Unit>>()

    private fun gate() = CompletableDeferred<Unit>().also { gates += it }

    private fun betaTest(body: suspend TestScope.() -> Unit) = runTest(timeout = 30.seconds) {
        var failure: Throwable? = null
        try { body() } catch (t: Throwable) { failure = t; throw t } finally {
            gates.forEach { it.complete(Unit) }
            processJob.cancel()
            for (pac in checked) {
                try {
                    assertSame(contract("installed checker is retained"), pac.check, pac.coordinator.onAccessPublishedForTest)
                    pac.check.assertHeld()
                } catch (t: Throwable) {
                    if (failure == null) throw t else failure.addSuppressed(t)
                }
            }
        }
    }

    private fun active(krx: Boolean) = EntitlementsOutcome.StableActive(krxVisible = krx)
    private class Sent(val kind: String, val key: GraphKey?, val guard: () -> Boolean) {
        val tab = CompletableDeferred<AuthenticatedHttpResponse<GraphV2TabResponse>>()
        val catalog = CompletableDeferred<AuthenticatedHttpResponse<GraphV2CatalogResponse>>()
    }

    /** The graph side on the issuer of [h]; see the class KDoc for the assembly rules. */
    private inner class GraphRig(
        test: TestScope,
        val h: Harness,
        startRecorder: Boolean = true,
        /** The protected admission the coordinator and the gate share; one function object (S4 RT01-A2). */
        admission: (() -> Boolean)? = null,
        /** The disk store; an empty one of its own unless a row passes one (S4 RT01-B2b-1). */
        store: GraphV2DiskStore? = null
    ) {
        private val scheduler = test.testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(processJob + dispatcher)
        /** The live auth session; a row moves it to a new generation of the same user (S4 RT01-A1). */
        var live = AuthIdentityFence(OWNER, 1L)
        var fence: TopicSessionFence? = null
        var catalog: GraphCatalog? = null
        val sent = mutableListOf<Sent>()
        val failures = mutableListOf<Throwable>()
        private val snapshot: () -> TopicAccessSnapshot = { h.coordinator.accessSnapshot }
        val uses = SnapshotTopicUseAuthority(snapshot)
        val protectedAdmission: () -> Boolean = admission ?: { true }
        val access = GraphV2AccessGate({ live }, { fence }, snapshot, protectedAdmission)
        val clock = AppClock { GRAPH_NOON + scheduler.currentTime.milliseconds }
        val recorder = GraphRecorder(scope, h.coordinator.accessRevisions, snapshot, { fence }, { catalog }, access, clock)
        val coordinator = GraphV2RequestCoordinator(
            fetcher = object : GraphV2Fetching {
                override suspend fun catalog(
                    owner: AuthSnapshot,
                    useAdmitted: () -> Boolean
                ): AuthenticatedHttpResponse<GraphV2CatalogResponse> {
                    val s = Sent("catalog", null, useAdmitted)
                    sent += s
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
            },
            owners = object : GraphOwnerSource {
                override fun currentIdentity(): AuthIdentityFence = live
                override suspend fun capture(expected: AuthIdentityFence) =
                    AuthSnapshot(expected.uid, expected.authGeneration, "token")
            },
            currentAccessFence = { fence },
            uses = uses,
            protectedAdmission = protectedAdmission,
            accessSnapshot = snapshot,
            scope = scope,
            clock = clock,
            rateLimitJitter = { Duration.ZERO },
            onEventFailure = { failures += it },
            cachePorts = GraphV2CachePorts(
                store ?: FileGraphV2DiskStore(folder.newFolder().let { dir -> { dir } }, JsonGraphV2EnvelopeCodec(), DefaultGraphV2AtomicFileIo(), dispatcher),
                access,
                onSeedDiagnostic = {}
            ),
            recorder = recorder
        )

        init {
            coordinator.start()
            if (startRecorder) recorder.start()
        }

        fun now(): Instant = clock.now()
        fun tabs(key: GraphKey) = sent.filter { it.kind == "tab" && it.key == key }
        fun series() = recorder.state.value.series
        fun kb(fence: TopicSessionFence) = GraphObservationSeriesKey(GraphDataScope(OWNER, checkNotNull(fence.userAccessEpoch)), KB)

        /** A usd quote from kb owned by [owner] under [lifetime]. */
        fun quote(rate: Double, owner: TopicSessionFence, lifetime: TopicUseLifetime) = TopicGraphInput.Observations(
            1L, "fx:usd-krw", TopicGraphPath.WS, TopicUseAttribution(Any(), owner, 1L, lifetime), 1L,
            listOf(TopicGraphCandidate.Quote("kb", "usd-krw", rate, now() - 1.seconds, null))
        )

        fun adopted(key: GraphObservationSeriesKey, rate: Double): Boolean =
            series()[key]?.data?.app?.observations?.any { it.rate == rate } == true

        /** Publishes the fence the issuer issues now - never one built here - and tells the coordinator. */
        suspend fun publishIssued(): TopicSessionFence {
            val issued = checkNotNull(h.coordinator.topicGrantResult().fence) { "no grant was issued" }
            fence = issued
            coordinator.onContextChanged()
            return issued
        }

        /** A user end as the session would carry it: the fence is withdrawn and the catalog slot empties. */
        fun withdraw() {
            fence = null
            catalog = null
            coordinator.onContextChanged()
        }

        fun threeMonthTab() = GraphV2TabResponse(
            tab = "usd",
            period = GraphPeriod.THREE_MONTHS.code,
            series = listOf(
                GraphV2Series(ONLINE, ONLINE, "krw", "KRW", 2, listOf(GraphV2Point(now() - 1.days, 1390.0, "x")),
                    GraphV2Provenance(false, emptyList()), null),
                GraphV2Series(KRX_SERIES, KRX_SERIES, "krw", "KRW", 1, listOf(GraphV2Point(now() - 1.days, 1395.0, "krx")),
                    GraphV2Provenance(false, emptyList()), null)
            ),
            metadata = GraphV2Metadata(now() - 1.hours, "1d", GraphV2Range("2026-07-05", "2026-10-05"))
        )

        /** A 10-minute usd 1d answer with a closed kb point. */
        fun dayTab(): GraphV2TabResponse {
            val bucket = Instant.fromEpochMilliseconds((now() - 30.minutes).toEpochMilliseconds() / 600_000L * 600_000L)
            return GraphV2TabResponse(
                tab = "usd",
                period = GraphPeriod.ONE_DAY.code,
                series = listOf(GraphV2Series(KB, KB, "krw", "KRW", 2, listOf(GraphV2Point(bucket, 1390.0, "x")),
                    GraphV2Provenance(false, emptyList()), null)),
                metadata = GraphV2Metadata(now() - 1.hours, "10min", GraphV2Range("2026-10-04", "2026-10-05"))
            )
        }
    }

    /** A 1d answer a screen can draw: six closed kb points on today's KST rolling domain (S4 U1d). */
    private fun GraphRig.drawableDayTab(): GraphV2TabResponse = drawableDayTabAt(now())

    private fun drawableDayTabAt(now: Instant): GraphV2TabResponse {
        val day0 = Instant.parse("2026-10-04T15:00:00Z")
        val points = (0 until 6).map { GraphV2Point(day0 + 1.hours + (it * 10).minutes, 1390.0 + it, "x") }
        return GraphV2TabResponse(
            tab = "usd",
            period = GraphPeriod.ONE_DAY.code,
            series = listOf(GraphV2Series(KB, KB, "krw", "KRW", 2, points, GraphV2Provenance(false, emptyList()), null)),
            metadata = GraphV2Metadata(
                now - 1.minutes, "10min", GraphV2Range("2026-10-05", "2026-10-05"),
                domainStartAt = day0, domainEndAt = day0 + 24.hours, liveDomainMode = "rolling"
            )
        )
    }

    private fun <T> ok(body: T) = AuthenticatedHttpResponse(200, Headers.headersOf(), body, null, byteArrayOf(1))

    /** usd: 3m lists the online and the KRX series, 1d lists kb; valid for two days. */
    private fun graphCatalog() = GraphV2CatalogResponse(
        tabs = listOf(GraphV2CatalogTab("usd", "usd", emptyMap(), mapOf(
            GraphPeriod.THREE_MONTHS.code to GraphV2CatalogPeriod(listOf(ONLINE, KRX_SERIES), listOf(ONLINE)),
            GraphPeriod.ONE_DAY.code to GraphV2CatalogPeriod(listOf(KB), listOf(KB))
        ))),
        version = "2026-05-27",
        supportedPeriods = listOf("1w", "3m", "1y"),
        cacheTtlSeconds = 172800
    )

    /** What an approved graph holds before a row blocks it. */
    private class Approved(
        val fence: TopicSessionFence,
        val lifetime: TopicUseLifetime,
        val capture: GraphV2AccessCapture,
        val kb: GraphObservationSeriesKey,
        /** A 1d request still out, carrying kb's recovery capture. */
        val day: Sent
    )

    /**
     * The approved start of every row: the issued grant published, a 3m catalog and tab adopted with their general and KRX
     * halves, kb adopted with its recovery demand, and a 1d request out carrying kb's capture.
     */
    private suspend fun TestScope.approve(g: GraphRig): Approved {
        val fence = g.publishIssued()
        runCurrent()
        val lifetime = checkNotNull(g.uses.acquire(fence)) { "contract(ON-P): a use under the issued fence" }
        g.coordinator.onActivated(KEY_3M); runCurrent()
        val catalogSent = g.sent.single { it.kind == "catalog" }
        assertTrue("contract(ON-P): the catalog guard admits an approved use", catalogSent.guard())
        catalogSent.catalog.complete(ok(graphCatalog())); runCurrent()
        g.tabs(KEY_3M).single().tab.complete(ok(g.threeMonthTab())); runCurrent()
        g.catalog = checkNotNull(g.coordinator.state.value.catalog) { "contract(ON-P): the catalog was adopted" }
        val kb = g.kb(fence)
        g.recorder.observe(g.quote(1390.0, fence, lifetime))
        assertTrue("contract(ON-P): kb adopted with a demand", g.adopted(kb, 1390.0) && g.series().getValue(kb).pending.isNotEmpty())
        g.coordinator.onActivated(KEY_1D); runCurrent()
        val day = g.tabs(KEY_1D).single()
        assertEquals("contract(ON-P): the 1d request captured kb's recovery", 1L, g.recorder.state.value.nextVersion)
        val capture = checkNotNull(g.access.bind(fence, lifetime)) { "contract(ON-P): the gate binds" }
        return Approved(fence, lifetime, capture, kb, day)
    }

    /** Selections that commit at once; nothing here depends on how they are kept. */
    private class HolderSelections : GraphSelectionStore {
        private val committed = mutableMapOf<GraphSelectionKey, GraphSeriesSelection>()

        private fun current(key: GraphSelectionKey) = committed[key]?.let {
            GraphSelectionReadResult.Present(
                GraphSelectionRecord(1, key.uid, key.audience, key.tab, it.visibleSeriesIds, it.initializedSeries)
            )
        } ?: GraphSelectionReadResult.Absent

        override suspend fun confirmGraphSelection(key: GraphSelectionKey) = current(key)
        override suspend fun readGraphSelection(key: GraphSelectionKey) = current(key)
        override suspend fun writeGraphSelection(key: GraphSelectionKey, selection: GraphSeriesSelection): GraphSelectionWriteResult {
            committed[key] = selection
            return GraphSelectionWriteResult.Committed
        }
    }

    /** What a ready holder holds before a row blocks it. */
    private class Shown(val fence: TopicSessionFence, val lifetime: TopicUseLifetime)

    private class MarkingDispatcher(private val queue: TestDispatcher) : CoroutineDispatcher(), Delay by queue {
        var marked = false
            private set

        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true
        override fun dispatch(context: CoroutineContext, block: Runnable) = queue.dispatch(context, Runnable {
            val outer = marked
            marked = true
            try {
                block.run()
            } finally {
                marked = outer
            }
        })
    }

    /**
     * The RT01-A3 assembly on the issuer of [h] and nothing else: its own [parent] under the process job, a marking main
     * dispatcher, a fake transport that records each send, and the issuer's snapshot behind a supplier that notes whether
     * each read ran on that dispatcher. Every read made while the assembly is being built is dropped; only reads after
     * construction are kept. The protected admission, the owner source's identity and the jitter are the rig's to change.
     * With [disk], the cache ports the assembly asks for wrap a real file store and count its reads; without, there are none,
     * so no disk work reads the snapshot off main. A tab send notes the coordinator's published in-flight keys at the moment
     * a cancellation reaches it, before any dispatched cleanup.
     */
    private inner class AssemblyRig(
        test: TestScope,
        val h: Harness,
        private val disk: Boolean = true,
        val parent: Job = Job(processJob),
        jitter: (String) -> Duration = { Duration.ZERO },
        cachePorts: ((GraphV2AccessGate) -> GraphV2CachePorts?)? = null,
        recoveryPermit: (() -> TopicGraphRecoveryPermit?)? = null,
        timeEvent: () -> Unit = {}
    ) {
        private val scheduler = test.testScheduler
        private val queue = StandardTestDispatcher(scheduler)
        val main = MarkingDispatcher(queue)
        var live = AuthIdentityFence(OWNER, 1L)
        /** When set, the owner source reports this identity instead of [live]. */
        var ownerOverride: AuthIdentityFence? = null
        var admitted = true
        val sent = mutableListOf<Sent>()
        /** Per send: whether it ran on [main], with [main] as its dispatcher. */
        val sendsOnMain = mutableListOf<Boolean>()
        /** Per read of the issuer snapshot through the assembly's supplier: whether it ran on [main]. */
        val snapshotReads = mutableListOf<Boolean>()
        /** Per tab send a cancellation reached: the coordinator's published in-flight keys at that moment. */
        val inFlightAtCancel = mutableListOf<Set<GraphKey>>()
        val failures = mutableListOf<Throwable>()
        var portsGate: GraphV2AccessGate? = null
        var diskReads = 0
        val uses = SnapshotTopicUseAuthority { h.coordinator.accessSnapshot }
        val clock = AppClock { GRAPH_NOON + scheduler.currentTime.milliseconds }

        private suspend fun record(kind: String, key: GraphKey?, useAdmitted: () -> Boolean): Sent {
            sendsOnMain += main.marked && currentCoroutineContext()[ContinuationInterceptor] === main
            return Sent(kind, key, useAdmitted).also { sent += it }
        }

        private fun ports(gate: GraphV2AccessGate): GraphV2CachePorts? {
            portsGate = gate
            if (!disk) return null
            val real = FileGraphV2DiskStore(folder.newFolder().let { dir -> { dir } }, JsonGraphV2EnvelopeCodec(), DefaultGraphV2AtomicFileIo(), queue)
            val counted = object : GraphV2DiskStore by real {
                override suspend fun readGeneral(
                    key: GraphV2GeneralKey,
                    catalog: GraphCatalog?,
                    admission: GraphV2IoAdmission
                ): GraphV2DiskRead<GraphV2GeneralEnvelope> {
                    diskReads++
                    return real.readGeneral(key, catalog, admission)
                }
            }
            return GraphV2CachePorts(counted, gate, onSeedDiagnostic = {})
        }

        val a: GraphRuntimeAssembly = GraphRuntimeAssembly(
            accessSnapshot = { snapshotReads += main.marked; h.coordinator.accessSnapshot },
            accessRevisions = h.coordinator.accessRevisions,
            liveIdentity = { live },
            uses = uses,
            protectedAdmission = { admitted },
            fetcher = object : GraphV2Fetching {
                override suspend fun catalog(owner: AuthSnapshot, useAdmitted: () -> Boolean) =
                    record("catalog", null, useAdmitted).catalog.await()

                override suspend fun tab(
                    owner: AuthSnapshot,
                    key: GraphKey,
                    useAdmitted: () -> Boolean
                ): AuthenticatedHttpResponse<GraphV2TabResponse> {
                    val s = record("tab", key, useAdmitted)
                    return suspendCancellableCoroutine { continuation ->
                        // Runs inside the cancel that reaches this send, before any dispatched cleanup.
                        continuation.invokeOnCancellation { inFlightAtCancel += a.coordinator.state.value.inFlight }
                        s.tab.invokeOnCompletion { cause ->
                            if (cause == null) continuation.resume(s.tab.getCompleted())
                            else continuation.resumeWithException(cause)
                        }
                    }
                }
            },
            owners = object : GraphOwnerSource {
                override fun currentIdentity(): AuthIdentityFence = ownerOverride ?: live
                override suspend fun capture(expected: AuthIdentityFence) =
                    AuthSnapshot(expected.uid, expected.authGeneration, "token")
            },
            cachePorts = cachePorts ?: ::ports,
            main = main,
            parent = parent,
            clock = clock,
            rateLimitJitter = jitter,
            onEventFailure = { failures += it },
            recoveryPermit = recoveryPermit,
            timeEvent = timeEvent
        ).also { snapshotReads.clear() }

        fun now(): Instant = clock.now()

        /** Hands the assembly's bridge the grant the issuer issues now, as the deliverer would; never one built here. */
        suspend fun deliverIssued(): TopicSessionFence {
            val issued = checkNotNull(h.coordinator.topicGrantResult().fence) { "no grant was issued" }
            a.fences.setAccess(true, issued, TopicGrantOrigin.NewContext)
            return issued
        }

        /** A usd quote from kb owned by [owner] under [lifetime]. */
        fun quote(rate: Double, owner: TopicSessionFence, lifetime: TopicUseLifetime) = TopicGraphInput.Observations(
            1L, "fx:usd-krw", TopicGraphPath.WS, TopicUseAttribution(Any(), owner, 1L, lifetime), 1L,
            listOf(TopicGraphCandidate.Quote("kb", "usd-krw", rate, now() - 1.seconds, null))
        )

        fun kb(fence: TopicSessionFence) =
            GraphObservationSeriesKey(GraphDataScope(fence.identity.uid, checkNotNull(fence.userAccessEpoch)), KB)

        fun adopted(key: GraphObservationSeriesKey, rate: Double): Boolean =
            a.recorder.state.value.series[key]?.data?.app?.observations?.any { it.rate == rate } == true

        fun held(): Int = a.recorder.state.value.pending.inputs.size
    }

    private class HeldWrites(private val real: GraphV2DiskStore) : GraphV2DiskStore by real {
        var holdBeforeWrite: CompletableDeferred<Unit>? = null
        var writesEntered = 0
        val reports = mutableListOf<GraphV2WriteReport>()
        val cancelled = mutableListOf<GraphV2WriteTicket>()
        val withdrawals = mutableListOf<GraphV2WriteTicket>()
        override fun cancelWrite(ticket: GraphV2WriteTicket) {
            cancelled += ticket
            real.cancelWrite(ticket)
        }
        override fun withdrawKrx(ticket: GraphV2WriteTicket): Boolean {
            withdrawals += ticket
            return real.withdrawKrx(ticket)
        }
        override suspend fun write(ticket: GraphV2WriteTicket, admission: GraphV2IoAdmission): GraphV2WriteReport {
            writesEntered++
            holdBeforeWrite?.await()
            return real.write(ticket, admission).also { reports += it }
        }
    }

    private fun threeMonthTabAt(now: Instant) = GraphV2TabResponse(
        tab = "usd",
        period = GraphPeriod.THREE_MONTHS.code,
        series = listOf(
            GraphV2Series(ONLINE, ONLINE, "krw", "KRW", 2, listOf(GraphV2Point(now - 2.days, 1389.0, "x"), GraphV2Point(now - 1.days, 1390.0, "x")),
                GraphV2Provenance(false, emptyList()), null),
            GraphV2Series(KRX_SERIES, KRX_SERIES, "krw", "KRW", 1, listOf(GraphV2Point(now - 2.days, 1394.0, "krx"), GraphV2Point(now - 1.days, 1395.0, "krx")),
                GraphV2Provenance(false, emptyList()), null)
        ),
        metadata = GraphV2Metadata(now - 1.hours, "1d", GraphV2Range("2026-07-05", "2026-10-05"))
    )

    private class HeldReads(private val real: GraphV2DiskStore) : GraphV2DiskStore by real {
        var reads = 0
        val krxReads = mutableListOf<GraphV2KrxKey>()
        val hold = mutableMapOf<Int, CompletableDeferred<Unit>>()
        override suspend fun readKrx(key: GraphV2KrxKey, catalog: GraphCatalog?, admission: GraphV2IoAdmission): GraphV2DiskRead<com.jay.fxi.data.graph.GraphV2KrxEnvelope> {
            krxReads += key
            return real.readKrx(key, catalog, admission)
        }

        override suspend fun readGeneral(
            key: GraphV2GeneralKey,
            catalog: GraphCatalog?,
            admission: GraphV2IoAdmission
        ): GraphV2DiskRead<GraphV2GeneralEnvelope> {
            val result = real.readGeneral(key, catalog, admission)
            hold[++reads]?.await()
            return result
        }
    }

    private inner class AssemblyHolder(test: TestScope, val r: AssemblyRig) {
        val owner = TopicDisplayOwner(r.live, 1L)
        val display = MutableStateFlow(TopicDisplayState.NONE.copy(owner = owner))
        val focus = MutableStateFlow<OwnedTopicFocus?>(OwnedTopicFocus(r.live, FreeTab.USD))
        val selectionsOnMain = mutableListOf<Boolean>()
        private val scope = CoroutineScope(r.parent + r.main)
        val holder = GraphV2ScreenStateHolder(
            tab = "usd",
            coordinator = r.a.coordinator,
            selectionSession = GraphSeriesSelectionSession(
                MarkedSelections(HolderSelections()) { selectionsOnMain += r.main.marked },
                GraphSelectionAudience.PREMIUM, "usd", scope, r.main
            ),
            liveIdentity = { r.live },
            display = display,
            focus = focus,
            currentAccessFence = r.a.fences.current,
            uses = r.uses,
            gate = r.a.gate,
            accessRevisions = r.h.coordinator.accessRevisions,
            scope = scope,
            dispatcher = r.main,
            clock = r.clock,
            recorder = r.a.recorder
        )
        val port = GraphRuntimeRetirementPort(r.a.coordinator, { listOf(holder) }, r.main)
        val published = mutableListOf<GraphV2ScreenState>()
        /** Per publication after the first collected value: whether it was made on main. */
        val publishedOnMain = mutableListOf<Boolean>()

        init {
            var first = true
            test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) {
                holder.state.collect {
                    published += it
                    if (first) first = false else publishedOnMain += r.main.marked
                }
            }
        }

        fun chartsPublishedSince(mark: Int) = published.drop(mark).count { it.chart != null }
    }

    /** A selection store that notes each call before delegating. */
    private class MarkedSelections(private val real: GraphSelectionStore, private val note: () -> Unit) : GraphSelectionStore {
        override suspend fun confirmGraphSelection(key: GraphSelectionKey): GraphSelectionReadResult {
            note()
            return real.confirmGraphSelection(key)
        }

        override suspend fun readGraphSelection(key: GraphSelectionKey): GraphSelectionReadResult {
            note()
            return real.readGraphSelection(key)
        }

        override suspend fun writeGraphSelection(key: GraphSelectionKey, selection: GraphSeriesSelection): GraphSelectionWriteResult {
            note()
            return real.writeGraphSelection(key, selection)
        }
    }

    /**
     * The holder made ready on the delivered grant, every holder call on main: started, activated, 1d drawn and shown, and a
     * live kb price adopted on main has just scheduled a 350 ms publication that has not yet run.
     */
    private suspend fun TestScope.showOnAssembly(ah: AssemblyHolder): Shown {
        val r = ah.r
        val fence = r.deliverIssued(); runCurrent()
        withContext(r.main) { ah.holder.start() }; runCurrent()
        withContext(r.main) { ah.holder.onActivated(ah.owner) }; runCurrent()
        r.sent.single { it.kind == "catalog" }.catalog.complete(ok(graphCatalog())); runCurrent()
        r.sent.single { it.kind == "tab" && it.key == KEY_1D }.tab.complete(ok(drawableDayTabAt(r.now()))); runCurrent()
        val state = withContext(r.main) { ah.holder.currentState() }
        assertEquals("contract(ON-P): the chart is ready", GraphV2Content.READY, state.content)
        withContext(r.main) { ah.holder.setSurfaceVisible(checkNotNull(state.inlineToken) { "contract(ON-P): an inline token" }, true) }
        runCurrent()
        val lifetime = checkNotNull(r.uses.acquire(fence)) { "contract(ON-P): a use under the issued fence" }
        withContext(r.main) { r.a.recorder.observe(r.quote(1410.0, fence, lifetime)) }
        assertTrue("contract(ON-P): kb adopted the live price", r.adopted(r.kb(fence), 1410.0))
        runCurrent()
        return Shown(fence, lifetime)
    }

    private companion object {
        const val OWNER = "user-a"
        const val OTHER = "user-b"
        const val RETRY = 1_000L
        const val SETTLE = 60 * 60 * 1_000L

        /** 2026-10-05 12:00 KST, the graph clock's origin. */
        val GRAPH_NOON: Instant = Instant.parse("2026-10-05T03:00:00Z")
        val KEY_3M = GraphKey("usd", GraphPeriod.THREE_MONTHS)
        val KEY_1D = GraphKey("usd", GraphPeriod.ONE_DAY)
        const val ONLINE = "investing.usd-krw"
        const val KRX_SERIES = "krx.usd-krw-futures"
        const val KB = "kb.usd"
    }

    private fun sameGeneral(h: Harness, before: TopicAccessSnapshot, label: String = "") {
        val s = h.snapshot
        assertEquals(contract("$label same token"), before.facts.token, s.facts.token)
        assertTrue(contract("$label token standing and user allowed"), s.facts.tokenStanding && s.facts.userAllowed)
        assertEquals(contract("$label U unchanged"), before.userInvalidations, s.userInvalidations)
        assertEquals(contract("$label USER epoch unchanged"), before.facts.recordFence?.userAccessEpoch, s.facts.recordFence?.userAccessEpoch)
        assertEquals(contract("$label USER end unchanged"), before.lastUserEnd, s.lastUserEnd)
        assertEquals(contract("$label floor unchanged"), before.userEndInvalidationsFloor, s.userEndInvalidationsFloor)
    }

    private suspend fun TestScope.rotate(h: Harness) {
        h.source.next = { active(false) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()
    }

    private suspend fun TestScope.reopen(h: Harness) {
        h.source.next = { active(true) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()
        assertEquals(contract("reapproval OPEN"), KrxContestPhase.OPEN, h.coordinator.krxContestView?.state?.phase)
        assertTrue(contract("reapproval capability allowed"), h.facts.capabilityAllowed && h.snapshot.krxPermitSeq >= 1)
    }

    @Test fun ON_P_realPacOpensAndCountsFirstCapabilityWithdrawal() = betaTest {
        row = "ON-P"
        val h = granted()
        val before = h.snapshot
        val check = checked.single().check
        assertTrue(contract("checker saw initial allowance"), check.published.any { it.facts.capabilityAllowed && it.krxPermitSeq >= 1 })
        rotate(h)
        assertEquals(contract("first allowed to blocked C increment"), before.capabilityInvalidations + 1, h.snapshot.capabilityInvalidations)
        sameGeneral(h, before)
        assertTrue(contract("hidden after rotation"), !h.facts.capabilityAllowed)
    }

    @Test fun P01_rotationPreservesEveryGeneralConsumerAndOnlyFreshK2CaptureOpensKrx() = betaTest {
        row = "P01"
        val h = granted()
        val g = GraphRig(this, h)
        val a = approve(g)
        val before = h.snapshot
        val catalog = g.coordinator.state.value.catalog
        val demand = g.series().getValue(a.kb).pending.keys.toSet()
        assertTrue("premise: P01 pending recovery and held old day", demand.isNotEmpty() && !a.day.tab.isCompleted)
        val pre = gate(); val landed = gate()
        h.store.rotationGate = pre; h.store.landedGate = landed
        var atNotification = false
        h.purger.onCapability = {
            sameGeneral(h, before, "purge notification")
            assertTrue(contract("GENERAL read at purge"), g.access.admits(a.capture, GraphV2DiskComponent.GENERAL))
            atNotification = true
        }
        h.source.next = { active(false) }
        val rotating = launch { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()
        assertTrue("premise: P01 before write gate hit", h.store.rotationParked.isCompleted)
        sameGeneral(h, before, "before landing")
        assertTrue(contract("old GENERAL bind during rotation"), g.access.bind(a.fence, a.lifetime) != null)
        pre.complete(Unit); runCurrent()
        assertTrue("premise: P01 after write gate hit", h.store.landedParked.isCompleted)
        sameGeneral(h, before, "landed before store return")
        assertTrue(contract("held HTTP guard during rotation"), a.day.guard())
        assertTrue(contract("old GENERAL io during rotation"), g.access.ioAdmission(a.capture).admits(GraphV2DiskComponent.GENERAL))
        landed.complete(Unit); runCurrent(); rotating.join()
        g.coordinator.onContextChanged(); runCurrent()
        assertTrue(contract("purge callback observed"), atNotification)
        assertEquals(contract("C counts first capability withdrawal"), before.capabilityInvalidations + 1, h.snapshot.capabilityInvalidations)
        assertNotEquals(contract("real record K1 to K2"), a.capture.krxCapabilityEpoch, h.store.record.krxCapabilityEpoch)
        sameGeneral(h, before, "after notification")
        assertTrue(contract("old use admits"), g.uses.admits(a.lifetime))
        assertEquals(contract("same use acquired"), a.lifetime, g.uses.acquire(a.fence))
        assertTrue(contract("old GENERAL capture reads"), g.access.admits(a.capture, GraphV2DiskComponent.GENERAL))
        g.recorder.observe(g.quote(1404.0, a.fence, a.lifetime))
        assertTrue(contract("old recorder price adopted"), g.adopted(a.kb, 1404.0))
        assertTrue("premise: P01 old HTTP still pending before answer", !a.day.tab.isCompleted)
        a.day.tab.complete(ok(g.dayTab())); runCurrent()
        assertTrue(contract("old valid GENERAL 200 adopted"), KEY_1D in g.coordinator.state.value.entries)
        assertTrue(contract("same kb and demand retained"), a.kb in g.series() && g.series().getValue(a.kb).pending.keys.containsAll(demand))
        assertSame(contract("catalog retained"), catalog, g.coordinator.state.value.catalog)
        reopen(h)
        sameGeneral(h, before, "K2 reapproved")
        assertTrue(contract("K2 open before old KRX negative"), h.facts.capabilityAllowed)
        assertFalse(contract("K1 capture remains closed"), g.access.admits(a.capture, GraphV2DiskComponent.KRX))
        val fresh = checkNotNull(g.access.bind(a.fence, a.lifetime)) { contract("fresh capture binds") }
        assertEquals(contract("fresh captures record K2"), h.store.record.krxCapabilityEpoch, fresh.krxCapabilityEpoch)
        assertTrue(contract("fresh K2 capture opens KRX"), g.access.admits(fresh, GraphV2DiskComponent.KRX))
        assertTrue(contract("no graph event failure"), g.failures.isEmpty())
    }

    @Test fun P02_everyPublicationAfterDoubtKeepsGeneralStandingAndCountsCOnce() = betaTest {
        row = "P02"
        val h = granted()
        val before = h.snapshot
        val check = checked.single().check
        val mark = check.published.size
        rotate(h)
        val after = check.published.drop(mark)
        val doubt = after.indexOfFirst { it.facts.capabilityContextUncertain }
        assertTrue(contract("doubt publication reached"), doubt >= 0)
        val window = after.drop(doubt)
        assertTrue("premise: P02 observed nonempty doubt window", window.isNotEmpty())
        for (s in window) {
            assertFalse(contract("no capability allowance after doubt rev=${s.revision}"), s.facts.capabilityAllowed)
            assertTrue(contract("GENERAL and standing rev=${s.revision}"), s.facts.userAllowed && s.facts.tokenStanding)
            assertEquals(contract("same token rev=${s.revision}"), before.facts.token, s.facts.token)
            assertEquals(contract("C counted only at first edge rev=${s.revision}"), before.capabilityInvalidations + 1, s.capabilityInvalidations)
            assertEquals(contract("U retained rev=${s.revision}"), before.userInvalidations, s.userInvalidations)
        }
        assertNotEquals(contract("actual record changed"), before.facts.recordFence?.krxCapabilityEpoch, h.store.record.krxCapabilityEpoch)
    }

    @Test fun P03_earlierGrantResultRemainsImmutableWhileCurrentTokenStands() = betaTest {
        row = "P03"
        val h = granted()
        val earlier = h.coordinator.topicGrantResult()
        val original = earlier.snapshot
        val value = original.copy()
        rotate(h)
        assertSame(contract("returned snapshot object retained"), original, earlier.snapshot)
        assertEquals(contract("past snapshot value and revision unchanged"), value, earlier.snapshot)
        assertEquals(contract("past result kept its own token"), earlier.fence?.grant, earlier.snapshot.facts.token)
        assertTrue(contract("past allowance and standing retained"), earlier.snapshot.facts.capabilityAllowed && earlier.snapshot.facts.tokenStanding)
        assertTrue(contract("current revision advanced"), h.snapshot.revision > original.revision)
        sameGeneral(h, original)
        assertFalse(contract("only current KRX closed"), h.facts.capabilityAllowed)
    }

    @Test fun P04_capabilityEndPreservesUserEndFloorAndUAfterRealUserHold() = betaTest {
        row = "P04"
        val h = granted()
        val initial = h.snapshot
        h.source.next = { EntitlementsOutcome.StableInactive(false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM); runCurrent()
        assertTrue(contract("real USER loss increments U"), h.snapshot.userInvalidations > initial.userInvalidations)
        assertEquals(contract("USER loss has its own end"), TopicAccessEndReason.AUTHORITATIVE_LOSS, h.snapshot.lastUserEnd?.reason)
        h.source.next = { active(true) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM); runCurrent()
        checkNotNull(h.coordinator.topicGrantResult().fence) { contract("reapproved grant") }
        h.store.record = h.store.record.copy(mayContainPremiumData = true, mayContainKrxData = true)
        val owned = h.store.record
        val beforeHold = h.snapshot.userInvalidations
        h.store.record = owned.copy(ownerUid = OTHER)
        assertNull(contract("real owner mismatch withholds USER"), h.coordinator.topicGrantResult().fence)
        h.store.record = owned
        checkNotNull(h.coordinator.topicGrantResult().fence) { contract("owner restored") }
        assertTrue(contract("USER hold increments U beyond floor"), h.snapshot.userInvalidations > beforeHold && h.snapshot.userInvalidations > h.snapshot.userEndInvalidationsFloor)
        val start = h.snapshot
        val mark = checked.single().check.published.size
        rotate(h)
        assertEquals(contract("CAPABILITY end recorded"), TopicAccessEndReason.CAPABILITY_REVOKED, h.snapshot.lastCapabilityEnd?.reason)
        sameGeneral(h, start)
        assertEquals(contract("C incremented"), start.capabilityInvalidations + 1, h.snapshot.capabilityInvalidations)
        for (s in checked.single().check.published.drop(mark)) {
            assertEquals(contract("all capability publications preserve USER end"), start.lastUserEnd, s.lastUserEnd)
            assertEquals(contract("all capability publications preserve floor"), start.userEndInvalidationsFloor, s.userEndInvalidationsFloor)
            assertEquals(contract("all capability publications preserve U"), start.userInvalidations, s.userInvalidations)
        }
        // KRX-only U is unchanged; the preceding real USER loss/hold is a separate event.
    }

    @Test fun P05_retirementRejectsLiveK2AndKeepsGeneralCatalogAndRecoveryOfOldHttp() = betaTest {
        row = "P05"
        val h = granted(); val g = GraphRig(this, h); val a = approve(g)
        val before = h.snapshot
        val k1 = checkNotNull(a.capture.krxCapabilityEpoch) { contract("K1 captured") }
        g.coordinator.onActivated(KEY_3M); runCurrent()
        g.coordinator.onRefreshRequested(force = true); runCurrent()
        val old3m = g.tabs(KEY_3M).last()
        val catalog = g.coordinator.state.value.catalog
        val generalBefore = g.coordinator.state.value.entries.getValue(KEY_3M).tab.graph.series.filter { it.seriesId != KRX_SERIES }
        val recoveryBefore = mutableListOf<GraphDataScope>()
        g.coordinator.purgeRecoveryCaptures { recoveryBefore += it; false }
        assertTrue("premise: P05 recovery captured by pending day", recoveryBefore.isNotEmpty() && !a.day.tab.isCompleted && !old3m.tab.isCompleted)
        rotate(h)
        sameGeneral(h, before)
        val k2 = checkNotNull(h.store.record.krxCapabilityEpoch) { contract("record K2") }
        assertNotEquals(contract("K1 differs from live K2"), k1, k2)
        val unchanged = g.coordinator.state.value
        assertEquals(contract("live K2 selector rejected"), GraphRuntimeRetirement.LIVE_SCOPE_SELECTED,
            g.coordinator.retireCapabilities { it.uid == OWNER && it.krxCapabilityEpoch == k2 })
        assertSame(contract("live selection changes no state"), unchanged, g.coordinator.state.value)
        val seen = mutableListOf<GraphCapabilityScope>()
        assertEquals(contract("retire K1"), GraphRuntimeRetirement.REMOVED,
            g.coordinator.retireCapabilities { seen += it; it.uid == OWNER && it.krxCapabilityEpoch == k1 })
        assertTrue(contract("K1 and live K2 candidates enumerated"), seen.containsAll(listOf(GraphCapabilityScope(OWNER, k1), GraphCapabilityScope(OWNER, k2))))
        assertEquals(contract("all existing GENERAL series retained"), generalBefore,
            g.coordinator.state.value.entries.getValue(KEY_3M).tab.graph.series.filter { it.seriesId != KRX_SERIES })
        assertSame(contract("catalog retained"), catalog, g.coordinator.state.value.catalog)
        assertTrue("premise: P05 old K1 requests still pending at port return", !old3m.tab.isCompleted && !a.day.tab.isCompleted)
        assertTrue(contract("K1 removed from every present raw entry at port return"),
            g.coordinator.state.value.entries.values.all { entry ->
                entry.tab.graph.series.none { it.seriesId == KRX_SERIES } && KRX_SERIES !in entry.tab.inProgress
            })
        val recoveryAfter = mutableListOf<GraphDataScope>()
        assertFalse(contract("no recovery capture removed"), g.coordinator.purgeRecoveryCaptures { recoveryAfter += it; false })
        assertEquals(contract("every recovery scope retained"), recoveryBefore.toSet(), recoveryAfter.toSet())
        assertTrue("premise: P05 old requests still pending, no newer answer", !old3m.tab.isCompleted && !a.day.tab.isCompleted)
        val unique = g.threeMonthTab().let { dto -> dto.copy(series = dto.series.map { series ->
            series.copy(data = series.data.map { it.copy(rate = 1601.0) })
        }) }
        old3m.tab.complete(ok(unique)); a.day.tab.complete(ok(g.dayTab())); runCurrent()
        val adopted = checkNotNull(g.coordinator.protectedEntry(KEY_3M)) { contract("old GENERAL 200 exposed") }
        assertTrue(contract("old GENERAL response identity adopted"), adopted.tab.graph.series.first { it.seriesId == ONLINE }.points.any { it.rate == 1601.0 })
        assertTrue(contract("old KRX 200 not adopted"), adopted.tab.graph.series.none { it.seriesId == KRX_SERIES })
        assertTrue(contract("old day GENERAL 200 adopted"), KEY_1D in g.coordinator.state.value.entries)
        reopen(h)
        val fresh = checkNotNull(g.access.bind(a.fence, a.lifetime)) { contract("fresh K2 bind") }
        assertTrue(contract("fresh K2 capture positive"), fresh.krxCapabilityEpoch == k2 && g.access.admits(fresh, GraphV2DiskComponent.KRX))
        g.coordinator.onContextChanged(); runCurrent()
        g.coordinator.onRefreshRequested(force = true); runCurrent()
        val freshHttp = g.tabs(KEY_3M).last()
        assertTrue("premise: P05 fresh response still pending", freshHttp !== old3m && !freshHttp.tab.isCompleted)
        freshHttp.tab.complete(ok(g.threeMonthTab())); runCurrent()
        assertTrue(contract("fresh K2 HTTP positive"), g.coordinator.protectedEntry(KEY_3M)?.tab?.graph?.series?.any { it.seriesId == KRX_SERIES } == true)
        assertTrue(contract("no failures"), g.failures.isEmpty())
    }

    @Test fun P06_retiredFillEmptyExpiresButUnretiredOldSeedRestoresOnlyGeneral() = betaTest {
        row = "P06"
        for (retire in listOf(true, false)) {
            val h = granted()
            val before = h.snapshot
            val k1 = checkNotNull(h.store.record.krxCapabilityEpoch)
            val epoch = checkNotNull(h.store.record.userAccessEpoch)
            val held = HeldReads(FileGraphV2DiskStore(folder.newFolder().let { dir -> { dir } },
                JsonGraphV2EnvelopeCodec(), DefaultGraphV2AtomicFileIo(), StandardTestDispatcher(testScheduler)))
            val g = GraphRig(this, h, store = held)
            val tab = (GraphV2Domain.admit(g.threeMonthTab(), "usd", GraphPeriod.THREE_MONTHS, null) as GraphTabAdmission.Accepted).tab
            val stored = (splitGraphV2ServerTab(tab, GraphV2GeneralKey(OWNER, epoch, "usd", GraphPeriod.THREE_MONTHS.code), k1, "P06-old-disk", null)
                as GraphV2Validation.Valid).value
            val ticket = (held.reserveWrite(stored) as GraphV2WriteReservation.Reserved).ticket
            held.write(ticket) { true }
            val seed = gate(); held.hold[1] = seed
            val fence = g.publishIssued(); runCurrent()
            val life = checkNotNull(g.uses.acquire(fence)) { contract("old seed use") }
            g.coordinator.onActivated(KEY_3M); runCurrent()
            assertEquals("premise: P06 FillEmpty GENERAL disk read parked", 1, held.reads)
            assertNull("premise: P06 FillEmpty has no entry", g.coordinator.state.value.entries[KEY_3M])
            rotate(h); sameGeneral(h, before, "retire=$retire")
            val k2 = checkNotNull(h.store.record.krxCapabilityEpoch)
            assertNotEquals(contract("actual K1 to K2"), k1, k2)
            if (retire) {
                val reads = held.reads
                assertEquals(contract("retire pending seed owner"), GraphRuntimeRetirement.REMOVED,
                    g.coordinator.retireCapabilities { it.uid == OWNER && it.krxCapabilityEpoch == k1 })
                runCurrent()
                assertEquals(contract("port starts no replacement seed"), reads, held.reads)
            }
            reopen(h)
            assertTrue(contract("same GENERAL seed use remains valid"), g.uses.admits(life))
            assertTrue("premise: P06 old seed still held before release", !seed.isCompleted)
            seed.complete(Unit); runCurrent()
            if (retire) {
                assertNull(contract("retired owner completion applies nothing"), g.coordinator.state.value.entries[KEY_3M])
                val reads = held.reads
                g.coordinator.onActivated(KEY_3M); runCurrent()
                assertTrue(contract("normal new start retries seed"), held.reads > reads)
            }
            val entry = checkNotNull(g.coordinator.state.value.entries[KEY_3M]) { contract("retire=$retire GENERAL restored") }
            assertEquals(contract("disk GENERAL restored exactly retire=$retire"), joinGraphV2Components(stored.general, null).tab, entry.tab)
            assertTrue(contract("old K1 seed makes no KRX disk read retire=$retire"), held.krxReads.none { it.krxCapabilityEpoch == k1 })
            val exposed = checkNotNull(g.coordinator.protectedEntry(KEY_3M)) { contract("GENERAL exposed") }
            assertTrue(contract("old disk K1 never combines retire=$retire"), exposed.tab.graph.series.none { it.seriesId == KRX_SERIES })
            val fresh = checkNotNull(g.access.bind(fence, life)) { contract("fresh K2 capture") }
            assertTrue(contract("fresh K2 capture positive retire=$retire"), fresh.krxCapabilityEpoch == k2 && g.access.admits(fresh, GraphV2DiskComponent.KRX))
            assertTrue(contract("no failures retire=$retire"), g.failures.isEmpty())
        }
    }

    @Test fun P07_preparingAndReadyWriteWithdrawOnlyKrxAndPublishGeneral() = betaTest {
        row = "P07"
        for ((stage, retire) in listOf("preparing", "ready").flatMap { stage -> listOf(stage to true, stage to false) }) {
            val h = granted(); val before = h.snapshot
            val k1 = checkNotNull(h.store.record.krxCapabilityEpoch)
            val epoch = checkNotNull(h.store.record.userAccessEpoch)
            val held = HeldWrites(FileGraphV2DiskStore(folder.newFolder().let { dir -> { dir } },
                JsonGraphV2EnvelopeCodec(), DefaultGraphV2AtomicFileIo(), StandardTestDispatcher(testScheduler)))
            val prepareCalls = mutableListOf<Pair<String?, Boolean>>()
            var holdPrepare: CompletableDeferred<Unit>? = null
            val writes = GraphV2WritePorts(
                prepareWrite = GraphV2PrepareWrite { captured, wantsKrx ->
                    prepareCalls += captured.krxCapabilityEpoch to wantsKrx
                    holdPrepare?.await()
                    // Same durable namespace producer as PATST graphB2b03; no hand access snapshots.
                    val record = AccessEpochRecord(captured.fence.identity.uid, captured.fence.userAccessEpoch,
                        captured.krxCapabilityEpoch, mayContainPremiumData = true, mayContainKrxData = true)
                    GraphV2WritePreparation(GraphV2NamespacePreparation.Ready(record),
                        if (wantsKrx) GraphV2NamespacePreparation.Ready(record) else null)
                }, onPreparationBlocked = {}, onWriteDiagnostic = {}
            )
            val r = AssemblyRig(this, h, cachePorts = { gate -> GraphV2CachePorts(held, gate, onSeedDiagnostic = {}, writePorts = writes) })
            r.a.start(); r.deliverIssued(); runCurrent()
            r.a.coordinator.onActivated(KEY_3M); runCurrent()
            r.sent.single { it.kind == "catalog" }.catalog.complete(ok(graphCatalog())); runCurrent()
            val hold = gate()
            if (stage == "preparing") holdPrepare = hold else held.holdBeforeWrite = hold
            r.sent.single { it.kind == "tab" && it.key == KEY_3M }.tab.complete(ok(threeMonthTabAt(r.now()))); runCurrent()
            assertEquals(contract("$stage write requested K1"), listOf(k1 to true), prepareCalls)
            assertEquals("premise: P07 $stage precise write window", if (stage == "ready") 1 else 0, held.writesEntered)
            var retired: GraphRuntimeRetirement? = null
            if (retire) h.purger.onCapability = { namespace -> withContext(r.main) {
                retired = r.a.coordinator.retireCapabilities {
                    it.uid == namespace.ownerUid && it.krxCapabilityEpoch != namespace.currentKrxCapabilityEpoch
                }
            } }
            rotate(h)
            // With no explicit purge port, 3b-2 automatic cleanup withdraws K1 on confirmed rotation; B21/write-ready-withdraw-fails isolates commit-time admission when withdrawal has no effect.
            if (!retire) reopen(h)
            sameGeneral(h, before, "$stage retire=$retire")
            if (retire) assertEquals(contract("$stage port removed K1"), GraphRuntimeRetirement.REMOVED, retired)
            assertEquals(contract("$stage KRX withdrawals retire=$retire"), 1, held.withdrawals.size)
            assertEquals(contract("$stage whole ticket never cancelled"), 0, held.cancelled.size)
            assertTrue("premise: P07 $stage write still held before release", !hold.isCompleted)
            hold.complete(Unit); runCurrent()
            assertEquals(contract("$stage one write completed"), 1, held.reports.size)
            assertEquals(contract("$stage GENERAL write succeeds"), GraphV2ComponentWriteOutcome.Replaced, held.reports.single().general)
            assertTrue(contract("$stage KRX never replaced"), held.reports.none { it.krx == GraphV2ComponentWriteOutcome.Replaced })
            assertTrue(contract("$stage GENERAL file exists"), held.readGeneral(GraphV2GeneralKey(OWNER, epoch, "usd", GraphPeriod.THREE_MONTHS.code), null) { true } is GraphV2DiskRead.Found)
            assertFalse(contract("$stage K1 file absent"), held.readKrx(GraphV2KrxKey(OWNER, epoch, k1, "usd", GraphPeriod.THREE_MONTHS.code), null) { true } is GraphV2DiskRead.Found)
            assertTrue(contract("$stage no failures"), r.failures.isEmpty())
            h.purger.onCapability = {}
            withContext(r.main) { r.a.close() }
        }
    }

    @Test fun P08_purgePortReturnsWithGeneralScreenPreservedAndKrxAbsentUntilFreshK2Answer() = betaTest {
        row = "P08"
        val h = granted(); val before = h.snapshot
        val k1 = checkNotNull(h.store.record.krxCapabilityEpoch)
        val r = AssemblyRig(this, h); r.a.start()
        val ah = AssemblyHolder(this, r); showOnAssembly(ah)
        val day = withContext(r.main) { checkNotNull(ah.holder.currentState().inlineToken) }
        withContext(r.main) { ah.holder.selectPeriod(day, GraphPeriod.THREE_MONTHS) }; runCurrent()
        r.sent.last { it.kind == "tab" && it.key == KEY_3M }.tab.complete(ok(threeMonthTabAt(r.now()))); runCurrent()
        var shown = withContext(r.main) { ah.holder.currentState() }
        assertTrue(contract("KRX toggle shown before retirement"), shown.toggles.any { it.seriesId == KRX_SERIES })
        // The 1d selection may carry only kb into 3m; put a GENERAL series on screen before testing its preservation.
        if (shown.selection?.visibleSeriesIds?.contains(ONLINE) != true) {
            withContext(r.main) { ah.holder.toggleSeries(checkNotNull(shown.inlineToken), ONLINE) }; runCurrent()
        }
        shown = withContext(r.main) { ah.holder.currentState() }
        assertTrue(contract("GENERAL chart drawn before rotation"), shown.chart?.renderedIds?.contains(ONLINE) == true)
        assertEquals(contract("GENERAL has a drawable line before rotation"), GraphV2Content.READY, shown.content)
        withContext(r.main) { ah.holder.enterFullscreen(checkNotNull(shown.inlineToken)) }; runCurrent()
        shown = withContext(r.main) { ah.holder.currentState() }
        assertTrue(contract("fullscreen open before rotation"), shown.fullscreenOpen)
        val results = mutableListOf<GraphRuntimeRetirement>()
        var atReturn: GraphV2ScreenState? = null
        var entered = false
        h.purger.onCapability = { namespace -> withContext(r.main) {
            assertTrue("premise: P08 callback runs on marked Main", r.main.marked)
            entered = true
            results += ah.port.retireCapabilities {
                it.uid == namespace.ownerUid && it.krxCapabilityEpoch != namespace.currentKrxCapabilityEpoch
            }
            atReturn = ah.holder.state.value
            sameGeneral(h, before, "inside issuer purge lock")
        } }
        rotate(h)
        h.purger.onCapability = {}
        assertTrue(contract("actual synchronous purge callback"), entered)
        assertEquals(contract("K1 retired"), listOf(GraphRuntimeRetirement.REMOVED), results)
        assertNotEquals(contract("record K2"), k1, h.store.record.krxCapabilityEpoch)
        val at = checkNotNull(atReturn) { contract("port return state observed") }
        assertEquals(contract("GENERAL remains READY at return"), GraphV2Content.READY, at.content)
        assertNotNull(contract("GENERAL chart remains at return"), at.chart)
        assertEquals(contract("period preserved"), shown.activePeriod, at.activePeriod)
        assertEquals(contract("fullscreen preserved"), shown.fullscreenOpen, at.fullscreenOpen)
        assertEquals(contract("raw selection preserved"), shown.selection, at.selection)
        assertEquals(contract("same UI owner/use"), shown.inlineToken, at.inlineToken)
        assertTrue("premise: P08 drawn GENERAL still in target window", at.chart != null && at.toggles.any { it.seriesId == ONLINE })
        assertTrue(contract("no KRX toggle at return"), at.toggles.none { it.seriesId == KRX_SERIES })
        assertTrue(contract("no rendered KRX at return"), at.chart?.renderedIds?.contains(KRX_SERIES) != true)
        val completedBefore = r.sent.count { it.kind == "tab" && it.tab.isCompleted }
        reopen(h); sameGeneral(h, before, "K2 reapproved")
        val notFresh = withContext(r.main) { ah.holder.currentState() }
        assertEquals("premise: P08 no additional tab answer supplied before old-KRX negative", completedBefore,
            r.sent.count { it.kind == "tab" && it.tab.isCompleted })
        assertTrue(contract("reapproval alone restores no old KRX toggle"), notFresh.toggles.none { it.seriesId == KRX_SERIES })
        withContext(r.main) { r.a.coordinator.onContextChanged(); r.a.coordinator.onRefreshRequested(force = true) }; runCurrent()
        val fresh = r.sent.last { it.kind == "tab" && it.key == KEY_3M }
        assertTrue("premise: P08 fresh K2 response pending", !fresh.tab.isCompleted)
        fresh.tab.complete(ok(threeMonthTabAt(r.now()))); runCurrent()
        val after = withContext(r.main) { ah.holder.currentState() }
        assertTrue(contract("fresh K2 answer restores KRX toggle"), after.toggles.any { it.seriesId == KRX_SERIES })
        assertTrue(contract("publications on Main"), ah.publishedOnMain.isNotEmpty() && ah.publishedOnMain.all { it })
        assertTrue(contract("selection calls on Main"), ah.selectionsOnMain.isNotEmpty() && ah.selectionsOnMain.all { it })
        assertTrue(contract("no assembly failure"), r.failures.isEmpty())
        withContext(r.main) { ah.holder.close(); r.a.close() }
    }

    private suspend fun TestScope.markable(): Triple<Harness, TopicSessionFence, TopicUseLifetime> {
        val h = build()
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 1L)).applied()
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM); runCurrent()
        val fence = checkNotNull(h.coordinator.topicGrantResult().fence) { contract("markable issued fence") }
        val life = checkNotNull(h.snapshot.acquireUse(fence)) { contract("markable GENERAL use") }
        assertEquals("contract(ON-P): markable OPEN", KrxContestPhase.OPEN, h.coordinator.krxContestView?.state?.phase)
        assertTrue("contract(ON-P): markable p and allowed", h.snapshot.krxPermitSeq >= 1 && h.facts.capabilityAllowed)
        assertFalse("premise: P09 no initial namespace markers", h.store.record.mayContainPremiumData || h.store.record.mayContainKrxData)
        return Triple(h, fence, life)
    }

    @Test fun P09_markerAndRotationLockOrdersPreserveGeneralAndRefuseEndedUserUse() = betaTest {
        row = "P09"
        run {
            val (h, fence, life) = markable(); val before = h.snapshot
            val k1 = checkNotNull(h.store.record.krxCapabilityEpoch)
            val purged = mutableListOf<PurgeNamespace>()
            h.purger.onCapability = { purged += it }
            val markGate = gate(); h.store.markGate = markGate
            var answer: GraphDataMarking? = null
            val marking = launch { answer = h.coordinator.markGraphData(fence, life, k1, krx = true) }
            runCurrent()
            assertTrue("premise: P09 marking parked inside store under issuer lock", h.store.markParked.isCompleted)
            h.source.next = { active(false) }
            val rotation = launch { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }; runCurrent()
            assertEquals(contract("mark first: rotation waits"), k1, h.store.record.krxCapabilityEpoch)
            assertTrue("premise: P09 marking still holds lock before no-purge assertion", !markGate.isCompleted && !marking.isCompleted)
            assertTrue(contract("mark first: no premature purge"), purged.isEmpty())
            markGate.complete(Unit); runCurrent(); marking.join(); rotation.join()
            assertTrue(contract("mark first: Marked"), answer is GraphDataMarking.Marked)
            val marked = (answer as GraphDataMarking.Marked).record
            assertEquals(contract("mark first: captured K1 namespace"), k1, marked.krxCapabilityEpoch)
            assertTrue(contract("mark first: K1 marker landed"), marked.mayContainPremiumData && marked.mayContainKrxData)
            assertTrue(contract("mark first: old namespace purge obligation"), purged.isNotEmpty() && purged.all { it.pending.krxCapabilityEpoch == k1 })
            sameGeneral(h, before, "mark first")
            assertFalse(contract("mark first: no KRX marker on new K2"), h.store.record.mayContainKrxData)
        }
        run {
            val (h, fence, life) = markable(); val before = h.snapshot
            val k1 = checkNotNull(h.store.record.krxCapabilityEpoch)
            val rotationGate = gate(); h.store.rotationGate = rotationGate
            h.source.next = { active(false) }
            val rotation = launch { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }; runCurrent()
            assertTrue("premise: P09 rotation holds issuer lock", h.store.rotationParked.isCompleted)
            var answer: GraphDataMarking? = null
            val marking = launch { answer = h.coordinator.markGraphData(fence, life, k1, krx = true) }; runCurrent()
            assertTrue("premise: P09 marker waiting for lock", marking.isActive && h.store.markCalls.isEmpty())
            rotationGate.complete(Unit); runCurrent(); rotation.join(); marking.join()
            sameGeneral(h, before, "rotation first")
            assertTrue(contract("rotation first: GENERAL Marked"), answer is GraphDataMarking.Marked)
            val marked = (answer as GraphDataMarking.Marked).record
            assertEquals(contract("rotation first: GENERAL marker only"), listOf(true to false), h.store.markCalls)
            assertEquals(contract("rotation first: current namespace"), h.store.record.krxCapabilityEpoch, marked.krxCapabilityEpoch)
            assertNotEquals(contract("rotation first: K1 not renamed as K2"), k1, marked.krxCapabilityEpoch)
            assertTrue(contract("rotation first: GENERAL durable marker"), marked.mayContainPremiumData)
            assertTrue("premise: P09 mark operation returned within tested window", marking.isCompleted)
            assertFalse(contract("rotation first: no KRX marker"), marked.mayContainKrxData)
        }
        run {
            val (h, fence, life) = markable()
            val k1 = checkNotNull(h.store.record.krxCapabilityEpoch)
            rotate(h); reopen(h)
            assertTrue(contract("old K1 marker supplied while new K2 is OPEN"), h.facts.capabilityAllowed && h.store.record.krxCapabilityEpoch != k1)
            val answer = h.coordinator.markGraphData(fence, life, k1, krx = true)
            assertTrue(contract("reapproved K2 still marks GENERAL for old capture"), answer is GraphDataMarking.Marked)
            assertEquals(contract("epoch guard refuses old K1 marker under allowed K2"), listOf(true to false), h.store.markCalls)
        }
        run {
            val (h, fence, life) = markable()
            // Genuine USER withdrawal is the R branch; KRX-only rotation is not used to synthesize Refused.
            h.store.record = h.store.record.copy(ownerUid = OTHER)
            assertNull(contract("USER scope no longer issuable"), h.coordinator.topicGrantResult().fence)
            assertTrue("premise: P09 real owner mismatch before late marker", h.store.record.ownerUid == OTHER)
            val answer = h.coordinator.markGraphData(fence, life, h.store.record.krxCapabilityEpoch, krx = true)
            assertTrue(contract("USER-ended use Refused"), answer is GraphDataMarking.Refused)
            assertTrue(contract("USER-ended use markCalls zero"), h.store.markCalls.isEmpty())
        }
    }
}
