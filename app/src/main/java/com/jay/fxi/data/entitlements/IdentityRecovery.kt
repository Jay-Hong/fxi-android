package com.jay.fxi.data.entitlements

/**
 * Which head task did not finish, so a message can name it rather than say "something".
 *
 * Deliberately coarser than [IdentityWork]: a banner needs to know what the user was doing, not
 * which fence or uid it was doing it for.
 */
internal enum class HeldWork {
    STARTUP_PURGE,
    SIGN_IN,
    SIGN_OUT
}

/**
 * Where a hold stands, for a surface that has to choose between an action and a progress note.
 *
 * [RUNNING] comes from the coordinator's executing round; the rest are read off the hold's own
 * schedule and wake. Both are projected together under the coordinator's mutex.
 */
internal enum class HoldProgress {
    /** An automatic round is scheduled. Nothing to ask for. */
    SCHEDULED,

    /** A round has been admitted and its disk work is under way. */
    RUNNING,

    /** A re-check has been recorded and the driver has not picked it up yet. */
    REQUESTED,

    /** Nothing will run until somebody asks. Only here does a surface offer the action. */
    STOPPED
}

/**
 * What a surface may tell the user about identity work that has not finished.
 *
 * The two sources are mutually exclusive by construction: a hold opens only with no attempt open
 * (`unownedEditLocked` and `cleanupOrHoldLocked` both require it), and an attempt opens only with
 * no hold standing (`prepareSignOut` checks it). So this is one slot, not two overlapping ones.
 *
 * **Nothing here proves data was deleted.** The placeholder purgers still answer `Deferred`, so
 * [IdentityRecoveryState.None] is neither an access-admission signal nor evidence that the journal
 * work completed.
 */
internal sealed interface IdentityRecoveryState {

    /**
     * No banner is selected.
     *
     * **Not** "access is admitted again": a hold still working through its first automatic batch
     * has not been surfaced, and it refuses access queries the whole time it reads as this.
     */
    data object None : IdentityRecoveryState

    /**
     * A sign-out attempt is open and has not released the seal.
     *
     * Reported only. This is an observation about the attempt's existence — it does not claim the
     * driver died, and offering an action here would mean re-entering the recovery machine, which
     * is a separate decision from wiring a banner.
     */
    data class SignOutUnfinished(
        /** An automatic recovery run is in progress. False also covers "no run was ever claimed". */
        val recovering: Boolean
    ) : IdentityRecoveryState

    /**
     * A head task's own disk work is unresolved, and the user has been told about it.
     *
     * Only surfaced once the automatic batch has stopped — a hold opened by the first failure is
     * still being retried, and showing it would put an error in front of every transient fault.
     * Once surfaced it stays until the hold clears, including while a requested round runs: the
     * wake branch of `admitRound` sets `blocked` back to null, so a live filter on that field alone
     * would hide the banner when the admitted round's updated hold is published.
     */
    data class HoldUnfinished(
        /** What a re-check must name. A surface holding an older id gets a refusal, not a wrong hold. */
        val id: Long,
        val work: HeldWork,
        val progress: HoldProgress,
        /**
         * The last recorded automatic stop, or null while a round is scheduled or executing.
         *
         * It survives into [HoldProgress.REQUESTED]: recording a re-check does not clear it, and
         * the stop it names is still true until a round is admitted. Nothing infers a reason from
         * [progress] alone.
         *
         * Null under [HoldProgress.RUNNING] even when the hold carries a stop — a round woken out
         * of one starts before the cleared copy is published. If the round records another stop,
         * it does so before its mark is dropped. Neither instant is one to report a stop from.
         */
        val reason: NoAutoRetry?
    ) : IdentityRecoveryState
}

/**
 * Composes what a surface may say from the coordinator's own state. Pure, so the rule can be read
 * without a coordinator.
 *
 * [surfaced] is the coordinator's record of having already shown this hold; it is not derivable
 * from the hold, because a hold that is mid-re-check looks like a hold that has never stopped.
 */
internal fun identityRecoveryOf(
    attempt: SignOutAttempt?,
    automaticRunning: Boolean,
    hold: PendingPersistence?,
    surfaced: Boolean,
    runningHoldId: Long? = null
): IdentityRecoveryState = when {
    // Checked first only to make the exclusion visible; the two are never both set.
    attempt != null -> IdentityRecoveryState.SignOutUnfinished(recovering = automaticRunning)
    hold == null || !surfaced -> IdentityRecoveryState.None
    else -> IdentityRecoveryState.HoldUnfinished(
        id = hold.id,
        work = heldWorkOf(hold.work),
        progress = holdProgressOf(hold, running = runningHoldId == hold.id),
        // An executing round can be looking at a hold that carries a stop, from either end. A round
        // woken out of a stop starts before it records the copy `admitRound` cleared. If the round
        // records another stop, its mark still stands at that point. Reporting both would say the
        // round is at work *and* has given up; the mark decides which of the two is being shown.
        reason = if (runningHoldId == hold.id) null else hold.blocked
    )
}

private fun heldWorkOf(work: IdentityWork): HeldWork = when (work) {
    IdentityWork.StartupPurge -> HeldWork.STARTUP_PURGE
    // The screen's category, not the work's identity: it is start-up cleanup of what was there before,
    // and saying "signing out" would claim a sign-out happened, which this work never decides.
    IdentityWork.UnverifiedStart -> HeldWork.STARTUP_PURGE
    is IdentityWork.Bind -> HeldWork.SIGN_IN
    is IdentityWork.End -> HeldWork.SIGN_OUT
}

/**
 * The order matters, and it is not the order the fields suggest.
 *
 * [running] comes first because the fields cannot express it: a round's read-back finishes before
 * the updated hold is published, and an admitted automatic round *keeps* a wake that was held
 * through it. Without the executing id, a read-back can look scheduled or requested, and a
 * retained wake can look requested.
 *
 * Then a scheduled batch beats a standing wake — that wake was kept rather than spent precisely so
 * it would not skip the batch's delay, so nothing is waiting on the user. And a wake with nothing
 * scheduled beats [PendingPersistence.blocked], because recording one does **not** clear that
 * field; `admitRound` clears it when it admits the round. Reading `blocked` first would put the
 * button back on screen the instant the user pressed it.
 */
private fun holdProgressOf(hold: PendingPersistence, running: Boolean): HoldProgress = when {
    running -> HoldProgress.RUNNING
    hold.nextAttemptAt != null -> HoldProgress.SCHEDULED
    hold.wakeRequested -> HoldProgress.REQUESTED
    else -> HoldProgress.STOPPED
}
