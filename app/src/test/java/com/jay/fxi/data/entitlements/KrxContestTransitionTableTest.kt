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
import org.junit.Test

/**
 * S6-2b0a CAP-P02 (`R4c/S6/s6_2b0a/s6_2b0a_contracts.r2.md` §4.2): five base states × 25 events = 125 cells. Each cell is a
 * literal written from §3.3 by hand — an `Ignored` reason, or all eight fields of the next state. Nothing here calls the policy
 * to build an expected value.
 */
class KrxContestTransitionTableTest {

    private companion object {
        const val NOW = 10_000L
        const val EARLY = 8_999L

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

        /** §4.0. */
        val BASES: List<Pair<String, KrxContestState>> = listOf(
            "NONE" to st(NONE, 10, 2, 1_000, null, 3, false, 4),
            "AWAITING" to st(AWAITING, 10, 2, 1_000, null, 3, false, 4),
            "HOLD" to st(HOLD, 10, 2, 1_000, 9_000, 3, false, 4),
            "HOLD_EXPIRED" to st(HOLD, 10, 2, 1_000, 9_000, 3, true, 4),
            "OPEN" to st(OPEN, 10, 2, 1_000, null, 3, false, 4)
        )

        /** (name, event, now). §4.2 columns in order. */
        val EVENTS: List<Triple<String, KrxContestEvent, Long>> = buildList {
            add(Triple("KC_OPEN", KrxContestEvent.KeyChanged(true), NOW))
            add(Triple("KC_SHUT", KrxContestEvent.KeyChanged(false), NOW))
            add(Triple("HIDE_UP", KrxContestEvent.HideEvidence(15), NOW))
            add(Triple("HIDE_DOWN", KrxContestEvent.HideEvidence(5), NOW))
            for (kind in listOf(STABLE_TRUE, STABLE_FALSE, PENDING_TRUE, PENDING_FALSE)) {
                for ((tag, q) in listOf("FRESH" to 11L, "S1" to 10L)) {
                    for ((rt, open) in listOf("RT" to true, "SHUT" to false)) {
                        add(Triple("${kind}_${tag}_$rt", KrxContestEvent.Answer(q, kind, open), NOW))
                    }
                }
            }
            add(Triple("TIMER_OPEN", KrxContestEvent.HoldTimerFired(3, true), NOW))
            add(Triple("TIMER_SHUT", KrxContestEvent.HoldTimerFired(3, false), NOW))
            add(Triple("TIMER_OTHER", KrxContestEvent.HoldTimerFired(4, true), NOW))
            add(Triple("TIMER_EARLY", KrxContestEvent.HoldTimerFired(3, true), EARLY))
            add(Triple("PUBLISHED", KrxContestEvent.KrxVisiblePublished, NOW))
        }

        fun ok(s: KrxContestState): KrxContestStep = KrxContestStep.Applied(s)
        val NT: KrxContestStep = KrxContestStep.Ignored(KrxContestIgnore.NO_TRANSITION)
        val STALE: KrxContestStep = KrxContestStep.Ignored(KrxContestIgnore.STALE_ANSWER)
        val RC: KrxContestStep = KrxContestStep.Ignored(KrxContestIgnore.RUNTIME_CLOSED)
        val TS: KrxContestStep = KrxContestStep.Ignored(KrxContestIgnore.STALE_HOLD_TIMER)

        // Shared next states.
        val C0_OPEN = ok(st(OPEN, null, 0, 1_000, null, 3, false, 5))
        val C0_SHUT = ok(st(NONE, null, 0, 1_000, null, 3, false, 4))
        val AW15 = ok(st(AWAITING, 15, 2, 1_000, null, 3, false, 4))
        val AW10 = ok(st(AWAITING, 10, 2, 1_000, null, 3, false, 4))
        val AW11 = ok(st(AWAITING, 11, 2, 1_000, null, 3, false, 4))
        val C1 = ok(st(NONE, 11, 0, 1_000, null, 3, false, 4))
        val ISSUE = ok(st(OPEN, 10, 2, 1_000, null, 3, false, 5))
        val T2_HOLD = ok(st(HOLD, 10, 3, NOW, NOW + 10_000, 4, false, 4))
        val X2 = ok(st(HOLD, 10, 2, 1_000, 9_000, 3, true, 4))

        /** Rows follow BASES, columns follow EVENTS. */
        val EXPECTED: Map<String, List<KrxContestStep>> = mapOf(
            "NONE" to listOf(
                C0_OPEN, C0_SHUT, AW15, AW10,
                ISSUE, RC, STALE, STALE,          // STABLE_TRUE
                C1, C1, STALE, STALE,             // STABLE_FALSE
                ISSUE, RC, STALE, STALE,          // PENDING_TRUE
                AW11, AW11, STALE, STALE,         // PENDING_FALSE
                TS, TS, TS, TS, NT
            ),
            "AWAITING" to listOf(
                C0_OPEN, C0_SHUT, AW15, NT,
                T2_HOLD, RC, STALE, STALE,
                C1, C1, STALE, STALE,
                NT, NT, STALE, STALE,
                AW11, AW11, STALE, STALE,
                TS, TS, TS, TS, NT
            ),
            "HOLD" to listOf(
                C0_OPEN, C0_SHUT, AW15, AW10,
                NT, RC, STALE, STALE,
                C1, C1, STALE, STALE,
                NT, NT, STALE, STALE,
                AW11, AW11, STALE, STALE,
                ISSUE, X2, TS, TS, NT
            ),
            "HOLD_EXPIRED" to listOf(
                C0_OPEN, C0_SHUT, AW15, AW10,
                ISSUE, RC, STALE, STALE,
                C1, C1, STALE, STALE,
                NT, NT, STALE, STALE,
                AW11, AW11, STALE, STALE,
                TS, TS, TS, TS, ISSUE
            ),
            "OPEN" to listOf(
                C0_OPEN, C0_SHUT, AW15, AW10,
                NT, RC, STALE, STALE,
                C1, C1, STALE, STALE,
                NT, NT, STALE, STALE,
                AW11, AW11, STALE, STALE,
                TS, TS, TS, TS, NT
            )
        )
    }

    /** The table is complete: 125 cells, every sealed event type and every answer kind present. */
    @Test fun PT00_tableCoversEveryStateAndEvent() {
        assertEquals(25, EVENTS.size)
        assertEquals(BASES.map { it.first }.toSet(), EXPECTED.keys)
        assertEquals(125, EXPECTED.values.sumOf { it.size })
        for ((name, row) in EXPECTED) assertEquals("row $name", EVENTS.size, row.size)
        assertEquals(5, EVENTS.map { it.second::class }.toSet().size)
        assertEquals(KrxAnswerKind.values().toSet(),
            EVENTS.mapNotNull { (it.second as? KrxContestEvent.Answer)?.kind }.toSet())
    }

    /** Every cell, in one pass, with the cell named in the message. */
    @Test fun PT01_everyCell() {
        val mismatches = mutableListOf<String>()
        for ((name, base) in BASES) {
            val row = EXPECTED.getValue(name)
            EVENTS.forEachIndexed { i, (eventName, event, now) ->
                val actual = KrxContestPolicy.next(base, event, now)
                if (actual != row[i]) mismatches += "$name × $eventName: expected ${row[i]}, was $actual"
            }
        }
        assertEquals(mismatches.joinToString("\n"), 0, mismatches.size)
    }
}
