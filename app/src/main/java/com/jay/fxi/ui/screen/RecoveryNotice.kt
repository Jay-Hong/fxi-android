package com.jay.fxi.ui.screen

import com.jay.fxi.data.entitlements.HeldWork
import com.jay.fxi.data.entitlements.HoldProgress
import com.jay.fxi.data.entitlements.IdentityRecoveryState
import com.jay.fxi.data.entitlements.NoAutoRetry

/**
 * Everything a banner is allowed to say, decided away from Compose.
 *
 * Kept in the same package and for the same reason as [RootDestination]: the choice is a rule, and
 * this is where CI can run it. The app's Kotlin test bodies execute in the three JVM unit-test
 * variants; androidTest sources are compiled and not executed. A composable *can* be exercised
 * directly on a device — nothing here needs the Root graph — but no CI step does that today, so a
 * rule living inside one would ship unrun. Compose is left with "draw these strings, call this
 * lambda".
 *
 * Only `String` and `Long`, so no `internal` type crosses into the UI layer with it.
 */
data class RecoveryNotice(
    /** What a re-check must name. A surface holding an older id is refused, not misapplied. */
    val holdId: Long,
    val title: String,
    val body: String,
    /**
     * The button's label, or null for no button.
     *
     * One nullable field rather than a boolean beside a string: two fields can disagree, and the
     * disagreement is exactly the bug — a button with no label, or a label with no button.
     */
    val action: String?
)

/** Offered only where pressing does something. Deliberately not "복구" — pressing buys a read, not an outcome. */
internal const val RECHECK_ACTION = "다시 확인"

/**
 * The one consequence this banner is entitled to state.
 *
 * Everything else about a hold is in progress or unknown, but this much is structural: with a hold
 * standing, `accessAdmittedLocked()` is false, so entitlement queries are refused the whole time.
 */
internal const val ACCESS_HELD_LINE = "확인이 끝날 때까지 구독 상태를 새로 조회하지 않습니다."

/** Said only while a round is actually executing — the one state where work is under way. */
internal const val RUNNING_TITLE = "계정 상태를 다시 확인하고 있습니다"

/**
 * Said for every other progress, including the ones that are proceeding normally.
 *
 * "완료되지 않았습니다" rather than "실패했습니다": a held edit's phase can still be `Unknown`, so
 * what it did is not established, and a failed round may already have landed its write with only the
 * cleanup outstanding. "Not finished" is true in all of them; "failed" is not.
 */
internal const val UNFINISHED_TITLE = "계정 처리가 아직 완료되지 않았습니다"

/**
 * Projects [state] into what a surface may show, or null to show nothing.
 *
 * Two states map to null and both are decisions, not omissions:
 *  - [IdentityRecoveryState.None] — no notice is selected. **Not** "nothing is held": a hold that
 *    has not been surfaced yet reads as this too, and it refuses access the whole time.
 *  - [IdentityRecoveryState.SignOutUnfinished] — an attempt is open, which includes the normal
 *    preparation of an admitted app sign-out: `prepareSignOut` opens the attempt before its first
 *    disk step. So the state alone is no evidence that anything stalled, and `recovering` does not
 *    separate the two. This slice hides every attempt, the stalled ones included; telling them
 *    apart needs a policy over the recovery status, which has no consumer yet.
 *
 * `internal` because [IdentityRecoveryState] is.
 */
internal fun recoveryNoticeFor(state: IdentityRecoveryState): RecoveryNotice? = when (state) {
    IdentityRecoveryState.None -> null
    is IdentityRecoveryState.SignOutUnfinished -> null
    is IdentityRecoveryState.HoldUnfinished -> RecoveryNotice(
        holdId = state.id,
        // Not keyed on `progress` being STOPPED: a hold that is scheduled, requested or running is
        // still refusing access, and a surface that appeared only at the stop would vanish the
        // moment the user asked for something.
        title = if (state.progress == HoldProgress.RUNNING) RUNNING_TITLE else UNFINISHED_TITLE,
        body = "${workLine(state.work)} ${progressLine(state.progress, state.reason)} $ACCESS_HELD_LINE",
        // The only state where nothing else will move without the user.
        action = if (state.progress == HoldProgress.STOPPED) RECHECK_ACTION else null
    )
}

/** What was interrupted, in the user's terms rather than the machine's. */
private fun workLine(work: HeldWork): String = when (work) {
    HeldWork.STARTUP_PURGE -> "이전에 사용하던 데이터를 정리하는 중이었습니다."
    // Not "로그인": `HeldWork` folds every `IdentityWork.Bind` into this, and a bind also covers a new
    // session generation for a uid that is already signed in.
    HeldWork.SIGN_IN -> "계정 정보를 저장하는 중이었습니다."
    // Not "로그아웃": this hold opens only with no attempt standing, and the app's own logout button
    // opens an attempt first — so the button the user pressed is usually not what this names.
    HeldWork.SIGN_OUT -> "계정 사용을 마무리하는 중이었습니다."
}

/**
 * What happens next. Nothing here promises an outcome.
 *
 * [NoAutoRetry.UNDECIDABLE] keeps the button — pressing records a wake, and the round it admits
 * reads the record again — but says plainly that the same answer may come back. A record the
 * corruption handler replaced can be judged differently; nothing guarantees it will be. A button
 * that silently repeats itself would be the lie; a button that says so is not.
 */
private fun progressLine(progress: HoldProgress, reason: NoAutoRetry?): String = when (progress) {
    HoldProgress.SCHEDULED -> "잠시 후 자동으로 다시 시도합니다."
    HoldProgress.RUNNING -> "잠시만 기다려 주세요."
    HoldProgress.REQUESTED -> "다시 확인을 요청했습니다."
    HoldProgress.STOPPED -> when (reason) {
        NoAutoRetry.BUDGET_EXHAUSTED -> "자동 재시도를 멈췄습니다."
        NoAutoRetry.UNDECIDABLE ->
            "이 기기에 남은 기록이 이 작업과 맞지 않아 자동 재시도를 멈췄습니다. " +
                "다시 확인해도 같은 결과가 나올 수 있습니다."
        // Total rather than a throw. No path is known to reach a stop with no reason, and a banner is
        // the wrong place to find out — it would take down the surface that was reporting the fault.
        null -> "자동 재시도가 멈춰 있습니다."
    }
}
