package com.jay.fxi.ui.premium.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphOwnerSource
import com.jay.fxi.data.graph.GraphSelectionBinding
import com.jay.fxi.data.graph.GraphSelectionPublication
import com.jay.fxi.data.graph.GraphSeriesSelectionSession
import com.jay.fxi.data.graph.GraphV2AccessGate
import com.jay.fxi.data.graph.GraphV2Fetching
import com.jay.fxi.data.graph.GraphV2RequestCoordinator
import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionKey
import com.jay.fxi.data.local.GraphSelectionReadResult
import com.jay.fxi.data.local.GraphSelectionRecord
import com.jay.fxi.data.local.GraphSelectionStore
import com.jay.fxi.data.local.GraphSelectionWriteResult
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.dto.GraphV2CatalogResponse
import com.jay.fxi.data.remote.dto.GraphV2TabResponse
import com.jay.fxi.domain.model.GraphSelectionChange
import com.jay.fxi.domain.model.GraphSeriesSelection
import com.jay.fxi.time.AppClock
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 CUT-P3b (`cut_p3b_agreed.r3.md` §3, H01-H18 and D01): the graph screen host over real holders and selection sessions. The
 * store can hold a write or a read; the close and time-event seams record (and, in H07, H10, H11, inject) around the real calls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphScreenHostContractTest {

    private class Pause {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    /** One uid throughout, so records are keyed by tab. */
    private class Store : GraphSelectionStore {
        val committed = mutableMapOf<String, GraphSeriesSelection>()
        val log = mutableListOf<String>()
        private val pauses = mutableMapOf<String, ArrayDeque<Pause>>()

        fun pauseNext(op: String) = Pause().also { pauses.getOrPut(op) { ArrayDeque() }.addLast(it) }

        private suspend fun gate(op: String) {
            val pause = pauses[op]?.removeFirstOrNull() ?: return
            pause.reached.complete(Unit)
            pause.release.await()
        }

        private fun current(key: GraphSelectionKey): GraphSelectionReadResult = committed[key.tab]?.let {
            GraphSelectionReadResult.Present(GraphSelectionRecord(1, key.uid, key.audience, key.tab, it.visibleSeriesIds, it.initializedSeries))
        } ?: GraphSelectionReadResult.Absent

        override suspend fun confirmGraphSelection(key: GraphSelectionKey): GraphSelectionReadResult {
            log += "confirm:${key.tab}"
            gate("confirm:${key.tab}")
            return current(key)
        }

        override suspend fun readGraphSelection(key: GraphSelectionKey): GraphSelectionReadResult {
            log += "read:${key.tab}"
            return current(key)
        }

        override suspend fun writeGraphSelection(key: GraphSelectionKey, selection: GraphSeriesSelection): GraphSelectionWriteResult {
            log += "write:${key.tab}"
            gate("write:${key.tab}")
            committed[key.tab] = selection
            log += "wrote:${key.tab}"
            return GraphSelectionWriteResult.Committed
        }
    }

    /** Marks what it runs, so a call can tell it is on the host dispatcher, and counts what it is handed. */
    private class Marked(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        val inside: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
        /** Every host-scope dispatch: a scheduled creation or close counts even when it never runs. */
        var dispatches = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            delegate.dispatch(context, Runnable {
                val was = inside.get()
                inside.set(true)
                try {
                    block.run()
                } finally {
                    inside.set(was)
                }
            })
        }
    }

    /** An injected Error, told apart from a failing assertion. */
    private class InjectedError(message: String) : Error(message)

    private class Rig(val test: TestScope) {
        val raw = StandardTestDispatcher(test.testScheduler)
        val main = Marked(raw)
        val uncaught = mutableListOf<Throwable>()
        val hostJob = SupervisorJob()
        val hostScope = CoroutineScope(hostJob + main + CoroutineExceptionHandler { _, failure -> uncaught += failure })
        /** Selection-session workers and holder jobs: siblings of the host, so cancelling a host close leaves them running. */
        val workers = CoroutineScope(SupervisorJob() + raw)
        val identity = AuthIdentityFence("u1", 1L)
        val fence = TopicSessionFence(identity, "e1", TopicGrantToken(7L))
        val snapshot = TopicAccessSnapshot.INITIAL
        val store = Store()
        val failures = mutableListOf<Throwable>()
        val created = mutableListOf<Pair<String, GraphV2ScreenStateHolder>>()
        val sessions = mutableMapOf<GraphV2ScreenStateHolder, GraphSeriesSelectionSession>()
        /** create, close and time-event calls in order, each with whether it ran on the host dispatcher. */
        val events = mutableListOf<String>()
        val createFailure = mutableMapOf<String, Throwable>()
        val startFailure = mutableMapOf<String, Throwable>()
        val closeCalls = mutableListOf<GraphV2ScreenStateHolder>()
        /** The real close by default; a test may wait before it or throw after it. */
        var closeHook: suspend (GraphV2ScreenStateHolder) -> Unit = { it.close() }
        val forwarded = mutableListOf<GraphV2ScreenStateHolder>()
        val display = MutableStateFlow(TopicDisplayState.NONE)
        val focus = MutableStateFlow<OwnedTopicFocus?>(null)
        val accessRevisions = MutableStateFlow(0L)
        val uses = SnapshotTopicUseAuthority { snapshot }
        val gate = GraphV2AccessGate({ identity }, { fence }, { snapshot }, { true })
        val coordinator = GraphV2RequestCoordinator(
            fetcher = object : GraphV2Fetching {
                override suspend fun catalog(owner: AuthSnapshot, useAdmitted: () -> Boolean): AuthenticatedHttpResponse<GraphV2CatalogResponse> =
                    error("the host asks nothing")
                override suspend fun tab(owner: AuthSnapshot, key: GraphKey, useAdmitted: () -> Boolean): AuthenticatedHttpResponse<GraphV2TabResponse> =
                    error("the host asks nothing")
            },
            owners = object : GraphOwnerSource {
                override fun currentIdentity(): AuthIdentityFence? = identity
                override suspend fun capture(expected: AuthIdentityFence): AuthSnapshot = error("the host asks nothing")
            },
            currentAccessFence = { fence },
            uses = uses,
            protectedAdmission = { true },
            accessSnapshot = { snapshot },
            scope = workers,
            clock = AppClock { Instant.fromEpochMilliseconds(0L) },
            rateLimitJitter = { Duration.ZERO },
            onEventFailure = { failures += it }
        )
        val host = GraphScreenHost(
            hostScope,
            ::create,
            { failures += it },
            { holder ->
                events += "close:${tabOf(holder)}:${main.inside.get()}"
                closeCalls += holder
                closeHook(holder)
                events += "closed:${tabOf(holder)}"
            },
            { holder ->
                events += "time:${tabOf(holder)}:${main.inside.get()}"
                forwarded += holder
                holder.onTimeEvent()
            }
        )

        fun create(tab: String): GraphV2ScreenStateHolder {
            events += "create:$tab:${main.inside.get()}"
            createFailure.remove(tab)?.let { throw it }
            val session = GraphSeriesSelectionSession(store, GraphSelectionAudience.PREMIUM, tab, workers, raw)
            var first = true
            val holder = GraphV2ScreenStateHolder(
                tab = tab, coordinator = coordinator, selectionSession = session,
                // start() reads the live identity first: a start failure is thrown from there.
                liveIdentity = {
                    if (first) {
                        first = false
                        events += "start:$tab:${main.inside.get()}"
                        startFailure.remove(tab)?.let { throw it }
                    }
                    identity
                },
                display = display, focus = focus, currentAccessFence = { fence }, uses = uses, gate = gate,
                accessRevisions = accessRevisions, scope = workers, dispatcher = raw
            )
            created += tab to holder
            sessions[holder] = session
            return holder
        }

        fun tabOf(holder: GraphV2ScreenStateHolder) = created.first { it.second === holder }.first
        fun createdCount(tab: String) = created.count { it.first == tab }
        fun run() = test.runCurrent()
        fun publication(holder: GraphV2ScreenStateHolder) = sessions.getValue(holder).publication.value

        fun binding(holder: GraphV2ScreenStateHolder): GraphSelectionBinding = when (val p = publication(holder)) {
            is GraphSelectionPublication.Restored -> p.binding
            is GraphSelectionPublication.AwaitingRestore -> p.binding
            is GraphSelectionPublication.WriteUncertain -> p.binding
            GraphSelectionPublication.Unbound -> error("premise: the holder's session is bound")
        }

        /** Starts a write of [visible] on the holder's session and holds it in the store mid-write. */
        fun holdWrite(holder: GraphV2ScreenStateHolder, visible: Set<String> = setOf("x")): Pause {
            val tab = tabOf(holder)
            val pause = store.pauseNext("write:$tab")
            val binding = binding(holder)
            workers.launch { sessions.getValue(holder).apply(binding) { GraphSelectionChange.Replace(GraphSeriesSelection(visible, visible)) } }
            run()
            assertTrue("premise: the $tab write is under way", pause.reached.isCompleted)
            return pause
        }

        fun dispose() {
            hostScope.cancel()
            workers.cancel()
        }
    }

    private fun hostTest(body: suspend TestScope.(Rig) -> Unit): TestResult = runTest {
        val rig = Rig(this)
        try {
            body(rig)
        } finally {
            rig.dispose()
        }
    }

    /** The first [first] comes before the last [then]; both must have happened. */
    private fun assertBefore(events: List<String>, first: String, then: String) {
        val i = events.indexOfFirst { it.startsWith(first) }
        val j = events.indexOfLast { it.startsWith(then) }
        assertTrue("premise: $first and $then both happened: $events", i >= 0 && j >= 0)
        assertTrue("$first before $then: $events", i < j)
    }

    @Test fun H01_openCreatesAndStartsOneHolderPerTab() = hostTest { r ->
        val mount = r.host.open()
        r.run()
        assertEquals(setOf("usd", "jpy", "eur"), r.created.map { it.first }.toSet())
        assertEquals("each tab once", 3, r.created.size)
        val holders = r.created.map { it.second }
        assertEquals(r.created.toMap(), mount.holders.value)
        assertEquals(holders.toSet(), r.host.holders().toSet())
        assertEquals(3, r.host.holders().size)
        for (holder in holders) {
            assertTrue("started: its selection restored", r.publication(holder) is GraphSelectionPublication.Restored)
        }
        assertEquals(setOf("confirm:usd", "confirm:jpy", "confirm:eur"), r.store.log.toSet())
        assertEquals(emptyList<Throwable>(), r.failures)
    }

    @Test fun H02_closeTakesHoldersOutAtOnce_andClosesEachOnce() = hostTest { r ->
        val mount = r.host.open()
        r.run()
        val holders = r.created.map { it.second }
        mount.close()
        assertEquals("out of the host list at once", emptyList<GraphV2ScreenStateHolder>(), r.host.holders())
        assertEquals("out of the mount at once", emptyMap<String, GraphV2ScreenStateHolder>(), mount.holders.value)
        r.run()
        assertEquals(holders.toSet(), r.closeCalls.toSet())
        assertEquals(3, r.closeCalls.size)
        for (holder in holders) assertEquals("really closed", GraphSelectionPublication.Unbound, r.publication(holder))
        mount.close()
        r.run()
        assertEquals("a second close does nothing", 3, r.closeCalls.size)
        assertTrue("closing a mount never ends the host scope", r.hostJob.isActive)
    }

    @Test fun H03_H18_aTabsNewHolderWaitsForItsPreviousClose_andRestoresWhatWasWritten() = hostTest { r ->
        val a = r.host.open()
        r.run()
        val oldUsd = a.holders.value.getValue("usd")
        val write = r.holdWrite(oldUsd, setOf("x"))
        a.close()
        r.run()
        val b = r.host.open()
        r.run()
        assertEquals("jpy and eur go ahead", setOf("jpy", "eur"), b.holders.value.keys)
        assertEquals("no second usd while the first still closes", 1, r.createdCount("usd"))
        assertFalse("premise: the old usd close is still waiting", r.events.contains("closed:usd"))
        write.release.complete(Unit)
        r.run()
        val newUsd = b.holders.value.getValue("usd")
        assertNotSame(oldUsd, newUsd)
        assertEquals(2, r.createdCount("usd"))
        assertBefore(r.events, "closed:usd", "create:usd")
        assertBefore(r.store.log, "wrote:usd", "confirm:usd")
        val restored = r.publication(newUsd) as GraphSelectionPublication.Restored
        assertEquals("it reads the selection written last", setOf("x"),
            (restored.read as GraphSelectionReadResult.Present).record.visibleSeriesIds)
    }

    @Test fun H04_openingClosesTheOpenMountFirst() = hostTest { r ->
        val a = r.host.open()
        r.run()
        val old = r.created.map { it.second }
        val b = r.host.open()
        assertEquals("a's holders leave the mount at once", emptyMap<String, GraphV2ScreenStateHolder>(), a.holders.value)
        assertEquals("and the host list", emptyList<GraphV2ScreenStateHolder>(), r.host.holders())
        r.run()
        assertEquals(old.toSet(), r.closeCalls.toSet())
        val new = b.holders.value.values.toSet()
        assertEquals(3, new.size)
        assertTrue("new holders", new.none { it in old })
        assertEquals(new, r.host.holders().toSet())
        for (tab in listOf("usd", "jpy", "eur")) {
            assertBefore(r.events, "closed:$tab", "create:$tab")
        }
    }

    @Test fun H05_aMountClosedWhileWaitingCreatesNothing_andTheNextStillWaits() = hostTest { r ->
        val a = r.host.open()
        r.run()
        val write = r.holdWrite(a.holders.value.getValue("usd"))
        a.close()
        val b = r.host.open()
        r.run()
        assertEquals(setOf("jpy", "eur"), b.holders.value.keys)
        b.close()
        val c = r.host.open()
        r.run()
        assertEquals("c still waits for a's usd", setOf("jpy", "eur"), c.holders.value.keys)
        write.release.complete(Unit)
        r.run()
        assertEquals(setOf("usd", "jpy", "eur"), c.holders.value.keys)
        assertEquals("closed b made no usd", 2, r.createdCount("usd"))
        assertEquals(emptyMap<String, GraphV2ScreenStateHolder>(), b.holders.value)
        for (tab in listOf("jpy", "eur")) {
            val lastClosed = r.events.indexOfLast { it == "closed:$tab" }
            val lastCreated = r.events.indexOfLast { it.startsWith("create:$tab") }
            assertTrue("c's $tab only after b's $tab close, the latest, returned: ${r.events}", lastClosed in 0 until lastCreated)
        }
    }

    @Test fun H06_theTimeEventReachesEachOpenHolderOnce_andNoClosingOrMissingOne() = hostTest { r ->
        r.host.onTimeEvent()
        assertEquals("nothing open, nothing reached", emptyList<GraphV2ScreenStateHolder>(), r.forwarded)
        val a = r.host.open()
        r.run()
        r.host.onTimeEvent()
        assertEquals(a.holders.value.values.toSet(), r.forwarded.toSet())
        assertEquals(3, r.forwarded.size)
        val write = r.holdWrite(a.holders.value.getValue("usd"))
        a.close()
        val b = r.host.open()
        r.run()
        r.forwarded.clear()
        r.host.onTimeEvent()
        assertEquals("b's jpy and eur only: not a's closing usd, not b's missing usd", b.holders.value.values.toSet(), r.forwarded.toSet())
        assertEquals(2, r.forwarded.size)
        write.release.complete(Unit)
        r.run()
    }

    @Test fun H07_createAndCloseFailuresAreReportedOnce_andAFailedCloseKeepsItsTabFromComingBack() = hostTest { r ->
        val createFailure = IllegalStateException("create")
        r.createFailure["jpy"] = createFailure
        val a = r.host.open()
        r.run()
        assertEquals(listOf<Throwable>(createFailure), r.failures)
        assertEquals("the other tabs go ahead", setOf("usd", "eur"), a.holders.value.keys)
        val closeFailure = IOException("after close")
        r.closeHook = { holder ->
            holder.close()
            if (r.tabOf(holder) == "usd") throw closeFailure
        }
        a.close()
        r.run()
        assertEquals(listOf(createFailure, closeFailure), r.failures)
        r.closeHook = { it.close() }
        val b = r.host.open()
        r.run()
        assertEquals("usd does not come back; jpy and eur do", setOf("jpy", "eur"), b.holders.value.keys)
        assertEquals(1, r.createdCount("usd"))
        assertEquals("the abandoned usd creation has ended", emptyList<Job>(), r.hostJob.children.toList())
        val c = r.host.open()
        r.run()
        assertEquals("later mounts keep usd out too", setOf("jpy", "eur"), c.holders.value.keys)
        assertEquals(1, r.createdCount("usd"))
        assertEquals("nothing else reported", 2, r.failures.size)
        assertEquals(emptyList<Throwable>(), r.uncaught)
    }

    @Test fun H07c_aCloseErrorIsNotReported_propagates_andKeepsItsTabFromComingBack() = hostTest { r ->
        val a = r.host.open()
        r.run()
        val closeError = InjectedError("after close")
        r.closeHook = { holder ->
            holder.close()
            if (r.tabOf(holder) == "usd") throw closeError
        }
        a.close()
        r.run()
        assertEquals("an Error is not reported", emptyList<Throwable>(), r.failures)
        assertEquals("it propagates to the host scope", listOf<Throwable>(closeError), r.uncaught)
        assertTrue("the supervised host scope survives", r.hostJob.isActive)
        r.closeHook = { it.close() }
        val b = r.host.open()
        r.run()
        assertEquals("usd does not come back; jpy and eur do", setOf("jpy", "eur"), b.holders.value.keys)
        assertEquals(1, r.createdCount("usd"))
        assertEquals(emptyList<Throwable>(), r.failures)
    }

    @Test fun H08_createAndCloseRunOnTheHostDispatcher_andTheTimeEventStaysOnIts() = hostTest { r ->
        val mount = r.host.open()
        r.run()
        r.hostScope.launch { r.host.onTimeEvent() }
        r.run()
        mount.close()
        r.run()
        val calls = r.events.filter {
            it.startsWith("create:") || it.startsWith("start:") || it.startsWith("close:") || it.startsWith("time:")
        }
        assertEquals(12, calls.size)
        assertTrue("all on the host dispatcher: $calls", calls.all { it.endsWith(":true") })
    }

    @Test fun H09_aCloseBeforeTheFirstDispatchCreatesNothing() = hostTest { r ->
        val mount = r.host.open()
        mount.close()
        r.run()
        assertEquals(emptyList<Pair<String, GraphV2ScreenStateHolder>>(), r.created)
        assertEquals(emptyMap<String, GraphV2ScreenStateHolder>(), mount.holders.value)
        assertEquals(emptyList<GraphV2ScreenStateHolder>(), r.host.holders())
        assertEquals(emptyList<GraphV2ScreenStateHolder>(), r.closeCalls)
    }

    @Test fun H10_aStartFailureIsCleanedUp_andItsTabWaitsForThatClose() = hostTest { r ->
        val startFailure = IllegalStateException("start")
        r.startFailure["usd"] = startFailure
        val cleanupGate = CompletableDeferred<Unit>()
        r.closeHook = { holder ->
            if (r.tabOf(holder) == "usd" && r.createdCount("usd") == 1) cleanupGate.await()
            holder.close()
        }
        val a = r.host.open()
        r.run()
        val failed = r.created.first { it.first == "usd" }.second
        assertEquals("not registered", setOf("jpy", "eur"), a.holders.value.keys)
        assertFalse(failed in r.host.holders())
        assertEquals("cleaned up once", listOf(failed), r.closeCalls.filter { it === failed })
        a.close()
        val b = r.host.open()
        r.run()
        assertEquals("no usd while the cleanup close waits", setOf("jpy", "eur"), b.holders.value.keys)
        assertEquals(1, r.createdCount("usd"))
        cleanupGate.complete(Unit)
        r.run()
        assertEquals(setOf("usd", "jpy", "eur"), b.holders.value.keys)
        val usd = b.holders.value.getValue("usd")
        assertTrue("the new usd restored", r.publication(usd) is GraphSelectionPublication.Restored)
        assertBefore(r.events, "closed:usd", "create:usd")
        assertEquals("reported once", listOf<Throwable>(startFailure), r.failures)
    }

    @Test fun H11_aCancelledCloseKeepsItsTabFromComingBack_evenOnceTheWriteEnds() = hostTest { r ->
        var usdClose: Job? = null
        r.closeHook = { holder ->
            if (r.tabOf(holder) == "usd") usdClose = currentCoroutineContext()[Job]
            holder.close()
        }
        val a = r.host.open()
        r.run()
        val write = r.holdWrite(a.holders.value.getValue("usd"))
        a.close()
        r.run()
        val close = checkNotNull(usdClose) { "premise: the usd close ran" }
        assertTrue("premise: the real close is waiting on the write", close.isActive && !r.events.contains("closed:usd"))
        close.cancel()
        r.run()
        assertTrue(close.isCancelled)
        assertTrue("host scope alive", r.hostJob.isActive)
        val b = r.host.open()
        r.run()
        assertEquals("usd does not come back; the others do", setOf("jpy", "eur"), b.holders.value.keys)
        write.release.complete(Unit)
        r.run()
        assertTrue("premise: the session worker lived and finished the write", r.store.log.contains("wrote:usd"))
        assertEquals("still no usd", setOf("jpy", "eur"), b.holders.value.keys)
        assertEquals(1, r.createdCount("usd"))
        assertEquals("the abandoned usd creation has ended", emptyList<Job>(), r.hostJob.children.toList())
        val c = r.host.open()
        r.run()
        assertEquals("later mounts keep usd out too", setOf("jpy", "eur"), c.holders.value.keys)
        assertEquals(1, r.createdCount("usd"))
        assertEquals("cancellation is not reported", emptyList<Throwable>(), r.failures)
        assertEquals(emptyList<Throwable>(), r.uncaught)
    }

    @Test fun H11b_aCloseThatNeverRanPermitsNoNewHolder() = hostTest { r ->
        val a = r.host.open()
        r.run()
        a.close()
        val closes = r.hostJob.children.toList()
        assertEquals("premise: three closes scheduled", 3, closes.size)
        closes.forEach { it.cancel() }
        r.run()
        assertEquals("no close ran", emptyList<GraphV2ScreenStateHolder>(), r.closeCalls)
        assertTrue(r.hostJob.isActive)
        val b = r.host.open()
        r.run()
        assertEquals("an unexecuted close is not a normal close", emptyMap<String, GraphV2ScreenStateHolder>(), b.holders.value)
        assertEquals(3, r.created.size)
        assertEquals(emptyList<Throwable>(), r.failures)
        assertEquals(emptyList<Throwable>(), r.uncaught)
    }

    @Test fun H12a_aCancelledHostBeforeTheFirstDispatchCreatesNothing_andRejectsOpen() = hostTest { r ->
        val a = r.host.open()
        r.hostScope.cancel()
        r.run()
        assertEquals(emptyList<Pair<String, GraphV2ScreenStateHolder>>(), r.created)
        repeat(2) {
            val scheduled = r.main.dispatches
            val rejected = r.host.open()
            assertEquals("open on an ended host schedules nothing", scheduled, r.main.dispatches)
            r.run()
            assertEquals(emptyMap<String, GraphV2ScreenStateHolder>(), rejected.holders.value)
            rejected.close()
            rejected.close()
            assertEquals("closing the rejected mount schedules nothing", scheduled, r.main.dispatches)
            r.run()
            assertEquals(emptyMap<String, GraphV2ScreenStateHolder>(), rejected.holders.value)
        }
        assertEquals(emptyMap<String, GraphV2ScreenStateHolder>(), a.holders.value)
        assertEquals(emptyList<Pair<String, GraphV2ScreenStateHolder>>(), r.created)
        assertEquals(emptyList<Throwable>(), r.failures)
        assertEquals(emptyList<Throwable>(), r.uncaught)
    }

    @Test fun H12b_aCancelledHostWhileATabWaitsCreatesNothing_andOpenTakesTheOpenMountOut() = hostTest { r ->
        val a = r.host.open()
        r.run()
        val write = r.holdWrite(a.holders.value.getValue("usd"))
        a.close()
        val b = r.host.open()
        r.run()
        assertEquals(setOf("jpy", "eur"), b.holders.value.keys)
        r.hostScope.cancel()
        write.release.complete(Unit)
        r.run()
        assertEquals("no usd after the host ended", 1, r.createdCount("usd"))
        val rejected = r.host.open()
        r.run()
        assertEquals(emptyMap<String, GraphV2ScreenStateHolder>(), rejected.holders.value)
        assertEquals("the open mount is taken out", emptyMap<String, GraphV2ScreenStateHolder>(), b.holders.value)
        assertEquals(emptyList<GraphV2ScreenStateHolder>(), r.host.holders())
        assertEquals(1, r.createdCount("usd"))
        assertEquals(emptyList<Throwable>(), r.failures)
        assertEquals(emptyList<Throwable>(), r.uncaught)
    }

    @Test fun H12c_aHostCancelledAfterThePreviousCloseReturnedCreatesNothing() = hostTest { r ->
        val a = r.host.open()
        r.run()
        val write = r.holdWrite(a.holders.value.getValue("usd"))
        r.closeHook = { holder ->
            holder.close()
            if (r.tabOf(holder) == "usd") r.hostScope.cancel()
        }
        a.close()
        val b = r.host.open()
        r.run()
        assertEquals(setOf("jpy", "eur"), b.holders.value.keys)
        write.release.complete(Unit)
        r.run()
        assertTrue("premise: the usd close returned normally", r.events.contains("closed:usd"))
        assertEquals("no usd after the host ended", 1, r.createdCount("usd"))
        assertEquals(emptyList<Throwable>(), r.failures)
        assertEquals(emptyList<Throwable>(), r.uncaught)
    }

    @Test fun H13_closingAnAutoClosedMountAgainLeavesTheNewOneAlone() = hostTest { r ->
        val a = r.host.open()
        r.run()
        val b = r.host.open()
        r.run()
        val map = b.holders.value
        val list = r.host.holders()
        val closes = r.closeCalls.size
        a.close()
        r.run()
        assertSame(map, b.holders.value)
        assertEquals(list, r.host.holders())
        assertEquals(closes, r.closeCalls.size)
        assertEquals(3, closes)
    }

    @Test fun H14_eachTabsPreviousCloseCarriesThroughAClosedMount() = hostTest { r ->
        val a = r.host.open()
        r.run()
        val usdWrite = r.holdWrite(a.holders.value.getValue("usd"))
        val eurWrite = r.holdWrite(a.holders.value.getValue("eur"))
        a.close()
        val b = r.host.open()
        r.run()
        assertEquals(setOf("jpy"), b.holders.value.keys)
        val jpyWrite = r.holdWrite(b.holders.value.getValue("jpy"), setOf("y"))
        val c = r.host.open()
        r.run()
        assertEquals("c waits for b's jpy close, the latest one, not a's", emptySet<String>(), c.holders.value.keys)
        assertEquals("premise: only a's jpy close has returned", 1, r.events.count { it == "closed:jpy" })
        jpyWrite.release.complete(Unit)
        r.run()
        assertEquals(setOf("jpy"), c.holders.value.keys)
        val cJpy = c.holders.value.getValue("jpy")
        assertEquals("c's jpy reads b's write", setOf("y"),
            ((r.publication(cJpy) as GraphSelectionPublication.Restored).read as GraphSelectionReadResult.Present).record.visibleSeriesIds)
        eurWrite.release.complete(Unit)
        r.run()
        assertEquals(setOf("jpy", "eur"), c.holders.value.keys)
        usdWrite.release.complete(Unit)
        r.run()
        assertEquals(setOf("usd", "jpy", "eur"), c.holders.value.keys)
        assertEquals(emptyMap<String, GraphV2ScreenStateHolder>(), b.holders.value)
        assertEquals("usd: a and c", 2, r.createdCount("usd"))
        assertEquals("eur: a and c", 2, r.createdCount("eur"))
        assertEquals("jpy: a, b and c", 3, r.createdCount("jpy"))
    }

    @Test fun H15_theMountAndTheHostListHoldTheSameHolders_andCopiesStayPut() = hostTest { r ->
        val a = r.host.open()
        r.run()
        val write = r.holdWrite(a.holders.value.getValue("usd"))
        a.close()
        val b = r.host.open()
        r.run()
        val map = b.holders.value
        val list = r.host.holders()
        assertEquals(map.values.toSet(), list.toSet())
        assertEquals(2, list.size)
        write.release.complete(Unit)
        r.run()
        assertEquals("an earlier map copy is unchanged", setOf("jpy", "eur"), map.keys)
        assertEquals("an earlier list copy is unchanged", 2, list.size)
        assertEquals(b.holders.value.values.toSet(), r.host.holders().toSet())
        assertEquals(3, r.host.holders().size)
        val full = r.host.holders()
        b.close()
        assertEquals(emptyMap<String, GraphV2ScreenStateHolder>(), b.holders.value)
        assertEquals("copies survive a close", 3, full.size)
        assertEquals(2, list.size)
    }

    @Test fun H16_cancellationAndErrorFromCreateOrStartAreNotReported_andAStartedHolderIsCleanedUp() = hostTest { r ->
        val cancelled = CancellationException("create")
        val error = InjectedError("create")
        val startCancelled = CancellationException("start")
        val startError = InjectedError("start")
        r.createFailure["usd"] = cancelled
        r.createFailure["jpy"] = error
        r.startFailure["eur"] = startCancelled
        val a = r.host.open()
        val aJobs = r.hostJob.children.toList()
        assertEquals("premise: one creation job per tab", 3, aJobs.size)
        r.run()
        assertTrue("each cancellation and Error ended its creation job, not swallowed", aJobs.all { it.isCancelled })
        assertEquals(emptyMap<String, GraphV2ScreenStateHolder>(), a.holders.value)
        val eur = r.created.single { it.first == "eur" }.second
        assertEquals("the started eur holder is cleaned up", listOf(eur), r.closeCalls)
        assertEquals("its session is closed: a closed session binds nothing", null, r.sessions.getValue(eur).bind(r.identity))
        assertEquals(emptyList<Throwable>(), r.failures)
        assertEquals("the Error propagates to the host scope", listOf<Throwable>(error), r.uncaught)
        a.close()
        r.startFailure["usd"] = startError
        val b = r.host.open()
        r.run()
        val usd = r.created.last { it.first == "usd" }.second
        assertTrue("the started usd holder is cleaned up", usd in r.closeCalls)
        assertEquals("its session is closed", null, r.sessions.getValue(usd).bind(r.identity))
        assertEquals("jpy is created now; eur follows its normal cleanup close", setOf("jpy", "eur"), b.holders.value.keys)
        assertEquals(emptyList<Throwable>(), r.failures)
        assertEquals(listOf<Throwable>(error, startError), r.uncaught)
    }

    /** H17: the host's source touches no activation and no coordinator or recorder; its seams default to the real calls. */
    @Test fun H17_theHostManagesLifetimesOnly() {
        val text = File("src/main/java/com/jay/fxi/ui/premium/graph/GraphScreenHost.kt").readText()
        val code = text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")
        for (name in listOf("onActivated", "onDeactivated", "coordinator", "recorder", "GraphV2RequestCoordinator", "GraphRecorder")) {
            assertFalse("the host never touches $name", Regex("""\b$name\b""").containsMatchIn(code))
        }
        assertTrue("the default close is the real close",
            Regex("""closeHolder\s*:\s*suspend\s*\(\s*GraphV2ScreenStateHolder\s*\)\s*->\s*Unit\s*=\s*\{\s*it\.close\(\)\s*}""").containsMatchIn(code))
        assertTrue("the default time event is the real one",
            Regex("""forwardTimeEvent\s*:\s*\(\s*GraphV2ScreenStateHolder\s*\)\s*->\s*Unit\s*=\s*\{\s*it\.onTimeEvent\(\)\s*}""").containsMatchIn(code))
    }

    /**
     * D01: outside the test source sets only the topic owner constructs the host (S4 CUT-CC5-2), once, and nothing constructs
     * a mount but the host or wires either into DI; only the host and the owner name the host, only the host names a mount.
     */
    @Test fun D01_dormant() {
        val src = File("src")
        val all = src.walkTopDown()
            .onEnter { it == src || it.parentFile != src || it.name !in setOf("test", "androidTest") }
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(src).invariantSeparatorsPath to it.readText() }
        val code = all.mapValues { (_, text) -> text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "") }
        val host = "main/java/com/jay/fxi/ui/premium/graph/GraphScreenHost.kt"
        assertTrue("premise: the scan sees the host", host in all && all.size > 100)
        assertTrue("premise: the scan reaches the benchmark source set", all.keys.any { it.startsWith("benchmark/") })
        val owner = "main/java/com/jay/fxi/data/remote/TopicRuntimeOwner.kt"
        assertEquals("only the host file and the owner name GraphScreenHost", setOf(host, owner),
            code.filter { (_, text) -> Regex("""\bGraphScreenHost\b""").containsMatchIn(text) }.keys)
        assertEquals("only the host file names GraphScreenMount", setOf(host),
            code.filter { (_, text) -> Regex("""\bGraphScreenMount\b""").containsMatchIn(text) }.keys)
        assertEquals("the owner constructs the host once", mapOf(owner to 1),
            code.filterKeys { it != host }.mapValues { (_, t) -> Regex("""\bGraphScreenHost\s*\(""").findAll(t).count() }.filterValues { it > 0 })
        assertFalse("the owner carries no DI annotation on the host", Regex("""@(?:[A-Za-z_][\w.]*\.)?(Provides|Binds|Module)\b""").containsMatchIn(code.getValue(owner)))
        val text = code.getValue(host)
        assertFalse("no DI annotation", Regex("""@(?:[A-Za-z_][\w.]*\.)?(Inject|AssistedInject|Singleton|Module|Provides|Binds|InstallIn|EntryPoint)\b""").containsMatchIn(text))
        assertFalse("the host file constructs no host", Regex("""\bGraphScreenHost\s*\(""").findAll(text).count() > 1)
    }
}
