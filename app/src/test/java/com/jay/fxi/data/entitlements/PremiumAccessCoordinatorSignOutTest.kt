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

    private enum class EditFault { BEFORE_WRITE, AFTER_WRITE, CANCEL_AFTER_WRITE }

    private class Store(private val ids: EpochIdGenerator) : AccessEpochStore {
        var record = AccessEpochRecord()
        var loads = 0
        var binds = 0
        var signOuts = 0
        var beginFailure: EditFault? = null
        /** The next bind or sign-out fails this way, once. */
        var bindFault: EditFault? = null
        var signOutFault: EditFault? = null
        /** Once an edit has failed, reading the record fails too. */
        var readBackFails = false
        var loadsFail = false
        var blockBeginSignOutOn: CompletableDeferred<Unit>? = null
        var blockNextLoadOn: CompletableDeferred<Unit>? = null
        var afterLoad: () -> Unit = {}
        /** Runs after a bind reached the record. */
        var afterBind: () -> Unit = {}

        override suspend fun load(): AccessEpochRecord {
            loads += 1
            blockNextLoadOn?.let { gate -> blockNextLoadOn = null; gate.await() }
            if (loadsFail) throw IOException("simulated read failure")
            return record.also { afterLoad() }
        }
        override suspend fun bindOwner(uid: String): AccessEpochRecord {
            binds += 1
            return edit(bindFault.also { bindFault = null }) { AccessEpochTransitions.bindOwner(record, uid, ids) }
                .also { afterBind() }
        }
        override suspend fun signOut(): AccessEpochRecord {
            signOuts += 1
            return edit(signOutFault.also { signOutFault = null }) { AccessEpochTransitions.signOut(record, ids) }
        }
        override suspend fun beginSignOut(uid: String): AccessEpochRecord {
            blockBeginSignOutOn?.let { gate -> blockBeginSignOutOn = null; gate.await() }
            return edit(beginFailure) { AccessEpochTransitions.beginSignOut(record, uid) }
        }
        private fun edit(fault: EditFault?, next: () -> AccessEpochRecord): AccessEpochRecord {
            if (fault == null) return next().also { record = it }
            if (fault != EditFault.BEFORE_WRITE) record = next()
            loadsFail = readBackFails
            when (fault) {
                EditFault.BEFORE_WRITE -> throw IOException("simulated write failure")
                EditFault.AFTER_WRITE -> throw IOException("simulated failure after the write")
                EditFault.CANCEL_AFTER_WRITE ->
                    throw CancellationException("simulated store cancellation after the write")
            }
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
        var purges = 0
        /** Thrown instead of answering, while set. */
        var throwing: Exception? = null
        override suspend fun purgeUserScope(namespace: PurgeNamespace) = answer()
        override suspend fun purgeCapabilityScope(namespace: PurgeNamespace) = answer()
        private fun answer(): PurgeResult {
            purges += 1
            throwing?.let { throw it }
            return result.also { beforeAnswer() }
        }
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
        h.store.beginFailure = EditFault.AFTER_WRITE

        assertTrue(h.coordinator.prepareSignOut(a7) is SignOutStart.Armed)
    }

    @Test
    fun aFailedWriteThatDidNotLandRequiresRecovery() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        h.store.beginFailure = EditFault.BEFORE_WRITE

        assertTrue(h.coordinator.prepareSignOut(a7) is SignOutStart.RecoveryRequired)
        assertNull(h.store.record.teardownOwedFor)
    }

    @Test
    fun anUnreadableOutcomeHoldsIdentityEvents() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        h.store.beginFailure = EditFault.BEFORE_WRITE
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
        h.store.beginFailure = EditFault.CANCEL_AFTER_WRITE

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

    // A failed edit while an attempt is open

    /** Binds [a7], arms an attempt for it, and returns its ticket. */
    private suspend fun armed(h: Harness): SignOutTicket {
        h.coordinator.onIdentityChanged(a7)
        return (h.coordinator.prepareSignOut(a7) as SignOutStart.Armed).ticket
    }

    @Test
    fun aFailedBindHoldsItsEventUntilTheEditIsReadBack() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(b3)
        h.store.bindFault = EditFault.BEFORE_WRITE

        assertNull(h.coordinator.onIdentityChanged(b3))
        assertFalse("읽어 보기 전에 뒤 사건이 적용됐다", h.coordinator.onSignedOut(a7))
        assertEquals(0, h.store.signOuts)

        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))
        assertNotNull(h.coordinator.onIdentityChanged(b3))
        assertEquals(b3.uid, h.store.record.ownerUid)
    }

    @Test
    fun aBindThatLandedBeforeFailingRotatesNothingMoreWhenRetried() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(a8)
        h.store.bindFault = EditFault.AFTER_WRITE

        assertNull(h.coordinator.onIdentityChanged(a8))
        val journal = h.store.record.pendingPurges
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))
        assertNotNull(h.coordinator.onIdentityChanged(a8))

        assertEquals("착지한 bind 를 재시도하며 다시 회전했다", journal, h.store.record.pendingPurges)
    }

    @Test
    fun aFailedEditLeavesItsReceiptForTheReadBack() = runTest {
        val h = harness()
        val ticket = armed(h)
        val epochBefore = h.store.record.userAccessEpoch
        h.purger.result = PurgeResult.Completed
        h.setLive(a8)
        h.store.bindFault = EditFault.AFTER_WRITE

        assertNull(h.coordinator.onIdentityChanged(a8))
        h.coordinator.resumePendingPurges()

        assertEquals("재읽기 전에 정리가 돌았다", 0, h.purger.purges)
        assertTrue(h.store.record.pendingPurges.any { it.userAccessEpoch == epochBefore })
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))
        assertNotNull(h.coordinator.onIdentityChanged(a8))
        assertFalse(h.store.record.pendingPurges.any { it.userAccessEpoch == epochBefore })
    }

    @Test
    fun aLandedEndIsNotRotatedAgainWhenItsEventIsRetried() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(null)
        h.store.signOutFault = EditFault.AFTER_WRITE

        assertFalse(h.coordinator.onSignedOut(a7))
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))
        assertTrue(h.coordinator.onSignedOut(a7))

        assertEquals("착지한 종료를 다시 회전했다", 1, h.store.signOuts)
        // Finished: judged afresh, and nobody is signed in to sign out.
        assertEquals(SignOutStart.Stale, h.coordinator.prepareSignOut(a7))
        // The retry consumed what it was holding: the next event is an ordinary one.
        h.setLive(b3)
        assertNotNull(h.coordinator.onIdentityChanged(b3))
    }

    @Test
    fun anEndThatNeverLandedRotatesOnceWhenRetried() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(null)
        h.store.signOutFault = EditFault.BEFORE_WRITE
        val journal = h.store.record.pendingPurges.size

        assertFalse(h.coordinator.onSignedOut(a7))
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))
        assertTrue(h.coordinator.onSignedOut(a7))

        assertEquals(journal + 1, h.store.record.pendingPurges.size)
        assertEquals(SignOutStart.Stale, h.coordinator.prepareSignOut(a7))
    }

    @Test
    fun markersChangingAloneStillReadAsAnEndThatNeverLanded() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(null)
        h.store.signOutFault = EditFault.BEFORE_WRITE
        assertFalse(h.coordinator.onSignedOut(a7))
        h.store.record = AccessEpochTransitions.markMayContainData(h.store.record, premium = true, krx = true)

        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))
    }

    @Test
    fun anEndReadBackThatIsNeitherResultIsNotGuessed() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(null)
        h.store.signOutFault = EditFault.BEFORE_WRITE
        assertFalse(h.coordinator.onSignedOut(a7))
        h.store.record = h.store.record.copy(userAccessEpoch = "not-from-any-rotation")

        assertEquals(EditResolution.INCONSISTENT, h.coordinator.resolvePendingEdit(ticket))
        assertFalse("판정하지 못한 종료의 보류가 풀렸다", h.coordinator.onSignedOut(a7))
        assertEquals(1, h.store.signOuts)
    }

    @Test
    fun anUnreadableReadBackKeepsTheEventHeld() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(null)
        h.store.readBackFails = true
        h.store.signOutFault = EditFault.BEFORE_WRITE

        assertFalse(h.coordinator.onSignedOut(a7))
        assertEquals(EditResolution.STILL_UNKNOWN, h.coordinator.resolvePendingEdit(ticket))
        assertFalse(h.coordinator.onSignedOut(a7))

        h.store.loadsFail = false
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))
        assertTrue(h.coordinator.onSignedOut(a7))
    }

    @Test
    fun onlyTheWaitingAttemptsEditIsReadBack() = runTest {
        val h = harness()
        val ticket = armed(h)
        assertEquals(EditResolution.NOT_PENDING, h.coordinator.resolvePendingEdit(ticket))
        h.setLive(null)
        h.store.signOutFault = EditFault.BEFORE_WRITE
        assertFalse(h.coordinator.onSignedOut(a7))

        assertEquals(EditResolution.NOT_PENDING, h.coordinator.resolvePendingEdit(SignOutTicket(ticket.value + 1)))
        assertFalse("다른 시도의 재읽기가 보류를 풀었다", h.coordinator.onSignedOut(a7))
    }

    @Test
    fun aFailedReadBeforeTheEditHoldsTheEventWithoutEditing() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(b3)
        val binds = h.store.binds
        h.store.loadsFail = true

        assertNull(h.coordinator.onIdentityChanged(b3))
        assertEquals(binds, h.store.binds)

        h.store.loadsFail = false
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))
        assertNotNull(h.coordinator.onIdentityChanged(b3))
    }

    @Test
    fun aStoreCancellationHoldsTheEventInsteadOfEndingTheCaller() = runTest {
        val h = harness()
        armed(h)
        h.setLive(a8)
        h.store.bindFault = EditFault.CANCEL_AFTER_WRITE

        assertNull(h.coordinator.onIdentityChanged(a8))
    }

    @Test
    fun withNoAttemptOpenAFailedEditStillPropagates() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        h.store.bindFault = EditFault.BEFORE_WRITE

        val failure = runCatching { h.coordinator.onIdentityChanged(b3) }.exceptionOrNull()
        assertTrue("시도가 없을 때의 실패를 삼켰다: $failure", failure is IOException)
    }

    @Test
    fun withNoAttemptOpenAFailedCleanupStillPropagates() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        h.purger.throwing = IOException("purge")

        // Another uid's bind journals a7's namespace, so its cleanup reaches the purger.
        val failure = runCatching { h.coordinator.onIdentityChanged(b3) }.exceptionOrNull()
        assertTrue("시도가 없을 때의 정리 실패를 삼켰다: $failure", failure is IOException)
    }

    @Test
    fun aThrownCleanupAfterALandedEndKeepsTheSealWithoutRotatingAgain() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(null)
        h.purger.throwing = IOException("purge")

        assertTrue(h.coordinator.onSignedOut(a7))

        assertEquals(1, h.store.signOuts)
        assertEquals(SignOutStart.Joined(ticket), h.coordinator.prepareSignOut(a7))
    }

    @Test
    fun theHeldEventIsRetriedBeforeAnyOther() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(b3)
        h.store.bindFault = EditFault.BEFORE_WRITE
        assertNull(h.coordinator.onIdentityChanged(b3))
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))

        val failure = runCatching { h.coordinator.onSignedOut(a7) }.exceptionOrNull()
        assertTrue(
            "보류된 사건보다 다른 사건이 먼저 적용됐다: $failure",
            failure is IllegalStateException && failure !is CancellationException
        )
        assertEquals(0, h.store.signOuts)
    }

    @Test
    fun theHeldEndIsRetriedBeforeABinding() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(null)
        h.store.signOutFault = EditFault.BEFORE_WRITE
        assertFalse(h.coordinator.onSignedOut(a7))
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))
        val binds = h.store.binds

        val failure = runCatching { h.coordinator.onIdentityChanged(b3) }.exceptionOrNull()
        assertTrue(
            "보류된 종료보다 바인딩이 먼저 적용됐다: $failure",
            failure is IllegalStateException && failure !is CancellationException
        )
        assertEquals(binds, h.store.binds)
    }

    // Recovery

    /** Intent persisted, but the live session moved during the write: Recovering(OWED), owed on disk. */
    private suspend fun TestScope.owedRecovery(h: Harness): SignOutTicket {
        h.coordinator.onIdentityChanged(a7)
        val gate = CompletableDeferred<Unit>()
        h.store.blockBeginSignOutOn = gate
        val start = async { h.coordinator.prepareSignOut(a7) }
        runCurrent()
        h.setLive(a8)
        gate.complete(Unit)
        val ticket = (start.await() as SignOutStart.RecoveryRequired).ticket
        assertEquals("user-a", h.store.record.teardownOwedFor)
        return ticket
    }

    /** Nothing bound under the live fence: Recovering(NOT_OWED) with an empty record. */
    private suspend fun unboundRecovery(h: Harness): SignOutTicket =
        (h.coordinator.prepareSignOut(a7) as SignOutStart.RecoveryRequired).ticket

    /** a7 bound and completed, then live moved to a8 before the intent write: Recovering(NOT_OWED). */
    private suspend fun movedRecovery(h: Harness): SignOutTicket {
        h.coordinator.onIdentityChanged(a7)
        h.store.afterLoad = { h.setLive(a8) }
        val ticket = (h.coordinator.prepareSignOut(a7) as SignOutStart.RecoveryRequired).ticket
        h.store.afterLoad = {}
        return ticket
    }

    @Test
    fun recoveryLeavesADriverAloneAndIgnoresAnotherTicket() = runTest {
        val h = harness()
        val ticket = armed(h)

        assertEquals(RecoveryAdvance.DRIVER_OWNS, h.coordinator.advanceRecovery(ticket))
        assertEquals(BarrierStep.DRIVER_OWNS, h.coordinator.completeRecovery(ticket, a7).step)
        val other = SignOutTicket(ticket.value + 1)
        assertEquals(RecoveryAdvance.CLOSED, h.coordinator.advanceRecovery(other))
        assertEquals(BarrierStep.CLOSED, h.coordinator.completeRecovery(other, a7).step)
        assertEquals(0, h.store.signOuts)
    }

    @Test
    fun anOwedIntentIsSettledOnce() = runTest {
        val h = harness()
        val ticket = owedRecovery(h)

        assertEquals(RecoveryAdvance.PROGRESSED, h.coordinator.advanceRecovery(ticket))
        assertEquals(RecoveryAdvance.NEEDS_BARRIER, h.coordinator.advanceRecovery(ticket))

        assertEquals(1, h.store.signOuts)
        assertNull(h.store.record.teardownOwedFor)
    }

    @Test
    fun aSettleThatLandedBeforeFailingIsReadBackNotRepeated() = runTest {
        val h = harness()
        val ticket = owedRecovery(h)
        h.store.signOutFault = EditFault.AFTER_WRITE

        assertEquals(RecoveryAdvance.UNRESOLVED, h.coordinator.advanceRecovery(ticket))
        assertEquals(RecoveryAdvance.RESOLVED, h.coordinator.advanceRecovery(ticket))
        assertEquals(RecoveryAdvance.NEEDS_BARRIER, h.coordinator.advanceRecovery(ticket))

        assertEquals(1, h.store.signOuts)
    }

    @Test
    fun aSettleThatNeverLandedIsTriedAgainOnlyOnALaterCall() = runTest {
        val h = harness()
        val ticket = owedRecovery(h)
        val journal = h.store.record.pendingPurges.size
        h.store.signOutFault = EditFault.BEFORE_WRITE

        assertEquals(RecoveryAdvance.UNRESOLVED, h.coordinator.advanceRecovery(ticket))
        assertEquals(RecoveryAdvance.RESOLVED, h.coordinator.advanceRecovery(ticket))
        assertEquals("같은 호출에서 실패한 정산을 다시 돌렸다", 1, h.store.signOuts)
        assertEquals(RecoveryAdvance.PROGRESSED, h.coordinator.advanceRecovery(ticket))
        assertEquals(RecoveryAdvance.NEEDS_BARRIER, h.coordinator.advanceRecovery(ticket))

        assertEquals(2, h.store.signOuts)
        assertEquals(journal + 1, h.store.record.pendingPurges.size)
    }

    @Test
    fun anUnresolvedEditIsLeftUnreadWhenTheRunHasNoReadBackLeft() = runTest {
        val h = harness()
        val ticket = owedRecovery(h)
        h.store.signOutFault = EditFault.AFTER_WRITE
        assertEquals(RecoveryAdvance.UNRESOLVED, h.coordinator.advanceRecovery(ticket))
        val loads = h.store.loads

        assertEquals(RecoveryAdvance.UNRESOLVED, h.coordinator.advanceRecovery(ticket, allowReadBack = false))
        assertEquals("재읽기를 허용하지 않았는데 읽었다", loads, h.store.loads)
        assertEquals(RecoveryAdvance.RESOLVED, h.coordinator.advanceRecovery(ticket))
    }

    @Test
    fun aReadBackThatIsNotAllowedStillJudgesWhetherAnEditIsWaiting() = runTest {
        val h = harness()
        val ticket = owedRecovery(h)
        assertEquals(EditResolution.NOT_PENDING, h.coordinator.resolvePendingEdit(ticket, allowReadBack = false))
        h.store.signOutFault = EditFault.AFTER_WRITE
        assertEquals(RecoveryAdvance.UNRESOLVED, h.coordinator.advanceRecovery(ticket))
        val loads = h.store.loads

        assertEquals(EditResolution.STILL_UNKNOWN, h.coordinator.resolvePendingEdit(ticket, allowReadBack = false))
        assertEquals("재읽기를 허용하지 않았는데 읽었다", loads, h.store.loads)
    }

    @Test
    fun anOwedAttemptWhoseDiskNoLongerOwesIsNotGuessed() = runTest {
        val h = harness()
        val ticket = owedRecovery(h)
        h.store.record = h.store.record.copy(teardownOwedFor = null)

        assertEquals(RecoveryAdvance.INCONSISTENT, h.coordinator.advanceRecovery(ticket))
        assertEquals(0, h.store.signOuts)
    }

    @Test
    fun aSameUidBindThatSettledTheIntentLeavesNothingToSettle() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(a8)
        assertNotNull(h.coordinator.onIdentityChanged(a8))

        assertEquals(RecoveryAdvance.NEEDS_BARRIER, h.coordinator.advanceRecovery(ticket))
        assertEquals(0, h.store.signOuts)
    }

    @Test
    fun anEndLeftSealedByItsCleanupLeavesNothingToSettle() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(null)
        h.purger.result = PurgeResult.Failed(IOException("purge"))
        assertTrue(h.coordinator.onSignedOut(a7))

        assertEquals(RecoveryAdvance.NEEDS_BARRIER, h.coordinator.advanceRecovery(ticket))
        assertEquals(1, h.store.signOuts)
    }

    @Test
    fun aResolvedEndStillInTheFifoIsNotJudgedAheadOfIt() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(null)
        h.store.signOutFault = EditFault.AFTER_WRITE
        assertFalse(h.coordinator.onSignedOut(a7))
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))

        assertEquals("보류 사건보다 복구가 먼저 디스크를 판정했다", RecoveryAdvance.NEEDS_BARRIER, h.coordinator.advanceRecovery(ticket))
        assertTrue(h.coordinator.onSignedOut(a7))
        assertEquals(1, h.store.signOuts)
    }

    @Test
    fun anUnlandedEndStillInTheFifoIsLeftToItsRetry() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(null)
        val journal = h.store.record.pendingPurges.size
        h.store.signOutFault = EditFault.BEFORE_WRITE
        assertFalse(h.coordinator.onSignedOut(a7))
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))

        assertEquals(RecoveryAdvance.NEEDS_BARRIER, h.coordinator.advanceRecovery(ticket))
        assertEquals(1, h.store.signOuts)
        assertTrue(h.coordinator.onSignedOut(a7))
        assertEquals("종료 회전이 한 번보다 많이 착지했다", journal + 1, h.store.record.pendingPurges.size)
    }

    @Test
    fun aRecoveryReadFailureHoldsIdentityEventsUntilReadBack() = runTest {
        val h = harness()
        val ticket = owedRecovery(h)
        h.store.loadsFail = true

        assertEquals(RecoveryAdvance.UNRESOLVED, h.coordinator.advanceRecovery(ticket))
        assertNull(h.coordinator.onIdentityChanged(b3))

        h.store.loadsFail = false
        assertEquals(RecoveryAdvance.RESOLVED, h.coordinator.advanceRecovery(ticket))
        assertEquals(RecoveryAdvance.PROGRESSED, h.coordinator.advanceRecovery(ticket))
        assertEquals(1, h.store.signOuts)
    }

    // The recovery barrier

    @Test
    fun aBarrierBindsItsCandidateThenReleases() = runTest {
        val h = harness()
        val ticket = unboundRecovery(h)

        val outcome = h.coordinator.completeRecovery(ticket, a7)

        assertEquals(BarrierStep.RELEASED, outcome.step)
        assertEquals(a7, outcome.completed)
        assertNotNull(outcome.releasedGeneration)
        assertEquals("user-a", h.store.record.ownerUid)
        assertTrue("해제 뒤에도 시도가 남았다", h.coordinator.prepareSignOut(a7) is SignOutStart.Armed)
    }

    @Test
    fun aBarrierWhoseCandidateMovedRecapturesWithoutBinding() = runTest {
        val h = harness()
        val ticket = unboundRecovery(h)
        h.setLive(a8)

        val outcome = h.coordinator.completeRecovery(ticket, a7)

        assertEquals(BarrierStep.RECAPTURE, outcome.step)
        assertNull(outcome.completed)
        assertEquals(0, h.store.binds)
    }

    @Test
    fun aBarrierDoesNotAdoptAnIdentityQueuedBehindIt() = runTest {
        val h = harness()
        val ticket = unboundRecovery(h)
        h.setLive(a8)

        assertEquals(BarrierStep.RECAPTURE, h.coordinator.completeRecovery(ticket, null).step)
        assertEquals(0, h.store.binds)
    }

    @Test
    fun aBindThatLandedBeforeTheCandidateMovedIsStillCompleted() = runTest {
        val h = harness()
        val ticket = unboundRecovery(h)
        h.store.afterBind = { h.setLive(b3) }

        val outcome = h.coordinator.completeRecovery(ticket, a7)

        assertEquals(BarrierStep.RECAPTURE, outcome.step)
        assertEquals(a7, outcome.completed)
        assertNull(outcome.releasedGeneration)
        assertEquals(SignOutStart.Joined(ticket), h.coordinator.prepareSignOut(a7))
    }

    @Test
    fun aSignedOutRecoveryFinishesWithoutRotating() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(a8)
        assertTrue(h.coordinator.onSignedOut(a7))
        h.setLive(null)

        val outcome = h.coordinator.completeRecovery(ticket, null)

        assertEquals(BarrierStep.RELEASED, outcome.step)
        assertNull(outcome.completed)
        assertNull(outcome.releasedGeneration)
        assertEquals(1, h.store.signOuts)
        assertEquals(SignOutStart.Stale, h.coordinator.prepareSignOut(a7))
    }

    @Test
    fun aFailedCleanupKeepsTheBarrierFromReleasingAndNeverRotates() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(a8)
        assertTrue(h.coordinator.onSignedOut(a7))
        h.setLive(null)

        h.purger.result = PurgeResult.Failed(IOException("purge"))
        assertEquals(BarrierStep.CLEANUP_FAILED, h.coordinator.completeRecovery(ticket, null).step)
        h.purger.throwing = IOException("purge")
        assertEquals(BarrierStep.CLEANUP_FAILED, h.coordinator.completeRecovery(ticket, null).step)
        h.purger.throwing = null
        h.purger.result = PurgeResult.Deferred("later")
        assertEquals(BarrierStep.RELEASED, h.coordinator.completeRecovery(ticket, null).step)

        assertEquals("정리 재시도가 종료를 다시 회전했다", 1, h.store.signOuts)
    }

    @Test
    fun anUnknownBarrierBindIsCompletedBeforeTheCandidateIsJudgedAgain() = runTest {
        val h = harness()
        val ticket = unboundRecovery(h)
        h.store.bindFault = EditFault.AFTER_WRITE
        assertEquals(BarrierStep.HELD, h.coordinator.completeRecovery(ticket, a7).step)
        h.setLive(null)
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))
        val binds = h.store.binds

        val outcome = h.coordinator.completeRecovery(ticket, a7)

        assertEquals(BarrierStep.RECAPTURE, outcome.step)
        assertEquals(a7, outcome.completed)
        assertEquals(binds, h.store.binds)
    }

    @Test
    fun anUnknownSameUidBarrierBindThatWasANoOpStillCompletesTheCandidate() = runTest {
        val h = harness()
        val ticket = movedRecovery(h)
        h.store.bindFault = EditFault.AFTER_WRITE
        assertEquals(BarrierStep.HELD, h.coordinator.completeRecovery(ticket, a8).step)
        h.setLive(null)
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))

        val outcome = h.coordinator.completeRecovery(ticket, a8)

        assertEquals(BarrierStep.RECAPTURE, outcome.step)
        assertEquals(a8, outcome.completed)
    }

    @Test
    fun aReadFailureBeforeTheBarrierBindDoesNotCompleteTheCandidate() = runTest {
        val h = harness()
        val ticket = movedRecovery(h)
        h.store.loadsFail = true
        assertEquals(BarrierStep.HELD, h.coordinator.completeRecovery(ticket, a8).step)
        h.store.loadsFail = false
        h.setLive(null)
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))

        val outcome = h.coordinator.completeRecovery(ticket, a8)

        assertEquals(BarrierStep.RECAPTURE, outcome.step)
        assertEquals("시작도 하지 않은 bind 를 완료로 기록했다", a7, outcome.completed)
    }

    @Test
    fun aBarrierBindThatNeverLandedBindsAgainWhileTheCandidateHolds() = runTest {
        val h = harness()
        val ticket = unboundRecovery(h)
        h.store.bindFault = EditFault.BEFORE_WRITE
        assertEquals(BarrierStep.HELD, h.coordinator.completeRecovery(ticket, a7).step)
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))

        val outcome = h.coordinator.completeRecovery(ticket, a7)

        assertEquals(BarrierStep.RELEASED, outcome.step)
        assertEquals(a7, outcome.completed)
        // The resumed barrier consumed what it was holding: the next event is an ordinary one.
        h.setLive(b3)
        assertNotNull(h.coordinator.onIdentityChanged(b3))
    }

    @Test
    fun aFailedCleanupAfterTheBarrierBindKeepsTheSeal() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        h.store.afterLoad = { h.setLive(b3) }
        val ticket = (h.coordinator.prepareSignOut(a7) as SignOutStart.RecoveryRequired).ticket
        h.store.afterLoad = {}
        // Binding another uid journals a7's namespace, so the barrier's cleanup reaches the purger.
        h.purger.result = PurgeResult.Failed(IOException("purge"))

        val outcome = h.coordinator.completeRecovery(ticket, b3)

        assertEquals(BarrierStep.CLEANUP_FAILED, outcome.step)
        assertEquals(b3, outcome.completed)
        assertEquals(SignOutStart.Joined(ticket), h.coordinator.prepareSignOut(a7))
    }

    @Test
    fun aSignInDuringTheSignedOutBarriersCleanupKeepsTheSeal() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(a8)
        assertTrue(h.coordinator.onSignedOut(a7))
        h.setLive(null)
        h.purger.beforeAnswer = { h.setLive(b3) }

        assertEquals(BarrierStep.RECAPTURE, h.coordinator.completeRecovery(ticket, null).step)
        assertEquals(SignOutStart.Joined(ticket), h.coordinator.prepareSignOut(a7))
    }

    @Test
    fun anUnclassifiableBarrierBindStaysHeld() = runTest {
        val h = harness()
        val ticket = unboundRecovery(h)
        h.store.bindFault = EditFault.BEFORE_WRITE
        assertEquals(BarrierStep.HELD, h.coordinator.completeRecovery(ticket, a7).step)
        h.store.record = h.store.record.copy(ownerUid = "user-b")

        assertEquals(EditResolution.INCONSISTENT, h.coordinator.resolvePendingEdit(ticket))
        assertEquals(BarrierStep.HELD, h.coordinator.completeRecovery(ticket, a7).step)
    }

    @Test
    fun aBarrierCannotRunAheadOfAHeldIdentityEvent() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(b3)
        h.store.bindFault = EditFault.BEFORE_WRITE
        assertNull(h.coordinator.onIdentityChanged(b3))
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))
        val loads = h.store.loads
        val binds = h.store.binds

        val failure = runCatching { h.coordinator.completeRecovery(ticket, b3) }.exceptionOrNull()

        assertTrue(
            "보류된 사건보다 barrier 가 먼저 실행됐다: $failure",
            failure is IllegalStateException && failure !is CancellationException
        )
        assertEquals(loads, h.store.loads)
        assertEquals(binds, h.store.binds)
    }
}
