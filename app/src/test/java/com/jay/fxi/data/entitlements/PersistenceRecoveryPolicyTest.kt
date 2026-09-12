package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.PersistenceRecoveryPolicy.Round
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The budget, in isolation from any disk.
 *
 * One round is one admitted resume: the read-back it needs and the re-execution that read-back
 * authorises belong to it, and a failure ends it. What must not happen is a hold that keeps
 * earning rounds — from a manual wake that arrives mid-batch, from a successful read-back, or
 * from a phase change.
 */
class PersistenceRecoveryPolicyTest {

    private val work = IdentityWork.Bind(AuthIdentityFence("user-a", 1))

    private fun held(
        spent: Int = 0,
        nextAttemptAt: Long? = 100L,
        wakeRequested: Boolean = false
    ) = PendingPersistence(
        id = 7L,
        work = work,
        phase = PendingPersistence.Phase.Unknown(PendingEdit.BIND_OWNER, before = null),
        revision = 3L,
        spent = spent,
        nextAttemptAt = nextAttemptAt,
        wakeRequested = wakeRequested
    )

    @Test
    fun aResumeNamingAnotherHold_consumesNothing() {
        assertEquals(Round.Stale, PersistenceRecoveryPolicy.admitRound(held(), id = 8L, now = 999L))
    }

    @Test
    fun aResumeBeforeTheScheduledTime_consumesNothing() {
        assertEquals(Round.TooEarly, PersistenceRecoveryPolicy.admitRound(held(nextAttemptAt = 100L), id = 7L, now = 99L))
    }

    /** The timer alone is enough: nothing else changes state while a hold waits. */
    @Test
    fun theScheduledTime_admitsARoundWithNoSignalAtAll() {
        val admitted = PersistenceRecoveryPolicy.admitRound(held(spent = 1), id = 7L, now = 100L)

        assertEquals(2, (admitted as Round.Run).pending.spent)
    }

    /**
     * Admitting spends the schedule, so a second resume for the same deadline finds nothing due.
     *
     * The coordinator runs admit, work and result under one lock hold, so the second resume only
     * ever sees the state the first one left: rescheduled after a failure (here), cleared after a
     * completion, or stopped. That two arrivals really do one disk step is the wiring's test — this
     * one pins the state the policy leaves behind.
     */
    @Test
    fun aSecondResumeForTheSameDeadline_findsNothingDue() {
        val pending = held(spent = 0, nextAttemptAt = 100L)

        val first = PersistenceRecoveryPolicy.admitRound(pending, id = 7L, now = 120L) as Round.Run
        assertEquals(1, first.pending.spent)
        assertNull("입장이 예약을 소비한다", first.pending.nextAttemptAt)

        val afterFailure = PersistenceRecoveryPolicy.afterFailedRound(first.pending, now = 120L, delayMillis = 30L, undecidable = false)

        assertEquals(Round.TooEarly, PersistenceRecoveryPolicy.admitRound(afterFailure, id = 7L, now = 120L))
    }

    /** A wake that was already pending is what restarts a stopped batch — that is its purpose. */
    @Test
    fun aWakeHeldThroughAStoppedBatch_startsTheNextOne() {
        val stopped = PersistenceRecoveryPolicy.afterFailedRound(
            held(spent = 1, nextAttemptAt = null, wakeRequested = true),
            now = 120L,
            delayMillis = 30L,
            undecidable = true
        )

        val admitted = PersistenceRecoveryPolicy.admitRound(stopped, id = 7L, now = 120L) as Round.Run

        assertEquals(1, admitted.pending.spent)
        assertFalse(admitted.pending.wakeRequested)
    }

    @Test
    fun aStoppedHoldWithNoWake_staysStopped() {
        assertEquals(Round.Blocked, PersistenceRecoveryPolicy.admitRound(held(nextAttemptAt = null), id = 7L, now = 999L))
    }

