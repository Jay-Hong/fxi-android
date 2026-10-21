package com.jay.fxi.data.entitlements

internal enum class KrxContestPhase { NONE, AWAITING, HOLD, OPEN }

internal enum class KrxAnswerKind { STABLE_TRUE, STABLE_FALSE, PENDING_TRUE, PENDING_FALSE }

internal data class KrxContestState(
    val phase: KrxContestPhase,
    val barrier: Long?,
    val streak: Int,
    val lastStepAtMillis: Long?,
    val holdUntilMillis: Long?,
    val holdSeq: Long,
    val holdExpired: Boolean,
    val permitSeq: Long
) {
    companion object {
        val INITIAL: KrxContestState = KrxContestState(
            phase = KrxContestPhase.NONE,
            barrier = null,
            streak = 0,
            lastStepAtMillis = null,
            holdUntilMillis = null,
            holdSeq = 0L,
            holdExpired = false,
            permitSeq = 0L
        )
    }
}

internal sealed interface KrxContestEvent {
    data class KeyChanged(val openOnEntry: Boolean) : KrxContestEvent
    data class HideEvidence(val order: Long) : KrxContestEvent
    data class Answer(val startOrder: Long, val kind: KrxAnswerKind, val runtimeOpen: Boolean) : KrxContestEvent
    data class HoldTimerFired(val holdSeq: Long, val openOnExpiry: Boolean) : KrxContestEvent
    data object KrxVisiblePublished : KrxContestEvent
}

internal enum class KrxContestIgnore { INVALID_INPUT, STALE_ANSWER, STALE_HOLD_TIMER, RUNTIME_CLOSED, NO_TRANSITION }

internal sealed interface KrxContestStep {
    data class Applied(val state: KrxContestState) : KrxContestStep
    data class Ignored(val reason: KrxContestIgnore) : KrxContestStep
}

internal object KrxContestPolicy {
    const val QUIET_MILLIS: Long = 600_000L
    const val BASE_COOLDOWN_MILLIS: Long = 5_000L
    const val MAX_COOLDOWN_MILLIS: Long = 60_000L

    fun cooldownMillis(streak: Int): Long =
        if (streak <= 1) 0L
        else (BASE_COOLDOWN_MILLIS shl (streak - 2).coerceAtMost(4)).coerceAtMost(MAX_COOLDOWN_MILLIS)

    fun next(state: KrxContestState, event: KrxContestEvent, nowMillis: Long): KrxContestStep {
        if (nowMillis < 0L || !validState(state) || !validEvent(event)) {
            return KrxContestStep.Ignored(KrxContestIgnore.INVALID_INPUT)
        }

        val step = when (event) {
            is KrxContestEvent.KeyChanged -> {
                val reset = state.copy(
                    phase = KrxContestPhase.NONE,
                    barrier = null,
                    streak = 0,
                    holdUntilMillis = null,
                    holdExpired = false
                )
                if (event.openOnEntry) issue(reset) else KrxContestStep.Applied(reset)
            }
            is KrxContestEvent.HideEvidence ->
                KrxContestStep.Applied(enterAwaiting(state, event.order, nowMillis))
            is KrxContestEvent.Answer -> answer(state, event, nowMillis)
            is KrxContestEvent.HoldTimerFired -> holdTimerFired(state, event, nowMillis)
            KrxContestEvent.KrxVisiblePublished ->
                if (state.phase == KrxContestPhase.HOLD && state.holdExpired) issue(state)
                else KrxContestStep.Ignored(KrxContestIgnore.NO_TRANSITION)
        }

        return if (step is KrxContestStep.Applied && step.state == state) {
            KrxContestStep.Ignored(KrxContestIgnore.NO_TRANSITION)
        } else {
            step
        }
    }

    fun effectiveOpen(state: KrxContestState): Boolean = state.phase == KrxContestPhase.OPEN

    fun contested(state: KrxContestState, krxVisible: Boolean): Boolean =
        krxVisible && state.phase != KrxContestPhase.OPEN

    private fun validState(state: KrxContestState): Boolean =
        state.streak >= 0 && state.permitSeq >= 0L && state.holdSeq >= 0L &&
            nonNegativeOrNull(state.barrier) && nonNegativeOrNull(state.lastStepAtMillis) &&
            nonNegativeOrNull(state.holdUntilMillis) &&
            if (state.phase == KrxContestPhase.HOLD) {
                state.holdUntilMillis != null
            } else {
                state.holdUntilMillis == null && !state.holdExpired
            }

    private fun nonNegativeOrNull(value: Long?): Boolean = value == null || value >= 0L

    private fun validEvent(event: KrxContestEvent): Boolean = when (event) {
        is KrxContestEvent.HideEvidence -> event.order >= 0L
        is KrxContestEvent.Answer -> event.startOrder >= 0L
        is KrxContestEvent.HoldTimerFired -> event.holdSeq >= 0L
        is KrxContestEvent.KeyChanged, KrxContestEvent.KrxVisiblePublished -> true
    }

