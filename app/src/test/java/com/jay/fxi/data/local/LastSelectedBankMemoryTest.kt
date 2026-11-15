package com.jay.fxi.data.local

import com.jay.fxi.data.auth.AuthIdentityFence
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The process memory in front of the last-bank store (S7 E5b4b, `s7e5b4_api_agreed.r1.md` §2.2).
 *
 * Main and the worker share one test scheduler, so every interleaving here is chosen, not raced: a read is held open with a
 * gate, a newer step is taken, and then the read is let go.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LastSelectedBankMemoryTest {

    private val u1 = AuthIdentityFence("u1", 1)
    private val u1Next = AuthIdentityFence("u1", 2)
    private val u2 = AuthIdentityFence("u2", 1)

    /** A store whose reads and writes wait at gates and are logged in the order the worker reached them. */
    private class GatedStore : LastSelectedBankStore {
        val log = mutableListOf<String>()
        val values = mutableMapOf<String, String>()
        val readGates = ArrayDeque<CompletableDeferred<Unit>>()
        val writeGates = ArrayDeque<CompletableDeferred<Unit>>()
        /** When not empty, each read returns the next of these instead of [values], so two reads can see two states. */
        val readResults = ArrayDeque<String?>()
        var readFailure: Exception? = null
        var writeFailure: Exception? = null
        var verifyFailure: Exception? = null
        var verifies = 0

        /** Each store call's start and end, and the most that were ever inside at once. */
        val trace = mutableListOf<String>()
        private var active = 0
        var maxActive = 0

        /** One owner for the whole file, as the E5b4a store keeps it: a read for anyone else clears it and answers none. */
        var singleOwner = false
        var owner: String? = null
        var ownerCode: String? = null

        private suspend fun <T> inside(what: String, body: suspend () -> T): T {
            trace += "start $what"
            active++
            maxActive = maxOf(maxActive, active)
            try {
                return body()
            } finally {
                active--
                trace += "end $what"
            }
        }

        override suspend fun read(uid: String): String? {
            log += "read $uid"
            return inside("read $uid") {
                readGates.removeFirstOrNull()?.await()
                readFailure?.let { readFailure = null; throw it }
                when {
                    readResults.isNotEmpty() -> readResults.removeFirst()
                    singleOwner -> if (owner == uid) ownerCode else { owner = null; ownerCode = null; null }
                    else -> values[uid]
                }
            }
        }

        override suspend fun remember(uid: String, bankCode: String) {
            log += "write $uid $bankCode"
            inside("write $uid $bankCode") {
                writeGates.removeFirstOrNull()?.await()
                writeFailure?.let { writeFailure = null; throw it }
                if (singleOwner) { owner = uid; ownerCode = bankCode } else values[uid] = bankCode
            }
        }

        override suspend fun verifyReadable() {
            verifies++
            verifyFailure?.let { throw it }
        }
    }

    private class Rig(scope: TestScope) {
        val store = GatedStore()
        val reports = mutableListOf<Throwable>()
        val worker = CoroutineScope(StandardTestDispatcher(scope.testScheduler) + SupervisorJob())
        val memory = LastSelectedBankMemory(store, StandardTestDispatcher(scope.testScheduler), worker) { reports += it }
    }

    /** Main and the worker each on a scheduler of its own: a step runs only when the test runs that scheduler. */
    private class SplitRig {
        val store = GatedStore()
        val reports = mutableListOf<Throwable>()
        val main = TestCoroutineScheduler()
        val work = TestCoroutineScheduler()
        val worker = CoroutineScope(StandardTestDispatcher(work) + SupervisorJob())
        val memory = LastSelectedBankMemory(store, StandardTestDispatcher(main), worker) { reports += it }
    }

    private fun revisionOf(snapshot: BankSelectionSnapshot): Long = when (snapshot) {
        is BankSelectionSnapshot.Loading -> snapshot.revision
        is BankSelectionSnapshot.Ready -> snapshot.revision
        is BankSelectionSnapshot.Failed -> snapshot.revision
        BankSelectionSnapshot.None -> error("None 에는 revision 이 없다")
    }

    @Test
    fun aPrepareReadsAndPublishesReadyWithTheCodeOrNone() = runTest {
        val r = Rig(this)
        r.store.values["u1"] = "hana"
        assertEquals(BankSelectionSnapshot.None, r.memory.selection.value)
        r.memory.prepare(u1)
        val ready = r.memory.selection.value as BankSelectionSnapshot.Ready
        assertEquals(listOf(u1, "hana"), listOf(ready.identity, ready.code))

        r.memory.prepare(u2)
        assertEquals("기억 없는 UID 는 Ready(null)", null, (r.memory.selection.value as BankSelectionSnapshot.Ready).code)
        r.worker.cancel()
    }

    @Test
    fun theSameIdentitysReadyIsReused_aNewIdentityOrAuthGenerationReadsAgain() = runTest {
        val r = Rig(this)
        r.memory.prepare(u1)
        r.memory.prepare(u1)
        assertEquals("같은 identity 의 Ready 는 다시 읽지 않는다", listOf("read u1"), r.store.log)
        r.memory.prepare(u1Next)
        assertEquals("auth generation 이 바뀌면 다시 읽는다", listOf("read u1", "read u1"), r.store.log)
        assertEquals(u1Next, (r.memory.selection.value as BankSelectionSnapshot.Ready).identity)
        r.worker.cancel()
    }

    @Test
    fun whileTheReadIsOpenTheSelectionIsLoading() = runTest {
        val r = Rig(this)
        val gate = CompletableDeferred<Unit>().also { r.store.readGates += it }
        val waiting = launch { r.memory.prepare(u1) }
        advanceUntilIdle()
        assertTrue(r.memory.selection.value is BankSelectionSnapshot.Loading)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(r.memory.selection.value is BankSelectionSnapshot.Ready)
        waiting.join()
        r.worker.cancel()
    }

    /** A late read, success or failure, finds its revision gone once an Add was accepted, and changes nothing. */
    @Test
    fun aLateReadNeverOverwritesANewerAcceptedAdd() = runTest {
        for (fails in listOf(false, true)) {
            val r = Rig(this)
            r.store.values["u1"] = "kb"
            if (fails) r.store.readFailure = IOException("read")
            val gate = CompletableDeferred<Unit>().also { r.store.readGates += it }
            val waiting = launch { r.memory.prepare(u1) }
            advanceUntilIdle()
            r.memory.acceptAddedBank(u1, "sc")
            gate.complete(Unit)
            advanceUntilIdle()
            val now = r.memory.selection.value as BankSelectionSnapshot.Ready
            assertEquals("fails=$fails: 늦은 read 가 새 Add 를 덮었다", "sc", now.code)
            waiting.join()
            r.worker.cancel()
        }
    }

    /**
     * Two prepares of one identity overlap: the older read, finishing first with what the file held then, finds a newer
     * revision and is dropped; the newer read's answer is the one published.
     */
    @Test
    fun anOlderReadOfTheSameIdentityLosesToTheNewerOne() = runTest {
        val r = Rig(this)
        val gate = CompletableDeferred<Unit>().also { r.store.readGates += it }
        r.store.readResults += listOf("old", "new")
        val first = launch { r.memory.prepare(u1) }
        advanceUntilIdle()
        val second = launch { r.memory.prepare(u1) }
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("오래된 read 가 이겼다", "new", (r.memory.selection.value as BankSelectionSnapshot.Ready).code)
        assertEquals(listOf("read u1", "read u1"), r.store.log)
        first.join()
        second.join()
        r.worker.cancel()
    }

    @Test
    fun aFailedReadIsFailedNotReadyNull_reported_andTheNextPrepareReadsAgain() = runTest {
        val r = Rig(this)
        val failure = IOException("unreadable")
        r.store.readFailure = failure
        r.memory.prepare(u1)
        val failed = r.memory.selection.value
        assertTrue("Failed 여야 한다: $failed", failed is BankSelectionSnapshot.Failed)
        assertNotEquals(BankSelectionSnapshot.Ready(u1, (failed as BankSelectionSnapshot.Failed).revision, null), failed)
        assertEquals(listOf<Throwable>(failure), r.reports)

        r.store.values["u1"] = "woori"
        r.memory.prepare(u1)
        assertEquals("다음 prepare 가 실제로 다시 읽는다", listOf("read u1", "read u1"), r.store.log)
        assertEquals("woori", (r.memory.selection.value as BankSelectionSnapshot.Ready).code)
        r.worker.cancel()
    }

    /** Two accepted Adds are written in the order they were accepted, each with the identity and code it was given. */
    @Test
    fun acceptedAddsAreWrittenFirstInFirstOutWithTheirOwnValues() = runTest {
        val r = Rig(this)
        r.memory.acceptAddedBank(u1, "hana")
        r.memory.acceptAddedBank(u2, "kb")
        r.memory.acceptAddedBank(u1, "nh")
        assertEquals("접수 즉시 Ready", BankSelectionSnapshot.Ready(u1, 3, "nh"), r.memory.selection.value)
        advanceUntilIdle()
        assertEquals(listOf("write u1 hana", "write u2 kb", "write u1 nh"), r.store.log)
        r.worker.cancel()
    }

    /** acceptAddedBank only publishes and queues: nothing reaches the store before the worker runs. */
    @Test
    fun acceptingAnAddDoesNoFileWorkOfItsOwn() = runTest {
        val r = Rig(this)
        r.memory.acceptAddedBank(u1, "hana")
        assertEquals("접수 단계에서 store 를 건드렸다", emptyList<String>(), r.store.log)
        advanceUntilIdle()
        assertEquals(listOf("write u1 hana"), r.store.log)
        r.worker.cancel()
    }

    @Test
    fun aFailedWriteIsReported_theReadyStays_andTheNextTaskStillRuns() = runTest {
        val r = Rig(this)
        val failure = IOException("disk")
        r.store.writeFailure = failure
        r.memory.acceptAddedBank(u1, "hana")
        r.memory.acceptAddedBank(u1, "sc")
        advanceUntilIdle()
        assertEquals(listOf<Throwable>(failure), r.reports)
        assertEquals(BankSelectionSnapshot.Ready(u1, 2, "sc"), r.memory.selection.value)
        assertEquals("실패 뒤 다음 작업도 실행된다", listOf("write u1 hana", "write u1 sc"), r.store.log)
        r.worker.cancel()
    }

    /** A caller that stops waiting does not take the read with it: the worker finishes it and the result is published. */
    @Test
    fun aCancelledWaiterLeavesTheReadToFinish() = runTest {
        val r = Rig(this)
        r.store.values["u1"] = "ibk"
        val gate = CompletableDeferred<Unit>().also { r.store.readGates += it }
        val waiting = launch { r.memory.prepare(u1) }
        advanceUntilIdle()
        waiting.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("영구 Loading 이 남았다", "ibk", (r.memory.selection.value as BankSelectionSnapshot.Ready).code)
        r.worker.cancel()
    }

    /**
     * The worker being cancelled is not turned into a failure or an empty Ready, and is not reported; the cancellation reaches
     * the waiters — the read in flight and the one still queued — without the test cancelling them. Staying Loading is what
     * this implementation does, not a required final state.
     */
    @Test
    fun aCancelledWorkerIsNotAFailure_andReachesItsWaiters() = runTest {
        val r = Rig(this)
        CompletableDeferred<Unit>().also { r.store.readGates += it }
        val inFlight = launch { r.memory.prepare(u1) }
        advanceUntilIdle()
        val queued = launch { r.memory.prepare(u2) }
        advanceUntilIdle()
        assertEquals("read 에 들어가지 않았다", listOf("start read u1"), r.store.trace)
        r.worker.cancel()
        advanceUntilIdle()
        val now = r.memory.selection.value
        assertFalse("취소가 Failed 가 됐다: $now", now is BankSelectionSnapshot.Failed)
        assertFalse("취소가 Ready 가 됐다: $now", now is BankSelectionSnapshot.Ready)
        assertEquals("취소를 실패로 report 했다", emptyList<Throwable>(), r.reports)
        assertTrue("진행 중 read 의 waiter 에 취소가 닿지 않았다", inFlight.isCompleted && inFlight.isCancelled)
        assertTrue("대기 중 read 의 waiter 에 취소가 닿지 않았다", queued.isCompleted && queued.isCancelled)
        assertEquals("취소 뒤 다른 store 작업이 돌았다", listOf("start read u1", "end read u1"), r.store.trace)
    }

    /**
     * A read the worker has finished is published only by a step on Main, and that step looks again: an Add accepted on Main
     * after the read ended but before its step ran stands — whether the read found a code, none, or failed (G01).
     */
    @Test
    fun aFinishedReadWaitsForMain_andAnAddAcceptedBeforeItsStepStands() {
        for (outcome in listOf("code", "none", "failure")) {
            val r = SplitRig()
            when (outcome) {
                "code" -> r.store.values["u1"] = "kb"
                "failure" -> r.store.readFailure = IOException("read")
            }
            // The caller lives on Main's scheduler too, outside runTest, so no third scheduler is in play.
            val caller = CoroutineScope(StandardTestDispatcher(r.main) + Job())
            val waiting = caller.launch { r.memory.prepare(u1) }
            r.main.runCurrent()
            val loading = BankSelectionSnapshot.Loading(u1, 1)
            assertEquals(outcome, loading, r.memory.selection.value)
            r.work.runCurrent()
            assertEquals("$outcome: read 가 끝나지 않았다", listOf("start read u1", "end read u1"), r.store.trace)
            assertEquals("$outcome: Main 이 돌기 전에 게시했다", loading, r.memory.selection.value)
            r.memory.acceptAddedBank(u1, "sc")
            val added = BankSelectionSnapshot.Ready(u1, 2, "sc")
            assertEquals(outcome, added, r.memory.selection.value)
            r.main.runCurrent()
            assertEquals("$outcome: 늦은 completion 이 Add 를 덮었다", added, r.memory.selection.value)
            r.work.runCurrent()
            r.main.runCurrent()
            assertTrue("$outcome: waiter 가 끝나지 않았다", waiting.isCompleted)
            assertEquals("$outcome: Add write 가 뒤를 잇지 않았다", "start write u1 sc", r.store.trace.getOrNull(2))
            caller.cancel()
            r.worker.cancel()
        }
    }

    /** Between two prepares the older read publishes nothing at any moment — not just "the newer one wins at the end" (G02). */
    @Test
    fun anOlderReadNeverPublishes_evenForAMoment() = runTest {
        class Case(val name: String, val second: AuthIdentityFence, val oldFails: Boolean, val oldValue: String?)
        val cases = listOf(
            Case("같은 identity, 성공", u1, false, "old"),
            Case("같은 identity, 부재", u1, false, null),
            Case("같은 identity, 실패", u1, true, null),
            Case("다른 UID", u2, false, "old"),
            Case("같은 UID 다른 generation", u1Next, false, "old")
        )
        for (c in cases) {
            val r = Rig(this)
            val history = mutableListOf<BankSelectionSnapshot>()
            val collector = launch(UnconfinedTestDispatcher(testScheduler)) { r.memory.selection.collect { history += it } }
            val firstGate = CompletableDeferred<Unit>()
            val secondGate = CompletableDeferred<Unit>()
            r.store.readGates += listOf(firstGate, secondGate)
            if (c.oldFails) r.store.readFailure = IOException("old") else r.store.readResults += c.oldValue
            r.store.readResults += "new"
            val a = launch { r.memory.prepare(u1) }
            advanceUntilIdle()
            val b = launch { r.memory.prepare(c.second) }
            advanceUntilIdle()
            firstGate.complete(Unit)
            advanceUntilIdle()
            val first = BankSelectionSnapshot.Loading(u1, 1)
            val second = BankSelectionSnapshot.Loading(c.second, 2)
            assertEquals("${c.name}: 첫 read 뒤", second, r.memory.selection.value)
            assertEquals("${c.name}: 중간 게시", listOf(BankSelectionSnapshot.None, first, second), history)
            secondGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                c.name,
                listOf(BankSelectionSnapshot.None, first, second, BankSelectionSnapshot.Ready(c.second, 2, "new")),
                history
            )
            a.join()
            b.join()
            collector.cancel()
            r.worker.cancel()
        }
    }

    /** Each prepare and Add takes a newer revision — across identities, a retry after Failed, and an Add — and a read's completion keeps the revision it started with (G02). */
    @Test
    fun revisionsOnlyGrow_andACompletionCarriesItsOwn() = runTest {
        val r = Rig(this)
        val history = mutableListOf<BankSelectionSnapshot>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { r.memory.selection.collect { history += it } }
        r.store.values["u1"] = "hana"
        r.memory.prepare(u1)
        r.memory.prepare(u2)
        r.store.readFailure = IOException("unreadable")
        r.memory.prepare(u1Next)
        r.memory.prepare(u1Next)
        r.memory.acceptAddedBank(u2, "nh")
        advanceUntilIdle()
        val steps = history.drop(1)
        assertEquals(
            listOf("Loading", "Ready", "Loading", "Ready", "Loading", "Failed", "Loading", "Ready", "Ready"),
            steps.map { it::class.simpleName }
        )
        var last = 0L
        var open: BankSelectionSnapshot.Loading? = null
        for (step in steps) {
            val revision = revisionOf(step)
            val started = open
            if (step !is BankSelectionSnapshot.Loading && started != null) {
                assertEquals("completion 이 자기 revision 을 잃었다: $step", started.revision, revision)
                open = null
            } else {
                assertTrue("revision 이 커지지 않았다: $step after $last", revision > last)
                last = revision
                open = step as? BankSelectionSnapshot.Loading
            }
        }
        collector.cancel()
        r.worker.cancel()
    }

    /** Reads and writes share one line: a write waits behind an open read, everything waits behind an open write, one at a time, in order (G03). */
    @Test
    fun readsAndWritesRunOneAtATimeInTheOrderTheyWereQueued() = runTest {
        val r = Rig(this)
        r.store.singleOwner = true
        val readGate = CompletableDeferred<Unit>().also { r.store.readGates += it }
        val first = launch { r.memory.prepare(u1) }
        advanceUntilIdle()
        r.memory.acceptAddedBank(u1, "hana")
        advanceUntilIdle()
        assertEquals("read 가 열린 동안 write 가 시작했다", listOf("start read u1"), r.store.trace)
        readGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("start read u1", "end read u1", "start write u1 hana", "end write u1 hana"), r.store.trace)

        val writeGate = CompletableDeferred<Unit>().also { r.store.writeGates += it }
        r.memory.acceptAddedBank(u1, "kb")
        advanceUntilIdle()
        val second = launch { r.memory.prepare(u2) }
        advanceUntilIdle()
        r.memory.acceptAddedBank(u2, "sc")
        advanceUntilIdle()
        assertEquals("write 가 열린 동안 다음 작업이 시작했다", "start write u1 kb", r.store.trace.last())
        writeGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(
            listOf(
                "start write u1 kb", "end write u1 kb",
                "start read u2", "end read u2",
                "start write u2 sc", "end write u2 sc"
            ),
            r.store.trace.drop(4)
        )
        assertEquals("동시에 둘이 돌았다", 1, r.store.maxActive)
        assertEquals("u2" to "sc", r.store.owner to r.store.ownerCode)
        first.join()
        second.join()
        r.worker.cancel()
    }

    /** Queued work keeps the identity it was queued with, whatever the live selection has become by the time it runs (G03). */
    @Test
    fun queuedWorkKeepsTheIdentityItWasQueuedWith() = runTest {
        val r = Rig(this)
        val writeGate = CompletableDeferred<Unit>().also { r.store.writeGates += it }
        r.memory.acceptAddedBank(u2, "kb")
        advanceUntilIdle()
        val a = launch { r.memory.prepare(u1) }
        advanceUntilIdle()
        val b = launch { r.memory.prepare(u2) }
        advanceUntilIdle()
        r.memory.acceptAddedBank(u2, "sc")
        r.memory.acceptAddedBank(u1Next, "nh")
        assertEquals("앞 작업이 막힌 동안 뒤 작업이 시작했다", listOf("write u2 kb"), r.store.log)
        writeGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("write u2 kb", "read u1", "read u2", "write u2 sc", "write u1 nh"), r.store.log)
        assertEquals(BankSelectionSnapshot.Ready(u1Next, 5, "nh"), r.memory.selection.value)
        assertEquals(mapOf("u2" to "sc", "u1" to "nh"), r.store.values)
        a.join()
        b.join()
        r.worker.cancel()
    }

    /** The latest Add's write failing, with nothing newer behind it, still leaves that Add's Ready as it was (G04). */
    @Test
    fun aFailedWriteOfTheLatestAddLeavesItsReadyExactlyAsItWas() = runTest {
        val r = Rig(this)
        r.store.values["u1"] = "kb"
        r.memory.prepare(u1)
        val failure = IOException("disk")
        r.store.writeFailure = failure
        r.memory.acceptAddedBank(u1, "hana")
        val accepted = r.memory.selection.value
        assertEquals(BankSelectionSnapshot.Ready(u1, 2, "hana"), accepted)
        advanceUntilIdle()
        assertSame("실패한 write 가 Ready 를 바꿨다", accepted, r.memory.selection.value)
        assertEquals(listOf<Throwable>(failure), r.reports)
        r.memory.prepare(u1)
        assertEquals("같은 identity 를 다시 읽었다", listOf("read u1", "write u1 hana"), r.store.log)
        assertSame(accepted, r.memory.selection.value)
        r.memory.acceptAddedBank(u1, "nh")
        advanceUntilIdle()
        assertEquals("실패 뒤 worker 가 멈췄다", "nh", r.store.values["u1"])
        r.worker.cancel()
    }

    /** requireReady reads the file every time, whatever the selection says: a Ready or Failed is no evidence of it (G05). */
    @Test
    fun requireReadyChecksTheFileEveryTime_whateverTheSelectionSays() = runTest {
        val unbound = Rig(this)
        unbound.store.values["u1"] = "hana"
        unbound.memory.prepare(u1)
        assertTrue(unbound.memory.selection.value is BankSelectionSnapshot.Ready)
        assertTrue("bound 전에 통과했다", runCatching { unbound.memory.requireReady() }.exceptionOrNull() is IllegalStateException)
        assertEquals("bound 전에 파일을 확인했다", 0, unbound.store.verifies)
        unbound.worker.cancel()

        val r = Rig(this)
        r.memory.markConsumerBound()
        val failure = IOException("unreadable")
        suspend fun failsEveryTime(label: String) {
            val before = r.memory.selection.value
            val verifies = r.store.verifies
            r.store.verifyFailure = failure
            assertSame("$label: 다른 실패", failure, runCatching { r.memory.requireReady() }.exceptionOrNull())
            assertEquals("$label: 파일을 확인하지 않았다", verifies + 1, r.store.verifies)
            assertSame("$label: selection 이 바뀌었다", before, r.memory.selection.value)
            r.store.verifyFailure = null
        }
        r.store.values["u1"] = "hana"
        r.memory.prepare(u1)
        failsEveryTime("Ready(code)")
        r.memory.prepare(u2)
        failsEveryTime("Ready(null)")
        r.store.readFailure = IOException("read")
        r.memory.prepare(u1Next)
        assertTrue(r.memory.selection.value is BankSelectionSnapshot.Failed)
        failsEveryTime("Failed")
        val verifies = r.store.verifies
        r.memory.requireReady()
        assertEquals("Failed 에서 verify 성공이면 통과해야 한다", verifies + 1, r.store.verifies)
        r.store.writeFailure = IOException("disk")
        r.memory.acceptAddedBank(u2, "sc")
        advanceUntilIdle()
        assertEquals(BankSelectionSnapshot.Ready(u2, 4, "sc"), r.memory.selection.value)
        failsEveryTime("write 실패 뒤 Ready")
        r.worker.cancel()
    }

    /** A code is kept exactly as it was read or accepted — no trimming, case folding or matching to a choice (G06). */
    @Test
    fun codesAreKeptVerbatim() = runTest {
        val r = Rig(this)
        r.store.values["u1"] = "  hana  "
        r.memory.prepare(u1)
        assertEquals(BankSelectionSnapshot.Ready(u1, 1, "  hana  "), r.memory.selection.value)
        val codes = listOf("HANA", "Woori", "future_bank", "", "citi")
        codes.forEachIndexed { i, code ->
            r.memory.acceptAddedBank(u2, code)
            assertEquals(BankSelectionSnapshot.Ready(u2, 2L + i, code), r.memory.selection.value)
        }
        advanceUntilIdle()
        assertEquals(listOf("read u1") + codes.map { "write u2 $it" }, r.store.log)
        r.worker.cancel()
    }

    @Test
    fun requireReadyNeedsTheBoundConsumerAndAReadableFile() = runTest {
        val r = Rig(this)
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { r.memory.requireReady() } }
        assertEquals("bound 전에 파일을 확인했다", 0, r.store.verifies)
        r.memory.markConsumerBound()
        r.memory.requireReady()
        assertEquals(1, r.store.verifies)
        r.store.verifyFailure = IOException("unreadable")
        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { r.memory.requireReady() } }
        r.worker.cancel()
    }
}
