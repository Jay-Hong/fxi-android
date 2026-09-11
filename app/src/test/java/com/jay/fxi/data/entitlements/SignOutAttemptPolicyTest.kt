package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.SignOutAttemptPolicy.Barrier
import com.jay.fxi.data.entitlements.SignOutAttemptPolicy.EndPlan
import com.jay.fxi.data.entitlements.SignOutAttemptPolicy.Prologue
import com.jay.fxi.data.entitlements.SignOutAttemptPolicy.RecoveryStep
import com.jay.fxi.data.entitlements.SignOutAttemptPolicy.Request
import com.jay.fxi.data.entitlements.SignOutAttemptPolicy.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agreed transition table, one test per row the policy decides on its own.
 *
 * Records that stand for disk results are produced by the real [AccessEpochTransitions], not
 * written by hand, so a receipt check is measured against what a rotation actually appends.
 */
class SignOutAttemptPolicyTest {

    private var counter = 0
    private val ids = EpochIdGenerator { "epoch-${counter++}" }

    private val t = SignOutTicket(1)
    private val a7 = AuthIdentityFence("user-a", 7)
    private val a8 = AuthIdentityFence("user-a", 8)
    private val b3 = AuthIdentityFence("user-b", 3)

    private val boundA = AccessEpochTransitions.bindOwner(AccessEpochRecord(), "user-a", ids)
    private val armedA = AccessEpochTransitions.beginSignOut(boundA, "user-a")
    private val settledA = AccessEpochTransitions.signOut(armedA, ids)

    private fun preparing() = SignOutAttempt.Preparing(t, a7)
    private fun armed() = SignOutAttempt.Armed(t, a7)
    private fun recovering(knowledge: TeardownKnowledge) = SignOutAttempt.Recovering(t, a7, knowledge)
    private fun unresolved(edit: PendingEdit, known: TeardownKnowledge) =
        SignOutAttempt.Unresolved(t, a7, edit, known, before = armedA)

    // Requests and admission

    @Test
    fun repeatedRequestsNeitherRearmNorAcquireExecutionRights() {
        assertEquals(Request.Start, SignOutAttemptPolicy.request(null, a7))
        assertEquals(Request.Joined(t), SignOutAttemptPolicy.request(armed(), a7))
        assertEquals(Request.Busy(t), SignOutAttemptPolicy.request(armed(), a8))
        assertEquals(Request.Joined(t), SignOutAttemptPolicy.request(recovering(TeardownKnowledge.LANDED), a7))
    }

    @Test
    fun anOpenAttemptRejectsAllFourAccessEntrypoints() {
        assertTrue(SignOutAttemptPolicy.admitsAccessQueries(null))
        listOf(
            preparing(),
            armed(),
            recovering(TeardownKnowledge.NOT_OWED),
            unresolved(PendingEdit.END, TeardownKnowledge.OWED)
        ).forEach { assertFalse("$it 가 조회를 받았다", SignOutAttemptPolicy.admitsAccessQueries(it)) }
    }

    @Test
    fun unknownEditBlocksLaterNamespaceTransitions() {
        assertTrue(SignOutAttemptPolicy.admitsIdentityEvents(null))
        assertTrue(SignOutAttemptPolicy.admitsIdentityEvents(armed()))
        assertTrue(SignOutAttemptPolicy.admitsIdentityEvents(recovering(TeardownKnowledge.OWED)))
        assertFalse(SignOutAttemptPolicy.admitsIdentityEvents(preparing()))
        assertFalse(SignOutAttemptPolicy.admitsIdentityEvents(unresolved(PendingEdit.BIND_OWNER, TeardownKnowledge.OWED)))
    }

    // Prologue

    @Test
    fun stalePrologueDoesNotPrepareSignOut() {
        assertEquals(Prologue.Stale, SignOutAttemptPolicy.prologue(t, a7, live = a8, completed = a7, Result.success("user-a")))
        assertEquals(Prologue.Stale, SignOutAttemptPolicy.prologue(t, a7, live = null, completed = a7, Result.success("user-a")))
        assertEquals(
            Prologue.Stale,
            SignOutAttemptPolicy.prologue(t, a7, live = a8, completed = a7, Result.failure(IllegalStateException()))
        )
    }

