package com.jay.fxi.data.entitlements

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coordinator behaviour the pure reducer cannot cover: the three staleness fences, the order of
 * persistence versus publication, sign-out teardown, the owner-bound escalation latch, and
 * per-entry journal completion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PremiumAccessCoordinatorTest {

    private val processJob = SupervisorJob()

    /** In-memory store built on the production transitions so the fake cannot drift. */
    private class FakeStore(private val ids: EpochIdGenerator) : AccessEpochStore {
        var record = AccessEpochRecord()
        var failNextRotation = false
        var failNextLoad = false

        /** Parks the next load so a test can hold the coordinator's lock from the outside. */
        var blockNextLoadOn: CompletableDeferred<Unit>? = null

        override suspend fun load(): AccessEpochRecord {
            if (failNextLoad) {
                failNextLoad = false
                throw java.io.IOException("simulated read failure")
            }
            blockNextLoadOn?.let { gate ->
                blockNextLoadOn = null
                gate.await()
            }
            return record
        }
        override suspend fun bindOwner(uid: String) =
            AccessEpochTransitions.bindOwner(record, uid, ids).also { record = it }
        override suspend fun signOut() =
            AccessEpochTransitions.signOut(record, ids).also { record = it }
        override suspend fun beginRotation(rotateUser: Boolean, rotateKrx: Boolean): AccessEpochRecord {
            if (failNextRotation) throw java.io.IOException("simulated persistence failure")
            return AccessEpochTransitions.rotate(record, rotateUser, rotateKrx, ids).also { record = it }
        }
        override suspend fun completePurges(completed: Collection<PendingPurge>) =
            AccessEpochTransitions.completePurges(record, completed).also { record = it }
        override suspend fun markMayContainData(premium: Boolean, krx: Boolean) =
            AccessEpochTransitions.markMayContainData(record, premium, krx).also { record = it }
    }

    private class RecordingPurger(var result: PurgeResult) : UserScopePurger, CapabilityScopePurger {
        val calls = mutableListOf<PurgeNamespace>()
        override suspend fun purgeUserScope(namespace: PurgeNamespace) = result.also { calls += namespace }
        override suspend fun purgeCapabilityScope(namespace: PurgeNamespace) = result.also { calls += namespace }
    }

    private class FakeSource(
        var identity: EntitlementsIdentity? = EntitlementsIdentity(OWNER, 1L),
        val onFetch: suspend (Boolean) -> EntitlementsResult
    ) : EntitlementsSource {
        override suspend fun fetch(freshPremium: Boolean) = onFetch(freshPremium)
        override suspend fun currentIdentity() = identity
    }

    private fun answer(
        outcome: EntitlementsOutcome,
        uid: String = OWNER,
        generation: Long = 1L
    ): EntitlementsResult =
        EntitlementsResult.Answered(EntitlementsIdentity(uid, generation), outcome)

    private fun TestScope.build(
        store: FakeStore,
        source: EntitlementsSource,
        purger: RecordingPurger = RecordingPurger(PurgeResult.Completed)
    ) = PremiumAccessCoordinator(
        source = source,
        store = store,
        userPurger = purger,
        capabilityPurger = purger,
        scope = CoroutineScope(processJob + StandardTestDispatcher(testScheduler)),
        clock = { testScheduler.currentTime }
    )

    private fun ids(): EpochIdGenerator {
        var n = 0
        return EpochIdGenerator { "epoch-${n++}" }
    }

    // --- staleness fences ------------------------------------------------------------------------

    @Test
    fun staleNamespaceResponse_cannotReopenARejection() = runTest {
        val store = FakeStore(ids())
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val source = FakeSource {
            calls += 1
            if (calls > 1) release.await()
            answer(EntitlementsOutcome.StableActive(krxVisible = false))
        }
        val coordinator = build(store, source)
        coordinator.onOwnerChanged(OWNER)
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value)

        val inFlight = async { coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        val fenceBefore = store.record.fence()

        coordinator.onTopicRejected(TopicRejection.PREMIUM_REQUIRED)
        runCurrent()
        assertEquals(PremiumAccessState.Rejected, coordinator.state.value)
        assertNotEquals("a grant rejection must rotate", fenceBefore, store.record.fence())

        release.complete(Unit)
        inFlight.await()
        advanceUntilIdle()

        assertEquals(PremiumAccessState.Rejected, coordinator.state.value)
        processJob.cancel()
    }

    /**
     * Regression: a rejection while nothing was granted rotates no epoch, so the namespace fence
     * still matches. Only a decision generation catches this.
     */
    @Test
    fun rejectionWithoutAGrant_stillInvalidatesAnInFlightActive() = runTest {
        val store = FakeStore(ids())
        val release = CompletableDeferred<Unit>()
        val source = FakeSource {
            release.await()
            answer(EntitlementsOutcome.StableActive(krxVisible = false))
        }
        val coordinator = build(store, source)
        coordinator.onOwnerChanged(OWNER)

        val inFlight = async { coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        val fenceBefore = store.record.fence()

        coordinator.onTopicRejected(TopicRejection.PREMIUM_REQUIRED)
        runCurrent()
        assertEquals(PremiumAccessState.Rejected, coordinator.state.value)
        assertEquals("nothing was protected, so nothing rotated", fenceBefore, store.record.fence())

        release.complete(Unit)
        inFlight.await()
        advanceUntilIdle()

        assertEquals(
            "an ACTIVE that left before the rejection must not grant",
            PremiumAccessState.Rejected,
            coordinator.state.value
        )
        processJob.cancel()
    }

    /** Regression: the transport captures its own credential, so the answer may be another user's. */
    @Test
    fun answerFetchedAsAnotherUser_isDropped() = runTest {
        val store = FakeStore(ids())
        val source = FakeSource {
            answer(EntitlementsOutcome.StableActive(krxVisible = true), uid = "user-b")
        }
        val coordinator = build(store, source)
        coordinator.onOwnerChanged(OWNER)

        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()

        assertEquals(
            "user-b's entitlement must not grant user-a",
            PremiumAccessState.NoGrant,
            coordinator.state.value
        )
        processJob.cancel()
    }

    @Test
    fun currentNamespaceResponse_isApplied() = runTest {
        val store = FakeStore(ids())
        val coordinator = build(store, FakeSource { answer(EntitlementsOutcome.StableActive(krxVisible = true)) })
        coordinator.onOwnerChanged(OWNER)

        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()

        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value)
        assertEquals(KrxCapabilityState.VISIBLE, coordinator.krx.value)
        processJob.cancel()
    }

    /**
     * Regression: the answer carried an auth generation that nothing checked, so a reply from a
     * session the transport had already superseded still granted.
     */
    @Test
    fun anAnswerFromASupersededAuthSession_isDropped() = runTest {
        val store = FakeStore(ids())
        val release = CompletableDeferred<Unit>()
        val source = FakeSource {
            release.await()
            answer(EntitlementsOutcome.StableActive(krxVisible = false), generation = 1L)
        }
        val coordinator = build(store, source)
        coordinator.onOwnerChanged(OWNER)

        val inFlight = async { coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        // Same uid, new session: a token re-issue the transport already treats as authoritative.
        source.identity = EntitlementsIdentity(OWNER, 2L)

        release.complete(Unit)
        inFlight.await()
        advanceUntilIdle()

        assertEquals(PremiumAccessState.NoGrant, coordinator.state.value)
        processJob.cancel()
    }

    /**
     * Regression: the auth-session fence dropped the answer whenever the live identity did not
     * match — including when the transport simply had no credential to report. A *different*
     * session is owned by whatever established it, but an unavailable one supersedes nothing, so
     * that drop left nobody to re-check and stranded the caller.
     */
    @Test
    fun anAnswerTheTransportCannotConfirm_isHeldBackButRetried() = runTest {
        val store = FakeStore(ids())
        var calls = 0
        val source = FakeSource(identity = null) {
            calls += 1
            answer(EntitlementsOutcome.StableActive(krxVisible = false))
        }
        val coordinator = build(store, source)
        coordinator.onOwnerChanged(OWNER)

        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals(
            "an answer no live session confirms must not grant",
            PremiumAccessState.NoGrant,
            coordinator.state.value
        )

        // The credential comes back. The re-check that was armed must pick the grant up.
        source.identity = EntitlementsIdentity(OWNER, 1L)
        advanceUntilIdle()

        assertTrue("no re-check was armed for an unconfirmable session", calls >= 2)
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value)
        processJob.cancel()
    }

    /**
     * Regression: holding an answer back also discarded the retry floor that answer carried.
     * Declining to apply a decision is not the same as ignoring the rate limit stated with it.
     */
    @Test
    fun anUnconfirmableAnswer_keepsTheRetryFloorItCarried() = runTest {
        val store = FakeStore(ids())
        val queriedAt = mutableListOf<Long>()
        val source = FakeSource(identity = null) {
            queriedAt += testScheduler.currentTime
            answer(EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 60))
        }
        val coordinator = build(store, source)
        coordinator.onOwnerChanged(OWNER)

        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        testScheduler.advanceTimeBy(59_000)
        testScheduler.runCurrent()
        assertEquals("the server asked for 60 s before the next query", listOf(0L), queriedAt)

        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals("the retry was dropped rather than deferred", listOf(0L, 60_000L), queriedAt)
        processJob.cancel()
    }

    /**
     * Regression: the latch release waited on the coordinator mutex, and a cancelled coroutine
     * cannot wait on anything. Whenever something else held the lock at cancellation time the
     * token outlived its own request and no later escalation could start.
     */
    @Test
    fun aCancelledQuery_stillReleasesItsEscalationLatch() = runTest {
        val store = FakeStore(ids())
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val source = FakeSource {
            calls += 1
            if (calls == 1) {
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
            answer(EntitlementsOutcome.StableActive(krxVisible = false))
        }
        val coordinator = build(store, source)
        coordinator.onOwnerChanged(OWNER)

        val cancelled = async { coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        assertTrue("the first query never started", started.isCompleted)

        // Something else is inside the coordinator lock when the cancellation lands.
        val gate = CompletableDeferred<Unit>()
        store.blockNextLoadOn = gate
        val holder = async { coordinator.resumePendingPurges() }
        runCurrent()

        cancelled.cancel()
        runCurrent()
        gate.complete(Unit)
        holder.await()
        runCurrent()

        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()

        assertEquals("the cancelled request's latch blocked the next escalation", 2, calls)
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value)
        processJob.cancel()
    }

    /** Regression: a token that could not be acquired escaped classification entirely. */
    @Test
    fun anUnauthenticatedResult_preservesTheGrantAndSchedulesARetry() = runTest {
        val store = FakeStore(ids())
        var calls = 0
        val source = FakeSource {
            calls += 1
            if (calls == 1) {
                answer(EntitlementsOutcome.StableActive(krxVisible = false))
            } else {
                EntitlementsResult.Unauthenticated(
                    EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT)
                )
            }
        }
        val coordinator = build(store, source)
        coordinator.onOwnerChanged(OWNER)
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value)

        coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()

        assertEquals(
            "a credential failure must not downgrade a grant",
            PremiumAccessState.PremiumConfirmed,
            coordinator.state.value
        )
        assertTrue("no re-check was armed", calls >= 2)
        processJob.cancel()
    }

    /**
     * Regression: a purchase arriving during a `Retry-After` wait returned immediately and the
     * retry that eventually ran was still the weaker ordinary mode, so it could not grant.
     */
    @Test
    fun aForcedCallerBlockedByTheFloor_upgradesTheQueuedRetry() = runTest {
        val store = FakeStore(ids())
        val modes = mutableListOf<Boolean>()
        val source = FakeSource { fresh ->
            modes += fresh
            if (modes.size == 1) {
                answer(EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 60))
            } else {
                answer(EntitlementsOutcome.StableActive(krxVisible = false))
            }
        }
        val coordinator = build(store, source)
        coordinator.onOwnerChanged(OWNER)

        coordinator.refresh(RefreshIntent.IF_STALE)
        runCurrent()
        assertEquals(listOf(false), modes)

        // A purchase lands one second into the 60 s floor.
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals("the floor must still be honoured", 1, modes.size)

        advanceUntilIdle()

        assertEquals("the retry ran, but not strongly enough to grant", listOf(false, true), modes)
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value)
        processJob.cancel()
    }

    // --- escalation latch -------------------------------------------------------------------------

    /** Regression: a failure between latching and fetching used to wedge escalation permanently. */
    @Test
    fun escalationLatch_isReleasedWhenTheQueryFails() = runTest {
        val store = FakeStore(ids())
        var calls = 0
        val source = FakeSource {
            calls += 1
            if (calls == 1) throw java.io.IOException("network down")
            answer(EntitlementsOutcome.StableActive(krxVisible = false))
        }
        val coordinator = build(store, source)
        coordinator.onOwnerChanged(OWNER)

        runCatching { coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        advanceUntilIdle()

        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()

        assertEquals("the second escalation was suppressed", 2, calls)
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value)
        processJob.cancel()
    }

    /**
     * Regression: an escalation still running for the previous user used to suppress the new
     * user's first query, leaving them stuck at NoGrant with nothing in flight.
     */
    @Test
    fun escalationLatch_doesNotBlockTheNextUsersFirstQuery() = runTest {
        val store = FakeStore(ids())
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val source = FakeSource {
            calls += 1
            if (calls == 1) release.await()
            answer(EntitlementsOutcome.StableActive(krxVisible = false), uid = "user-b")
        }
        val coordinator = build(store, source)
        coordinator.onOwnerChanged(OWNER)

        val stuck = async { coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()

        coordinator.onOwnerChanged("user-b")
        // The transport is now signed in as user-b, so its answers are attributable to user-b.
        source.identity = EntitlementsIdentity("user-b", 1L)
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()

        assertEquals("user-b never got to query", 2, calls)
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value)

        release.complete(Unit)
        stuck.await()
        advanceUntilIdle()
        processJob.cancel()
    }

    /** Regression: an old request's cleanup used to release whatever latch was current. */
    @Test
    fun anOldRequestFinishing_doesNotReleaseTheCurrentRequestsLatch() = runTest {
        val store = FakeStore(ids())
        val gateA = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        var calls = 0
        val source = FakeSource {
            calls += 1
            when (calls) {
                1 -> { gateA.await(); answer(EntitlementsOutcome.StableActive(false)) }
                2 -> { gateB.await(); answer(EntitlementsOutcome.StableActive(false), uid = "user-b") }
                else -> answer(EntitlementsOutcome.StableActive(false), uid = "user-b")
            }
        }
        val coordinator = build(store, source)
        coordinator.onOwnerChanged(OWNER)
        val a = async { coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()

        coordinator.onOwnerChanged("user-b")
        val b = async { coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()

        // A finishes. Its cleanup must not unlatch B's still-running escalation.
        gateA.complete(Unit)
        a.await()
        runCurrent()

        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals("a third query ran while B's escalation was still in flight", 2, calls)

        gateB.complete(Unit)
        b.await()
        advanceUntilIdle()
        processJob.cancel()
    }

    /**
     * Regression: the generation was bumped from the resulting *state*, so a cached ACTIVE landing
     * on an already-free state counted as a fresh rejection and discarded the real answer.
     */
    @Test
    fun aCachedActive_doesNotInvalidateAnInFlightFreshQuery() = runTest {
        val store = FakeStore(ids())
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val source = FakeSource { fresh ->
            calls += 1
            when {
                calls == 1 -> answer(EntitlementsOutcome.StableInactive(krxVisible = false))
                fresh -> { release.await(); answer(EntitlementsOutcome.StableActive(krxVisible = false)) }
                else -> answer(EntitlementsOutcome.StableActive(krxVisible = false))
            }
        }
        val coordinator = build(store, source)
        coordinator.onOwnerChanged(OWNER)
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        assertEquals(PremiumAccessState.FreeConfirmed, coordinator.state.value)

        val fresh = async { coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        // A separate ordinary query lands a cached ACTIVE: no state change, no new rejection.
        coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()

        release.complete(Unit)
        fresh.await()
        advanceUntilIdle()

        assertEquals(
            "the fresh grant was discarded by a bump that no rejection caused",
            PremiumAccessState.PremiumConfirmed,
            coordinator.state.value
        )
        processJob.cancel()
    }

    // --- persistence ordering ----------------------------------------------------------------------

    @Test
    fun persistenceFailure_leavesTheTransitionUnpublished() = runTest {
        val store = FakeStore(ids())
        val coordinator = build(store, FakeSource { answer(EntitlementsOutcome.StableActive(krxVisible = false)) })
        coordinator.onOwnerChanged(OWNER)
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        val epochBefore = store.record.userAccessEpoch

        store.failNextRotation = true
        runCatching { coordinator.onTopicRejected(TopicRejection.PREMIUM_REQUIRED) }
        advanceUntilIdle()

        assertEquals(
            "state was published although the rotation never persisted",
            PremiumAccessState.PremiumConfirmed,
            coordinator.state.value
        )
        assertEquals(epochBefore, store.record.userAccessEpoch)
        processJob.cancel()
    }

    // --- sign-out and journal ------------------------------------------------------------------------

    @Test
    fun signOut_rotatesAndJournals_soTheNamespaceIsNotInherited() = runTest {
        val store = FakeStore(ids())
        val purger = RecordingPurger(PurgeResult.Deferred("test"))
        val coordinator = build(store, FakeSource { answer(EntitlementsOutcome.StableActive(krxVisible = false)) }, purger)
        coordinator.onOwnerChanged(OWNER)
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        val epochWhileSignedIn = store.record.userAccessEpoch

        coordinator.onSignedOut()
        advanceUntilIdle()

        assertNotEquals(epochWhileSignedIn, store.record.userAccessEpoch)
        assertEquals(epochWhileSignedIn, store.record.pendingPurges.single().userAccessEpoch)
        assertEquals(OWNER, store.record.pendingPurges.single().ownerUid)
        assertEquals(PremiumAccessState.NoGrant, coordinator.state.value)
        processJob.cancel()
    }

    @Test
    fun deferredPurge_keepsTheJournalForALaterAttempt() = runTest {
        val store = FakeStore(ids())
        val purger = RecordingPurger(PurgeResult.Deferred("not owned by this slice"))
        val coordinator = build(store, FakeSource { answer(EntitlementsOutcome.StableActive(krxVisible = false)) }, purger)
        coordinator.onOwnerChanged(OWNER)
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()

        coordinator.onTopicRejected(TopicRejection.PREMIUM_REQUIRED)
        advanceUntilIdle()

        assertTrue(purger.calls.isNotEmpty())
        assertTrue("a deferred purge must not clear the journal", store.record.pendingPurges.isNotEmpty())
        assertEquals(OWNER, purger.calls.first().ownerUid)
        processJob.cancel()
    }

    /** Regression: completing one namespace must not discard another user's outstanding entry. */
    @Test
    fun completingOnePurge_leavesTheOtherOwnersEntryOwed() = runTest {
        val store = FakeStore(ids())
        val purger = RecordingPurger(PurgeResult.Deferred("stuck"))
        val coordinator = build(store, FakeSource { answer(EntitlementsOutcome.StableActive(krxVisible = false)) }, purger)

        coordinator.onOwnerChanged("user-a")
        coordinator.onOwnerChanged("user-b")
        coordinator.onOwnerChanged("user-c")
        advanceUntilIdle()
        assertEquals(2, store.record.pendingPurges.size)

        // Only the first namespace can be cleaned; the second stays owed.
        val first = store.record.pendingPurges.first()
        purger.result = PurgeResult.Completed
        val selective = object : UserScopePurger, CapabilityScopePurger {
            override suspend fun purgeUserScope(namespace: PurgeNamespace) =
                if (namespace.pending == first) PurgeResult.Completed else PurgeResult.Deferred("stuck")
            override suspend fun purgeCapabilityScope(namespace: PurgeNamespace) =
                if (namespace.pending == first) PurgeResult.Completed else PurgeResult.Deferred("stuck")
        }
        val second = PremiumAccessCoordinator(
            source = FakeSource { answer(EntitlementsOutcome.StableActive(krxVisible = false)) },
            store = store,
            userPurger = selective,
            capabilityPurger = selective,
            scope = CoroutineScope(processJob + StandardTestDispatcher(testScheduler)),
            clock = { testScheduler.currentTime }
        )
        second.resumePendingPurges()
        advanceUntilIdle()

        assertEquals(1, store.record.pendingPurges.size)
        assertEquals("user-b", store.record.pendingPurges.single().ownerUid)
        processJob.cancel()
    }

    // --- slice boundary -------------------------------------------------------------------------------

    @Test
    fun pushDelete_isDeclaredButThisSliceExecutesNothing() = runTest {
        val store = FakeStore(ids())
        val coordinator = build(store, FakeSource { answer(EntitlementsOutcome.StableActive(krxVisible = false)) })
        coordinator.onOwnerChanged(OWNER)
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()

        coordinator.onTopicRejected(TopicRejection.PREMIUM_REQUIRED)
        advanceUntilIdle()

        assertTrue(AccessEffect.PushDelete in coordinator.lastEffects.value)
        processJob.cancel()
    }

    @Test
    fun pendingRetry_actuallyRuns_despiteTheOrdinaryDebounce() = runTest {
        val store = FakeStore(ids())
        var calls = 0
        val source = FakeSource {
            calls += 1
            if (calls == 1) {
                answer(EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 5))
            } else {
                answer(EntitlementsOutcome.StableInactive(krxVisible = false))
            }
        }
        val coordinator = build(store, source)
        coordinator.onOwnerChanged(OWNER)

        coordinator.refresh(RefreshIntent.IF_STALE)
        advanceUntilIdle()

        assertEquals("the scheduled retry never ran", 2, calls)
        assertEquals(PremiumAccessState.FreeConfirmed, coordinator.state.value)
        assertFalse(coordinator.state.value.grantsPremiumRuntime)
        processJob.cancel()
    }

    private companion object {
        const val OWNER = "user-a"
    }
}
