package com.jay.fxi.data.entitlements

import com.jay.fxi.data.entitlements.KrxAnswerKind.PENDING_FALSE
import com.jay.fxi.data.entitlements.KrxAnswerKind.PENDING_TRUE
import com.jay.fxi.data.entitlements.KrxAnswerKind.STABLE_FALSE
import com.jay.fxi.data.entitlements.KrxAnswerKind.STABLE_TRUE
import com.jay.fxi.data.entitlements.KrxContestPhase.AWAITING
import com.jay.fxi.data.entitlements.KrxContestPhase.HOLD
import com.jay.fxi.data.entitlements.KrxContestPhase.NONE
import com.jay.fxi.data.entitlements.KrxContestPhase.OPEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6-2b0a pure KRX contest policy (`R4c/S6/s6_2b0a/s6_2b0a_contracts.r2.md` §4.1, PC-00..PC-19). Every expectation is written
 * from the design's §3 by hand; nothing here calls the policy to build an expected value.
 */
class KrxContestPolicyTest {

    private companion object {
        const val NOW = 10_000L
        const val M = Long.MAX_VALUE

        fun st(
            phase: KrxContestPhase,
            barrier: Long?,
            streak: Int,
            last: Long?,
            holdUntil: Long?,
            holdSeq: Long,
            expired: Boolean,
            permit: Long
        ) = KrxContestState(phase, barrier, streak, last, holdUntil, holdSeq, expired, permit)

        /** §4.0 base states. */
        val N_BASE = st(NONE, 10, 2, 1_000, null, 3, false, 4)
        val A_BASE = st(AWAITING, 10, 2, 1_000, null, 3, false, 4)
        val H_BASE = st(HOLD, 10, 2, 1_000, 9_000, 3, false, 4)
        val HX_BASE = st(HOLD, 10, 2, 1_000, 9_000, 3, true, 4)
        val O_BASE = st(OPEN, 10, 2, 1_000, null, 3, false, 4)

        fun next(s: KrxContestState, e: KrxContestEvent, now: Long = NOW) = KrxContestPolicy.next(s, e, now)
        fun ans(q: Long, kind: KrxAnswerKind, runtime: Boolean = true) = KrxContestEvent.Answer(q, kind, runtime)
        fun applied(s: KrxContestState) = KrxContestStep.Applied(s)
        fun ignored(r: KrxContestIgnore) = KrxContestStep.Ignored(r)

        fun stateOf(step: KrxContestStep): KrxContestState {
            assertTrue("expected Applied, was $step", step is KrxContestStep.Applied)
            return (step as KrxContestStep.Applied).state
        }
    }

    /** PC-00: INITIAL's eight fields, and the two predicates on it. Catches a different INITIAL. */
    @Test fun PC00_initialState() {
        val i = KrxContestState.INITIAL
        assertEquals(NONE, i.phase)
        assertEquals(null, i.barrier)
        assertEquals(0, i.streak)
        assertEquals(null, i.lastStepAtMillis)
        assertEquals(null, i.holdUntilMillis)
        assertEquals(0L, i.holdSeq)
        assertEquals(false, i.holdExpired)
        assertEquals(0L, i.permitSeq)
        assertFalse(KrxContestPolicy.effectiveOpen(i))
        assertFalse(KrxContestPolicy.contested(i, krxVisible = false))
        assertTrue(KrxContestPolicy.contested(i, krxVisible = true))
    }

    /** PC-01 (CAP-P01): the cooldown table. Catches d(1) ≠ 0, a missing cap, a shift offset and overflow. */
    @Test fun PC01_cooldownTable() {
        val inputs = listOf(-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, Int.MAX_VALUE)
        val expected = listOf(0L, 0L, 0L, 5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L, 60_000L)
        assertEquals(expected, inputs.map { KrxContestPolicy.cooldownMillis(it) })
    }

    /** PC-02: the three constants. */
    @Test fun PC02_constants() {
        assertEquals(600_000L, KrxContestPolicy.QUIET_MILLIS)
        assertEquals(5_000L, KrxContestPolicy.BASE_COOLDOWN_MILLIS)
        assertEquals(60_000L, KrxContestPolicy.MAX_COOLDOWN_MILLIS)
    }

    /** PC-03 (CAP-P05): one AWAITING ends with one permit; true→true is not a trigger. */
    @Test fun PC03_awaitingTrueThriceIssuesOnce() {
        val s0 = st(AWAITING, 1, 0, null, null, 0, false, 0)
        val s1 = stateOf(next(s0, ans(2, STABLE_TRUE), now = 100))
        assertEquals(st(OPEN, 1, 1, 100, null, 0, false, 1), s1)
        assertEquals(ignored(KrxContestIgnore.NO_TRANSITION), next(s1, ans(3, STABLE_TRUE), now = 200))
        assertEquals(ignored(KrxContestIgnore.NO_TRANSITION), next(s1, ans(4, STABLE_TRUE), now = 300))
    }

