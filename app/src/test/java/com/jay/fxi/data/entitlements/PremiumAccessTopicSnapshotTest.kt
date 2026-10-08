package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.graph.DefaultGraphV2AtomicFileIo
import com.jay.fxi.data.graph.FileGraphV2DiskStore
import com.jay.fxi.data.graph.GraphAccessFenceBridge
import com.jay.fxi.data.graph.GraphCapabilityScope
import com.jay.fxi.data.graph.GraphDataScope
import com.jay.fxi.data.graph.GraphEntry
import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphObservationSeriesKey
import com.jay.fxi.data.graph.GraphOwnerSource
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.graph.GraphRecorder
import com.jay.fxi.data.graph.GraphRequestState
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
import com.jay.fxi.data.graph.graphRecorderCatalog
import com.jay.fxi.data.graph.joinGraphV2Components
import com.jay.fxi.data.graph.splitGraphV2ServerTab
import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionKey
import com.jay.fxi.data.local.GraphSelectionReadResult
import com.jay.fxi.data.local.GraphSelectionRecord
import com.jay.fxi.data.local.GraphSelectionStore
import com.jay.fxi.data.local.GraphSelectionWriteResult
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicGrantOrigin
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
import com.jay.fxi.ui.premium.graph.GraphV2SeriesToggle
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * L-4e E1: the topic access snapshot the issuer publishes (`l4e_e1_design_v3.md`, `ANDROID_V2_PLAN.md` 동결 후 12번).
 *
 * Each test checks the facts against a recomputation after its steps ([PremiumAccessCoordinator.accessFactsAreCurrent]),
 * but that check alone cannot see order: the ordering and in-doubt tests read the snapshot while the change is still
 * under way — inside a state collector, or with a write parked.
 *
 * S4 U1a (동결 후 18번 (1); design R4c/S4 u1_design_codex.r1 cut down by u1_review_claude.r1 and agreed in
 * u1_design_codex.r2) adds rows graphI01-graphI08 and graphI06b: the graph consumers wired to this real issuer, for the
 * user axis. The graph reads `accessSnapshot` afresh on every question; its fence is only ever one `topicGrantResult()`
 * actually issued, kept through a hold and withdrawn, where a row does so, only after a user end; the coordinator, the
 * recorder and their suppliers share one executor; the recorder's catalog is the last one the coordinator adopted in
 * the current scope. "Closed" means no price adopted, nothing exposed, the kept guard refused, the gate binding
 * nothing, the kept capture reading neither GENERAL nor KRX and the protected entry withheld. graphI02, graphI05,
 * graphI07 and graphI08 check it with the fence still published and the coordinator not yet told, so the snapshot alone
 * must close it; graphI03 checks it after a user end the session has already carried. Refused prices may still leave a
 * loss demand, and a refused capture consumes no demand.
 *
 * S4 U1b (the same design's I09-I10) adds graphI09a-graphI09c and graphI10 on the same assembly, for the capability
 * axis. A capability hold, a capability write in doubt and an explicit capability seal each leave the user axis and the
 * token standing: every GENERAL use stays open and only KRX is refused, checked before the coordinator is told; told,
 * the coordinator strips the KRX half and keeps GENERAL. A landed capability rotation takes the token away without
 * touching the user scope: the old use is refused before the session publishes anything, kb keeps its demand, and the
 * reissued grant reopens GENERAL only.
 *
 * S4 U1c (freeze-7 record r2 §2) completes the kept 1d request with the fence still published - under a seal, a hold,
 * after its release and in doubt - and it applies neither an entry nor a recovery. graphI09d and graphI09e release a
 * capability hold: untold, the coordinator's kept KRX half is shown again; told, memory does not restore it and, with
 * no disk write port in this assembly, only a fresh protected fetch does.
 *
 * S4 U1d (freeze-7 record r2 §2 A3) puts a usd screen holder on the same assembly - its own selection session, the
 * issuer's access revisions, display and focus fixed to the owner - and has it draw a chart with a live price scheduled
 * for publication. graphH0 shows that publication draws the live price when nothing blocks it; under a seal, a P4 hold
 * and a loss in doubt the holder itself reports BLOCKED and neither the current state nor that publication draws
 * anything; after a stale release it draws again under a fresh lifetime and the publication from before the hold is not
 * revived. The closed rows are also closed by the coordinator's protected entry, so the holder's own use check is told
 * apart only on the resume. The production display, focus and DI wiring are RT01's.
 *
 * S4 RT01-A3 (API agreed in R4c/S4 rt01a3_api_codex.r3 from rt01a3_api_proposal.r3) adds graphA01a-graphA01e, graphA02,
 * graphA03, graphA09a and graphA09b on the unwired runtime assembly over this issuer. That assembly owns one fence bridge,
 * gate, recorder, coordinator and deferred sink on one injected main and under its own job beneath a parent. The rows
 * cover its life - nothing runs before start(); close() on main at every stage; the parent's cancellation; a failed
 * construction - and the suppliers its parts read. They check that every part runs on that main, that the bridge
 * publishes only what is handed over and the gate takes no grant the issuer issued but did not deliver, and that the
 * recorder's catalog is the coordinator's in the current data scope only. The rows fix the names
 * `GraphRuntimeAssembly`, `GraphAccessFenceBridge` (with `current` and `close()`) and `graphRecorderCatalog`. Production
 * suppliers, the deliverer's fan-out and the TopicRuntime wiring are the cutover's.
 *
 * S4 RT01-B3 (API agreed in R4c/S4 rt01b3_api_agreed.r2) adds graphB301-graphB303: a usd screen holder built from the
 * assembly's own gate, fence supplier, recorder and coordinator, on its main, with the runtime retirement port listing
 * it. Each change the holder publishes and each selection store call is made on main in all three rows; graphB301 also
 * calls both port retirements from off main and sees their coordinator steps and holder steps on main. This carries
 * PA's same-assembly obligation to the holder (ANDROID_V2_PLAN.md :1457); the coordinator, recorder and sink parts are
 * graphA02's. The issuer's user purge - refused while the retired fence is still delivered, then retried after the next
 * epoch's grant - and its capability purge both run the port under the issuer's lock; the topic session's graph loss is
 * not part of it.
 *
 * A send refused by the guard reaching the wire is TopicUseHttpBoundaryTest GW1-GW4's; a refused admission reaching the
 * disk is GraphV2DiskStoreTest's; a KRX-only refusal keeping a seed off the KRX file is
 * GraphV2RequestCoordinatorCacheTest C10's. This is in-process evidence only: it claims nothing about process death,
 * the real purger or RT01's production assembly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PremiumAccessTopicSnapshotTest {

    private val processJob = SupervisorJob()

    @get:Rule val folder = TemporaryFolder()

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
        override suspend fun markMayContainData(premium: Boolean, krx: Boolean) =
            AccessEpochTransitions.markMayContainData(record, premium, krx).also { record = it }
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
        val coordinator = PremiumAccessCoordinator(
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
        return Harness(store, source, coordinator, hooks, purger)
    }

    /** A bound owner with a confirmed grant, KRX visible, markers standing so a loss asks to rotate, and a grant issued. */
    private suspend fun TestScope.granted(): Harness {
        val h = build()
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 1L))
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
        h.store.record = h.store.record.copy(mayContainPremiumData = true, mayContainKrxData = true)
        checkNotNull(h.coordinator.topicGrantResult().fence) { "no grant was issued" }
        return h
    }

    private fun TestScope.settle() {
        advanceTimeBy(SETTLE)
        runCurrent()
    }

    /** Gates a test parks a write on. Released on the way out, so a failing assertion cannot leave a non-cancellable write waiting. */
    private val gates = mutableListOf<CompletableDeferred<Unit>>()

    private fun gate() = CompletableDeferred<Unit>().also { gates += it }

    private fun snapshotTest(body: suspend TestScope.() -> Unit) = runTest(timeout = 30.seconds) {
        try {
            body()
        } finally {
            gates.forEach { it.complete(Unit) }
            processJob.cancel()
        }
    }

    private fun active(krx: Boolean) = EntitlementsOutcome.StableActive(krxVisible = krx)

    // --- what the snapshot says ---------------------------------------------------------------------------------------

    @Test
    fun beforeAnythingIsBound_theSnapshotAllowsNothing() = snapshotTest {
        val h = build()
        assertEquals(TopicAccessSnapshot.INITIAL, h.snapshot)
        assertFalse(h.facts.userAllowed)
        assertFalse(h.facts.tokenStanding)
        assertNull(h.coordinator.topicGrantResult().fence)
        h.assertCurrent("unbound")
    }

    @Test
    fun aGrant_isReturnedWithTheSnapshotThatStandsForIt() = snapshotTest {
        val h = granted()
        val result = h.coordinator.topicGrantResult()
        val fence = checkNotNull(result.fence)
        assertEquals("the returned snapshot is not the published one", h.snapshot, result.snapshot)
        assertEquals(fence.grant, result.snapshot.facts.token)
        assertTrue(result.snapshot.facts.tokenStanding)
        assertTrue(result.snapshot.facts.userAllowed)
        assertTrue(result.snapshot.facts.capabilityAllowed)
        assertEquals(h.store.record.fence(), result.snapshot.facts.recordFence)
        assertEquals(result.snapshot.revision, h.coordinator.accessRevisions.value)
        h.assertCurrent("granted")
    }

    @Test
    fun anUnchangedRepeat_publishesNothing_soAPullOnEveryRevisionDoesNotLoop() = snapshotTest {
        val h = granted()
        val before = h.snapshot
        repeat(3) { h.coordinator.topicGrantResult() }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals("an unchanged repeat moved the revision", before.revision, h.snapshot.revision)
        assertEquals(before, h.snapshot)
        h.assertCurrent("repeated")
    }

    @Test
    fun aCapabilityHold_keepsTheUserAxisAndTheToken() = snapshotTest {
        val h = granted()
        val token = h.facts.token
        h.source.next = { active(krx = false) }
        h.source.afterFetch = { h.store.loadFailures = 2 }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()

        assertEquals(1, h.coordinator.heldLossCandidateCount())
        assertTrue(TopicAccessBlock.LOSS_CANDIDATE in h.facts.capabilityBlocks)
        assertTrue("a capability hold took the user axis", h.facts.userAllowed)
        assertTrue(h.facts.tokenStanding)
        assertEquals(token, h.facts.token)
        h.assertCurrent("capability hold")
    }

    @Test
    fun aKrxRotation_takesTheTokenAway_andTheNextGrantStandsAgain() = snapshotTest {
        val h = granted()
        val before = h.snapshot
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        settle()

        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertNotEquals(before.facts.recordFence, h.facts.recordFence)
        assertFalse("the old token still stands after the capability epoch moved", h.facts.tokenStanding)
        assertTrue(h.facts.userAllowed)
        assertTrue(TopicAccessBlock.NOT_GRANTED in h.facts.capabilityBlocks)
        assertTrue(h.snapshot.userInvalidations > before.userInvalidations)
        assertEquals(TopicAccessEndReason.CAPABILITY_REVOKED, h.snapshot.lastCapabilityEnd?.reason)
        assertEquals(before.facts.recordFence?.krxCapabilityEpoch, h.snapshot.lastCapabilityEnd?.namespace)
        assertEquals("a capability end was recorded as a user end", before.lastUserEnd, h.snapshot.lastUserEnd)
        h.assertCurrent("rotated")

        val result = h.coordinator.topicGrantResult()
        val reissued = checkNotNull(result.fence)
        assertEquals("the returned snapshot does not carry the token it was issued with", reissued.grant, result.snapshot.facts.token)
        assertTrue(result.snapshot.facts.tokenStanding)
        assertNotEquals(before.facts.token, reissued.grant)
        assertTrue(h.facts.tokenStanding)
        assertEquals(reissued.grant, h.facts.token)
        h.assertCurrent("reissued")
    }

    @Test
    fun aShortUserHold_leavesItsInvalidationBehind_evenWithTheSameToken() = snapshotTest {
        val h = granted()
        val before = h.snapshot
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.source.afterFetch = { h.store.loadFailures = 2 }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertTrue(TopicAccessBlock.LOSS_CANDIDATE in h.facts.userBlocks)
        h.assertCurrent("held")

        // The transport moved to another session of the same owner: the held answer is stale and only its hold goes.
        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        advanceTimeBy(RETRY)
        runCurrent()
        assertEquals(0, h.coordinator.heldLossCandidateCount())
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)

        assertTrue(h.facts.userAllowed)
        assertTrue(h.facts.tokenStanding)
        assertEquals("the context did not move, so neither did the token", before.facts.token, h.facts.token)
        assertTrue("a hold that came and went left no trace", h.snapshot.userInvalidations > before.userInvalidations)
        assertEquals("a stale hold was recorded as an end", before.lastUserEnd, h.snapshot.lastUserEnd)
        h.assertCurrent("released")
    }

    @Test
    fun aConfirmedRecordOfSomeoneElse_orWithoutAUserEpoch_blocksTheUserAxis() = snapshotTest {
        val h = granted()
        val owned = h.store.record
        val invalidations = h.snapshot.userInvalidations

        h.store.record = owned.copy(ownerUid = "someone-else")
        assertNull(h.coordinator.topicGrantResult().fence)
        assertEquals(
            "the user axis and the token fell in one publication, which counts once",
            invalidations + 1,
            h.snapshot.userInvalidations
        )
        assertTrue(TopicAccessBlock.OWNER_MISMATCH in h.facts.userBlocks)
        assertFalse(h.facts.userAllowed)
        assertFalse(h.facts.tokenStanding)
        h.assertCurrent("owner mismatch")

        h.store.record = owned.copy(userAccessEpoch = null)
        assertNull(h.coordinator.topicGrantResult().fence)
        assertTrue(TopicAccessBlock.DERIVED_SEAL in h.facts.userBlocks)
        assertFalse(TopicAccessBlock.OWNER_MISMATCH in h.facts.userBlocks)
        assertFalse(h.facts.userAllowed)
        h.assertCurrent("derived seal")

        h.store.record = owned.copy(krxCapabilityEpoch = null)
        h.coordinator.topicGrantResult()
        assertTrue(TopicAccessBlock.DERIVED_SEAL in h.facts.capabilityBlocks)
        assertTrue("a missing capability epoch took the user axis", h.facts.userAllowed)
        h.assertCurrent("derived capability seal")
    }

    // --- order ----------------------------------------------------------------------------------------------------------

    /**
     * The hold path, not an authoritative loss: a loss decision raises its in-doubt gate before the state setter runs, so it
     * cannot tell whether the setter publishes the snapshot before or after the flows. A hold publishes through the setter alone.
     */
    @Test
    fun aHoldIsInTheSnapshotBeforeEitherFlowShowsIt() = snapshotTest {
        val h = granted()
        val userAllowedWhenWithdrawn = mutableListOf<Boolean>()
        val capabilityAllowedWhenHidden = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.state.collect { access ->
                if (!access.state.grantsPremiumRuntime) userAllowedWhenWithdrawn += h.coordinator.accessSnapshot.facts.userAllowed
            }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.krx.collect { krx ->
                if (krx == KrxCapabilityState.HIDDEN) capabilityAllowedWhenHidden += h.coordinator.accessSnapshot.facts.capabilityAllowed
            }
        }
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.source.afterFetch = { h.store.loadFailures = 2 }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        // Checked before candidate recovery runs.

        assertEquals(1, h.coordinator.heldLossCandidateCount())
        assertFalse("the hold raised no gate, or this test would not see the setter's order", h.facts.userContextUncertain)
        assertTrue("no withdrawal was observed", userAllowedWhenWithdrawn.isNotEmpty())
        assertEquals("a reader of the withdrawn state found the user axis allowed", listOf(false), userAllowedWhenWithdrawn.distinct())
        assertTrue("no hidden capability was observed", capabilityAllowedWhenHidden.isNotEmpty())
        assertEquals("a reader of the hidden capability found it allowed", listOf(false), capabilityAllowedWhenHidden.distinct())
        h.assertCurrent("held")
    }

    @Test
    fun anOpenedSignOutIsInTheSnapshotBeforeItsSignalWakesAnyone() = snapshotTest {
        val h = granted()
        val fence = AuthIdentityFence(OWNER, 1L)
        h.source.liveFence = fence
        val blocksAtSignal = mutableListOf<Set<TopicAccessBlock>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.attemptSignals.collect { signal ->
                if (signal.ticket != null) blocksAtSignal += h.coordinator.accessSnapshot.facts.userBlocks
            }
        }
        h.coordinator.prepareSignOut(fence)
        runCurrent()

        assertTrue("no attempt signal was observed", blocksAtSignal.isNotEmpty())
        assertTrue(
            "a waiter woken by the attempt found access still admitted: $blocksAtSignal",
            blocksAtSignal.all { TopicAccessBlock.ATTEMPT_OPEN in it }
        )
        val end = checkNotNull(h.snapshot.lastUserEnd) { "preparing a sign-out recorded no end" }
        assertEquals(TopicAccessEndReason.SIGNED_OUT, end.reason)
        assertEquals(EntitlementsIdentity(OWNER, 1L), end.binding)
        h.assertCurrent("sign-out prepared")
    }

    /**
     * Uses a synthetic waiter threshold equal to the next admitted round's revision, with no timer, to isolate the release.
     * The public driver does not receive this intermediate step: resumePersistence holds the lock through the whole round.
     * In this failed-READ scenario, admission precedes the next load, so loadCalls distinguishes admission from release.
     */
    @Test
    fun aReleasedHoldIsGoneFromTheSnapshotBeforeItsWaiterWakes() = snapshotTest {
        val h = build()
        h.store.loadFailures = 1
        val held = h.coordinator.onUnverifiedStart().heldByPersistence()
        assertTrue(TopicAccessBlock.PERSISTENCE_HOLD in h.facts.userBlocks)
        val due = checkNotNull(held.nextAttemptAt) { "no automatic round was scheduled: $held" }
        val loadsBeforeTheRound = h.store.loadCalls
        val woken = mutableListOf<Pair<Int, Set<TopicAccessBlock>>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.awaitPersistenceRetry(held.copy(afterRevision = held.afterRevision + 1, nextAttemptAt = null))
            woken += h.store.loadCalls to h.coordinator.accessSnapshot.facts.userBlocks
        }
        advanceTimeBy(due - testScheduler.currentTime)
        h.coordinator.resumePersistence(held.id).applied("the round")

        assertEquals("the waiter did not wake exactly once: $woken", 1, woken.size)
        val (loads, blocks) = woken.single()
        assertTrue("the waiter woke before the round read the record, not at the release", loads > loadsBeforeTheRound)
        assertFalse(
            "a waiter woken by the release found the hold still in the snapshot: $blocks",
            TopicAccessBlock.PERSISTENCE_HOLD in blocks
        )
        assertFalse(TopicAccessBlock.PERSISTENCE_HOLD in h.facts.userBlocks)
        h.assertCurrent("released")
    }

    /** The banner is a reader too: a hold it shows is in the snapshot, and when it clears, so has the snapshot. */
    @Test
    fun aSurfacedHoldAndItsRelease_areInTheSnapshotBeforeTheBannerShowsThem() = snapshotTest {
        val h = build()
        h.store.loadFailures = 1 + PersistenceRecoveryPolicy.AUTOMATIC_ROUNDS
        var held = h.coordinator.onUnverifiedStart().heldByPersistence()
        val shown = mutableListOf<Pair<IdentityRecoveryState, Set<TopicAccessBlock>>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.identityRecovery.collect { recovery -> shown += recovery to h.coordinator.accessSnapshot.facts.userBlocks }
        }
        repeat(PersistenceRecoveryPolicy.AUTOMATIC_ROUNDS) { round ->
            val due = checkNotNull(held.nextAttemptAt) { "no automatic round was scheduled: $held" }
            advanceTimeBy(due - testScheduler.currentTime)
            held = h.coordinator.resumePersistence(held.id).heldByPersistence("round ${round + 1}")
        }
        assertEquals(NoAutoRetry.BUDGET_EXHAUSTED, held.blocked)
        assertTrue(h.coordinator.retryPersistence(held.id))
        h.coordinator.resumePersistence(held.id).applied("the requested round")

        val holds = shown.filter { it.first is IdentityRecoveryState.HoldUnfinished }
        assertTrue("the hold was never surfaced", holds.isNotEmpty())
        assertTrue(
            "the banner showed a hold the snapshot did not have: $holds",
            holds.all { TopicAccessBlock.PERSISTENCE_HOLD in it.second }
        )
        val cleared = shown.dropWhile { it.first !is IdentityRecoveryState.HoldUnfinished }
            .filter { it.first == IdentityRecoveryState.None }
        assertEquals("the banner did not clear exactly once: $shown", 1, cleared.size)
        assertFalse(
            "the banner cleared while the snapshot still had the hold: $shown",
            TopicAccessBlock.PERSISTENCE_HOLD in cleared.single().second
        )
        h.assertCurrent("released")
    }

    @Test
    fun aUserLossWriteInDoubt_blocksTheUserAxisBeforeTheWriteStarts() = snapshotTest {
        val h = granted()
        val gate = gate()
        h.store.rotationGate = gate
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        // Launched: the decision runs inside refresh, and the parked write would hold this coroutine too.
        val refreshing = launch { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        assertTrue("the rotation did not start", h.store.rotationParked.isCompleted)

        // The loss is not published yet, and the write may land at any moment.
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertTrue(TopicAccessBlock.CONTEXT_UNCERTAIN in h.facts.userBlocks)
        assertFalse(h.facts.userAllowed)
        assertFalse(h.facts.tokenStanding)
        assertEquals(TopicAccessEndReason.AUTHORITATIVE_LOSS, h.snapshot.lastUserEnd?.reason)
        // No recomputation here: the parked write holds the coordinator's lock.

        gate.complete(Unit)
        settle()
        refreshing.join()
        assertEquals(PremiumAccessState.FreeConfirmed, h.coordinator.state.value.state)
        assertFalse(h.facts.userContextUncertain)
        assertTrue(TopicAccessBlock.NOT_GRANTED in h.facts.userBlocks)
        assertFalse(h.facts.userAllowed)
        h.assertCurrent("settled")
    }

    @Test
    fun aKrxWriteInDoubt_blocksOnlyTheCapability_andTheUserAxisGoesOn() = snapshotTest {
        val h = granted()
        val before = h.snapshot
        val gate = gate()
        h.store.rotationGate = gate
        h.source.next = { active(krx = false) }
        val refreshing = launch { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()
        assertTrue("the rotation did not start", h.store.rotationParked.isCompleted)

        assertTrue(TopicAccessBlock.CONTEXT_UNCERTAIN in h.facts.capabilityBlocks)
        assertFalse(h.facts.capabilityAllowed)
        assertTrue("a capability write in doubt took the user axis", h.facts.userAllowed)
        assertTrue("a capability write in doubt took the token", h.facts.tokenStanding)
        assertEquals(before.facts.token, h.facts.token)
        assertFalse(h.facts.userContextUncertain)
        // No recomputation here: the parked write holds the coordinator's lock.

        gate.complete(Unit)
        settle()
        refreshing.join()
        assertFalse(h.facts.capabilityContextUncertain)
        assertFalse("the confirmed capability epoch change left the old token standing", h.facts.tokenStanding)
        assertTrue(h.facts.userAllowed)
        h.assertCurrent("settled")
    }

    @Test
    fun aDoubtThatSettles_neverPublishesTheOldAllowanceInBetween() = snapshotTest {
        val h = granted()
        val oldToken = h.facts.token
        val seen = mutableListOf<TopicAccessFacts>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.accessRevisions.collect { seen += h.coordinator.accessSnapshot.facts }
        }
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        settle()

        val doubtStarts = seen.indexOfFirst { it.capabilityContextUncertain }
        assertTrue("the capability write was never in doubt", doubtStarts >= 0)
        val after = seen.drop(doubtStarts)
        assertTrue(
            "the capability was shown allowed again after its write came into doubt: $after",
            after.none { it.capabilityAllowed }
        )
        assertTrue(
            "the old token was shown standing after the capability epoch was confirmed changed",
            after.dropWhile { it.recordFence == seen[doubtStarts].recordFence }.none { it.token == oldToken && it.tokenStanding }
        )
        h.assertCurrent("settled")
    }

    @Test
    fun aFailedStoreRead_isPublishedBeforeItThrows() = snapshotTest {
        val h = granted()
        h.store.loadFailures = 1
        try {
            h.coordinator.topicGrantResult()
            fail("the read did not throw")
        } catch (expected: IOException) {
            assertTrue("the failure was not published before it propagated", h.facts.recordUnconfirmed)
        }
        assertTrue("a plain read failure took the user axis", h.facts.userAllowed)
        h.assertCurrent("failed read")

        assertNotNull(h.coordinator.topicGrantResult().fence)
        assertFalse(h.facts.recordUnconfirmed)
        h.assertCurrent("read again")
    }

    @Test
    fun aPersistenceHoldIsPublished() = snapshotTest {
        val h = build()
        h.store.loadFailures = 1
        h.coordinator.onUnverifiedStart()

        assertTrue(TopicAccessBlock.PERSISTENCE_HOLD in h.facts.userBlocks)
        assertFalse(h.facts.userAllowed)
        h.assertCurrent("held")
    }

    @Test
    fun aRotationThatLandsAndThrows_isReadBackAsLanded_andNothingStaysInDoubt() = snapshotTest {
        val h = granted()
        val before = h.snapshot
        h.store.rotationLandsThenThrows = 1
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)

        assertEquals(PremiumAccessState.FreeConfirmed, h.coordinator.state.value.state)
        assertEquals(h.store.record.fence(), h.facts.recordFence)
        assertNotEquals(before.facts.recordFence, h.facts.recordFence)
        assertFalse(TopicAccessBlock.EXPLICIT_SEAL in h.facts.userBlocks)
        assertFalse(h.facts.userContextUncertain)
        assertFalse(h.facts.capabilityContextUncertain)
        assertFalse(h.facts.tokenStanding)
        assertEquals(TopicAccessEndReason.AUTHORITATIVE_LOSS, h.snapshot.lastUserEnd?.reason)
        h.assertCurrent("landed")
    }

    @Test
    fun aLandedRotationThatCannotBeReadBack_sealsTheNamespaceItWasAskedToRetire() = snapshotTest {
        val h = granted()
        val retiring = h.store.record.userAccessEpoch
        h.store.rotationLandsThenThrows = 1
        h.store.failTheReadBack = true
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        // Checked before loss recovery reads the record again.

        assertNotEquals("the fixture did not land the rotation", retiring, h.store.record.userAccessEpoch)
        assertTrue(TopicAccessBlock.EXPLICIT_SEAL in h.facts.userBlocks)
        val sealed = checkNotNull(h.snapshot.lastUserEnd)
        assertEquals(TopicAccessEndReason.SEALED, sealed.reason)
        assertEquals("the seal named another epoch than the one it retires", retiring, sealed.namespace)
        assertEquals(EntitlementsIdentity(OWNER, 1L), sealed.binding)
        assertFalse("the seal holds the axis; the in-doubt flag was not released with the decision", h.facts.userContextUncertain)
        h.assertCurrent("sealed")
    }

    @Test
    fun aCallerCancelledWhileItsRotationIsParked_leavesTheSnapshotSettled() = snapshotTest {
        val h = granted()
        val gate = gate()
        h.store.rotationGate = gate
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        val refreshing = launch { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        assertTrue(h.store.rotationParked.isCompleted)
        assertTrue(h.facts.userContextUncertain)

        refreshing.cancel()
        gate.complete(Unit)
        settle()
        refreshing.join()

        assertFalse(h.facts.userContextUncertain)
        assertFalse(h.facts.userAllowed)
        h.assertCurrent("cancelled")
    }

    @Test
    fun aUserLossWriteParkedAfterItLands_isWithheldUntilItsDecisionIsPublished() = snapshotTest {
        parkedAfterLanding(Axis.USER)
    }

    @Test
    fun aKrxWriteParkedAfterItLands_withholdsOnlyTheCapability() = snapshotTest {
        parkedAfterLanding(Axis.CAPABILITY)
    }

    @Test
    fun aUserLossWriteTheStoreCancelsAfterLanding_isWithheldWhileItIsReadBack() = snapshotTest {
        cancelledAfterLanding(Axis.USER)
    }

    @Test
    fun aKrxWriteTheStoreCancelsAfterLanding_withholdsOnlyTheCapabilityWhileItIsReadBack() = snapshotTest {
        cancelledAfterLanding(Axis.CAPABILITY)
    }

    @Test
    fun aUserLossWriteTheStoreCancelsBeforeWriting_andCannotReadBack_isSealed() = snapshotTest {
        cancelledAndUnreadable(Axis.USER)
    }

    @Test
    fun aKrxWriteTheStoreCancelsBeforeWriting_andCannotReadBack_sealsOnlyTheCapability() = snapshotTest {
        cancelledAndUnreadable(Axis.CAPABILITY)
    }

    @Test
    fun anIdentityChangeOverAHold_neverShowsTheEndingGrantAgain() = snapshotTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.source.afterFetch = { h.store.loadFailures = 2 }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        assertTrue(TopicAccessBlock.LOSS_CANDIDATE in h.facts.userBlocks)

        val seen = mutableListOf<TopicAccessFacts>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.accessRevisions.collect { seen += h.coordinator.accessSnapshot.facts }
        }
        h.source.identity = EntitlementsIdentity(OTHER, 1L)
        h.coordinator.onIdentityChanged(AuthIdentityFence(OTHER, 1L))

        assertTrue("the ending binding's grant was shown allowed after its hold was cleared: $seen", seen.none { it.userAllowed })
        assertFalse(TopicAccessBlock.LOSS_CANDIDATE in h.facts.userBlocks)
        val end = checkNotNull(h.snapshot.lastUserEnd)
        assertEquals(TopicAccessEndReason.IDENTITY_CHANGED, end.reason)
        assertEquals(EntitlementsIdentity(OWNER, 1L), end.binding)
        h.assertCurrent("rebound")
    }

    @Test
    fun aNullTargetSealReleasedByRecovery_isPublishedBeforeTheReapprovalIsHandedOn() = snapshotTest {
        val h = granted()
        h.store.record = h.store.record.copy(krxCapabilityEpoch = null)
        h.store.rotationFailures = 2
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        assertTrue(TopicAccessBlock.EXPLICIT_SEAL in h.facts.capabilityBlocks)

        val blocksAtReapproval = mutableListOf<Set<TopicAccessBlock>>()
        h.hooks.onReapproval = { blocksAtReapproval += h.coordinator.accessSnapshot.facts.capabilityBlocks }
        settle()

        assertNotNull("recovery did not land the rotation", h.store.record.krxCapabilityEpoch)
        assertTrue("no re-approval was handed on", blocksAtReapproval.isNotEmpty())
        assertTrue(
            "the re-approval was handed on while the snapshot still showed the released seal: $blocksAtReapproval",
            blocksAtReapproval.none { TopicAccessBlock.EXPLICIT_SEAL in it }
        )
        h.assertCurrent("released")
    }

    @Test
    fun aGrantReturnedEarlier_keepsTheSnapshotItWasIssuedWith() = snapshotTest {
        val h = granted()
        val earlier = h.coordinator.topicGrantResult()
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        settle()

        assertTrue(h.snapshot.revision > earlier.snapshot.revision)
        assertTrue("the earlier result was repackaged with a later snapshot", earlier.snapshot.facts.tokenStanding)
        assertEquals(earlier.fence?.grant, earlier.snapshot.facts.token)
        assertFalse(h.facts.tokenStanding)
    }

    // --- ends -------------------------------------------------------------------------------------------------------------

    @Test
    fun aLossThatCannotBeEstablished_isSealedAndRecordedAfterTheLoss() = snapshotTest {
        val h = granted()
        val ends = mutableListOf<TopicAccessEnd>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.accessRevisions.collect {
                h.coordinator.accessSnapshot.lastUserEnd?.let { end -> if (ends.lastOrNull() != end) ends += end }
            }
        }
        // The rotation throws before writing, so the read-back shows the loss still owed: sealed.
        h.store.rotationFailures = 1
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        // Checked before loss recovery gets to run: its own rotation would release the seal.

        assertTrue(TopicAccessBlock.EXPLICIT_SEAL in h.facts.userBlocks)
        assertEquals(
            listOf(TopicAccessEndReason.AUTHORITATIVE_LOSS, TopicAccessEndReason.SEALED),
            ends.map { it.reason }.takeLast(2)
        )
        assertTrue("the seal was not recorded after the loss", ends.last().sequence > ends[ends.size - 2].sequence)
        assertEquals(TopicAccessEndReason.SEALED, h.snapshot.lastUserEnd?.reason)
        h.assertCurrent("sealed")
    }

    @Test
    fun aSignOut_recordsTheEndOfTheNamespaceBeingLeft() = snapshotTest {
        val h = granted()
        val namespace = h.store.record.userAccessEpoch
        h.coordinator.onSignedOut(AuthIdentityFence(OWNER, 1L))
        runCurrent()

        val end = checkNotNull(h.snapshot.lastUserEnd)
        assertEquals(TopicAccessEndReason.SIGNED_OUT, end.reason)
        assertEquals(EntitlementsIdentity(OWNER, 1L), end.binding)
        assertEquals(OWNER, end.ownerUid)
        assertEquals(namespace, end.namespace)
        assertFalse(h.facts.tokenStanding)
        assertFalse(h.facts.userAllowed)
        h.assertCurrent("signed out")
    }

    @Test
    fun theFirstBinding_endsNothing() = snapshotTest {
        val h = build()
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 1L))
        assertNull("a binding with nothing before it recorded an end", h.snapshot.lastUserEnd)
    }

    @Test
    fun anEndWhileAlreadyWithheld_isStillRecorded() = snapshotTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.source.afterFetch = { h.store.loadFailures = 2 }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        assertFalse(h.facts.userAllowed)
        val before = h.snapshot.lastUserEnd

        h.coordinator.onSignedOut(AuthIdentityFence(OWNER, 1L))
        val end = checkNotNull(h.snapshot.lastUserEnd)
        assertEquals(TopicAccessEndReason.SIGNED_OUT, end.reason)
        assertTrue(end.sequence > (before?.sequence ?: 0L))
        h.assertCurrent("signed out while held")
    }

    @Test
    fun anEnd_isNotReplacedByALaterHoldAndItsRelease() = snapshotTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        val loss = checkNotNull(h.snapshot.lastUserEnd)
        assertEquals(TopicAccessEndReason.AUTHORITATIVE_LOSS, loss.reason)

        h.source.next = { active(krx = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)

        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.source.afterFetch = { h.store.loadFailures = 2 }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        assertTrue(TopicAccessBlock.LOSS_CANDIDATE in h.facts.userBlocks)
        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        advanceTimeBy(RETRY)
        runCurrent()
        assertEquals(0, h.coordinator.heldLossCandidateCount())

        assertEquals("a hold and its release replaced the end", loss, h.snapshot.lastUserEnd)
        h.assertCurrent("released")
    }

    @Test
    fun anEndAtABoundary_neverNamesAnotherOwnersNamespace() = snapshotTest {
        val h = granted()
        h.store.record = h.store.record.copy(ownerUid = OTHER)
        h.coordinator.topicGrantResult() // the other owner's record is now the last confirmed

        h.coordinator.onSignedOut(AuthIdentityFence(OWNER, 1L))
        val end = checkNotNull(h.snapshot.lastUserEnd)
        assertEquals(EntitlementsIdentity(OWNER, 1L), end.binding)
        assertEquals(OWNER, end.ownerUid)
        assertNull("the end named another owner's namespace", end.namespace)
    }

    // --- the user-end invalidations floor (F2b-1 T13) ---------------------------------------------------------------------
    //
    // A lifetime acquired before a user end carries fewer invalidations than the floor that end pins, and one acquired after
    // it carries at least as many — however late the snapshot is read. Every published snapshot is checked as a revision
    // collector reads it (T13-08 is part of every walk).

    /** Every snapshot published from now on, read when its revision is announced. */
    private fun TestScope.publishedFrom(h: Harness): List<TopicAccessSnapshot> {
        val published = mutableListOf<TopicAccessSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.accessRevisions.collect { published += h.coordinator.accessSnapshot }
        }
        return published
    }

    /** A new user end pins the floor to its own publication's invalidations; any other publication keeps the floor. */
    private fun assertFloorsPinned(what: String, start: TopicAccessSnapshot, published: List<TopicAccessSnapshot>) {
        assertTrue("$what: nothing was published", published.isNotEmpty())
        var previous = start
        for (next in published) {
            if (next.lastUserEnd?.sequence != previous.lastUserEnd?.sequence) {
                assertEquals(
                    "$what: the end at revision ${next.revision} did not pin its own publication's invalidations",
                    next.userInvalidations,
                    next.userEndInvalidationsFloor
                )
            } else {
                assertEquals(
                    "$what: the floor moved at revision ${next.revision} without a new user end",
                    previous.userEndInvalidationsFloor,
                    next.userEndInvalidationsFloor
                )
            }
            previous = next
        }
    }

    /** T13-01. */
    @Test
    fun theUserEndFloor_isZeroUntilTheFirstUserEnd_whateverIsInvalidated() = snapshotTest {
        assertEquals(0L, TopicAccessSnapshot.INITIAL.userEndInvalidationsFloor)
        val h = build()
        val start = h.snapshot
        val published = publishedFrom(h)
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 1L))
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        h.store.record = h.store.record.copy(mayContainPremiumData = true, mayContainKrxData = true)
        checkNotNull(h.coordinator.topicGrantResult().fence) { "no grant was issued" }

        // A capability rotation takes the token away.
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        settle()
        checkNotNull(h.coordinator.topicGrantResult().fence) { "no grant was reissued" }
        // A user hold that comes and goes.
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.source.afterFetch = { h.store.loadFailures = 2 }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        advanceTimeBy(RETRY)
        runCurrent()

        assertNull("this walk recorded a user end", h.snapshot.lastUserEnd)
        assertTrue("nothing was invalidated", h.snapshot.userInvalidations > 0L)
        assertFloorsPinned("before any end", start, published)
        assertEquals(0L, h.snapshot.userEndInvalidationsFloor)
    }

    /** T13-02 (identity boundary) and T13-08. */
    @Test
    fun aSignOut_pinsTheFloorToTheInvalidationsItsOwnPublicationCounted() = snapshotTest {
        val h = granted()
        val before = h.snapshot
        assertNull(before.lastUserEnd)
        assertTrue(before.facts.userAllowed && before.facts.tokenStanding)
        val published = publishedFrom(h)

        h.coordinator.onSignedOut(AuthIdentityFence(OWNER, 1L))
        runCurrent()

        val first = published.first { it.lastUserEnd != null }
        assertEquals(TopicAccessEndReason.SIGNED_OUT, first.lastUserEnd?.reason)
        assertEquals("both axes fell with the end, which counts once", before.userInvalidations + 1, first.userInvalidations)
        assertEquals(first.userInvalidations, first.userEndInvalidationsFloor)
        assertTrue(
            "a lifetime acquired before the end is not below the floor",
            before.userInvalidations < first.userEndInvalidationsFloor
        )
        assertFloorsPinned("signed out", before, published)
    }

    /** T13-02 (authoritative loss) and T13-08. */
    @Test
    fun anAuthoritativeLoss_pinsTheFloorToTheInvalidationsItsOwnPublicationCounted() = snapshotTest {
        val h = granted()
        val before = h.snapshot
        assertNull(before.lastUserEnd)
        val published = publishedFrom(h)

        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()

        val first = published.first { it.lastUserEnd != null }
        assertEquals(TopicAccessEndReason.AUTHORITATIVE_LOSS, first.lastUserEnd?.reason)
        assertEquals("both axes fell with the end, which counts once", before.userInvalidations + 1, first.userInvalidations)
        assertEquals(first.userInvalidations, first.userEndInvalidationsFloor)
        assertFloorsPinned("lost", before, published)
    }

    /** T13-03, then T13-06 for a later end. */
    @Test
    fun theFloor_holdsThroughReapprovalAHoldAndItsRelease_andMovesOnlyWithTheNextEnd() = snapshotTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        val end = checkNotNull(h.snapshot.lastUserEnd)
        val floor = h.snapshot.userEndInvalidationsFloor
        val start = h.snapshot
        val published = publishedFrom(h)

        h.source.next = { active(krx = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        checkNotNull(h.coordinator.topicGrantResult().fence) { "no grant after re-approval" }
        assertTrue("a lifetime acquired after the end is below the floor", h.snapshot.userInvalidations >= floor)

        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.source.afterFetch = { h.store.loadFailures = 2 }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        advanceTimeBy(RETRY)
        runCurrent()

        assertEquals("a hold or a re-approval replaced the end", end, h.snapshot.lastUserEnd)
        assertTrue("the hold left no invalidation", h.snapshot.userInvalidations > floor)
        assertEquals(floor, h.snapshot.userEndInvalidationsFloor)
        assertFloorsPinned("held after the end", start, published)

        val held = h.snapshot
        h.coordinator.onSignedOut(AuthIdentityFence(OWNER, 1L))
        runCurrent()
        val next = published.first { it.lastUserEnd?.sequence != end.sequence }
        assertEquals(TopicAccessEndReason.SIGNED_OUT, next.lastUserEnd?.reason)
        assertTrue("the next end kept the old floor", next.userEndInvalidationsFloor > floor)
        assertTrue(next.userEndInvalidationsFloor >= held.userInvalidations)
        assertFloorsPinned("ended again", start, published)
    }

    /** T13-04. */
    @Test
    fun theFloor_ignoresCapabilityEndsAndTokenInvalidations() = snapshotTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        h.source.next = { active(krx = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        h.store.record = h.store.record.copy(mayContainPremiumData = true, mayContainKrxData = true)
        checkNotNull(h.coordinator.topicGrantResult().fence) { "no grant after re-approval" }
        val end = checkNotNull(h.snapshot.lastUserEnd)
        // Move the count past the floor without an end, so a capability end that re-pinned it would show.
        val owned = h.store.record
        h.store.record = owned.copy(ownerUid = "someone-else")
        assertNull(h.coordinator.topicGrantResult().fence)
        h.store.record = owned
        checkNotNull(h.coordinator.topicGrantResult().fence) { "no grant after the owner came back" }
        assertEquals("blocking the axes recorded a user end", end, h.snapshot.lastUserEnd)
        assertTrue(
            "the count did not move past the floor before the capability end",
            h.snapshot.userInvalidations > h.snapshot.userEndInvalidationsFloor
        )
        val start = h.snapshot
        val published = publishedFrom(h)

        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        settle()
        checkNotNull(h.coordinator.topicGrantResult().fence) { "no grant was reissued" }

        assertEquals(TopicAccessEndReason.CAPABILITY_REVOKED, h.snapshot.lastCapabilityEnd?.reason)
        assertEquals("a capability end was recorded as a user end", end, h.snapshot.lastUserEnd)
        assertTrue("the rotation left no invalidation", h.snapshot.userInvalidations > start.userInvalidations)
        assertEquals(start.userEndInvalidationsFloor, h.snapshot.userEndInvalidationsFloor)
        assertFloorsPinned("capability rotated", start, published)
    }

    /** T13-05. */
    @Test
    fun anEndWhileAlreadyBlocked_pinsTheCurrentCount_withoutCountingAnotherInvalidation() = snapshotTest {
        val h = granted()
        val n = h.snapshot.userInvalidations
        h.store.record = h.store.record.copy(ownerUid = "someone-else")
        assertNull(h.coordinator.topicGrantResult().fence)
        val blocked = h.snapshot
        assertFalse(blocked.facts.userAllowed)
        assertFalse(blocked.facts.tokenStanding)
        assertEquals(n + 1, blocked.userInvalidations)
        assertNull("blocking the axes recorded a user end", blocked.lastUserEnd)
        assertEquals(0L, blocked.userEndInvalidationsFloor)
        val published = publishedFrom(h)

        h.coordinator.onSignedOut(AuthIdentityFence(OWNER, 1L))
        runCurrent()

        val first = published.first { it.lastUserEnd != null }
        assertEquals("an end with both axes already down counted an invalidation", n + 1, first.userInvalidations)
        assertEquals(n + 1, first.userEndInvalidationsFloor)
        assertFloorsPinned("ended while blocked", blocked, published)
    }

    /** T13-06 (an authoritative loss and the seal recorded after it). */
    @Test
    fun aLossAndItsSeal_eachPinTheirOwnFloor() = snapshotTest {
        val h = granted()
        val before = h.snapshot
        val published = publishedFrom(h)
        h.store.rotationFailures = 1
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        // Checked before loss recovery gets to run, as in the sealing test above.

        val reasons = published.mapNotNull { it.lastUserEnd }.distinct().map { it.reason }
        assertEquals(
            listOf(TopicAccessEndReason.AUTHORITATIVE_LOSS, TopicAccessEndReason.SEALED),
            reasons.takeLast(2)
        )
        assertFloorsPinned("sealed", before, published)
    }

    /** T13-07. */
    @Test
    fun afterAnEnd_anUnchangedRepeat_stillPublishesNothing() = snapshotTest {
        val h = granted()
        h.coordinator.onSignedOut(AuthIdentityFence(OWNER, 1L))
        settle()
        val settled = h.snapshot
        checkNotNull(settled.lastUserEnd)
        val published = publishedFrom(h)

        repeat(3) { h.coordinator.topicGrantResult() }
        runCurrent()

        assertTrue("the current snapshot was not read", published.isNotEmpty())
        assertEquals("an unchanged repeat published", listOf(settled), published.distinct())
        assertTrue("an unchanged repeat replaced the snapshot", settled === h.snapshot)
        assertEquals(settled.revision, h.coordinator.accessRevisions.value)
    }

    // --- a loss write's boundaries ------------------------------------------------------------------------------------------

    /** The axis a loss write is for. A user loss rotates both epochs; a KRX false edge rotates the capability only. */
    private enum class Axis { USER, CAPABILITY }

    private fun lossOn(axis: Axis): EntitlementsOutcome = when (axis) {
        Axis.USER -> EntitlementsOutcome.StableInactive(krxVisible = false)
        Axis.CAPABILITY -> active(krx = false)
    }

    private fun intentFor(axis: Axis): RefreshIntent = when (axis) {
        Axis.USER -> RefreshIntent.FORCE_PREMIUM
        Axis.CAPABILITY -> RefreshIntent.FORCE_ENTITLEMENTS
    }

    /** Every snapshot published from the start of the watch, and what a reader of the withdrawing flow found. */
    private class Watch {
        val published = mutableListOf<TopicAccessSnapshot>()
        val atWithdrawal = mutableListOf<TopicAccessFacts>()
    }

    private fun TestScope.watch(h: Harness, axis: Axis): Watch {
        val watch = Watch()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.accessRevisions.collect { watch.published += h.coordinator.accessSnapshot }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            when (axis) {
                Axis.USER -> h.coordinator.state.collect { access ->
                    if (!access.state.grantsPremiumRuntime) watch.atWithdrawal += h.coordinator.accessSnapshot.facts
                }
                Axis.CAPABILITY -> h.coordinator.krx.collect { krx ->
                    if (krx == KrxCapabilityState.HIDDEN) watch.atWithdrawal += h.coordinator.accessSnapshot.facts
                }
            }
        }
        return watch
    }

    /** The write may have landed and nothing established it: its own axis is withheld, the other is not, and its end is recorded. */
    private fun assertInDoubt(what: String, h: Harness, axis: Axis, before: TopicAccessSnapshot) {
        val facts = h.facts
        when (axis) {
            Axis.USER -> {
                assertTrue("$what: the user axis was not in doubt", TopicAccessBlock.CONTEXT_UNCERTAIN in facts.userBlocks)
                assertFalse("$what: the user axis was allowed", facts.userAllowed)
                assertFalse("$what: the old token still stood", facts.tokenStanding)
                val end = checkNotNull(h.snapshot.lastUserEnd) { "$what: no end was recorded before the write" }
                assertEquals(TopicAccessEndReason.AUTHORITATIVE_LOSS, end.reason)
                assertEquals(before.facts.recordFence?.userAccessEpoch, end.namespace)
            }
            Axis.CAPABILITY -> {
                assertTrue("$what: the capability was not in doubt", TopicAccessBlock.CONTEXT_UNCERTAIN in facts.capabilityBlocks)
                assertFalse("$what: the capability was allowed", facts.capabilityAllowed)
                assertTrue("$what: a capability write in doubt took the user axis", facts.userAllowed)
                assertTrue("$what: a capability write in doubt took the token", facts.tokenStanding)
                assertEquals(before.facts.token, facts.token)
                val end = checkNotNull(h.snapshot.lastCapabilityEnd) { "$what: no end was recorded before the write" }
                assertEquals(TopicAccessEndReason.CAPABILITY_REVOKED, end.reason)
                assertEquals(before.facts.recordFence?.krxCapabilityEpoch, end.namespace)
                assertEquals("$what: a capability end was recorded as a user end", before.lastUserEnd, h.snapshot.lastUserEnd)
            }
        }
    }

    /**
     * From the first publication that raised the doubt to the end: the axis is never shown allowed again, a capability loss never
     * takes the user axis, the old token never stands on a record that moved, and a reader of the withdrawing flow found the axis
     * already withheld. Covers the observation of the read-back and the decision's publication, which no gate stops at.
     */
    private fun assertNothingCameBack(what: String, axis: Axis, watch: Watch, before: TopicAccessSnapshot) {
        val start = watch.published.indexOfFirst {
            if (axis == Axis.USER) it.facts.userContextUncertain else it.facts.capabilityContextUncertain
        }
        assertTrue("$what: the write was never in doubt", start >= 0)
        val after = watch.published.drop(start).map { it.facts }
        when (axis) {
            Axis.USER -> assertTrue("$what: the user axis was shown allowed again: $after", after.none { it.userAllowed })
            Axis.CAPABILITY -> {
                assertTrue("$what: the capability was shown allowed again: $after", after.none { it.capabilityAllowed })
                assertTrue("$what: a capability loss took the user axis: $after", after.all { it.userAllowed })
            }
        }
        assertTrue(
            "$what: the old token was shown standing on a record that moved: $after",
            after.none { it.recordFence != before.facts.recordFence && it.token == before.facts.token && it.tokenStanding }
        )
        assertTrue("$what: no withdrawal reached the flow", watch.atWithdrawal.isNotEmpty())
        assertTrue(
            "$what: a reader of the withdrawal found the axis allowed: ${watch.atWithdrawal}",
            watch.atWithdrawal.none { if (axis == Axis.USER) it.userAllowed else it.capabilityAllowed }
        )
    }

    /** The write landed and was established: nothing is in doubt, nothing is sealed, and the published loss holds the axis. */
    private fun assertLandedLoss(what: String, h: Harness, axis: Axis) {
        assertEquals("$what: the landed record was not observed", h.store.record.fence(), h.facts.recordFence)
        assertFalse("$what: the user doubt outlived the decision", h.facts.userContextUncertain)
        assertFalse("$what: the capability doubt outlived the decision", h.facts.capabilityContextUncertain)
        assertFalse("$what: sealed the user axis", TopicAccessBlock.EXPLICIT_SEAL in h.facts.userBlocks)
        assertFalse("$what: sealed the capability", TopicAccessBlock.EXPLICIT_SEAL in h.facts.capabilityBlocks)
        assertFalse("$what: the old token stood on the rotated record", h.facts.tokenStanding)
        when (axis) {
            Axis.USER -> {
                assertEquals(PremiumAccessState.FreeConfirmed, h.coordinator.state.value.state)
                assertTrue(TopicAccessBlock.NOT_GRANTED in h.facts.userBlocks)
            }
            Axis.CAPABILITY -> {
                assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
                assertTrue(TopicAccessBlock.NOT_GRANTED in h.facts.capabilityBlocks)
                assertTrue("$what: a capability loss took the user axis", h.facts.userAllowed)
            }
        }
    }

    /** The write lands and parks before the store returns. */
    private suspend fun TestScope.parkedAfterLanding(axis: Axis) {
        val h = granted()
        val before = h.snapshot
        val watch = watch(h, axis)
        val gate = gate()
        h.store.landedGate = gate
        h.source.next = { lossOn(axis) }
        // Launched: the decision runs inside refresh, and the parked write would hold this coroutine too.
        val refreshing = launch { h.coordinator.refresh(intentFor(axis)) }
        runCurrent()
        assertTrue("the rotation did not land", h.store.landedParked.isCompleted)
        assertNotEquals("the fixture did not write", before.facts.recordFence, h.store.record.fence())
        assertEquals("the write was observed before the store returned", before.facts.recordFence, h.facts.recordFence)
        assertInDoubt("landed, not returned", h, axis, before)
        // No recomputation here: the parked write holds the coordinator's lock.

        gate.complete(Unit)
        settle()
        refreshing.join()
        assertLandedLoss("returned", h, axis)
        assertNothingCameBack("returned", axis, watch, before)
        h.assertCurrent("returned")
    }

    /** The write lands, the store then throws its own cancellation, and the read-back parks. */
    private suspend fun TestScope.cancelledAfterLanding(axis: Axis) {
        val h = granted()
        val before = h.snapshot
        val watch = watch(h, axis)
        val gate = gate()
        h.store.rotationLandsThenThrows = 1
        h.store.rotationError = { CancellationException("the store's own scope ended") }
        h.store.readBackGate = gate
        h.source.next = { lossOn(axis) }
        val refreshing = launch { h.coordinator.refresh(intentFor(axis)) }
        runCurrent()
        assertTrue("the read-back did not start", h.store.readBackParked.isCompleted)
        assertNotEquals("the fixture did not write", before.facts.recordFence, h.store.record.fence())
        assertTrue("the cancelled write was not published before its read-back", h.facts.recordUnconfirmed)
        assertEquals(before.facts.recordFence, h.facts.recordFence)
        assertInDoubt("read-back parked", h, axis, before)

        gate.complete(Unit)
        settle()
        refreshing.join()
        assertFalse("the store's cancellation cancelled the caller", refreshing.isCancelled)
        assertLandedLoss("read back", h, axis)
        assertNothingCameBack("read back", axis, watch, before)
        h.assertCurrent("read back")
    }

    /** The store throws its own cancellation before writing, and again when the write is read back: the loss is sealed. */
    private suspend fun TestScope.cancelledAndUnreadable(axis: Axis) {
        val h = granted()
        val before = h.snapshot
        val watch = watch(h, axis)
        h.store.rotationFailures = 1
        h.store.rotationError = { CancellationException("the store's own scope ended") }
        h.store.failTheReadBack = true
        h.store.readBackError = { CancellationException("the store's own scope ended") }
        h.source.next = { lossOn(axis) }
        val escaped = runCatching { h.coordinator.refresh(intentFor(axis)) }.exceptionOrNull()
        // Checked before loss recovery runs: its own rotation would release the seal.

        assertNull("the store's cancellation escaped the decision: $escaped", escaped)
        assertEquals("the fixture wrote", before.facts.recordFence, h.store.record.fence())
        assertFalse("the user doubt outlived the decision", h.facts.userContextUncertain)
        assertFalse("the capability doubt outlived the decision", h.facts.capabilityContextUncertain)
        val ends = watch.published.mapNotNull { if (axis == Axis.USER) it.lastUserEnd else it.lastCapabilityEnd }.distinct()
        assertTrue("fewer than two ends were recorded: $ends", ends.size >= 2)
        val (loss, sealed) = ends.takeLast(2)
        when (axis) {
            Axis.USER -> {
                assertTrue(TopicAccessBlock.EXPLICIT_SEAL in h.facts.userBlocks)
                assertFalse(h.facts.userAllowed)
                assertFalse(h.facts.tokenStanding)
                assertEquals(TopicAccessEndReason.AUTHORITATIVE_LOSS, loss.reason)
                assertEquals(before.facts.recordFence?.userAccessEpoch, sealed.namespace)
            }
            Axis.CAPABILITY -> {
                assertTrue(TopicAccessBlock.EXPLICIT_SEAL in h.facts.capabilityBlocks)
                assertFalse("a capability seal sealed the user axis", TopicAccessBlock.EXPLICIT_SEAL in h.facts.userBlocks)
                assertTrue("a capability seal took the user axis", h.facts.userAllowed)
                assertTrue("the record never moved, so the token still stands", h.facts.tokenStanding)
                assertEquals(TopicAccessEndReason.CAPABILITY_REVOKED, loss.reason)
                assertEquals(before.facts.recordFence?.krxCapabilityEpoch, sealed.namespace)
                assertEquals("a capability end was recorded as a user end", before.lastUserEnd, h.snapshot.lastUserEnd)
            }
        }
        assertEquals(TopicAccessEndReason.SEALED, sealed.reason)
        assertTrue("the seal was not recorded after the loss", sealed.sequence > loss.sequence)
        assertNothingCameBack("sealed", axis, watch, before)
        h.assertCurrent("sealed")
    }

    // --- S4 U1a: the graph consumers on this real issuer, user axis -----------------------------------------------------

    /** One graph request the fake fetcher received, with the guard it was actually handed. */
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
                store ?: FileGraphV2DiskStore(folder.newFolder(), JsonGraphV2EnvelopeCodec(), DefaultGraphV2AtomicFileIo(), dispatcher),
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
        val lifetime = checkNotNull(g.uses.acquire(fence)) { "premise: a use under the issued fence" }
        g.coordinator.onActivated(KEY_3M); runCurrent()
        val catalogSent = g.sent.single { it.kind == "catalog" }
        assertTrue("premise: the catalog guard admits an approved use", catalogSent.guard())
        catalogSent.catalog.complete(ok(graphCatalog())); runCurrent()
        g.tabs(KEY_3M).single().tab.complete(ok(g.threeMonthTab())); runCurrent()
        g.catalog = checkNotNull(g.coordinator.state.value.catalog) { "premise: the catalog was adopted" }
        val kb = g.kb(fence)
        g.recorder.observe(g.quote(1390.0, fence, lifetime))
        assertTrue("premise: kb adopted with a demand", g.adopted(kb, 1390.0) && g.series().getValue(kb).pending.isNotEmpty())
        g.coordinator.onActivated(KEY_1D); runCurrent()
        val day = g.tabs(KEY_1D).single()
        assertEquals("premise: the 1d request captured kb's recovery", 1L, g.recorder.state.value.nextVersion)
        val capture = checkNotNull(g.access.bind(fence, lifetime)) { "premise: the gate binds" }
        return Approved(fence, lifetime, capture, kb, day)
    }

    /** Every graph consumer refuses [a]'s use; a refused price may still add a loss demand, which this does not check. */
    private fun assertClosed(label: String, g: GraphRig, a: Approved, rate: Double) {
        assertFalse("$label: the kept 1d guard", a.day.guard())
        assertNull("$label: the gate binds nothing", g.access.bind(a.fence, a.lifetime))
        assertFalse("$label: GENERAL read", g.access.ioAdmission(a.capture).admits(GraphV2DiskComponent.GENERAL))
        assertFalse("$label: KRX read", g.access.admits(a.capture, GraphV2DiskComponent.KRX))
        assertTrue("$label: nothing exposed", g.recorder.exposed(a.fence, a.lifetime).isEmpty())
        assertNull("$label: no protected entry", g.coordinator.protectedEntry(KEY_3M))
        g.recorder.observe(g.quote(rate, a.fence, a.lifetime))
        assertFalse("$label: the price is not adopted", g.adopted(a.kb, rate))
    }

    /** Sealed: an explicit user loss whose rotation keeps failing, then the session's withdrawal. */
    private suspend fun TestScope.sealed(): Triple<Harness, GraphRig, Approved> {
        val h = granted()
        val g = GraphRig(this, h)
        val a = approve(g)
        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertTrue("premise: sealed", TopicAccessBlock.EXPLICIT_SEAL in h.facts.userBlocks)
        g.withdraw(); runCurrent()
        return Triple(h, g, a)
    }

    /**
     * Held: a loss answer whose decision read failed, so a P4 candidate holds the user axis. The fence stays published and the
     * coordinator is not told: once told it drops its context and request, which alone would refuse the kept guard and the
     * protected entry. Unless [startRecorder], the recorder does not collect either.
     */
    private suspend fun TestScope.held(startRecorder: Boolean = true): Triple<Harness, GraphRig, Approved> {
        val h = granted()
        val g = GraphRig(this, h, startRecorder)
        val a = approve(g)
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.source.afterFetch = { h.store.loadFailures = 2 }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertTrue("premise: held", TopicAccessBlock.LOSS_CANDIDATE in h.facts.userBlocks)
        return Triple(h, g, a)
    }

    /**
     * graphI01: approved by the real issuer, every graph consumer is open - a price is adopted and exposed, the kept guard and
     * the gate's GENERAL and KRX reads are admitted, the protected entry carries its KRX half, and a 1d answer applies kb's
     * recovery.
     */
    @Test
    fun graphI01_anApprovedUseOpensEveryGraphConsumer() = snapshotTest {
        val g = GraphRig(this, granted())
        val a = approve(g)
        assertTrue("the kept 1d guard", a.day.guard())
        assertTrue("GENERAL read", g.access.ioAdmission(a.capture).admits(GraphV2DiskComponent.GENERAL))
        assertTrue("KRX read", g.access.admits(a.capture, GraphV2DiskComponent.KRX))
        assertTrue("kb exposed", a.kb in g.recorder.exposed(a.fence, a.lifetime))
        val entry = checkNotNull(g.coordinator.protectedEntry(KEY_3M)) { "no protected entry" }
        assertTrue("with its KRX half", entry.tab.graph.series.any { it.seriesId == KRX_SERIES })
        g.recorder.observe(g.quote(1391.0, a.fence, a.lifetime))
        assertTrue("a new price is adopted", g.adopted(a.kb, 1391.0))
        a.day.tab.complete(ok(g.dayTab())); runCurrent()
        assertEquals("kb's recovery is applied", 1L, g.series().getValue(a.kb).lastAppliedVersion)
        assertTrue(g.coordinator.state.value.entries.containsKey(KEY_1D))
    }

    /**
     * graphI02: an explicit user loss whose rotation keeps failing seals the user axis. Before the session withdraws anything
     * every graph consumer already refuses on the snapshot alone, the user end discards kb with its demand, the late 1d
     * answer applies nothing and a new activation sends nothing. Once the fence is withdrawn the coordinator's entries go.
     * A premium answer while sealed opens no use.
     */
    @Test
    fun graphI02_anExplicitUserSealClosesEveryGraphConsumer() = snapshotTest {
        val h = granted()
        val g = GraphRig(this, h)
        val a = approve(g)
        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertTrue("premise: sealed", TopicAccessBlock.EXPLICIT_SEAL in h.facts.userBlocks)
        assertFalse("the user end discarded kb before any input", a.kb in g.series())
        assertClosed("sealed, fence still published", g, a, 1392.0)
        a.day.tab.complete(ok(g.dayTab())); runCurrent()
        assertFalse("the late 1d answer applies no entry", g.coordinator.state.value.entries.containsKey(KEY_1D))
        assertFalse("nor brings kb back", a.kb in g.series())
        val sends = g.sent.size
        g.coordinator.onActivated(KEY_3M); runCurrent()
        assertEquals("a new activation sends nothing", sends, g.sent.size)
        g.withdraw(); runCurrent()
        assertTrue("the coordinator's entries went with the scope", g.coordinator.state.value.entries.isEmpty())
        h.source.next = { active(krx = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertTrue("still sealed", TopicAccessBlock.EXPLICIT_SEAL in h.facts.userBlocks)
        assertNull("a premium answer while sealed opens no use", g.uses.acquire(a.fence))
    }

    /**
     * graphI03: once the failing rotation lands the seal lifts, but nothing opens until a fresh premium answer: the recovery
     * really asks for one, and while that answer is out no use is admitted.
     */
    @Test
    fun graphI03_aLandedRecoveryAloneOpensNothing() = snapshotTest {
        val (h, g, a) = sealed()
        h.store.rotationFailures = 0
        h.source.next = { active(krx = true) }
        h.source.fetchGate = gate()
        val asked = h.source.freshRequests.size
        advanceTimeBy(RETRY * 2); runCurrent()
        assertNotEquals("premise: the rotation landed", a.fence.userAccessEpoch, h.store.record.userAccessEpoch)
        assertFalse("the seal lifted", TopicAccessBlock.EXPLICIT_SEAL in h.facts.userBlocks)
        assertTrue("a fresh premium answer was asked for", true in h.source.freshRequests.drop(asked))
        assertFalse(h.facts.userAllowed)
        assertNull("no use under the old fence", g.uses.acquire(a.fence))
        assertClosed("recovered, answer out", g, a, 1393.0)
    }

    /**
     * graphI04: the fresh premium answer re-approves. The new grant opens new use - a price is adopted under the new scope -
     * but nothing discarded comes back and nothing captured before the end applies: the old lifetime, guard and capture stay
     * refused and the late 1d answer applies nothing.
     */
    @Test
    fun graphI04_reapprovalOpensOnlyFreshUse() = snapshotTest {
        val (h, g, a) = sealed()
        h.store.rotationFailures = 0
        h.source.next = { active(krx = true) }
        val answer = gate()
        h.source.fetchGate = answer
        advanceTimeBy(RETRY * 2); runCurrent()
        answer.complete(Unit)
        settle()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        val renewed = g.publishIssued()
        runCurrent()
        assertNotEquals("a new grant", a.fence, renewed)
        val fresh = checkNotNull(g.uses.acquire(renewed)) { "a fresh use under the new grant" }
        g.coordinator.onActivated(KEY_3M); runCurrent()
        g.sent.last { it.kind == "catalog" }.catalog.complete(ok(graphCatalog())); runCurrent()
        g.catalog = checkNotNull(g.coordinator.state.value.catalog) { "premise: the new scope adopted a catalog" }
        val kbNew = g.kb(renewed)
        g.recorder.observe(g.quote(1394.0, renewed, fresh))
        assertTrue("a fresh price is adopted under the new scope", g.adopted(kbNew, 1394.0))
        assertFalse("the discarded series does not come back", a.kb in g.series())
        assertFalse("the old use stays refused", g.uses.admits(a.lifetime))
        assertFalse("the old guard stays refused", a.day.guard())
        assertFalse("the old capture reads nothing", g.access.ioAdmission(a.capture).admits(GraphV2DiskComponent.GENERAL))
        a.day.tab.complete(ok(g.dayTab())); runCurrent()
        assertFalse("the late 1d answer applied nothing", g.coordinator.state.value.entries.containsKey(KEY_1D))
        assertFalse("nor revived the discarded series", a.kb in g.series())
        g.recorder.observe(g.quote(1395.0, a.fence, a.lifetime))
        assertFalse("an old-scope price adopts nothing", g.adopted(a.kb, 1395.0))
    }

    /**
     * graphI05: a P4 hold closes every use but keeps what the scope holds - no user end, no rotation; kb and its demand stay,
     * a refused capture consumes no demand, the kept 1d answer applies neither an entry nor kb's recovery, and the
     * coordinator, told of the hold only after those checks, keeps its entry.
     * The token still stands, so only the user axis keeps a new use from starting.
     */
    @Test
    fun graphI05_aUserHoldClosesUseAndKeepsTheData() = snapshotTest {
        val h = granted()
        val g = GraphRig(this, h)
        val a = approve(g)
        val before = h.snapshot
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.source.afterFetch = { h.store.loadFailures = 2 }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertTrue("premise: held", TopicAccessBlock.LOSS_CANDIDATE in h.facts.userBlocks)
        assertEquals("no user end", before.lastUserEnd, h.snapshot.lastUserEnd)
        assertEquals("no rotation", a.fence.userAccessEpoch, h.store.record.userAccessEpoch)
        assertTrue("premise: the token still stands", h.facts.tokenStanding && h.facts.token == a.fence.grant)
        assertNull("no use starts while held", g.uses.acquire(a.fence))
        val demands = g.series().getValue(a.kb).pending.keys
        assertClosed("held", g, a, 1396.0)
        assertTrue("kb is kept", a.kb in g.series())
        assertTrue("its demand is kept", g.series().getValue(a.kb).pending.keys.containsAll(demands))
        assertTrue("a refused capture issues nothing", g.recorder.captureRequests(setOf(a.kb), a.fence, a.lifetime).isEmpty())
        assertTrue("and consumes no demand", g.series().getValue(a.kb).pending.keys.containsAll(demands))
        a.day.tab.complete(ok(g.dayTab())); runCurrent()
        assertFalse("the held 1d answer applies no entry", g.coordinator.state.value.entries.containsKey(KEY_1D))
        assertNull("nor kb's recovery", g.series().getValue(a.kb).lastAppliedVersion)
        g.coordinator.onContextChanged()
        runCurrent()
        assertTrue("the coordinator, told of the hold, keeps its entry", g.coordinator.state.value.entries.containsKey(KEY_3M))
    }

    /**
     * graphI06: a stale hold is released with no user end and the same token, yet the old lifetime, guard and capture stay
     * refused - a price, a recovery capture or the kept 1d answer under the old lifetime is neither adopted, issued nor
     * applied; a fresh lifetime under the
     * kept fence is admitted, kb keeps its demand and a fresh capture issues it, and once the context moves on the protected
     * entry is exposed again. The coordinator is told only after the release.
     */
    @Test
    fun graphI06_aStaleHoldReleasesOnlyFreshUse() = snapshotTest {
        val (h, g, a) = held()
        val before = h.snapshot
        h.store.loadFailures = 0
        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        advanceTimeBy(RETRY); runCurrent()
        assertEquals("premise: released", 0, h.coordinator.heldLossCandidateCount())
        assertEquals("no user end", before.lastUserEnd, h.snapshot.lastUserEnd)
        assertEquals("the same token", a.fence.grant, h.facts.token)
        assertFalse("the old lifetime stays refused", g.uses.admits(a.lifetime))
        assertFalse("the old guard stays refused", a.day.guard())
        assertFalse("the old capture reads nothing", g.access.admits(a.capture, GraphV2DiskComponent.GENERAL))
        assertTrue("kb keeps its demand through the release", g.series().getValue(a.kb).pending.isNotEmpty())
        g.recorder.observe(g.quote(1396.5, a.fence, a.lifetime))
        assertFalse("an old-lifetime price is not adopted", g.adopted(a.kb, 1396.5))
        val demands = g.series().getValue(a.kb).pending.keys
        assertTrue("an old-lifetime capture issues nothing", g.recorder.captureRequests(setOf(a.kb), a.fence, a.lifetime).isEmpty())
        assertEquals("and consumes no demand", demands, g.series().getValue(a.kb).pending.keys)
        a.day.tab.complete(ok(g.dayTab())); runCurrent()
        assertFalse("the old 1d answer applies no entry", g.coordinator.state.value.entries.containsKey(KEY_1D))
        assertNull("nor kb's recovery", g.series().getValue(a.kb).lastAppliedVersion)
        val fresh = checkNotNull(g.uses.acquire(a.fence)) { "a fresh use under the kept fence" }
        assertNotEquals(a.lifetime, fresh)
        assertNotNull(g.access.bind(a.fence, fresh))
        assertTrue("kb is usable again", a.kb in g.recorder.exposed(a.fence, fresh))
        assertTrue("which a fresh capture issues", g.recorder.captureRequests(setOf(a.kb), a.fence, fresh).isNotEmpty())
        g.recorder.observe(g.quote(1397.0, a.fence, fresh))
        assertTrue("a fresh price is adopted into the kept series", g.adopted(a.kb, 1397.0))
        g.coordinator.onContextChanged(); runCurrent()
        assertNotNull("the protected entry is exposed again", g.coordinator.protectedEntry(KEY_3M))
    }

    /**
     * graphI06b: a hold the graph never saw - its recorder not yet collecting and its coordinator never told - still leaves
     * the old lifetime, guard, capture and 1d answer refused once released; only a fresh lifetime is admitted.
     */
    @Test
    fun graphI06b_aHoldTheGraphNeverSawStillRefusesTheOldUse() = snapshotTest {
        val (h, g, a) = held(startRecorder = false)
        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        advanceTimeBy(RETRY); runCurrent()
        assertEquals("premise: released", 0, h.coordinator.heldLossCandidateCount())
        assertTrue(h.facts.userAllowed)
        assertFalse("the old guard", a.day.guard())
        assertNull("the old lifetime binds nothing", g.access.bind(a.fence, a.lifetime))
        assertFalse("the old capture reads no GENERAL", g.access.ioAdmission(a.capture).admits(GraphV2DiskComponent.GENERAL))
        assertFalse("nor KRX", g.access.admits(a.capture, GraphV2DiskComponent.KRX))
        assertTrue("the old lifetime is shown nothing", g.recorder.exposed(a.fence, a.lifetime).isEmpty())
        a.day.tab.complete(ok(g.dayTab())); runCurrent()
        assertFalse("the old 1d answer applies no entry", g.coordinator.state.value.entries.containsKey(KEY_1D))
        assertNull("nor kb's recovery", g.series().getValue(a.kb).lastAppliedVersion)
        val fresh = checkNotNull(g.uses.acquire(a.fence))
        assertTrue("a fresh lifetime is shown kb", a.kb in g.recorder.exposed(a.fence, fresh))
    }

    /**
     * graphI07: a hold confirmed as a real loss ends the user scope: every consumer refuses, kb is discarded and, with the fence
     * still published, the late 1d answer applies nothing.
     */
    @Test
    fun graphI07_aConfirmedHoldEndsTheUserScope() = snapshotTest {
        val (h, g, a) = held()
        val before = h.snapshot
        h.store.loadFailures = 0
        advanceTimeBy(RETRY); runCurrent()
        assertEquals("premise: decided", 0, h.coordinator.heldLossCandidateCount())
        assertNotEquals("a user end", before.lastUserEnd, h.snapshot.lastUserEnd)
        assertFalse("kb is discarded before any input", a.kb in g.series())
        assertClosed("decided", g, a, 1398.0)
        a.day.tab.complete(ok(g.dayTab())); runCurrent()
        assertFalse("the late 1d answer applied nothing", g.coordinator.state.value.entries.containsKey(KEY_1D))
    }

    /**
     * graphI08: a user loss whose write is in doubt closes every consumer while the state still reads PremiumConfirmed, its
     * user end discards kb and the kept 1d answer applies nothing; after the write the use stays closed until a fresh
     * approval. Nothing here waits on the
     * issuer's lock.
     */
    @Test
    fun graphI08_aUserLossInDoubtClosesEveryGraphConsumer() = snapshotTest {
        val h = granted()
        val g = GraphRig(this, h)
        val a = approve(g)
        val parked = gate()
        h.store.rotationGate = parked
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        val refreshing = launch { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        assertTrue("premise: the rotation is parked", h.store.rotationParked.isCompleted)
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertTrue(TopicAccessBlock.CONTEXT_UNCERTAIN in h.facts.userBlocks)
        assertFalse("the user end discarded kb before any input", a.kb in g.series())
        assertClosed("in doubt", g, a, 1399.0)
        a.day.tab.complete(ok(g.dayTab())); runCurrent()
        assertFalse("the 1d answer in doubt applies no entry", g.coordinator.state.value.entries.containsKey(KEY_1D))
        assertFalse("nor brings the discarded kb back", a.kb in g.series())
        parked.complete(Unit)
        settle()
        refreshing.join()
        assertNull("still closed until a fresh approval", g.uses.acquire(a.fence))
    }

    // --- S4 U1b: the same graph consumers, capability axis --------------------------------------------------------------

    /**
     * Only KRX refuses [a]'s use: the kept guard, the gate's binding and GENERAL read, exposure, a new use and a new price stay
     * open; the kept capture's KRX read is refused and the protected entry keeps its GENERAL half without its KRX half.
     */
    private fun assertOnlyKrxClosed(label: String, g: GraphRig, a: Approved, rate: Double) {
        assertTrue("$label: the kept 1d guard", a.day.guard())
        assertNotNull("$label: the gate binds", g.access.bind(a.fence, a.lifetime))
        assertTrue("$label: GENERAL read", g.access.ioAdmission(a.capture).admits(GraphV2DiskComponent.GENERAL))
        assertFalse("$label: KRX read", g.access.admits(a.capture, GraphV2DiskComponent.KRX))
        assertTrue("$label: kb exposed", a.kb in g.recorder.exposed(a.fence, a.lifetime))
        val entry = checkNotNull(g.coordinator.protectedEntry(KEY_3M)) { "$label: no protected entry" }
        assertTrue("$label: its GENERAL half", entry.tab.graph.series.any { it.seriesId == ONLINE })
        assertFalse("$label: without its KRX half", entry.tab.graph.series.any { it.seriesId == KRX_SERIES })
        assertNotNull("$label: a new use starts", g.uses.acquire(a.fence))
        g.recorder.observe(g.quote(rate, a.fence, a.lifetime))
        assertTrue("$label: the price is adopted", g.adopted(a.kb, rate))
    }

    /**
     * The common body of graphI09a-c, with the capability blocked and the coordinator not yet told: the user axis, the token
     * and the user end are untouched, only KRX is closed, kb keeps its demand and a 1d completion applies it. Told afterwards,
     * the coordinator strips the KRX half from its entry and keeps serving GENERAL.
     */
    private fun TestScope.assertCapabilityOnly(label: String, h: Harness, g: GraphRig, a: Approved, before: TopicAccessSnapshot, rate: Double) {
        assertTrue("$label: the user axis", h.facts.userAllowed)
        assertTrue("$label: the token still stands", h.facts.tokenStanding && h.facts.token == a.fence.grant)
        assertFalse(h.facts.capabilityAllowed)
        assertEquals("$label: no user end", before.lastUserEnd, h.snapshot.lastUserEnd)
        val demands = g.series().getValue(a.kb).pending.keys
        assertTrue("premise: kb has a demand", demands.isNotEmpty())
        assertOnlyKrxClosed(label, g, a, rate)
        assertTrue("$label: kb keeps its demand", g.series().getValue(a.kb).pending.keys.containsAll(demands))
        a.day.tab.complete(ok(g.dayTab())); runCurrent()
        assertTrue("$label: the 1d completion applies", g.coordinator.state.value.entries.containsKey(KEY_1D))
        assertEquals("$label: with kb's recovery", 1L, g.series().getValue(a.kb).lastAppliedVersion)
        g.coordinator.onContextChanged(); runCurrent()
        val kept = checkNotNull(g.coordinator.state.value.entries[KEY_3M]) { "$label: the coordinator dropped its entry" }
        assertTrue("$label: told, it keeps GENERAL", kept.tab.graph.series.any { it.seriesId == ONLINE })
        assertFalse("$label: and strips KRX", kept.tab.graph.series.any { it.seriesId == KRX_SERIES })
        val entry = checkNotNull(g.coordinator.protectedEntry(KEY_3M)) { "$label: told, no protected entry" }
        assertFalse("$label: told, the protected entry has no KRX half", entry.tab.graph.series.any { it.seriesId == KRX_SERIES })
        assertTrue("$label: kb is kept", a.kb in g.series())
    }

    /** graphI09a: a capability hold - a KRX loss whose decision read failed - closes KRX only. */
    @Test
    fun graphI09a_aCapabilityHoldClosesOnlyKrx() = snapshotTest {
        val h = granted()
        val g = GraphRig(this, h)
        val a = approve(g)
        val before = h.snapshot
        h.source.next = { active(krx = false) }
        h.source.afterFetch = { h.store.loadFailures = 2 }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()
        assertTrue("premise: a capability hold", TopicAccessBlock.LOSS_CANDIDATE in h.facts.capabilityBlocks)
        assertCapabilityOnly("capability hold", h, g, a, before, 1401.0)
    }

    /**
     * graphI09b: a KRX loss whose rotation write is in doubt closes KRX only, while the write is still parked. Nothing here
     * waits on the issuer's lock.
     */
    @Test
    fun graphI09b_aCapabilityWriteInDoubtClosesOnlyKrx() = snapshotTest {
        val h = granted()
        val g = GraphRig(this, h)
        val a = approve(g)
        val before = h.snapshot
        h.store.rotationGate = gate()
        h.source.next = { active(krx = false) }
        launch { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()
        assertTrue("premise: the rotation is parked", h.store.rotationParked.isCompleted)
        assertTrue("premise: the capability is in doubt", TopicAccessBlock.CONTEXT_UNCERTAIN in h.facts.capabilityBlocks)
        assertCapabilityOnly("capability in doubt", h, g, a, before, 1402.0)
    }

    /**
     * graphI09c: an explicit capability seal - a KRX loss whose rotation keeps failing - closes KRX only, and a KRX-visible
     * answer while sealed, fetched before the coordinator is told, does not open KRX again.
     */
    @Test
    fun graphI09c_anExplicitCapabilitySealClosesOnlyKrx() = snapshotTest {
        val h = granted()
        val g = GraphRig(this, h)
        val a = approve(g)
        val before = h.snapshot
        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()
        assertTrue("premise: sealed", TopicAccessBlock.EXPLICIT_SEAL in h.facts.capabilityBlocks)
        assertOnlyKrxClosed("capability sealed", g, a, 1403.0)
        h.source.next = { active(krx = true) }
        val asked = h.source.freshRequests.size
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()
        assertTrue("premise: the KRX-visible answer was fetched", h.source.freshRequests.size > asked)
        assertTrue("still sealed", TopicAccessBlock.EXPLICIT_SEAL in h.facts.capabilityBlocks)
        // Still untold, the coordinator's slot keeps its KRX half, so only the snapshot can withhold it here.
        assertCapabilityOnly("sealed, after a KRX-visible answer", h, g, a, before, 1403.5)
        val fresh = checkNotNull(g.uses.acquire(a.fence)) { "GENERAL still starts a use" }
        assertNull("nor binds a KRX epoch", checkNotNull(g.access.bind(a.fence, fresh)).krxCapabilityEpoch)
    }

    /** A capability hold - a KRX loss whose decision read failed - with the coordinator not told. */
    private suspend fun TestScope.capabilityHeld(): Triple<Harness, GraphRig, Approved> {
        val h = granted()
        val g = GraphRig(this, h)
        val a = approve(g)
        h.source.next = { active(krx = false) }
        h.source.afterFetch = { h.store.loadFailures = 2 }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()
        assertTrue("premise: a capability hold", TopicAccessBlock.LOSS_CANDIDATE in h.facts.capabilityBlocks)
        return Triple(h, g, a)
    }

    /** Releases a held capability as stale: the transport identity moved before the next recovery read. */
    private suspend fun TestScope.releaseStale(h: Harness) {
        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        advanceTimeBy(RETRY); runCurrent()
        assertEquals("premise: released", 0, h.coordinator.heldLossCandidateCount())
        assertTrue("premise: the capability is allowed again", h.facts.capabilityAllowed)
    }

    private fun showsKrx(entry: GraphEntry?) = checkNotNull(entry).tab.graph.series.any { it.seriesId == KRX_SERIES }

    /**
     * graphI09d: a capability hold the coordinator was never told of, once released as stale, re-exposes the KRX half its
     * slot still holds: the kept capture reads KRX again under the same epoch, and the user lifetime was never invalidated.
     */
    @Test
    fun graphI09d_anUntoldCapabilityHoldReleasedReexposesTheKeptKrxHalf() = snapshotTest {
        val (h, g, a) = capabilityHeld()
        assertOnlyKrxClosed("held", g, a, 1406.0)
        releaseStale(h)
        assertTrue("the user lifetime was never invalidated", g.uses.admits(a.lifetime))
        assertTrue("the kept capture reads KRX again", g.access.admits(a.capture, GraphV2DiskComponent.KRX))
        assertEquals(
            "under the same epoch",
            a.capture.krxCapabilityEpoch,
            checkNotNull(g.access.bind(a.fence, a.lifetime)).krxCapabilityEpoch
        )
        assertTrue("the protected entry shows its kept KRX half again", showsKrx(g.coordinator.protectedEntry(KEY_3M)))
    }

    /**
     * graphI09e: a capability hold the coordinator was told of strips the KRX half from its entry, and the release does not
     * bring it back from memory: the kept capture reads KRX again under the same epoch, but the protected entry shows KRX
     * only once a fresh protected fetch of the tab is answered. The rig has no disk write port, so no KRX file exists for a
     * supplement seed to restore it from; that path is not measured here.
     */
    @Test
    fun graphI09e_aToldCapabilityHoldReleasedRestoresKrxOnlyByAFreshFetch() = snapshotTest {
        val (h, g, a) = capabilityHeld()
        g.coordinator.onContextChanged(); runCurrent()
        assertFalse("premise: told, the entry lost its KRX half", showsKrx(g.coordinator.state.value.entries[KEY_3M]))
        releaseStale(h)
        assertTrue("the kept capture reads KRX again", g.access.admits(a.capture, GraphV2DiskComponent.KRX))
        assertEquals(
            "under the same epoch",
            a.capture.krxCapabilityEpoch,
            checkNotNull(g.access.bind(a.fence, a.lifetime)).krxCapabilityEpoch
        )
        g.coordinator.onContextChanged(); runCurrent()
        assertFalse("memory does not bring the KRX half back", showsKrx(g.coordinator.protectedEntry(KEY_3M)))
        val sentBefore = g.sent.size
        g.coordinator.onActivated(KEY_3M); runCurrent()
        val tab = g.sent.drop(sentBefore).single { it.kind == "tab" && it.key == KEY_3M }
        assertTrue("the fresh fetch is admitted", tab.guard())
        tab.tab.complete(ok(g.threeMonthTab())); runCurrent()
        assertTrue("a fresh protected fetch restores the KRX half", showsKrx(g.coordinator.protectedEntry(KEY_3M)))
    }

    /**
     * The same user signs in again under a new auth generation: the issuer records an IDENTITY_CHANGED user end and keeps
     * the namespace, and a fresh premium answer grants again. Returns the approved state from before the change.
     */
    private suspend fun TestScope.sameUserNewGeneration(): Triple<Harness, GraphRig, Approved> {
        val h = granted()
        val g = GraphRig(this, h)
        val a = approve(g)
        val before = h.snapshot
        val epoch = h.store.record.userAccessEpoch
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 2L))
        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        val end = checkNotNull(h.snapshot.lastUserEnd) { "premise: a user end" }
        assertEquals("premise: an identity change", TopicAccessEndReason.IDENTITY_CHANGED, end.reason)
        assertTrue("premise: a new user end", end.sequence > (before.lastUserEnd?.sequence ?: 0L))
        assertEquals("premise: the namespace is kept", epoch, h.store.record.userAccessEpoch)
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        g.live = AuthIdentityFence(OWNER, 2L)
        return Triple(h, g, a)
    }

    /** The coordinator after the new grant: nothing it held before the user end is kept or applied. */
    private fun TestScope.assertRetiredByTheUserEnd(label: String, g: GraphRig, a: Approved, renewed: TopicSessionFence) {
        assertEquals("$label: the same data scope", a.kb, g.kb(renewed))
        assertFalse("$label: no 3m entry is kept", g.coordinator.state.value.entries.containsKey(KEY_3M))
        assertNull("$label: no protected entry", g.coordinator.protectedEntry(KEY_3M))
        assertFalse("$label: kb is discarded", a.kb in g.series())
        a.day.tab.complete(ok(g.dayTab())); runCurrent()
        assertFalse("$label: the old 1d answer applies nothing", g.coordinator.state.value.entries.containsKey(KEY_1D))
    }

    /**
     * graphA05 (S4 RT01-A1): a same-user IDENTITY_CHANGED end retires the coordinator's memory of that data scope alike
     * whether the end reaches the graph on its own - the fence withdrawn, then the new grant published - or together with
     * the new grant, as the deliverer sends an end and a grant it reads in one pull. The recorder already goes by the user
     * end; the coordinator must too, since the data scope does not change.
     */
    @Test
    fun graphA05_aSameUserIdentityChangeRetiresTheScopeAlikeWhetherOrNotTheEndArrivesAlone() = snapshotTest {
        run {
            val (_, g, a) = sameUserNewGeneration()
            g.withdraw(); runCurrent()
            val renewed = g.publishIssued(); runCurrent()
            assertRetiredByTheUserEnd("end alone", g, a, renewed)
        }
        run {
            val (_, g, a) = sameUserNewGeneration()
            val renewed = g.publishIssued(); runCurrent()
            assertRetiredByTheUserEnd("end with the new grant", g, a, renewed)
        }
    }

    /** An approved graph whose coordinator and gate share the graph admission over [deletions] (S4 RT01-A2). */
    private suspend fun TestScope.approvedWithDeletions(deletions: DeletionAdmissionStore): Triple<Harness, GraphRig, Approved> {
        val h = granted()
        lateinit var rig: GraphRig
        val admission = GraphProtectedAdmission({ rig.live }, deletions)
        rig = GraphRig(this, h, admission = admission)
        return Triple(h, rig, approve(rig))
    }

    /**
     * graphA07 (S4 RT01-A2): a deletion about to leave for the server closes every graph consumer, GENERAL and KRX, for that
     * user at any auth generation, and stays closed once the server confirms it. Another user's deletion closes nothing here.
     * The issuer's snapshot is untouched: the closure is the shared graph admission's alone.
     */
    @Test
    fun graphA07_aPendingDeletionClosesEveryGraphConsumer() = snapshotTest {
        val deletions = DeletionAdmissionStore()
        val (h, g, a) = approvedWithDeletions(deletions)
        deletions.begin(AuthIdentityFence("user-b", 1L), "op-b")
        assertTrue("another user's deletion closes nothing", a.day.guard())
        assertNotNull(g.access.bind(a.fence, a.lifetime))
        deletions.begin(AuthIdentityFence(OWNER, 2L), "op-a")
        assertTrue("premise: the issuer still allows the user", h.facts.userAllowed && h.facts.tokenStanding)
        assertTrue("premise: the use is still the issuer's", g.uses.admits(a.lifetime))
        assertClosed("deletion requested", g, a, 1420.0)
        a.day.tab.complete(ok(g.dayTab())); runCurrent()
        assertFalse("the 1d answer applies no entry", g.coordinator.state.value.entries.containsKey(KEY_1D))
        assertNull("nor kb's recovery", g.series().getValue(a.kb).lastAppliedVersion)
        deletions.serverDeleted("op-a")
        assertClosed("deletion confirmed", g, a, 1421.0)
    }

    /**
     * graphA07b (S4 RT01-A2): a deletion request proven not to have left is released, and the graph opens again for the
     * same use, which the issuer never withdrew.
     */
    @Test
    fun graphA07b_aProvenUnsentDeletionReopensTheGraph() = snapshotTest {
        val deletions = DeletionAdmissionStore()
        val (_, g, a) = approvedWithDeletions(deletions)
        deletions.begin(g.live, "op-a")
        assertFalse("premise: closed", a.day.guard())
        deletions.releaseUnsent("op-a")
        assertTrue("the kept guard admits again", a.day.guard())
        assertNotNull("the gate binds again", g.access.bind(a.fence, a.lifetime))
        g.recorder.observe(g.quote(1422.0, a.fence, a.lifetime))
        assertTrue("a price is adopted again", g.adopted(a.kb, 1422.0))
    }

    /**
     * graphA08 (S4 RT01-A2): while the graph is closed by a pending deletion, the issuer's own control queries go on - a
     * refresh really asks for an answer - because the graph admission gates only graph use.
     */
    @Test
    fun graphA08_aClosedGraphDoesNotStopEntitlementControlQueries() = snapshotTest {
        val deletions = DeletionAdmissionStore()
        val (h, g, a) = approvedWithDeletions(deletions)
        deletions.begin(g.live, "op-a")
        assertFalse("premise: the graph is closed", a.day.guard())
        val asked = h.source.freshRequests.size
        h.source.next = { active(krx = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertTrue("the refresh really asked", h.source.freshRequests.size > asked)
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertFalse("and the graph stays closed", a.day.guard())
    }

    /**
     * graphI10: once the parked capability rotation lands, the old token no longer stands: the old grant starts no new use
     * and the old lifetime, guard, capture and 1d completion are refused before the session publishes anything, with the
     * user epoch and user end unchanged and kb kept with its demand. The reissued grant opens GENERAL only - a fresh use is shown the kept kb and adopts a price,
     * its own 1d request is admitted and its completion applies, and kb keeps its demand - while KRX stays refused.
     */
    @Test
    fun graphI10_aLandedCapabilityRotationReopensGeneralOnly() = snapshotTest {
        val h = granted()
        val g = GraphRig(this, h)
        val a = approve(g)
        val before = h.snapshot
        val parked = gate()
        h.store.rotationGate = parked
        h.source.next = { active(krx = false) }
        val refreshing = launch { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()
        assertTrue("premise: the rotation is parked", h.store.rotationParked.isCompleted)
        parked.complete(Unit)
        settle()
        refreshing.join()
        assertNotNull("premise: approved with a KRX epoch", a.capture.krxCapabilityEpoch)
        assertNotEquals("premise: the capability epoch moved", a.capture.krxCapabilityEpoch, h.store.record.krxCapabilityEpoch)
        assertEquals("the user epoch stays", a.fence.userAccessEpoch, h.store.record.userAccessEpoch)
        assertEquals("no user end", before.lastUserEnd, h.snapshot.lastUserEnd)
        assertFalse("premise: the old token no longer stands", h.facts.tokenStanding)
        val demands = g.series().getValue(a.kb).pending.keys
        assertTrue("premise: kb has a demand", demands.isNotEmpty())

        assertNull("the old grant starts no new use", g.uses.acquire(a.fence))
        assertFalse("the old lifetime", g.uses.admits(a.lifetime))
        assertFalse("the kept guard", a.day.guard())
        assertNull("the gate binds nothing for it", g.access.bind(a.fence, a.lifetime))
        assertFalse("the old capture reads nothing", g.access.ioAdmission(a.capture).admits(GraphV2DiskComponent.GENERAL))
        assertTrue("nothing exposed to it", g.recorder.exposed(a.fence, a.lifetime).isEmpty())
        g.recorder.observe(g.quote(1404.0, a.fence, a.lifetime))
        assertFalse("its price is not adopted", g.adopted(a.kb, 1404.0))
        a.day.tab.complete(ok(g.dayTab())); runCurrent()
        assertFalse("the old 1d completion applies no entry", g.coordinator.state.value.entries.containsKey(KEY_1D))
        assertNull("nor kb's recovery", g.series().getValue(a.kb).lastAppliedVersion)
        assertTrue("kb is kept", a.kb in g.series())
        assertTrue("with its demand", g.series().getValue(a.kb).pending.keys.containsAll(demands))

        val sentBefore = g.sent.size
        val renewed = g.publishIssued()
        runCurrent()
        assertNotEquals("a new grant", a.fence.grant, renewed.grant)
        assertEquals("in the same user scope", a.kb, g.kb(renewed))
        val fresh = checkNotNull(g.uses.acquire(renewed)) { "a fresh use under the new grant" }
        val capture = checkNotNull(g.access.bind(renewed, fresh)) { "the gate binds the fresh use" }
        assertTrue("GENERAL read", g.access.ioAdmission(capture).admits(GraphV2DiskComponent.GENERAL))
        assertFalse("KRX stays refused", g.access.admits(capture, GraphV2DiskComponent.KRX))
        assertTrue("the kept kb is shown to the fresh use", a.kb in g.recorder.exposed(renewed, fresh))
        g.recorder.observe(g.quote(1405.0, renewed, fresh))
        assertTrue("a fresh price is adopted into it", g.adopted(a.kb, 1405.0))
        val sent = g.sent.drop(sentBefore)
        sent.single { it.kind == "catalog" }.catalog.complete(ok(graphCatalog())); runCurrent()
        val day = sent.single { it.kind == "tab" && it.key == KEY_1D }
        assertTrue("the new 1d guard", day.guard())
        day.tab.complete(ok(g.dayTab())); runCurrent()
        assertTrue("the new 1d completion applies", g.coordinator.state.value.entries.containsKey(KEY_1D))
        // Measured here: kb keeps its demand through this completion. Read from code, not asserted: the context change dropped
        // the coordinator's catalog (synchronizeContext), and a request registered without one captures no recovery
        // (captureRecovery). Picking the kept demand up in a later request is RT03b/RT05's.
        assertTrue("kb keeps its demand", g.series().getValue(a.kb).pending.keys.containsAll(demands))
        val entry = checkNotNull(g.coordinator.protectedEntry(KEY_3M)) { "no protected entry under the new grant" }
        assertTrue("its GENERAL half", entry.tab.graph.series.any { it.seriesId == ONLINE })
    }

    // --- S4 U1d: the screen holder on this real issuer ------------------------------------------------------------------

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

    /**
     * A usd screen holder over [g]: the same coordinator, recorder, gate, use authority, fence supplier, executor and the issuer's
     * own access revisions. The display and focus are fixed to the owner; the holder drives its own activation.
     */
    private inner class HolderRig(test: TestScope, val g: GraphRig) {
        val owner = TopicDisplayOwner(g.live, 1L)
        val display = MutableStateFlow(TopicDisplayState.NONE.copy(owner = owner))
        val focus = MutableStateFlow<OwnedTopicFocus?>(OwnedTopicFocus(g.live, FreeTab.USD))
        val holder = GraphV2ScreenStateHolder(
            tab = "usd",
            coordinator = g.coordinator,
            selectionSession = GraphSeriesSelectionSession(
                HolderSelections(), GraphSelectionAudience.PREMIUM, "usd", g.scope, g.dispatcher
            ),
            liveIdentity = { g.live },
            display = display,
            focus = focus,
            currentAccessFence = { g.fence },
            uses = g.uses,
            gate = g.access,
            accessRevisions = g.h.coordinator.accessRevisions,
            scope = g.scope,
            dispatcher = g.dispatcher,
            clock = g.clock,
            recorder = g.recorder
        )
        val published = mutableListOf<GraphV2ScreenState>()

        init {
            holder.start()
            test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) { holder.state.collect { published += it } }
        }

        fun chartShown(): Boolean = holder.currentState().chart != null
        fun chartsPublishedSince(mark: Int) = published.drop(mark).count { it.chart != null }
    }

    /** What a ready holder holds before a row blocks it. */
    private class Shown(val fence: TopicSessionFence, val lifetime: TopicUseLifetime)

    /**
     * The holder made ready on the issued grant: it activated 1d, the catalog and a kb 1d answer arrived, the chart is drawn
     * and shown, and a live kb price has just scheduled a 350 ms publication that has not yet run.
     */
    private suspend fun TestScope.showHolder(hr: HolderRig): Shown {
        val g = hr.g
        val fence = g.publishIssued()
        runCurrent()
        hr.holder.onActivated(hr.owner); runCurrent()
        g.sent.single { it.kind == "catalog" }.catalog.complete(ok(graphCatalog())); runCurrent()
        g.tabs(KEY_1D).single().tab.complete(ok(g.drawableDayTab())); runCurrent()
        g.catalog = checkNotNull(g.coordinator.state.value.catalog) { "premise: the catalog was adopted" }
        val state = hr.holder.currentState()
        assertEquals("premise: the chart is ready", GraphV2Content.READY, state.content)
        hr.holder.setSurfaceVisible(checkNotNull(state.inlineToken) { "premise: an inline token" }, true); runCurrent()
        val lifetime = checkNotNull(g.uses.acquire(fence)) { "premise: a use under the issued fence" }
        g.recorder.observe(g.quote(1410.0, fence, lifetime))
        assertTrue("premise: kb adopted the live price", g.adopted(g.kb(fence), 1410.0))
        runCurrent()
        return Shown(fence, lifetime)
    }

    /**
     * After a block: the holder draws no chart now and reports BLOCKED with no token, and nothing drawable is published once
     * the publication scheduled before the block was due.
     */
    private fun TestScope.assertHolderClosed(label: String, hr: HolderRig, mark: Int) {
        assertFalse("$label: no chart is drawn", hr.chartShown())
        val blocked = hr.holder.currentState()
        assertEquals("$label: the holder itself blocks", GraphV2Content.BLOCKED, blocked.content)
        assertNull("$label: no token under the refused use", blocked.inlineToken)
        advanceTimeBy(400); runCurrent()
        assertFalse("$label: still none once the scheduled publication was due", hr.chartShown())
        assertEquals("$label: nothing drawable was published", 0, hr.chartsPublishedSince(mark))
    }

    /** graphH0, the window the rows below close: left open, the scheduled publication does draw the live price. */
    @Test
    fun graphH0_anOpenHolderPublishesItsScheduledLivePrice() = snapshotTest {
        val hr = HolderRig(this, GraphRig(this, granted()))
        showHolder(hr)
        val mark = hr.published.size
        advanceTimeBy(400); runCurrent()
        assertTrue("the scheduled publication drew the live price", hr.published.drop(mark).any { s ->
            s.chart?.prepared?.bySeries?.get(KB)?.linePoints?.any { it.rate == 1410.0 } == true
        })
        assertTrue(hr.chartShown())
    }

    /** graphH1: under an explicit user seal the holder draws nothing, now or from the publication it had scheduled. */
    @Test
    fun graphH1_aSealedUserAxisRendersNothing() = snapshotTest {
        val h = granted()
        val hr = HolderRig(this, GraphRig(this, h))
        showHolder(hr)
        val mark = hr.published.size
        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertTrue("premise: sealed", TopicAccessBlock.EXPLICIT_SEAL in h.facts.userBlocks)
        assertHolderClosed("sealed", hr, mark)
    }

    /**
     * graphH2: under a P4 hold the holder draws nothing; once the hold is released as stale it draws again under a fresh
     * lifetime - the use acquired before the hold stays refused - and the live publication scheduled before the hold is not
     * revived.
     */
    @Test
    fun graphH2_aHeldUserAxisRendersNothingAndResumesOnlyFresh() = snapshotTest {
        val h = granted()
        val hr = HolderRig(this, GraphRig(this, h))
        val shown = showHolder(hr)
        val mark = hr.published.size
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.source.afterFetch = { h.store.loadFailures = 2 }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertTrue("premise: held", TopicAccessBlock.LOSS_CANDIDATE in h.facts.userBlocks)
        assertHolderClosed("held", hr, mark)
        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        advanceTimeBy(RETRY); runCurrent()
        assertEquals("premise: released", 0, h.coordinator.heldLossCandidateCount())
        assertTrue("premise: released as stale, the user axis allowed", h.facts.userAllowed)
        assertFalse("the use from before the hold stays refused", hr.g.uses.admits(shown.lifetime))
        hr.g.sent.single { it.kind == "catalog" && !it.catalog.isCompleted }.catalog.complete(ok(graphCatalog()))
        runCurrent()
        val again = hr.holder.currentState()
        assertNotNull("the holder draws again", again.chart)
        val resumed = checkNotNull(again.inlineToken) { "premise: a token after the release" }
        assertNotEquals("under a fresh lifetime, not the one from before the hold", shown.lifetime, resumed.lifetime)
        assertTrue("the fresh lifetime is admitted", hr.g.uses.admits(resumed.lifetime))
        assertNull("no live publication is shown, so none from before the hold is revived", again.chart?.rightEdgeNow)
    }

    /** graphH3: while a user loss's write is in doubt the holder draws nothing, and it stays closed once the write lands. */
    @Test
    fun graphH3_aUserLossInDoubtRendersNothing() = snapshotTest {
        val h = granted()
        val hr = HolderRig(this, GraphRig(this, h))
        showHolder(hr)
        val mark = hr.published.size
        val parked = gate()
        h.store.rotationGate = parked
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        val refreshing = launch { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        assertTrue("premise: the rotation is parked", h.store.rotationParked.isCompleted)
        assertTrue(TopicAccessBlock.CONTEXT_UNCERTAIN in h.facts.userBlocks)
        assertHolderClosed("in doubt", hr, mark)
        parked.complete(Unit)
        settle()
        refreshing.join()
        assertFalse("still closed after the write", hr.chartShown())
    }

    // --- S4 RT01-A3: the unwired runtime assembly ----------------------------------------------------------------------

    /**
     * Runs every dispatched block through [queue], the test scheduler, and marks it while it runs, so a supplier can tell
     * whether it was read on this dispatcher. It always dispatches, as Main without immediate does. Delays are the scheduler's.
     */
    @OptIn(InternalCoroutinesApi::class)
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
        cachePorts: ((GraphV2AccessGate) -> GraphV2CachePorts?)? = null
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
            val real = FileGraphV2DiskStore(folder.newFolder(), JsonGraphV2EnvelopeCodec(), DefaultGraphV2AtomicFileIo(), queue)
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
            onEventFailure = { failures += it }
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

    /** The issuer grants a new generation of the same user, recording a user end; returns the grant it now issues. */
    private suspend fun TestScope.renewGeneration(h: Harness): TopicSessionFence {
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 2L))
        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        assertNotNull("premise: a user end", h.snapshot.lastUserEnd)
        return checkNotNull(h.coordinator.topicGrantResult().fence) { "premise: a new grant" }
    }

    /** A 429 with no Retry-After, as the authenticated transport hands it over. */
    private fun <T> rateLimited(): AuthenticatedHttpResponse<T> {
        val headers = Headers.headersOf()
        return AuthenticatedHttpResponse(
            429, headers, null,
            AuthenticatedHttpFailure(429, headers, byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP),
            byteArrayOf(1)
        )
    }

    /**
     * graphA01a (S4 RT01-A3): an assembly runs nothing until start() - no send, no coordinator context, no recorder read
     * from a worker or a collector, no disk read - while its sink and bridge already take what they are handed. start() runs
     * the coordinator, the recorder's collector and the sink's worker: the kept input reaches the recorder, and the cache
     * ports are built from the assembly's own gate. A second start() changes nothing.
     */
    @Test
    fun graphA01a_theAssemblyRunsNothingUntilStart() = snapshotTest {
        val r = AssemblyRig(this, granted())
        val issued = r.deliverIssued()
        val lifetime = checkNotNull(r.uses.acquire(issued))
        assertEquals(TopicGraphOffer.ENQUEUED, r.a.sink.tryOffer(r.quote(1390.0, issued, lifetime)))
        r.a.coordinator.onActivated(KEY_3M)
        runCurrent()
        assertTrue("no send before start", r.sent.isEmpty())
        assertNull("the coordinator has not run", r.a.coordinator.state.value.dataScope)
        assertTrue("nothing read the snapshot: no worker, no collector", r.snapshotReads.isEmpty())
        assertEquals("no disk read", 0, r.diskReads)
        assertEquals("nothing reached the recorder", 0, r.held())

        r.a.start(); runCurrent()
        assertEquals("the coordinator runs: the catalog and the tab are asked for", setOf("catalog", "tab"), r.sent.map { it.kind }.toSet())
        val sends = r.sent.size
        assertEquals(GraphDataScope(OWNER, checkNotNull(issued.userAccessEpoch)), r.a.coordinator.state.value.dataScope)
        assertEquals("the input kept before start reaches the recorder", 1, r.held())
        assertSame("the cache ports are built from the assembly's own gate", r.a.gate, r.portsGate)
        assertTrue("and are the coordinator's", r.diskReads > 0)

        r.a.start(); runCurrent()
        assertEquals("a second start sends nothing more", sends, r.sent.size)
        assertEquals(1, r.held())
        assertTrue(r.failures.isEmpty())
    }

    /**
     * graphA01b (S4 RT01-A3): close() on main ends the assembly the same way before start, after start but before the first
     * dispatch (start and close in one main frame), and with a request out and an input held by the recorder. When it
     * returns - also a second close called while the first is still waiting - the assembly's job, which was under the parent,
     * is gone while the parent and its other children live on; the request out is refused and nothing is in flight; and the
     * coordinator had published its release before the cancellation reached the send. Neither close nor anything after it
     * reads the snapshot. After it, start() runs nothing, the sink refuses input, a late answer adopts no catalog, the
     * recorder holds nothing and the bridge publishes no handed-over fence.
     */
    @Test
    fun graphA01b_closeEndsTheAssemblyAtEveryStage() = snapshotTest {
        for (stage in listOf("before start", "started, before the first dispatch", "running, with a request out")) {
            val r = AssemblyRig(this, granted())
            val sibling = Job(r.parent)
            var out: Sent? = null
            lateinit var issued: TopicSessionFence
            lateinit var lifetime: TopicUseLifetime
            if (stage == "running, with a request out") {
                r.a.start()
                issued = r.deliverIssued(); runCurrent()
                lifetime = checkNotNull(r.uses.acquire(issued))
                r.a.coordinator.onActivated(KEY_3M); runCurrent()
                out = r.sent.single { it.kind == "catalog" }
                assertTrue("$stage: premise, the send is admitted", out.guard())
                assertEquals(TopicGraphOffer.ENQUEUED, r.a.sink.tryOffer(r.quote(1390.0, issued, lifetime))); runCurrent()
                assertEquals("$stage: premise, the recorder holds the input", 1, r.held())
            }
            var reads = 0
            var sends = 0
            var atFirstReturn: List<Job>? = null
            var atSecondReturn: List<Job>? = null
            withContext(r.main) {
                if (out == null) {
                    if (stage == "started, before the first dispatch") r.a.start()
                    issued = r.deliverIssued()
                    lifetime = checkNotNull(r.uses.acquire(issued))
                    r.a.coordinator.onActivated(KEY_3M)
                    assertEquals(TopicGraphOffer.ENQUEUED, r.a.sink.tryOffer(r.quote(1390.0, issued, lifetime)))
                    assertTrue("$stage: premise, nothing has run yet", r.snapshotReads.isEmpty() && r.sent.isEmpty())
                }
                assertEquals("$stage: premise, the assembly's job is under the parent", 2, r.parent.children.count())
                reads = r.snapshotReads.size
                sends = r.sent.size
                val first = launch(start = CoroutineStart.UNDISPATCHED) {
                    r.a.close()
                    atFirstReturn = r.parent.children.toList()
                }
                r.a.close()
                atSecondReturn = r.parent.children.toList()
                first.join()
            }
            assertEquals("$stage: the assembly's job is gone when the first close returns", listOf(sibling), atFirstReturn)
            assertEquals("$stage: and when a second close returns", listOf(sibling), atSecondReturn)
            assertTrue("$stage: the parent and its other child live on", r.parent.isActive && sibling.isActive)
            out?.let { assertFalse("$stage: the request out is refused", it.guard()) }
            assertTrue("$stage: nothing in flight", r.a.coordinator.state.value.inFlight.isEmpty())
            if (out != null) {
                assertEquals(
                    "$stage: the coordinator was released before the cancellation reached its send",
                    listOf(emptySet<GraphKey>()), r.inFlightAtCancel
                )
            }

            r.a.start()
            out?.catalog?.complete(ok(graphCatalog()))
            r.a.coordinator.onActivated(KEY_1D)
            val later = issued.copy(userAccessEpoch = "handed over after close")
            r.a.fences.setAccess(true, later, TopicGrantOrigin.NewContext)
            assertEquals("$stage: no input is taken", TopicGraphOffer.CLOSED, r.a.sink.tryOffer(r.quote(1391.0, issued, lifetime)))
            runCurrent()
            assertEquals("$stage: nothing more is sent", sends, r.sent.size)
            assertEquals("$stage: neither close nor anything after it reads the snapshot", reads, r.snapshotReads.size)
            assertNull("$stage: no catalog is adopted", r.a.coordinator.state.value.catalog)
            assertEquals("$stage: the recorder holds nothing", 0, r.held())
            assertNotSame("$stage: the bridge publishes nothing after close", later, r.a.fences.current())
            assertTrue("$stage: no failure", r.failures.isEmpty())
            sibling.cancel()
        }
    }

    /** graphA01c (S4 RT01-A3): the parent's cancellation reaches the assembly - the request out is refused, the sink closed. */
    @Test
    fun graphA01c_theParentsCancellationEndsTheAssembly() = snapshotTest {
        val r = AssemblyRig(this, granted())
        r.a.start()
        val issued = r.deliverIssued(); runCurrent()
        val lifetime = checkNotNull(r.uses.acquire(issued))
        r.a.coordinator.onActivated(KEY_3M); runCurrent()
        val out = r.sent.single { it.kind == "catalog" }
        assertTrue("premise: the send is admitted", out.guard())
        r.parent.cancel(); runCurrent()
        assertFalse("the request out is refused", out.guard())
        assertEquals("the sink is closed", TopicGraphOffer.CLOSED, r.a.sink.tryOffer(r.quote(1390.0, issued, lifetime)))
        withContext(r.main) { r.a.close() }
        assertTrue(r.failures.isEmpty())
    }

    /**
     * graphA01d (S4 RT01-A3): a construction that fails rethrows its failure and leaves nothing of the assembly under the
     * parent, checked at once: nothing of it had started.
     */
    @Test
    fun graphA01d_aFailedConstructionLeavesNothingRunning() = snapshotTest {
        val h = granted()
        val parent = Job(processJob)
        val boom = IllegalStateException("cache ports")
        val thrown = assertThrows(IllegalStateException::class.java) {
            AssemblyRig(this, h, parent = parent, cachePorts = { throw boom })
        }
        assertSame(boom, thrown)
        assertTrue("nothing of the assembly is left under the parent", parent.children.none())
        assertTrue("the parent lives on", parent.isActive)
    }

    /**
     * graphA01e (S4 RT01-A3): the parts read what the assembly is given. The send guard and the gate read the injected
     * protected admission; the gate reads the injected live identity, not the owner source; a bridge call tells the
     * coordinator of a context change, not a refresh, so a revision with an unchanged context asks for nothing; and the
     * coordinator uses the injected jitter and reports an event failure to the injected sink.
     */
    @Test
    fun graphA01e_thePartsReadWhatTheAssemblyIsGiven() = snapshotTest {
        run {
            val r = AssemblyRig(this, granted(), disk = false)
            r.a.start()
            val issued = r.deliverIssued(); runCurrent()
            val lifetime = checkNotNull(r.uses.acquire(issued))
            r.a.coordinator.onActivated(KEY_3M); runCurrent()
            val out = r.sent.single { it.kind == "catalog" }
            assertTrue("premise: the send is admitted", out.guard())
            assertNotNull("premise: the gate binds", r.a.gate.bind(issued, lifetime))
            r.admitted = false
            assertFalse("the send guard reads the injected admission", out.guard())
            assertNull("the gate reads the injected admission", r.a.gate.bind(issued, lifetime))
            r.admitted = true
            r.ownerOverride = r.live
            r.live = AuthIdentityFence(OWNER, 2L)
            assertNull("the gate reads the injected live identity, not the owner source", r.a.gate.bind(issued, lifetime))
            r.live = issued.identity
            r.ownerOverride = null

            out.catalog.completeExceptionally(IOException("catalog down")); runCurrent()
            val asked = r.sent.count { it.kind == "catalog" }
            r.a.fences.accessRevised(); runCurrent()
            assertEquals("a revision with an unchanged context asks for nothing", asked, r.sent.count { it.kind == "catalog" })
            assertTrue(r.failures.isEmpty())
            withContext(r.main) { r.a.close() }
        }
        run {
            val boom = IllegalStateException("jitter")
            val r = AssemblyRig(this, granted(), disk = false, jitter = { throw boom })
            r.a.start()
            r.deliverIssued(); runCurrent()
            r.a.coordinator.onActivated(KEY_3M); runCurrent()
            r.sent.single { it.kind == "catalog" }.catalog.complete(rateLimited()); runCurrent()
            assertEquals("the injected jitter is used and its failure reaches the injected sink", listOf<Throwable>(boom), r.failures)
            withContext(r.main) { r.a.close() }
        }
    }

    /**
     * graphA02 (S4 RT01-A3): every part runs on the injected main - the recorder's collector, also for an issuer revision
     * alone, the coordinator's loop and its sends, and the sink's worker calling the recorder - each seen through the
     * snapshot reads it makes, or the send it makes, while the dispatcher marks it. No cache ports here, so no disk work
     * reads the snapshot elsewhere.
     */
    @Test
    fun graphA02_everyPartRunsOnTheInjectedMain() = snapshotTest {
        val r = AssemblyRig(this, granted(), disk = false)
        r.a.start(); runCurrent()
        assertTrue("the collector, on main: ${r.snapshotReads}", r.snapshotReads.isNotEmpty() && r.snapshotReads.all { it })
        r.snapshotReads.clear()
        val issued = r.deliverIssued(); runCurrent()
        assertTrue("the loop, on main: ${r.snapshotReads}", r.snapshotReads.isNotEmpty() && r.snapshotReads.all { it })
        r.snapshotReads.clear()
        r.a.coordinator.onActivated(KEY_3M); runCurrent()
        assertTrue("its sends, on main and under main: ${r.sendsOnMain}", r.sendsOnMain.isNotEmpty() && r.sendsOnMain.all { it })
        assertTrue("the loop, on main: ${r.snapshotReads}", r.snapshotReads.isNotEmpty() && r.snapshotReads.all { it })
        r.snapshotReads.clear()
        val lifetime = checkNotNull(r.uses.acquire(issued))
        assertEquals(TopicGraphOffer.ENQUEUED, r.a.sink.tryOffer(r.quote(1390.0, issued, lifetime))); runCurrent()
        assertEquals("premise: the worker handed it to the recorder", 1, r.held())
        assertTrue("the worker, on main: ${r.snapshotReads}", r.snapshotReads.isNotEmpty() && r.snapshotReads.all { it })

        r.snapshotReads.clear()
        val revision = r.h.coordinator.accessRevisions.value
        r.h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        r.h.source.afterFetch = { r.h.store.loadFailures = 2 }
        r.h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM); runCurrent()
        assertNotEquals("premise: the issuer published a revision", revision, r.h.coordinator.accessRevisions.value)
        assertTrue("a revision alone reaches the collector, on main: ${r.snapshotReads}", r.snapshotReads.isNotEmpty() && r.snapshotReads.all { it })
    }

    /**
     * graphA03 (S4 RT01-A3): the fence bridge publishes exactly what it is handed and never a fence of its own. On its own,
     * over the real issuer's grants: the delivered grant itself; an end delivered withdraws it; a grant handed over as a
     * re-approval is published as handed over, whether the bridge was empty or held another; the host is told once per call,
     * after the publication; a hold revision keeps the delivered fence; the same grant handed again tells the host again; and
     * a closed bridge tells the host nothing more. In the assembly, while the issuer has issued a grant that has not been
     * delivered, the gate takes no use under it; once it is delivered, the same use binds.
     */
    @Test
    fun graphA03_theBridgePublishesOnlyWhatIsDelivered() = snapshotTest {
        run {
            val h = granted()
            var told = 0
            val seen = mutableListOf<TopicSessionFence?>()
            lateinit var bridge: GraphAccessFenceBridge
            bridge = GraphAccessFenceBridge { told++; seen += bridge.current() }
            val first = checkNotNull(h.coordinator.topicGrantResult().fence)
            bridge.setAccess(true, first, TopicGrantOrigin.NewContext)
            assertSame("the delivered grant itself", first, bridge.current())
            val renewed = renewGeneration(h)
            assertNotEquals("premise: a new grant", first, renewed)
            bridge.setAccess(false, first, TopicGrantOrigin.NewContext)
            assertNull("the end delivered withdraws it", bridge.current())
            bridge.setAccess(true, renewed, TopicGrantOrigin.Reapproval(first.grant))
            assertSame("a re-approval into an empty bridge is published as handed over", renewed, bridge.current())
            bridge.setAccess(true, first, TopicGrantOrigin.Reapproval(renewed.grant))
            assertSame("a re-approval over a held grant is published as handed over, not kept", first, bridge.current())
            assertEquals("each call tells the host once", 4, told)
            assertEquals("the host is told after the publication", listOf(first, null, renewed, first), seen)
        }
        run {
            val h = granted()
            var told = 0
            val bridge = GraphAccessFenceBridge { told++ }
            val issued = checkNotNull(h.coordinator.topicGrantResult().fence)
            bridge.setAccess(true, issued, TopicGrantOrigin.NewContext)
            h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
            h.source.afterFetch = { h.store.loadFailures = 2 }
            h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
            runCurrent()
            assertTrue("premise: held", TopicAccessBlock.LOSS_CANDIDATE in h.facts.userBlocks)
            bridge.accessRevised()
            assertSame("a hold revision keeps the delivered fence", issued, bridge.current())
            assertEquals("each call tells the host once", 2, told)
            bridge.setAccess(true, issued, TopicGrantOrigin.NewContext)
            assertEquals("the same grant handed again tells the host again", 3, told)
        }
        run {
            val h = granted()
            var told = 0
            val bridge = GraphAccessFenceBridge { told++ }
            val issued = checkNotNull(h.coordinator.topicGrantResult().fence)
            bridge.setAccess(true, issued, TopicGrantOrigin.NewContext)
            bridge.close()
            val later = issued.copy(userAccessEpoch = "handed over after close")
            bridge.setAccess(true, later, TopicGrantOrigin.NewContext)
            assertNotSame("a closed bridge publishes nothing handed over", later, bridge.current())
            bridge.setAccess(false, issued, TopicGrantOrigin.NewContext)
            bridge.accessRevised()
            assertEquals("a closed bridge tells the host nothing", 1, told)
        }
        run {
            val r = AssemblyRig(this, granted(), disk = false)
            val first = r.deliverIssued()
            val renewed = renewGeneration(r.h)
            assertNotEquals("premise: a new grant", first, renewed)
            r.live = renewed.identity
            val lifetime = checkNotNull(r.uses.acquire(renewed)) { "premise: a use under the new grant" }
            assertNull("the assembly's gate takes no use under a grant issued but not delivered", r.a.gate.bind(renewed, lifetime))
            r.a.fences.setAccess(true, renewed, TopicGrantOrigin.NewContext)
            assertNotNull("once delivered, the same use binds", r.a.gate.bind(renewed, lifetime))
            withContext(r.main) { r.a.close() }
        }
    }

    /**
     * graphA09a (S4 RT01-A3): the recorder's catalog supplier answers the coordinator's adopted catalog only while the
     * published fence's data scope is the coordinator's: nothing before adoption, nothing for another epoch, a fence with no
     * epoch, another user or no fence - even before the coordinator moves - nothing after a context change until a catalog
     * is adopted again, then that new catalog. S4 RT03b-0: the context synchronization sets the publication's source before
     * any catalog; the supplier reads nothing for another grant of the same scope before the coordinator moves (the source's
     * fence), nor once the use authority, renewed under the same fence, admits only the next lifetime (the source's
     * lifetime is no longer admitted).
     */
    @Test
    fun graphA09a_theRecorderCatalogIsTheCoordinatorsInItsScopeOnly() = snapshotTest {
        val r = AssemblyRig(this, granted())
        r.a.start()
        val issued = r.deliverIssued(); runCurrent()
        val supply = graphRecorderCatalog({ r.a.coordinator }, r.a.fences.current, r.uses)
        r.a.coordinator.onActivated(KEY_3M); runCurrent()
        assertEquals("the context sync sets the source before any catalog", issued, r.a.coordinator.state.value.source?.fence)
        assertNull("nothing before a catalog is adopted", supply())
        r.sent.single { it.kind == "catalog" }.catalog.complete(ok(graphCatalog())); runCurrent()
        val adopted = checkNotNull(r.a.coordinator.state.value.catalog) { "premise: adopted" }
        assertSame("the same scope reads the adopted catalog", adopted, supply())
        // A same-fence use invalidation, modelled: once renewed, only the next lifetime is acquired and admitted.
        var renewed: TopicUseLifetime? = null
        val gated = graphRecorderCatalog({ r.a.coordinator }, r.a.fences.current, object : TopicUseAuthority {
            override fun acquire(fence: TopicSessionFence) = renewed ?: r.uses.acquire(fence)
            override fun admits(lifetime: TopicUseLifetime) = renewed?.let { it == lifetime } ?: r.uses.admits(lifetime)
        })
        assertSame("premise: an admitted source lifetime reads it", adopted, gated())
        val source = checkNotNull(r.a.coordinator.state.value.source) { "premise: the snapshot carries its source" }
        assertEquals("premise: the source is the delivered fence", issued, source.fence)
        renewed = source.lifetime.copy(invalidations = source.lifetime.invalidations + 1)
        assertNull("the source's lifetime no longer admitted reads nothing, before the coordinator moves", gated())
        assertSame("premise: the coordinator still holds it", adopted, r.a.coordinator.state.value.catalog)

        r.a.fences.setAccess(true, issued.copy(grant = TopicGrantToken(issued.grant.value + 100)), TopicGrantOrigin.NewContext)
        assertNull("another grant of the same scope reads nothing, before the coordinator moves (S4 RT03b-0)", supply())
        r.a.fences.setAccess(true, issued.copy(userAccessEpoch = "another"), TopicGrantOrigin.NewContext)
        assertNull("another epoch reads nothing, before the coordinator moves", supply())
        r.a.fences.setAccess(true, issued.copy(userAccessEpoch = null), TopicGrantOrigin.NewContext)
        assertNull("a fence with no epoch reads nothing", supply())
        r.a.fences.setAccess(true, issued.copy(identity = AuthIdentityFence(OTHER, 1L)), TopicGrantOrigin.NewContext)
        assertNull("another user reads nothing", supply())
        r.a.fences.setAccess(false, issued, TopicGrantOrigin.NewContext)
        assertNull("no fence reads nothing", supply())
        assertSame("premise: the coordinator still holds it", adopted, r.a.coordinator.state.value.catalog)
        runCurrent()
        r.a.fences.setAccess(true, issued, TopicGrantOrigin.NewContext); runCurrent()
        assertNull("after a context change, nothing until a catalog is adopted again", supply())
        val asked = r.sent.filter { it.kind == "catalog" }
        assertEquals("premise: a new catalog is asked for", 2, asked.size)
        asked.last().catalog.complete(ok(graphCatalog())); runCurrent()
        val again = checkNotNull(r.a.coordinator.state.value.catalog)
        assertNotSame("premise: a new catalog", adopted, again)
        assertSame("the new catalog is read", again, supply())
    }

    /**
     * graphA09b (S4 RT01-A3): in the assembly, an input the recorder held for want of a catalog is adopted as soon as the
     * coordinator publishes one, and the coordinator's 1d request then captures the recorder's recovery - the recorder is
     * the coordinator's own. A grant for another user handed over before the coordinator is told does not borrow the old
     * scope's catalog: the recorder holds the new user's input instead of adopting it.
     */
    @Test
    fun graphA09b_theAssemblysRecorderReplaysWithTheCatalogJustAdopted() = snapshotTest {
        val r = AssemblyRig(this, granted())
        r.a.start()
        val issued = r.deliverIssued(); runCurrent()
        val lifetime = checkNotNull(r.uses.acquire(issued))
        r.a.coordinator.onActivated(KEY_3M); runCurrent()
        assertEquals(TopicGraphOffer.ENQUEUED, r.a.sink.tryOffer(r.quote(1390.0, issued, lifetime))); runCurrent()
        assertTrue("premise: held without a catalog", r.held() == 1 && !r.adopted(r.kb(issued), 1390.0))
        r.sent.single { it.kind == "catalog" }.catalog.complete(ok(graphCatalog())); runCurrent()
        assertTrue("adopted as soon as the catalog is published", r.adopted(r.kb(issued), 1390.0))
        assertEquals(0, r.held())
        r.a.coordinator.onActivated(KEY_1D); runCurrent()
        assertEquals("the coordinator's 1d request captures the recorder's recovery", 1L, r.a.recorder.state.value.nextVersion)

        r.h.source.identity = EntitlementsIdentity(OTHER, 1L)
        r.h.coordinator.onIdentityChanged(AuthIdentityFence(OTHER, 1L))
        r.h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        val other = checkNotNull(r.h.coordinator.topicGrantResult().fence) { "premise: a grant for the other user" }
        assertNotNull("premise: the coordinator still holds the old scope's catalog", r.a.coordinator.state.value.catalog)
        r.live = AuthIdentityFence(OTHER, 1L)
        r.a.fences.setAccess(true, other, TopicGrantOrigin.NewContext)
        val otherLifetime = checkNotNull(r.uses.acquire(other)) { "premise: a use for the other user" }
        r.a.recorder.observe(r.quote(1391.0, other, otherLifetime))
        assertFalse("the old scope's catalog is not lent to the new one", r.adopted(r.kb(other), 1391.0))
        assertEquals("the new user's input is held instead", 1, r.held())
    }

    // --- S4 RT01-B2a: retiring a user data scope from the coordinator -----------------------------------------------

    /**
     * graphB2a01 (S4 RT01-B2a): over the real issuer, with the fence withdrawn and the coordinator not yet told, a caller
     * that has established that this USER epoch ended (a null fence alone is not that evidence) retires the scope the
     * coordinator still holds. Everything it held for it goes at once - entries, the catalog, the protected slot and the 1d
     * request carrying kb's recovery capture - published before the call returns; once the late 1d answer is handled
     * nothing holds the scope any more.
     */
    @Test
    fun graphB2a01_retiringAHeldScopeRemovesItsEntriesAndRequestsAtOnce() = snapshotTest {
        val h = granted()
        val g = GraphRig(this, h)
        val a = approve(g)
        val scope = a.kb.scope
        assertNotNull("premise: a protected entry", g.coordinator.protectedEntry(KEY_3M))
        g.fence = null
        val seen = mutableListOf<GraphDataScope>()
        assertEquals(GraphRuntimeRetirement.REMOVED, g.coordinator.retireScopes { seen += it; it == scope })
        assertEquals(listOf(scope), seen)
        assertEquals("emptied, data scope included", GraphRequestState(), g.coordinator.state.value)
        assertNull("no protected entry (the gate alone already refuses a withdrawn fence)", g.coordinator.protectedEntry(KEY_3M))
        a.day.tab.complete(ok(g.dayTab())); runCurrent()
        assertEquals("the late 1d answer lands nowhere", GraphRequestState(), g.coordinator.state.value)
        seen.clear()
        assertEquals("nothing is left to remove", GraphRuntimeRetirement.NOTHING_TO_REMOVE, g.coordinator.retireScopes { seen += it; it == scope })
        assertTrue("nothing holds the scope any more, its protected slot included", seen.isEmpty())
        assertTrue(g.failures.isEmpty())
    }

    /**
     * graphB2a02 (S4 RT01-B2a): a 1d request released by a context change still holds kb's recovery capture while its
     * answer is pending. Retiring kb's scope removes that capture; retiring again removes nothing more, though the request
     * keeps the scope a candidate until its answer is handled. Afterwards nothing holds the scope.
     */
    @Test
    fun graphB2a02_aReleasedRequestsCaptureIsRemoved_andTheScopeLeavesWithItsAnswer() = snapshotTest {
        val h = granted()
        val g = GraphRig(this, h)
        val a = approve(g)
        val scope = a.kb.scope
        g.withdraw(); runCurrent()
        assertNull("premise: the coordinator let the scope go", g.coordinator.state.value.dataScope)
        val seen = mutableListOf<GraphDataScope>()
        assertEquals("the released request held kb's capture", GraphRuntimeRetirement.REMOVED, g.coordinator.retireScopes { seen += it; it == scope })
        assertEquals(listOf(scope), seen)
        seen.clear()
        assertEquals("nothing more to remove", GraphRuntimeRetirement.NOTHING_TO_REMOVE, g.coordinator.retireScopes { seen += it; it == scope })
        assertEquals("still a candidate while its answer is pending", listOf(scope), seen)
        a.day.tab.complete(ok(g.dayTab())); runCurrent()
        seen.clear()
        assertEquals(GraphRuntimeRetirement.NOTHING_TO_REMOVE, g.coordinator.retireScopes { seen += it; it == scope })
        assertTrue("gone once its answer is handled", seen.isEmpty())
        assertTrue(g.failures.isEmpty())
    }

    private fun GraphRig.krxIn3m(): Boolean =
        coordinator.state.value.entries[KEY_3M]?.tab?.graph?.series?.any { it.seriesId == KRX_SERIES } == true

    /**
     * graphB2b01 (S4 RT01-B2b-1, B04): over the real issuer. The capability rotation lands closed (K1 -> K2); the P3-i sweep
     * retires everything of the owner but the record's K2, and K1's half leaves the 3m entry. Nothing old rejoins: the 3m
     * and 1d requests started with K1 apply nothing when they answer, and a use renewed while K2 is closed binds no epoch and
     * gains none when the issuer re-approves K2 under the same lifetime - nor does a request it started. Only a capture taken
     * after the re-approval carries K2, and only a request started with it takes a KRX half. A second sweep finds no K1. The
     * sweep takes only the K epoch from a request: the 1d request keeps kb's recovery capture.
     */
    @Test
    fun graphB2b01_aReapprovedEpochIsTheOnlyKrxAfterARetiredOne() = snapshotTest {
        val h = granted()
        val g = GraphRig(this, h)
        val a = approve(g)
        val k1 = checkNotNull(a.capture.krxCapabilityEpoch) { "premise: approved with a KRX epoch" }
        g.coordinator.onActivated(KEY_3M); runCurrent()
        g.coordinator.onRefreshRequested(force = true); runCurrent()
        val old3m = g.tabs(KEY_3M).last()
        assertTrue("premise: K1's half in the 3m entry", g.krxIn3m())

        val parked = gate()
        h.store.rotationGate = parked
        h.source.next = { active(krx = false) }
        val refreshing = launch { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()
        parked.complete(Unit)
        settle()
        refreshing.join()
        val k2 = checkNotNull(h.store.record.krxCapabilityEpoch) { "premise: a new epoch" }
        assertNotEquals("premise: the capability epoch moved", k1, k2)
        assertFalse("premise: the old token no longer stands", h.facts.tokenStanding)

        val keepK2: (GraphCapabilityScope) -> Boolean = { it.uid == OWNER && it.krxCapabilityEpoch != k2 }
        val seen = mutableListOf<GraphCapabilityScope>()
        assertEquals(GraphRuntimeRetirement.REMOVED, g.coordinator.retireCapabilities { seen += it; keepK2(it) })
        assertEquals(setOf(GraphCapabilityScope(OWNER, k2), GraphCapabilityScope(OWNER, k1)), seen.toSet())
        assertFalse("K1's half left the 3m entry", g.krxIn3m())
        val recovery = mutableListOf<GraphDataScope>()
        assertFalse(g.coordinator.purgeRecoveryCaptures { recovery += it; false })
        assertTrue("the 1d request keeps kb's recovery capture", recovery.isNotEmpty())
        val retired = g.coordinator.state.value.entries
        old3m.tab.complete(ok(g.threeMonthTab()))
        a.day.tab.complete(ok(g.dayTab())); runCurrent()
        assertEquals("the K1 requests apply nothing", retired, g.coordinator.state.value.entries)

        val renewed = g.publishIssued(); runCurrent()
        g.sent.filter { it.kind == "catalog" && !it.catalog.isCompleted }.forEach { it.catalog.complete(ok(graphCatalog())) }
        runCurrent()
        val fresh = checkNotNull(g.uses.acquire(renewed)) { "a fresh use under the new grant" }
        val closed = checkNotNull(g.access.bind(renewed, fresh)) { "the gate binds the fresh use" }
        assertNull("bound while K2 is closed", closed.krxCapabilityEpoch)
        val closedTab = g.tabs(KEY_3M).last()
        assertTrue("premise: a 3m request started while K2 is closed", closedTab !== old3m && closedTab.guard())

        h.source.next = { active(krx = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        settle()
        assertEquals("premise: the same K2 reopened", k2, h.store.record.krxCapabilityEpoch)
        assertTrue("premise: the same lifetime", g.uses.admits(fresh))
        assertFalse("the closed capture gains nothing", g.access.admits(closed, GraphV2DiskComponent.KRX))
        val reopened = checkNotNull(g.access.bind(renewed, fresh))
        assertEquals("a new capture carries K2", k2, reopened.krxCapabilityEpoch)
        g.coordinator.onContextChanged(); runCurrent()
        closedTab.tab.complete(ok(g.threeMonthTab())); runCurrent()
        assertFalse("nor does a request it started", g.krxIn3m())

        g.coordinator.onRefreshRequested(force = true); runCurrent()
        val reopenedTab = g.tabs(KEY_3M).last()
        assertTrue("premise: a request started after the re-approval", reopenedTab !== closedTab)
        reopenedTab.tab.complete(ok(g.threeMonthTab())); runCurrent()
        assertTrue("it takes K2's half", g.krxIn3m())
        val exposed = checkNotNull(g.coordinator.protectedEntry(KEY_3M)) { "exposed to the fresh use" }
        assertTrue(exposed.tab.graph.series.any { it.seriesId == KRX_SERIES })

        seen.clear()
        assertEquals(GraphRuntimeRetirement.NOTHING_TO_REMOVE, g.coordinator.retireCapabilities { seen += it; keepK2(it) })
        assertEquals("only K2 is held", setOf(GraphCapabilityScope(OWNER, k2)), seen.toSet())
        assertTrue(g.failures.isEmpty())
    }

    /** The real store; a write can wait just before the store is asked, and its reports, cancels and withdrawals are kept. */
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
            GraphV2Series(ONLINE, ONLINE, "krw", "KRW", 2, listOf(GraphV2Point(now - 1.days, 1390.0, "x")),
                GraphV2Provenance(false, emptyList()), null),
            GraphV2Series(KRX_SERIES, KRX_SERIES, "krw", "KRW", 1, listOf(GraphV2Point(now - 1.days, 1395.0, "krx")),
                GraphV2Provenance(false, emptyList()), null)
        ),
        metadata = GraphV2Metadata(now - 1.hours, "1d", GraphV2Range("2026-07-05", "2026-10-05"))
    )

    /**
     * graphB2b03 (S4 RT01-B2b-2, B04): over the real issuer and the assembly, with write ports. A K1 answer's write waits in
     * its preparation, or after Ready just before the store, when the capability rotation lands. The sweep runs where the
     * issuer purges (under its lock, on the assembly's main) and withdraws the store's KRX half without cancelling the
     * write; then the old capture lands nothing at all. After the new grant and K2's re-approval, a write started with a
     * fresh capture publishes K2's half; K1's never appears. No disk purge runs.
     */
    @Test
    fun graphB2b03_aWriteStartedWithARetiredEpochLandsNothingOfIt() = snapshotTest {
        for (stage in listOf("preparing", "ready")) {
            val h = granted()
            val k1 = checkNotNull(h.store.record.krxCapabilityEpoch) { "premise: a KRX epoch" }
            val epoch = checkNotNull(h.store.record.userAccessEpoch) { "premise: a user epoch" }
            val held = HeldWrites(FileGraphV2DiskStore(folder.newFolder(), JsonGraphV2EnvelopeCodec(), DefaultGraphV2AtomicFileIo(),
                StandardTestDispatcher(testScheduler)))
            val prepareCalls = mutableListOf<Pair<String?, Boolean>>()
            var holdPrepare: CompletableDeferred<Unit>? = null
            val writes = GraphV2WritePorts(
                prepareWrite = GraphV2PrepareWrite { captured, wantsKrx ->
                    prepareCalls += captured.krxCapabilityEpoch to wantsKrx
                    holdPrepare?.await()
                    // A stand-in producer: the namespace record made durable for exactly this capture.
                    val record = AccessEpochRecord(captured.fence.identity.uid, captured.fence.userAccessEpoch,
                        captured.krxCapabilityEpoch, mayContainPremiumData = true, mayContainKrxData = true)
                    GraphV2WritePreparation(GraphV2NamespacePreparation.Ready(record),
                        if (wantsKrx) GraphV2NamespacePreparation.Ready(record) else null)
                },
                onPreparationBlocked = {},
                onWriteDiagnostic = {}
            )
            val r = AssemblyRig(this, h, cachePorts = { gate -> GraphV2CachePorts(held, gate, onSeedDiagnostic = {}, writePorts = writes) })
            r.a.start()
            r.deliverIssued(); runCurrent()
            r.a.coordinator.onActivated(KEY_3M); runCurrent()
            r.sent.single { it.kind == "catalog" }.catalog.complete(ok(graphCatalog())); runCurrent()
            val hold = gate()
            if (stage == "preparing") holdPrepare = hold else held.holdBeforeWrite = hold
            r.sent.single { it.kind == "tab" && it.key == KEY_3M }.tab.complete(ok(threeMonthTabAt(r.now()))); runCurrent()
            assertEquals("$stage: premise: K1's write asks KRX", listOf(k1 to true), prepareCalls)
            assertEquals("$stage: premise: where it waits", if (stage == "ready") 1 else 0, held.writesEntered)
            val ticket = held.withdrawals.size

            var retired: GraphRuntimeRetirement? = null
            var cancelledAtReturn = -1
            h.purger.onCapability = { namespace ->
                withContext(r.main) {
                    retired = r.a.coordinator.retireCapabilities {
                        it.uid == namespace.ownerUid && it.krxCapabilityEpoch != namespace.currentKrxCapabilityEpoch
                    }
                    cancelledAtReturn = held.cancelled.size
                }
            }
            val parked = gate()
            h.store.rotationGate = parked
            h.source.next = { active(krx = false) }
            val refreshing = launch { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
            runCurrent()
            parked.complete(Unit)
            settle()
            refreshing.join()
            h.purger.onCapability = {}
            assertNotEquals("$stage: premise: the capability epoch moved", k1, h.store.record.krxCapabilityEpoch)
            assertEquals("$stage: the sweep at the issuer's purge", GraphRuntimeRetirement.REMOVED, retired)
            assertEquals("$stage: it withdrew the write's KRX half", ticket + 1, held.withdrawals.size)
            assertEquals("$stage: and cancelled nothing", 0, cancelledAtReturn)
            hold.complete(Unit); runCurrent()
            assertTrue("$stage: nothing of the old capture lands",
                held.reports.none { it.general == GraphV2ComponentWriteOutcome.Replaced || it.krx == GraphV2ComponentWriteOutcome.Replaced })
            val k1Key = GraphV2KrxKey(OWNER, epoch, k1, "usd", GraphPeriod.THREE_MONTHS.code)
            assertFalse("$stage: no K1 file", held.readKrx(k1Key, null) { true } is GraphV2DiskRead.Found)

            r.deliverIssued(); runCurrent()
            r.sent.filter { it.kind == "catalog" && !it.catalog.isCompleted }.forEach { it.catalog.complete(ok(graphCatalog())) }
            runCurrent()
            val closedTab = r.sent.last { it.kind == "tab" && it.key == KEY_3M }
            assertFalse("$stage: premise: a request started while K2 is closed", closedTab.tab.isCompleted)
            h.source.next = { active(krx = true) }
            h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
            settle()
            val k2 = checkNotNull(h.store.record.krxCapabilityEpoch)
            closedTab.tab.complete(ok(threeMonthTabAt(r.now()))); runCurrent()
            assertEquals("$stage: the closed-time capture writes without KRX", null to false, prepareCalls.last())
            for (attempt in 1..3) {
                advanceTimeBy(3_100); runCurrent()
                r.sent.filter { it.kind == "tab" && it.key == KEY_3M && !it.tab.isCompleted }.forEach {
                    it.tab.complete(ok(threeMonthTabAt(r.now())))
                }
                runCurrent()
                if (prepareCalls.last() == (k2 to true)) break
            }
            assertEquals("$stage: a fresh write with K2", k2 to true, prepareCalls.last())
            val k2Key = GraphV2KrxKey(OWNER, epoch, k2, "usd", GraphPeriod.THREE_MONTHS.code)
            assertTrue("$stage: K2's half is published", held.readKrx(k2Key, null) { true } is GraphV2DiskRead.Found)
            assertFalse("$stage: K1's never", held.readKrx(k1Key, null) { true } is GraphV2DiskRead.Found)
            assertTrue(r.failures.isEmpty())
            r.a.close()
        }
    }

    /** The real store, with GENERAL reads counted and held after the real read returned when a row asks (S4 RT01-B2b-1). */
    private class HeldReads(private val real: GraphV2DiskStore) : GraphV2DiskStore by real {
        var reads = 0
        val hold = mutableMapOf<Int, CompletableDeferred<Unit>>()

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

    /**
     * graphB2b02 (S4 RT01-B2b-1, B04): over the real issuer, a FillEmpty seed for K1's stored answer is out when the capability
     * rotation lands closed. The P3-i sweep retires K1, the use is renewed while K2 is closed, and K2 is re-approved under the
     * same lifetime; only then does the K1 seed answer. It applies nothing and K1's stored half never joins: the new context
     * seeds the stored general half alone, nothing is exposed with a KRX half, and the 3m entry carries none.
     */
    @Test
    fun graphB2b02_aSeedOutForARetiredEpochRejoinsNothing() = snapshotTest {
        val h = granted()
        val k1 = checkNotNull(h.store.record.krxCapabilityEpoch) { "premise: a KRX epoch" }
        val epoch = checkNotNull(h.store.record.userAccessEpoch) { "premise: a user epoch" }
        val held = HeldReads(FileGraphV2DiskStore(folder.newFolder(), JsonGraphV2EnvelopeCodec(), DefaultGraphV2AtomicFileIo(),
            StandardTestDispatcher(testScheduler)))
        val g = GraphRig(this, h, store = held)
        val tab = (GraphV2Domain.admit(g.threeMonthTab(), "usd", GraphPeriod.THREE_MONTHS, null) as GraphTabAdmission.Accepted).tab
        val stored = (splitGraphV2ServerTab(tab, GraphV2GeneralKey(OWNER, epoch, "usd", GraphPeriod.THREE_MONTHS.code), k1, "stored", null)
            as GraphV2Validation.Valid).value
        val ticket = (held.reserveWrite(stored) as GraphV2WriteReservation.Reserved).ticket
        held.write(ticket) { true }
        held.hold[1] = gate()
        g.publishIssued(); runCurrent()
        g.coordinator.onActivated(KEY_3M); runCurrent()
        assertEquals("premise: the K1 seed is out", 1, held.reads)
        assertNull(g.coordinator.state.value.entries[KEY_3M])

        val parked = gate()
        h.store.rotationGate = parked
        h.source.next = { active(krx = false) }
        val refreshing = launch { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()
        parked.complete(Unit)
        settle()
        refreshing.join()
        val k2 = checkNotNull(h.store.record.krxCapabilityEpoch) { "premise: a new epoch" }
        assertNotEquals("premise: the capability epoch moved", k1, k2)
        val seen = mutableListOf<GraphCapabilityScope>()
        assertEquals(GraphRuntimeRetirement.REMOVED, g.coordinator.retireCapabilities {
            seen += it; it.uid == OWNER && it.krxCapabilityEpoch != k2
        })
        assertEquals("the seed held K1", setOf(GraphCapabilityScope(OWNER, k2), GraphCapabilityScope(OWNER, k1)), seen.toSet())

        val renewed = g.publishIssued(); runCurrent()
        g.sent.filter { it.kind == "catalog" && !it.catalog.isCompleted }.forEach { it.catalog.complete(ok(graphCatalog())) }
        runCurrent()
        h.source.next = { active(krx = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        settle()
        assertEquals("premise: the same K2 reopened", k2, h.store.record.krxCapabilityEpoch)
        val fresh = checkNotNull(g.uses.acquire(renewed)) { "premise: the renewed use" }
        assertEquals("premise: a capture now carries K2", k2, checkNotNull(g.access.bind(renewed, fresh)).krxCapabilityEpoch)
        g.coordinator.onContextChanged(); runCurrent()
        checkNotNull(held.hold[1]).complete(Unit); runCurrent()

        assertEquals("the stored general half alone", GraphEntry(joinGraphV2Components(stored.general, null).tab, null),
            g.coordinator.state.value.entries[KEY_3M])
        val exposed = g.coordinator.protectedEntry(KEY_3M)
        assertTrue("nothing exposed with a KRX half", exposed == null || exposed.tab.graph.series.none { it.seriesId == KRX_SERIES })
        assertTrue(g.failures.isEmpty())
    }

    // --- S4 RT01-B3: the runtime port and the screen holder on the assembly ---------------------------------------

    /**
     * A usd screen holder built from [r]'s assembly - its coordinator, gate, fence supplier and recorder - with the rig's use
     * authority, the issuer's access revisions and the assembly's main, under a scope beneath the rig's parent (outside the
     * assembly's job, which knows no holder). Its selection store notes whether each call ran on main, and each publication
     * the flow emits (a change of the published state) notes whether it was made on main; an equal re-publication emits
     * nothing. The runtime port lists this holder alone. Rows call the holder only on main.
     */
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
        assertEquals("premise: the chart is ready", GraphV2Content.READY, state.content)
        withContext(r.main) { ah.holder.setSurfaceVisible(checkNotNull(state.inlineToken) { "premise: an inline token" }, true) }
        runCurrent()
        val lifetime = checkNotNull(r.uses.acquire(fence)) { "premise: a use under the issued fence" }
        withContext(r.main) { r.a.recorder.observe(r.quote(1410.0, fence, lifetime)) }
        assertTrue("premise: kb adopted the live price", r.adopted(r.kb(fence), 1410.0))
        runCurrent()
        return Shown(fence, lifetime)
    }

    /**
     * graphB301 (S4 RT01-B3; PA's same-assembly obligation, carried to the holder): a screen holder built from the
     * assembly's own gate, fence supplier, recorder and coordinator runs on the assembly's main. Called from off main, both
     * of the port's coordinator steps evaluate their selector on main, and each change the holder publishes - starting,
     * activating, drawing, and inside both port retirements - is made on main, as is every selection store call. A closed
     * protected admission nobody told the holder about is first published by the capability step. Then the bridge's
     * access is withdrawn (no issuer end: this row checks executors only) and the USER retirement is called before
     * anything handles it; the coordinator, dispatched first, already dropped the scope (NOTHING_TO_REMOVE), and the port's
     * holder step still publishes the empty screen.
     */
    @Test
    fun graphB301_theScreenAndThePortRunOnTheAssemblysMain() = snapshotTest {
        val r = AssemblyRig(this, granted())
        r.a.start()
        val ah = AssemblyHolder(this, r)
        val shown = showOnAssembly(ah)
        assertTrue("premise: the holder published", ah.publishedOnMain.isNotEmpty())
        assertTrue("premise: the selection store was used", ah.selectionsOnMain.isNotEmpty())
        // While the scope is live each coordinator step evaluates the live candidate and removes nothing; from off main,
        // the port must have entered main before the coordinator runs.
        val evaluatedOnMain = mutableListOf<Boolean>()
        assertEquals(GraphRuntimeRetirement.NOTHING_TO_REMOVE, ah.port.retireScopes { evaluatedOnMain += r.main.marked; false })
        assertEquals(GraphRuntimeRetirement.NOTHING_TO_REMOVE,
            ah.port.retireCapabilities { evaluatedOnMain += r.main.marked; false })
        assertTrue("the coordinator steps ran on main: $evaluatedOnMain", evaluatedOnMain.size >= 2 && evaluatedOnMain.all { it })
        val capabilityMark = ah.publishedOnMain.size
        r.admitted = false
        assertEquals(GraphRuntimeRetirement.NOTHING_TO_REMOVE, ah.port.retireCapabilities { false })
        assertTrue("the capability step published", ah.publishedOnMain.size > capabilityMark)
        assertEquals(GraphV2Content.BLOCKED, ah.holder.state.value.content)
        r.admitted = true
        withContext(r.main) { ah.holder.currentState() }
        assertEquals("premise: drawn again", GraphV2Content.READY, ah.holder.state.value.content)
        val retired = GraphDataScope(OWNER, checkNotNull(shown.fence.userAccessEpoch))
        val mark = ah.publishedOnMain.size
        r.a.fences.setAccess(false, shown.fence, TopicGrantOrigin.NewContext)
        assertEquals("premise: nothing handled the withdrawal yet", GraphV2Content.READY, ah.holder.state.value.content)
        assertEquals(GraphRuntimeRetirement.NOTHING_TO_REMOVE, ah.port.retireScopes { it == retired })
        assertTrue("the USER step published", ah.publishedOnMain.size > mark)
        assertEquals("the empty screen", GraphV2Content.BLOCKED, ah.holder.state.value.content)
        assertNull(ah.holder.state.value.chart)
        runCurrent()
        assertTrue("every publication on main: ${ah.publishedOnMain}", ah.publishedOnMain.all { it })
        assertTrue("every selection call on main: ${ah.selectionsOnMain}", ah.selectionsOnMain.all { it })
        assertTrue(r.failures.isEmpty())
        withContext(r.main) { r.a.close() }
    }

    /**
     * graphB302 (S4 RT01-B3, B02): the real issuer's user purge runs the runtime port under its lock, on main. While the
     * retired epoch's fence is still delivered every call refuses (LIVE_SCOPE_SELECTED) - the first one meets the drawn
     * screen - and changes neither the coordinator nor the holder; the purge answers Failed and its journal stays. The
     * holder empties its screen itself on the issuer's revision. The user is approved again and the issuer's next grant,
     * of a new epoch, is delivered once its lock is released; the holder takes the new scope. The issuer's retry then runs
     * the port: whatever the coordinator still had, the holder - holding the new scope at that moment - keeps that screen as
     * it was in the same main block (that the holder step runs at all is graphB301's and B3H02's). The journal clears. Once
     * the new scope draws, the late answer of a retired request, the old token and the publication scheduled before change
     * nothing.
     */
    @Test
    fun graphB302_aUserPurgeRefusedWhileLiveRunsAgainOnItsRetryAndKeepsTheNewScope() = snapshotTest {
        val h = granted()
        val r = AssemblyRig(this, h)
        r.a.start()
        val ah = AssemblyHolder(this, r)
        val shown = showOnAssembly(ah)
        val retired = GraphDataScope(OWNER, checkNotNull(shown.fence.userAccessEpoch))
        val old = withContext(r.main) { checkNotNull(ah.holder.currentState().inlineToken) }
        withContext(r.main) { r.a.coordinator.onRefreshRequested(force = true) }; runCurrent()
        val late = r.sent.last { it.kind == "tab" && it.key == KEY_1D }
        assertFalse("premise: a request of the retired scope is out", late.tab.isCompleted)

        val results = mutableListOf<GraphRuntimeRetirement>()
        var refusedUnchanged: Boolean? = null
        var firstRefusalDrawn: Boolean? = null
        var keptAcrossRetry: Boolean? = null
        var fenceAtRetry: TopicSessionFence? = null
        h.purger.onUser = { namespace ->
            withContext(r.main) {
                val snapshotBefore = r.a.coordinator.state.value
                val stateBefore = ah.holder.state.value
                val result = ah.port.retireScopes {
                    it.uid == namespace.ownerUid && it.userAccessEpoch != namespace.currentUserAccessEpoch
                }
                results += result
                if (result == GraphRuntimeRetirement.LIVE_SCOPE_SELECTED) {
                    if (firstRefusalDrawn == null) firstRefusalDrawn = stateBefore.chart != null
                    refusedUnchanged = (refusedUnchanged ?: true) && snapshotBefore === r.a.coordinator.state.value &&
                        stateBefore === ah.holder.state.value
                    PurgeResult.Failed(IllegalStateException("a selected graph scope is still live"))
                } else {
                    fenceAtRetry = stateBefore.inlineToken?.fence
                    keptAcrossRetry = stateBefore === ah.holder.state.value
                    PurgeResult.Completed
                }
            }
        }
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertNotEquals("premise: the user epoch moved", retired.userAccessEpoch, h.store.record.userAccessEpoch)
        assertTrue("the purge met the delivered fence: $results",
            results.isNotEmpty() && results.all { it == GraphRuntimeRetirement.LIVE_SCOPE_SELECTED })
        assertEquals("premise: the first refusal met the drawn screen", true, firstRefusalDrawn)
        assertEquals("and changed nothing", true, refusedUnchanged)
        assertTrue("its journal stays", h.store.record.pendingPurges.isNotEmpty())
        assertNull("the holder emptied the screen itself", withContext(r.main) { ah.holder.currentState() }.chart)
        val refusals = results.size

        h.source.next = { active(krx = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        val next = r.deliverIssued(); runCurrent()
        assertNotEquals("premise: a new user scope", retired.userAccessEpoch, next.userAccessEpoch)
        r.sent.filter { it.kind == "catalog" && !it.catalog.isCompleted }.forEach { it.catalog.complete(ok(graphCatalog())) }
        runCurrent()
        val newScope = withContext(r.main) { checkNotNull(ah.holder.currentState().inlineToken) { "premise: the holder holds a scope" } }
        assertEquals("premise: the holder holds the new scope before the retry", next, newScope.fence)
        for (attempt in 1..60) {
            if (results.size > refusals) break
            advanceTimeBy(RETRY); runCurrent()
        }
        assertEquals("the retry ran the port once more: $results", refusals + 1, results.size)
        assertTrue("the retry was not refused", results.last() != GraphRuntimeRetirement.LIVE_SCOPE_SELECTED)
        assertEquals("premise: the holder held the new scope at the retry", next, fenceAtRetry)
        assertEquals("the new scope's screen is kept", true, keptAcrossRetry)
        assertTrue("the journal clears", h.store.record.pendingPurges.isEmpty())

        r.sent.filter { it !== late && it.kind == "tab" && !it.tab.isCompleted }
            .forEach { it.tab.complete(ok(drawableDayTabAt(r.now()))) }
        runCurrent()
        val drawn = withContext(r.main) { ah.holder.currentState() }
        assertNotNull("premise: the new scope draws", drawn.chart)
        late.tab.complete(ok(drawableDayTabAt(r.now()))); runCurrent()
        withContext(r.main) { ah.holder.toggleSeries(old, KB) }; runCurrent()
        advanceTimeBy(400); runCurrent()
        val after = withContext(r.main) { ah.holder.currentState() }
        assertSame("the late answer, the old token and the old publication change nothing",
            checkNotNull(drawn.chart).prepared, checkNotNull(after.chart).prepared)
        assertEquals(drawn.inlineToken, after.inlineToken)
        assertTrue("every publication on main", ah.publishedOnMain.all { it })
        assertTrue("every selection call on main", ah.selectionsOnMain.all { it })
        assertTrue(r.failures.isEmpty())
        h.purger.onUser = { PurgeResult.Completed }
        withContext(r.main) { r.a.close() }
    }

    /**
     * graphB303 (S4 RT01-B3, B03): the real issuer's capability purge runs the runtime port under its lock, on main, while
     * the holder still shows 3m with the KRX toggle. In that main block the coordinator drops K1's half (REMOVED) and the
     * holder's publication at the port's return has no KRX toggle or rendered id. After the new grant and K2's
     * re-approval, choosing 3m again shows the kept GENERAL entry without the KRX toggle; only a fresh answer under K2
     * brings the toggle back.
     */
    @Test
    fun graphB303_aCapabilityPurgeLeavesTheScreenWithoutKrxUntilAFreshAnswer() = snapshotTest {
        val h = granted()
        val k1 = checkNotNull(h.store.record.krxCapabilityEpoch) { "premise: a KRX epoch" }
        val r = AssemblyRig(this, h)
        r.a.start()
        val ah = AssemblyHolder(this, r)
        showOnAssembly(ah)
        val day = withContext(r.main) { checkNotNull(ah.holder.currentState().inlineToken) }
        withContext(r.main) { ah.holder.selectPeriod(day, GraphPeriod.THREE_MONTHS) }; runCurrent()
        r.sent.last { it.kind == "tab" && it.key == KEY_3M }.tab.complete(ok(threeMonthTabAt(r.now()))); runCurrent()
        val shown = withContext(r.main) { ah.holder.currentState() }
        assertTrue("premise: the KRX toggle is shown", shown.toggles.any { it.seriesId == KRX_SERIES })

        val results = mutableListOf<GraphRuntimeRetirement>()
        var atReturn: GraphV2ScreenState? = null
        var krxAtEntry: Boolean? = null
        h.purger.onCapability = { namespace ->
            withContext(r.main) {
                krxAtEntry = ah.holder.state.value.toggles.any { it.seriesId == KRX_SERIES }
                results += ah.port.retireCapabilities {
                    it.uid == namespace.ownerUid && it.krxCapabilityEpoch != namespace.currentKrxCapabilityEpoch
                }
                atReturn = ah.holder.state.value
            }
        }
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        settle()
        h.purger.onCapability = {}
        assertNotEquals("premise: the capability epoch moved", k1, h.store.record.krxCapabilityEpoch)
        assertEquals(listOf(GraphRuntimeRetirement.REMOVED), results)
        val at = checkNotNull(atReturn)
        assertEquals("premise: the holder still showed the KRX toggle when the port began", true, krxAtEntry)
        assertTrue("no KRX toggle at the port's return", at.toggles.none { it.seriesId == KRX_SERIES })
        assertTrue("no KRX drawn at the port's return", at.chart?.renderedIds?.contains(KRX_SERIES) != true)

        r.deliverIssued(); runCurrent()
        r.sent.filter { it.kind == "catalog" && !it.catalog.isCompleted }.forEach { it.catalog.complete(ok(graphCatalog())) }
        runCurrent()
        h.source.next = { active(krx = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        settle()
        val k2 = checkNotNull(h.store.record.krxCapabilityEpoch)
        assertNotEquals(k1, k2)
        r.sent.filter { it.kind == "tab" && !it.tab.isCompleted }.forEach { it.tab.complete(ok(drawableDayTabAt(r.now()))) }
        runCurrent()
        val token = withContext(r.main) { checkNotNull(ah.holder.currentState().inlineToken) { "premise: shown again" } }
        withContext(r.main) { ah.holder.selectPeriod(token, GraphPeriod.THREE_MONTHS) }; runCurrent()
        val before = withContext(r.main) { ah.holder.currentState() }
        assertTrue("premise: the kept GENERAL 3m entry is shown", before.chart != null && before.toggles.any { it.seriesId == ONLINE })
        assertTrue("KRX does not come back before a fresh answer", before.toggles.none { it.seriesId == KRX_SERIES })
        val fresh = r.sent.last { it.kind == "tab" && it.key == KEY_3M }
        assertFalse("premise: a 3m request after the re-approval", fresh.tab.isCompleted)
        fresh.tab.complete(ok(threeMonthTabAt(r.now()))); runCurrent()
        val after = withContext(r.main) { ah.holder.currentState() }
        assertTrue("a fresh answer under K2 brings the KRX toggle", after.toggles.any { it.seriesId == KRX_SERIES })
        assertTrue("every publication on main", ah.publishedOnMain.all { it })
        assertTrue("every selection call on main", ah.selectionsOnMain.all { it })
        assertTrue(r.failures.isEmpty())
        withContext(r.main) { r.a.close() }
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
}
