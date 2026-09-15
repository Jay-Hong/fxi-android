package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.service.pushRegistrationAllowed
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1r-2c: a loss answer whose record cannot be read holds access back without deciding anything, and candidate recovery
 * applies or discards it once the record reads (`s1r2c_design_v3.md`).
 *
 * The decision read is made to fail from inside the fetch, after the query's own record read has returned. Some stores keep
 * failing, so each test cancels the coordinator's scope before it ends and advances time explicitly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PremiumAccessLossCandidateTest {

    private val processJob = SupervisorJob()

    private class Store(private val ids: EpochIdGenerator) : AccessEpochStore {
        var record = AccessEpochRecord()
        var loads = 0

        /** The next this many loads throw, after [loadGate]. */
        var loadFailures = 0

        /** The load with this ordinal (counted by [loads]) throws, whatever runs before it. */
        var failLoadNumber: Int? = null

        /** Parks the next load before it fails or returns, so its caller can be cancelled inside it. */
        var loadGate: CompletableDeferred<Unit>? = null
        val loadParked = CompletableDeferred<Unit>()

        /** Parks the next rotation before it writes. */
        var rotationGate: CompletableDeferred<Unit>? = null
        val rotationParked = CompletableDeferred<Unit>()
        val rotationRequests = mutableListOf<Pair<Boolean, Boolean>>()

        override suspend fun load(): AccessEpochRecord {
            loads += 1
            val number = loads
            loadGate?.let { gate ->
                loadGate = null
                loadParked.complete(Unit)
                gate.await()
            }
            if (number == failLoadNumber) {
                failLoadNumber = null
                throw IOException("load $number")
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
            rotationRequests += rotateUser to rotateKrx
            rotationGate?.let { gate ->
                rotationGate = null
                rotationParked.complete(Unit)
                gate.await()
            }
            return AccessEpochTransitions.rotate(record, rotateUser, rotateKrx, ids).also { record = it }
        }
        override suspend fun completePurges(completed: Collection<PendingPurge>) =
            AccessEpochTransitions.completePurges(record, completed).also { record = it }
        override suspend fun journalRetired(obligation: LossObligation) =
            AccessEpochTransitions.journalRetired(record, obligation).also { record = it }
        override suspend fun markMayContainData(premium: Boolean, krx: Boolean) =
            AccessEpochTransitions.markMayContainData(record, premium, krx).also { record = it }
    }

    private class Purger : UserScopePurger, CapabilityScopePurger {
        var calls = 0
        override suspend fun purgeUserScope(namespace: PurgeNamespace) = PurgeResult.Completed.also { calls += 1 }
        override suspend fun purgeCapabilityScope(namespace: PurgeNamespace) = PurgeResult.Completed.also { calls += 1 }
    }

    private class Source(var next: () -> EntitlementsOutcome) : EntitlementsSource {
        /** The transport's session: what an answer comes back as, and what a live identity read returns. */
        var identity: EntitlementsIdentity? = EntitlementsIdentity(OWNER, 1L)

        /** When set, answers come back as this instead of [identity]. */
        var answeredAs: EntitlementsIdentity? = null
        var liveFence: AuthIdentityFence? = null
        var fetches = 0
        var gate: CompletableDeferred<Unit>? = null

        /** Runs once, after the answer is formed and before it returns: where the decision read is made to fail. */
        var afterFetch: () -> Unit = {}

        override suspend fun fetch(freshPremium: Boolean): EntitlementsResult {
            fetches += 1
            val outcome = next()
            val owner = answeredAs ?: checkNotNull(identity)
            gate?.let { g -> gate = null; g.await() }
            afterFetch().also { afterFetch = {} }
            return EntitlementsResult.Answered(owner, outcome)
        }
        override suspend fun currentIdentity() = identity
    }

    private class Harness(
        val store: Store,
        val source: Source,
        val purger: Purger,
        val coordinator: PremiumAccessCoordinator
    )

    private fun ids(): EpochIdGenerator {
        var n = 0
        return EpochIdGenerator { "epoch-${n++}" }
    }

    private fun TestScope.build(): Harness {
        val store = Store(ids())
        val source = Source { active(krx = true) }
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
            orders = AccessOrderSequence()
        )
        return Harness(store, source, purger, coordinator)
    }

    /** A bound owner with a confirmed grant and KRX visible, and markers standing so a loss asks to rotate. */
    private suspend fun TestScope.granted(): Harness {
        val h = build()
        h.coordinator.onIdentityChanged(AuthIdentityFence(OWNER, 1L)).applied()
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
        h.store.record = h.store.record.copy(mayContainPremiumData = true, mayContainKrxData = true)
        return h
    }

    /** Every value [PremiumAccessCoordinator.krx] publishes from now on, in order. */
    private fun TestScope.publishedKrx(h: Harness): List<KrxCapabilityState> {
        val seen = mutableListOf<KrxCapabilityState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.coordinator.krx.collect { seen += it } }
        return seen
    }

    /** Every value [PremiumAccessCoordinator.state] publishes from now on, in order. */
    private fun TestScope.published(h: Harness): List<OwnedPremiumAccess> {
        val seen = mutableListOf<OwnedPremiumAccess>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.coordinator.state.collect { seen += it } }
        return seen
    }

    /**
     * Asserted right after the answer was handled and before recovery has run: recovery's own checks would discard a hold
     * made by mistake a moment later, so an assertion after settling cannot tell "never held" from "held, then released".
     */
    private suspend fun Harness.assertNotHeldBeforeRecoveryRuns(what: String) {
        assertEquals("$what: held", 0, coordinator.heldLossCandidateCount())
    }

    private fun Harness.failTheDecisionRead(times: Int = 1) {
        source.afterFetch = { store.loadFailures = times }
    }

    private fun TestScope.settle() {
        advanceTimeBy(SETTLE)
        runCurrent()
    }

    private fun candidateTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            processJob.cancel()
        }
    }

    // --- the hold ---------------------------------------------------------------------------------------------------

    @Test
    fun aPremiumLossWhoseRecordCannotBeRead_withdrawsTheGrantAtOnce_andDecidesNothing() = candidateTest {
        val h = granted()
        val record = h.store.record
        val effects = h.coordinator.lastEffects.value
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.source.afterFetch = { h.store.loadFailures = Int.MAX_VALUE }

        assertTrue("a held read failure is not thrown", runCatching { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }.isSuccess)

        val shown = h.coordinator.state.value
        assertEquals(PremiumAccessState.NoGrant, shown.state)
        assertEquals("the owner stays the one the decision was made for", OWNER, shown.uid)
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        assertNull(h.coordinator.topicGrant())
        assertFalse(pushRegistrationAllowed(AuthIdentityFence(OWNER, 1L), shown, shouldRegisterForPush = true))
        assertEquals(1, h.coordinator.heldLossCandidateCount())
        assertEquals("no rotation, no epoch change", record, h.store.record)
        assertTrue(h.store.rotationRequests.isEmpty())
        assertEquals(0, h.purger.calls)
        assertEquals(effects, h.coordinator.lastEffects.value)

        advanceTimeBy(RETRY * 20)
        runCurrent()
        assertEquals("the hold stands while the record stays unreadable", PremiumAccessState.NoGrant, h.coordinator.state.value.state)
        assertEquals(1, h.coordinator.heldLossCandidateCount())
    }

    @Test
    fun aKrxLossWhoseRecordCannotBeRead_hidesKrxOnly() = candidateTest {
        val h = granted()
        h.source.next = { active(krx = false) }
        h.failTheDecisionRead(times = 2) // and recovery's first read

        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()

        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        assertNotNull("a capability hold leaves the premium session", h.coordinator.topicGrant())
        assertEquals(1, h.coordinator.heldLossCandidateCount())
        assertTrue(h.store.rotationRequests.isEmpty())

        advanceTimeBy(RETRY)
        runCurrent()
        assertEquals(listOf(false to true), h.store.rotationRequests)
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        assertEquals(0, h.coordinator.heldLossCandidateCount())
    }

    // --- known stale without the record -----------------------------------------------------------------------------

    @Test
    fun aLiveSessionKnownToBeSomeoneElse_discardsTheAnswerWithoutAHold() = candidateTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.source.liveFence = AuthIdentityFence(OWNER, 2L)
        h.failTheDecisionRead()

        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        h.assertNotHeldBeforeRecoveryRuns("another live session")
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        settle()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals(0, h.coordinator.heldLossCandidateCount())
        assertTrue(h.store.rotationRequests.isEmpty())
    }

    @Test
    fun anAnswerForAnotherOwnerThanTheQuerysNamespace_isDiscardedWithoutAHold() = candidateTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.source.answeredAs = EntitlementsIdentity(OTHER, 1L)
        h.failTheDecisionRead()

        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        h.assertNotHeldBeforeRecoveryRuns("another owner")
        settle()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals(0, h.coordinator.heldLossCandidateCount())
    }

    @Test
    fun anAnswerBeforeAnyBinding_forAnotherOwnerThanTheRecord_isDiscardedWithoutAHold() = candidateTest {
        // Nothing is bound yet, so there is no binding session to compare: only the owner of the namespace the query read
        // tells this answer is somebody else's.
        val h = build()
        h.store.record = AccessEpochTransitions.bindOwner(h.store.record, OTHER, ids())
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.failTheDecisionRead()

        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        assertEquals("the query ran", 1, h.source.fetches)
        assertEquals("and its decision read failed", 0, h.store.loadFailures)
        h.assertNotHeldBeforeRecoveryRuns("an unbound answer for another owner than the record")
    }

    @Test
    fun anAnswerForAnotherSessionThanTheBinding_isDiscardedWithoutAHold() = candidateTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.source.answeredAs = EntitlementsIdentity(OWNER, 2L)
        h.failTheDecisionRead()

        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        h.assertNotHeldBeforeRecoveryRuns("another session than the binding")
        settle()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals(0, h.coordinator.heldLossCandidateCount())
    }

    @Test
    fun anAnswerFromBeforeAnIdentityChange_isDiscardedWithoutAHold() = candidateTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        val gate = CompletableDeferred<Unit>()
        h.source.gate = gate
        // Runs once the query below parks in its fetch; the query runs in the test body, so nothing else runs after it returns.
        val boundary = async {
            h.coordinator.onIdentityChanged(AuthIdentityFence(OTHER, 1L)).applied()
            h.failTheDecisionRead()
            gate.complete(Unit)
        }

        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        assertTrue(boundary.isCompleted)
        h.assertNotHeldBeforeRecoveryRuns("an answer from before the identity change")
    }

    // --- recovery ---------------------------------------------------------------------------------------------------

    @Test
    fun recoveryAppliesAHeldLoss_andTheGrantIsNeverPublishedInBetween() = candidateTest {
        val h = granted()
        val seen = published(h)
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.failTheDecisionRead(times = 2)

        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals(1, h.coordinator.heldLossCandidateCount())
        assertNull("a readable store does not let a held grant issue a topic session", h.coordinator.topicGrant())

        advanceTimeBy(RETRY)
        runCurrent()
        assertEquals(PremiumAccessState.FreeConfirmed, h.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        assertEquals(listOf(true to true), h.store.rotationRequests)
        assertTrue(h.purger.calls > 0)
        assertEquals(0, h.coordinator.heldLossCandidateCount())

        val afterHold = seen.dropWhile { it.state.grantsPremiumRuntime }
        assertTrue("the hold was published", afterHold.isNotEmpty())
        assertTrue("a grant was published after the hold: $seen", afterHold.none { it.state.grantsPremiumRuntime })
    }

    @Test
    fun aLiveIdentityThatCannotBeRead_keepsTheHold_andRetriesOnTheBackoff() = candidateTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.source.afterFetch = {
            h.store.loadFailures = 1
            h.source.identity = null
        }
        h.source.answeredAs = EntitlementsIdentity(OWNER, 1L)

        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        val loads = h.store.loads
        advanceTimeBy(RETRY * 3)
        runCurrent()
        assertEquals(PremiumAccessState.NoGrant, h.coordinator.state.value.state)
        assertEquals(1, h.coordinator.heldLossCandidateCount())
        assertTrue("retried on the backoff, not in a loop: ${h.store.loads - loads}", h.store.loads - loads <= 2)

        h.source.identity = EntitlementsIdentity(OWNER, 1L)
        settle()
        assertEquals(PremiumAccessState.FreeConfirmed, h.coordinator.state.value.state)
        assertEquals(0, h.coordinator.heldLossCandidateCount())
    }

    @Test
    fun aHeldAnswerThatTurnsOutStale_releasesOnlyItsHold_andTheDecisionComesBackUnchanged() = candidateTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.failTheDecisionRead(times = 2)
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals(PremiumAccessState.NoGrant, h.coordinator.state.value.state)

        // The transport has moved to another session of the same owner: the held answer is not for it.
        h.source.identity = EntitlementsIdentity(OWNER, 2L)
        advanceTimeBy(RETRY)
        runCurrent()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
        assertEquals(0, h.coordinator.heldLossCandidateCount())
        assertTrue(h.store.rotationRequests.isEmpty())
    }

    @Test
    fun aLiveSessionKnownToHaveMovedOnWhileHeld_discardsTheCandidateEvenWhereTheTransportStillAnswersAsTheOld() = candidateTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.failTheDecisionRead(times = 2)
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals(1, h.coordinator.heldLossCandidateCount())

        // The auth tracker has moved on; the transport's identity read has not caught up.
        h.source.liveFence = AuthIdentityFence(OWNER, 2L)
        advanceTimeBy(RETRY)
        runCurrent()
        assertEquals(0, h.coordinator.heldLossCandidateCount())
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertTrue("a candidate known stale was applied", h.store.rotationRequests.isEmpty())
    }

    @Test
    fun aHeldRefusalWhoseGrantWasReplaced_isDiscardedEvenWhileTheRecordStaysUnreadable() = candidateTest {
        val h = granted()
        val grant = checkNotNull(h.coordinator.topicGrant()).grant
        h.store.loadFailures = 2 // the refusal's decision read, and recovery's first read
        h.coordinator.onTopicRejected(grant, KRX_REFUSAL)
        runCurrent()
        assertEquals(1, h.coordinator.heldLossCandidateCount())

        // Another answer rotates KRX alone — the decision generation stays — and a new grant is issued for the new context.
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()
        val replacement = checkNotNull(h.coordinator.topicGrant()).grant
        assertTrue(replacement != grant)

        h.store.loadFailures = Int.MAX_VALUE
        advanceTimeBy(RETRY)
        runCurrent()
        assertEquals("the refusal of a replaced grant stayed held for want of a read", 0, h.coordinator.heldLossCandidateCount())
    }

    @Test
    fun eachCandidateIsCheckedAgainstARecordReadForIt_notOneAnEarlierCandidateAlreadyRotated() = candidateTest {
        val h = granted()
        val grant = checkNotNull(h.coordinator.topicGrant()).grant
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        val gate = CompletableDeferred<Unit>()
        h.source.gate = gate
        val query = async { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent() // the premium query has captured the record's fence and is parked in the fetch

        h.store.loadFailures = 2 // the refusal's decision read, and recovery's first read
        h.coordinator.onTopicRejected(grant, KRX_REFUSAL)
        h.failTheDecisionRead()
        gate.complete(Unit)
        query.await()
        runCurrent()
        assertEquals("both are held together", 2, h.coordinator.heldLossCandidateCount())
        assertEquals(PremiumAccessState.NoGrant, h.coordinator.state.value.state)

        // The applied refusal asks for a re-check at once; let that query find premium still active, so a premium rotation
        // can only come from the held premium answer.
        h.source.next = { active(krx = false) }
        val fetches = h.source.fetches
        advanceTimeBy(RETRY)
        runCurrent()
        assertEquals("the applied refusal's re-check ran", fetches + 1, h.source.fetches)
        assertEquals("the older KRX refusal rotated the capability", listOf(false to true), h.store.rotationRequests)
        assertEquals("the premium answer's namespace had moved, so it was stale", PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals("the applied refusal still stands", KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        assertEquals(0, h.coordinator.heldLossCandidateCount())
    }

    @Test
    fun aLaterGrantAnswer_doesNotCancelAnEarlierHeldLoss() = candidateTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.failTheDecisionRead(times = 2)
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()

        h.source.next = { active(krx = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals("the hold still masks the grant the later answer kept", PremiumAccessState.NoGrant, h.coordinator.state.value.state)

        advanceTimeBy(RETRY)
        runCurrent()
        assertEquals(PremiumAccessState.FreeConfirmed, h.coordinator.state.value.state)
        assertEquals(0, h.coordinator.heldLossCandidateCount())
    }

    @Test
    fun aLossWithNothingOnScreenToHide_isStillHeldAndItsCleanupRunsAfterRecovery() = candidateTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        assertEquals(PremiumAccessState.FreeConfirmed, h.coordinator.state.value.state)
        assertEquals(1, h.store.rotationRequests.size)

        // Already free, but the namespace may hold premium data again.
        h.store.record = h.store.record.copy(mayContainPremiumData = true)
        h.failTheDecisionRead(times = 2)
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals(1, h.coordinator.heldLossCandidateCount())
        assertEquals("a hold leaves a state that grants nothing as it is", PremiumAccessState.FreeConfirmed, h.coordinator.state.value.state)
        settle()
        assertEquals(listOf(true to true, true to true), h.store.rotationRequests)
        assertEquals(0, h.coordinator.heldLossCandidateCount())

        // Nothing marked: the candidate is still decided, and asks for no rotation.
        h.failTheDecisionRead()
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        settle()
        assertEquals(2, h.store.rotationRequests.size)
        assertEquals(0, h.coordinator.heldLossCandidateCount())
    }

    @Test
    fun aKrxLossWithKrxAlreadyHidden_isStillHeldAndRotatesAMarkedCapability() = candidateTest {
        val h = granted()
        h.source.next = { active(krx = false) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        settle()
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        val rotations = h.store.rotationRequests.size

        h.store.record = h.store.record.copy(mayContainKrxData = true)
        h.failTheDecisionRead()
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        settle()
        assertEquals(rotations + 1, h.store.rotationRequests.size)
        assertEquals(false to true, h.store.rotationRequests.last())
        assertEquals(0, h.coordinator.heldLossCandidateCount())
    }

    @Test
    fun aSecondCandidateAfterTheFirstResolved_getsItsOwnRecovery() = candidateTest {
        val h = granted()
        h.source.next = { active(krx = false) }
        h.failTheDecisionRead()
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()
        assertEquals(0, h.coordinator.heldLossCandidateCount())

        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.failTheDecisionRead()
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals(PremiumAccessState.FreeConfirmed, h.coordinator.state.value.state)
        assertEquals(0, h.coordinator.heldLossCandidateCount())
    }

    // --- boundaries and cancellation ----------------------------------------------------------------------------------

    @Test
    fun anIdentityBoundary_discardsTheCandidates_withoutPublishingTheEndingGrant() = candidateTest {
        listOf("sign-out", "identity change").forEach { boundary ->
            val h = granted()
            h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
            h.source.afterFetch = { h.store.loadFailures = 1_000 }
            h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
            runCurrent()
            assertEquals(1, h.coordinator.heldLossCandidateCount())
            h.store.loadFailures = 0

            val seen = published(h)
            val seenKrx = publishedKrx(h)
            when (boundary) {
                "sign-out" -> h.coordinator.onSignedOut(AuthIdentityFence(OWNER, 1L)).applied()
                else -> h.coordinator.onIdentityChanged(AuthIdentityFence(OTHER, 1L)).applied()
            }
            runCurrent()
            assertEquals(boundary, 0, h.coordinator.heldLossCandidateCount())
            assertTrue("$boundary published a grant: $seen", seen.none { it.state.grantsPremiumRuntime })
            assertTrue("$boundary showed KRX again: $seenKrx", seenKrx.none { it == KrxCapabilityState.VISIBLE })
            assertEquals(PremiumAccessState.NoGrant, h.coordinator.state.value.state)
        }
    }

    @Test
    fun aHoldBlocksNeitherAnotherQueryNorAnIdentityChange() = candidateTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        h.source.afterFetch = { h.store.loadFailures = 1 }
        h.source.answeredAs = EntitlementsIdentity(OWNER, 1L)
        h.source.identity = null // recovery cannot confirm the session, so the hold stays
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals(1, h.coordinator.heldLossCandidateCount())

        val fetches = h.source.fetches
        h.source.next = { active(krx = true) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals("a query still runs under a hold", fetches + 1, h.source.fetches)

        h.coordinator.onIdentityChanged(AuthIdentityFence(OTHER, 1L)).applied()
        assertEquals(0, h.coordinator.heldLossCandidateCount())
    }

    @Test
    fun aCallerCancelledInsideTheDecisionRead_stillHandsTheCandidateToRecovery() = candidateTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        val gate = CompletableDeferred<Unit>()
        h.source.afterFetch = {
            h.store.loadGate = gate
            h.store.loadFailures = 1 // left for recovery's first read: the cancelled read throws before reaching it
        }
        var ranPastTheRefresh = false
        val query = async {
            h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
            ranPastTheRefresh = true
        }
        runCurrent()
        assertTrue("parked in the decision read", h.store.loadParked.isCompleted)

        query.cancel()
        runCurrent()
        assertTrue(query.isCancelled)
        assertFalse("the caller's cancellation did not go through the refresh", ranPastTheRefresh)
        assertEquals("held before the cancellation went through", PremiumAccessState.NoGrant, h.coordinator.state.value.state)

        gate.complete(Unit)
        settle()
        assertEquals(PremiumAccessState.FreeConfirmed, h.coordinator.state.value.state)
        assertEquals(listOf(true to true), h.store.rotationRequests)
        assertEquals(0, h.coordinator.heldLossCandidateCount())
    }

    @Test
    fun theScopeCancelledWhileRecoveryPublishesTheLoss_leavesTheLossShownAndNoHoldBehind() = candidateTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        val gate = CompletableDeferred<Unit>()
        h.source.afterFetch = {
            h.store.loadFailures = 1
            h.store.rotationGate = gate
        }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertTrue("recovery is inside the loss rotation", h.store.rotationParked.isCompleted)
        assertEquals(PremiumAccessState.NoGrant, h.coordinator.state.value.state)

        processJob.cancel()
        gate.complete(Unit)
        runCurrent()
        assertEquals(PremiumAccessState.FreeConfirmed, h.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        assertEquals(0, h.coordinator.heldLossCandidateCount())
    }

    @Test
    fun anAnswerFromBeforeAnAuthoritativeLoss_isDiscardedWithoutAHold() = candidateTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        val gate = CompletableDeferred<Unit>()
        h.source.gate = gate
        // Runs once the early query below parks in its fetch: a later loss is decided, then the early answer is let through.
        val later = async {
            h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
            assertEquals(PremiumAccessState.FreeConfirmed, h.coordinator.state.value.state)
            h.failTheDecisionRead()
            gate.complete(Unit)
        }

        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertTrue(later.isCompleted)
        h.assertNotHeldBeforeRecoveryRuns("an answer from before the decision generation moved")
    }

    @Test
    fun aRefusalWhoseLiveSessionIsKnownToBeSomeoneElse_isDiscardedWithoutAHold() = candidateTest {
        val h = granted()
        val grant = checkNotNull(h.coordinator.topicGrant()).grant
        h.source.liveFence = AuthIdentityFence(OWNER, 2L)
        h.store.loadFailures = 1
        h.coordinator.onTopicRejected(grant, KRX_REFUSAL)
        h.assertNotHeldBeforeRecoveryRuns("a refusal for another live session")
        assertEquals(KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
    }

    @Test
    fun aCandidateRaisedWhileTheLastRoundIsEnding_stillGetsARecovery() = candidateTest {
        val h = granted()
        h.source.next = { active(krx = false) }
        h.failTheDecisionRead(times = 2)
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals(1, h.coordinator.heldLossCandidateCount())

        // A second query is parked in its fetch, having read the record already.
        h.source.next = { EntitlementsOutcome.StableInactive(krxVisible = false) }
        val fetchGate = CompletableDeferred<Unit>()
        h.source.gate = fetchGate
        val second = async { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()

        // Recovery's next round parks inside the lock, in its read.
        val loadGate = CompletableDeferred<Unit>()
        h.store.loadGate = loadGate
        advanceTimeBy(RETRY)
        runCurrent()
        assertTrue(h.store.loadParked.isCompleted)

        // The second answer now waits for the lock the round holds. Only its decision read fails: after the round's parked
        // read, the first candidate's KRX rotation is purged, and that purge reads the record once before the second answer
        // gets the lock.
        h.store.failLoadNumber = h.store.loads + 2
        fetchGate.complete(Unit)
        runCurrent()
        assertFalse("the second query is waiting for the round's lock", second.isCompleted)

        // The round ends with nothing left; the second answer takes the lock before the ending run lets go of its job.
        loadGate.complete(Unit)
        second.await()
        assertEquals("the round resolved the first candidate", listOf(false to true), h.store.rotationRequests)
        assertTrue("the first candidate's purge ran on a read that did not fail", h.purger.calls > 0)
        assertNull("the second answer's decision read failed", h.store.failLoadNumber)
        settle()
        assertEquals("the later candidate was left with nobody to resolve it", 0, h.coordinator.heldLossCandidateCount())
    }

    @Test
    fun theScopeCancelledWhileRecoveryPublishesARefusal_stillLeavesItsRecheckOwed() = candidateTest {
        val h = granted()
        val grant = checkNotNull(h.coordinator.topicGrant()).grant
        assertNull(h.coordinator.recheckDiagnostics().owedIntent)
        val gate = CompletableDeferred<Unit>()
        h.store.loadFailures = 1
        h.coordinator.onTopicRejected(grant, KRX_REFUSAL)
        h.store.rotationGate = gate
        runCurrent()
        assertTrue("recovery is inside the KRX rotation", h.store.rotationParked.isCompleted)

        processJob.cancel()
        gate.complete(Unit)
        runCurrent()
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        assertEquals(0, h.coordinator.heldLossCandidateCount())
        assertEquals(
            "the refusal's re-check was lost with the cancellation",
            RefreshIntent.FORCE_ENTITLEMENTS,
            h.coordinator.recheckDiagnostics().owedIntent
        )
    }

    // --- floors and demands -------------------------------------------------------------------------------------------

    @Test
    fun aResolvedPendingCandidate_armsItsRecheckForWhatIsLeftOfTheFloor() = candidateTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 60) }
        h.failTheDecisionRead(times = 2)
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()
        val heldAt = testScheduler.currentTime
        val fetches = h.source.fetches

        advanceTimeBy(RETRY)
        runCurrent()
        assertEquals("resolved", 0, h.coordinator.heldLossCandidateCount())

        advanceTimeBy(heldAt + 59_900 - testScheduler.currentTime)
        runCurrent()
        assertEquals(fetches, h.source.fetches)
        advanceTimeBy(heldAt + 60_100 - testScheduler.currentTime)
        runCurrent()
        assertEquals("the recheck fell due at the original floor", fetches + 1, h.source.fetches)
    }

    @Test
    fun aStatedFloorOnAHeldAnswerFiredByTheTimer_holdsBackEveryRequery_andOnlyWhatIsLeftIsAskedForLater() = candidateTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT, retryAfterSeconds = 5) }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()
        val start = testScheduler.currentTime
        val beforeTimer = h.source.fetches

        h.source.next = { EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 60) }
        h.failTheDecisionRead(times = 2)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("the timer's query ran", beforeTimer + 1, h.source.fetches)
        assertEquals(1, h.coordinator.heldLossCandidateCount())
        val heldAt = testScheduler.currentTime

        advanceTimeBy(RETRY)
        runCurrent()
        assertEquals("recovery applied it", 0, h.coordinator.heldLossCandidateCount())
        assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)

        advanceTimeBy(heldAt + 30_000 - testScheduler.currentTime)
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()
        advanceTimeBy(heldAt + 59_900 - testScheduler.currentTime)
        runCurrent()
        assertEquals("nothing inside the stated floor", beforeTimer + 1, h.source.fetches)

        advanceTimeBy(heldAt + 60_100 - testScheduler.currentTime)
        runCurrent()
        assertEquals("the floor from the hold, not restarted by recovery", beforeTimer + 2, h.source.fetches)
        assertTrue(start < heldAt)
    }

    /** Steps time until recovery has released every hold, and returns when that was. */
    private suspend fun TestScope.untilResolved(h: Harness): Long {
        repeat(600) {
            if (h.coordinator.heldLossCandidateCount() == 0) return testScheduler.currentTime
            advanceTimeBy(100)
            runCurrent()
        }
        error("recovery never released the hold")
    }

    @Test
    fun aFloorThatLapsedBeforeRecovery_isNotAskedForAgain() = candidateTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 5) }
        h.source.answeredAs = EntitlementsIdentity(OWNER, 1L)
        h.source.afterFetch = {
            h.store.loadFailures = 1
            h.source.identity = null // recovery cannot confirm the session until the floor has lapsed
        }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()
        val fetches = h.source.fetches

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, h.coordinator.heldLossCandidateCount())
        h.source.identity = EntitlementsIdentity(OWNER, 1L)
        untilResolved(h)
        assertEquals("the lapsed floor, or the default five seconds, was asked for again", fetches + 1, h.source.fetches)
    }

    @Test
    fun aLaterFloorAlreadyRecorded_outlastsWhatIsLeftOfTheCandidatesFloor() = candidateTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 5) }
        val gate = CompletableDeferred<Unit>()
        h.source.gate = gate
        val held = async { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent() // parked in its fetch, before any floor

        h.source.next = { EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT, retryAfterSeconds = 120) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        val floorFrom = testScheduler.currentTime
        h.failTheDecisionRead(times = 2)
        gate.complete(Unit)
        held.await()
        runCurrent()
        assertEquals(1, h.coordinator.heldLossCandidateCount())
        untilResolved(h)
        val fetches = h.source.fetches

        advanceTimeBy(floorFrom + 119_900 - testScheduler.currentTime)
        runCurrent()
        assertEquals("inside the later floor", fetches, h.source.fetches)
        advanceTimeBy(floorFrom + 120_100 - testScheduler.currentTime)
        runCurrent()
        assertEquals(fetches + 1, h.source.fetches)
    }

    @Test
    fun theClientBackoff_outlastsWhatIsLeftOfTheCandidatesFloor() = candidateTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 2) }
        val gate = CompletableDeferred<Unit>()
        h.source.gate = gate
        val held = async { h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
        runCurrent()

        // A demand whose retries have already backed off: the third arming waits ten seconds.
        h.source.next = { EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT, retryAfterSeconds = 1) }
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        h.failTheDecisionRead(times = 2)
        gate.complete(Unit)
        held.await()
        runCurrent()
        val resolvedAt = untilResolved(h)
        val fetches = h.source.fetches

        advanceTimeBy(resolvedAt + RecheckSchedule.BASE_BACKOFF_MILLIS * 2 - 100 - testScheduler.currentTime)
        runCurrent()
        assertEquals("the client backoff was cut short by what was left of the floor", fetches, h.source.fetches)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(fetches + 1, h.source.fetches)
    }

    @Test
    fun aFloorOnACandidateWithNoDemand_isRecorded_butTheCandidateArmsNoQuery() = candidateTest {
        val h = granted()
        h.source.next = { EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 60) }
        h.source.answeredAs = EntitlementsIdentity(OWNER, 1L)
        h.source.afterFetch = {
            h.store.loadFailures = 1
            h.source.identity = null // recovery keeps retrying without resolving it
        }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()
        val fetches = h.source.fetches
        assertEquals(1, h.coordinator.heldLossCandidateCount())

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("a hold arms no query", fetches, h.source.fetches)
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()
        assertEquals("a caller inside the recorded floor does not query", fetches, h.source.fetches)

        advanceTimeBy(29_900)
        runCurrent()
        assertEquals("recovery's retries did not restart the floor either way", fetches, h.source.fetches)
        advanceTimeBy(RETRY)
        runCurrent()
        assertEquals("the caller's request runs once the floor ends", fetches + 1, h.source.fetches)
    }

    // --- outside the candidate contract -------------------------------------------------------------------------------

    @Test
    fun aReadFailureBeforeTheQuery_orForAnAnswerThatIsNotALoss_isNotACandidateAndStillThrows() = candidateTest {
        val h = granted()
        h.store.loadFailures = 1
        assertTrue(runCatching { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }.isFailure)
        assertEquals(0, h.coordinator.heldLossCandidateCount())

        h.source.next = { EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT) }
        h.failTheDecisionRead()
        assertTrue(runCatching { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }.isFailure)
        assertEquals(0, h.coordinator.heldLossCandidateCount())
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
    }

    private fun active(krx: Boolean) = EntitlementsOutcome.StableActive(krxVisible = krx)

    private companion object {
        const val OWNER = "user-a"
        const val OTHER = "user-b"
        const val RETRY = 1_000L

        /** Longer than any settling recovery here needs. */
        const val SETTLE = 60 * 60 * 1_000L

        val KRX_REFUSAL = listOf(TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED)
    }
}