    private fun quietElapsed(state: KrxContestState, nowMillis: Long): Boolean =
        state.lastStepAtMillis?.let { nowMillis - it >= QUIET_MILLIS } == true

    private fun raisedBarrier(barrier: Long?, order: Long): Long =
        if (barrier == null) order else maxOf(barrier, order)

    private fun enterAwaiting(state: KrxContestState, order: Long, nowMillis: Long): KrxContestState {
        val resetStreak = (state.phase == KrxContestPhase.NONE || state.phase == KrxContestPhase.OPEN) &&
            quietElapsed(state, nowMillis)
        return state.copy(
            phase = KrxContestPhase.AWAITING,
            barrier = raisedBarrier(state.barrier, order),
            streak = if (resetStreak) 0 else state.streak,
            holdUntilMillis = null,
            holdExpired = false
        )
    }

    private fun issue(state: KrxContestState): KrxContestStep {
        if (state.permitSeq == Long.MAX_VALUE) {
            return KrxContestStep.Ignored(KrxContestIgnore.INVALID_INPUT)
        }
        return KrxContestStep.Applied(
            state.copy(
                phase = KrxContestPhase.OPEN,
                holdUntilMillis = null,
                holdExpired = false,
                permitSeq = state.permitSeq + 1L
            )
        )
    }

    private fun answer(
        state: KrxContestState,
        event: KrxContestEvent.Answer,
        nowMillis: Long
    ): KrxContestStep {
        if (state.barrier != null && event.startOrder <= state.barrier) {
            return KrxContestStep.Ignored(KrxContestIgnore.STALE_ANSWER)
        }
        return when (event.kind) {
            KrxAnswerKind.STABLE_FALSE -> KrxContestStep.Applied(
                state.copy(
                    phase = KrxContestPhase.NONE,
                    barrier = raisedBarrier(state.barrier, event.startOrder),
                    streak = 0,
                    holdUntilMillis = null,
                    holdExpired = false
                )
            )
            KrxAnswerKind.PENDING_FALSE ->
                KrxContestStep.Applied(enterAwaiting(state, event.startOrder, nowMillis))
            KrxAnswerKind.STABLE_TRUE ->
                if (!event.runtimeOpen) KrxContestStep.Ignored(KrxContestIgnore.RUNTIME_CLOSED)
                else stableTrue(state, nowMillis)
            KrxAnswerKind.PENDING_TRUE -> when {
                state.phase != KrxContestPhase.NONE -> KrxContestStep.Ignored(KrxContestIgnore.NO_TRANSITION)
                !event.runtimeOpen -> KrxContestStep.Ignored(KrxContestIgnore.RUNTIME_CLOSED)
                else -> issue(state)
            }
        }
    }

    private fun stableTrue(state: KrxContestState, nowMillis: Long): KrxContestStep = when (state.phase) {
        KrxContestPhase.NONE -> issue(state)
        KrxContestPhase.AWAITING -> stepAwaiting(state, nowMillis)
        KrxContestPhase.HOLD ->
            if (state.holdExpired) issue(state) else KrxContestStep.Ignored(KrxContestIgnore.NO_TRANSITION)
        KrxContestPhase.OPEN -> KrxContestStep.Ignored(KrxContestIgnore.NO_TRANSITION)
    }

    private fun stepAwaiting(state: KrxContestState, nowMillis: Long): KrxContestStep {
        val previousStreak = if (quietElapsed(state, nowMillis)) 0 else state.streak
        val streak = if (previousStreak == Int.MAX_VALUE) Int.MAX_VALUE else previousStreak + 1
        val cooldown = cooldownMillis(streak)
        if (cooldown == 0L) {
            return issue(state.copy(streak = streak, lastStepAtMillis = nowMillis))
        }
        if (state.holdSeq == Long.MAX_VALUE) {
            return KrxContestStep.Ignored(KrxContestIgnore.INVALID_INPUT)
        }
        val holdUntil = if (nowMillis > Long.MAX_VALUE - cooldown) Long.MAX_VALUE else nowMillis + cooldown
        return KrxContestStep.Applied(
            state.copy(
                phase = KrxContestPhase.HOLD,
                streak = streak,
                lastStepAtMillis = nowMillis,
                holdUntilMillis = holdUntil,
                holdSeq = state.holdSeq + 1L,
                holdExpired = false
            )
        )
    }

    private fun holdTimerFired(
        state: KrxContestState,
        event: KrxContestEvent.HoldTimerFired,
        nowMillis: Long
    ): KrxContestStep {
        val holdUntil = state.holdUntilMillis
        if (state.phase != KrxContestPhase.HOLD || event.holdSeq != state.holdSeq ||
            state.holdExpired || holdUntil == null || nowMillis < holdUntil
        ) {
            return KrxContestStep.Ignored(KrxContestIgnore.STALE_HOLD_TIMER)
        }
        return if (event.openOnExpiry) issue(state)
        else KrxContestStep.Applied(state.copy(holdExpired = true))
    }
}
