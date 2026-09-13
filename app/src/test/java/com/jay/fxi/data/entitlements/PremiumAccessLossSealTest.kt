package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1r-2b: an explicit loss whose rotation cannot be persisted is published and sealed, never thrown, and loss recovery
 * owns what is left (`s1r2b_contract_v3_1.md`).
 *
 * The store fails on purpose and some of these keep failing, so recovery keeps retrying: each test cancels the
 * coordinator's scope before the test body ends, and advances time explicitly where recovery never settles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PremiumAccessLossSealTest {

    private val processJob = SupervisorJob()

    /** Built on the production transitions, with the failure modes these contracts need. */
    private class Store(private val ids: EpochIdGenerator) : AccessEpochStore {
        var record = AccessEpochRecord()

        /** The next this many rotations throw before writing. */
        var rotationFailures = 0

        /** The next this many rotations write and then throw. */
        var rotationLandsThenThrows = 0
        var rotationFailure: () -> Throwable = { IOException("rotation") }

        /** The next this many binds write and then throw. */
        var bindLandsThenThrows = 0

        /** The next this many journal restorations write and then throw. */
        var journalRetiredLandsThenThrows = 0

        /** The next this many loads throw. */
        var loadFailures = 0

        /** When a rotation lands and then throws, the load that reads it back throws too. */
        var failTheReadBackOfALandedThrow = false

        /** The axes each rotation was asked for, in order. */
        val rotationRequests = mutableListOf<Pair<Boolean, Boolean>>()

        var rotationAttempts = 0
        var journalRetiredCalls = 0

        /** Parks the next rotation before it fails or writes, so a caller can be cancelled while it runs. */
        var rotationGate: CompletableDeferred<Unit>? = null
        val rotationParked = CompletableDeferred<Unit>()

        override suspend fun load(): AccessEpochRecord {
            if (loadFailures > 0) {
                loadFailures -= 1
                throw IOException("load")
            }
            return record
        }
        override suspend fun bindOwner(uid: String): AccessEpochRecord {
            record = AccessEpochTransitions.bindOwner(record, uid, ids)
            if (bindLandsThenThrows > 0) {
                bindLandsThenThrows -= 1
                throw IOException("bind landed")
            }
            return record
        }
        var signOutLandsThenThrows = false
        override suspend fun signOut(): AccessEpochRecord {
            record = AccessEpochTransitions.signOut(record, ids)
            if (signOutLandsThenThrows) {
                signOutLandsThenThrows = false
                throw IOException("sign-out landed")
            }
            return record
        }
        override suspend fun retireUnverifiedStart() =
            AccessEpochTransitions.retireUnverifiedStart(record, ids).also { record = it }
        override suspend fun beginSignOut(uid: String) = AccessEpochTransitions.beginSignOut(record, uid).also { record = it }
        override suspend fun beginRotation(rotateUser: Boolean, rotateKrx: Boolean): AccessEpochRecord {
            rotationAttempts += 1
            rotationRequests += rotateUser to rotateKrx
            rotationGate?.let { gate ->
                rotationGate = null
                rotationParked.complete(Unit)
                gate.await()
            }
            if (rotationFailures > 0) {
                rotationFailures -= 1
                throw rotationFailure()
            }
            record = AccessEpochTransitions.rotate(record, rotateUser, rotateKrx, ids)
            if (rotationLandsThenThrows > 0) {
                rotationLandsThenThrows -= 1
                if (failTheReadBackOfALandedThrow) loadFailures += 1
                throw IOException("rotation landed")
            }
            return record
        }
        override suspend fun completePurges(completed: Collection<PendingPurge>) =
            AccessEpochTransitions.completePurges(record, completed).also { record = it }
        override suspend fun journalRetired(obligation: LossObligation): AccessEpochRecord {
            journalRetiredCalls += 1
            record = AccessEpochTransitions.journalRetired(record, obligation)
            if (journalRetiredLandsThenThrows > 0) {
                journalRetiredLandsThenThrows -= 1
                throw IOException("journal restored")
            }
            return record
        }
        override suspend fun markMayContainData(premium: Boolean, krx: Boolean) =
            AccessEpochTransitions.markMayContainData(record, premium, krx).also { record = it }
    }

    private class Purger(var result: PurgeResult) : UserScopePurger, CapabilityScopePurger {
        var calls = 0
        var throwNext = false
        var throwAlways = false
        private fun run(): PurgeResult {
            calls += 1
            if (throwAlways) throw IOException("purge keeps throwing")
            if (throwNext) {
                throwNext = false
                throw IOException("purge threw")
            }
            return result
        }
        override suspend fun purgeUserScope(namespace: PurgeNamespace) = run()
        override suspend fun purgeCapabilityScope(namespace: PurgeNamespace) = run()
    }

    private class Source(var next: () -> EntitlementsOutcome) : EntitlementsSource {
        var identity: EntitlementsIdentity? = EntitlementsIdentity(OWNER, 1L)
        var liveFence: AuthIdentityFence? = null
        var fetches = 0
        val freshRequests = mutableListOf<Boolean>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun fetch(freshPremium: Boolean): EntitlementsResult {
            fetches += 1
            freshRequests += freshPremium
            val outcome = next()
            val owner = checkNotNull(identity)
            gate?.let { g -> gate = null; g.await() }
            return EntitlementsResult.Answered(owner, outcome)
        }
        override suspend fun currentIdentity() = identity
    }

    private class Harness(
        val store: Store,
        val source: Source,
        val purger: Purger,
        val coordinator: PremiumAccessCoordinator,
        val reapprovals: MutableList<Pair<RefreshIntent, Long>>
    )

    private fun ids(): EpochIdGenerator {
        var n = 0
        return EpochIdGenerator { "epoch-${n++}" }
    }

    private fun TestScope.build(purge: PurgeResult = PurgeResult.Completed): Harness {
        val store = Store(ids())
        val source = Source { active(krx = true) }
        val purger = Purger(purge)
        val reapprovals = mutableListOf<Pair<RefreshIntent, Long>>()
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
            onLossReapprovalScheduled = { intent, binding -> reapprovals += intent to binding }
        )
        return Harness(store, source, purger, coordinator, reapprovals)
    }

    /** A bound owner with a confirmed grant and KRX visible, and markers standing so a repeat loss asks to rotate again. */
    private suspend fun TestScope.granted(purge: PurgeResult = PurgeResult.Completed): Harness {
        val h = build(purge)
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 1L)).applied()
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
        h.store.record = h.store.record.copy(mayContainPremiumData = true, mayContainKrxData = true)
        return h
    }

    /**
     * Runs what falls due within a long but bounded stretch of virtual time. Not advanceUntilIdle: a recovery that never
     * settles — which is what a mutation of it looks like — must fail an assertion, not hang the run.
     */
    private fun TestScope.settle() {
        advanceTimeBy(SETTLE)
        runCurrent()
    }

    private fun sealTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            processJob.cancel()
        }
    }

    // --- failure is published and sealed, not thrown ------------------------------------------------------------------

    @Test
    fun aRestLossWhoseRotationKeepsFailing_isPublishedSealedAndNeverThrown() = sealTest {
        val h = granted()
        val epoch = h.store.record.userAccessEpoch
        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }

        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()

        assertEquals(PremiumAccessState.FreeConfirmed, h.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        assertEquals(epoch, h.store.record.userAccessEpoch)
        assertEquals("no purge before the rotation lands", 0, h.purger.calls)
        assertNull(h.coordinator.topicGrant())

        val attempts = h.store.rotationAttempts
        advanceTimeBy(RETRY * 7)
        runCurrent()
        assertTrue("recovery keeps retrying", h.store.rotationAttempts > attempts)
        assertEquals(PremiumAccessState.FreeConfirmed, h.coordinator.state.value.state)
    }

    @Test
    fun aRotationThatLandedButThrew_isReadBackAsLanded_andNothingStaysSealed() = sealTest {
        val h = granted()
        h.store.rotationLandsThenThrows = 1
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }

        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        val fetches = h.source.fetches
        settle()
        assertEquals(PremiumAccessState.FreeConfirmed, h.coordinator.state.value.state)
        assertEquals("one rotation, not a retry", 1, h.store.rotationAttempts)
        assertTrue("purged as a landing", h.purger.calls > 0)
        assertEquals("a confirmed landing owes no re-approval", fetches, h.source.fetches)

        h.source.next = { active(krx = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
        assertNotNull(h.coordinator.topicGrant())
    }

    @Test
    fun aCancellationThrownByTheStore_isAnUnknownOutcome_notTheCallersCancellation() = sealTest {
        val h = granted()
        h.store.rotationFailures = 1
        h.store.rotationFailure = { CancellationException("store cancelled") }
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }

        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals(PremiumAccessState.FreeConfirmed, h.coordinator.state.value.state)
        assertNull(h.coordinator.topicGrant())

        settle()
        assertEquals("recovery landed it on the next round", 2, h.store.rotationAttempts)
    }

    @Test
    fun aCallerCancelledDuringTheWrite_stillPublishesTheLossAndSealsBeforeCancellationGoesThrough() = sealTest {
        val h = granted()
        h.store.rotationFailures = Int.MAX_VALUE
        val gate = CompletableDeferred<Unit>()
        h.store.rotationGate = gate
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }

        val refresh = async { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        assertTrue("parked inside the rotation", h.store.rotationParked.isCompleted)
        refresh.cancel()
        runCurrent()
        assertEquals("still deciding under NonCancellable", PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)

        gate.complete(Unit)
        runCurrent()
        assertTrue(refresh.isCancelled)
        assertEquals(PremiumAccessState.FreeConfirmed, h.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        assertNull(h.coordinator.topicGrant())
    }

    @Test
    fun aCallerCancelledWhileItsRotationLands_stillHasThePurgeRunByRecovery() = sealTest {
        val h = granted()
        val gate = CompletableDeferred<Unit>()
        h.store.rotationGate = gate
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }

        val refresh = async { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        assertTrue("parked inside the rotation", h.store.rotationParked.isCompleted)
        refresh.cancel()
        gate.complete(Unit)
        runCurrent()

        assertTrue(refresh.isCancelled)
        assertEquals("the rotation landed", 1, h.store.rotationAttempts)
        assertTrue("the landed rotation's journal was cleaned though the caller went away", h.purger.calls > 0)
        assertTrue(h.store.record.pendingPurges.isEmpty())
    }

    // --- recovery -----------------------------------------------------------------------------------------------------

    @Test
    fun recoveryLandsTheRotationLater_purges_andAsksTheBindingAgain() = sealTest {
        val h = granted()
        // The first recovery round runs straight away; it fails too, so the grant below is decided after a retry.
        h.store.rotationFailures = 2
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        val fetches = h.source.fetches
        assertEquals(0, h.purger.calls)

        h.source.next = { active(krx = true) }
        settle()

        assertEquals(3, h.store.rotationAttempts)
        assertTrue(h.purger.calls > 0)
        assertEquals("one re-approval for the binding", fetches + 1, h.source.fetches)
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
    }

    @Test
    fun aRepeatedLossForTheSameTarget_joinsTheSeal_withoutWritingAgainOrPullingTheRetryForward() = sealTest {
        val h = granted()
        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        val attempts = h.store.rotationAttempts
        val fetches = h.source.fetches

        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals("the repeats were really decided", fetches + 2, h.source.fetches)
        assertEquals(attempts, h.store.rotationAttempts)
    }

    @Test
    fun aPurgeThatFailsAfterALanding_isRetriedWithoutRotatingAgain_andEndsWhenItCompletes() = sealTest {
        val h = granted(purge = PurgeResult.Failed(IOException("purge")))
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        val calls = h.purger.calls
        assertTrue(calls > 0)

        advanceTimeBy(RETRY + 1)
        runCurrent()
        assertTrue("retried", h.purger.calls > calls)
        assertEquals(1, h.store.rotationAttempts)

        h.purger.result = PurgeResult.Completed
        settle()
        assertEquals(1, h.store.rotationAttempts)
    }

    @Test
    fun aPurgeThatThrowsAfterALanding_doesNotEscape_andIsRetried() = sealTest {
        val h = granted()
        h.purger.throwNext = true
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        // Returns normally although the purge threw inside it.
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        // Read before running anything else: recovery's first round is due at once.
        assertEquals("the purge that threw", 1, h.purger.calls)
        settle()
        assertTrue("retried by recovery", h.purger.calls > 1)
        assertEquals(1, h.store.rotationAttempts)
    }

    @Test
    fun aDeferredPurgeIsAHandOver_notRetried() = sealTest {
        val h = granted(purge = PurgeResult.Deferred("not owned by this slice"))
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        val calls = h.purger.calls
        advanceTimeBy(RETRY * 100)
        runCurrent()
        assertEquals(calls, h.purger.calls)
    }

    // --- recovery keeps its ownership through failures (implementation review) ----------------------------------------

    @Test
    fun aCancellationThrownByTheStoreInsideRecovery_isAFailedRound_notTheEndOfRecovery() = sealTest {
        val h = granted()
        h.store.rotationFailures = 2
        h.store.rotationFailure = { CancellationException("store cancelled") }
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals("the first recovery round failed too", 2, h.store.rotationAttempts)

        h.source.next = { active(krx = true) }
        settle()
        assertEquals("a later round still ran", 3, h.store.rotationAttempts)
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
    }

    @Test
    fun aRecoveryRotationThatLandsThenThrows_stillHandsItsJournalToCleanup() = sealTest {
        val h = granted()
        h.store.rotationFailures = 1
        h.store.rotationLandsThenThrows = 1
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        assertEquals("nothing purged before the rotation lands", 0, h.purger.calls)
        // The next query parks, so no later decision can issue the purge in recovery's place.
        h.source.gate = CompletableDeferred()
        runCurrent()
        advanceTimeBy(RETRY)
        runCurrent()
        assertEquals(2, h.store.rotationAttempts)
        assertTrue("recovery handed the landed rotation's journal to the purgers", h.purger.calls > 0)
    }

    @Test
    fun aJournalRestorationThatLandsThenThrows_stillHandsTheEntryToCleanup() = sealTest {
        val h = granted()
        h.store.rotationFailures = 1
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        // Before recovery's first round, so no rotation is attempted: the one KRX namespace leaves the record with nothing
        // journalled, and restoring its entry is the only write.
        h.store.record = h.store.record.copy(krxCapabilityEpoch = "moved-k")
        h.store.journalRetiredLandsThenThrows = 1
        // The next query parks, so no later decision can issue the purge in recovery's place.
        h.source.gate = CompletableDeferred()
        val calls = h.purger.calls
        runCurrent()
        assertEquals("restored, then threw", 1, h.store.journalRetiredCalls)
        advanceTimeBy(RETRY)
        runCurrent()
        assertEquals("never rotated again", 1, h.store.rotationAttempts)
        assertTrue("recovery cleaned the restored entry", h.purger.calls > calls)
        assertTrue(h.store.record.pendingPurges.isEmpty())
    }

    @Test
    fun aFirstRotationThatLandedButCouldNotBeReadBack_isStillCleanedUp() = sealTest {
        val h = granted()
        h.store.rotationLandsThenThrows = 1
        h.store.failTheReadBackOfALandedThrow = true
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        assertEquals(1, h.store.rotationAttempts)
        assertEquals(0, h.purger.calls)
        // The next query parks, so no later decision can issue the purge in recovery's place.
        h.source.gate = CompletableDeferred()
        runCurrent()
        assertEquals("a confirmed read found it landed; no second rotation", 1, h.store.rotationAttempts)
        assertTrue("recovery cleaned up", h.purger.calls > 0)
        assertTrue(h.store.record.pendingPurges.isEmpty())
    }

    @Test
    fun anIdentityBindThatRetiresTheSealButWhosePurgeFails_leavesTheCleanupToRecovery() = sealTest {
        val h = granted(purge = PurgeResult.Failed(IOException("purge")))
        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)

        // Before recovery's first round, so no rotation attempt of its own has marked the cleanup owed; and the next query
        // parks, so no decision for the new owner can issue the purge in recovery's place.
        h.source.identity = EntitlementsIdentity(OTHER, 1L)
        h.source.gate = CompletableDeferred()
        h.coordinator.onIdentityChanged(AuthIdentityFence(OTHER, 1L)).applied("다른 UID bind, 정리 실패로도 완료")
        val calls = h.purger.calls
        runCurrent()
        advanceTimeBy(RETRY * 20)
        runCurrent()
        assertTrue("recovery kept retrying the cleanup the identity task completed on", h.purger.calls > calls)
    }

    @Test
    fun aPurgeThatKeepsThrowingAfterRecoveryLands_doesNotBlockTheReapproval() = sealTest {
        val h = granted()
        h.store.rotationFailures = 2
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        val fetches = h.source.fetches
        h.purger.throwAlways = true
        h.source.next = { active(krx = true) }

        advanceTimeBy(RETRY * 10)
        runCurrent()
        assertEquals(3, h.store.rotationAttempts)
        assertEquals("asked again although the purge keeps throwing", fetches + 1, h.source.fetches)
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
    }

    @Test
    fun aRepeatedPurgeOnlyKrxFalseWhileTheCleanupIsRetrying_doesNotRunThePurgeEarly() = sealTest {
        val h = granted(purge = PurgeResult.Failed(IOException("purge")))
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals("the KRX rotation landed", 1, h.store.rotationAttempts)
        val calls = h.purger.calls
        assertTrue(calls > 0)

        // Repeats: not an edge any more, but the capability journal is still owed, so each asks for a purge.
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        assertEquals("no purge ahead of recovery's deadline", calls, h.purger.calls)
        assertEquals(1, h.store.rotationAttempts)

        advanceTimeBy(RETRY - 1)
        runCurrent()
        assertEquals("the deadline was not pulled forward", calls, h.purger.calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("recovery retries at its own deadline", calls + 1, h.purger.calls)
    }

    @Test
    fun aSealRecoveryGaveUpOn_isPickedUpAgainWhenAConfirmedRecordMakesItActionable() = sealTest {
        val h = granted()
        h.store.record = h.store.record.copy(krxCapabilityEpoch = null)
        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        h.store.record = AccessEpochTransitions.ensureNamespace(h.store.record, EpochIdGenerator { "allocated" })
        h.store.rotationFailures = 0
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        val attempts = h.store.rotationAttempts

        // An unknown entry for the axis now covers what the missing epoch left behind: a rotation can release the seal.
        h.store.record = h.store.record.copy(
            pendingPurges = listOf(PendingPurge(null, null, null, AccessEpochTransitions.ALL_SCOPES))
        )
        h.source.next = { active(krx = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        assertTrue("recovery restarted on the new evidence", h.store.rotationAttempts > attempts)
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
    }

    @Test
    fun aPremiumLossWhileKrxIsOwed_rotatesOnlyTheNewAxisAtFirst() = sealTest {
        val h = granted()
        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        val krxEpoch = h.store.record.krxCapabilityEpoch
        val grant = checkNotNull(h.coordinator.topicGrant()).grant
        h.store.rotationRequests.clear()

        h.coordinator.onTopicRejected(grant, listOf(com.jay.fxi.domain.model.TopicRejectionReason.PREMIUM_REQUIRED))
        assertEquals("the owned KRX target joins; only the user axis is tried", listOf(true to false), h.store.rotationRequests)
        assertEquals(krxEpoch, h.store.record.krxCapabilityEpoch)
    }

    // --- what a seal holds back ---------------------------------------------------------------------------------------

    @Test
    fun aUserSeal_suppressesAGrantAndHidesKrx() = sealTest {
        val h = granted()
        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()

        h.source.next = { active(krx = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals(PremiumAccessState.FreeConfirmed, h.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        assertNull(h.coordinator.topicGrant())
    }

    @Test
    fun aKrxOnlySeal_hidesKrxAndLeavesPremium() = sealTest {
        val h = granted()
        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        assertNotNull("a KRX seal does not take the premium grant", h.coordinator.topicGrant())

        h.source.next = { active(krx = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
    }

    @Test
    fun aPremiumRefusalWhileKrxIsSealed_widensToTheUserAxis() = sealTest {
        val h = granted()
        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        val grant = checkNotNull(h.coordinator.topicGrant()).grant

        h.coordinator.onTopicRejected(grant, listOf(com.jay.fxi.domain.model.TopicRejectionReason.PREMIUM_REQUIRED))
        runCurrent()
        assertEquals(PremiumAccessState.Rejected, h.coordinator.state.value.state)
        assertNull(h.coordinator.topicGrant())
    }

    @Test
    fun aRecordWithNoUserEpoch_grantsNothing_untilOneIsAllocated() = sealTest {
        val h = build()
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 1L)).applied()
        h.store.record = h.store.record.copy(userAccessEpoch = null)

        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals(PremiumAccessState.NoGrant, h.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        assertNull(h.coordinator.topicGrant())

        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 2L)).applied()
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
    }

    @Test
    fun aGrantStandingWhenAConfirmedRecordLosesItsUserEpoch_issuesNoTopicGrant() = sealTest {
        val h = granted()
        assertNotNull(h.coordinator.topicGrant())
        h.store.record = h.store.record.copy(userAccessEpoch = null)
        assertNull("checked on the record topicGrant itself confirmed", h.coordinator.topicGrant())
    }

    @Test
    fun anObligationWhoseEpochLeftTheRecordWithNothingJournalled_hasItsEntryRestored_andThenResolves() = sealTest {
        // Deferred keeps the restored entries in the journal where the test can read them; a completing purger clears them.
        val h = granted(purge = PurgeResult.Deferred("kept for the assertion"))
        val lostUser = h.store.record.userAccessEpoch
        val lostKrx = h.store.record.krxCapabilityEpoch
        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()

        // The record lost its journal and got new epochs some other way: nothing shows the old namespace handed on.
        h.store.record = AccessEpochRecord(ownerUid = OWNER, userAccessEpoch = "moved-u", krxCapabilityEpoch = "moved-k")
        h.store.rotationFailures = 0
        val attempts = h.store.rotationAttempts
        h.source.next = { active(krx = true) }
        settle()

        assertEquals(2, h.store.journalRetiredCalls)
        assertEquals("restored, not rotated", attempts, h.store.rotationAttempts)
        assertTrue("handed to the purgers", h.purger.calls > 0)
        assertTrue(h.store.record.pendingPurges.contains(PendingPurge(OWNER, lostUser, null, setOf(PurgeScope.USER))))
        assertTrue(h.store.record.pendingPurges.contains(PendingPurge(OWNER, null, lostKrx, setOf(PurgeScope.CAPABILITY))))
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
    }

    // --- identity -----------------------------------------------------------------------------------------------------

    @Test
    fun aSameUidRebindWhileSealed_isOwedTheReapproval_whenRecoveryLands() = sealTest {
        val h = granted()
        h.store.rotationFailures = 3
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()

        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 2L)).applied()
        h.source.next = { active(krx = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals("the new binding's first answer is held back", PremiumAccessState.NoGrant, h.coordinator.state.value.state)

        settle()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
    }

    /**
     * The new binding is owed a re-approval from when the bind completes, not from when a clamp happens: here its first
     * answer never reaches a clamp, because recovery lands first and the answer is discarded as stale.
     *
     * The answer in flight is a FORCE_ENTITLEMENTS one. With FORCE_PREMIUM in flight the re-approval would meet the
     * single-flight token and not run — the limit left to S1r-2a, not what this test is about.
     */
    @Test
    fun aFirstAnswerDiscardedAsStaleAfterRecoveryLands_isStillFollowedByTheReapproval() = sealTest {
        val h = granted()
        h.store.rotationFailures = 2
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()

        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 2L)).applied()
        h.source.next = { active(krx = true) }
        val gate = CompletableDeferred<Unit>()
        h.source.gate = gate
        val first = async { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()
        val fetches = h.source.fetches

        advanceTimeBy(RETRY + 1)
        runCurrent()
        assertEquals("recovery rotated while the first answer was in flight", 3, h.store.rotationAttempts)
        settle()
        assertEquals("the re-approval ran", fetches + 1, h.source.fetches)
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)

        gate.complete(Unit)
        first.await()
        settle()
        assertEquals("the stale first answer changed nothing", PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
    }

    @Test
    fun anotherUidBindRetiresTheSeal_andTheNewOwnerIsNotHeldBack() = sealTest {
        val h = granted()
        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        val fetches = h.source.fetches

        h.source.identity = EntitlementsIdentity(OTHER, 1L)
        h.coordinator.onIdentityChanged(AuthIdentityFence(OTHER, 1L)).applied()
        runCurrent()
        assertEquals("no re-approval is handed to another owner", fetches, h.source.fetches)
        h.source.next = { active(krx = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
    }

    @Test
    fun aSignOutRetiresTheSeal_soTheNextBindingOfTheSameUidIsNotHeldBack() = sealTest {
        val h = granted()
        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()

        h.coordinator.onSignedOut(AuthIdentityFence(OWNER, 1L)).applied("로그아웃")
        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 2L)).applied()
        h.source.next = { active(krx = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
    }

    @Test
    fun recoveryWritesNothingWhileAnIdentityEditIsHeld_andResumesWhenItClears() = sealTest {
        val h = granted()
        h.store.rotationFailures = 2
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()

        h.store.bindLandsThenThrows = 1
        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        val held = h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 2L)).heldByPersistence()
        val attempts = h.store.rotationAttempts
        advanceTimeBy(RETRY * 20)
        runCurrent()
        assertEquals("no loss write while the identity edit is unresolved", attempts, h.store.rotationAttempts)

        assertTrue(checkNotNull(held.nextAttemptAt) <= testScheduler.currentTime)
        h.coordinator.resumePersistence(held.id).applied("보류 해소")
        settle()
        assertTrue("resumed once admitted", h.store.rotationAttempts > attempts)
    }

    // --- null targets -------------------------------------------------------------------------------------------------

    @Test
    fun aKrxLossOnAMissingEpoch_isReleasedByARotationThatLands() = sealTest {
        val h = granted()
        h.store.record = h.store.record.copy(krxCapabilityEpoch = null)
        h.store.rotationFailures = 2
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)

        h.source.next = { active(krx = true) }
        settle()
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        assertNotEquals(null, h.store.record.krxCapabilityEpoch)
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
    }

    @Test
    fun aKrxLossOnAMissingEpoch_staysSealedAfterAllocationAlone_andIsNotRotatedOnATimer() = sealTest {
        val h = granted()
        h.store.record = h.store.record.copy(krxCapabilityEpoch = null)
        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()

        // Allocation fills the epoch without journalling the namespace that had none.
        h.store.record = AccessEpochTransitions.ensureNamespace(h.store.record, EpochIdGenerator { "allocated" })
        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 2L)).applied()
        h.store.rotationFailures = 0
        val attempts = h.store.rotationAttempts
        h.source.next = { active(krx = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceTimeBy(RETRY * 50)
        runCurrent()
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        assertEquals("nothing a rotation could release", attempts, h.store.rotationAttempts)
    }

    @Test
    fun anOldOwnersNullSeal_neverRotatesTheNextOwnersNamespace() = sealTest {
        // Deferred keeps the bind's journal entry for the old owner, so the owner check is the only thing between the
        // old seal and a rotation of the new owner's namespace.
        val h = granted(purge = PurgeResult.Deferred("keeps the old owner's entry"))
        h.store.record = h.store.record.copy(krxCapabilityEpoch = null)
        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()

        // The retirement lands and throws, and is resolved by a read-back: no operation-bound evidence for the seal.
        h.store.bindLandsThenThrows = 1
        h.source.identity = EntitlementsIdentity(OTHER, 1L)
        val held = h.coordinator.onIdentityChanged(AuthIdentityFence(OTHER, 1L)).heldByPersistence()
        advanceTimeBy((checkNotNull(held.nextAttemptAt) - testScheduler.currentTime).coerceAtLeast(0))
        h.coordinator.resumePersistence(held.id).applied("다른 UID bind 되읽기 해소")
        h.store.rotationFailures = 0
        val epoch = h.store.record.krxCapabilityEpoch
        val attempts = h.store.rotationAttempts

        advanceTimeBy(RETRY * 50)
        runCurrent()
        assertEquals(OTHER, h.store.record.ownerUid)
        assertTrue("the premise: an unknown entry for the old owner's axis is still journalled",
            h.store.record.pendingPurges.any { it.ownerUid == OWNER && PurgeScope.CAPABILITY in it.scopes && it.krxCapabilityEpoch == null })
        assertEquals("no rotation for the old owner's seal", attempts, h.store.rotationAttempts)
        assertEquals(epoch, h.store.record.krxCapabilityEpoch)
    }

    @Test
    fun aRepeatedLossBeforeTheFirstRecoveryRound_doesNotPurgeAnOlderDeferredJournal() = sealTest {
        val h = granted(purge = PurgeResult.Deferred("handed over"))
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        assertTrue(h.store.record.pendingPurges.isNotEmpty())

        h.source.next = { active(krx = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)

        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        val calls = h.purger.calls
        val attempts = h.store.rotationAttempts
        val fetches = h.source.fetches

        // Recovery has been queued but has not run.
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)

        assertEquals(fetches + 1, h.source.fetches)
        assertEquals(attempts, h.store.rotationAttempts)
        assertEquals("the repeat must leave cleanup with recovery", calls, h.purger.calls)
    }

    @Test
    fun aBarrierBindRegistersASealLeftByASignOutThatLandedThenThrew() = sealTest {
        val h = granted(purge = PurgeResult.Deferred("retain the unknown journal"))
        h.store.record = h.store.record.copy(krxCapabilityEpoch = null)
        h.store.rotationFailures = Int.MAX_VALUE
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()

        val original = AuthIdentityFence(OWNER, 1L)
        h.source.liveFence = original
        val ticket = (h.coordinator.prepareSignOut(original) as SignOutStart.Armed).ticket
        assertTrue(h.coordinator.stopDriver(ticket))

        h.store.signOutLandsThenThrows = true
        assertEquals(RecoveryAdvance.UNRESOLVED, h.coordinator.advanceRecovery(ticket))
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))

        val rebound = AuthIdentityFence(OWNER, 2L)
        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        h.source.liveFence = rebound
        assertEquals(BarrierStep.RELEASED, h.coordinator.completeRecovery(ticket, rebound).step)
        assertTrue(h.store.record.pendingPurges.any {
            it.ownerUid == OWNER &&
                PurgeScope.CAPABILITY in it.scopes &&
                it.krxCapabilityEpoch == null
        })
        val beforeRecovery = h.store.record.krxCapabilityEpoch

        h.store.rotationFailures = 0
        h.source.next = { active(krx = true) }
        advanceTimeBy(RETRY)
        runCurrent()

        assertNotEquals(beforeRecovery, h.store.record.krxCapabilityEpoch)
        assertEquals(
            listOf(RefreshIntent.FORCE_ENTITLEMENTS),
            h.reapprovals.map { it.first }
        )
    }

    @Test
    fun aForcePremiumInFlightTakesTheLossReapproval_andWhenItsAnswerIsStaleTheReapprovalRuns() = sealTest {
        val h = granted()
        h.store.rotationFailures = 2
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()

        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 2L)).applied()
        h.source.next = { active(krx = true) }
        val gate = CompletableDeferred<Unit>()
        h.source.gate = gate
        val capturedEpoch = h.store.record.userAccessEpoch
        val first = async { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        assertTrue("the first FP is still in flight", !first.isCompleted)

        advanceTimeBy(RETRY)
        runCurrent()

        assertNotEquals(capturedEpoch, h.store.record.userAccessEpoch)
        assertTrue("the old FP has not answered yet", !first.isCompleted)
        // S1r-2a §2.5: a premium query still running answers for the demand first, so nothing is armed or reported yet.
        assertEquals(emptyList<Pair<RefreshIntent, Long>>(), h.reapprovals)
        val fetches = h.source.fetches

        // Its captured namespace is now stale: its end arms the re-approval, which then runs.
        gate.complete(Unit)
        first.await()
        runCurrent()
        assertEquals(listOf(RefreshIntent.FORCE_PREMIUM), h.reapprovals.map { it.first })
        settle()
        assertEquals("the re-approval ran once", fetches + 1, h.source.fetches)
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
    }

    @Test
    fun aScheduledPremiumQueryBeforeLossReapproval_rearmsAfterItsOwnStaleAnswer() = sealTest {
        val h = granted()
        h.store.rotationFailures = 2
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()

        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 2L)).applied()
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 0) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)

        val gate = CompletableDeferred<Unit>()
        h.source.gate = gate
        h.source.next = { active(krx = true) }
        val capturedEpoch = h.store.record.userAccessEpoch
        val beforeScheduled = h.source.fetches
        runCurrent()
        assertEquals(beforeScheduled + 1, h.source.fetches)
        assertTrue(h.source.freshRequests.last())

        advanceTimeBy(RETRY)
        runCurrent()
        assertNotEquals(capturedEpoch, h.store.record.userAccessEpoch)
        assertEquals(emptyList<Pair<RefreshIntent, Long>>(), h.reapprovals)
        val total = h.source.fetches

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(RefreshIntent.FORCE_PREMIUM), h.reapprovals.map { it.first })
        settle()

        assertEquals(total + 1, h.source.fetches)
        assertTrue(h.source.freshRequests.last())
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
    }

    @Test
    fun lossReapprovalWhileAuthStopped_upgradesTheIntentUsedByTheExistingEntitlementsTimer() = sealTest {
        val h = granted()
        h.store.rotationFailures = 2
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()

        val retryGate = CompletableDeferred<Unit>()
        h.source.gate = retryGate
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 60) }
        val retry = async { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()

        val authGate = CompletableDeferred<Unit>()
        h.source.gate = authGate
        h.source.next = { EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION) }
        val auth = async { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()

        retryGate.complete(Unit)
        retry.await()
        authGate.complete(Unit)
        auth.await()
        val total = h.source.fetches

        advanceTimeBy(RETRY)
        runCurrent()
        assertEquals(total, h.source.fetches)
        assertEquals(emptyList<Pair<RefreshIntent, Long>>(), h.reapprovals)

        h.source.next = { active(krx = true) }
        advanceTimeBy(60_000L - RETRY)
        runCurrent()

        assertEquals(total + 1, h.source.fetches)
        assertTrue("the existing FE timer executes the current FP demand", h.source.freshRequests.last())
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
    }

    @Test
    fun aQueryAfterRotationButBeforeReapproval_doesNotAnswerTheLaterIndependentDemand() = sealTest {
        val h = granted()
        h.store.rotationLandsThenThrows = 1
        h.store.failTheReadBackOfALandedThrow = true
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)

        h.store.loadFailures = 1
        runCurrent()

        h.source.next = { EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 0) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        val landedEpoch = h.store.record.userAccessEpoch

        val gate = CompletableDeferred<Unit>()
        h.source.gate = gate
        h.source.next = { active(krx = true) }
        val first = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        }
        runCurrent()
        assertTrue(!first.isCompleted)

        advanceTimeBy(RETRY)
        runCurrent()
        assertEquals("the query already captured the landed namespace", landedEpoch, h.store.record.userAccessEpoch)
        assertEquals(emptyList<Pair<RefreshIntent, Long>>(), h.reapprovals)
        val total = h.source.fetches

        gate.complete(Unit)
        first.await()
        runCurrent()
        assertEquals(listOf(RefreshIntent.FORCE_PREMIUM), h.reapprovals.map { it.first })
        settle()

        assertEquals("the later independent demand still executes", total + 1, h.source.fetches)
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
    }

    private fun active(krx: Boolean) = EntitlementsOutcome.StableActive(krxVisible = krx)

    private companion object {
        const val OWNER = "user-a"
        const val OTHER = "user-b"
        const val RETRY = 1_000L

        /** Longer than any settling recovery here needs: its delays start at RETRY and are capped at five minutes. */
        const val SETTLE = 60 * 60 * 1_000L
    }
}