    @Test
    fun unreadableOwnerLeavesTheAttemptUnresolved() {
        val outcome = SignOutAttemptPolicy.prologue(
            t, a7, live = a7, completed = a7, Result.failure(IllegalStateException("disk"))
        )
        assertEquals(
            Prologue.Unreadable(SignOutAttempt.Unresolved(t, a7, PendingEdit.READ, TeardownKnowledge.NOT_OWED, before = null)),
            outcome
        )
    }

    @Test
    fun prologueDoesNotRepairAnUncompletedBinding() {
        val expected = Prologue.RecoveryRequired(SignOutAttempt.Recovering(t, a7, TeardownKnowledge.NOT_OWED))
        assertEquals(expected, SignOutAttemptPolicy.prologue(t, a7, live = a7, completed = null, Result.success("user-a")))
        assertEquals(expected, SignOutAttemptPolicy.prologue(t, a7, live = a7, completed = a8, Result.success("user-a")))
        assertEquals(expected, SignOutAttemptPolicy.prologue(t, a7, live = a7, completed = a7, Result.success("user-b")))
        assertEquals(expected, SignOutAttemptPolicy.prologue(t, a7, live = a7, completed = a7, Result.success(null)))
    }

    @Test
    fun prologueSealsBeforePersistingIntent() {
        assertEquals(
            Prologue.Persist(SignOutAttempt.Preparing(t, a7)),
            SignOutAttemptPolicy.prologue(t, a7, live = a7, completed = a7, Result.success("user-a"))
        )
    }

    // Intent write

    @Test
    fun confirmedIntentArmsOnlyItsOriginalDriver() {
        assertEquals(
            SignOutAttempt.Armed(t, a7),
            SignOutAttemptPolicy.intentWritten(preparing(), EditResult.Landed(armedA), live = a7)
        )
    }

    @Test
    fun identityMovingDuringIntentWriteRequiresRecovery() {
        val owed = SignOutAttempt.Recovering(t, a7, TeardownKnowledge.OWED)
        assertEquals(owed, SignOutAttemptPolicy.intentWritten(preparing(), EditResult.Landed(armedA), live = a8))
        assertEquals(owed, SignOutAttemptPolicy.intentWritten(preparing(), EditResult.Landed(armedA), live = null))
    }

    @Test
    fun unpersistedIntentEntersRecoveryWithoutTeardown() {
        val notOwed = SignOutAttempt.Recovering(t, a7, TeardownKnowledge.NOT_OWED)
        val anotherOwner = AccessEpochTransitions.bindOwner(boundA, b3.uid, ids)
        val noOp = AccessEpochTransitions.beginSignOut(anotherOwner, a7.uid)
        assertEquals(notOwed, SignOutAttemptPolicy.intentWritten(preparing(), EditResult.Landed(noOp), live = a7))
        assertEquals(notOwed, SignOutAttemptPolicy.intentWritten(preparing(), EditResult.NotAttempted, live = a7))
    }

    @Test
    fun unreadableIntentOutcomeRetainsTheGate() {
        assertEquals(
            SignOutAttempt.Unresolved(t, a7, PendingEdit.BEGIN_SIGN_OUT, TeardownKnowledge.NOT_OWED, boundA),
            SignOutAttemptPolicy.intentWritten(preparing(), EditResult.Unknown(boundA), live = a7)
        )
    }

    // Rotation receipt

    @Test
    fun rotationReceiptIsTheJournalEntryNotAChangedId() {
        assertTrue(SignOutAttemptPolicy.retired(armedA, settledA, "user-a"))
        // A namespace allocated from nothing changes the ids without retiring anything.
        val unallocated = AccessEpochRecord(ownerUid = "user-a")
        assertFalse(SignOutAttemptPolicy.retired(unallocated, AccessEpochTransitions.ensureNamespace(unallocated, ids), "user-a"))
        // A KRX-only rotation leaves the user namespace in place.
        val krxOnly = AccessEpochTransitions.rotate(boundA, rotateUser = false, rotateKrx = true, ids = ids)
        assertFalse(SignOutAttemptPolicy.retired(boundA, krxOnly, "user-a"))
        // Somebody else's namespace is not this uid's receipt.
        assertFalse(SignOutAttemptPolicy.retired(armedA, settledA, "user-b"))
        // Outside retired's before-purge input contract: pin the helper's journal-only result.
        // This does not show that an id comparison fails for inputs within that contract.
        val purged = AccessEpochTransitions.completePurges(settledA, settledA.pendingPurges)
        assertFalse(SignOutAttemptPolicy.retired(armedA, purged, "user-a"))
    }

