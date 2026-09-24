package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, ninth file: the shared `ControlLifecycleReceipt.journal` observation (LC:277-285).
 *
 * The receipt maps every journal entry the descriptor names to Present / Absent / Uninterpretable against the
 * canonical journal in storage. Before C9 no test asserted that mapping on this receipt type: the suite's
 * `JournalObservation` assertions are on `HandoverSettlementReceipt` and `SettlementReceipt`, and C7 asserts only
 * `requiredUnchanged`. A mutant that forced every entry to Uninterpretable (`R6B.LC.282.17.IF.e18191.fixed`) was
 * NOT_CAUGHT with C7 and C8 installed.
 *
 * Each test names a non-empty entry list and compares against a Map built in the test. The canonical journal is
 * written as a literal; no production codec, `canonicalJournal` or `receipt` result produces an expected value
 * (design §9.1 539). These are observation contracts, so the role is `retry contract violated` (§9.1 543, C7 precedent).
 */
class DemandAuthBacklogContract9Test {

    private val entryA = PendingPurge("A", "u", null, setOf(PurgeScope.USER))
    private val entryB = PendingPurge("B", "v", null, setOf(PurgeScope.USER))

    private fun descriptor(journal: List<PendingPurge>) = ControlLifecycleDescriptor("op-journal",
        LifecycleTransition.RECOVER_HOLD, emptyList(), namespace = LifecycleNamespacePostcondition(F.fence, F.fence, journal))

    private fun storedWith(journalLiteral: String) =
        F.read(F.raw().toMutablePreferences().apply { this[PURGE_JOURNAL] = journalLiteral }.toPreferences())

    private fun observed(journal: List<PendingPurge>, literal: String): Map<PendingPurge, JournalObservation> {
        val read = storedWith(literal)
        assertEquals("fixture: the stored journal is the literal", literal, read.original[PURGE_JOURNAL])
        val d = descriptor(journal)
        assertEquals("fixture: the descriptor names the entries", journal, checkNotNull(d.namespace).journal)
        return ControlLifecycleConfirmation(F.codec).receipt(d, read).journal
    }

    // RC.journal.present — an entry that is in the canonical journal is Present.

    @Test fun RC_journal_present() {
        assertEquals(F.retry("RC.journal.present"),
            mapOf(entryA to JournalObservation.Present), observed(listOf(entryA), "A|u||USER"))
    }

    // RC.journal.absent — an entry the descriptor names but the canonical journal lacks is Absent.

    @Test fun RC_journal_absent() {
        assertEquals(F.retry("RC.journal.absent"),
            mapOf(entryB to JournalObservation.Absent), observed(listOf(entryB), "A|u||USER"))
    }

    // RC.journal.uninterpretable — when the stored journal is not canonical, every named entry is Uninterpretable.
    // "A|u||" has an empty scope name, the same non-canonical line NamespaceSettlementR3BoundaryTest:78 uses.

    @Test fun RC_journal_uninterpretable() {
        assertEquals(F.retry("RC.journal.uninterpretable"),
            mapOf(entryA to JournalObservation.Uninterpretable, entryB to JournalObservation.Uninterpretable),
            observed(listOf(entryA, entryB), "A|u||"))
    }
}
