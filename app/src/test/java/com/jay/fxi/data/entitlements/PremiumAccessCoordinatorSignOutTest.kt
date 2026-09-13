package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.domain.model.TopicRejectionReason
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        var retires = 0
        var retireFault: EditFault? = null
        /** Runs inside the retirement edit before it reads the record, as a concurrent marker write would. */
        var beforeRetire: () -> Unit = {}
        override suspend fun retireUnverifiedStart(): AccessEpochRecord {
            retires += 1
            return edit(retireFault.also { retireFault = null }) {
                beforeRetire()
                AccessEpochTransitions.retireUnverifiedStart(record, ids)
            }
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
        val requestedNamespaces = mutableListOf<Pair<PurgeScope, PurgeNamespace>>()
        override suspend fun purgeUserScope(namespace: PurgeNamespace) =
            answer(PurgeScope.USER, namespace)
        override suspend fun purgeCapabilityScope(namespace: PurgeNamespace) =
            answer(PurgeScope.CAPABILITY, namespace)
        private fun answer(scope: PurgeScope, namespace: PurgeNamespace): PurgeResult {
            requestedNamespaces += scope to namespace
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
        h.coordinator.onIdentityChanged(b3).heldByAttempt("결과를 모르는 편집 뒤의 신원 사건")
        h.coordinator.onSignedOut(a7).heldByAttempt("로그아웃")
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
        h.coordinator.onIdentityChanged(a8).heldByAttempt()
        h.coordinator.onSignedOut(a7).heldByAttempt("로그아웃")
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
        h.coordinator.onIdentityChanged(a8).applied()
    }

    // The seal

    @Test
    fun anOpenAttemptRejectsRefreshProbeAndTopicBeforeSideEffects() = runTest {
        val h = harness()
        // A real grant and its topic token, taken before the seal: a refusal for a grant that was
        // never issued would be refused for that reason, and would say nothing about the seal.
        premiumFor(h, a7)
        val grant = checkNotNull(h.coordinator.topicGrant()).grant
        assertTrue(h.coordinator.prepareSignOut(a7) is SignOutStart.Armed)
        val fetchesBefore = h.fetches()
        val loadsBefore = h.store.loads
        val recordBefore = h.store.record

        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        h.coordinator.onLocalPremiumSignal()
        h.coordinator.onTopicRejected(grant, listOf(TopicRejectionReason.PREMIUM_REQUIRED))
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

        h.coordinator.onSignedOut(a7).applied("로그아웃")

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

        h.coordinator.onSignedOut(a7).applied("로그아웃")

        assertEquals(SignOutStart.Joined(armed.ticket), h.coordinator.prepareSignOut(a7))
    }

    @Test
    fun aSignInDuringCleanupKeepsTheSeal() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        val armed = h.coordinator.prepareSignOut(a7) as SignOutStart.Armed
        h.setLive(null)
        h.purger.beforeAnswer = { h.setLive(a8) }

        h.coordinator.onSignedOut(a7).applied("로그아웃")

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

        h.coordinator.onSignedOut(a7).applied("로그아웃")

        assertEquals(SignOutStart.Joined(armed.ticket), h.coordinator.prepareSignOut(a7))
    }

    @Test
    fun anEndedBindingIsNoLongerComplete() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        h.setLive(null)
        h.coordinator.onSignedOut(a7).applied("로그아웃")
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

        h.coordinator.onSignedOut(a7).applied("로그아웃")

        assertEquals(SignOutStart.Joined(armed.ticket), h.coordinator.prepareSignOut(a7))
    }

    @Test
    fun anEndNeverRotatesAnotherDiskOwner() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7)
        h.store.record = AccessEpochTransitions.bindOwner(h.store.record, b3.uid) { "foreign-epoch" }

        h.coordinator.onSignedOut(a7).applied("로그아웃")

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

        h.coordinator.onIdentityChanged(b3).heldByAttempt()
        h.coordinator.onSignedOut(a7).heldByAttempt("읽어 보기 전의 뒤 사건")
        assertEquals(0, h.store.signOuts)

        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))
        h.coordinator.onIdentityChanged(b3).applied()
        assertEquals(b3.uid, h.store.record.ownerUid)
    }

    @Test
    fun aBindThatLandedBeforeFailingRotatesNothingMoreWhenRetried() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(a8)
        h.store.bindFault = EditFault.AFTER_WRITE

        h.coordinator.onIdentityChanged(a8).heldByAttempt()
        val journal = h.store.record.pendingPurges
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))
        h.coordinator.onIdentityChanged(a8).applied()

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

        h.coordinator.onIdentityChanged(a8).heldByAttempt()
        h.coordinator.resumePendingPurges()

        assertEquals("재읽기 전에 정리가 돌았다", 0, h.purger.purges)
        assertTrue(h.store.record.pendingPurges.any { it.userAccessEpoch == epochBefore })
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))
        h.coordinator.onIdentityChanged(a8).applied()
        assertFalse(h.store.record.pendingPurges.any { it.userAccessEpoch == epochBefore })
    }

    @Test
    fun aLandedEndIsNotRotatedAgainWhenItsEventIsRetried() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(null)
        h.store.signOutFault = EditFault.AFTER_WRITE

        h.coordinator.onSignedOut(a7).heldByAttempt("로그아웃")
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))
        h.coordinator.onSignedOut(a7).applied("로그아웃")

        assertEquals("착지한 종료를 다시 회전했다", 1, h.store.signOuts)
        // Finished: judged afresh, and nobody is signed in to sign out.
        assertEquals(SignOutStart.Stale, h.coordinator.prepareSignOut(a7))
        // The retry consumed what it was holding: the next event is an ordinary one.
        h.setLive(b3)
        h.coordinator.onIdentityChanged(b3).applied()
    }

    @Test
    fun anEndThatNeverLandedRotatesOnceWhenRetried() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(null)
        h.store.signOutFault = EditFault.BEFORE_WRITE
        val journal = h.store.record.pendingPurges.size

        h.coordinator.onSignedOut(a7).heldByAttempt("로그아웃")
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))
        h.coordinator.onSignedOut(a7).applied("로그아웃")

        assertEquals(journal + 1, h.store.record.pendingPurges.size)
        assertEquals(SignOutStart.Stale, h.coordinator.prepareSignOut(a7))
    }

    @Test
    fun markersChangingAloneStillReadAsAnEndThatNeverLanded() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(null)
        h.store.signOutFault = EditFault.BEFORE_WRITE
        h.coordinator.onSignedOut(a7).heldByAttempt("로그아웃")
        h.store.record = AccessEpochTransitions.markMayContainData(h.store.record, premium = true, krx = true)

        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))
    }

    @Test
    fun anEndReadBackThatIsNeitherResultIsNotGuessed() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(null)
        h.store.signOutFault = EditFault.BEFORE_WRITE
        h.coordinator.onSignedOut(a7).heldByAttempt("로그아웃")
        h.store.record = h.store.record.copy(userAccessEpoch = "not-from-any-rotation")

        assertEquals(EditResolution.INCONSISTENT, h.coordinator.resolvePendingEdit(ticket))
        h.coordinator.onSignedOut(a7).heldByAttempt("판정하지 못한 종료")
        assertEquals(1, h.store.signOuts)
    }

    @Test
    fun anUnreadableReadBackKeepsTheEventHeld() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(null)
        h.store.readBackFails = true
        h.store.signOutFault = EditFault.BEFORE_WRITE

        h.coordinator.onSignedOut(a7).heldByAttempt("로그아웃")
        assertEquals(EditResolution.STILL_UNKNOWN, h.coordinator.resolvePendingEdit(ticket))
        h.coordinator.onSignedOut(a7).heldByAttempt("로그아웃")

        h.store.loadsFail = false
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))
        h.coordinator.onSignedOut(a7).applied("로그아웃")
    }

    @Test
    fun onlyTheWaitingAttemptsEditIsReadBack() = runTest {
        val h = harness()
        val ticket = armed(h)
        assertEquals(EditResolution.NOT_PENDING, h.coordinator.resolvePendingEdit(ticket))
        h.setLive(null)
        h.store.signOutFault = EditFault.BEFORE_WRITE
        h.coordinator.onSignedOut(a7).heldByAttempt("로그아웃")

        assertEquals(EditResolution.NOT_PENDING, h.coordinator.resolvePendingEdit(SignOutTicket(ticket.value + 1)))
        h.coordinator.onSignedOut(a7).heldByAttempt("다른 시도의 재읽기 뒤 종료")
    }

    @Test
    fun aFailedReadBeforeTheEditHoldsTheEventWithoutEditing() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(b3)
        val binds = h.store.binds
        h.store.loadsFail = true

        h.coordinator.onIdentityChanged(b3).heldByAttempt()
        assertEquals(binds, h.store.binds)

        h.store.loadsFail = false
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))
        h.coordinator.onIdentityChanged(b3).applied()
    }

    @Test
    fun aStoreCancellationHoldsTheEventInsteadOfEndingTheCaller() = runTest {
        val h = harness()
        armed(h)
        h.setLive(a8)
        h.store.bindFault = EditFault.CANCEL_AFTER_WRITE

        h.coordinator.onIdentityChanged(a8).heldByAttempt()
    }

    /**
     * The failure this used to let through took the process: the consumer that calls this has no
     * handler for it. It is now held instead, and the hold — not an exception — is what says the
     * record on disk may not be what was published.
     */
    @Test
    fun withNoAttemptOpenAFailedEditIsHeldRatherThanThrown() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7).applied()
        h.store.bindFault = EditFault.BEFORE_WRITE

        val step = runCatching { h.coordinator.onIdentityChanged(b3) }
        assertTrue("시도가 없을 때의 실패가 그대로 던져졌다: $step", step.isSuccess)
        val held = step.getOrThrow().heldByPersistence("시도 없는 편집 실패")
        assertEquals("첫 실패는 회차를 쓰지 않고 자동 재시도를 예약한다", 0L, held.afterRevision)
        assertNull("멈춘 이유가 있으면 자동 재시도가 없다는 뜻이다", held.blocked)
        assertNotNull("자동 재시도가 예약되지 않았다", held.nextAttemptAt)
    }

    /**
     * Cleanup is the step that can fail *after* the write is on disk, so its hold must not be one
     * that would run the write again — the phase carries that difference, and this pins that the
     * failure is held at all.
     */
    @Test
    fun withNoAttemptOpenAFailedCleanupIsHeldRatherThanThrown() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7).applied()
        h.purger.throwing = IOException("purge")

        // Another uid's bind journals a7's namespace, so its cleanup reaches the purger.
        val step = runCatching { h.coordinator.onIdentityChanged(b3) }
        assertTrue("시도가 없을 때의 정리 실패가 그대로 던져졌다: $step", step.isSuccess)
        step.getOrThrow().heldByPersistence("시도 없는 정리 실패")
    }

    @Test
    fun aThrownCleanupAfterALandedEndKeepsTheSealWithoutRotatingAgain() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(null)
        h.purger.throwing = IOException("purge")

        h.coordinator.onSignedOut(a7).applied("로그아웃")

        assertEquals(1, h.store.signOuts)
        assertEquals(SignOutStart.Joined(ticket), h.coordinator.prepareSignOut(a7))
    }

    @Test
    fun theHeldEventIsRetriedBeforeAnyOther() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(b3)
        h.store.bindFault = EditFault.BEFORE_WRITE
        h.coordinator.onIdentityChanged(b3).heldByAttempt()
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
        h.coordinator.onSignedOut(a7).heldByAttempt("로그아웃")
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
    fun onlyTheArmedDriversOwnTicketStopsIt() = runTest {
        val h = harness()
        val ticket = armed(h)

        assertFalse(h.coordinator.stopDriver(SignOutTicket(ticket.value + 1)))
        assertEquals(RecoveryAdvance.DRIVER_OWNS, h.coordinator.advanceRecovery(ticket))
        assertTrue(h.coordinator.stopDriver(ticket))
        assertFalse("멈춘 실행자를 다시 멈췄다", h.coordinator.stopDriver(ticket))
        assertEquals(RecoveryAdvance.PROGRESSED, h.coordinator.advanceRecovery(ticket))
        assertEquals(1, h.store.signOuts)
    }

    @Test
    fun theRecoveryStatusIsTheOpenAttemptsAndGoesWithIt() = runTest {
        val h = harness()
        val ticket = unboundRecovery(h)
        h.coordinator.publishRecovery(ticket) { it.copy(running = true) }
        assertEquals(SignOutRecoveryStatus(ticket, running = true), h.coordinator.recoveryStatus.value)

        // A change within the same attempt keeps it.
        h.store.loadsFail = true
        assertEquals(RecoveryAdvance.UNRESOLVED, h.coordinator.advanceRecovery(ticket))
        assertEquals(SignOutRecoveryStatus(ticket, running = true), h.coordinator.recoveryStatus.value)
        h.store.loadsFail = false
        assertEquals(RecoveryAdvance.RESOLVED, h.coordinator.advanceRecovery(ticket))

        // The attempt ends, and a late write for it is dropped.
        assertEquals(BarrierStep.RELEASED, h.coordinator.completeRecovery(ticket, a7).step)
        assertNull(h.coordinator.recoveryStatus.value)
        h.coordinator.publishRecovery(ticket) { it.copy(running = false, outcome = RecoveryOutcome.CLOSED) }
        assertNull("끝난 시도의 늦은 결과가 기록됐다", h.coordinator.recoveryStatus.value)
    }

    @Test
    fun anAutomaticRunIsClaimedOnceAndOnlyWhileRecoveryOwnsTheAttempt() = runTest {
        val h = harness()
        val ticket = armed(h)

        assertFalse("실행자가 있는 시도를 복구가 가져갔다", h.coordinator.claimRecovery(ticket))
        assertTrue(h.coordinator.stopDriver(ticket))
        assertFalse(h.coordinator.claimRecovery(SignOutTicket(ticket.value + 1)))
        assertTrue(h.coordinator.claimRecovery(ticket))
        assertFalse("같은 시도를 두 번 가져갔다", h.coordinator.claimRecovery(ticket))
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
        h.coordinator.onIdentityChanged(a8).applied()

        assertEquals(RecoveryAdvance.NEEDS_BARRIER, h.coordinator.advanceRecovery(ticket))
        assertEquals(0, h.store.signOuts)
    }

    @Test
    fun anEndLeftSealedByItsCleanupLeavesNothingToSettle() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(null)
        h.purger.result = PurgeResult.Failed(IOException("purge"))
        h.coordinator.onSignedOut(a7).applied("로그아웃")

        assertEquals(RecoveryAdvance.NEEDS_BARRIER, h.coordinator.advanceRecovery(ticket))
        assertEquals(1, h.store.signOuts)
    }

    @Test
    fun aResolvedEndStillInTheFifoIsNotJudgedAheadOfIt() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(null)
        h.store.signOutFault = EditFault.AFTER_WRITE
        h.coordinator.onSignedOut(a7).heldByAttempt("로그아웃")
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))

        assertEquals("보류 사건보다 복구가 먼저 디스크를 판정했다", RecoveryAdvance.NEEDS_BARRIER, h.coordinator.advanceRecovery(ticket))
        h.coordinator.onSignedOut(a7).applied("로그아웃")
        assertEquals(1, h.store.signOuts)
    }

    @Test
    fun anUnlandedEndStillInTheFifoIsLeftToItsRetry() = runTest {
        val h = harness()
        val ticket = armed(h)
        h.setLive(null)
        val journal = h.store.record.pendingPurges.size
        h.store.signOutFault = EditFault.BEFORE_WRITE
        h.coordinator.onSignedOut(a7).heldByAttempt("로그아웃")
        assertEquals(EditResolution.RESOLVED, h.coordinator.resolvePendingEdit(ticket))

        assertEquals(RecoveryAdvance.NEEDS_BARRIER, h.coordinator.advanceRecovery(ticket))
        assertEquals(1, h.store.signOuts)
        h.coordinator.onSignedOut(a7).applied("로그아웃")
        assertEquals("종료 회전이 한 번보다 많이 착지했다", journal + 1, h.store.record.pendingPurges.size)
    }

    @Test
    fun aRecoveryReadFailureHoldsIdentityEventsUntilReadBack() = runTest {
        val h = harness()
        val ticket = owedRecovery(h)
        h.store.loadsFail = true

        assertEquals(RecoveryAdvance.UNRESOLVED, h.coordinator.advanceRecovery(ticket))
        h.coordinator.onIdentityChanged(b3).heldByAttempt()

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
        h.coordinator.onSignedOut(a7).applied("로그아웃")
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
        h.coordinator.onSignedOut(a7).applied("로그아웃")
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
        h.coordinator.onIdentityChanged(b3).applied()
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
        h.coordinator.onSignedOut(a7).applied("로그아웃")
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
        h.coordinator.onIdentityChanged(b3).heldByAttempt()
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

    // A failed edit with no attempt to own it

    /**
     * Advances to the hold's own deadline and spends the round that falls due there.
     *
     * Read from the hold rather than restated: a test that hard-coded the delay would keep passing
     * if the schedule silently moved, which is one of the things the hold is meant to make visible.
     */
    private suspend fun TestScope.resumeWhenDue(h: Harness, held: IdentityStep.AwaitPersistence): IdentityStep {
        val due = checkNotNull(held.nextAttemptAt) { "자동 재시도가 예약되지 않았다: $held" }
        advanceTimeBy(due - testScheduler.currentTime)
        return h.coordinator.resumePersistence(held.id)
    }

    /** Opens a hold by failing a bind that no sign-out owns. */
    private suspend fun TestScope.holdABind(
        h: Harness,
        fault: EditFault = EditFault.BEFORE_WRITE
    ): IdentityStep.AwaitPersistence {
        h.coordinator.onIdentityChanged(a7).applied()
        h.setLive(b3)
        h.store.bindFault = fault
        return h.coordinator.onIdentityChanged(b3).heldByPersistence()
    }

    /**
     * While a hold stands the record on disk may not be what [PremiumAccessCoordinator.state]
     * already published, so nothing may query against it — the same rule an open sign-out follows,
     * for the same reason.
     */
    @Test
    fun aHeldEditRefusesAccessQueriesUntilItIsResolved() = runTest {
        val h = harness()
        val held = holdABind(h)
        val fetches = h.fetches()

        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        assertEquals("보류 중에 질의가 나갔다", fetches, h.fetches())

        resumeWhenDue(h, held).applied()
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        assertEquals("보류가 풀렸는데도 질의가 막혔다", fetches + 1, h.fetches())
    }

    /** The same rule for the topic side: no grant is issued while a hold stands, and none is acted on. */
    @Test
    fun aHeldEditIssuesNoTopicGrantAndAppliesNoRefusal() = runTest {
        val h = harness()
        premiumFor(h, a7)
        val grant = checkNotNull(h.coordinator.topicGrant()).grant
        holdABind(h)
        val recordBefore = h.store.record
        val stateBefore = h.coordinator.state.value

        assertNull("보류 중에 topic grant 가 나왔다", h.coordinator.topicGrant())
        h.coordinator.onTopicRejected(grant, listOf(TopicRejectionReason.PREMIUM_REQUIRED))
        advanceUntilIdle()

        assertEquals(recordBefore, h.store.record)
        assertEquals(stateBefore, h.coordinator.state.value)
    }

    @Test
    fun aResumeBeforeTheScheduledTimeRunsNothing() = runTest {
        val h = harness()
        val held = holdABind(h)
        val loads = h.store.loads
        val binds = h.store.binds

        val again = h.coordinator.resumePersistence(held.id).heldByPersistence()

        assertEquals("이른 재개가 읽기를 썼다", loads, h.store.loads)
        assertEquals("이른 재개가 쓰기를 썼다", binds, h.store.binds)
        assertEquals("이른 재개가 예약을 소비했다", held.nextAttemptAt, again.nextAttemptAt)
    }

    /**
     * The whole point of the hold: a fault that was transient costs the user nothing once it
     * clears. The bind that failed is the bind that lands.
     */
    @Test
    fun aResumeAtTheScheduledTimeReExecutesAndCompletes() = runTest {
        val h = harness()
        val held = holdABind(h)
        assertEquals("실패한 묶기가 기록을 바꿔서는 안 된다", "user-a", h.store.record.ownerUid)

        val completion = resumeWhenDue(h, held).applied("재개된 묶기")

        assertEquals("user-b", h.store.record.ownerUid)
        assertEquals(b3, completion.completed)
        assertNotNull("묶기는 뒤따르는 질의를 위한 세대를 넘긴다", completion.queryGeneration)
    }

    /**
     * A write that reached the disk before the failure must not run again.
     *
     * Slice 5 is what makes this particular repeat merely wasteful rather than destructive: the
     * bind that just landed cleared any owed teardown, so running it again returns `ensureNamespace`
     * for the owner the record already names. That is a property of this state, not of `bindOwner`
     * in general — an owed teardown would settle, and settling rotates. So what this pins is that
     * the read-back's answer is *used*: a machine that re-ran regardless would leave the
     * landed/not-landed split as dead weight for the first edit that is not idempotent.
     */
    @Test
    fun aBindThatLandedIsNotBoundASecondTime() = runTest {
        val h = harness()
        val held = holdABind(h, EditFault.AFTER_WRITE)
        val binds = h.store.binds
        assertEquals("기록에 닿은 묶기여야 이 시험이 성립한다", "user-b", h.store.record.ownerUid)

        resumeWhenDue(h, held).applied("착지한 묶기의 재개")

        assertEquals("착지한 묶기를 다시 실행했다", binds, h.store.binds)
    }

    /** The same for an end, which also has to keep the receipt of what it did. */
    @Test
    fun anEndThatLandedIsNotRotatedASecondTime() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7).applied()
        h.setLive(null)
        h.store.signOutFault = EditFault.AFTER_WRITE

        val held = h.coordinator.onSignedOut(a7).heldByPersistence("착지한 종료")
        val signOuts = h.store.signOuts
        val epoch = h.store.record.userAccessEpoch

        resumeWhenDue(h, held).applied("착지한 종료의 재개")

        assertEquals("착지한 회전을 다시 실행했다", signOuts, h.store.signOuts)
        assertEquals("두 번째 회전이 이름공간을 갈아치웠다", epoch, h.store.record.userAccessEpoch)
    }

    /**
     * A record that is neither the edit's result nor what it started from says nothing about what
     * happened. Guessing either way is worse than stopping, so the batch stops and records why.
     */
    @Test
    fun anUnrecognisableRecordStopsTheBatchAsUndecidable() = runTest {
        val h = harness()
        val held = holdABind(h)
        // Somebody else's rotation, landing between the failure and its read-back.
        h.store.record = AccessEpochTransitions.rotate(
            h.store.record, rotateUser = true, rotateKrx = true, EpochIdGenerator { "outside" }
        )
        val binds = h.store.binds

        val stopped = resumeWhenDue(h, held).heldByPersistence("판정할 수 없는 보류")

        assertEquals(NoAutoRetry.UNDECIDABLE, stopped.blocked)
        assertNull("판정하지 못했는데도 다음 회차가 예약됐다", stopped.nextAttemptAt)
        assertEquals("판정하지 못한 채로 다시 썼다", binds, h.store.binds)
    }

    /**
     * The write is already on disk when cleanup fails, so the round that follows runs the cleanup
     * alone. A hold that parked at the retry phase instead would spend the round binding again and
     * re-derive a completion that belongs to the moment the landing was established.
     */
    @Test
    fun aHeldCleanupResumesTheCleanupAndNotTheWrite() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7).applied()
        h.setLive(b3)
        h.purger.throwing = IOException("purge")

        // Another uid's bind journals a7's namespace, so its cleanup reaches the purger.
        val held = h.coordinator.onIdentityChanged(b3).heldByPersistence("실패한 정리")
        val binds = h.store.binds
        assertEquals("묶기가 기록에 닿아야 이 시험이 성립한다", "user-b", h.store.record.ownerUid)

        h.purger.throwing = null
        resumeWhenDue(h, held).applied("정리만 남은 회차")

        assertEquals("정리 재개가 쓰기를 다시 실행했다", binds, h.store.binds)
        assertTrue("정리가 실제로 돌지 않았다", h.purger.purges > 0)
    }

    /** The budget is finite, and running out is a different fact from being undecidable. */
    @Test
    fun theAutomaticBudgetRunsOutAndSaysSo() = runTest {
        val h = harness()
        var held = holdABind(h)
        h.store.loadsFail = true

        repeat(PersistenceRecoveryPolicy.AUTOMATIC_ROUNDS) { round ->
            held = resumeWhenDue(h, held).heldByPersistence("회차 ${round + 1}")
        }

        assertEquals(NoAutoRetry.BUDGET_EXHAUSTED, held.blocked)
        assertNull("예산이 끝났는데도 다음 회차가 예약됐다", held.nextAttemptAt)
    }

    /**
     * The other way a round can fail: the work ran again and failed again.
     *
     * That path extends the hold rather than recording a failed read-back, and the budget has to
     * survive the trip — a hold that took its rounds back on every re-execution would retry for
     * ever and never reach a state anybody could be told about.
     */
    @Test
    fun aHoldWhoseReExecutionsKeepFailing_stillRunsOutOfBudget() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7).applied()
        h.setLive(b3)
        h.store.loadsFail = true
        // A failed read is judged without a read-back, so each round spends itself on the retry.
        var held = h.coordinator.onIdentityChanged(b3).heldByPersistence("실패한 읽기")

        repeat(PersistenceRecoveryPolicy.AUTOMATIC_ROUNDS) { round ->
            held = resumeWhenDue(h, held).heldByPersistence("재실행 회차 ${round + 1}")
        }

        assertEquals("재실행이 이어져도 예산은 끝나야 한다", NoAutoRetry.BUDGET_EXHAUSTED, held.blocked)
        assertNull("예산이 끝났는데도 다음 회차가 예약됐다", held.nextAttemptAt)
    }

    /** A stopped hold is not a dead one: somebody asking again is what starts the next batch. */
    @Test
    fun aManualWakeGivesAStoppedHoldOneMoreRound() = runTest {
        val h = harness()
        var held = holdABind(h)
        h.store.loadsFail = true
        repeat(PersistenceRecoveryPolicy.AUTOMATIC_ROUNDS) { held = resumeWhenDue(h, held).heldByPersistence() }
        assertEquals(NoAutoRetry.BUDGET_EXHAUSTED, held.blocked)

        h.store.loadsFail = false
        assertTrue("멈춘 보류가 깨우기를 받지 않았다", h.coordinator.retryPersistence(held.id))
        assertFalse(
            "두 번째 깨우기가 합류하지 않고 따로 섰다",
            h.coordinator.retryPersistence(held.id)
        )

        h.coordinator.resumePersistence(held.id).applied("깨운 뒤의 재개")
        assertEquals("user-b", h.store.record.ownerUid)
    }

    /**
     * A wake that arrives mid-batch is kept, not spent. Setting it raises a revision, but the
     * rounds that follow raise later ones — so the step a waiter ends up holding is already past
     * it. Watching only for a newer revision would sleep on a hold that is already admissible, and
     * asking again would only coalesce into the wake that is already set.
     */
    @Test
    fun aWakeKeptThroughTheLastRound_wakesTheWaiterWhenTheBatchStops() = runTest {
        val h = harness()
        var held = holdABind(h)
        h.store.loadsFail = true

        // Asked for during the first round's wait, so it is recorded while a deadline still stands.
        assertTrue(h.coordinator.retryPersistence(held.id))
        repeat(PersistenceRecoveryPolicy.AUTOMATIC_ROUNDS) { held = resumeWhenDue(h, held).heldByPersistence() }
        assertEquals(NoAutoRetry.BUDGET_EXHAUSTED, held.blocked)
        assertFalse("같은 요청을 다시 보내도 바뀌는 것이 없다", h.coordinator.retryPersistence(held.id))

        h.store.loadsFail = false
        val waited = async { h.coordinator.awaitPersistenceRetry(held) }
        advanceUntilIdle()

        assertTrue("멈춘 보류에 선 깨우기를 대기가 보지 못했다", waited.isCompleted)
        h.coordinator.resumePersistence(held.id).applied("선 깨우기가 얻은 회차")
        assertEquals("user-b", h.store.record.ownerUid)
    }

    @Test
    fun aResumeNamingAHoldThatIsGoneCompletesNothing() = runTest {
        val h = harness()
        val held = holdABind(h)
        resumeWhenDue(h, held).applied()

        val stale = h.coordinator.resumePersistence(held.id)

        assertTrue("사라진 보류를 재개가 완료로 보고했다: $stale", stale is IdentityStep.StaleResume)
        assertFalse("사라진 보류가 깨우기를 받았다", h.coordinator.retryPersistence(held.id))
    }

    /**
     * A purge resume before the read-back would erase what an unowned END's read-back is going to
     * look for, so the guard covers every Unknown phase. This bind case verifies the guard; a bind
     * landing is judged by its postcondition and does not itself need the journal.
     */
    @Test
    fun aPurgeResumeWaitsForTheReadBack() = runTest {
        val h = harness()
        val held = holdABind(h)
        val loads = h.store.loads

        h.coordinator.resumePendingPurges()
        assertEquals("결과를 모르는 편집 앞에서 정리가 기록을 건드렸다", loads, h.store.loads)

        resumeWhenDue(h, held).applied()
        h.coordinator.resumePendingPurges()
        assertTrue("보류가 풀렸는데도 정리가 막혔다", h.store.loads > loads)
    }

    /**
     * A sign-out attempt must never open on top of a hold, and the identity FIFO is what keeps that
     * true: the app's request is queued behind the head task, which has not returned while its hold
     * stands. This test bypasses that queue, and what it pins is that the coordinator refuses the
     * overlap before recording any sign-out intent. It does not establish what would go wrong
     * without the guard.
     */
    @Test
    fun aSignOutPreparedOnTopOfAHoldIsRefusedBeforeAnyIntentIsWritten() = runTest {
        val h = harness()
        holdABind(h)
        h.setLive(a7)

        val refused = runCatching { h.coordinator.prepareSignOut(a7) }.exceptionOrNull()

        assertTrue("보류 위의 로그아웃 준비를 받아들였다: $refused", refused is IllegalStateException)
        assertNull("거절된 준비가 의도를 기록했다", h.store.record.teardownOwedFor)
    }

    /**
     * A read that failed started no write, so there is nothing for a read-back to find. Spending
     * the round on one would waste the whole batch at the very moment reads are failing — the retry
     * would never run.
     */
    @Test
    fun aHeldReadRetriesTheWorkInsteadOfReadingItBack() = runTest {
        val h = harness()
        h.coordinator.onIdentityChanged(a7).applied()
        h.setLive(b3)
        h.store.loadsFail = true
        val held = h.coordinator.onIdentityChanged(b3).heldByPersistence("실패한 읽기")

        // Still failing when the round falls due, so a read-back would take it. The retry's own read
        // is what must be spent instead — and here it fails, leaving a second round to come.
        val stillHeld = resumeWhenDue(h, held).heldByPersistence("읽기가 여전히 실패하는 회차")
        assertNotNull("읽기 실패는 판정 불가가 아니라 재시도 대상이다", stillHeld.nextAttemptAt)
        assertNull(stillHeld.blocked)

        h.store.loadsFail = false
        val loads = h.store.loads
        resumeWhenDue(h, stillHeld).applied("읽기가 돌아온 회차")
        assertEquals("user-b", h.store.record.ownerUid)
        // Two reads, and both are doing something: the work's own read, and the purge resume's.
        // A round that also read the record back to judge a failed READ would spend a third, and
        // would need the store healthy three times over to make the same progress.
        assertEquals("회차가 판정용 읽기를 하나 더 썼다", loads + 2, h.store.loads)
    }

    // The startup purge, as a head task

    /** A journal a previous process left behind, so a resume has something to do. */
    private fun journalled(h: Harness) {
        h.store.record = AccessEpochRecord(
            userAccessEpoch = "current-user-epoch",
            krxCapabilityEpoch = "current-krx-epoch",
            pendingPurges = listOf(
                PendingPurge(
                    ownerUid = "previous-owner",
                    userAccessEpoch = "older-user-epoch",
                    krxCapabilityEpoch = null,
                    scopes = setOf(PurgeScope.USER)
                )
            )
        )
    }

    @Test
    fun aStartupPurgeThatResumesCleanly_completesWithNothingBound() = runTest {
        val h = harness(live = null)
        journalled(h)

        val completion = h.coordinator.resumeStartupPurge().applied("시작 purge")

        assertNull("시작 purge 는 아무것도 묶지 않는다", completion.completed)
        assertNull("뒤따르는 질의도 없다", completion.queryGeneration)
        assertEquals(1, h.purger.purges)
    }

    /**
     * The lock is not about the journal still having entries — today's purgers always answer
     * Deferred, so that is the normal state. It is about this resume ending in an exception: the
     * store it could not finish is the one every access query reads, and `refresh` reads it without
     * a handler of its own.
     */
    @Test
    fun aStartupPurgeThatThrows_holdsAndRefusesAccess() = runTest {
        val h = harness(live = null)
        journalled(h)
        h.purger.throwing = IOException("purge")
        val fetches = h.fetches()

        val held = h.coordinator.resumeStartupPurge().heldByPersistence("시작 purge")

        assertNotNull("자동 재시도가 예약되지 않았다", held.nextAttemptAt)
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        advanceUntilIdle()
        assertEquals("시작 보류 중에 질의가 나갔다", fetches, h.fetches())
    }

    /** Its whole work is the cleanup, so a round runs that and never an identity edit. */
    @Test
    fun aHeldStartupPurgeResumesTheCleanupOnly() = runTest {
        val h = harness(live = null)
        journalled(h)
        h.purger.throwing = IOException("purge")
        val held = h.coordinator.resumeStartupPurge().heldByPersistence()
        h.purger.throwing = null
        val binds = h.store.binds
        val signOuts = h.store.signOuts

        resumeWhenDue(h, held).applied("재개된 시작 purge")

        assertEquals("시작 purge 재개가 묶기를 실행했다", binds, h.store.binds)
        assertEquals("시작 purge 재개가 회전을 실행했다", signOuts, h.store.signOuts)
        assertTrue("정리가 실제로 돌지 않았다", h.purger.purges >= 2)
    }

    /**
     * The consumer runs this before it has accepted anything, so no attempt can be open — and the
     * work it does assumes that: its failure records a hold rather than the attempt's unresolved
     * edit. Unreachable through the FIFO is not the same as unobservable, so the contract is pinned
     * by opening an attempt and calling the startup entry point directly.
     */
    @Test
    fun aStartupPurgeEnteringOverAnOpenAttemptIsAContractViolation() = runTest {
        val h = harness()
        armed(h)

        val refused = runCatching { h.coordinator.resumeStartupPurge() }.exceptionOrNull()

        assertTrue("열린 시도 위의 시작 재개를 받아들였다: $refused", refused is IllegalStateException)
    }

    /**
     * A head task never enters over somebody else's hold. It would raise the generation, republish
     * state and edit the record before anything noticed — and on its way out its own completion
     * would clear the hold it never owned.
     */
    @Test
    fun anIdentityEventEnteringOverAHoldIsAContractViolation() = runTest {
        val h = harness()
        holdABind(h)

        val bind = runCatching { h.coordinator.onIdentityChanged(a8) }.exceptionOrNull()
        val end = runCatching { h.coordinator.onSignedOut(a7) }.exceptionOrNull()

        assertTrue("보류 위의 신원 변경을 받아들였다: $bind", bind is IllegalStateException)
        assertTrue("보류 위의 종료를 받아들였다: $end", end is IllegalStateException)
    }

    // What a surface may say (slice 8a)

    /**
     * The banner earns its place only when the machine has stopped trying. A hold opened by the
     * first failed step is still on its automatic batch, and putting that on screen would turn
     * every transient disk fault into an error the user is asked to act on.
     */
    @Test
    fun aHoldIsNotSurfacedUntilItsAutomaticBatchStops() = runTest {
        val h = harness()
        var held = holdABind(h)

        assertEquals(
            "자동 배치가 도는 중에 배너가 떴다",
            IdentityRecoveryState.None,
            h.coordinator.identityRecovery.value
        )

        h.store.loadsFail = true
        repeat(PersistenceRecoveryPolicy.AUTOMATIC_ROUNDS) { held = resumeWhenDue(h, held).heldByPersistence() }

        val shown = h.coordinator.identityRecovery.value as IdentityRecoveryState.HoldUnfinished
        assertEquals(held.id, shown.id)
        assertEquals(HeldWork.SIGN_IN, shown.work)
        assertEquals(HoldProgress.STOPPED, shown.progress)
        assertEquals(NoAutoRetry.BUDGET_EXHAUSTED, shown.reason)
    }

    /**
     * And once it has earned it, a re-check does not take it away. `admitRound` clears `blocked`
     * when it spends a wake, so a surface filtering on that field alone would hide the banner at
     * the point when the admitted round's updated hold is published — while access is still refused.
     */
    @Test
    fun aSurfacedHoldStaysSurfacedThroughARecheck() = runTest {
        val h = harness()
        var held = holdABind(h)
        h.store.loadsFail = true
        repeat(PersistenceRecoveryPolicy.AUTOMATIC_ROUNDS) { held = resumeWhenDue(h, held).heldByPersistence() }
        assertTrue(h.coordinator.identityRecovery.value is IdentityRecoveryState.HoldUnfinished)

        assertTrue(h.coordinator.retryPersistence(held.id))
        val requested = h.coordinator.identityRecovery.value as IdentityRecoveryState.HoldUnfinished
        assertEquals(HoldProgress.REQUESTED, requested.progress)

        // The re-check runs and fails again. The wake bought a whole new batch, so the next round
        // is scheduled rather than stopped — and the banner is still there either way, which is
        // what this pins.
        val again = h.coordinator.resumePersistence(held.id).heldByPersistence("재확인 뒤")
        val still = h.coordinator.identityRecovery.value as IdentityRecoveryState.HoldUnfinished
        assertEquals(again.id, still.id)
        assertEquals(HoldProgress.SCHEDULED, still.progress)
        assertEquals("새 배치가 도는데 멈춘 이유가 남았다", null, still.reason)
    }

    /** Resolving the work is what clears it — not the button, and not a round starting. */
    @Test
    fun aResolvedHoldClearsTheSurface() = runTest {
        val h = harness()
        var held = holdABind(h)
        h.store.loadsFail = true
        repeat(PersistenceRecoveryPolicy.AUTOMATIC_ROUNDS) { held = resumeWhenDue(h, held).heldByPersistence() }
        assertTrue(h.coordinator.identityRecovery.value is IdentityRecoveryState.HoldUnfinished)

        h.store.loadsFail = false
        assertTrue(h.coordinator.retryPersistence(held.id))
        h.coordinator.resumePersistence(held.id).applied("재확인이 성공한 회차")

        assertEquals(IdentityRecoveryState.None, h.coordinator.identityRecovery.value)
    }

    /**
     * An `Armed` attempt is excluded from the `recovering` signal the supervisor watches, so
     * nothing would ever claim a run for it and the recovery status stays null. Reading the
     * attempt itself is what keeps that sealed state from being invisible.
     */
    @Test
    fun anArmedAttemptIsSurfacedThoughNoRecoveryRunExists() = runTest {
        val h = harness()
        premiumFor(h, a7)

        assertTrue(h.coordinator.prepareSignOut(a7) is SignOutStart.Armed)

        assertEquals(
            IdentityRecoveryState.SignOutUnfinished(recovering = false),
            h.coordinator.identityRecovery.value
        )
    }

    /**
     * The surfacing record outlives the hold it names, so what keeps it from mattering is that it
     * has to name the hold standing *now*. A later hold starts its own automatic batch unseen.
     */
    @Test
    fun aLaterHoldDoesNotInheritAnEarlierHoldsBanner() = runTest {
        val h = harness()
        var first = holdABind(h)
        h.store.loadsFail = true
        repeat(PersistenceRecoveryPolicy.AUTOMATIC_ROUNDS) { first = resumeWhenDue(h, first).heldByPersistence() }
        assertTrue("첫 보류가 표시되지 않았다", h.coordinator.identityRecovery.value is IdentityRecoveryState.HoldUnfinished)

        h.store.loadsFail = false
        assertTrue(h.coordinator.retryPersistence(first.id))
        h.coordinator.resumePersistence(first.id).applied("첫 보류 해소")
        assertEquals(IdentityRecoveryState.None, h.coordinator.identityRecovery.value)

        // A second hold, still on its own first automatic batch.
        h.setLive(a8)
        h.store.bindFault = EditFault.BEFORE_WRITE
        val second = h.coordinator.onIdentityChanged(a8).heldByPersistence("두 번째 보류")
        assertNotEquals(first.id, second.id)

        assertEquals(
            "앞선 보류의 표시를 새 보류가 물려받았다",
            IdentityRecoveryState.None,
            h.coordinator.identityRecovery.value
        )
    }

    /** A re-check named against a hold that is no longer the pending one is refused, not misapplied. */
    @Test
    fun aRecheckNamingAnEarlierHoldIsRefused() = runTest {
        val h = harness()
        val first = holdABind(h)
        resumeWhenDue(h, first).applied()

        // A second, different hold — a stale surface could still be holding the first id.
        h.setLive(a8)
        h.store.bindFault = EditFault.BEFORE_WRITE
        val second = h.coordinator.onIdentityChanged(a8).heldByPersistence("두 번째 보류")
        assertNotEquals("두 보류가 같은 id 를 받았다", first.id, second.id)

        assertFalse("옛 id 의 재확인이 새 보류를 건드렸다", h.coordinator.retryPersistence(first.id))
        // The second hold is untouched: its own re-check is still available to be recorded.
        assertTrue("새 보류에 이미 깨우기가 서 있었다", h.coordinator.retryPersistence(second.id))
    }

    /**
     * This round reads back before publishing its updated hold. While that read is suspended the
     * surface must already report RUNNING, rather than keep projecting the pre-admission hold's
     * REQUESTED — which is what the fields alone say.
     */
    @Test
    fun aRoundIsRunningFromItsReadBackOnward() = runTest {
        val h = harness()
        var held = holdABind(h)
        h.store.loadsFail = true
        repeat(PersistenceRecoveryPolicy.AUTOMATIC_ROUNDS) { held = resumeWhenDue(h, held).heldByPersistence() }
        assertTrue(h.coordinator.retryPersistence(held.id))

        h.store.loadsFail = false
        val gate = CompletableDeferred<Unit>()
        h.store.blockNextLoadOn = gate
        val round = async { h.coordinator.resumePersistence(held.id) }
        runCurrent()

        try {
            val shown = h.coordinator.identityRecovery.value as IdentityRecoveryState.HoldUnfinished
            assertEquals("재읽기 중인 회차가 실행 중으로 보이지 않는다", HoldProgress.RUNNING, shown.progress)
            assertEquals("실행 중인데 멈춘 이유가 붙었다", null, shown.reason)
        } finally {
            gate.complete(Unit)
        }
        round.await()
    }

    /** And nothing is left marked as running once a round is over, by failure or by success. */
    @Test
    fun anEndedRoundLeavesNoRunningMark() = runTest {
        val h = harness()
        var held = holdABind(h)
        h.store.loadsFail = true

        // Every failing round: the hold is surfaced from the last one onward, and never running.
        repeat(PersistenceRecoveryPolicy.AUTOMATIC_ROUNDS) {
            held = resumeWhenDue(h, held).heldByPersistence()
            val shown = h.coordinator.identityRecovery.value
            if (shown is IdentityRecoveryState.HoldUnfinished) {
                assertNotEquals("끝난 회차가 실행 중으로 남았다", HoldProgress.RUNNING, shown.progress)
            }
        }
        assertTrue(h.coordinator.identityRecovery.value is IdentityRecoveryState.HoldUnfinished)

        h.store.loadsFail = false
        assertTrue(h.coordinator.retryPersistence(held.id))
        h.coordinator.resumePersistence(held.id).applied("성공으로 끝난 회차")

        assertEquals(IdentityRecoveryState.None, h.coordinator.identityRecovery.value)
    }

    // Unverified start (plan amendment 6): a cold start whose first observation is no uid

    private val ownedByA = AccessEpochRecord(ownerUid = "user-a", userAccessEpoch = "u0", krxCapabilityEpoch = "k0")

    /** The entry a both-axes rotation of [before] appends. */
    private fun receiptOf(before: AccessEpochRecord) =
        PendingPurge(before.ownerUid, before.userAccessEpoch, before.krxCapabilityEpoch, AccessEpochTransitions.ALL_SCOPES)

    @Test
    fun anUnverifiedStartRetiresThePreviousOwnersNamespace_andTheSameUidDoesNotInheritIt() = runTest {
        val h = harness(live = null)
        h.store.record = ownedByA.copy(mayContainPremiumData = true)

        h.coordinator.onUnverifiedStart().applied("첫 관측이 uid 없음")

        val retired = h.store.record
        assertNull("이전 owner 가 남았다", retired.ownerUid)
        assertEquals(listOf(receiptOf(ownedByA)), retired.pendingPurges)
        // Recorded as owed, not deleted: the purger is Deferred and the entry stays for a later start.
        assertTrue("정리가 시도되지 않았다", h.purger.purges > 0)
        assertEquals(AccessEpochTransitions.ALL_SCOPES, retired.pendingPurges.single().scopes)
        val expectedScopes = setOf(PurgeScope.USER, PurgeScope.CAPABILITY)
        assertEquals(2, h.purger.requestedNamespaces.size)
        assertEquals(expectedScopes, h.purger.requestedNamespaces.map { it.first }.toSet())
        h.purger.requestedNamespaces.forEach { (_, namespace) ->
            assertEquals(expectedScopes, namespace.pending.scopes)
        }
        assertEquals(IdentityRecoveryState.None, h.coordinator.identityRecovery.value)

        h.setLive(a7)
        h.coordinator.onIdentityChanged(a7).applied("같은 uid 로그인")
        assertEquals("user-a", h.store.record.ownerUid)
        assertEquals("회전 뒤의 namespace 를 쓰지 않았다", retired.userAccessEpoch, h.store.record.userAccessEpoch)
        assertNotEquals("이전 namespace 를 이어받았다", ownedByA.userAccessEpoch, h.store.record.userAccessEpoch)
    }

    @Test
    fun anUnverifiedStartWithNothingOwedWritesNothingAndRunsNoCleanup() = runTest {
        val h = harness(live = null)

        h.coordinator.onUnverifiedStart().applied()

        assertEquals(AccessEpochRecord(), h.store.record)
        assertEquals(1, h.store.loads)
        assertEquals("정산할 것이 없는데 편집했다", 0, h.store.retires)
        assertEquals(0, h.purger.purges)
    }

    @Test
    fun anUnverifiedStartRetiresAnOwnerlessMarkedNamespaceWithTheOwnerUnknown() = runTest {
        val h = harness(live = null)
        val marked = AccessEpochRecord(userAccessEpoch = "u0", krxCapabilityEpoch = "k0", mayContainKrxData = true)
        h.store.record = marked

        h.coordinator.onUnverifiedStart().applied()

        assertEquals(listOf(receiptOf(marked)), h.store.record.pendingPurges)
        assertNull(h.store.record.pendingPurges.single().ownerUid)
        assertFalse(h.store.record.mayContainKrxData)
    }

    @Test
    fun anUnverifiedStartDropsAStaleIntentWithoutRotating() = runTest {
        val h = harness(live = null)
        val stale = AccessEpochRecord(userAccessEpoch = "u0", krxCapabilityEpoch = "k0", teardownOwedFor = "user-c")
        h.store.record = stale

        h.coordinator.onUnverifiedStart().applied()

        assertEquals(stale.copy(teardownOwedFor = null), h.store.record)
    }

    /** T10. The disk took the rotation and the store reported a failure anyway. */
    @Test
    fun aRetirementThatLandedButReportedAFailureIsRecognisedWithoutRunningAgain() = runTest {
        val h = harness(live = null)
        h.store.record = ownedByA
        h.store.retireFault = EditFault.AFTER_WRITE

        val held = h.coordinator.onUnverifiedStart().heldByPersistence("저장 뒤 실패")
        val completion = resumeWhenDue(h, held).applied("착지를 알아본 재개")

        assertEquals(IdentityCompletion(completed = null, queryGeneration = null), completion)
        assertEquals("착지한 편집을 다시 실행했다", 1, h.store.retires)
        assertEquals("journal 이 정확히 한 항목 늘지 않았다", listOf(receiptOf(ownedByA)), h.store.record.pendingPurges)
        assertTrue(h.coordinator.resumePersistence(held.id) is IdentityStep.StaleResume)
    }

    /** The contrast to T10: a write that never started must still retire on resume. */
    @Test
    fun aRetirementThatFailedBeforeWritingRunsAgainAndRotatesOnce() = runTest {
        val h = harness(live = null)
        h.store.record = ownedByA
        h.store.retireFault = EditFault.BEFORE_WRITE

        val held = h.coordinator.onUnverifiedStart().heldByPersistence("저장 전 실패")
        assertEquals("쓰기 전에 실패했는데 레코드가 바뀌었다", ownedByA, h.store.record)
        resumeWhenDue(h, held).applied()

        assertEquals(2, h.store.retires)
        assertEquals(listOf(receiptOf(ownedByA)), h.store.record.pendingPurges)
        assertNull(h.store.record.ownerUid)
    }

    @Test
    fun aDroppedIntentThatLandedButReportedAFailureIsRecognised() = runTest {
        val h = harness(live = null)
        val stale = AccessEpochRecord(userAccessEpoch = "u0", krxCapabilityEpoch = "k0", teardownOwedFor = "user-c")
        h.store.record = stale
        h.store.retireFault = EditFault.AFTER_WRITE

        val held = h.coordinator.onUnverifiedStart().heldByPersistence()
        resumeWhenDue(h, held).applied()

        assertEquals(1, h.store.retires)
        assertEquals(stale.copy(teardownOwedFor = null), h.store.record)
    }

    /** A marker written after the read turns the planned drop into a rotation inside the edit. */
    @Test
    fun aDropThatAConcurrentMarkerTurnedIntoARotationIsStillThisWorkLanding() = runTest {
        val h = harness(live = null)
        val stale = AccessEpochRecord(userAccessEpoch = "u0", krxCapabilityEpoch = "k0", teardownOwedFor = "user-c")
        h.store.record = stale
        h.store.beforeRetire = {
            h.store.record = AccessEpochTransitions.markMayContainData(h.store.record, premium = true, krx = false)
        }
        h.store.retireFault = EditFault.AFTER_WRITE

        val held = h.coordinator.onUnverifiedStart().heldByPersistence()
        resumeWhenDue(h, held).applied("경합으로 회전한 편집의 재개")

        assertEquals(1, h.store.retires)
        assertEquals(listOf(receiptOf(stale)), h.store.record.pendingPurges)
        assertNull(h.store.record.teardownOwedFor)
    }

    /** A marker in the *new* namespace after the landing is later input, not a reason to run again. */
    @Test
    fun aMarkerSetAfterTheLandingDoesNotMakeTheResumeEditAgain() = runTest {
        val h = harness(live = null)
        h.store.record = ownedByA
        h.store.retireFault = EditFault.AFTER_WRITE

        val held = h.coordinator.onUnverifiedStart().heldByPersistence()
        h.store.record = AccessEpochTransitions.markMayContainData(h.store.record, premium = true, krx = false)
        resumeWhenDue(h, held).applied()

        assertEquals("착지 뒤 선 marker 때문에 다시 편집했다", 1, h.store.retires)
        assertEquals(listOf(receiptOf(ownedByA)), h.store.record.pendingPurges)
    }

    @Test
    fun aFailedReadThatFindsNothingOwedOnResumeEndsTheHold() = runTest {
        val h = harness(live = null)
        h.store.loadsFail = true

        val held = h.coordinator.onUnverifiedStart().heldByPersistence("읽기 실패")
        h.store.loadsFail = false
        resumeWhenDue(h, held).applied("아무것도 빚지지 않은 재개")

        assertEquals(0, h.store.retires)
        assertTrue("보류가 남았다", h.coordinator.resumePersistence(held.id) is IdentityStep.StaleResume)
        assertFalse("보류가 남아 깨우기를 받았다", h.coordinator.retryPersistence(held.id))
        assertEquals(IdentityRecoveryState.None, h.coordinator.identityRecovery.value)
    }

    @Test
    fun aCleanupThatThrewAfterTheLandingResumesTheCleanupOnly() = runTest {
        val h = harness(live = null)
        h.store.record = ownedByA
        h.purger.throwing = IOException("purge")

        val held = h.coordinator.onUnverifiedStart().heldByPersistence("정리 실패")
        assertNull("착지하지 않았다", h.store.record.ownerUid)
        h.purger.throwing = null
        resumeWhenDue(h, held).applied()

        assertEquals("정리만 남았는데 편집을 다시 실행했다", 1, h.store.retires)
    }

    /** While the landing is unknown, the journal is the receipt; an outside purge resume must not erase it. */
    @Test
    fun anOutsidePurgeResumeDoesNotEraseTheReceiptOfAnUnknownRetirement() = runTest {
        val h = harness(live = null)
        h.store.record = ownedByA
        h.store.retireFault = EditFault.AFTER_WRITE
        h.purger.result = PurgeResult.Completed

        val held = h.coordinator.onUnverifiedStart().heldByPersistence()
        val purgesBefore = h.purger.purges
        h.coordinator.resumePendingPurges()

        assertEquals("착지 판정 전에 purge 가 돌았다", purgesBefore, h.purger.purges)
        assertEquals(listOf(receiptOf(ownedByA)), h.store.record.pendingPurges)
        resumeWhenDue(h, held).applied()
        assertEquals(1, h.store.retires)
    }

    @Test
    fun anUnverifiedStartOverAStandingHoldIsAContractViolation() = runTest {
        val h = harness()
        holdABind(h)

        val failure = runCatching { h.coordinator.onUnverifiedStart() }.exceptionOrNull()

        assertTrue("보류 위에서 시작 정산이 허용됐다: $failure", failure is IllegalStateException)
    }
}