    @Test
    fun anOwnerChangeRetiresThePreviousOwnersNamespace() {
        val rebound = AccessEpochTransitions.bindOwner(armedA, "user-b", ids)
        assertTrue(SignOutAttemptPolicy.retired(armedA, rebound, "user-a"))
    }

    // Identity bound while an attempt is open

    @Test
    fun sameUidGenerationChangeSettlesIntentWithoutGranting() {
        assertEquals(
            SignOutAttempt.Recovering(t, a7, TeardownKnowledge.LANDED),
            SignOutAttemptPolicy.identityBound(armed(), retiredAttemptNamespace = true)
        )
        // A binding that did not settle still takes the right to run the sign-out away.
        assertEquals(
            SignOutAttempt.Recovering(t, a7, TeardownKnowledge.OWED),
            SignOutAttemptPolicy.identityBound(armed(), retiredAttemptNamespace = false)
        )
    }

    @Test
    fun aBindingKeepsWhatRecoveryAlreadyKnew() {
        assertEquals(
            recovering(TeardownKnowledge.NOT_OWED),
            SignOutAttemptPolicy.identityBound(recovering(TeardownKnowledge.NOT_OWED), retiredAttemptNamespace = false)
        )
        assertEquals(
            recovering(TeardownKnowledge.LANDED),
            SignOutAttemptPolicy.identityBound(recovering(TeardownKnowledge.LANDED), retiredAttemptNamespace = false)
        )
    }

    @Test
    fun anAttemptWithAnEditInFlightCannotBeReachedByIdentityEvents() {
        val waiting: List<SignOutAttempt> = listOf(
            preparing(),
            unresolved(PendingEdit.BIND_OWNER, TeardownKnowledge.OWED)
        )
        val outcomes: List<EditResult> = listOf(
            EditResult.Landed(settledA),
            EditResult.NotAttempted,
            EditResult.Unknown(armedA)
        )
        waiting.forEach { attempt ->
            listOf(false, true).forEach { retired ->
                assertThrows(IllegalStateException::class.java) {
                    SignOutAttemptPolicy.identityBound(attempt, retiredAttemptNamespace = retired)
                }
            }
            outcomes.forEach { outcome ->
                assertThrows(IllegalStateException::class.java) {
                    SignOutAttemptPolicy.afterEnd(attempt, a7, outcome, live = null)
                }
            }
        }
    }

    // Ends

    @Test
    fun ordinaryEndRotatesItsCurrentOwner() {
        assertEquals(EndPlan.ROTATE, SignOutAttemptPolicy.planEnd(a7, diskOwner = "user-a"))
    }

    @Test
    fun endNeverRotatesAnotherDiskOwner() {
        assertEquals(EndPlan.LEAVE_DISK, SignOutAttemptPolicy.planEnd(a7, diskOwner = "user-b"))
        assertEquals(EndPlan.LEAVE_DISK, SignOutAttemptPolicy.planEnd(a7, diskOwner = null))
        assertEquals(
            SignOutAttempt.Recovering(t, a7, TeardownKnowledge.OWED),
            SignOutAttemptPolicy.afterEnd(armed(), a7, EditResult.NotAttempted, live = null)
        )
    }

    @Test
    fun matchingEndFinishesTheAttempt() {
        assertNull(SignOutAttemptPolicy.afterEnd(armed(), a7, EditResult.Landed(settledA), live = null))
        // Already landed by something else: the end is still rotated, and still finishes.
        assertNull(
            SignOutAttemptPolicy.afterEnd(recovering(TeardownKnowledge.LANDED), a7, EditResult.Landed(settledA), live = null)
        )
    }

