package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.free.InstallSeedSource
import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphOwnerSource
import com.jay.fxi.data.graph.GraphRuntimeAssembly
import com.jay.fxi.data.graph.GraphV2Fetching
import com.jay.fxi.data.graph.LateBound
import com.jay.fxi.data.graph.ProcessGraphBuilder
import com.jay.fxi.data.graph.ProcessGraphParts
import com.jay.fxi.data.remote.C4OwnerHarness.Companion.F1
import com.jay.fxi.data.remote.C4OwnerHarness.Companion.TETHER
import com.jay.fxi.data.remote.C4OwnerHarness.Companion.tetherFrame
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.time.AppClock
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 CUT-CC4b contract: the topic owner builds the process graph early, hands its sink and bridge to the runtime
 * factory, binds the runtime to the starter and starts the graph once the install seed is in memory (`cut_cc4b_agreed.r1.md`
 * §2). Every row joins the production owner, factory, runtime, session, starter and assembly through [C4OwnerHarness] and a
 * builder that constructs a real [GraphRuntimeAssembly] over fakes for the server, auth and clock. Holders and activation stay
 * at zero, so no row may see a graph request.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TopicRuntimeOwnerGraphContractTest {

    /** Runs nothing until [release]: an install seed read held mid-flight. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val held = mutableListOf<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { held += block }
        fun release() {
            val blocks = held.toList()
            held.clear()
            blocks.forEach { it.run() }
        }
    }

    /**
     * A builder over a real assembly on the owner's Main, its parts noted for the rows: the slots it was handed, the runtime
     * scopes that existed when it ran, every graph request, every time event and every event failure. [failWith] makes the
     * build throw before anything is built; [presetPermit] binds the permit slot inside the build, so the owner's later binding
     * fails.
     */
    private class GraphRig(
        test: TestScope,
        seedIo: CoroutineDispatcher? = null,
        private val seedRead: () -> String = { "seed-1" },
        /** A real disk store at this folder, with write ports that count preparations; none by default. */
        private val disk: java.io.File? = null
    ) {
        lateinit var h: C4OwnerHarness
        val scheduler = test.testScheduler
        val held = (seedIo as? HeldDispatcher)
        val seeds = InstallSeedSource({ seedReads++; seedRead() }, seedIo ?: StandardTestDispatcher(scheduler))
        var seedReads = 0
        var builds = 0
        var scopesAtBuild = -1
        var failWith: Throwable? = null
        var presetPermit = false
        val requests = mutableListOf<String>()
        val eventFailures = mutableListOf<Throwable>()
        var timeEvents = 0
        var assembly: GraphRuntimeAssembly? = null
        var permit: LateBound<() -> TopicGraphRecoveryPermit?>? = null
        val reports = mutableListOf<Throwable>()
        var prepares = 0
        /** The report itself throws after noting the failure. */
        var reportFails = false

        val builder = ProcessGraphBuilder { permit, seed, timeEvent, parent ->
            builds++
            scopesAtBuild = h.scopes.size
            failWith?.let { throw it }
            this.permit = permit
            if (presetPermit) permit.set { null }
            val built = GraphRuntimeAssembly(
                accessSnapshot = { snapshot() },
                accessRevisions = h.fakeIssuer.revisions,
                liveIdentity = { h.live },
                uses = h.authority,
                protectedAdmission = { true },
                fetcher = object : GraphV2Fetching {
                    override suspend fun catalog(owner: AuthSnapshot, useAdmitted: () -> Boolean) =
                        requests.add("catalog").let { awaitCancellation() }

                    override suspend fun tab(owner: AuthSnapshot, key: GraphKey, useAdmitted: () -> Boolean) =
                        requests.add("tab").let { awaitCancellation() }
                },
                owners = object : GraphOwnerSource {
                    override fun currentIdentity(): AuthIdentityFence? = h.live
                    override suspend fun capture(expected: AuthIdentityFence) =
                        AuthSnapshot(expected.uid, expected.authGeneration, "token")
                },
                cachePorts = { gate ->
                    disk?.let { dir ->
                        com.jay.fxi.data.graph.GraphV2CachePorts(
                            com.jay.fxi.data.graph.FileGraphV2DiskStore({ dir }, com.jay.fxi.data.graph.JsonGraphV2EnvelopeCodec(),
                                com.jay.fxi.data.graph.DefaultGraphV2AtomicFileIo(), StandardTestDispatcher(scheduler)),
                            gate, onSeedDiagnostic = {},
                            writePorts = com.jay.fxi.data.graph.GraphV2WritePorts(
                                prepareWrite = { _, _ -> prepares++; error("CC4b: no write is prepared with no holder") },
                                onPreparationBlocked = {}, onWriteDiagnostic = {}
                            )
                        )
                    }
                },
                main = h.main,
                parent = parent,
                clock = AppClock { NOON + scheduler.currentTime.milliseconds },
                rateLimitJitter = { seed.require(); Duration.ZERO },
                onEventFailure = { eventFailures += it },
                recoveryPermit = { permit.require()() },
                timeEvent = { timeEvents++; timeEvent() }.also { timeEventHandedOver = timeEvent }
            )
            assembly = built
            ProcessGraphParts(built, seeds) { tab, display, focus, scope ->
                holderArgs += Triple(display, focus, scope)
                holderFailure?.let { (failing, failure) -> if (failing == tab) throw failure }
                val session = com.jay.fxi.data.graph.GraphSeriesSelectionSession(
                    selections, com.jay.fxi.data.local.GraphSelectionAudience.PREMIUM, tab, scope, h.main)
                com.jay.fxi.ui.premium.graph.GraphV2ScreenStateHolder(
                    tab, built.coordinator, session, { h.live }, display, focus, built.fences.current, h.authority, built.gate,
                    h.fakeIssuer.revisions, scope, h.main, recorder = built.recorder
                ).also { holders += it }
            }
        }

        /** What each holder build was handed: display, focus, scope. */
        val holderArgs = mutableListOf<Triple<Any, Any, Any>>()
        /** Every holder the rig built, in order. */
        val holders = mutableListOf<com.jay.fxi.ui.premium.graph.GraphV2ScreenStateHolder>()
        /** Holders the host forwarded a time event to. */
        val timeForwarded = mutableListOf<com.jay.fxi.ui.premium.graph.GraphV2ScreenStateHolder>()
        /** When set, building a holder for this tab throws it. */
        var holderFailure: Pair<String, Throwable>? = null
        /** When set, making the host throws it. */
        var hostFailure: Throwable? = null
        var hostsMade = 0
        /** The last host made, whether or not it was published. */
        var lastHost: com.jay.fxi.ui.premium.graph.GraphScreenHost? = null
        /** The time event the builder was handed, as the recovery events would call it. */
        var timeEventHandedOver: (() -> Unit)? = null
        val newGraphHost: (kotlinx.coroutines.CoroutineScope, (String) -> com.jay.fxi.ui.premium.graph.GraphV2ScreenStateHolder, (Throwable) -> Unit) ->
            com.jay.fxi.ui.premium.graph.GraphScreenHost = { scope, create, onFailure ->
            hostsMade++
            hostFailure?.let { throw it }
            com.jay.fxi.ui.premium.graph.GraphScreenHost(scope, create, onFailure, forwardTimeEvent = { timeForwarded += it })
                .also { lastHost = it }
        }
        val selections = object : com.jay.fxi.data.local.GraphSelectionStore {
            override suspend fun readGraphSelection(key: com.jay.fxi.data.local.GraphSelectionKey) =
                com.jay.fxi.data.local.GraphSelectionReadResult.Absent
            override suspend fun writeGraphSelection(key: com.jay.fxi.data.local.GraphSelectionKey, selection: com.jay.fxi.domain.model.GraphSeriesSelection) =
                com.jay.fxi.data.local.GraphSelectionWriteResult.Committed
            override suspend fun confirmGraphSelection(key: com.jay.fxi.data.local.GraphSelectionKey) =
                com.jay.fxi.data.local.GraphSelectionReadResult.Absent
        }

        fun snapshot() = TopicAccessSnapshot.INITIAL.copy(
            revision = h.fakeIssuer.revisions.value,
            facts = TopicAccessFacts.NONE.copy(token = h.fakeIssuer.grant?.grant, tokenStanding = h.fakeIssuer.grant != null, userBlocks = emptySet())
        )

        fun harness(
            test: TestScope,
            online: Boolean = true,
            failFirstCreate: Boolean = false,
            failFenceObserve: Boolean = false,
            initialForeground: Boolean? = null,
            captureUncaught: Boolean = false,
            live: AuthIdentityFence? = C4OwnerHarness.U1,
            graphCutover: (() -> com.jay.fxi.data.local.AppGraphCacheCutover)? = null
        ) =
            C4OwnerHarness(
                test, live = live, online = online, failFirstCreate = failFirstCreate, failFenceObserve = failFenceObserve,
                graphBuilder = builder, reportGraph = { reports += it; if (reportFails) error("report failed") },
                initialForeground = initialForeground, newGraphHost = newGraphHost, captureUncaught = captureUncaught,
                graphCutover = graphCutover
            ).also { h = it; it.tabs.stored["u1"] = FreeTab.TETHER }

        /** Closes the assembly on the owner's Main and drains, so no recovery-event timer outlives the row. */
        fun close() {
            val a = assembly ?: return
            kotlinx.coroutines.CoroutineScope(h.main).launch { a.close() }
            h.settle(100)
        }

        fun held(): Int = checkNotNull(assembly).recorder.state.value.pending.inputs.size
        /**
         * The foreground the recovery events were last told; null before any. Read from the events, since the harness's Main
         * holds no virtual-time delay for their ticks.
         */
        fun eventsForeground(): Boolean? = checkNotNull(assembly).events.let { e ->
            e.javaClass.getDeclaredField("foreground").apply { isAccessible = true }.get(e) as Boolean?
        }
        /** The Connection the recovery events took from the session's permit as their baseline; null before any. */
        fun eventsConnection(): Any? = checkNotNull(assembly).events.let { e ->
            e.javaClass.getDeclaredField("connection").apply { isAccessible = true }.get(e)
        }
        /** The rates of the observations waiting in the recorder, in order. */
        fun heldRates(): List<Double> = checkNotNull(assembly).recorder.state.value.pending.inputs
            .filterIsInstance<TopicGraphInput.Observations>()
            .flatMap { o -> o.candidates.filterIsInstance<TopicGraphCandidate.Quote>().map { it.rate } }
        fun assemblyEnded(): Boolean = checkNotNull(assembly).let { a ->
            (GraphRuntimeAssembly::class.java.getDeclaredField("assemblyJob").apply { isAccessible = true }.get(a) as kotlinx.coroutines.Job).isCompleted
        }
    }

    private companion object {
        val NOON: Instant = Instant.parse("2026-10-09T03:00:00Z")
    }

    /**
     * The runtime the owner created, reached through its consumer's tab selection, which the owner binds to it. Read from the
     * owner's field, so it also works after a topic failure left the owner not ready.
     */
    private fun C4OwnerHarness.runtime(): TopicRuntime {
        val consumer = checkNotNull(TopicRuntimeOwner::class.java.getDeclaredField("processConsumer")
            .apply { isAccessible = true }.get(owner)) { "fixture: no consumer was installed" }
        val selectTab = com.jay.fxi.ui.premium.PremiumTopicConsumer::class.java.getDeclaredField("selectTab")
            .apply { isAccessible = true }.get(consumer)
        (selectTab as? kotlin.jvm.internal.CallableReference)?.boundReceiver?.let { if (it is TopicRuntime) return it }
        return selectTab.javaClass.declaredFields.firstNotNullOf { f ->
            f.isAccessible = true
            f.get(selectTab) as? TopicRuntime
        }
    }

    private fun TopicRuntime.part(name: String): Any? =
        TopicRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)

    private val probe = TopicGraphInput.Continuity(
        1L, TopicGraphEventKind.INITIAL, null, emptySet(), emptySet(), TopicGraphAuthority(Any(), F1, 1L, null), null, 0L
    )

    /** A live socket with the tether topic acknowledged, then one tether frame: an observation for the graph. */
    private fun C4OwnerHarness.liveFrame(rate: Double) {
        if (wire.requests.isEmpty()) error("fixture: no socket")
        deliverAck()
        wire.deliver(tetherFrame(rate))
        settle(100)
    }

    private var acked = false
    private fun C4OwnerHarness.deliverAck() {
        if (acked) return
        wire.open()
        settle(3_000)
        val subscribe = subscribes.last()
        wire.deliver(C4OwnerHarness.ack(subscribe.requestId, listOf(TETHER), emptyMap()))
        settle(100)
        acked = true
    }

    /**
     * CC4b-O01: the owner builds the graph before the runtime exists, hands the factory its sink and bridge — the session's
     * graph input and the fan-out's second grant connection, each detachable — binds the runtime and, with the seed in memory,
     * starts the graph: observations the session sees reach the recorder, the bridge publishes the delivered grant, and the
     * recovery events are told the foreground and later take the session's Connection through the permit. No graph request, no failure, and the topic is ready.
     */
    @Test
    fun `CC4b-O01 the graph is built first, wired into the runtime and started`() = runTest {
        val g = GraphRig(this)
        val h = g.harness(this)
        h.owner.start()
        h.settle(1_000)
        assertNull("CC4b-O01 fixture: handed over with no Connection yet (no foreground, no socket)", g.eventsConnection())
        h.foreground(true)
        h.settle(1_000)
        assertNotNull("CC4b-O01 the session's later Connection reaches the recovery events through the permit", g.eventsConnection())
        h.owner.requireReady()
        assertEquals("CC4b-O01 built once, before any runtime", 1 to 0, g.builds to g.scopesAtBuild)
        val runtime = h.runtime()
        assertTrue("CC4b-O01 the session's graph input is installed", runtime.part("graphInputs") is DetachableTopicGraphSink)
        assertTrue("CC4b-O01 the fan-out's grant connection is installed", runtime.part("graphGrants") is DetachableTopicGrantSink)
        assertSame("CC4b-O01 the bridge publishes the delivered grant", F1, checkNotNull(g.assembly).fences.current())
        h.liveFrame(1390.0)
        assertTrue("CC4b-O01 the observation reached the recorder", g.held() > 0)
        assertEquals("CC4b-O01 the recovery events were told the foreground", true, g.eventsForeground())
        assertTrue("CC4b-O01 no graph request", g.requests.isEmpty())
        assertTrue("CC4b-O01 nothing reported", g.reports.isEmpty() && g.eventFailures.isEmpty())
        g.close()
    }

    /** CC4b-O02: without a builder the owner creates the runtime as before: no graph connection on either side. */
    @Test
    fun `CC4b-O02 with no builder the runtime has no graph connection`() = runTest {
        val h = C4OwnerHarness(this, online = true)
        h.owner.start()
        h.settle(100)
        val runtime = h.runtime()
        assertNull("CC4b-O02 no graph input", runtime.part("graphInputs"))
        assertNull("CC4b-O02 no grant connection", runtime.part("graphGrants"))
        h.owner.requireReady()
    }

    /**
     * CC4b-O03: a build that throws an Exception is reported once and gives up the graph alone: the runtime has no graph
     * connection, the topic connects and is ready. An Error or a cancellation from the build is rethrown, unreported, and the
     * owner is not ready.
     */
    @Test
    fun `CC4b-O03 a failed build gives up the graph alone, an Error or a cancellation is rethrown`() = runTest {
        val g = GraphRig(this)
        val h = g.harness(this)
        val boom = IllegalStateException("graph build failed")
        g.failWith = boom
        g.reportFails = true
        h.owner.start()
        h.foreground(true)
        h.settle(1_000)
        h.owner.requireReady()
        assertSame("CC4b-O03 reported once, and the report's own failure did not escape", boom, g.reports.single())
        assertNull("CC4b-O03 no graph input", h.runtime().part("graphInputs"))
        assertEquals("CC4b-O03 the topic connects", 1, h.wire.requests.size)

        for (thrown in listOf<Throwable>(AssertionError("graph build broke"), CancellationException("graph build cancelled"))) {
            val g2 = GraphRig(this)
            val h2 = g2.harness(this)
            g2.failWith = thrown
            val caught = runCatching { h2.owner.start() }.exceptionOrNull()
            assertSame("CC4b-O03 rethrown: $thrown", thrown, caught)
            assertTrue("CC4b-O03 not reported: $thrown", g2.reports.isEmpty())
            assertThrows("CC4b-O03 not ready: $thrown", IllegalStateException::class.java) { h2.owner.requireReady() }
        }
    }

    /**
     * CC4b-O04: a failed binding — the permit slot already bound — is reported once; the graph is given up after the runtime's
     * graph detachment: the runtime's two graph connections answer DORMANT and reach nothing, the assembly is closed and waited
     * for, the graph is never started (no seed read), a later observation reaches no recorder, and the topic stays ready.
     */
    @Test
    fun `CC4b-O04 a failed binding detaches the runtime, closes the graph and keeps the topic`() = runTest {
        val g = GraphRig(this)
        val h = g.harness(this)
        g.presetPermit = true
        h.owner.start()
        h.foreground(true)
        h.settle(1_000)
        h.owner.requireReady()
        assertTrue("CC4b-O04 reported once: the binding", g.reports.single() is IllegalStateException)
        val runtime = h.runtime()
        assertEquals("CC4b-O04 the graph input is detached", TopicGraphOffer.DORMANT,
            (runtime.part("graphInputs") as DetachableTopicGraphSink).tryOffer(probe))
        assertTrue("CC4b-O04 the assembly is closed and waited for", g.assemblyEnded())
        assertEquals("CC4b-O04 the graph given up is never started: the seed is not read", 0, g.seedReads)
        val held = g.held()
        h.liveFrame(1391.0)
        assertEquals("CC4b-O04 nothing more reaches the recorder", held, g.held())
        assertEquals("CC4b-O04 the topic connected", 1, h.wire.requests.size)
        assertTrue("CC4b-O04 no graph request", g.requests.isEmpty())
    }

    /**
     * CC4b-O05: a topic failure — runtime creation, or identity forwarding after the runtime exists — is rethrown at once and
     * leaves the owner not ready; the graph's cleanup was secured first and, once Main runs it, has detached a runtime that
     * exists and closed the assembly. A topic failure is not a graph failure: nothing is reported.
     */
    @Test
    fun `CC4b-O05 a topic failure secures the graph cleanup, then is rethrown`() = runTest {
        for (case in listOf("runtime creation", "identity forwarding")) {
            val g = GraphRig(this)
            val h = g.harness(this, failFirstCreate = case == "runtime creation", failFenceObserve = case == "identity forwarding")
            assertThrows("CC4b-O05 $case: rethrown", IllegalStateException::class.java) { h.owner.start() }
            assertThrows("CC4b-O05 $case: not ready", IllegalStateException::class.java) { h.owner.requireReady() }
            h.settle(100)
            assertTrue("CC4b-O05 $case: the assembly is closed", g.assemblyEnded())
            assertTrue("CC4b-O05 $case: not reported", g.reports.isEmpty())
            if (case == "identity forwarding") {
                assertEquals("CC4b-O05 $case: the runtime that exists is detached", TopicGraphOffer.DORMANT,
                    (h.runtime().part("graphInputs") as DetachableTopicGraphSink).tryOffer(probe))
            }
        }
    }

    /**
     * CC4b-O13: the owner's readiness, which the rates cache cutover reads on IO, is published only after the graph's start was
     * asked: at the starter's dispatch — the last task start() queues on Main — the owner is not yet ready, and once start()
     * returns it is.
     */
    @Test
    fun `CC4b-O13 readiness is published after the graph start is asked`() = runTest {
        val g = GraphRig(this)
        val h = g.harness(this)
        val readyAtDispatch = mutableListOf<Boolean>()
        h.main.onDispatch = { readyAtDispatch += runCatching { h.owner.requireReady() }.isSuccess }
        h.owner.start()
        h.main.onDispatch = null
        assertTrue("CC4b-O13 fixture: start() queued work on Main", readyAtDispatch.isNotEmpty())
        assertEquals("CC4b-O13 not ready at the last dispatch inside start(), the starter's", false, readyAtDispatch.last())
        h.owner.requireReady()
        h.settle(100)
        g.close()
    }

    // ---- S4 CUT-CC5-2: the process screen host (`cut_cc5_agreed.r1.md`) ------------------------------------------------

    /**
     * CC5-2-H01: no host before the graph's publication; right after it, on Main, the host is made, the starter's time events
     * go to it and it is published. A mount opened on it gets one started holder per FX tab, and a time event reaches each.
     */
    @Test
    fun `CC5-2-H01 the host is installed after the publication and receives the time events`() = runTest {
        val io = HeldDispatcher()
        val g = GraphRig(this, seedIo = io)
        val h = g.harness(this)
        h.owner.start()
        h.settle(100)
        assertNull("CC5-2-H01 no host before the publication", h.owner.graphHost.value)
        assertEquals("CC5-2-H01 none made", 0, g.hostsMade)
        io.release()
        h.settle(100)
        val host = checkNotNull(h.owner.graphHost.value) { "CC5-2-H01 published after the publication" }
        assertEquals("CC5-2-H01 made once", 1, g.hostsMade)
        val mount = host.open()
        h.settle(100)
        assertEquals("CC5-2-H01 one started holder per tab", setOf("usd", "jpy", "eur"), mount.holders.value.keys)
        val runtime = h.runtime()
        assertTrue("CC5-2-H01 each over the runtime's display and focus, on the owner's Main scope", g.holderArgs.all { (display, focus, scope) ->
            display === runtime.display && focus === runtime.focus && scope === h.ownerMain })
        checkNotNull(g.timeEventHandedOver).invoke()
        assertEquals("CC5-2-H01 the time event reaches each mounted holder", mount.holders.value.values.toSet(), g.timeForwarded.toSet())
        mount.close()
        h.settle(100)
        g.close()
    }

    /**
     * CC5-2-H02: making the host fails after the publication: nothing is published, the time events go nowhere, the graph is
     * detached from the runtime and the assembly closed and waited for; the failure is reported once and the topic stays ready.
     */
    @Test
    fun `CC5-2-H02 a failed host install runs the terminal cleanup and keeps the topic`() = runTest {
        val g = GraphRig(this)
        val h = g.harness(this)
        val boom = IllegalStateException("host failed")
        g.hostFailure = boom
        h.owner.start()
        h.settle(100)
        h.owner.requireReady()
        assertNull("CC5-2-H02 nothing published", h.owner.graphHost.value)
        assertSame("CC5-2-H02 reported once", boom, g.reports.single())
        assertEquals("CC5-2-H02 detached", TopicGraphOffer.DORMANT,
            (h.runtime().part("graphInputs") as DetachableTopicGraphSink).tryOffer(probe))
        assertTrue("CC5-2-H02 the assembly is closed and waited for", g.assemblyEnded())
        checkNotNull(g.timeEventHandedOver).invoke()
        assertTrue("CC5-2-H02 the time events go nowhere", g.timeForwarded.isEmpty())
    }

    /** CC5-2-H03: an Error making the host runs the same cleanup, is not reported and escapes. */
    @Test
    fun `CC5-2-H03 an Error installing the host is cleaned up after and escapes`() = runTest {
        val g = GraphRig(this)
        val h = g.harness(this, captureUncaught = true)
        g.hostFailure = AssertionError("host broke")
        h.owner.start()
        h.settle(100)
        assertEquals("CC5-2-H03 it escapes the owner's scope", listOf("host broke"), h.uncaught.map { it.message })
        assertNull("CC5-2-H03 nothing published", h.owner.graphHost.value)
        assertTrue("CC5-2-H03 not reported", g.reports.isEmpty())
        assertTrue("CC5-2-H03 the assembly is closed", g.assemblyEnded())
    }

    /** CC5-2-H04: a graph given up before its publication — a failed binding — installs no host, ever. */
    @Test
    fun `CC5-2-H04 a graph given up before the publication installs no host`() = runTest {
        val g = GraphRig(this)
        val h = g.harness(this)
        g.presetPermit = true
        h.owner.start()
        h.settle(1_000)
        assertNull("CC5-2-H04 no host", h.owner.graphHost.value)
        assertEquals("CC5-2-H04 none made", 0, g.hostsMade)
    }

    /**
     * CC5-2-H06: the host's own failures — a holder it could not build — go to the owner's graph report; the other tabs
     * still mount.
     */
    @Test
    fun `CC5-2-H06 a holder the host could not build is reported`() = runTest {
        val g = GraphRig(this)
        val h = g.harness(this)
        val boom = IllegalStateException("jpy holder failed")
        g.holderFailure = "jpy" to boom
        h.owner.start()
        h.settle(100)
        val mount = checkNotNull(h.owner.graphHost.value).open()
        h.settle(100)
        assertSame("CC5-2-H06 reported to the owner's graph report", boom, g.reports.single())
        assertEquals("CC5-2-H06 the other tabs mount", setOf("usd", "eur"), mount.holders.value.keys)
        mount.close()
        h.settle(100)
        g.close()
    }

    /**
     * CC5-2-H07: a cancellation making the host runs the same terminal cleanup, is not reported and publishes nothing.
     */
    @Test
    fun `CC5-2-H07 a cancellation installing the host is cleaned up after unreported`() = runTest {
        val g = GraphRig(this)
        val h = g.harness(this, captureUncaught = true)
        g.hostFailure = kotlinx.coroutines.CancellationException("host cancelled")
        h.owner.start()
        h.settle(100)
        assertNull("CC5-2-H07 nothing published", h.owner.graphHost.value)
        assertTrue("CC5-2-H07 not reported", g.reports.isEmpty())
        assertTrue("CC5-2-H07 nothing escapes as a failure", h.uncaught.isEmpty())
        assertTrue("CC5-2-H07 the assembly is closed", g.assemblyEnded())
        assertEquals("CC5-2-H07 detached", TopicGraphOffer.DORMANT,
            (h.runtime().part("graphInputs") as DetachableTopicGraphSink).tryOffer(probe))
    }

    /**
     * CC5-2-H08: a graph that fails inside its start — the seed read — installs no host and leaves no waiter behind: the
     * owner's Main scope keeps only the topic's own work.
     */
    @Test
    fun `CC5-2-H08 a graph failing in its start leaves no host waiter behind`() = runTest {
        val noGraph = C4OwnerHarness(this, online = true)
        noGraph.owner.start()
        noGraph.settle(100)
        val baseline = noGraph.ownerMain.coroutineContext[kotlinx.coroutines.Job]!!.children.count { it.isActive }
        val g = GraphRig(this, seedRead = { throw java.io.IOException("seed failed") })
        val h = g.harness(this)
        h.owner.start()
        h.settle(100)
        assertNull("CC5-2-H08 no host", h.owner.graphHost.value)
        assertEquals("CC5-2-H08 none made", 0, g.hostsMade)
        assertEquals("CC5-2-H08 no waiter left: as many active children as a process with no graph", baseline,
            h.ownerMain.coroutineContext[kotlinx.coroutines.Job]!!.children.count { it.isActive })
    }

    /** CC5-2-H05: with no builder there is never a host. */
    @Test
    fun `CC5-2-H05 with no builder there is no host`() = runTest {
        val h = C4OwnerHarness(this, online = true)
        h.owner.start()
        h.settle(1_000)
        assertNull("CC5-2-H05 no host", h.owner.graphHost.value)
    }

    /** CC4b-O10: with no builder a topic failure is rethrown as before and reports nothing. */
    @Test
    fun `CC4b-O10 with no builder a topic failure reports nothing`() = runTest {
        for (case in listOf("runtime creation", "identity forwarding")) {
            val reports = mutableListOf<Throwable>()
            val h = C4OwnerHarness(this, online = true, failFirstCreate = case == "runtime creation",
                failFenceObserve = case == "identity forwarding", reportGraph = { reports += it })
            assertThrows("CC4b-O10 $case: rethrown", IllegalStateException::class.java) { h.owner.start() }
            h.settle(100)
            assertTrue("CC4b-O10 $case: nothing reported", reports.isEmpty())
        }
    }

    /**
     * CC4b-O11: with the seed already in memory and the process already in the foreground at registration, the stream's
     * current value is kept and handed over at the graph's start, with no later foreground call.
     */
    @Test
    fun `CC4b-O11 the foreground at registration reaches the recovery events at the hand-over`() = runTest {
        val g = GraphRig(this)
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { g.seeds.get() }
        testScheduler.runCurrent()
        assertNotNull("CC4b-O11 fixture: the seed is in memory", g.seeds.current)
        val h = g.harness(this, initialForeground = true)
        h.owner.start()
        h.settle(100)
        assertEquals("CC4b-O11 the registration's foreground reached the recovery events", true, g.eventsForeground())
        assertEquals("CC4b-O11 the seed was not read again", 1, g.seedReads)
        g.close()
    }

    /**
     * CC4b-O12: with a real disk store and write ports, the wired runtime with its observations recorded writes nothing: no
     * holder or activation asks, so no write is prepared and the store's folder stays empty.
     */
    @Test
    fun `CC4b-O12 with a real disk the wired graph writes nothing`() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("cc4b-o12").toFile()
        try {
            val g = GraphRig(this, disk = dir)
            val h = g.harness(this)
            h.owner.start()
            h.foreground(true)
            h.settle(1_000)
            h.liveFrame(1390.0)
            h.settle(1_000)
            assertTrue("CC4b-O12 fixture: the observation was recorded", g.held() > 0)
            assertEquals("CC4b-O12 no write prepared", 0, g.prepares)
            assertTrue("CC4b-O12 no file", dir.walk().none { it.isFile })
            assertTrue("CC4b-O12 no graph request", g.requests.isEmpty())
            g.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * CC4b-O06: while the seed is being read nothing of the graph starts: no recorder worker, no foreground to the recovery events, no permit read; the
     * foreground given meanwhile is kept, the last one wins, and observations wait in the sink, in order. Once the seed arrives
     * the graph starts: the kept inputs reach the recorder in order, the kept foreground the recovery events, and the Connection that already existed becomes their baseline.
     */
    @Test
    fun `CC4b-O06 the seed pending keeps inputs and the last foreground until the hand-over`() = runTest {
        val io = HeldDispatcher()
        val g = GraphRig(this, seedIo = io)
        val h = g.harness(this)
        h.owner.start()
        h.foreground(false)
        h.foreground(true)
        h.settle(1_000)
        h.liveFrame(1390.0)
        h.liveFrame(1391.0)
        assertEquals("CC4b-O06 nothing reached the recorder", 0, g.held())
        assertNull("CC4b-O06 the recovery events were told nothing", g.eventsForeground())
        assertTrue("CC4b-O06 nothing reported: no permit read before binding was needed", g.reports.isEmpty())
        assertNull("CC4b-O06 no permit read before the hand-over", g.eventsConnection())
        io.release()
        h.settle(100)
        assertEquals("CC4b-O06 the kept inputs reach the recorder, in order", listOf(1390.0, 1391.0), g.heldRates())
        assertNotNull("CC4b-O06 the Connection that already existed is the recovery events' baseline", g.eventsConnection())
        assertEquals("CC4b-O06 the kept foreground, the last one, reaches the recovery events", true, g.eventsForeground())
        h.foreground(false)
        assertEquals("CC4b-O06 after the hand-over a foreground is forwarded at once", false, g.eventsForeground())
        g.close()
    }

    /** CC4b-O07: a second start() builds no second graph. */
    @Test
    fun `CC4b-O07 a second start builds nothing more`() = runTest {
        val g = GraphRig(this)
        val h = g.harness(this)
        h.owner.start()
        h.owner.start()
        h.settle(100)
        assertEquals("CC4b-O07 built once", 1, g.builds)
        g.close()
    }

    /**
     * CC4b-O09: constructing the production owner resolves no graph dependency: its builder takes each of them as a Provider —
     * the disk store, the deletion admission, the seed source, the authenticated client, the use authority and (S4 CUT-CC5-2)
     * the selection store — and holds
     * nothing else to resolve; the owner takes that builder and (S4 CUT-CC5-4) the graph cache migration as a Provider of its
     * own, which constructing it does not resolve. A D24-off or no-data process never calls start(), so it builds, resolves and
     * starts no graph and launches no migration.
     */
    @Test
    fun `CC4b-O09 constructing the owner resolves no graph provider`() {
        val ctor = com.jay.fxi.data.graph.AppProcessGraphBuilder::class.java.declaredConstructors.single()
        val providers = ctor.genericParameterTypes.filterIsInstance<java.lang.reflect.ParameterizedType>()
            .filter { it.rawType == javax.inject.Provider::class.java }
            .map { (it.actualTypeArguments.single() as Class<*>).simpleName }
        assertEquals("CC4b-O09 the graph dependencies come as Providers",
            listOf("FileGraphV2DiskStore", "DeletionAdmissionStore", "InstallSeedSource", "AuthenticatedApiClient", "TopicUseAuthority",
                "GraphSelectionStore"),
            providers)
        assertEquals("CC4b-O09 and the two process singletons it already shares", listOf("PremiumAccessCoordinator", "AuthTokenProvider"),
            ctor.parameterTypes.filterNot { it == javax.inject.Provider::class.java }.map { it.simpleName })
        val source = java.io.File("src/main/java/com/jay/fxi/data/graph/ProcessGraphBuilder.kt").readText()
        assertFalse("CC4b-O09 the builder resolves nothing when constructed", Regex("""\binit\s*\{|\bby\s+lazy\b""").containsMatchIn(source))
        val owner = TopicRuntimeOwner::class.java.declaredConstructors.single { c -> c.isAnnotationPresent(javax.inject.Inject::class.java) }
        assertTrue("CC4b-O09 the owner takes the builder", owner.parameterTypes.contains(com.jay.fxi.data.graph.AppProcessGraphBuilder::class.java))
        val ownProviders = owner.genericParameterTypes.filterIsInstance<java.lang.reflect.ParameterizedType>()
            .filter { it.rawType == javax.inject.Provider::class.java }.map { (it.actualTypeArguments.single() as Class<*>).simpleName }
        assertEquals("CC5-4-O09 and one Provider of its own, the graph cache migration", listOf("AppGraphCacheCutover"), ownProviders)
        var resolved = 0
        val u = C4OwnerHarness(kotlinx.coroutines.test.TestScope(), graphCutover = { resolved++; error("resolved") })
        checkNotNull(u.owner)
        assertEquals("CC5-4-O09 constructing the owner does not resolve it", 0, resolved)
    }

    /**
     * CC4b-O08: a seed read that fails is the starter's recoverable failure: reported once, the runtime detached and the graph
     * closed, the topic ready.
     */
    @Test
    fun `CC4b-O08 a failed seed read gives up the graph alone`() = runTest {
        val g = GraphRig(this, seedRead = { throw java.io.IOException("seed failed") })
        val h = g.harness(this)
        h.owner.start()
        h.settle(100)
        h.owner.requireReady()
        assertTrue("CC4b-O08 reported once", g.reports.single() is java.io.IOException)
        assertEquals("CC4b-O08 detached", TopicGraphOffer.DORMANT,
            (h.runtime().part("graphInputs") as DetachableTopicGraphSink).tryOffer(probe))
        assertTrue("CC4b-O08 closed", g.assemblyEnded())
    }

    // ---- S4 CUT-CC5-4: readiness and the graph cache migration (`cut_cc5_agreed.r1.md`) --------------------------------

    /**
     * A filesDir holding one v1 graph cache file and the v1 graph preference store, with a real migration journal beside it,
     * and the process's graph cache migration over both, launching on its own IO scope. Each resolution of [provider] is
     * counted, with the owner's published host and the hosts made at that moment.
     */
    private class Legacy(private val g: GraphRig) {
        val root: java.io.File = kotlin.io.path.createTempDirectory("cc54").toFile()
        val files = java.io.File(root, "files").also { it.mkdirs() }
        val cache = java.io.File(files, "graph_cache_v1_usd-krw.json").also { it.writeText("v1") }
        val prefs = java.io.File(files, "datastore/fxi_graph_preferences.preferences_pb").also { it.parentFile!!.mkdirs(); it.writeText("v1") }
        private val storeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val journal = com.jay.fxi.data.local.LocalMigrationJournal(
            androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = storeScope) { java.io.File(root, "journal.preferences_pb") })
        val launchScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val reports = mutableListOf<Throwable>()
        /** Each resolution: whether a host was published then, and how many hosts had been made. */
        val resolutions = mutableListOf<Pair<Boolean, Int>>()
        var failure: Throwable? = null
        val cutover by lazy { com.jay.fxi.data.local.AppGraphCacheCutover(com.jay.fxi.data.local.GraphConsumerReadiness(), journal, files, launchScope, { reports += it }) }
        val provider: () -> com.jay.fxi.data.local.AppGraphCacheCutover = {
            resolutions += (g.h.owner.graphHost.value != null) to g.hostsMade
            failure?.let { throw it }
            cutover
        }

        /** Waits for every migration this process launched. */
        suspend fun joinLaunched() = launchScope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList().forEach { it.join() }

        suspend fun stages() = journal.stage(com.jay.fxi.data.local.LegacyMigrationTarget.GRAPH_CACHE_FILES) to
            journal.stage(com.jay.fxi.data.local.LegacyMigrationTarget.GRAPH_PREFERENCES)

        fun intact() = cache.readText() == "v1" && prefs.readText() == "v1"

        fun close() = kotlinx.coroutines.runBlocking {
            launchScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
            storeScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
            root.deleteRecursively()
        }
    }

    /**
     * CC5-4-K01: signed out and with no screen opened, the graph's publication installs the host, then resolves the migration
     * — after the host is made, before it is published — then marks the readiness and launches: both v1 graph targets are
     * deleted and journalled. A second start launches nothing more.
     */
    @Test
    fun `CC5-4-K01 after the host the migration is resolved, marked ready and launched, signed out and unvisited`() = runTest {
        val g = GraphRig(this)
        val legacy = Legacy(g)
        try {
            val h = g.harness(this, live = null, graphCutover = legacy.provider)
            h.owner.start()
            h.settle(100)
            h.owner.start()
            h.settle(100)
            assertEquals("CC5-4-K01 resolved once, after the host was made and before it was published", listOf(false to 1), legacy.resolutions)
            assertNotNull("CC5-4-K01 the host is published", h.owner.graphHost.value)
            legacy.joinLaunched()
            assertTrue("CC5-4-K01 no failure reported: ${legacy.reports}", legacy.reports.isEmpty())
            assertFalse("CC5-4-K01 the v1 graph files are deleted", legacy.cache.exists() || legacy.prefs.exists())
            assertEquals("CC5-4-K01 journalled", com.jay.fxi.data.local.LegacyMigrationStage.LEGACY_DELETED to
                com.jay.fxi.data.local.LegacyMigrationStage.LEGACY_DELETED, legacy.stages())
            g.close()
        } finally {
            legacy.close()
        }
    }

    /**
     * CC5-4-K02: resolving the migration fails: nothing is published, the time events go back to a no-op even for a mounted
     * host, nothing is marked or launched — a journal already at CONSUMER_CUTOVER deletes nothing — and the graph is detached
     * and closed; reported once, the topic ready.
     */
    @Test
    fun `CC5-4-K02 a failed migration resolution publishes nothing, launches nothing and cleans up`() = runTest {
        val g = GraphRig(this)
        val legacy = Legacy(g)
        try {
            for (target in com.jay.fxi.data.local.LegacyMigrationTarget.entries.filter { it.key.startsWith("graph_") }) {
                legacy.journal.detect(target)
                legacy.journal.cutover(target) {}
            }
            val boom = IllegalStateException("cutover failed")
            legacy.failure = boom
            val h = g.harness(this, graphCutover = legacy.provider)
            h.owner.start()
            h.settle(100)
            h.owner.requireReady()
            assertEquals("CC5-4-K02 resolved once", 1, legacy.resolutions.size)
            assertNull("CC5-4-K02 nothing published", h.owner.graphHost.value)
            assertSame("CC5-4-K02 reported once", boom, g.reports.single())
            assertEquals("CC5-4-K02 detached", TopicGraphOffer.DORMANT,
                (h.runtime().part("graphInputs") as DetachableTopicGraphSink).tryOffer(probe))
            assertTrue("CC5-4-K02 the assembly is closed and waited for", g.assemblyEnded())
            val mount = checkNotNull(g.lastHost).open()
            h.settle(100)
            assertTrue("CC5-4-K02 premise: the made host mounts holders", mount.holders.value.isNotEmpty())
            checkNotNull(g.timeEventHandedOver).invoke()
            assertTrue("CC5-4-K02 the time events go nowhere", g.timeForwarded.isEmpty())
            mount.close()
            h.settle(100)
            legacy.joinLaunched()
            assertTrue("CC5-4-K02 nothing deleted", legacy.intact())
            assertEquals("CC5-4-K02 the journal is where it was", com.jay.fxi.data.local.LegacyMigrationStage.CONSUMER_CUTOVER to
                com.jay.fxi.data.local.LegacyMigrationStage.CONSUMER_CUTOVER, legacy.stages())
        } finally {
            legacy.close()
        }
    }

    /**
     * CC5-4-K03: an Error, or a cancellation, resolving the migration runs the same cleanup, is not reported and is rethrown —
     * an Error escapes the owner's scope — and launches nothing.
     */
    @Test
    fun `CC5-4-K03 an Error or a cancellation resolving the migration is cleaned up after and rethrown`() = runTest {
        for (thrown in listOf<Throwable>(AssertionError("cutover broke"), kotlinx.coroutines.CancellationException("cutover cancelled"))) {
            val g = GraphRig(this)
            val legacy = Legacy(g)
            try {
                legacy.failure = thrown
                val h = g.harness(this, captureUncaught = true, graphCutover = legacy.provider)
                h.owner.start()
                h.settle(100)
                h.owner.requireReady()
                val escaped = if (thrown is Error) listOf(thrown.message) else emptyList()
                assertEquals("CC5-4-K03 ${thrown.message}: what escapes the owner's scope", escaped, h.uncaught.map { it.message })
                assertEquals("CC5-4-K03 ${thrown.message}: premise, resolved once", 1, legacy.resolutions.size)
                assertNull("CC5-4-K03 ${thrown.message}: nothing published", h.owner.graphHost.value)
                assertTrue("CC5-4-K03 ${thrown.message}: not reported", g.reports.isEmpty())
                assertTrue("CC5-4-K03 ${thrown.message}: the assembly is closed", g.assemblyEnded())
                legacy.joinLaunched()
                assertTrue("CC5-4-K03 ${thrown.message}: nothing deleted", legacy.intact())
            } finally {
                legacy.close()
            }
        }
    }

    /**
     * CC5-4-K04: a graph that fails before its publication — a failed seed read — and a host that cannot be made never resolve
     * the migration: nothing is marked, launched or deleted, with the journal at CONSUMER_CUTOVER or LEGACY_DELETED.
     */
    @Test
    fun `CC5-4-K04 a failed graph never resolves or launches the migration`() = runTest {
        for (fail in listOf("seed", "host")) for (deleted in listOf(false, true)) {
            val g = if (fail == "seed") GraphRig(this, seedRead = { throw java.io.IOException("seed failed") }) else GraphRig(this)
            if (fail == "host") g.hostFailure = IllegalStateException("host failed")
            val legacy = Legacy(g)
            try {
                // The journal is past its cutover, or past its deletion with the v1 files written back.
                for (target in com.jay.fxi.data.local.LegacyMigrationTarget.entries.filter { it.key.startsWith("graph_") }) {
                    legacy.journal.detect(target)
                    legacy.journal.cutover(target) {}
                    if (deleted) legacy.journal.deleteLegacy(target) {}
                }
                val h = g.harness(this, graphCutover = legacy.provider)
                h.owner.start()
                h.settle(100)
                h.owner.requireReady()
                assertTrue("CC5-4-K04 $fail/$deleted: premise, the graph failed", g.reports.isNotEmpty())
                assertTrue("CC5-4-K04 $fail/$deleted: never resolved", legacy.resolutions.isEmpty())
                legacy.joinLaunched()
                assertTrue("CC5-4-K04 $fail/$deleted: nothing deleted", legacy.intact())
            } finally {
                legacy.close()
            }
        }
    }

    /**
     * CC5-4-K05: a D24-off or no-data process never starts the owner (`FXiApplicationStartTest` C4-J-START-ADMISSION, the
     * gate of app-owned services). Constructed and left unstarted while time passes, the owner builds no graph and resolves,
     * marks and launches no migration: with the journal at CONSUMER_CUTOVER or LEGACY_DELETED, the v1 files and the stages stay.
     */
    @Test
    fun `CC5-4-K05 an owner never started builds, resolves and launches nothing`() = runTest {
        for (deleted in listOf(false, true)) {
            val g = GraphRig(this)
            val legacy = Legacy(g)
            try {
                for (target in com.jay.fxi.data.local.LegacyMigrationTarget.entries.filter { it.key.startsWith("graph_") }) {
                    legacy.journal.detect(target)
                    legacy.journal.cutover(target) {}
                    if (deleted) legacy.journal.deleteLegacy(target) {}
                }
                val before = legacy.stages()
                val h = g.harness(this, graphCutover = legacy.provider)
                h.settle(60_000)
                assertEquals("CC5-4-K05 $deleted: no graph built", 0, g.builds)
                assertTrue("CC5-4-K05 $deleted: never resolved", legacy.resolutions.isEmpty())
                legacy.joinLaunched()
                assertTrue("CC5-4-K05 $deleted: the v1 files stay", legacy.intact())
                assertEquals("CC5-4-K05 $deleted: the stages stay", before, legacy.stages())
            } finally {
                legacy.close()
            }
        }
    }
}
