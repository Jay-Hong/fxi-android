package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * L-4e E1: the topic access snapshot the issuer publishes (`l4e_e1_design_v3.md`, `ANDROID_V2_PLAN.md` 동결 후 12번).
 *
 * Each test checks the facts against a recomputation after its steps ([PremiumAccessCoordinator.accessFactsAreCurrent]),
 * but that check alone cannot see order: the ordering and in-doubt tests read the snapshot while the change is still
 * under way — inside a state collector, or with a write parked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PremiumAccessTopicSnapshotTest {

    private val processJob = SupervisorJob()

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
        override suspend fun purgeUserScope(namespace: PurgeNamespace) = PurgeResult.Completed
        override suspend fun purgeCapabilityScope(namespace: PurgeNamespace) = PurgeResult.Completed
    }

    private class Source(var next: () -> EntitlementsOutcome) : EntitlementsSource {
        var identity: EntitlementsIdentity? = EntitlementsIdentity(OWNER, 1L)
        var liveFence: AuthIdentityFence? = null

        /** Runs once, after the answer is formed and before it returns: where the decision read is made to fail. */
        var afterFetch: () -> Unit = {}

        override suspend fun fetch(freshPremium: Boolean): EntitlementsResult {
            val outcome = next()
            val owner = checkNotNull(identity)
            afterFetch().also { afterFetch = {} }
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
        val hooks: Hooks
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
        val coordinator = PremiumAccessCoordinator(
            source = source,
            store = store,
            userPurger = Purger(),
            capabilityPurger = Purger(),
            scope = CoroutineScope(processJob + StandardTestDispatcher(testScheduler)),
            clock = { testScheduler.currentTime },
            jitter = ProbeJitter.None,
            persistenceRetryDelayMillis = RETRY,
            liveFence = { source.liveFence },
            onLossReapprovalScheduled = { _, _ -> hooks.onReapproval() }
        )
        return Harness(store, source, coordinator, hooks)
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

    private companion object {
        const val OWNER = "user-a"
        const val OTHER = "user-b"
        const val RETRY = 1_000L
        const val SETTLE = 60 * 60 * 1_000L
    }
}