    @Test
    fun otherEndRotatesButDoesNotFinishTheAttempt() {
        // Somebody is signed in again.
        assertEquals(
            recovering(TeardownKnowledge.LANDED),
            SignOutAttemptPolicy.afterEnd(armed(), a7, EditResult.Landed(settledA), live = a8)
        )
        // The end of another session of the same uid retires that uid's namespace too.
        assertEquals(
            recovering(TeardownKnowledge.LANDED),
            SignOutAttemptPolicy.afterEnd(armed(), a8, EditResult.Landed(settledA), live = null)
        )
        // Another uid's end says nothing about this attempt's namespace.
        assertEquals(
            recovering(TeardownKnowledge.OWED),
            SignOutAttemptPolicy.afterEnd(armed(), b3, EditResult.Landed(settledA), live = null)
        )
    }

    @Test
    fun ambiguousEndRotationIsNotBlindlyRetried() {
        assertEquals(
            SignOutAttempt.Unresolved(t, a7, PendingEdit.END, TeardownKnowledge.OWED, armedA),
            SignOutAttemptPolicy.afterEnd(armed(), a7, EditResult.Unknown(armedA), live = null)
        )
    }

    // Abort and read-back

    @Test
    fun abortRequiresRecoveryWithoutAnotherAuthEvent() {
        assertEquals(recovering(TeardownKnowledge.OWED), SignOutAttemptPolicy.driverStopped(armed()))
        // Already taken away: stopping the driver changes nothing more.
        assertEquals(recovering(TeardownKnowledge.LANDED), SignOutAttemptPolicy.driverStopped(recovering(TeardownKnowledge.LANDED)))
        assertEquals(preparing(), SignOutAttemptPolicy.driverStopped(preparing()))
    }

    @Test
    fun aFailedEditKeepsWhatTheAttemptKnew() {
        assertEquals(
            SignOutAttempt.Unresolved(t, a7, PendingEdit.BIND_OWNER, TeardownKnowledge.OWED, armedA),
            SignOutAttemptPolicy.editFailed(armed(), PendingEdit.BIND_OWNER, armedA)
        )
        assertEquals(
            SignOutAttempt.Unresolved(t, a7, PendingEdit.READ, TeardownKnowledge.LANDED, null),
            SignOutAttemptPolicy.editFailed(recovering(TeardownKnowledge.LANDED), PendingEdit.READ, null)
        )
        assertThrows(IllegalStateException::class.java) {
            SignOutAttemptPolicy.editFailed(preparing(), PendingEdit.BIND_OWNER, armedA)
        }
    }

    @Test
    fun readBackOfAFailedReadAddsNothing() {
        val read = SignOutAttempt.Unresolved(t, a7, PendingEdit.READ, TeardownKnowledge.OWED, before = null)
        assertEquals(Resolution.Resolved(recovering(TeardownKnowledge.OWED)), SignOutAttemptPolicy.resolve(read, settledA))
    }

    @Test
    fun readBackOfTheIntentWriteSaysOnlyWhetherItIsOwed() {
        val begin = SignOutAttempt.Unresolved(t, a7, PendingEdit.BEGIN_SIGN_OUT, TeardownKnowledge.NOT_OWED, boundA)
        assertEquals(Resolution.Resolved(recovering(TeardownKnowledge.OWED)), SignOutAttemptPolicy.resolve(begin, armedA))
        assertEquals(Resolution.Resolved(recovering(TeardownKnowledge.NOT_OWED)), SignOutAttemptPolicy.resolve(begin, boundA))
    }

