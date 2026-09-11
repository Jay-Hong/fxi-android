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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An app sign-out attempt inside the coordinator: the prologue, the intent write, the seal over the
 * four access entry points, and what identity events do while the attempt is open.
 *
 * Whether an attempt is still open is read through the coordinator's own answers: a second request
 * for the same fence is told [SignOutStart.Joined] while it is, and is judged afresh once it is not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PremiumAccessCoordinatorSignOutTest {

    private val processJob = SupervisorJob()

    @After
    fun tearDown() = processJob.cancel()

    private enum class BeginFailure { BEFORE_WRITE, AFTER_WRITE, CANCEL_AFTER_WRITE }

    private class Store(private val ids: EpochIdGenerator) : AccessEpochStore {
        var record = AccessEpochRecord()
        var loads = 0
        var signOuts = 0
        var beginFailure: BeginFailure? = null
        /** Once the intent write has failed, reading the record fails too. */
        var readBackFails = false
        var blockBeginSignOutOn: CompletableDeferred<Unit>? = null
        var blockNextLoadOn: CompletableDeferred<Unit>? = null
        var afterLoad: () -> Unit = {}
        private var failLoads = false

        override suspend fun load(): AccessEpochRecord {
            loads += 1
            blockNextLoadOn?.let { gate -> blockNextLoadOn = null; gate.await() }
            if (failLoads) throw IOException("simulated read failure")
            return record.also { afterLoad() }
        }
        override suspend fun bindOwner(uid: String) =
            AccessEpochTransitions.bindOwner(record, uid, ids).also { record = it }
        override suspend fun signOut(): AccessEpochRecord {
            signOuts += 1
            return AccessEpochTransitions.signOut(record, ids).also { record = it }
        }
        override suspend fun beginSignOut(uid: String): AccessEpochRecord {
            blockBeginSignOutOn?.let { gate -> blockBeginSignOutOn = null; gate.await() }
            when (beginFailure) {
                BeginFailure.BEFORE_WRITE -> {
                    failLoads = readBackFails
                    throw IOException("simulated write failure")
                }
                BeginFailure.AFTER_WRITE, BeginFailure.CANCEL_AFTER_WRITE -> {
                    record = AccessEpochTransitions.beginSignOut(record, uid)
                    failLoads = readBackFails
                    if (beginFailure == BeginFailure.CANCEL_AFTER_WRITE) {
                        throw CancellationException("simulated store cancellation after the write")
                    }
                    throw IOException("simulated failure after the write")
                }
                null -> Unit
            }
            return AccessEpochTransitions.beginSignOut(record, uid).also { record = it }
        }
        override suspend fun beginRotation(rotateUser: Boolean, rotateKrx: Boolean) =
            AccessEpochTransitions.rotate(record, rotateUser, rotateKrx, ids).also { record = it }
        override suspend fun completePurges(completed: Collection<PendingPurge>) =
            AccessEpochTransitions.completePurges(record, completed).also { record = it }
        override suspend fun markMayContainData(premium: Boolean, krx: Boolean) =
            AccessEpochTransitions.markMayContainData(record, premium, krx).also { record = it }
    }

    private class Purger(var result: PurgeResult = PurgeResult.Deferred("unimplemented")) :
        UserScopePurger, CapabilityScopePurger {
        var beforeAnswer: () -> Unit = {}
        override suspend fun purgeUserScope(namespace: PurgeNamespace) = result.also { beforeAnswer() }
        override suspend fun purgeCapabilityScope(namespace: PurgeNamespace) = result.also { beforeAnswer() }
    }

    private val a7 = AuthIdentityFence("user-a", 7)
    private val a8 = AuthIdentityFence("user-a", 8)
    private val b3 = AuthIdentityFence("user-b", 3)

    private class Harness(
        val coordinator: PremiumAccessCoordinator,
        val store: Store,
        val purger: Purger,
        val fetches: () -> Int,
        val setLive: (AuthIdentityFence?) -> Unit,
        val releaseFetch: CompletableDeferred<Unit>?
    )

    private fun TestScope.harness(
        live: AuthIdentityFence? = a7,
        holdFetch: Boolean = false
    ): Harness {
        var n = 0
        val store = Store(EpochIdGenerator { "epoch-${n++}" })
        val purger = Purger()
        var current = live
        var fetchCount = 0
        val release = if (holdFetch) CompletableDeferred<Unit>() else null
        val coordinator = PremiumAccessCoordinator(
            source = object : EntitlementsSource {
                override suspend fun currentIdentity() =
                    current?.let { EntitlementsIdentity(it.uid, it.authGeneration) }

                override suspend fun fetch(freshPremium: Boolean): EntitlementsResult {
                    fetchCount += 1
                    release?.await()
                    val who = checkNotNull(current)
                    return EntitlementsResult.Answered(
                        EntitlementsIdentity(who.uid, who.authGeneration),
                        EntitlementsOutcome.StableActive(krxVisible = false)
                    )
                }
            },
            store = store,
            userPurger = purger,
            capabilityPurger = purger,
            scope = CoroutineScope(processJob + StandardTestDispatcher(testScheduler)),
            clock = { testScheduler.currentTime },
            jitter = ProbeJitter.None,
            liveFence = { current }
        )
        return Harness(coordinator, store, purger, { fetchCount }, { current = it }, release)
    }

    /** Binds [fence] and lets its grant land, so a seal is visible as the grant disappearing. */
    private suspend fun TestScope.premiumFor(h: Harness, fence: AuthIdentityFence) {
        h.coordinator.onIdentityChanged(fence)
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
    }

    // Prologue and intent write

    @Test
    fun aStaleRequestSealsNothingAndWritesNothing() = runTest {
        val h = harness()
        premiumFor(h, a7)
        h.setLive(a8)

        assertEquals(SignOutStart.Stale, h.coordinator.prepareSignOut(a7))
        assertEquals(PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertNull(h.store.record.teardownOwedFor)
    }

    @Test
    fun theSealLandsBeforeTheIntentIsWritten() = runTest {
        val h = harness()
        premiumFor(h, a7)
        val gate = CompletableDeferred<Unit>()
        h.store.blockBeginSignOutOn = gate

        val start = async { h.coordinator.prepareSignOut(a7) }
        runCurrent()
        // The write runs non-cancellable, so a failing assertion must still open the gate or the
        // test hangs instead of failing.
        try {
            assertEquals("권한이 의도 기록 중에도 읽혔다", PremiumAccessState.NoGrant, h.coordinator.state.value.state)
            assertEquals(KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
            assertNull(h.store.record.teardownOwedFor)
        } finally {
            gate.complete(Unit)
        }
        assertTrue(start.await() is SignOutStart.Armed)
        assertEquals("user-a", h.store.record.teardownOwedFor)
    }

    @Test
    fun aRequestWithoutACompletedBindingRequiresRecovery() = runTest {
        val h = harness()

        assertTrue(h.coordinator.prepareSignOut(a7) is SignOutStart.RecoveryRequired)
        assertNull(h.store.record.teardownOwedFor)
    }

    @Test
    fun repeatedRequestsJoinOrAreBusy() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        val armed = h.coordinator.prepareSignOut(a7) as SignOutStart.Armed

        assertEquals(SignOutStart.Joined(armed.ticket), h.coordinator.prepareSignOut(a7))
        assertEquals(SignOutStart.Busy(armed.ticket), h.coordinator.prepareSignOut(b3))
    }

    @Test
    fun aFailedWriteThatLandedStillArms() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        h.store.beginFailure = BeginFailure.AFTER_WRITE

        assertTrue(h.coordinator.prepareSignOut(a7) is SignOutStart.Armed)
    }

    @Test
    fun aFailedWriteThatDidNotLandRequiresRecovery() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        h.store.beginFailure = BeginFailure.BEFORE_WRITE

        assertTrue(h.coordinator.prepareSignOut(a7) is SignOutStart.RecoveryRequired)
        assertNull(h.store.record.teardownOwedFor)
    }

    @Test
    fun anUnreadableOutcomeHoldsIdentityEvents() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        h.store.beginFailure = BeginFailure.BEFORE_WRITE
        h.store.readBackFails = true

        assertTrue(h.coordinator.prepareSignOut(a7) is SignOutStart.RecoveryRequired)
        val signOutsBefore = h.store.signOuts
        assertNull("결과를 모르는 편집 뒤에 신원 사건이 적용됐다", h.coordinator.onIdentityChanged(b3))
        assertFalse(h.coordinator.onSignedOut(a7))
        assertEquals(signOutsBefore, h.store.signOuts)
    }

    @Test
    fun identityMovingDuringTheWriteRequiresRecovery() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        val gate = CompletableDeferred<Unit>()
        h.store.blockBeginSignOutOn = gate

        val start = async { h.coordinator.prepareSignOut(a7) }
        runCurrent()
        h.setLive(a8)
        gate.complete(Unit)

        assertTrue(start.await() is SignOutStart.RecoveryRequired)
        assertEquals("user-a", h.store.record.teardownOwedFor)
    }

    @Test
    fun cancellationDuringTheFirstReadKeepsAccessAndIdentityEventsSealed() = runTest {
        val h = harness()
        premiumFor(h, a7)
        val gate = CompletableDeferred<Unit>()
        h.store.blockNextLoadOn = gate
        val start = async { h.coordinator.prepareSignOut(a7) }
        runCurrent()
        start.cancel()
        gate.complete(Unit)
        start.join()

        val loadsBefore = h.store.loads
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        assertNull(h.coordinator.onIdentityChanged(a8))
        assertFalse(h.coordinator.onSignedOut(a7))
        assertEquals(loadsBefore, h.store.loads)
        assertEquals(PremiumAccessState.NoGrant, h.coordinator.state.value.state)
    }

    @Test
    fun aStoreCancellationAfterTheWriteIsReconciled() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        h.store.beginFailure = BeginFailure.CANCEL_AFTER_WRITE

        assertTrue(h.coordinator.prepareSignOut(a7) is SignOutStart.Armed)
        assertEquals("user-a", h.store.record.teardownOwedFor)
    }

    @Test
    fun identityMovingDuringTheFirstReadPreventsTheIntentWrite() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        h.store.afterLoad = { h.setLive(a8) }

        assertTrue(h.coordinator.prepareSignOut(a7) is SignOutStart.RecoveryRequired)
        assertNull(h.store.record.teardownOwedFor)
        // Handed to recovery, not left unresolved: the next identity event is admitted.
        h.store.afterLoad = {}
        assertNotNull(h.coordinator.onIdentityChanged(a8))
    }

    // The seal

    @Test
    fun anOpenAttemptRejectsRefreshProbeAndTopicBeforeSideEffects() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        assertTrue(h.coordinator.prepareSignOut(a7) is SignOutStart.Armed)
        val fetchesBefore = h.fetches()
        val loadsBefore = h.store.loads
        val recordBefore = h.store.record

        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        h.coordinator.onLocalPremiumSignal()
        h.coordinator.onTopicRejected(TopicRejection.PREMIUM_REQUIRED)
        advanceUntilIdle()

        assertEquals("봉인 중에 조회가 나갔다", fetchesBefore, h.fetches())
        assertEquals("봉인 가드보다 저장소 읽기가 먼저였다", loadsBefore, h.store.loads)
        assertEquals(recordBefore, h.store.record)
        assertEquals(PremiumAccessState.NoGrant, h.coordinator.state.value.state)
    }

    @Test
    fun anAnswerLandingAfterTheSealIsDropped() = runTest {
        val h = harness(holdFetch = true)
        h.coordinator.onIdentityChanged(a7)
        val inFlight = async { h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
        runCurrent()
        assertEquals(1, h.fetches())

        assertTrue(h.coordinator.prepareSignOut(a7) is SignOutStart.Armed)
        // The seal moved the decision generation, so the answer would be dropped further down
        // anyway. What this pins is that it is dropped first — before the store is read.
        val loadsBefore = h.store.loads
        h.releaseFetch!!.complete(Unit)
        inFlight.await()
        advanceUntilIdle()

        assertEquals("봉인 뒤 도착한 답이 권한을 열었다", PremiumAccessState.NoGrant, h.coordinator.state.value.state)
        assertEquals("버린 답이 재확인을 걸었다", 1, h.fetches())
        assertEquals("봉인 가드보다 저장소 읽기가 먼저였다", loadsBefore, h.store.loads)
    }

    // Identity events while an attempt is open

    @Test
    fun theAttemptsOwnEndRotatesAndFinishesIt() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        assertTrue(h.coordinator.prepareSignOut(a7) is SignOutStart.Armed)
        h.setLive(null)

        assertTrue(h.coordinator.onSignedOut(a7))

        assertEquals(1, h.store.signOuts)
        assertNull(h.store.record.teardownOwedFor)
        // Finished: judged afresh, and nobody is signed in to sign out.
        assertEquals(SignOutStart.Stale, h.coordinator.prepareSignOut(a7))
    }

    @Test
    fun anEndWhileSomebodyIsSignedInKeepsTheSeal() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        val armed = h.coordinator.prepareSignOut(a7) as SignOutStart.Armed
        h.setLive(a8)

        assertTrue(h.coordinator.onSignedOut(a7))

        assertEquals(SignOutStart.Joined(armed.ticket), h.coordinator.prepareSignOut(a7))
    }

    @Test
    fun aSignInDuringCleanupKeepsTheSeal() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        val armed = h.coordinator.prepareSignOut(a7) as SignOutStart.Armed
        h.setLive(null)
        h.purger.beforeAnswer = { h.setLive(a8) }

        assertTrue(h.coordinator.onSignedOut(a7))

        assertEquals(SignOutStart.Joined(armed.ticket), h.coordinator.prepareSignOut(a7))
    }

    /** A session seen when the end was handled keeps the seal, even if it is gone again by the end of cleanup. */
    @Test
    fun aSessionSeenAtTheEndIsNotForgottenByCleanup() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        val armed = h.coordinator.prepareSignOut(a7) as SignOutStart.Armed
        h.setLive(a8)
        h.purger.beforeAnswer = { h.setLive(null) }

        assertTrue(h.coordinator.onSignedOut(a7))

        assertEquals(SignOutStart.Joined(armed.ticket), h.coordinator.prepareSignOut(a7))
    }

    @Test
    fun anEndedBindingIsNoLongerComplete() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        h.setLive(null)
        assertTrue(h.coordinator.onSignedOut(a7))
        // The tracker names a7 again with no binding observed for it since that end.
        h.setLive(a7)

        assertTrue(h.coordinator.prepareSignOut(a7) is SignOutStart.RecoveryRequired)
    }

    @Test
    fun aFailedCleanupKeepsTheSeal() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        val armed = h.coordinator.prepareSignOut(a7) as SignOutStart.Armed
        h.setLive(null)
        h.purger.result = PurgeResult.Failed(IOException("purge"))

        assertTrue(h.coordinator.onSignedOut(a7))

        assertEquals(SignOutStart.Joined(armed.ticket), h.coordinator.prepareSignOut(a7))
    }

    @Test
    fun anEndNeverRotatesAnotherDiskOwner() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        h.store.record = AccessEpochTransitions.bindOwner(h.store.record, b3.uid) { "foreign-epoch" }

        assertTrue(h.coordinator.onSignedOut(a7))

        assertEquals("다른 계정의 namespace 를 회전시켰다", 0, h.store.signOuts)
    }

    @Test
    fun aBindWhileArmedLandsTheIntentAndKeepsTheSeal() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        val armed = h.coordinator.prepareSignOut(a7) as SignOutStart.Armed
        val epochBefore = h.store.record.userAccessEpoch
        h.setLive(a8)

        h.coordinator.onIdentityChanged(a8)
        advanceUntilIdle()

        assertNull("같은 uid 바인딩이 표식을 정산하지 않았다", h.store.record.teardownOwedFor)
        assertTrue(h.store.record.pendingPurges.any { it.userAccessEpoch == epochBefore })
        assertEquals(SignOutStart.Joined(armed.ticket), h.coordinator.prepareSignOut(a7))
        assertEquals(PremiumAccessState.NoGrant, h.coordinator.state.value.state)
    }
}
