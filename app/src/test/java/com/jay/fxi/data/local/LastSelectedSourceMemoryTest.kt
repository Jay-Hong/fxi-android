package com.jay.fxi.data.local

import com.jay.fxi.data.auth.AuthIdentityFence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S8-5b J4 (`R4c/S8/s8_5b_contract.agreed.r1.md` §2 "저장 형식과 오류"): one source key per UID, so A → B → A restores
 * each account's last add; an add is published on Main before its write; a late read never overwrites a newer add; a read
 * failure settles as Failed and writes nothing; a write failure leaves the next work running.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LastSelectedSourceMemoryTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val bob = AuthIdentityFence("user-b", 1)

    private class Store : LastSelectedSourceStore {
        val values = mutableMapOf<String, String>()
        val writes = mutableListOf<Pair<String, String>>()
        val reads = mutableListOf<String>()
        var readGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        var failRead = false
        var failWrite = false
        override suspend fun read(uid: String): String? {
            reads += uid
            readGate?.await()
            if (failRead) error("read failed")
            return values[uid]
        }
        override suspend fun remember(uid: String, sourceCode: String) {
            writeGate?.await()
            if (failWrite) error("write failed")
            writes += uid to sourceCode
            values[uid] = sourceCode
        }
    }

    @Test fun `S8_5B_J4a each UID keeps its own last add across A, B and A`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val store = Store()
        val memory = LastSelectedSourceMemory(store, dispatcher, scope) { }
        launch { memory.prepare(alice) }; runCurrent()
        memory.acceptAddedSource(alice, "bithumb"); runCurrent()
        launch { memory.prepare(bob) }; runCurrent()
        assertEquals("B starts with nothing", SourceSelectionSnapshot.Ready(bob, 3, null), memory.selection.value)
        memory.acceptAddedSource(bob, "gopax"); runCurrent()
        // A fresh process for A reads its own key.
        val again = LastSelectedSourceMemory(store, dispatcher, scope) { }
        launch { again.prepare(alice) }; runCurrent()
        assertEquals("bithumb", (again.selection.value as SourceSelectionSnapshot.Ready).code)
        assertEquals(listOf("user-a" to "bithumb", "user-b" to "gopax"), store.writes)
        scope.cancel()
    }

    @Test fun `S8_5B_J4b an add while a read is late is never overwritten by that read`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val store = Store().apply { values["user-a"] = "upbit"; readGate = CompletableDeferred() }
        val memory = LastSelectedSourceMemory(store, dispatcher, scope) { }
        launch { memory.prepare(alice) }; runCurrent()
        assertTrue(memory.selection.value is SourceSelectionSnapshot.Loading)
        memory.acceptAddedSource(alice, "korbit"); runCurrent()
        store.readGate!!.complete(Unit); runCurrent()
        assertEquals("korbit", (memory.selection.value as SourceSelectionSnapshot.Ready).code)
        scope.cancel()
    }

    @Test fun `S8_5B_J4c a read failure settles as Failed and writes nothing, a write failure keeps the next work`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val reports = mutableListOf<Throwable>()
        val store = Store().apply { failRead = true }
        val memory = LastSelectedSourceMemory(store, dispatcher, scope) { reports += it }
        launch { memory.prepare(alice) }; runCurrent()
        assertTrue(memory.selection.value is SourceSelectionSnapshot.Failed)
        assertEquals("nothing written by a failed read", emptyList<Pair<String, String>>(), store.writes)
        store.failWrite = true
        memory.acceptAddedSource(alice, "coinone"); runCurrent()
        assertEquals("the process keeps the add", "coinone", (memory.selection.value as SourceSelectionSnapshot.Ready).code)
        assertEquals("the disk keeps its last persisted value", null, store.values["user-a"])
        store.failWrite = false
        memory.acceptAddedSource(alice, "gopax"); runCurrent()
        assertEquals("the next write still runs", listOf("user-a" to "gopax"), store.writes)
        assertEquals("two failures reported", 2, reports.size)
        scope.cancel()
    }

    @Test fun `S8_5B_J4d one memory restores A after B, a warm prepare reads nothing, a new generation reads again`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val store = Store().apply { values["user-a"] = "bithumb"; values["user-b"] = "gopax" }
        val memory = LastSelectedSourceMemory(store, dispatcher, scope) { }
        launch { memory.prepare(alice) }; runCurrent()
        launch { memory.prepare(alice) }; runCurrent()
        assertEquals("a warm prepare reads nothing", listOf("user-a"), store.reads)
        launch { memory.prepare(bob) }; runCurrent()
        assertEquals("gopax", (memory.selection.value as SourceSelectionSnapshot.Ready).code)
        launch { memory.prepare(alice) }; runCurrent()
        assertEquals("A after B", SourceSelectionSnapshot.Ready(alice, 3, "bithumb"), memory.selection.value)
        val alice2 = AuthIdentityFence("user-a", 2)
        launch { memory.prepare(alice2) }; runCurrent()
        assertEquals("the same UID's next generation reads again", SourceSelectionSnapshot.Ready(alice2, 4, "bithumb"), memory.selection.value)
        assertEquals(listOf("user-a", "user-b", "user-a", "user-a"), store.reads)
        scope.cancel()
    }

    @Test fun `S8_5B_J4f a delayed read of the old generation cannot publish in the new one`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val store = Store().apply { values[alice.uid] = "upbit"; readGate = CompletableDeferred() }
            val memory = LastSelectedSourceMemory(store, dispatcher, scope) { }
            launch { memory.prepare(alice) }
            runCurrent()
            val next = AuthIdentityFence(alice.uid, 2)
            launch { memory.prepare(next) }
            runCurrent()
            val oldRead = checkNotNull(store.readGate)
            val newRead = CompletableDeferred<Unit>()
            store.readGate = newRead
            oldRead.complete(Unit)
            runCurrent()
            assertEquals("the old read cannot publish", SourceSelectionSnapshot.Loading(next, 2), memory.selection.value)
            store.values[next.uid] = "korbit"
            newRead.complete(Unit)
            runCurrent()
            assertEquals(SourceSelectionSnapshot.Ready(next, 2, "korbit"), memory.selection.value)
        } finally {
            scope.cancel()
        }
    }

    @Test fun `S8_5B_J4e two adds while the first write waits show the second at once and persist both in order`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val store = Store().apply { writeGate = CompletableDeferred() }
        val memory = LastSelectedSourceMemory(store, dispatcher, scope) { }
        launch { memory.prepare(alice) }; runCurrent()
        memory.acceptAddedSource(alice, "korbit")
        memory.acceptAddedSource(alice, "coinone")
        assertEquals("the latest add at once", "coinone", (memory.selection.value as SourceSelectionSnapshot.Ready).code)
        runCurrent()
        assertEquals("nothing persisted yet", emptyList<Pair<String, String>>(), store.writes)
        store.writeGate!!.complete(Unit); runCurrent()
        assertEquals("both, in order", listOf("user-a" to "korbit", "user-a" to "coinone"), store.writes)
        val cold = LastSelectedSourceMemory(store, dispatcher, scope) { }
        launch { cold.prepare(alice) }; runCurrent()
        assertEquals("a cold read sees the last", "coinone", (cold.selection.value as SourceSelectionSnapshot.Ready).code)
        scope.cancel()
    }
}
