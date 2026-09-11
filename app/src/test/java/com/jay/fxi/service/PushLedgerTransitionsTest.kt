package com.jay.fxi.service

import com.jay.fxi.service.PushRegistrationState.MAY_EXIST
import com.jay.fxi.service.PushRegistrationState.OWED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PushLedgerTransitionsTest {

    private fun PushLedger.mayExist(uid: String, token: String) =
        PushLedgerTransitions.recordMayExist(this, uid, token)

    @Test
    fun aRecordTakesAFreshIdAndMovesTheCounterPastIt() {
        val first = PushLedger.EMPTY.mayExist("a", "t1")
        val second = first.ledger.mayExist("b", "t2")

        assertEquals(PushLedgerEntry(1, "a", "t1", MAY_EXIST), first.value)
        assertEquals(PushLedgerEntry(2, "b", "t2", MAY_EXIST), second.value)
        assertEquals(3, second.ledger.nextId)
        assertEquals(listOf(first.value, second.value), second.ledger.entries)
    }

    @Test
    fun recordingAKeyAgainIssuesANewIdSoTheOlderCompletionMisses() {
        val older = PushLedger.EMPTY.mayExist("a", "t1")
        val newer = older.ledger.mayExist("a", "t1")

        val completed = PushLedgerTransitions.complete(newer.ledger, checkNotNull(older.value))

        assertEquals(2L, newer.value?.id)
        assertFalse("늦은 완료가 다시 기록된 등록을 지웠다", completed.value)
        assertEquals(listOf(newer.value), completed.ledger.entries)
    }

    @Test
    fun anOwedKeyRefusesANewRecordUntilItsDeleteCompletes() {
        val owed = PushLedgerTransitions.recordOwed(PushLedger.EMPTY, "a", "t1")

        val refused = owed.ledger.mayExist("a", "t1")

        assertNull(refused.value)
        assertEquals(owed.ledger, refused.ledger)
    }

    @Test
    fun markingOwedTouchesOnlyThatUidAndKeepsIds() {
        val ledger = PushLedger.EMPTY.mayExist("a", "t1").ledger
            .mayExist("b", "t2").ledger
            .mayExist("a", "t3").ledger

        val marked = PushLedgerTransitions.markOwed(ledger, "a")

        assertEquals(
            listOf(
                PushLedgerEntry(1, "a", "t1", OWED),
                PushLedgerEntry(2, "b", "t2", MAY_EXIST),
                PushLedgerEntry(3, "a", "t3", OWED)
            ),
            marked.ledger.entries
        )
        assertEquals(listOf(1L, 3L), marked.value.map { it.id })
        assertEquals(ledger.nextId, marked.ledger.nextId)
    }

    @Test
    fun recordingOwedCreatesAnAbsentKeyAndKeepsAnExistingId() {
        val existing = PushLedger.EMPTY.mayExist("a", "t1")

        val kept = PushLedgerTransitions.recordOwed(existing.ledger, "a", "t1")
        val created = PushLedgerTransitions.recordOwed(kept.ledger, "a", "t2")
        val again = PushLedgerTransitions.recordOwed(created.ledger, "a", "t2")

        assertEquals(PushLedgerEntry(1, "a", "t1", OWED), kept.value)
        assertEquals(2L, kept.ledger.nextId)
        assertEquals(PushLedgerEntry(2, "a", "t2", OWED), created.value)
        assertEquals(created.ledger, again.ledger)
        assertEquals(created.value, again.value)
    }

    @Test
    fun anOlderKeyMarkedOwedLeavesTheCounterWhereItWas() {
        val ledger = PushLedger.EMPTY.mayExist("a", "t1").ledger.mayExist("b", "t2").ledger

        val owed = PushLedgerTransitions.recordOwed(ledger, "a", "t1")

        assertEquals(1L, owed.value.id)
        assertEquals("카운터가 뒤로 갔다", 3L, owed.ledger.nextId)
    }

    @Test
    fun completingRemovesOnlyTheEntryWithTheSameId() {
        val recorded = PushLedger.EMPTY.mayExist("a", "t1")
        val entry = checkNotNull(recorded.value)
        val owed = PushLedgerTransitions.markOwed(recorded.ledger, "a")

        // Marking keeps the id, so a DELETE begun before the mark still completes the entry.
        val completed = PushLedgerTransitions.complete(owed.ledger, entry)
        val absent = PushLedgerTransitions.complete(completed.ledger, entry)

        assertTrue(completed.value)
        assertEquals(emptyList<PushLedgerEntry>(), completed.ledger.entries)
        assertFalse(absent.value)
    }

    @Test
    fun idsAreNeverReusedOnceTheLedgerEmpties() {
        val recorded = PushLedger.EMPTY.mayExist("a", "t1")
        val emptied = PushLedgerTransitions.complete(recorded.ledger, checkNotNull(recorded.value)).ledger

        val next = emptied.mayExist("a", "t1")

        assertEquals(2L, emptied.nextId)
        assertEquals(2L, next.value?.id)
    }

    @Test
    fun differentKeysNeverOverwriteEachOther() {
        val ledger = PushLedger.EMPTY.mayExist("a", "t1").ledger
            .let { PushLedgerTransitions.recordOwed(it, "a", "t2").ledger }
            .mayExist("b", "t1").ledger

        assertEquals(
            listOf("a" to "t1", "a" to "t2", "b" to "t1"),
            ledger.entries.map { it.uid to it.token }
        )
    }

    @Test
    fun aLedgerTheTransitionsCannotProduceIsRejected() {
        val a = PushLedgerEntry(1, "a", "t1", MAY_EXIST)
        listOf(
            { PushLedger(listOf(a), 1) },                                   // id not below the counter
            { PushLedger(listOf(a, a.copy(token = "t2")), 3) },             // duplicate id
            { PushLedger(listOf(a, a.copy(id = 2)), 3) },                   // duplicate key
            { PushLedger(listOf(a.copy(id = 2), a.copy(token = "t2")), 3) }, // out of id order
            { PushLedger(emptyList(), 0) }                                  // counter below one
        ).forEach { build -> assertThrows(IllegalArgumentException::class.java) { build() } }
    }

    @Test
    fun anEntryDoesNotPrintItsUidOrToken() {
        val printed = PushLedgerEntry(7, "uid-secret", "token-secret", OWED).toString()

        assertFalse(printed.contains("uid-secret"))
        assertFalse(printed.contains("token-secret"))
    }
}