    /** PC-04 (CAP-P05): no permit in an unexpired HOLD; its expiry issues exactly one. */
    @Test fun PC04_holdTrueThriceThenExpiryIssuesExactlyOne() {
        for (q in listOf(11L, 12L, 13L)) {
            assertEquals("q=$q", ignored(KrxContestIgnore.NO_TRANSITION), next(H_BASE, ans(q, STABLE_TRUE)))
        }
        val opened = stateOf(next(H_BASE, KrxContestEvent.HoldTimerFired(3, openOnExpiry = true), now = 9_000))
        assertEquals(st(OPEN, 10, 2, 1_000, null, 3, false, 5), opened)
        assertEquals(ignored(KrxContestIgnore.STALE_HOLD_TIMER),
            next(opened, KrxContestEvent.HoldTimerFired(3, openOnExpiry = true), now = 9_000))
    }

    /** PC-05 (CAP-P06): Pending(true) issues only in NONE. */
    @Test fun PC05_pendingTrueOnlyInNone() {
        assertEquals(applied(st(OPEN, 10, 2, 1_000, null, 3, false, 5)), next(N_BASE, ans(11, PENDING_TRUE)))
        for (s in listOf(A_BASE, H_BASE, O_BASE)) {
            assertEquals("${s.phase}", ignored(KrxContestIgnore.NO_TRANSITION), next(s, ans(11, PENDING_TRUE)))
        }
    }

    /** PC-06 (CAP-P07): HOLD → Pending(false) → stable true continues the contest with a cooldown, not at once. */
    @Test fun PC06_holdPendingFalseThenTrueAppliesCooldown() {
        val awaiting = stateOf(next(H_BASE, ans(11, PENDING_FALSE)))
        assertEquals(st(AWAITING, 11, 2, 1_000, null, 3, false, 4), awaiting)
        val held = stateOf(next(awaiting, ans(12, STABLE_TRUE)))
        assertEquals(st(HOLD, 11, 3, NOW, NOW + 10_000, 4, false, 4), held)
    }

    /** PC-07 (CAP-P03): the T2 QUIET gap, `≥` and negative elapsed. */
    @Test fun PC07_t2QuietBoundary() {
        val now = 1_000_000L
        val kept = stateOf(next(st(AWAITING, 10, 2, 400_001, null, 3, false, 4), ans(11, STABLE_TRUE), now))
        assertEquals(st(HOLD, 10, 3, now, now + 10_000, 4, false, 4), kept)
        val reset = stateOf(next(st(AWAITING, 10, 2, 400_000, null, 3, false, 4), ans(11, STABLE_TRUE), now))
        assertEquals(st(OPEN, 10, 1, now, null, 3, false, 5), reset)
        val negative = stateOf(next(st(AWAITING, 10, 2, 1_000_001, null, 3, false, 4), ans(11, STABLE_TRUE), now))
        assertEquals(st(HOLD, 10, 3, now, now + 10_000, 4, false, 4), negative)
    }

    /** PC-08 (CAP-P03): enterAwaiting resets on QUIET only when entered from NONE or OPEN. */
    @Test fun PC08_enterAwaitingQuietBoundary() {
        val now = 1_000_000L
        for (phase in listOf(NONE, OPEN)) {
            for ((last, streak) in listOf(400_001L to 2, 400_000L to 0, 1_000_001L to 2)) {
                val s = st(phase, 10, 2, last, null, 3, false, 4)
                assertEquals("$phase last=$last", applied(st(AWAITING, 15, streak, last, null, 3, false, 4)),
                    next(s, KrxContestEvent.HideEvidence(15), now))
            }
        }
        assertEquals(applied(st(AWAITING, 15, 2, 400_000, null, 3, false, 4)),
            next(st(AWAITING, 10, 2, 400_000, null, 3, false, 4), KrxContestEvent.HideEvidence(15), now))
        assertEquals(applied(st(AWAITING, 15, 2, 400_000, null, 3, false, 4)),
            next(st(HOLD, 10, 2, 400_000, 1_100_000, 3, false, 4), KrxContestEvent.HideEvidence(15), now))
    }

    /** PC-09 (I6): the timer callback is rechecked. Catches a missing recheck and `<`↔`≤`. */
    @Test fun PC09_holdTimerRecheck() {
        val stale = ignored(KrxContestIgnore.STALE_HOLD_TIMER)
        assertEquals("other seq", stale, next(H_BASE, KrxContestEvent.HoldTimerFired(4, openOnExpiry = true)))
        assertEquals("before deadline", stale, next(H_BASE, KrxContestEvent.HoldTimerFired(3, openOnExpiry = true), now = 8_999))
        assertEquals("already expired", stale, next(HX_BASE, KrxContestEvent.HoldTimerFired(3, openOnExpiry = true)))
        assertEquals("not HOLD", stale, next(A_BASE, KrxContestEvent.HoldTimerFired(3, openOnExpiry = true)))
    }

