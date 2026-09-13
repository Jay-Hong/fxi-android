package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.domain.model.TopicRejectionReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.launch
import org.junit.Assert.assertNull
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

        /** Parks the teardown/bind writes, to observe what a reader sees while they are running. */
        var blockNextSignOutOn: CompletableDeferred<Unit>? = null
        var blockNextBindOn: CompletableDeferred<Unit>? = null

        var loads = 0
        var rotations = 0

        override suspend fun load(): AccessEpochRecord {
            loads += 1
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
        override suspend fun bindOwner(uid: String): AccessEpochRecord {
            blockNextBindOn?.let { gate -> blockNextBindOn = null; gate.await() }
            return AccessEpochTransitions.bindOwner(record, uid, ids).also { record = it }
        }
        override suspend fun signOut(): AccessEpochRecord {
            blockNextSignOutOn?.let { gate -> blockNextSignOutOn = null; gate.await() }
            return AccessEpochTransitions.signOut(record, ids).also { record = it }
        }
        override suspend fun retireUnverifiedStart(): AccessEpochRecord =
            AccessEpochTransitions.retireUnverifiedStart(record, ids).also { record = it }
        override suspend fun beginRotation(rotateUser: Boolean, rotateKrx: Boolean): AccessEpochRecord {
            if (failNextRotation) throw java.io.IOException("simulated persistence failure")
            rotations += 1
            return AccessEpochTransitions.rotate(record, rotateUser, rotateKrx, ids).also { record = it }
        }
        override suspend fun completePurges(completed: Collection<PendingPurge>) =
            AccessEpochTransitions.completePurges(record, completed).also { record = it }
        override suspend fun journalRetired(obligation: LossObligation) =
            AccessEpochTransitions.journalRetired(record, obligation).also { record = it }
        override suspend fun markMayContainData(premium: Boolean, krx: Boolean) =
            AccessEpochTransitions.markMayContainData(record, premium, krx).also { record = it }

        override suspend fun beginSignOut(uid: String) =
            AccessEpochTransitions.beginSignOut(record, uid).also { record = it }
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
        /** Parks the identity read, so a test can land a sign-out inside that window. */
        var identityGate: CompletableDeferred<Unit>? = null

        /** Thrown by the next identity read. The production source never throws but cancellation; a fake can. */
        var identityFailure: Throwable? = null

        override suspend fun fetch(freshPremium: Boolean) = onFetch(freshPremium)
        override suspend fun currentIdentity(): EntitlementsIdentity? {
            // Snapshot *then* park. The race being modelled is a read that was live when it
            // happened and stale by the time the caller acts on it — parking before the read
            // would just return the new value and reproduce nothing.
            identityFailure?.let { failure -> identityFailure = null; throw failure }
            val snapshot = identity
            identityGate?.let { gate -> identityGate = null; gate.await() }
            return snapshot
        }
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
        purger: RecordingPurger = RecordingPurger(PurgeResult.Completed),
        /** The live auth fence the coordinator reads. Signed out unless a test says otherwise. */
        live: () -> AuthIdentityFence? = { null }
    ) = PremiumAccessCoordinator(
        source = source,
        store = store,
        userPurger = purger,
        capabilityPurger = purger,
        scope = CoroutineScope(processJob + StandardTestDispatcher(testScheduler)),
        clock = { testScheduler.currentTime },
        // Exact nominal delays. Production spreads them; a test that cannot pin the spread
        // cannot assert when a tick fired.
        jitter = ProbeJitter.None,
        liveFence = live
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
        coordinator.onIdentityChanged(ownerFence(OWNER))
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)
        val grant = coordinator.issuedGrant()

        val inFlight = async { coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        val fenceBefore = store.record.fence()

        coordinator.onTopicRejected(grant, PREMIUM_REFUSAL)
        runCurrent()
        assertEquals(PremiumAccessState.Rejected, coordinator.state.value.state)
        assertNotEquals("a grant rejection must rotate", fenceBefore, store.record.fence())

        release.complete(Unit)
        inFlight.await()
        advanceUntilIdle()

        assertEquals(PremiumAccessState.Rejected, coordinator.state.value.state)
        processJob.cancel()
    }

    /**
     * Regression: an authoritative loss while nothing was granted rotates no epoch, so the
     * namespace fence still matches. Only a decision generation catches this.
     *
     * Driven by a stable `premium_active=false` answer. A topic refusal used to stand in for it,
     * but a refusal now needs an issued grant, and there is none while nothing is granted.
     */
    @Test
    fun anAuthoritativeLossWithoutAGrant_stillInvalidatesAnInFlightActive() = runTest {
        val store = FakeStore(ids())
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val source = FakeSource {
            calls += 1
            if (calls == 1) {
                release.await()
                answer(EntitlementsOutcome.StableActive(krxVisible = false))
            } else {
                answer(EntitlementsOutcome.StableInactive(krxVisible = false))
            }
        }
        val coordinator = build(store, source)
        coordinator.onIdentityChanged(ownerFence(OWNER))

        val inFlight = async { coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        val fenceBefore = store.record.fence()

        coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()
        assertEquals(PremiumAccessState.FreeConfirmed, coordinator.state.value.state)
        assertEquals("nothing was protected, so nothing rotated", fenceBefore, store.record.fence())

        release.complete(Unit)
        inFlight.await()
        advanceUntilIdle()

        assertEquals(
            "an ACTIVE that left before the loss must not grant",
            PremiumAccessState.FreeConfirmed,
            coordinator.state.value.state
        )
        assertEquals(fenceBefore, store.record.fence())
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
        coordinator.onIdentityChanged(ownerFence(OWNER))

        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()

        assertEquals(
            "user-b's entitlement must not grant user-a",
            PremiumAccessState.NoGrant,
            coordinator.state.value.state
        )
        processJob.cancel()
    }

    @Test
    fun currentNamespaceResponse_isApplied() = runTest {
        val store = FakeStore(ids())
        val coordinator = build(store, FakeSource { answer(EntitlementsOutcome.StableActive(krxVisible = true)) })
        coordinator.onIdentityChanged(ownerFence(OWNER))

        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()

        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)
        assertEquals(KrxCapabilityState.VISIBLE, coordinator.krx.value)
        processJob.cancel()
    }

    /**
     * Slice 7a: KRX used to be hidden only after the disk work of an identity transition. A read or
     * write that failed there left the previous session's VISIBLE standing while the premium state
     * had already dropped to NoGrant — the capability outliving the grant it belongs to.
     */
    @Test
    fun anIdentityChangeWhoseDiskWorkFails_stillHidesKrxFirst() = runTest {
        val store = FakeStore(ids())
        val coordinator = build(store, FakeSource { answer(EntitlementsOutcome.StableActive(krxVisible = true)) })
        coordinator.onIdentityChanged(ownerFence(OWNER))
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        assertEquals(KrxCapabilityState.VISIBLE, coordinator.krx.value)

        store.failNextLoad = true
        // Slice 7b took the propagation over: with no attempt open the failure is held, not thrown.
        // What this test is about is unchanged — nothing readable survives it either way.
        coordinator.onIdentityChanged(ownerFence("user-b")).heldByPersistence("신원 전환의 디스크 실패")

        assertEquals(KrxCapabilityState.HIDDEN, coordinator.krx.value)
        assertEquals(PremiumAccessState.NoGrant, coordinator.state.value.state)
        processJob.cancel()
    }

    @Test
    fun aSignOutWhoseDiskWorkFails_stillHidesKrxFirst() = runTest {
        val store = FakeStore(ids())
        val coordinator = build(store, FakeSource { answer(EntitlementsOutcome.StableActive(krxVisible = true)) })
        coordinator.onIdentityChanged(ownerFence(OWNER))
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        assertEquals(KrxCapabilityState.VISIBLE, coordinator.krx.value)

        store.failNextLoad = true
        coordinator.onSignedOut(ownerFence(OWNER)).heldByPersistence("로그아웃의 디스크 실패")

        assertEquals(KrxCapabilityState.HIDDEN, coordinator.krx.value)
        assertEquals(PremiumAccessState.NoGrant, coordinator.state.value.state)
        processJob.cancel()
    }

    /**
     * The timing, not just the outcome: KRX is hidden *before* the first suspension, so it is
     * already hidden while the disk work is still parked. An implementation that hid it from a
     * failure path instead would pass the two tests above and fail this one.
     */
    @Test
    fun anIdentityChange_hidesKrxBeforeItsDiskWorkEvenStarts() = runTest {
        val store = FakeStore(ids())
        val coordinator = build(store, FakeSource { answer(EntitlementsOutcome.StableActive(krxVisible = true)) })
        coordinator.onIdentityChanged(ownerFence(OWNER))
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        assertEquals(KrxCapabilityState.VISIBLE, coordinator.krx.value)

        val parked = CompletableDeferred<Unit>()
        store.blockNextLoadOn = parked
        val binding = async { coordinator.onIdentityChanged(ownerFence("user-b")) }
        runCurrent()

        assertEquals("디스크 작업이 아직 도는 중인데 KRX 가 보였다", KrxCapabilityState.HIDDEN, coordinator.krx.value)
        assertEquals(PremiumAccessState.NoGrant, coordinator.state.value.state)

        parked.complete(Unit)
        binding.await()
        assertEquals(KrxCapabilityState.HIDDEN, coordinator.krx.value)
        processJob.cancel()
    }

    @Test
    fun aSignOut_hidesKrxBeforeItsDiskWorkEvenStarts() = runTest {
        val store = FakeStore(ids())
        val coordinator = build(store, FakeSource { answer(EntitlementsOutcome.StableActive(krxVisible = true)) })
        coordinator.onIdentityChanged(ownerFence(OWNER))
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        assertEquals(KrxCapabilityState.VISIBLE, coordinator.krx.value)

        val parked = CompletableDeferred<Unit>()
        store.blockNextLoadOn = parked
        val ending = async { coordinator.onSignedOut(ownerFence(OWNER)) }
        runCurrent()

        assertEquals("해제의 디스크 작업 중에 KRX 가 보였다", KrxCapabilityState.HIDDEN, coordinator.krx.value)

        parked.complete(Unit)
        ending.await()
        assertEquals(KrxCapabilityState.HIDDEN, coordinator.krx.value)
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
        coordinator.onIdentityChanged(ownerFence(OWNER))

        val inFlight = async { coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        // Same uid, new session: a token re-issue the transport already treats as authoritative.
        source.identity = EntitlementsIdentity(OWNER, 2L)

        release.complete(Unit)
        inFlight.await()
        advanceUntilIdle()

        assertEquals(PremiumAccessState.NoGrant, coordinator.state.value.state)
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
        coordinator.onIdentityChanged(ownerFence(OWNER))

        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals(
            "an answer no live session confirms must not grant",
            PremiumAccessState.NoGrant,
            coordinator.state.value.state
        )

        // The credential comes back. The re-check that was armed must pick the grant up.
        source.identity = EntitlementsIdentity(OWNER, 1L)
        advanceUntilIdle()

        assertTrue("no re-check was armed for an unconfirmable session", calls >= 2)
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)
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
        coordinator.onIdentityChanged(ownerFence(OWNER))

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
        coordinator.onIdentityChanged(ownerFence(OWNER))

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
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)
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
        coordinator.onIdentityChanged(ownerFence(OWNER))
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)

        coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()

        assertEquals(
            "a credential failure must not downgrade a grant",
            PremiumAccessState.PremiumConfirmed,
            coordinator.state.value.state
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
        coordinator.onIdentityChanged(ownerFence(OWNER))

        coordinator.refresh(RefreshIntent.IF_STALE)
        runCurrent()
        assertEquals(listOf(false), modes)

        // A purchase lands one second into the 60 s floor.
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        assertEquals("the floor must still be honoured", 1, modes.size)

        advanceUntilIdle()

        assertEquals("the retry ran, but not strongly enough to grant", listOf(false, true), modes)
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)
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
        coordinator.onIdentityChanged(ownerFence(OWNER))

        runCatching { coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        advanceUntilIdle()

        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()

        assertEquals("the second escalation was suppressed", 2, calls)
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)
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
        coordinator.onIdentityChanged(ownerFence(OWNER))

        val stuck = async { coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()

        coordinator.onIdentityChanged(ownerFence("user-b"))
        // The transport is now signed in as user-b, so its answers are attributable to user-b.
        source.identity = EntitlementsIdentity("user-b", 1L)
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()

        assertEquals("user-b never got to query", 2, calls)
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)

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
        coordinator.onIdentityChanged(ownerFence(OWNER))
        val a = async { coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()

        coordinator.onIdentityChanged(ownerFence("user-b"))
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
        coordinator.onIdentityChanged(ownerFence(OWNER))
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        assertEquals(PremiumAccessState.FreeConfirmed, coordinator.state.value.state)

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
            coordinator.state.value.state
        )
        processJob.cancel()
    }

    // --- propagation probe ------------------------------------------------------------------------

    /**
     * `ANDROID_V2_PLAN.md` D23 `FreeConfirmed` row lists `[0,2,5,10,20]s`. iOS
     * (`EntitlementsManager.swift`) consumes that list as a **sleep per tick**, so the window is
     * cumulative. Reading it as absolute offsets would finish the whole probe inside 20s.
     */
    @Test
    fun probeTicks_fireAtCumulativeDelays_notAbsoluteOffsets() = runTest {
        val store = FakeStore(ids())
        val firedAt = mutableListOf<Long>()
        val source = FakeSource {
            firedAt += testScheduler.currentTime
            answer(EntitlementsOutcome.StableInactive(krxVisible = false))
        }
        val coordinator = build(store, source)
        coordinator.onIdentityChanged(ownerFence(OWNER))
        advanceUntilIdle()
        firedAt.clear()

        coordinator.onLocalPremiumSignal()
        advanceUntilIdle()

        assertEquals(listOf(0L, 2_000L, 7_000L, 17_000L, 37_000L), firedAt)
        processJob.cancel()
    }

    /**
     * The probe exists *for* the window in which the server still answers free. Stopping on
     * `FreeConfirmed` would end it on the first answer it was written to survive.
     */
    @Test
    fun probe_survivesFreeConfirmed_becauseThatIsThePropagationWindow() = runTest {
        val store = FakeStore(ids())
        var calls = 0
        val source = FakeSource {
            calls += 1
            answer(EntitlementsOutcome.StableInactive(krxVisible = false))
        }
        val coordinator = build(store, source)
        coordinator.onIdentityChanged(ownerFence(OWNER))
        advanceUntilIdle()
        calls = 0

        coordinator.onLocalPremiumSignal()
        advanceUntilIdle()

        assertEquals("the probe stopped inside its own window", 5, calls)
        assertEquals(PremiumAccessState.FreeConfirmed, coordinator.state.value.state)
        processJob.cancel()
    }

    @Test
    fun probe_stopsOnceThePurchaseIsConfirmed() = runTest {
        val store = FakeStore(ids())
        var calls = 0
        val source = FakeSource {
            calls += 1
            if (calls >= 2) answer(EntitlementsOutcome.StableActive(krxVisible = false))
            else answer(EntitlementsOutcome.StableInactive(krxVisible = false))
        }
        val coordinator = build(store, source)
        coordinator.onIdentityChanged(ownerFence(OWNER))
        advanceUntilIdle()
        calls = 0

        coordinator.onLocalPremiumSignal()
        advanceUntilIdle()

        assertEquals("the goal was reached; later ticks must not re-query", 2, calls)
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)
        processJob.cancel()
    }

    @Test
    fun probe_stopsOnAnAuthoritativeRejection() = runTest {
        val store = FakeStore(ids())
        var calls = 0
        val source = FakeSource {
            calls += 1
            answer(EntitlementsOutcome.PremiumRequired)
        }
        val coordinator = build(store, source)
        coordinator.onIdentityChanged(ownerFence(OWNER))
        advanceUntilIdle()
        calls = 0

        coordinator.onLocalPremiumSignal()
        advanceUntilIdle()

        assertEquals("a local true must not keep re-asking past a typed rejection", 1, calls)
        assertEquals(PremiumAccessState.Rejected, coordinator.state.value.state)
        processJob.cancel()
    }

    /**
     * Regression guard for the one line that must never change: the probe calls `refresh` with the
     * default CALLER origin. SCHEDULED returns true unconditionally in `shouldQuery`, so the probe
     * would walk straight past a server `Retry-After`.
     */
    @Test
    fun probeTick_respectsAServerRetryAfterFloor() = runTest {
        val store = FakeStore(ids())
        val firedAt = mutableListOf<Long>()
        val source = FakeSource {
            firedAt += testScheduler.currentTime
            answer(EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 30))
        }
        val coordinator = build(store, source)
        coordinator.onIdentityChanged(ownerFence(OWNER))
        advanceUntilIdle()

        coordinator.onLocalPremiumSignal()
        testScheduler.advanceTimeBy(20_000)
        testScheduler.runCurrent()

        // One query at t=0 sets a 30s floor; the 2s and 7s and 17s ticks are all inside it.
        assertEquals(listOf(0L), firedAt)
        processJob.cancel()
    }

    @Test
    fun probe_isAbandonedWhenTheOwnerChanges() = runTest {
        val store = FakeStore(ids())
        var calls = 0
        val source = FakeSource {
            calls += 1
            answer(EntitlementsOutcome.StableInactive(krxVisible = false))
        }
        val coordinator = build(store, source)
        coordinator.onIdentityChanged(ownerFence(OWNER))
        advanceUntilIdle()

        coordinator.onLocalPremiumSignal()
        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()
        val duringProbe = calls

        coordinator.onIdentityChanged(ownerFence("user-b"))
        source.identity = EntitlementsIdentity("user-b", 1L)
        advanceUntilIdle()

        assertEquals("the departed owner's probe kept querying", duringProbe, calls)
        processJob.cancel()
    }

    @Test
    fun probe_isAbandonedOnSignOut() = runTest {
        val store = FakeStore(ids())
        var calls = 0
        val source = FakeSource {
            calls += 1
            answer(EntitlementsOutcome.StableInactive(krxVisible = false))
        }
        val coordinator = build(store, source)
        coordinator.onIdentityChanged(ownerFence(OWNER))
        advanceUntilIdle()

        coordinator.onLocalPremiumSignal()
        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()
        val duringProbe = calls

        coordinator.onSignedOut(ownerFence(OWNER))
        advanceUntilIdle()

        assertEquals("a signed-out user's probe kept querying", duringProbe, calls)
        processJob.cancel()
    }

    /** iOS parity: a later billing callback joins the running budget instead of extending it. */
    @Test
    fun repeatedSignals_shareOneBudget() = runTest {
        val store = FakeStore(ids())
        var calls = 0
        val source = FakeSource {
            calls += 1
            answer(EntitlementsOutcome.StableInactive(krxVisible = false))
        }
        val coordinator = build(store, source)
        coordinator.onIdentityChanged(ownerFence(OWNER))
        advanceUntilIdle()
        calls = 0

        coordinator.onLocalPremiumSignal()
        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()
        coordinator.onLocalPremiumSignal()
        coordinator.onLocalPremiumSignal()
        advanceUntilIdle()

        assertEquals("a repeated signal restarted the window instead of joining it", 5, calls)
        processJob.cancel()
    }

    /**
     * A signal that arrives before any owner is bound is dropped, not queued.
     *
     * Reachable only in theory — the paywall sits behind the signed-in branch and the binder
     * starts at process start — but the behaviour should be stated rather than discovered: a
     * queued signal would later fire an escalation for whoever happened to sign in next.
     */
    @Test
    fun aPremiumSignalWithNoBoundOwner_isDropped() = runTest {
        val store = FakeStore(ids())
        var calls = 0
        val coordinator = build(store, FakeSource {
            calls += 1
            answer(EntitlementsOutcome.StableActive(krxVisible = false))
        })

        coordinator.onLocalPremiumSignal()
        advanceUntilIdle()
        assertEquals("no owner is bound, so there is nobody to query for", 0, calls)

        // And it is not replayed when someone does sign in.
        coordinator.onIdentityChanged(ownerFence(OWNER))
        advanceUntilIdle()
        assertEquals(0, calls)
        processJob.cancel()
    }

    /**
     * Regression: a probe gated on persisted ownership still started after a logout. With no
     * credential the transport throws, which classifies TRANSIENT, which arms a recheck — a retry
     * ladder that outlives the probe window and restarts work the teardown had just stopped.
     *
     * Since slice 5 the landed sign-out also gives up the owner, so the disk check refuses this one
     * too. The credential gate is what
     * [aPremiumSignalWithNoCredential_whileTheRecordStillNamesItsOwner_startsNothing] holds down.
     */
    @Test
    fun aPremiumSignalDeliveredAfterSignOut_startsNothing() = runTest {
        val store = FakeStore(ids())
        var calls = 0
        // The real post-logout chain: no credential -> IOException -> TRANSIENT -> a recheck.
        val source = FakeSource {
            calls += 1
            EntitlementsResult.Unauthenticated(
                EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT)
            )
        }
        val coordinator = build(store, source)
        try {
            coordinator.onIdentityChanged(ownerFence(OWNER))
            advanceUntilIdle()

            coordinator.onSignedOut(ownerFence(OWNER))
            source.identity = null
            assertNull("a landed sign-out gives up the owner", store.record.ownerUid)
            calls = 0

            coordinator.onLocalPremiumSignal()
            // Bounded, not advanceUntilIdle(): the defect is an *unbounded* retry ladder, so an
            // unbounded advance hangs instead of failing. 60 s spans the whole probe window.
            testScheduler.advanceTimeBy(60_000)
            testScheduler.runCurrent()

            assertEquals("a signal after teardown restarted querying", 0, calls)
        } finally {
            // Must run even when the assertion above fails: runTest's own teardown drains the
            // scheduler, so a leftover retry ladder would turn a red test into a hang.
            processJob.cancel()
        }
    }

    /**
     * The credential gate alone. An external sign-out takes the credential away while the record
     * still names its owner, which is the one shape the disk read cannot refuse — so this is the
     * case that fails if the probe goes back to trusting persisted ownership.
     */
    @Test
    fun aPremiumSignalWithNoCredential_whileTheRecordStillNamesItsOwner_startsNothing() = runTest {
        val store = FakeStore(ids())
        var calls = 0
        val source = FakeSource {
            calls += 1
            EntitlementsResult.Unauthenticated(
                EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT)
            )
        }
        val coordinator = build(store, source)
        try {
            coordinator.onIdentityChanged(ownerFence(OWNER))
            advanceUntilIdle()

            source.identity = null
            assertEquals("no teardown ran, so the record still names its owner", OWNER, store.record.ownerUid)
            calls = 0

            coordinator.onLocalPremiumSignal()
            testScheduler.advanceTimeBy(60_000)
            testScheduler.runCurrent()

            assertEquals("a signal with no credential started querying", 0, calls)
        } finally {
            processJob.cancel()
        }
    }

    /**
     * Regression: the identity read used to sit outside the coordinator lock. A sign-out landing
     * in that window left the probe holding a stale-but-matching identity, and it then captured
     * the *post*-sign-out epoch as its own — so every later fence passed.
     *
     * Since slice 5, the null-owner check also refuses this case. This test verifies the outcome,
     * but no longer isolates the placement of the identity read.
     */
    @Test
    fun aSignOutLandingDuringTheIdentityRead_stillStopsTheProbe() = runTest {
        val store = FakeStore(ids())
        var calls = 0
        val source = FakeSource {
            calls += 1
            EntitlementsResult.Unauthenticated(
                EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT)
            )
        }
        val coordinator = build(store, source)
        try {
            coordinator.onIdentityChanged(ownerFence(OWNER))
            advanceUntilIdle()

            val gate = CompletableDeferred<Unit>()
            source.identityGate = gate
            coordinator.onLocalPremiumSignal()
            runCurrent()

            // The sign-out happens while the probe is parked reading the identity.
            val signOut = async { coordinator.onSignedOut(ownerFence(OWNER)) }
            runCurrent()
            source.identity = null
            gate.complete(Unit)
            signOut.await()

            testScheduler.advanceTimeBy(60_000)
            testScheduler.runCurrent()

            assertEquals("the probe outran the sign-out", 0, calls)
        } finally {
            processJob.cancel()
        }
    }

    /**
     * The signal carries no identity, so a callback raised under one account and delivered after
     * a switch runs for whoever is live. That is bounded and cannot leak a grant — the server
     * answers per uid — but it is a decision, so it is stated rather than left to be discovered.
     */
    @Test
    fun aPremiumSignalAfterAnAccountSwitch_runsForTheLiveOwner() = runTest {
        val store = FakeStore(ids())
        val seenAs = mutableListOf<String?>()
        val source = FakeSource {
            seenAs += store.record.ownerUid
            answer(EntitlementsOutcome.StableActive(krxVisible = false), uid = "user-b")
        }
        val coordinator = build(store, source)
        coordinator.onIdentityChanged(ownerFence(OWNER))
        advanceUntilIdle()

        coordinator.onIdentityChanged(ownerFence("user-b"))
        source.identity = EntitlementsIdentity("user-b", 1L)
        advanceUntilIdle()
        seenAs.clear()

        // A callback raised while user-a was signed in, delivered now.
        coordinator.onLocalPremiumSignal()
        advanceUntilIdle()

        assertEquals(listOf("user-b"), seenAs)
        processJob.cancel()
    }

    /**
     * Regression: the probe's own pre-tick epoch check narrows the window but cannot close it. A
     * sign-out landing between that check and the critical section that builds `StartedQuery`
     * makes the captured generation and fence *post*-sign-out, so every later staleness check
     * agrees and a TRANSIENT answer arms a recheck. The epoch has to be re-checked where the
     * state is captured, which is what `requireProbeEpoch` does.
     */
    @Test
    fun aQueryCarryingAStaleProbeEpoch_neverStarts() = runTest {
        val store = FakeStore(ids())
        var calls = 0
        val source = FakeSource {
            calls += 1
            answer(EntitlementsOutcome.StableActive(krxVisible = false))
        }
        val coordinator = build(store, source)
        try {
            coordinator.onIdentityChanged(ownerFence(OWNER))
            advanceUntilIdle()
            calls = 0

            // An epoch no probe can still own — every sign-out and owner change advances it.
            coordinator.refresh(RefreshIntent.FORCE_PREMIUM, requireProbeEpoch = -1L)
            advanceUntilIdle()
            assertEquals("a superseded probe still reached the transport", 0, calls)

            // Positive control through the real path: the probe passes its own live epoch, so a
            // guard that simply blocked everything would fail here. Asserting a literal epoch
            // instead would only re-encode the internal counter, which onIdentityChanged advances.
            coordinator.onLocalPremiumSignal()
            advanceUntilIdle()
            assertEquals("the live epoch was refused too — the guard blocks everything", 1, calls)
        } finally {
            processJob.cancel()
        }
    }

    // --- persistence ordering ----------------------------------------------------------------------

    /**
     * This test used to lock persist-before-observe as "nothing is published when the write fails". The plan never
     * required keeping the previous grant on a failed write, and amendment 7's explicit seal contract does not allow it:
     * the loss is published and what could not be persisted is sealed (S1r-2b). The persist-first order on the success
     * path is still checked by the rotation tests.
     *
     * The fake keeps failing, so recovery keeps retrying: only the work already due is run here.
     */
    @Test
    fun persistenceFailure_publishesTheLossAndSealsWhatWasNotPersisted() = runTest {
        val store = FakeStore(ids())
        val coordinator = build(store, FakeSource { answer(EntitlementsOutcome.StableActive(krxVisible = true)) })
        coordinator.onIdentityChanged(ownerFence(OWNER))
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        assertEquals(KrxCapabilityState.VISIBLE, coordinator.krx.value)
        val epochBefore = store.record.userAccessEpoch
        val grant = coordinator.issuedGrant()

        store.failNextRotation = true
        coordinator.onTopicRejected(grant, PREMIUM_REFUSAL)
        runCurrent()

        assertEquals(PremiumAccessState.Rejected, coordinator.state.value.state)
        assertEquals(KrxCapabilityState.HIDDEN, coordinator.krx.value)
        assertEquals("nothing reached the disk", epochBefore, store.record.userAccessEpoch)
        assertNull("a sealed user axis issues no grant", coordinator.topicGrant())
        processJob.cancel()
    }

    // --- topic grant and refusals (L-4d) --------------------------------------------------------------

    private class Granted(
        val store: FakeStore,
        val source: FakeSource,
        val purger: RecordingPurger,
        val coordinator: PremiumAccessCoordinator,
        val grant: TopicGrantToken,
        val fetches: () -> Int
    )

    /** A bound owner with a confirmed premium grant and the topic grant issued for it. */
    private suspend fun TestScope.granted(
        krxVisible: Boolean = false,
        live: () -> AuthIdentityFence? = { null },
        next: () -> EntitlementsOutcome = { EntitlementsOutcome.StableActive(krxVisible = krxVisible) }
    ): Granted {
        val store = FakeStore(ids())
        var fetches = 0
        val source = FakeSource { fetches += 1; answer(next()) }
        val purger = RecordingPurger(PurgeResult.Completed)
        val coordinator = build(store, source, purger, live)
        coordinator.onIdentityChanged(ownerFence(OWNER))
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)
        return Granted(store, source, purger, coordinator, coordinator.issuedGrant(), { fetches })
    }

    @Test
    fun topicGrant_isIssuedOnlyForAStandingPremiumGrantOfTheBoundLiveOwner() = runTest {
        val never = build(FakeStore(ids()), FakeSource { answer(EntitlementsOutcome.StableActive(krxVisible = false)) })
        assertNull("아무도 바인딩되지 않았는데 grant 가 나왔다", never.topicGrant())

        val store = FakeStore(ids())
        val source = FakeSource { answer(EntitlementsOutcome.StableActive(krxVisible = false)) }
        val coordinator = build(store, source)
        coordinator.onIdentityChanged(ownerFence(OWNER))
        assertNull("권한 확인 전에 grant 가 나왔다", coordinator.topicGrant())

        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        val issued = checkNotNull(coordinator.topicGrant())
        assertEquals(ownerFence(OWNER), issued.identity)
        assertEquals(store.record.userAccessEpoch, issued.userAccessEpoch)

        source.identity = EntitlementsIdentity(OWNER, 2L)
        assertNull("live 세션이 바인딩과 다른데 grant 가 나왔다", coordinator.topicGrant())
        source.identity = null
        assertNull("live 신원을 읽지 못했는데 grant 가 나왔다", coordinator.topicGrant())
        source.identity = EntitlementsIdentity(OWNER, 1L)

        val owned = store.record
        store.record = owned.copy(ownerUid = "someone-else")
        assertNull("디스크 소유자가 바인딩과 다른데 grant 가 나왔다", coordinator.topicGrant())
        store.record = owned

        assertEquals(issued, coordinator.topicGrant())
        processJob.cancel()
    }

    @Test
    fun topicGrant_isTheSameTokenWhileItsContextHolds_andANewOneAfterAKrxRotation() = runTest {
        var krxVisible = true
        val g = granted(next = { EntitlementsOutcome.StableActive(krxVisible = krxVisible) })
        assertEquals(KrxCapabilityState.VISIBLE, g.coordinator.krx.value)

        assertEquals("같은 문맥인데 토큰이 바뀌었다", g.grant, g.coordinator.issuedGrant())

        g.store.record = AccessEpochTransitions.markMayContainData(g.store.record, premium = true, krx = true)
        g.store.record = g.store.record.copy(
            pendingPurges = g.store.record.pendingPurges +
                PendingPurge("someone-else", "old-user", "old-krx", setOf(PurgeScope.USER))
        )
        assertEquals("표식·journal 만 바뀌었는데 토큰이 바뀌었다", g.grant, g.coordinator.issuedGrant())

        krxVisible = false
        g.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        advanceUntilIdle()
        assertEquals(PremiumAccessState.PremiumConfirmed, g.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.HIDDEN, g.coordinator.krx.value)
        assertNotEquals("KRX 가 회전했는데 같은 토큰이다", g.grant, g.coordinator.issuedGrant())
        processJob.cancel()
    }

    @Test
    fun topicGrant_aFailedOrCancelledIssueLeavesTheStandingGrant() = runTest {
        val g = granted()

        g.store.failNextLoad = true
        assertTrue(runCatching { g.coordinator.topicGrant() }.isFailure)
        g.source.identityFailure = java.io.IOException("simulated identity failure")
        assertTrue(runCatching { g.coordinator.topicGrant() }.isFailure)
        val gate = CompletableDeferred<Unit>()
        g.source.identityGate = gate
        val cancelled = async { g.coordinator.topicGrant() }
        runCurrent()
        cancelled.cancel()
        runCurrent()

        g.coordinator.onTopicRejected(g.grant, PREMIUM_REFUSAL)
        assertEquals("실패한 발급이 서 있던 grant 를 바꿨다", PremiumAccessState.Rejected, g.coordinator.state.value.state)
        processJob.cancel()
    }

    @Test
    fun refusals_collapseToOneDecisionPerAcknowledgement_premiumFirst() = runTest {
        val premium = TopicRejectionReason.PREMIUM_REQUIRED
        val krx = TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED
        assertEquals(TopicRejection.PREMIUM_REQUIRED, TopicRejection.of(listOf(krx, premium)))
        assertEquals(TopicRejection.PREMIUM_REQUIRED, TopicRejection.of(listOf(premium, krx)))
        assertEquals(TopicRejection.PREMIUM_REQUIRED, TopicRejection.of(listOf(premium, premium)))
        assertEquals(TopicRejection.KRX_ENTITLEMENT_REQUIRED, TopicRejection.of(listOf(krx, krx)))
        assertNull(
            TopicRejection.of(
                listOf(
                    TopicRejectionReason.TOPICS_DISABLED,
                    TopicRejectionReason.TOPIC_UNAVAILABLE,
                    TopicRejectionReason.UNKNOWN_TOPIC
                )
            )
        )
        assertNull(TopicRejection.of(emptyList()))

        // Consumed in both orders. The reducer's application count is not observable from here, so the
        // yardstick is what one premium refusal does on its own: applied one at a time, KRX first would
        // rotate the capability epoch and leave the premium refusal stale. After premium, the same grant
        // is stale too; a KRX re-query would require incorrectly bypassing that check.
        val alone = granted(krxVisible = true)
        val aloneRotations = alone.store.rotations
        alone.coordinator.onTopicRejected(alone.grant, listOf(premium))
        advanceUntilIdle()
        for (order in listOf(listOf(krx, premium), listOf(premium, krx))) {
            val g = granted(krxVisible = true)
            val rotationsBefore = g.store.rotations
            val fetchesBefore = g.fetches()
            g.coordinator.onTopicRejected(g.grant, order)
            advanceUntilIdle()
            assertEquals("$order: 상태", PremiumAccessState.Rejected, g.coordinator.state.value.state)
            assertEquals("$order: 회전이 premium 거부 하나와 다르다", alone.store.rotations - aloneRotations, g.store.rotations - rotationsBefore)
            assertEquals("$order: purge 가 premium 거부 하나와 다르다", alone.purger.calls.size, g.purger.calls.size)
            assertEquals("$order: premium 거부 뒤에 KRX 재조회가 걸렸다", fetchesBefore, g.fetches())
        }
        processJob.cancel()
    }

    @Test
    fun refusalsThatAreNotAboutAccess_reachNeitherTheReducerNorTheSchedule() = runTest {
        var pending = false
        val g = granted(krxVisible = true, next = {
            if (pending) EntitlementsOutcome.Pending(krxVisible = true, retryAfterSeconds = 5)
            else EntitlementsOutcome.StableActive(krxVisible = true)
        })
        pending = true
        g.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()
        // One pending answer is enough to arm the retry. Left pending, every retry would arm the next
        // one, and a failing assertion below would turn into a test that never finishes.
        pending = false
        val loadsBefore = g.store.loads
        val recordBefore = g.store.record
        val fetchesBefore = g.fetches()

        g.coordinator.onTopicRejected(g.grant, listOf(TopicRejectionReason.TOPIC_UNAVAILABLE))
        g.coordinator.onTopicRejected(g.grant, emptyList())
        runCurrent()

        assertEquals(loadsBefore, g.store.loads)
        assertEquals(recordBefore, g.store.record)
        assertEquals(PremiumAccessState.PremiumConfirmed, g.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.VISIBLE, g.coordinator.krx.value)

        // The retry the pending answer scheduled still runs: nothing here cancelled or replaced it.
        advanceTimeBy(5_100)
        runCurrent()
        assertTrue("접근과 무관한 거부가 예약된 재조회를 없앴다", g.fetches() > fetchesBefore)
        processJob.cancel()
    }

    @Test
    fun aKrxRefusalForTheStandingGrant_rotatesTheCapabilityOnlyAndAsksAgain() = runTest {
        var krxVisible = true
        val g = granted(next = { EntitlementsOutcome.StableActive(krxVisible = krxVisible) })
        assertEquals(KrxCapabilityState.VISIBLE, g.coordinator.krx.value)
        // The re-query the refusal asks for answers as the server would after revoking KRX.
        krxVisible = false
        val before = g.store.record
        val fetchesBefore = g.fetches()

        g.coordinator.onTopicRejected(g.grant, KRX_REFUSAL)
        advanceUntilIdle()

        assertEquals(PremiumAccessState.PremiumConfirmed, g.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.HIDDEN, g.coordinator.krx.value)
        assertEquals(before.userAccessEpoch, g.store.record.userAccessEpoch)
        assertNotEquals(before.krxCapabilityEpoch, g.store.record.krxCapabilityEpoch)
        assertTrue("KRX 거부 뒤 재조회가 없었다", g.fetches() > fetchesBefore)
        processJob.cancel()
    }

    @Test
    fun aKrxRefusalWithKrxAlreadyHidden_rotatesNothingButStillAsksAgain() = runTest {
        val g = granted(krxVisible = false)
        val rotationsBefore = g.store.rotations
        val fetchesBefore = g.fetches()

        g.coordinator.onTopicRejected(g.grant, KRX_REFUSAL)
        advanceUntilIdle()

        assertEquals("숨겨진 KRX 를 다시 회전했다", rotationsBefore, g.store.rotations)
        assertEquals("아무것도 안 바뀌었는데 토큰이 바뀌었다", g.grant, g.coordinator.issuedGrant())
        assertTrue("KRX 거부 뒤 재조회가 없었다", g.fetches() > fetchesBefore)
        processJob.cancel()
    }

    @Test
    fun aPremiumRefusalForTheStandingGrant_tearsTheGrantDown() = runTest {
        val g = granted(krxVisible = true)
        val before = g.store.record

        g.coordinator.onTopicRejected(g.grant, PREMIUM_REFUSAL)
        advanceUntilIdle()

        assertEquals(PremiumAccessState.Rejected, g.coordinator.state.value.state)
        assertEquals(KrxCapabilityState.HIDDEN, g.coordinator.krx.value)
        assertNotEquals(before.userAccessEpoch, g.store.record.userAccessEpoch)
        assertNotEquals(before.krxCapabilityEpoch, g.store.record.krxCapabilityEpoch)
        assertTrue(g.purger.calls.isNotEmpty())
        assertNull("거부된 뒤에도 topic grant 가 나왔다", g.coordinator.topicGrant())
        processJob.cancel()
    }

    /**
     * A refusal delivered after its context moved, with nobody having asked for a new grant.
     *
     * The token, generation, record-fence and live-identity cases isolate those checks.
     * Rebinding to another account changes generation, record fence and binding together.
     */
    @Test
    fun aDelayedRefusalIsDiscarded_whenItsContextMovedWithoutAReissue() = runTest {
        val external = run { var n = 0; EpochIdGenerator { "external-${n++}" } }
        fun case(name: String, move: suspend Granted.() -> TopicGrantToken) = name to move
        val cases = listOf(
            case("토큰 불일치") { TopicGrantToken(grant.value + 100) },
            case("같은 신원 재바인딩(세대만 이동)") { coordinator.onIdentityChanged(ownerFence(OWNER)); grant },
            case("user epoch 회전") {
                store.record = AccessEpochTransitions.rotate(store.record, rotateUser = true, rotateKrx = false, ids = external)
                grant
            },
            case("KRX epoch 회전") {
                store.record = AccessEpochTransitions.rotate(store.record, rotateUser = false, rotateKrx = true, ids = external)
                grant
            },
            case("live 세션 이동") { source.identity = EntitlementsIdentity(OWNER, 2L); grant },
            case("live 신원 읽기 불가") { source.identity = null; grant },
            case("다른 계정 바인딩") { coordinator.onIdentityChanged(ownerFence("user-b")); grant }
        )
        for ((name, move) in cases) {
            val g = granted()
            val token = g.move()
            val stateBefore = g.coordinator.state.value
            val recordBefore = g.store.record
            val purgesBefore = g.purger.calls.size

            g.coordinator.onTopicRejected(token, PREMIUM_REFUSAL)
            advanceUntilIdle()

            assertEquals("$name: 상태가 바뀌었다", stateBefore, g.coordinator.state.value)
            assertEquals("$name: 기록이 바뀌었다", recordBefore, g.store.record)
            assertEquals("$name: purge 가 돌았다", purgesBefore, g.purger.calls.size)
        }
        processJob.cancel()
    }

    @Test
    fun aDelayedRefusalIsDiscarded_whileASignOutIsOpen() = runTest {
        val g = granted(live = { ownerFence(OWNER) })
        assertTrue(g.coordinator.prepareSignOut(ownerFence(OWNER)) is SignOutStart.Armed)
        val loadsBefore = g.store.loads
        val recordBefore = g.store.record

        g.coordinator.onTopicRejected(g.grant, PREMIUM_REFUSAL)
        advanceUntilIdle()

        assertEquals("봉인 중에 저장소를 읽었다", loadsBefore, g.store.loads)
        assertEquals(recordBefore, g.store.record)
        assertNull("봉인 중에 grant 가 나왔다", g.coordinator.topicGrant())
        processJob.cancel()
    }

    @Test
    fun aReissuedGrantIsAccepted_whereTheOneItReplacedIsNot() = runTest {
        var krxVisible = true
        val g = granted(next = { EntitlementsOutcome.StableActive(krxVisible = krxVisible) })
        krxVisible = false
        g.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        advanceUntilIdle()
        val replacement = g.coordinator.issuedGrant()
        assertNotEquals(g.grant, replacement)

        g.coordinator.onTopicRejected(g.grant, PREMIUM_REFUSAL)
        advanceUntilIdle()
        assertEquals("대체된 grant 의 거부가 적용됐다", PremiumAccessState.PremiumConfirmed, g.coordinator.state.value.state)

        g.coordinator.onTopicRejected(replacement, PREMIUM_REFUSAL)
        advanceUntilIdle()
        assertEquals(PremiumAccessState.Rejected, g.coordinator.state.value.state)
        processJob.cancel()
    }

    /**
     * What a discard leaves alone is observed by the scheduled retry actually running, not by a
     * count of timers.
     */
    @Test
    fun aDiscardedOrFailedRefusal_leavesTheScheduledRecheckToRun() = runTest {
        var pending = false
        val g = granted(next = {
            if (pending) EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 5)
            else EntitlementsOutcome.StableActive(krxVisible = false)
        })
        pending = true
        g.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        runCurrent()
        // Armed once; see refusalsThatAreNotAboutAccess for why it is not left pending.
        pending = false
        assertEquals(PremiumAccessState.PremiumConfirmed, g.coordinator.state.value.state)
        val fetchesBefore = g.fetches()

        g.source.identity = null
        g.coordinator.onTopicRejected(g.grant, PREMIUM_REFUSAL)
        g.source.identity = EntitlementsIdentity(OWNER, 1L)
        g.store.failNextLoad = true
        assertTrue(runCatching { g.coordinator.onTopicRejected(g.grant, PREMIUM_REFUSAL) }.isFailure)
        g.source.identityFailure = java.io.IOException("simulated identity failure")
        assertTrue(runCatching { g.coordinator.onTopicRejected(g.grant, PREMIUM_REFUSAL) }.isFailure)
        assertEquals(PremiumAccessState.PremiumConfirmed, g.coordinator.state.value.state)

        advanceTimeBy(5_100)
        runCurrent()
        assertTrue("버려진·실패한 거부가 예약된 재조회를 없앴다", g.fetches() > fetchesBefore)
        processJob.cancel()
    }

    // --- sign-out and journal ------------------------------------------------------------------------

    @Test
    fun signOut_rotatesAndJournals_soTheNamespaceIsNotInherited() = runTest {
        val store = FakeStore(ids())
        val purger = RecordingPurger(PurgeResult.Deferred("test"))
        val coordinator = build(store, FakeSource { answer(EntitlementsOutcome.StableActive(krxVisible = false)) }, purger)
        coordinator.onIdentityChanged(ownerFence(OWNER))
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        val epochWhileSignedIn = store.record.userAccessEpoch

        coordinator.onSignedOut(ownerFence(OWNER))
        advanceUntilIdle()

        assertNotEquals(epochWhileSignedIn, store.record.userAccessEpoch)
        assertEquals(epochWhileSignedIn, store.record.pendingPurges.single().userAccessEpoch)
        assertEquals(OWNER, store.record.pendingPurges.single().ownerUid)
        assertEquals(PremiumAccessState.NoGrant, coordinator.state.value.state)
        processJob.cancel()
    }

    /**
     * Slice 6: the record a corrupt file is replaced with reaches the purgers with nobody signed in.
     *
     * That is the whole point of writing an obligation rather than an empty record — the startup
     * resume runs before any identity event, and an empty journal would end it there.
     */
    @Test
    fun aRecoveredRecord_deliversItsUnknownObligationToBothPurgers_whileSignedOut() = runTest {
        val store = FakeStore(ids())
        store.record = accessEpochRecoveryRecord(ids())
        val purger = RecordingPurger(PurgeResult.Deferred("not owned by this slice"))
        val coordinator = build(store, FakeSource { answer(EntitlementsOutcome.StableActive(krxVisible = false)) }, purger)

        coordinator.resumePendingPurges()
        advanceUntilIdle()

        assertEquals("both scopes are asked", 2, purger.calls.size)
        assertTrue("the target is unknown, not narrowed", purger.calls.all { it.ownerUid == null })
        assertTrue(purger.calls.all { it.pending.userAccessEpoch == null && it.pending.krxCapabilityEpoch == null })
        assertEquals(
            "a deferred purge keeps the obligation for the next start",
            1,
            store.record.pendingPurges.size
        )
        processJob.cancel()
    }

    @Test
    fun deferredPurge_keepsTheJournalForALaterAttempt() = runTest {
        val store = FakeStore(ids())
        val purger = RecordingPurger(PurgeResult.Deferred("not owned by this slice"))
        val coordinator = build(store, FakeSource { answer(EntitlementsOutcome.StableActive(krxVisible = false)) }, purger)
        coordinator.onIdentityChanged(ownerFence(OWNER))
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()

        coordinator.onTopicRejected(coordinator.issuedGrant(), PREMIUM_REFUSAL)
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

        coordinator.onIdentityChanged(ownerFence("user-a"))
        coordinator.onIdentityChanged(ownerFence("user-b"))
        coordinator.onIdentityChanged(ownerFence("user-c"))
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
            clock = { testScheduler.currentTime },
            liveFence = { null }
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
        coordinator.onIdentityChanged(ownerFence(OWNER))
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()

        coordinator.onTopicRejected(coordinator.issuedGrant(), PREMIUM_REFUSAL)
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
        coordinator.onIdentityChanged(ownerFence(OWNER))

        coordinator.refresh(RefreshIntent.IF_STALE)
        advanceUntilIdle()

        assertEquals("the scheduled retry never ran", 2, calls)
        assertEquals(PremiumAccessState.FreeConfirmed, coordinator.state.value.state)
        assertFalse(coordinator.state.value.state.grantsPremiumRuntime)
        processJob.cancel()
    }

    /**
     * Persist-before-observe is the rule for handing a grant out. Taking one away is the mirror:
     * `store.signOut()` is disk I/O, and until it returns a reader still sees the old grant. A
     * same-uid sign-in landing in that window matches on uid and opens the premium surface on a
     * session that no longer exists.
     */
    @Test
    fun signOut_revokesBeforeItPersists() = runTest {
        val store = FakeStore(ids())
        val coordinator = build(
            store,
            FakeSource { answer(EntitlementsOutcome.StableActive(krxVisible = false)) }
        )
        coordinator.onIdentityChanged(ownerFence(OWNER))
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)

        val gate = CompletableDeferred<Unit>()
        store.blockNextSignOutOn = gate
        val teardown = launch { coordinator.onSignedOut(ownerFence(OWNER)) }
        advanceUntilIdle()

        assertEquals(
            "the old grant stayed readable while the teardown was still writing",
            PremiumAccessState.NoGrant,
            coordinator.state.value.state
        )
        assertNull("the old owner stayed readable too", coordinator.state.value.uid)

        gate.complete(Unit)
        teardown.join()
        processJob.cancel()
    }

    /** Same rule when the owner changes rather than leaves. */
    @Test
    fun ownerChange_revokesBeforeItPersists() = runTest {
        val store = FakeStore(ids())
        val coordinator = build(
            store,
            FakeSource { answer(EntitlementsOutcome.StableActive(krxVisible = false)) }
        )
        coordinator.onIdentityChanged(ownerFence(OWNER))
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)

        val gate = CompletableDeferred<Unit>()
        store.blockNextBindOn = gate
        val rebind = launch { coordinator.onIdentityChanged(ownerFence("user-b")) }
        advanceUntilIdle()

        assertEquals(
            "the previous owner's grant stayed readable while the bind was still writing",
            PremiumAccessState.NoGrant,
            coordinator.state.value.state
        )

        gate.complete(Unit)
        rebind.join()
        processJob.cancel()
    }

    /**
     * The published grant has to say which *session* it belongs to. A sign-out followed by a
     * sign-in of the same user rotates only the auth generation, so a reader comparing uid alone
     * cannot tell the torn-down session's grant from the new one's.
     */
    @Test
    fun theGrantIsPublishedWithTheSessionItWasDecidedFor() = runTest {
        val store = FakeStore(ids())
        val source = FakeSource(
            identity = EntitlementsIdentity(OWNER, 7L)
        ) { answer(EntitlementsOutcome.StableActive(krxVisible = false), generation = 7L) }
        val coordinator = build(store, source)

        // The generation now travels with the observation instead of being re-read at bind time,
        // so the caller states the session it saw. That is the point of the change: a binding can
        // no longer pair one observation's uid with another's generation.
        coordinator.onIdentityChanged(ownerFence(OWNER, 7L))
        advanceUntilIdle()
        assertEquals(
            "the binding published no session",
            7L,
            coordinator.state.value.authGeneration
        )

        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)
        assertEquals(
            "the grant lost the session it was decided for",
            7L,
            coordinator.state.value.authGeneration
        )
        processJob.cancel()
    }

    /**
     * An identity boundary keeps the server's floor and cancels the pending retry, so a new
     * binding's first query is refused with nothing to fold into. Without a deferral it is simply
     * dropped and that user stays on `NoGrant` with nothing scheduled to ask again.
     */
    @Test
    fun aFirstQueryRefusedByAPreservedFloor_stillRunsWhenTheFloorLapses() = runTest {
        val store = FakeStore(ids())
        val firedAt = mutableListOf<Long>()
        val modes = mutableListOf<Boolean>()
        val source = FakeSource { fresh ->
            firedAt += testScheduler.currentTime
            modes += fresh
            if (firedAt.size == 1) {
                answer(EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 30L))
            } else {
                answer(EntitlementsOutcome.StableActive(krxVisible = false))
            }
        }
        val coordinator = build(store, source)
        try {
            val firstBinding = coordinator.onIdentityChanged(ownerFence(OWNER)).boundGeneration()
            coordinator.refresh(RefreshIntent.FORCE_PREMIUM, requireDecisionGeneration = firstBinding)
            advanceTimeBy(1_000L)
            val liveBinding = coordinator.onIdentityChanged(ownerFence(OWNER)).boundGeneration()
            coordinator.refresh(RefreshIntent.FORCE_PREMIUM, requireDecisionGeneration = liveBinding)
            advanceTimeBy(28_999L)
            runCurrent()
            assertEquals("the new binding queried inside the preserved floor", listOf(0L), firedAt)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals("the live deferred refresh missed its deadline", listOf(0L, 30_000L), firedAt)
            assertEquals(listOf(true, true), modes)
            assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)
        } finally {
            processJob.cancel()
        }
    }

    @Test
    fun aQueuedLookupForARetiredGeneration_cannotReviveAPreservedFloor() = runTest {
        val store = FakeStore(ids())
        var fetches = 0
        val source = FakeSource {
            fetches += 1
            if (fetches == 1) {
                answer(EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 30L))
            } else {
                EntitlementsResult.Unauthenticated(
                    EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT)
                )
            }
        }
        val coordinator = build(store, source)
        try {
            val binding = coordinator.onIdentityChanged(ownerFence(OWNER)).boundGeneration()
            coordinator.refresh(RefreshIntent.FORCE_PREMIUM, requireDecisionGeneration = binding)
            advanceTimeBy(1_000L)

            // The binder queued this launch while binding N was alive; logout wins delivery.
            val queuedLookup = launch {
                coordinator.refresh(RefreshIntent.FORCE_PREMIUM, requireDecisionGeneration = binding)
            }
            source.identity = null
            coordinator.onSignedOut(ownerFence(OWNER))
            runCurrent()
            queuedLookup.join()
            advanceTimeBy(30_000L)
            runCurrent()
            assertEquals("the stale lookup resurrected the cancelled timer", 1, fetches)
            assertEquals(OwnedPremiumAccess(), coordinator.state.value)
        } finally {
            processJob.cancel()
        }
    }

    @Test
    fun aStaleProbeTick_cannotReviveAPreservedFloor() = runTest {
        val store = FakeStore(ids())
        var fetches = 0
        val source = FakeSource {
            fetches += 1
            answer(EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 30L))
        }
        val coordinator = build(store, source)
        try {
            coordinator.onIdentityChanged(ownerFence(OWNER))
            coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
            advanceTimeBy(1_000L)
            source.identity = null
            coordinator.onSignedOut(ownerFence(OWNER))
            // The first binding owns probe epoch 1; sign-out has advanced it to 2.
            coordinator.refresh(RefreshIntent.FORCE_PREMIUM, requireProbeEpoch = 1L)
            advanceTimeBy(30_000L)
            runCurrent()
            assertEquals("a retired probe revived the timer before its epoch check", 1, fetches)
            assertEquals(OwnedPremiumAccess(), coordinator.state.value)
        } finally {
            processJob.cancel()
        }
    }

    @Test
    fun aStaleLookup_cannotUpgradeTheNewBindingsDeferredIntent() = runTest {
        val store = FakeStore(ids())
        val modes = mutableListOf<Boolean>()
        val source = FakeSource { fresh ->
            modes += fresh
            if (modes.size == 1) {
                answer(EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 30L))
            } else {
                answer(EntitlementsOutcome.StableInactive(krxVisible = false))
            }
        }
        val coordinator = build(store, source)
        try {
            val oldBinding = coordinator.onIdentityChanged(ownerFence(OWNER)).boundGeneration()
            coordinator.refresh(RefreshIntent.IF_STALE, requireDecisionGeneration = oldBinding)
            advanceTimeBy(1_000L)
            val newBinding = coordinator.onIdentityChanged(ownerFence(OWNER)).boundGeneration()
            coordinator.refresh(RefreshIntent.IF_STALE, requireDecisionGeneration = newBinding)
            coordinator.refresh(RefreshIntent.FORCE_PREMIUM, requireDecisionGeneration = oldBinding)
            advanceTimeBy(29_000L)
            runCurrent()
            assertEquals("stale work strengthened the live binding's retry", listOf(false, false), modes)
            assertEquals(PremiumAccessState.FreeConfirmed, coordinator.state.value.state)
        } finally {
            processJob.cancel()
        }
    }

    @Test
    fun aScheduledCallbackForARetiredEpoch_cannotQueryOrRearm() = runTest {
        val store = FakeStore(ids())
        var fetches = 0
        val source = FakeSource {
            fetches += 1
            answer(EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 30L))
        }
        val coordinator = build(store, source)
        try {
            coordinator.onIdentityChanged(ownerFence(OWNER)) // first binding owns probe epoch 1
            coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
            advanceTimeBy(1_000L)
            source.identity = null
            coordinator.onSignedOut(ownerFence(OWNER))
            // Model a callback already delivered when cancellation arrives. SCHEDULED bypasses
            // the floor, but must still validate the epoch captured when the timer was armed.
            coordinator.refresh(
                RefreshIntent.FORCE_PREMIUM,
                origin = QueryOrigin.SCHEDULED,
                requireProbeEpoch = 1L
            )
            advanceTimeBy(30_000L)
            runCurrent()
            assertEquals("scheduled origin bypassed the binding lifetime check", 1, fetches)
            assertEquals(OwnedPremiumAccess(), coordinator.state.value)
        } finally {
            processJob.cancel()
        }
    }

    /** The grant a topic session would be issued right now; fails the test when there is none. */
    private suspend fun PremiumAccessCoordinator.issuedGrant(): TopicGrantToken =
        checkNotNull(topicGrant()) { "no topic grant was issued" }.grant

    private companion object {
        val PREMIUM_REFUSAL = listOf(TopicRejectionReason.PREMIUM_REQUIRED)
        val KRX_REFUSAL = listOf(TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED)
        const val OWNER = "user-a"
    }

    // ---- identity mixing (L-4b) -------------------------------------------------------------------

    /**
     * C1 — the binding publishes the generation it was **handed**, not one it read for itself.
     *
     * The two are deliberately different here. They agree whenever the observation the binding
     * was handed is the live one — which is the common case, and exactly why a re-read survived
     * this long: it produced the right answer for the wrong reason. They do **not** always agree;
     * the delayed-observation race two tests below is the case where they differ on a real path.
     * Using different generations makes the source of the published value observable in this test.
     */
    @Test
    fun theBindingUsesTheObservedGenerationRatherThanRereadingIt() = runTest {
        val store = FakeStore(ids())
        // The source would answer 1. The observation carried 9.
        val source = FakeSource(identity = EntitlementsIdentity(OWNER, 1L)) {
            answer(EntitlementsOutcome.StableActive(krxVisible = false), generation = 1L)
        }
        val coordinator = build(store, source)

        coordinator.onIdentityChanged(ownerFence(OWNER, 9L))
        advanceUntilIdle()

        assertEquals(
            "the binding re-read the generation instead of using the one it was given",
            9L,
            coordinator.state.value.authGeneration
        )
    }

    /**
     * C2 — an answer from a session the binding is not standing on is refused.
     *
     * The delayed-observation race: bound from `(OWNER, 1)` while the live session is already
     * `(OWNER, 2)`, so the query runs and answers as 2. Every earlier check passes — the decision
     * generation has not moved, the namespace is the same, the owner uid matches, and the answer's
     * session *is* the live one. Without the bound-identity comparison the grant lands and is
     * published carrying the 1 the binding still holds.
     */
    @Test
    fun anAnswerFromASessionTheBindingIsNotOnIsRefused() = runTest {
        val store = FakeStore(ids())
        val source = FakeSource(identity = EntitlementsIdentity(OWNER, 2L)) {
            answer(EntitlementsOutcome.StableActive(krxVisible = false), generation = 2L)
        }
        val coordinator = build(store, source)

        coordinator.onIdentityChanged(ownerFence(OWNER, 1L))
        advanceUntilIdle()
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()

        assertEquals(
            "a grant decided on another session was applied",
            PremiumAccessState.NoGrant,
            coordinator.state.value.state
        )
    }

    /**
     * C2 control — the same setup with the binding standing on the answering session **does**
     * apply. Without this the test above would pass on a coordinator that refuses everything.
     */
    @Test
    fun anAnswerFromTheSessionTheBindingIsOnIsApplied() = runTest {
        val store = FakeStore(ids())
        val source = FakeSource(identity = EntitlementsIdentity(OWNER, 2L)) {
            answer(EntitlementsOutcome.StableActive(krxVisible = false), generation = 2L)
        }
        val coordinator = build(store, source)

        coordinator.onIdentityChanged(ownerFence(OWNER, 2L))
        advanceUntilIdle()
        coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()

        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)
    }
}

/**
 * An explicit binding fence, independent of FakeSource.identity.
 *
 * Generation 1 is a fixture default, not a reconstruction of every old source read. The old
 * onOwnerChanged read returned 1 for the default FakeSource identity, but null when that identity
 * was null. This helper supplies generation 1 even in those null-source cases, so their bindings
 * now carry a generation where the old bindings did not.
 * Tests requiring another generation pass it explicitly; the identity-mixing tests are above.
 */
private fun ownerFence(uid: String, generation: Long = 1L) = AuthIdentityFence(uid, generation)