    /**
     * The correction that matters: a wake arriving while a batch is still scheduled must not skip
     * that batch's delay, and must not be spent early.
     */
    @Test
    fun aWakeDuringAScheduledBatch_neitherSkipsTheDelayNorIsSpent() {
        val pending = held(spent = 1, nextAttemptAt = 100L, wakeRequested = true)

        assertEquals(Round.TooEarly, PersistenceRecoveryPolicy.admitRound(pending, id = 7L, now = 99L))

        val onTime = PersistenceRecoveryPolicy.admitRound(pending, id = 7L, now = 100L) as Round.Run
        assertEquals("현재 배치의 회차가 이어져야 한다", 2, onTime.pending.spent)
        assertEquals("깨우기는 아직 쓰지 않았다", true, onTime.pending.wakeRequested)
    }

    @Test
    fun aWakeOnAStoppedHold_startsANewBatchAtItsFirstRound() {
        val exhausted = held(spent = PersistenceRecoveryPolicy.AUTOMATIC_ROUNDS, nextAttemptAt = null, wakeRequested = true)

        val admitted = PersistenceRecoveryPolicy.admitRound(exhausted, id = 7L, now = 999L) as Round.Run

        assertEquals("새 배치는 자기 회차부터 센다", 1, admitted.pending.spent)
        assertFalse("깨우기는 실행권을 얻을 때 소비된다", admitted.pending.wakeRequested)
    }

    @Test
    fun afterAFailedRound_theNextIsScheduledUntilTheBudgetRunsOut() {
        val first = PersistenceRecoveryPolicy.afterFailedRound(held(spent = 1), now = 500L, delayMillis = 30L, undecidable = false)
        assertEquals(530L, first.nextAttemptAt)
        assertEquals("상태가 바뀌면 revision 이 오른다", 4L, first.revision)

        val last = PersistenceRecoveryPolicy.afterFailedRound(
            held(spent = PersistenceRecoveryPolicy.AUTOMATIC_ROUNDS),
            now = 500L,
            delayMillis = 30L,
            undecidable = false
        )
        assertNull(last.nextAttemptAt)
        assertEquals("사유는 상태가 들고 있어야 한다", NoAutoRetry.BUDGET_EXHAUSTED, last.blocked)
    }

    /**
     * An undecidable read-back stops the batch whatever the budget says — and the two reasons must
     * stay apart on the record, because the same "no deadline" would otherwise stand for both.
     */
    @Test
    fun anUndecidableRoundStops_evenWithBudgetLeft_andSaysWhich() {
        val undecidable = PersistenceRecoveryPolicy.afterFailedRound(held(spent = 1), now = 500L, delayMillis = 30L, undecidable = true)
        val exhausted = PersistenceRecoveryPolicy.afterFailedRound(
            held(spent = PersistenceRecoveryPolicy.AUTOMATIC_ROUNDS),
            now = 500L,
            delayMillis = 30L,
            undecidable = false
        )

        assertNull(undecidable.nextAttemptAt)
        assertEquals(NoAutoRetry.UNDECIDABLE, undecidable.blocked)
        assertNull(exhausted.nextAttemptAt)
        assertEquals(NoAutoRetry.BUDGET_EXHAUSTED, exhausted.blocked)
        assertNotEquals("두 중단을 같은 상태로 저장하면 안 된다", undecidable.blocked, exhausted.blocked)
    }

    @Test
    fun aScheduledHold_carriesNoBlockedReason() {
        val scheduled = PersistenceRecoveryPolicy.afterFailedRound(held(spent = 0), now = 500L, delayMillis = 30L, undecidable = false)

        assertEquals(530L, scheduled.nextAttemptAt)
        assertNull(scheduled.blocked)
    }

    /** A new batch clears the old stop reason: it is no longer the state of this hold. */
    @Test
    fun aWakeStartingANewBatch_clearsTheStopReason() {
        val stopped = held(spent = 3, nextAttemptAt = null, wakeRequested = true).copy(blocked = NoAutoRetry.BUDGET_EXHAUSTED)

        val admitted = PersistenceRecoveryPolicy.admitRound(stopped, id = 7L, now = 999L) as Round.Run

        assertNull(admitted.pending.blocked)
    }
}
