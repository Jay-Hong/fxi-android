package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.graph.GraphProtectedAdmission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 RT01-A2 (RT01 design r2 P5, API agreed in rt01a2_api_codex.r1): the process-owned deletion admission store. A deletion
 * blocks its UID from the moment the server request is about to leave, whatever auth generation asks, and nothing but a
 * send proven not to have left takes a requesting record away; a server-confirmed deletion stays. Wiring the account
 * deletion stage and the graph to it is the critical cutover's.
 */
class DeletionAdmissionStoreTest {

    private val a1 = AuthIdentityFence("user-a", 1L)
    private val a2 = AuthIdentityFence("user-a", 2L)
    private val b1 = AuthIdentityFence("user-b", 1L)

    @Test
    fun d01_aRequestingDeletionBlocksItsUidOnly_atEveryGeneration() {
        val store = DeletionAdmissionStore()
        assertFalse("nothing blocks before a deletion", store.blocks("user-a"))
        store.begin(a1, "op-a")
        assertTrue(store.blocks("user-a"))
        assertTrue("a new auth generation of the same user is still blocked", store.blocks(a2.uid))
        assertFalse("another user is not", store.blocks("user-b"))
        assertFalse("no identity is not a blocked user", store.blocks(null))
        assertEquals(
            listOf(DeletionAdmissionRecord(a1, "op-a", DeletionAdmissionPhase.REQUESTING_SERVER)),
            store.records
        )
    }

    @Test
    fun d02_aServerDeletionAdvancesOnlyItsOwnOperation_andStays() {
        val store = DeletionAdmissionStore()
        store.begin(a1, "op-a")
        store.begin(b1, "op-b")
        store.serverDeleted("op-a")
        assertEquals(
            setOf(
                DeletionAdmissionRecord(a1, "op-a", DeletionAdmissionPhase.SERVER_DELETED),
                DeletionAdmissionRecord(b1, "op-b", DeletionAdmissionPhase.REQUESTING_SERVER)
            ),
            store.records.toSet()
        )
        store.releaseUnsent("op-a")
        assertTrue("a server-confirmed deletion is never released", store.blocks("user-a"))
        assertEquals(DeletionAdmissionPhase.SERVER_DELETED, store.records.single { it.operationId == "op-a" }.phase)
    }

    @Test
    fun d03_onlyAProvenUnsentRequestIsReleased_andOnlyItsOwn() {
        val store = DeletionAdmissionStore()
        store.begin(a1, "op-a1")
        store.begin(a2, "op-a2")
        store.releaseUnsent("op-a1")
        assertTrue("the user's other requesting record still blocks", store.blocks("user-a"))
        assertEquals(listOf(DeletionAdmissionRecord(a2, "op-a2", DeletionAdmissionPhase.REQUESTING_SERVER)), store.records)
        store.releaseUnsent("op-a2")
        assertFalse(store.blocks("user-a"))
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun d04_unknownOperationsChangeNothing() {
        val store = DeletionAdmissionStore()
        store.begin(a1, "op-a")
        val before = store.records
        store.serverDeleted("op-x")
        store.releaseUnsent("op-x")
        assertEquals(before, store.records)
    }

    @Test
    fun d05_aReadRecordsListIsASnapshot() {
        val store = DeletionAdmissionStore()
        store.begin(a1, "op-a")
        val read = store.records
        store.serverDeleted("op-a")
        store.begin(b1, "op-b")
        assertEquals(listOf(DeletionAdmissionRecord(a1, "op-a", DeletionAdmissionPhase.REQUESTING_SERVER)), read)
    }

    /**
     * d07: the graph admission over the store - the one supplier the coordinator and the gate share - closes for no live
     * identity and for a live user with a deletion, at any of that user's generations, and stays open otherwise.
     */
    @Test
    fun d07_theGraphAdmissionClosesForNoIdentityAndForADeletedUser() {
        val store = DeletionAdmissionStore()
        var live: AuthIdentityFence? = null
        val admission = GraphProtectedAdmission({ live }, store)
        assertFalse("no live identity", admission())
        live = a2
        assertTrue("a live user with no deletion", admission())
        store.begin(a1, "op-a")
        assertFalse("the same user's deletion from an older generation", admission())
        live = b1
        assertTrue("another user", admission())
    }

    /** Readers on other threads see either side of each change and never an exception or a torn list. */
    @Test
    fun d06_readsAndWritesFromManyThreads() {
        val store = DeletionAdmissionStore()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        try {
            repeat(4) { w ->
                pool.execute {
                    start.await()
                    try {
                        repeat(200) { i ->
                            val op = "op-$w-$i"
                            store.begin(AuthIdentityFence("user-$w", 1L), op)
                            if (i % 2 == 0) store.releaseUnsent(op) else store.serverDeleted(op)
                        }
                    } catch (t: Throwable) {
                        failures += t
                    }
                }
            }
            repeat(4) {
                pool.execute {
                    start.await()
                    try {
                        repeat(2000) {
                            store.blocks("user-${it % 4}")
                            store.records.forEach { record -> record.phase }
                        }
                    } catch (t: Throwable) {
                        failures += t
                    }
                }
            }
            start.countDown()
            pool.shutdown()
            assertTrue("the threads finished", pool.awaitTermination(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertTrue("no read or write failed: $failures", failures.isEmpty())
        repeat(4) { w ->
            assertEquals("user-$w keeps exactly its server-deleted half", 100, store.records.count { it.owner.uid == "user-$w" })
            assertTrue(store.records.filter { it.owner.uid == "user-$w" }.all { it.phase == DeletionAdmissionPhase.SERVER_DELETED })
        }
    }
}
