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
                timeEvent = { timeEvents++; timeEvent() }
            )
            assembly = built
            ProcessGraphParts(built, seeds)
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
            initialForeground: Boolean? = null
        ) =
            C4OwnerHarness(
                test, online = online, failFirstCreate = failFirstCreate, failFenceObserve = failFenceObserve,
                graphBuilder = builder, reportGraph = { reports += it; if (reportFails) error("report failed") },
                initialForeground = initialForeground
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
     * the disk store, the deletion admission, the seed source, the authenticated client and the use authority — and holds
     * nothing else to resolve; the owner takes that builder. A D24-off or no-data process never calls start(), so it builds,
     * resolves and starts no graph.
     */
    @Test
    fun `CC4b-O09 constructing the owner resolves no graph provider`() {
        val ctor = com.jay.fxi.data.graph.AppProcessGraphBuilder::class.java.declaredConstructors.single()
        val providers = ctor.genericParameterTypes.filterIsInstance<java.lang.reflect.ParameterizedType>()
            .filter { it.rawType == javax.inject.Provider::class.java }
            .map { (it.actualTypeArguments.single() as Class<*>).simpleName }
        assertEquals("CC4b-O09 the graph dependencies come as Providers",
            listOf("FileGraphV2DiskStore", "DeletionAdmissionStore", "InstallSeedSource", "AuthenticatedApiClient", "TopicUseAuthority"),
            providers)
        assertEquals("CC4b-O09 and the two process singletons it already shares", listOf("PremiumAccessCoordinator", "AuthTokenProvider"),
            ctor.parameterTypes.filterNot { it == javax.inject.Provider::class.java }.map { it.simpleName })
        val source = java.io.File("src/main/java/com/jay/fxi/data/graph/ProcessGraphBuilder.kt").readText()
        assertFalse("CC4b-O09 the builder resolves nothing when constructed", Regex("""\binit\s*\{|\bby\s+lazy\b""").containsMatchIn(source))
        val owner = TopicRuntimeOwner::class.java.declaredConstructors.single { c -> c.isAnnotationPresent(javax.inject.Inject::class.java) }
        assertTrue("CC4b-O09 the owner takes the builder", owner.parameterTypes.contains(com.jay.fxi.data.graph.AppProcessGraphBuilder::class.java))
        assertTrue("CC4b-O09 and no graph Provider of its own", owner.parameterTypes.none { it == javax.inject.Provider::class.java })
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
}
