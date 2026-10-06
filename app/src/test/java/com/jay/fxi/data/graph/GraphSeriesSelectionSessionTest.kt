package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionKey
import com.jay.fxi.data.local.GraphSelectionReadResult
import com.jay.fxi.data.local.GraphSelectionRecord
import com.jay.fxi.data.local.GraphSelectionStore
import com.jay.fxi.data.local.GraphSelectionUnreadableReason
import com.jay.fxi.data.local.GraphSelectionWriteResult
import com.jay.fxi.domain.model.GraphSelectionChange
import com.jay.fxi.domain.model.GraphSeriesSelection
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 B2b-2 contract r3: the session that binds one audience and tab's graph selection to the signed-in UID.
 * r2 adds close() and bind's blank-UID refusal, from Codex's r1 review; r3 queues an old-binding restore in B2-09e (battery S06).
 *
 * Oracles: ANDROID_V2_PLAN.md :1292 (the selection is UID-scoped; a late answer for another user never shows), §7 S1 (a
 * write in flight finishes; nothing of the previous identity is applied after a switch). Design: b2b_api_codex.r1 §3 rules
 * 1-9 (bind is non-suspending, removes the previous publication at once and bumps the generation on every call; work is
 * serial; queued work of a retired binding is StaleBinding without a store call; started work of a retired binding is drained
 * before the new binding's work starts; the binding is re-checked right before a result is published; restore uses
 * confirmGraphSelection; Unreadable and WriteUncertain refuse apply without calling the change; Unchanged, Rejected and a
 * Replace equal to the current selection write nothing; a caller's cancellation is not the store command's) and §5 rows
 * B2-09c-g, B2-10i/j.
 *
 * The store is a fake on purpose: it does not serialise calls, so the session's own ordering is what is tested, and it pauses
 * one named operation at its entry. Publications are collected eagerly, so a late value that is overwritten in the same step
 * is still seen. The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphSeriesSelectionSessionTest {

    private companion object {
        val AUDIENCE = GraphSelectionAudience.PREMIUM
        const val TAB = "usd"
        fun fence(uid: String, generation: Long = 1) = AuthIdentityFence(uid, generation)
        fun key(uid: String) = GraphSelectionKey(uid, AUDIENCE, TAB)
        fun s(v: Set<String>, i: Set<String>) = GraphSeriesSelection(v, i)
        fun present(uid: String, sel: GraphSeriesSelection) = GraphSelectionReadResult.Present(
            GraphSelectionRecord(1, uid, AUDIENCE, TAB, sel.visibleSeriesIds, sel.initializedSeries)
        )

        /** Fails at once rather than waiting for runTest's timeout when the session never answered. */
        fun <T> Deferred<T>.done(): T {
            assertTrue("the call did not complete", isCompleted)
            return getCompleted()
        }
    }

    private class Pause {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    /** Does not serialise; pauses the next named operation (`confirm:A`, `write:A`) at its entry; logs entries and ends. */
    private class FakeStore : GraphSelectionStore {
        val committed = mutableMapOf<GraphSelectionKey, GraphSeriesSelection>()
        val log = mutableListOf<String>()
        private val pauses = mutableMapOf<String, ArrayDeque<Pause>>()
        var nextConfirm: GraphSelectionReadResult? = null
        var nextWrite: ((GraphSelectionKey, GraphSeriesSelection) -> GraphSelectionWriteResult)? = null
        var cancelledInside = 0

        fun pauseNext(op: String) = Pause().also { pauses.getOrPut(op) { ArrayDeque() }.addLast(it) }

        val writes get() = log.count { it.startsWith("write:") }

        private suspend fun gate(op: String) {
            val pause = pauses[op]?.removeFirstOrNull() ?: return
            pause.reached.complete(Unit)
            try {
                pause.release.await()
            } catch (cancelled: CancellationException) {
                cancelledInside += 1
                throw cancelled
            }
        }

        private fun current(key: GraphSelectionKey) =
            committed[key]?.let { present(key.uid, it) } ?: GraphSelectionReadResult.Absent

        override suspend fun confirmGraphSelection(key: GraphSelectionKey): GraphSelectionReadResult {
            log += "confirm:${key.uid}"
            gate("confirm:${key.uid}")
            val override = nextConfirm
            nextConfirm = null
            return (override ?: current(key)).also { log += "confirmed:${key.uid}" }
        }

        override suspend fun readGraphSelection(key: GraphSelectionKey): GraphSelectionReadResult {
            log += "read:${key.uid}"
            return current(key)
        }

        override suspend fun writeGraphSelection(key: GraphSelectionKey, selection: GraphSeriesSelection): GraphSelectionWriteResult {
            log += "write:${key.uid}"
            gate("write:${key.uid}")
            val outcome = nextWrite
            nextWrite = null
            val result = if (outcome != null) outcome(key, selection) else {
                committed[key] = selection
                GraphSelectionWriteResult.Committed
            }
            log += "wrote:${key.uid}"
            return result
        }
    }

    private class Env(
        val test: TestScope,
        val store: FakeStore,
        val session: GraphSeriesSelectionSession,
        val callers: CoroutineScope,
        val seen: MutableList<GraphSelectionPublication>
    ) {
        fun <T> call(block: suspend () -> T): Deferred<T> = callers.async { block() }
        fun idle() = test.advanceUntilIdle()
        fun replace(sel: GraphSeriesSelection): (GraphSeriesSelection?) -> GraphSelectionChange = { GraphSelectionChange.Replace(sel) }
        suspend fun restored(binding: GraphSelectionBinding): GraphSelectionRestoreResult {
            val r = call { session.restore(binding) }
            idle()
            return r.done()
        }
    }

    private fun sessionTest(block: suspend Env.() -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sessionScope = CoroutineScope(SupervisorJob() + dispatcher)
        val callers = CoroutineScope(SupervisorJob() + dispatcher)
        val store = FakeStore()
        val session = GraphSeriesSelectionSession(store, AUDIENCE, TAB, sessionScope, dispatcher)
        val seen = mutableListOf<GraphSelectionPublication>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.publication.collect { seen += it } }
        try {
            Env(this, store, session, callers, seen).block()
        } finally {
            callers.cancel()
            sessionScope.cancel()
        }
    }

    private fun List<GraphSelectionPublication>.uids() = mapNotNull {
        when (it) {
            is GraphSelectionPublication.Restored -> it.binding.key.uid
            is GraphSelectionPublication.WriteUncertain -> it.binding.key.uid
            is GraphSelectionPublication.AwaitingRestore -> it.binding.key.uid
            GraphSelectionPublication.Unbound -> null
        }
    }

    private fun List<GraphSelectionPublication>.restoredUids() =
        filterIsInstance<GraphSelectionPublication.Restored>().map { it.binding.key.uid }

    // --- B2-09: binding, generation, serial work -------------------------------------------------------------------

    /**
     * B2-09c: A's restore is held right before its result; binding B removes A's publication at once; when A's result is let
     * go it is StaleBinding and never published; B restores B's own record.
     */
    @Test fun B2_09c_aLateRestoreOfAnotherUserIsNeverPublished() = sessionTest {
        store.committed[key("A")] = s(setOf("P"), setOf("P"))
        store.committed[key("B")] = s(setOf("Q"), setOf("Q"))
        val a = checkNotNull(session.bind(fence("A")))
        assertEquals(key("A"), a.key)
        val held = store.pauseNext("confirm:A")
        val aRestore = call { session.restore(a) }
        idle()
        assertTrue("premise: A's restore is held", held.reached.isCompleted)

        val b = checkNotNull(session.bind(fence("B")))
        assertEquals("B's binding is the publication at once", GraphSelectionPublication.AwaitingRestore(b), session.publication.value)
        val mark = seen.size
        held.release.complete(Unit)
        val bRestore = call { session.restore(b) }
        idle()

        assertEquals(GraphSelectionRestoreResult.StaleBinding, aRestore.done())
        assertEquals(GraphSelectionRestoreResult.Applied(present("B", s(setOf("Q"), setOf("Q")))), bRestore.done())
        assertFalse("nothing of A after B was bound: ${seen.drop(mark - 1)}", "A" in seen.drop(mark - 1).uids())
        assertEquals(GraphSelectionPublication.Restored(b, present("B", s(setOf("Q"), setOf("Q")))), session.publication.value)
    }

    /**
     * B2-09d: a held restore of A at auth generation 1 is StaleBinding after bind(null) and A at generation 2 - and after a
     * rebind of the very same fence, whose binding generation differs. Nothing of the old binding is applied in either case.
     */
    @Test fun B2_09d_aRebindRetiresTheOldBindingEvenForTheSameFence() = sessionTest {
        store.committed[key("A")] = s(setOf("P"), setOf("P"))
        val a1 = checkNotNull(session.bind(fence("A", 1)))
        val held = store.pauseNext("confirm:A")
        val oldRestore = call { session.restore(a1) }
        idle()
        assertTrue(held.reached.isCompleted)
        assertEquals(null, session.bind(null))
        assertEquals(GraphSelectionPublication.Unbound, session.publication.value)
        val mark = seen.size
        val a2 = checkNotNull(session.bind(fence("A", 2)))
        held.release.complete(Unit)
        idle()
        assertEquals(GraphSelectionRestoreResult.StaleBinding, oldRestore.done())
        assertEquals(GraphSelectionPublication.AwaitingRestore(a2), session.publication.value)
        assertTrue("no restored publication from the old binding: ${seen.drop(mark - 1)}", seen.drop(mark - 1).restoredUids().isEmpty())

        val sameFence = checkNotNull(session.bind(fence("A", 2)))
        assertNotEquals("every bind is a new generation", a2.bindingGeneration, sameFence.bindingGeneration)
        assertNotEquals(a2, sameFence)
        val heldAgain = store.pauseNext("confirm:A")
        val stale = call { session.restore(sameFence) }
        idle()
        assertTrue("premise: the same-fence binding's restore is held", heldAgain.reached.isCompleted)
        val markAgain = seen.size
        val current = checkNotNull(session.bind(fence("A", 2)))
        heldAgain.release.complete(Unit)
        idle()
        assertEquals(GraphSelectionRestoreResult.StaleBinding, stale.done())
        assertTrue(seen.drop(markAgain - 1).restoredUids().isEmpty())
        assertEquals("the current binding still restores",
            GraphSelectionRestoreResult.Applied(present("A", s(setOf("P"), setOf("P")))), restored(current))
    }

    /**
     * B2-09e: a write of the old binding that has started is drained before the new binding's restore or write starts; a write
     * and a restore of the old binding still queued are StaleBinding without a store call; then the new binding restores what
     * landed and writes its own selection.
     */
    @Test fun B2_09e_startedWorkIsDrainedAndQueuedWorkIsDropped() = sessionTest {
        store.committed[key("A")] = s(setOf("X"), setOf("X"))
        val a1 = checkNotNull(session.bind(fence("A", 1)))
        restored(a1)
        val held = store.pauseNext("write:A")
        val logStart = store.log.size
        val w1 = call { session.apply(a1, replace(s(setOf("Y"), setOf("X", "Y")))) }
        idle()
        assertTrue(held.reached.isCompleted)
        val w2 = call { session.apply(a1, replace(s(setOf("Z"), setOf("X", "Y", "Z")))) }
        val oldRestore = call { session.restore(a1) }
        idle()
        val a2 = checkNotNull(session.bind(fence("A", 2)))
        val r2 = call { session.restore(a2) }
        idle()
        assertEquals("nothing new starts before the started write ends", listOf("write:A"), store.log.drop(logStart))

        held.release.complete(Unit)
        idle()
        assertEquals("the old write landed but is not this binding's result", GraphSelectionApplyResult.StaleBinding, w1.done())
        assertEquals(GraphSelectionApplyResult.StaleBinding, w2.done())
        assertEquals(GraphSelectionRestoreResult.StaleBinding, oldRestore.done())
        assertEquals(GraphSelectionRestoreResult.Applied(present("A", s(setOf("Y"), setOf("X", "Y")))), r2.done())
        assertEquals("the queued write and the queued old restore never reached the store",
            listOf("write:A", "wrote:A", "confirm:A", "confirmed:A"), store.log.drop(logStart))

        val w3 = call { session.apply(a2, replace(s(setOf("W"), setOf("W", "X", "Y")))) }
        idle()
        assertEquals(GraphSelectionApplyResult.Committed, w3.done())
        assertEquals(s(setOf("W"), setOf("W", "X", "Y")), store.committed[key("A")])
    }

    /**
     * B2-09f: changes are computed from the selection current when they run, not when they were asked for: X off is held,
     * Y on waits, and the second change sees the first one's commit.
     */
    @Test fun B2_09f_aQueuedChangeSeesThePreviousCommit() = sessionTest {
        store.committed[key("A")] = s(setOf("X"), setOf("X", "Y"))
        val a = checkNotNull(session.bind(fence("A")))
        restored(a)
        val inputs = mutableListOf<GraphSeriesSelection?>()
        val held = store.pauseNext("write:A")
        val off = call {
            session.apply(a) { cur ->
                inputs += cur
                GraphSelectionChange.Replace(cur!!.copy(visibleSeriesIds = cur.visibleSeriesIds - "X"))
            }
        }
        idle()
        assertTrue(held.reached.isCompleted)
        val on = call {
            session.apply(a) { cur ->
                inputs += cur
                GraphSelectionChange.Replace(cur!!.copy(visibleSeriesIds = cur.visibleSeriesIds + "Y"))
            }
        }
        idle()
        held.release.complete(Unit)
        idle()
        assertEquals(GraphSelectionApplyResult.Committed, off.done())
        assertEquals(GraphSelectionApplyResult.Committed, on.done())
        assertEquals(listOf(s(setOf("X"), setOf("X", "Y")), s(emptySet(), setOf("X", "Y"))), inputs)
        assertEquals(s(setOf("Y"), setOf("X", "Y")), store.committed[key("A")])
        assertEquals(GraphSelectionPublication.Restored(a, present("A", s(setOf("Y"), setOf("X", "Y")))), session.publication.value)
    }

    /**
     * B2-09g: cancelling the caller waiting on a write does not cancel the store command; drain after bind(null) does not
     * return until that command has ended; nothing of it is applied to the unbound session.
     */
    @Test fun B2_09g_aCallersCancellationIsNotTheStoreCommands() = sessionTest {
        store.committed[key("A")] = s(setOf("X"), setOf("X"))
        val a = checkNotNull(session.bind(fence("A")))
        restored(a)
        val held = store.pauseNext("write:A")
        val caller = call { session.apply(a, replace(s(setOf("Y"), setOf("X", "Y")))) }
        idle()
        assertTrue(held.reached.isCompleted)
        caller.cancel()
        idle()
        assertEquals("the store command was not cancelled", 0, store.cancelledInside)

        assertEquals(null, session.bind(null))
        val mark = seen.size
        var drained = false
        call { session.drain(); drained = true }
        idle()
        assertFalse("drain waits for the started command", drained)

        held.release.complete(Unit)
        idle()
        assertTrue(drained)
        assertTrue("the command ran to its end", "wrote:A" in store.log)
        assertEquals(s(setOf("Y"), setOf("X", "Y")), store.committed[key("A")])
        assertEquals(GraphSelectionPublication.Unbound, session.publication.value)
        assertTrue("nothing applied after unbinding: ${seen.drop(mark - 1)}", seen.drop(mark - 1).uids().isEmpty())
    }

    // --- B2-10: uncertainty and unreadable ---------------------------------------------------------------------------

    /**
     * B2-10i: a write whose answer is uncertain publishes WriteUncertain; apply is then NotReady without calling the change or
     * the store; a successful restore makes it changeable again, showing what landed.
     */
    @Test fun B2_10i_anUncertainWriteBlocksChangesUntilARestore() = sessionTest {
        store.committed[key("A")] = s(setOf("X"), setOf("X"))
        val a = checkNotNull(session.bind(fence("A")))
        restored(a)
        val cause = IOException("post-commit")
        store.nextWrite = { k, sel -> store.committed[k] = sel; GraphSelectionWriteResult.Uncertain(cause) }
        val w = call { session.apply(a, replace(s(setOf("Y"), setOf("X", "Y")))) }
        idle()
        assertEquals(GraphSelectionApplyResult.Uncertain(cause), w.done())
        assertEquals(GraphSelectionPublication.WriteUncertain(a, cause), session.publication.value)

        var called = false
        val writes = store.writes
        val blocked = call { session.apply(a) { called = true; GraphSelectionChange.Replace(s(setOf("Z"), setOf("X", "Y", "Z"))) } }
        idle()
        assertEquals(GraphSelectionApplyResult.NotReady, blocked.done())
        assertFalse("change not called", called)
        assertEquals("no write", writes, store.writes)

        assertEquals(GraphSelectionRestoreResult.Applied(present("A", s(setOf("Y"), setOf("X", "Y")))), restored(a))
        assertEquals(GraphSelectionPublication.Restored(a, present("A", s(setOf("Y"), setOf("X", "Y")))), session.publication.value)
        val again = call { session.apply(a, replace(s(setOf("Z"), setOf("X", "Y", "Z")))) }
        idle()
        assertEquals("changeable again", GraphSelectionApplyResult.Committed, again.done())
    }

    /** B2-10j: an Unreadable restore is published as such, and apply is NotReady without calling the change or the store. */
    @Test fun B2_10j_anUnreadableRestoreRefusesChanges() = sessionTest {
        val unreadable = GraphSelectionReadResult.Unreadable(GraphSelectionUnreadableReason.MALFORMED_RECORD)
        store.nextConfirm = unreadable
        val a = checkNotNull(session.bind(fence("A")))
        assertEquals(GraphSelectionRestoreResult.Applied(unreadable), restored(a))
        assertEquals(GraphSelectionPublication.Restored(a, unreadable), session.publication.value)
        var called = false
        val w = call { session.apply(a) { called = true; GraphSelectionChange.Replace(s(setOf("D"), setOf("D"))) } }
        idle()
        assertEquals(GraphSelectionApplyResult.NotReady, w.done())
        assertFalse(called)
        assertEquals(0, store.writes)
    }

    // --- session results -----------------------------------------------------------------------------------------------

    /**
     * An Absent restore hands the change null; a Replace commits and publishes the binding's own record. Restore goes through
     * confirmGraphSelection, never readGraphSelection. Before any restore, apply is NotReady without calling the change.
     */
    @Test fun anAbsentRestoreHandsTheChangeNullAndACommitIsPublished() = sessionTest {
        val a = checkNotNull(session.bind(fence("A")))
        var early = false
        val notYet = call { session.apply(a) { early = true; GraphSelectionChange.Unchanged } }
        idle()
        assertEquals(GraphSelectionApplyResult.NotReady, notYet.done())
        assertFalse(early)

        assertEquals(GraphSelectionRestoreResult.Applied(GraphSelectionReadResult.Absent), restored(a))
        assertEquals(GraphSelectionPublication.Restored(a, GraphSelectionReadResult.Absent), session.publication.value)
        val inputs = mutableListOf<GraphSeriesSelection?>()
        val w = call { session.apply(a) { cur -> inputs += cur; GraphSelectionChange.Replace(s(setOf("D"), setOf("D"))) } }
        idle()
        assertEquals(GraphSelectionApplyResult.Committed, w.done())
        assertEquals(listOf<GraphSeriesSelection?>(null), inputs)
        assertEquals(GraphSelectionPublication.Restored(a, present("A", s(setOf("D"), setOf("D")))), session.publication.value)
        assertTrue("restore uses confirm", store.log.none { it.startsWith("read:") })
    }

    /** Unchanged, Rejected and a Replace equal to the current selection write nothing and publish nothing new. */
    @Test fun aChangeThatChangesNothingWritesNothing() = sessionTest {
        store.committed[key("A")] = s(setOf("X"), setOf("X"))
        val a = checkNotNull(session.bind(fence("A")))
        restored(a)
        val before = session.publication.value
        val mark = seen.size
        val results = listOf<(GraphSeriesSelection?) -> GraphSelectionChange>(
            { GraphSelectionChange.Unchanged },
            { GraphSelectionChange.Rejected("not in the catalog") },
            { cur -> GraphSelectionChange.Replace(cur!!) }
        ).map { change -> call { session.apply(a, change) }.also { idle() }.done() }
        assertEquals(
            listOf(
                GraphSelectionApplyResult.Unchanged,
                GraphSelectionApplyResult.Rejected("not in the catalog"),
                GraphSelectionApplyResult.Unchanged
            ),
            results
        )
        assertEquals(0, store.writes)
        assertEquals(before, session.publication.value)
        assertEquals(mark, seen.size)
    }

    /** NotCommitted keeps the restored publication and leaves the session changeable. */
    @Test fun aWriteThatDidNotCommitKeepsTheRestoredSelection() = sessionTest {
        store.committed[key("A")] = s(setOf("X"), setOf("X"))
        val a = checkNotNull(session.bind(fence("A")))
        restored(a)
        val before = session.publication.value
        val cause = IOException("before the delegate")
        store.nextWrite = { _, _ -> GraphSelectionWriteResult.NotCommitted(cause) }
        val w = call { session.apply(a, replace(s(setOf("Y"), setOf("X", "Y")))) }
        idle()
        assertEquals(GraphSelectionApplyResult.NotCommitted(cause), w.done())
        assertEquals(before, session.publication.value)
        val again = call { session.apply(a, replace(s(setOf("Y"), setOf("X", "Y")))) }
        idle()
        assertEquals(GraphSelectionApplyResult.Committed, again.done())
    }

    /** bind itself refuses empty and whitespace-only UIDs without store I/O. */
    @Test fun bindRefusesBlankUidsWithoutAStoreCall() = sessionTest {
        for (uid in listOf("", " ", "\t\n")) {
            val logSize = store.log.size
            val failure = runCatching { session.bind(fence(uid)) }.exceptionOrNull()
            assertTrue("blank UID must be rejected, was $failure", failure is IllegalArgumentException)
            idle()
            assertEquals(logSize, store.log.size)
        }
    }

    /** close retires bindings, drains started work and refuses further work. */
    @Test fun closeDrainsStartedWorkAndClosesAcceptance() = sessionTest {
        store.committed[key("A")] = s(setOf("X"), setOf("X"))
        val a = checkNotNull(session.bind(fence("A")))
        restored(a)
        val held = store.pauseNext("write:A")
        val logStart = store.log.size
        val started = call {
            session.apply(a, replace(s(setOf("Y"), setOf("X", "Y"))))
        }
        idle()
        assertTrue(held.reached.isCompleted)

        var queuedChangeCalled = false
        val queued = call {
            session.apply(a) {
                queuedChangeCalled = true
                GraphSelectionChange.Replace(s(setOf("Z"), setOf("X", "Y", "Z")))
            }
        }
        idle()

        val closing = call { session.close() }
        idle()
        assertEquals(GraphSelectionPublication.Unbound, session.publication.value)
        val mark = seen.size
        assertFalse("close waits for the started command", closing.isCompleted)
        assertEquals(0, store.cancelledInside)
        assertEquals(listOf("write:A"), store.log.drop(logStart))

        held.release.complete(Unit)
        idle()
        closing.done()
        assertEquals(GraphSelectionApplyResult.StaleBinding, started.done())
        assertEquals(GraphSelectionApplyResult.StaleBinding, queued.done())
        assertFalse(queuedChangeCalled)
        assertEquals(listOf("write:A", "wrote:A"), store.log.drop(logStart))
        assertEquals(s(setOf("Y"), setOf("X", "Y")), store.committed[key("A")])
        assertEquals(GraphSelectionPublication.Unbound, session.publication.value)
        assertTrue(seen.drop(mark - 1).uids().isEmpty())

        // The API does not prescribe bind's return/exception after close.
        val rebound = runCatching { session.bind(fence("B")) }.getOrNull()
        assertEquals(GraphSelectionPublication.Unbound, session.publication.value)
        val closedBinding = rebound ?: a
        val logSize = store.log.size
        var called = false
        val w = call {
            session.apply(closedBinding) {
                called = true
                GraphSelectionChange.Replace(s(setOf("D"), setOf("D")))
            }
        }
        val r = call { session.restore(closedBinding) }
        idle()
        assertEquals(GraphSelectionApplyResult.StaleBinding, w.done())
        assertEquals(GraphSelectionRestoreResult.StaleBinding, r.done())
        assertFalse(called)
        assertEquals(logSize, store.log.size)
        assertEquals(GraphSelectionPublication.Unbound, session.publication.value)
        assertTrue(seen.drop(mark - 1).uids().isEmpty())
    }

    /** A call with a retired binding is StaleBinding without calling the change or the store. */
    @Test fun aRetiredBindingIsRefusedWithoutAStoreCall() = sessionTest {
        store.committed[key("A")] = s(setOf("X"), setOf("X"))
        val a = checkNotNull(session.bind(fence("A")))
        restored(a)
        checkNotNull(session.bind(fence("B")))
        val logSize = store.log.size
        var called = false
        val w = call { session.apply(a) { called = true; GraphSelectionChange.Unchanged } }
        val r = call { session.restore(a) }
        idle()
        assertEquals(GraphSelectionApplyResult.StaleBinding, w.done())
        assertEquals(GraphSelectionRestoreResult.StaleBinding, r.done())
        assertFalse(called)
        assertEquals(logSize, store.log.size)
    }
}