    @Test
    fun readBackOfABindCountsOnlyTheJournalReceipt() {
        val bind = unresolved(PendingEdit.BIND_OWNER, TeardownKnowledge.OWED)
        // The same uid's bind settles the intent; another uid's retires the namespace by the owner change.
        val sameUid = AccessEpochTransitions.bindOwner(armedA, "user-a", ids)
        val otherUid = AccessEpochTransitions.bindOwner(armedA, "user-b", ids)
        assertEquals(Resolution.Resolved(recovering(TeardownKnowledge.LANDED)), SignOutAttemptPolicy.resolve(bind, sameUid))
        assertEquals(Resolution.Resolved(recovering(TeardownKnowledge.LANDED)), SignOutAttemptPolicy.resolve(bind, otherUid))
        assertEquals(Resolution.Resolved(recovering(TeardownKnowledge.OWED)), SignOutAttemptPolicy.resolve(bind, armedA))
    }

    @Test
    fun readBackOfASettleThatDidNotLandLeavesItOwed() {
        val settle = unresolved(PendingEdit.SETTLE, TeardownKnowledge.OWED)
        assertEquals(Resolution.Resolved(recovering(TeardownKnowledge.LANDED)), SignOutAttemptPolicy.resolve(settle, settledA))
        assertEquals(Resolution.Resolved(recovering(TeardownKnowledge.OWED)), SignOutAttemptPolicy.resolve(settle, armedA))
    }

    @Test
    fun aLandedEndLeavesItsResultForTheRetry() {
        val end = unresolved(PendingEdit.END, TeardownKnowledge.OWED)
        assertEquals(
            Resolution.Resolved(recovering(TeardownKnowledge.OWED), endReceipt = EditResult.Landed(settledA)),
            SignOutAttemptPolicy.resolve(end, settledA)
        )
    }

    @Test
    fun anotherUidsLandedEndIsReadByTheOwnerItRotated() {
        // The attempt is a7's, but the end being retried is user-b's: the receipt is b's epoch.
        val boundB = AccessEpochTransitions.bindOwner(settledA, "user-b", ids)
        val endOfB = SignOutAttempt.Unresolved(t, a7, PendingEdit.END, TeardownKnowledge.LANDED, boundB)
        val landed = AccessEpochTransitions.signOut(boundB, ids)
        assertEquals(
            Resolution.Resolved(recovering(TeardownKnowledge.LANDED), endReceipt = EditResult.Landed(landed)),
            SignOutAttemptPolicy.resolve(endOfB, landed)
        )
    }

    @Test
    fun anEndThatDidNotLandIsReadByTheNamespaceAlone() {
        val end = unresolved(PendingEdit.END, TeardownKnowledge.OWED)
        val marked = AccessEpochTransitions.markMayContainData(armedA, premium = true, krx = true)
        assertEquals(Resolution.Resolved(recovering(TeardownKnowledge.OWED)), SignOutAttemptPolicy.resolve(end, armedA))
        assertEquals(Resolution.Resolved(recovering(TeardownKnowledge.OWED)), SignOutAttemptPolicy.resolve(end, marked))
    }

    @Test
    fun anEndReadBackThatIsNeitherIsNotGuessed() {
        val end = unresolved(PendingEdit.END, TeardownKnowledge.OWED)
        listOf(
            armedA.copy(userAccessEpoch = "not-from-any-rotation"),
            armedA.copy(krxCapabilityEpoch = "not-from-any-rotation"),
            armedA.copy(ownerUid = "user-b"),
            armedA.copy(teardownOwedFor = null),
            armedA.copy(pendingPurges = listOf(PendingPurge("user-a", "elsewhere", null, AccessEpochTransitions.ALL_SCOPES)))
        ).forEach { assertEquals("$it", Resolution.Inconsistent, SignOutAttemptPolicy.resolve(end, it)) }
    }

    // Recovery

    @Test
    fun recoverySettlesTheStillOwedOwnerOnce() {
        assertEquals(RecoveryStep.SETTLE, SignOutAttemptPolicy.recover(recovering(TeardownKnowledge.OWED), armedA))
        assertEquals(
            recovering(TeardownKnowledge.LANDED),
            SignOutAttemptPolicy.settled(recovering(TeardownKnowledge.OWED), EditResult.Landed(settledA))
        )
    }

    @Test
    fun recoveryDoesNotRotateAnUnowedOrSettledAttempt() {
        assertEquals(RecoveryStep.AWAIT_BARRIER, SignOutAttemptPolicy.recover(recovering(TeardownKnowledge.NOT_OWED), armedA))
        assertEquals(RecoveryStep.AWAIT_BARRIER, SignOutAttemptPolicy.recover(recovering(TeardownKnowledge.LANDED), armedA))
    }

