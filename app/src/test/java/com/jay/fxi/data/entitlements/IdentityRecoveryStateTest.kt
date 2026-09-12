package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a surface may say, in isolation from any coordinator.
 *
 * The rule this pins is not "render the hold" but *when* a hold has earned a place on screen and
 * what it looks like once it has. A hold opened by the first failed disk step is still being
 * retried; putting that in front of the user would turn every transient fault into an error.
 */
class IdentityRecoveryStateTest {

    private val fence = AuthIdentityFence("user-a", 7)
    private val ticket = SignOutTicket(1)

    private fun hold(
        work: IdentityWork = IdentityWork.Bind(fence),
        nextAttemptAt: Long? = 100L,
        wakeRequested: Boolean = false,
        blocked: NoAutoRetry? = null
    ) = PendingPersistence(
        id = 7L,
        work = work,
        phase = PendingPersistence.Phase.Unknown(PendingEdit.BIND_OWNER, before = null),
        revision = 3L,
        spent = 1,
        nextAttemptAt = nextAttemptAt,
        wakeRequested = wakeRequested,
        blocked = blocked
    )

    private fun state(
        attempt: SignOutAttempt? = null,
        automaticRunning: Boolean = false,
        hold: PendingPersistence? = null,
        surfaced: Boolean = false,
        runningHoldId: Long? = null
    ) = identityRecoveryOf(attempt, automaticRunning, hold, surfaced, runningHoldId)

    @Test
    fun withNothingHeld_thereIsNothingToSay() {
        assertEquals(IdentityRecoveryState.None, state())
    }

    /** A hold that has not stopped yet is still the machine's business, not the user's. */
    @Test
    fun aHoldStillRunningItsAutomaticBatch_saysNothing() {
        assertEquals(IdentityRecoveryState.None, state(hold = hold(), surfaced = false))
    }

    @Test
    fun aStoppedHoldThatHasBeenSurfaced_offersItsIdAndReason() {
        val shown = state(
            hold = hold(nextAttemptAt = null, blocked = NoAutoRetry.BUDGET_EXHAUSTED),
            surfaced = true
        ) as IdentityRecoveryState.HoldUnfinished

        assertEquals(7L, shown.id)
        assertEquals(HeldWork.SIGN_IN, shown.work)
        assertEquals(HoldProgress.STOPPED, shown.progress)
        assertEquals(NoAutoRetry.BUDGET_EXHAUSTED, shown.reason)
    }

    /**
     * The reason a surface cannot filter on `blocked` alone: admitting a re-check clears it, so a
     * live filter would take the banner away when the admitted round's updated hold is published.
     *
     * The `blocked` here is the one a round records as it ends, before the running mark is dropped:
     * without the suppression that instant would read as executing *and* stopped at once.
     */
    @Test
    fun aSurfacedHoldMidRecheck_staysOnScreenWithoutAReason() {
        val running = state(
            hold = hold(nextAttemptAt = null, blocked = NoAutoRetry.BUDGET_EXHAUSTED),
            surfaced = true,
            runningHoldId = 7L
        ) as IdentityRecoveryState.HoldUnfinished

        assertEquals(HoldProgress.RUNNING, running.progress)
        assertEquals("실행 중인 회차에 멈춤 이유가 붙었다", null, running.reason)
    }

    /** A wake recorded against a batch that is still scheduled is kept, not spent — so it is not "asked for". */
    @Test
    fun aWakeHeldThroughAScheduledBatch_readsAsScheduled() {
        val shown = state(hold = hold(nextAttemptAt = 100L, wakeRequested = true), surfaced = true)
            as IdentityRecoveryState.HoldUnfinished

        assertEquals(HoldProgress.SCHEDULED, shown.progress)
    }

    @Test
    fun aWakeOnAStoppedBatch_readsAsRequestedOnceTheStopIsLifted() {
        val shown = state(hold = hold(nextAttemptAt = null, wakeRequested = true), surfaced = true)
            as IdentityRecoveryState.HoldUnfinished

        assertEquals(HoldProgress.REQUESTED, shown.progress)
    }

    /**
     * The real shape after the button is pressed: `retryPersistence` records the wake and leaves
     * `blocked` alone, so a surface reading that field first would show the button again straight
     * away — and a second press would be refused, since the wake is already standing.
     */
    @Test
    fun aWakeRecordedOnATopOfAStopReadsAsRequestedNotStopped() {
        val shown = state(
            hold = hold(nextAttemptAt = null, wakeRequested = true, blocked = NoAutoRetry.BUDGET_EXHAUSTED),
            surfaced = true
        ) as IdentityRecoveryState.HoldUnfinished

        assertEquals(HoldProgress.REQUESTED, shown.progress)
        assertEquals("멈춘 이유는 회차가 입장할 때까지 참이다", NoAutoRetry.BUDGET_EXHAUSTED, shown.reason)
    }

    /**
     * An admitted automatic round *keeps* a wake that was held through it, so the fields alone say
     * "requested" for a round that is already at work. The executing id is what tells them apart.
     */
    @Test
    fun anExecutingRoundThatKeptAWakeIsRunningNotRequested() {
        val shown = state(
            hold = hold(nextAttemptAt = null, wakeRequested = true),
            surfaced = true,
            runningHoldId = 7L
        ) as IdentityRecoveryState.HoldUnfinished

        assertEquals(HoldProgress.RUNNING, shown.progress)
    }

    /** The executing id names one hold. Another hold's round says nothing about this one. */
    @Test
    fun anotherHoldsRoundDoesNotMakeThisOneRunning() {
        val shown = state(
            hold = hold(nextAttemptAt = null, blocked = NoAutoRetry.BUDGET_EXHAUSTED),
            surfaced = true,
            runningHoldId = 99L
        ) as IdentityRecoveryState.HoldUnfinished

        assertEquals(HoldProgress.STOPPED, shown.progress)
        assertEquals(NoAutoRetry.BUDGET_EXHAUSTED, shown.reason)
    }

    @Test
    fun eachKindOfWorkIsNamed() {
        fun workOf(work: IdentityWork) =
            (state(hold = hold(work = work, nextAttemptAt = null, blocked = NoAutoRetry.UNDECIDABLE), surfaced = true)
                as IdentityRecoveryState.HoldUnfinished).work

        assertEquals(HeldWork.STARTUP_PURGE, workOf(IdentityWork.StartupPurge))
        assertEquals(HeldWork.SIGN_IN, workOf(IdentityWork.Bind(fence)))
        assertEquals(HeldWork.SIGN_OUT, workOf(IdentityWork.End(fence)))
    }

    /**
     * An `Armed` attempt is excluded from the `recovering` flag the supervisor watches, so a
     * surface keyed on the recovery status alone would report nothing while access is sealed. The
     * attempt itself is the source.
     */
    @Test
    fun anArmedAttemptIsReportedEvenWithNoRecoveryRun() {
        val shown = state(attempt = SignOutAttempt.Armed(ticket, fence), automaticRunning = false)

        assertEquals(IdentityRecoveryState.SignOutUnfinished(recovering = false), shown)
    }

    @Test
    fun aClaimedRunIsReportedAsRecovering() {
        val shown = state(
            attempt = SignOutAttempt.Recovering(ticket, fence, TeardownKnowledge.OWED),
            automaticRunning = true
        )

        assertEquals(IdentityRecoveryState.SignOutUnfinished(recovering = true), shown)
    }
}