    /** PC-10: X2 and its resumption by a published `_krx` or a fresh stable true. */
    @Test fun PC10_x2AndResume() {
        val expired = stateOf(next(H_BASE, KrxContestEvent.HoldTimerFired(3, openOnExpiry = false)))
        assertEquals(st(HOLD, 10, 2, 1_000, 9_000, 3, true, 4), expired)
        assertEquals(applied(st(OPEN, 10, 2, 1_000, null, 3, false, 5)), next(expired, KrxContestEvent.KrxVisiblePublished))
        assertEquals(applied(st(OPEN, 10, 2, 1_000, null, 3, false, 5)), next(expired, ans(11, STABLE_TRUE)))
    }

    /** PC-11: a fresh StableActive(false) is C1 from every non-NONE phase. */
    @Test fun PC11_stableFalseIsC1() {
        for (s in listOf(A_BASE, H_BASE, HX_BASE, O_BASE)) {
            assertEquals("${s.phase} ${s.holdExpired}", applied(st(NONE, 11, 0, 1_000, null, 3, false, 4)), next(s, ans(11, STABLE_FALSE)))
        }
    }

    /** PC-12: a fresh Pending(false) is H2: AWAITING, the streak kept. Catches treating it as C1. */
    @Test fun PC12_pendingFalseIsH2() {
        for (s in listOf(N_BASE, O_BASE)) {
            assertEquals("${s.phase}", applied(st(AWAITING, 11, 2, 1_000, null, 3, false, 4)), next(s, ans(11, PENDING_FALSE)))
        }
    }

    /** PC-13: S1 for every kind at q = barrier and below; with no barrier, q = 0 is fresh. */
    @Test fun PC13_staleAnswers() {
        for (kind in KrxAnswerKind.values()) {
            for (q in listOf(10L, 9L)) {
                assertEquals("$kind q=$q", ignored(KrxContestIgnore.STALE_ANSWER), next(A_BASE, ans(q, kind)))
            }
        }
        assertEquals(applied(st(NONE, 0, 0, null, null, 0, false, 0)), next(KrxContestState.INITIAL, ans(0, STABLE_FALSE)))
    }

    /** PC-14: a closed runtime gives no permit and no step. */
    @Test fun PC14_runtimeClosed() {
        val closed = ignored(KrxContestIgnore.RUNTIME_CLOSED)
        assertEquals(closed, next(N_BASE, ans(11, STABLE_TRUE, runtime = false)))
        assertEquals(closed, next(N_BASE, ans(11, PENDING_TRUE, runtime = false)))
        assertEquals(closed, next(A_BASE, ans(11, STABLE_TRUE, runtime = false)))
    }

    /** PC-15: C0 from every base, open and closed. lastStepAt and holdSeq are kept (O-1). */
    @Test fun PC15_keyChanged() {
        for (s in listOf(N_BASE, A_BASE, H_BASE, HX_BASE, O_BASE)) {
            assertEquals("${s.phase} ${s.holdExpired} open", applied(st(OPEN, null, 0, 1_000, null, 3, false, 5)),
                next(s, KrxContestEvent.KeyChanged(openOnEntry = true)))
            assertEquals("${s.phase} ${s.holdExpired} closed", applied(st(NONE, null, 0, 1_000, null, 3, false, 4)),
                next(s, KrxContestEvent.KeyChanged(openOnEntry = false)))
        }
    }

    /** PC-16: every input-validation item of §3.2. */
    @Test fun PC16_invalidInput() {
        val invalid = ignored(KrxContestIgnore.INVALID_INPUT)
        val e = ans(11, STABLE_TRUE)
        assertEquals("now < 0", invalid, next(A_BASE, e, now = -1))
        assertEquals("streak < 0", invalid, next(A_BASE.copy(streak = -1), e))
        assertEquals("permitSeq < 0", invalid, next(A_BASE.copy(permitSeq = -1), e))
        assertEquals("holdSeq < 0", invalid, next(A_BASE.copy(holdSeq = -1), e))
        assertEquals("barrier < 0", invalid, next(A_BASE.copy(barrier = -1), e))
        assertEquals("lastStepAt < 0", invalid, next(A_BASE.copy(lastStepAtMillis = -1), e))
        assertEquals("holdUntil < 0", invalid, next(H_BASE.copy(holdUntilMillis = -1), KrxContestEvent.HoldTimerFired(3, true), now = 0))
        assertEquals("HOLD without holdUntil", invalid, next(H_BASE.copy(holdUntilMillis = null), e))
        assertEquals("non-HOLD with holdUntil", invalid, next(A_BASE.copy(holdUntilMillis = 5), e))
        assertEquals("non-HOLD expired", invalid, next(A_BASE.copy(holdExpired = true), e))
        assertEquals("order < 0", invalid, next(A_BASE, KrxContestEvent.HideEvidence(-1)))
        assertEquals("startOrder < 0", invalid, next(KrxContestState.INITIAL, ans(-1, STABLE_FALSE)))
        assertEquals("timer seq < 0", invalid, next(H_BASE, KrxContestEvent.HoldTimerFired(-1, true)))
    }