    @Test
    fun owedInMemoryButNotOnDiskIsNotGuessed() {
        assertEquals(RecoveryStep.INCONSISTENT, SignOutAttemptPolicy.recover(recovering(TeardownKnowledge.OWED), boundA))
    }

    @Test
    fun ambiguousSettlementIsRecordedNotRetried() {
        assertEquals(
            SignOutAttempt.Unresolved(t, a7, PendingEdit.SETTLE, TeardownKnowledge.OWED, armedA),
            SignOutAttemptPolicy.settled(recovering(TeardownKnowledge.OWED), EditResult.Unknown(armedA))
        )
    }

    // Barrier

    @Test
    fun barrierWaitsForSettlement() {
        assertEquals(Barrier.NotReady, SignOutAttemptPolicy.barrier(recovering(TeardownKnowledge.OWED), a7, live = a7, completed = a7))
    }

    @Test
    fun recoveryBarrierBindsBeforeReleasingTheGate() {
        assertEquals(
            Barrier.Bind("user-a"),
            SignOutAttemptPolicy.barrier(recovering(TeardownKnowledge.LANDED), a8, live = a8, completed = a7)
        )
        assertTrue(SignOutAttemptPolicy.barrierBound(a8, EditResult.Landed(boundA), liveAfter = a8))
    }

    @Test
    fun movingIdentityKeepsRecoverySealed() {
        assertEquals(
            Barrier.Recapture,
            SignOutAttemptPolicy.barrier(recovering(TeardownKnowledge.LANDED), a8, live = b3, completed = a7)
        )
        assertFalse(SignOutAttemptPolicy.barrierBound(a8, EditResult.Unknown(boundA), liveAfter = a8))
        assertFalse(SignOutAttemptPolicy.barrierBound(a8, EditResult.Landed(boundA), liveAfter = b3))
        assertEquals(
            Barrier.Hold,
            SignOutAttemptPolicy.barrier(recovering(TeardownKnowledge.LANDED), null, live = null, completed = a7)
        )
    }

    @Test
    fun recoveryBarrierDoesNotAdoptAnIdentityQueuedBehindIt() {
        assertEquals(
            Barrier.Recapture,
            SignOutAttemptPolicy.barrier(recovering(TeardownKnowledge.NOT_OWED), candidate = null, live = a8, completed = null)
        )
    }

    @Test
    fun signedOutRecoveryCompletesWithoutAnotherRotation() {
        assertEquals(
            Barrier.FinishSignedOut,
            SignOutAttemptPolicy.barrier(recovering(TeardownKnowledge.NOT_OWED), candidate = null, live = null, completed = null)
        )
    }

    // A held barrier's bind

    @Test
    fun bindLandedIsTheBindPostcondition() {
        assertTrue(SignOutAttemptPolicy.bindLanded(boundA, "user-a"))
        assertFalse("정산 전 의도가 남았다", SignOutAttemptPolicy.bindLanded(armedA, "user-a"))
        assertFalse(SignOutAttemptPolicy.bindLanded(boundA, "user-b"))
        assertFalse(SignOutAttemptPolicy.bindLanded(boundA.copy(userAccessEpoch = null), "user-a"))
        assertFalse(SignOutAttemptPolicy.bindLanded(boundA.copy(krxCapabilityEpoch = null), "user-a"))
    }

    @Test
    fun anUnknownBarrierBindIsClassifiedByTheCandidatesBinding() {
        val unknown = BarrierBind.Unknown(before = AccessEpochRecord())
        assertEquals(BarrierBind.Landed, SignOutAttemptPolicy.barrierBindResolved(unknown, a7, boundA))
        assertEquals(BarrierBind.NotLanded, SignOutAttemptPolicy.barrierBindResolved(unknown, a7, AccessEpochRecord()))
        assertNull(SignOutAttemptPolicy.barrierBindResolved(unknown, a7, boundA.copy(ownerUid = "user-b")))
    }
}