    /** PC-17: saturation and sequence exhaustion (design r2 §3.2). */
    @Test fun PC17_saturationAndExhaustion() {
        val maxStreak = stateOf(next(st(AWAITING, 10, Int.MAX_VALUE, 1_000, null, 3, false, 4), ans(11, STABLE_TRUE)))
        assertEquals(st(HOLD, 10, Int.MAX_VALUE, NOW, NOW + 60_000, 4, false, 4), maxStreak)

        val late = M - 1
        val farHold = stateOf(next(st(AWAITING, 10, 2, M - 2, null, 3, false, 4), ans(11, STABLE_TRUE), now = late))
        assertEquals(st(HOLD, 10, 3, late, M, 4, false, 4), farHold)

        val atMax = stateOf(next(st(NONE, 10, 0, null, null, 0, false, M - 1), ans(11, STABLE_TRUE)))
        assertEquals(st(OPEN, 10, 0, null, null, 0, false, M), atMax)
        val closed = stateOf(next(atMax, ans(12, STABLE_FALSE)))
        assertEquals(st(NONE, 12, 0, null, null, 0, false, M), closed)
        assertEquals(ignored(KrxContestIgnore.INVALID_INPUT), next(closed, ans(13, STABLE_TRUE)))

        val lastHold = stateOf(next(st(AWAITING, 10, 2, 1_000, null, M - 1, false, 4), ans(11, STABLE_TRUE)))
        assertEquals(st(HOLD, 10, 3, NOW, NOW + 10_000, M, false, 4), lastHold)
        assertEquals(applied(st(OPEN, 10, 3, NOW, null, M, false, 5)),
            next(lastHold, KrxContestEvent.HoldTimerFired(M, openOnExpiry = true), now = NOW + 10_000))
        assertEquals(ignored(KrxContestIgnore.INVALID_INPUT), next(st(AWAITING, 10, 2, 1_000, null, M, false, 4), ans(11, STABLE_TRUE)))
    }

    /** PC-18: the two predicates over every phase and both krxVisible values. */
    @Test fun PC18_predicates() {
        for (s in listOf(N_BASE, A_BASE, H_BASE, O_BASE)) {
            assertEquals("${s.phase} effective", s.phase == OPEN, KrxContestPolicy.effectiveOpen(s))
            assertFalse("${s.phase} contested when hidden", KrxContestPolicy.contested(s, krxVisible = false))
            assertEquals("${s.phase} contested when visible", s.phase != OPEN, KrxContestPolicy.contested(s, krxVisible = true))
        }
    }

    /**
     * PC-19 (I2, the policy half of CAP-K02): seven rounds of the persistent mismatch at T = 100 ms, r0 = 0 (design r4 §4.2).
     * Permits at 0.1, 5.3, 15.5, 35.7, 75.9, 136.1, 196.3 s; holds 0, 5, 10, 20, 40, 60, 60 s; at most four permits in any 60 s.
     */
    @Test fun PC19_persistentMismatchSevenRounds() {
        val t = 100L
        var s = st(OPEN, null, 0, null, null, 0, false, 1)
        var order = 0L
        var r = 0L
        val permits = mutableListOf<Long>()
        val holds = mutableListOf<Long>()
        repeat(7) {
            s = stateOf(next(s, KrxContestEvent.HideEvidence(++order), now = r))
            val answerAt = r + t
            s = stateOf(next(s, ans(++order, STABLE_TRUE), now = answerAt))
            val permitAt = if (s.phase == HOLD) {
                val until = s.holdUntilMillis!!
                holds += until - answerAt
                s = stateOf(next(s, KrxContestEvent.HoldTimerFired(s.holdSeq, openOnExpiry = true), now = until))
                until
            } else {
                holds += 0L
                answerAt
            }
            assertEquals(OPEN, s.phase)
            permits += permitAt
            r = permitAt + t
        }
        assertEquals(listOf(100L, 5_300L, 15_500L, 35_700L, 75_900L, 136_100L, 196_300L), permits)
        assertEquals(listOf(0L, 5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), holds)
        assertEquals(8L, s.permitSeq)
        val maxIn60s = permits.maxOf { start -> permits.count { it >= start && it < start + 60_000 } }
        assertEquals(4, maxIn60s)
    }
}
