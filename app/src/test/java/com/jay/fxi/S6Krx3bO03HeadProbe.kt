package com.jay.fxi

import com.jay.fxi.data.auth.*
import com.jay.fxi.data.graph.*
import com.jay.fxi.ui.graph.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import org.junit.Test
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.purge.*
import com.jay.fxi.data.local.*
import com.jay.fxi.data.remote.*
import com.jay.fxi.data.remote.S6KrxCleanupRig.Companion.field
import com.jay.fxi.data.remote.S6KrxCleanupRig.Companion.setField
import com.jay.fxi.data.remote.dto.*
import com.jay.fxi.domain.model.*
import com.jay.fxi.time.AppClock
import com.jay.fxi.ui.graph.GraphPreparedBuilder
import com.jay.fxi.ui.premium.graph.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.datetime.Instant
import okhttp3.Headers
import org.junit.Assert.*


/** HEAD24ea81c-compatible probe. The source is copied byte-for-byte into the clean HEAD run. */
@OptIn(ExperimentalCoroutinesApi::class)
class S6Krx3bO03HeadProbe {
    @Test fun writeHeadTraceOnlyWhenRequested() {
        val destination = System.getProperty("s63b1.o03.headTraceOut") ?: return
        try {
            val traces = COLUMNS.associateWith { trace(it) }
            val body = buildJsonObject {
                put("schema", 1)
                put("head", HEAD)
                put("columns", JsonObject(traces))
            }.toString().toByteArray(Charsets.UTF_8)
            val path = Paths.get(destination)
            path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.write(path, body, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (failure: Throwable) {
            throw AssertionError("contract(O03/head-generation): actual HEAD trace generation failed", failure)
        }
    }
    companion object {
        const val HEAD = "24ea81ca8ed9f2dddbd0eb8c05a16420ce601180"
        val COLUMNS = listOf("GENERAL-WS-REST", "reverse-two-times", "catalog-wait", "continuity-FULL",
            "continuity-FAILED", "continuity-CLOSED", "overflow", "drain", "REST-high", "REST-low",
            "USER-end", "CAP-end", "D1-hold", "server-seed-HTTP", "USD-tether", "assembly-failure", "detach-close")
        internal fun trace(column: String, partsFactory: ((O03HeadWorld) -> O03HeadParts)? = null): JsonElement {
            var result: JsonElement = JsonNull
            val projection = O03HeadProjection(column)
            try {
                runTest(timeout = 30.seconds) {
                    val g = O03HeadWorld(this, "O03/$column")
                    val checkpoints = mutableListOf<Any?>()
                    val publications = mutableListOf<Any?>()
                    val observers = mutableListOf<kotlinx.coroutines.Job>()
                    try {
                        g.r.approve()
                        g.owner = g.need(g.r.issuer.topicGrantResult().fence, "OFF actual GENERAL grant")
                        val p = partsFactory?.invoke(g) ?: g.headParts()
                        val queued = column in setOf("continuity-FULL", "continuity-FAILED", "overflow", "drain")
                        // boundary-input: a real full queue, with valid actual GENERAL owner/use, before any ACK fact.
                        val full = TopicGraphInput.Observations(90_000, "fx:usd-krw", TopicGraphPath.WS,
                            TopicUseAttribution(Any(), g.owner!!, 1, g.use), 1,
                            List(512) { TopicGraphCandidate.Quote("investing", "usd-krw", 1444.0, O03_AT, null) })
                        if (queued) {
                            g.eq("512-unit GENERAL holding is accepted whole", TopicGraphOffer.ENQUEUED, p.inputSink.tryOffer(full))
                            g.eq("inside exact full queue window", 512, field(p.inputSink, "usedUnits"))
                        }
                        if (column == "continuity-CLOSED") p.inputSink.close()
                        if (column == "continuity-FAILED") {
                            // boundary-input: one existing GENERAL ledger read fails; the actual sink catch records FAILED.
                            @Suppress("UNCHECKED_CAST") val original = field(p.inputSink, "ledger") as MutableMap<GraphDataScope, MutableMap<String, Long>>
                            var armed = true
                            setField(p.inputSink, "ledger", object : MutableMap<GraphDataScope, MutableMap<String, Long>> by original {
                                override fun get(key: GraphDataScope): MutableMap<String, Long>? {
                                    if (armed) { armed = false; throw IllegalArgumentException("boundary-input: ledger read fault") }
                                    return original[key]
                                }
                            })
                        }
                        p.startGraph(deferSink = queued)
                        p.startSession(leaseSeconds = if (column == "D1-hold") 181 else 600)
                        fun frame(rate: Double, at: Instant = O03_AT, changed: Instant? = null): String {
                            val change = changed?.let { ",\"rate_changed_at\":\"$it\"" } ?: ""
                            return """{"type":"snapshot","version":1,"topic":"fx:usd-krw","data":{"banks":[{"source":"investing","asset":"usd-krw","rate":$rate,"timestamp":"$at"$change}]}}"""
                        }
                        fun raw(): List<Any?> {
                            val state = p.record.state.value
                            return listOf(state.scope, state.versionFloor, state.nextVersion, state.seenUserEnd, state.seenRevision,
                                state.series.mapValues { g.print(it.value) }, state.untransferredSeries,
                                state.pending.inputs.map { projection.input(it) }, state.pending.lostTopics, field(p.inputSink, "usedUnits"),
                                (field(p.inputSink, "ledger") as Map<*, *>).mapValues { (_, value) -> (value as Map<*, *>).toMap() },
                                g.session.cleanup.session.graphLoss.value)
                        }
                        fun checkpoint(label: String) {
                            val state = p.requests.state.value
                            checkpoints.add(projection.value(listOf(label, raw(), state,
                                state.failures.mapValues { it.value.javaClass.simpleName to it.value.message })))
                        }
                        fun finishHttp(rate: Double = 1500.0) {
                            repeat(3) {
                                runCurrent()
                                g.sent.filter { !it.response.isCompleted }.toList().forEach {
                                    it.response.complete(O03HeadWorld.ok(O03HeadWorld.tabDto(it.key, rate)))
                                }
                            }
                            runCurrent()
                        }
                        fun activate() { p.requests.onActivated(GraphKey("usd", GraphPeriod.ONE_DAY)); runCurrent() }
                        fun displayTrace(state: GraphV2ScreenState): List<Any?> = listOf(state.tab, state.activePeriod,
                            state.content, state.selectionStatus, state.selection, state.toggles, state.chart,
                            state.refreshing, state.fullscreenOpen, state.inlineToken, state.fullscreenToken)
                        g.session.deliver(frame(1444.0))
                        g.r.krxTick()
                        g.yes("actual GENERAL producer ran before comparison", g.session.inputs.filterIsInstance<TopicGraphInput.Observations>()
                            .any { it.topic == "fx:usd-krw" })
                        checkpoint("after-actual-WS")
                        when (column) {
                            "GENERAL-WS-REST", "reverse-two-times" -> {
                                g.r.until(g.c("actual GENERAL REST is waiting"), 2_600) {
                                    g.session.cleanup.f.calls.any { it.topic == "fx:usd-krw" && it.outcome == null && !it.cancelled }
                                }
                                val call = g.session.cleanup.f.calls.last { it.topic == "fx:usd-krw" && it.outcome == null && !it.cancelled }
                                val restFrame = if (column == "reverse-two-times") frame(1443.0, O03_AT + 2.seconds, O03_AT - 1.seconds)
                                    else frame(1443.0, O03_AT - 1.seconds)
                                g.session.cleanup.f.reply(call, KrxA1bFixture.Reply(200, restFrame))
                                if (column == "reverse-two-times") {
                                    g.session.deliver(frame(1445.0, O03_AT, O03_AT - 2.seconds))
                                    g.session.deliver(frame(1444.0, O03_AT - 1.seconds))
                                }
                                g.yes("GENERAL REST completed normally", call.outcome != null && !call.cancelled)
                                activate(); finishHttp()
                            }
                            "catalog-wait" -> {
                                g.yes("catalog absent and actual batch retained", p.requests.state.value.catalog == null &&
                                    p.record.state.value.pending.inputs.any { it is TopicGraphInput.Observations })
                                checkpoint("catalog-wait")
                                activate(); finishHttp(); p.record.replayPending()
                                g.yes("catalog replay adopts GENERAL", p.record.state.value.series.keys.any { it.seriesId == O03HeadWorld.GENERAL })
                            }
                            "continuity-FULL", "continuity-FAILED", "continuity-CLOSED", "overflow", "drain" -> {
                                if (column == "overflow") {
                                    // boundary-input: original valid GENERAL scope with a 513-unit batch.
                                    val oversized = TopicGraphInput.Observations(90_001, full.topic, full.path, full.attribution, 1,
                                        List(513) { full.candidates.first() })
                                    g.eq("observation overflow is whole-batch FULL", TopicGraphOffer.FULL, p.inputSink.tryOffer(oversized))
                                }
                                val from = g.session.inputs.size
                                g.r.wire.drop(); g.r.drain()
                                g.yes("actual mixed WS ending exists", g.session.inputs.drop(from).filterIsInstance<TopicGraphInput.Continuity>()
                                    .any { it.reason == TopicGraphEventReason.WS_ENDED && "fx:usd-krw" in it.topics && TopicCatalogue.KRX_FUTURES in it.topics })
                                val expected = when (column) {
                                    "continuity-CLOSED" -> TopicGraphOffer.CLOSED
                                    "continuity-FAILED" -> TopicGraphOffer.FAILED
                                    else -> TopicGraphOffer.FULL
                                }
                                g.yes("actual session observes requested loss result", g.session.offers.any { it.second == expected })
                                checkpoint("loss-before-drain")
                                p.inputSink.start(); runCurrent()
                                g.eq("drain refunds all units", 0, field(p.inputSink, "usedUnits"))
                                activate(); finishHttp()
                            }
                            "REST-high", "REST-low" -> {
                                activate(); finishHttp(); p.record.replayPending()
                                // boundary-input: an explicit GENERAL handover loss opens recovery before the next real HTTP issue.
                                p.record.loseTopics(g.dataScope, mapOf("fx:usd-krw" to g.use.invalidations))
                                p.requests.onRefreshRequested(force = true); runCurrent()
                                g.yes("actual graph HTTP pending", g.sent.any { it.admitted && !it.returned && !it.response.isCompleted })
                                val extreme = if (column == "REST-high") 1800.0 else 1000.0
                                g.session.deliver(frame(extreme, O03_AT + 1.seconds))
                                g.yes("GENERAL extreme adopted while HTTP waits", p.record.state.value.series.values.any {
                                    it.data.app.observations.any { observation -> observation.rate == extreme } })
                                checkpoint("extreme-before-http")
                                finishHttp()
                                g.yes("native GENERAL recovery response was applied", p.record.state.value.series.values.any { it.lastAppliedVersion != null })
                                g.yes("HTTP preserves original GENERAL extreme", p.record.state.value.series.values.any {
                                    it.data.app.observations.any { observation -> observation.rate == extreme } })
                            }
                            "USER-end", "CAP-end", "D1-hold" -> {
                                activate(); finishHttp()
                                if (column == "D1-hold") {
                                    g.session.cleanup.unavailable(g.session.renewal())
                                    g.session.cleanup.hold(); g.session.cleanup.release()
                                } else {
                                    g.r.h.source.next = { if (column == "USER-end") EntitlementsOutcome.StableInactive(false)
                                        else EntitlementsOutcome.StableActive(false) }
                                    g.r.pac.refresh(if (column == "USER-end") RefreshIntent.FORCE_PREMIUM else RefreshIntent.FORCE_ENTITLEMENTS)
                                    g.r.drain()
                                    if (column == "USER-end") g.yes("actual USER end published", !g.r.snapshot.facts.userAllowed)
                                    else g.yes("CAP input keeps GENERAL user", g.r.snapshot.facts.userAllowed)
                                }
                            }
                            "server-seed-HTTP" -> {
                                activate(); finishHttp()
                                g.disk.seed = g.need(g.disk.reserved.lastOrNull(), "actual online response supplied a disk seed")
                                val reads = g.disk.readCount
                                val cold = p.seededRequests(); cold.onActivated(GraphKey("usd", GraphPeriod.ONE_DAY)); runCurrent()
                                g.yes("actual cold coordinator read server seed", g.disk.readCount > reads)
                                checkpoints.add(projection.value(listOf("seed-before-http", cold.state.value)))
                                g.yes("cold HTTP producer exists", g.sent.any { !it.response.isCompleted })
                                finishHttp(1501.0)
                                checkpoints.add(projection.value(listOf("seed-after-http", cold.state.value)))
                            }
                            "USD-tether" -> {
                                val mounted = listOf("usd", "tether").map { tab ->
                                    g.holder(p.requests, p.accessGate, p.fences.current, p.record, tab)
                                }
                                finishHttp()
                                for (holder in mounted) {
                                    observers += g.scope.launch(UnconfinedTestDispatcher(testScheduler)) {
                                        holder.state.collect { publications.add(projection.value(displayTrace(it))) }
                                    }
                                    val token = g.need(holder.currentState().inlineToken, "OFF holder token")
                                    holder.setSurfaceVisible(token, true); holder.enterFullscreen(token); runCurrent()
                                    holder.currentState().fullscreenToken?.let { holder.setSurfaceVisible(it, true) }
                                    g.yes("GENERAL holder chart exists", holder.currentState().chart != null)
                                }
                                g.r.generalTick(); g.r.advance(351)
                                mounted.forEach { publications.add(projection.value(displayTrace(it.currentState()))) }
                            }
                            "assembly-failure" -> {
                                var failed = false
                                try { g.assembly(g::graphSnapshot, failConstruction = true) }
                                catch (failure: IllegalArgumentException) { failed = failure.message == "boundary-input: assembly cache-port construction fails" }
                                g.yes("actual construction failure reached", failed)
                                g.session.deliver(frame(1446.0, O03_AT + 1.seconds))
                            }
                            "detach-close" -> {
                                activate(); finishHttp()
                                p.runtime.detachGraph()
                                val before = g.clockReads to g.graphSnapshotTrace.size
                                val old = raw()
                                g.session.deliver(frame(1447.0, O03_AT + 1.seconds)); g.r.krxTick()
                                g.eq("detached offers have no graph clock/snapshot effect", before, g.clockReads to g.graphSnapshotTrace.size)
                                g.eq("detached graph holding unchanged", old, raw())
                            }
                        }
                        runCurrent(); checkpoint("after-column")
                        p.verifyOff()
                        g.yes("OFF dedicated KRX observations zero", g.session.inputs.filterIsInstance<TopicGraphInput.Observations>()
                            .none { it.topic == TopicCatalogue.KRX_FUTURES })
                        observers.forEach { it.cancel() }
                        g.closeParts(p); runCurrent()
                        result = projection.value(listOf(checkpoints.toList(), publications.toList(), g.session.inputs.map { projection.input(it) },
                            g.session.offers.map { listOf(projection.input(it.first), it.second) }, g.controlTrace(),
                            g.clockTrace.toList(), g.graphSnapshotTrace.toList(), g.sent.map { listOf(it.key, it.admitted, it.returned, it.cancelled) },
                            g.session.cleanup.f.calls.map { listOf(it.topic, it.at, it.stage, it.cancelled, it.sends.toList(),
                                it.read.revision, it.read.krxPermitSeq, it.outcome?.javaClass?.simpleName) },
                            g.session.cleanup.f.floorEvidence.toList(), g.r.httpFloor,
                            g.preparationTrace.toList(), g.prepareStarted.toList(), g.disk.ioTrace.toList(),
                            g.disk.admissions.toList(), g.disk.held.size, g.disk.withdrawals.size, g.failures.map { it.javaClass.simpleName to it.message }))
                    } finally { observers.forEach { it.cancel() }; g.close() }
                }
            } catch (failure: Throwable) {
                if (failure is AssertionError && (failure.message.orEmpty().startsWith("contract(") || failure.message.orEmpty().startsWith("premise:"))) throw failure
                throw AssertionError("premise: O03/$column executable wiring helper failed", failure)
            }
            return result
        }

    }
}

internal val O03_AT: Instant = Instant.parse("2026-09-26T01:20:00Z")

/** The injection interface contains only API already present in HEAD. */
internal interface O03HeadParts {
    val fences: GraphAccessFenceBridge
    val accessGate: GraphV2AccessGate
    val record: GraphRecorder
    val requests: GraphV2RequestCoordinator
    val inputSink: GraphRecorderTopicSink
    val runtime: TopicRuntime
    fun startGraph(deferSink: Boolean)
    fun startSession(leaseSeconds: Long)
    fun seededRequests(): GraphV2RequestCoordinator
    fun verifyOff() = Unit
    suspend fun closeParts()
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class O03HeadWorld(val test: TestScope, val row: String) {
    val session = O03HeadSession(test, row)
    val r get() = session.r
    val dispatcher = StandardTestDispatcher(test.testScheduler)
    val job = SupervisorJob()
    val scope = CoroutineScope(job + dispatcher)
    private val base = test.testScheduler.currentTime
    var clockReads = 0
    val clockTrace = mutableListOf<Instant>()
    val clock = AppClock {
        clockReads++
        (O03_AT + (test.testScheduler.currentTime - base).milliseconds).also { clockTrace += it }
    }
    var owner: TopicSessionFence? = null
    val snapshot get() = r.snapshotSupply()
    val revisions get() = r.pac.accessRevisions
    val graphSnapshotTrace = mutableListOf<List<Any?>>()
    fun graphSnapshot(): TopicAccessSnapshot = snapshot.also {
        graphSnapshotTrace.add(listOf(it.revision, it.krxPermitSeq, it.userInvalidations, it.capabilityInvalidations, it.facts))
    }
    val dataScope get() = GraphDataScope(need(owner, "owner").identity.uid, need(owner?.userAccessEpoch, "USER epoch"))
    val use get() = need(snapshot.acquireUse(need(owner, "owner")), "GENERAL lifetime")
    var catalog: GraphCatalog? = defaultCatalog()
    fun c(text: String) = "contract($row): $text"
    fun yes(text: String, value: Boolean) = assertTrue(c(text), value)
    fun eq(text: String, expected: Any?, actual: Any?) = assertEquals(c(text), expected, actual)
    fun <T : Any> need(value: T?, text: String): T = value ?: throw AssertionError(c(text))
    fun print(s: GraphRecoverableState) = listOf(s.data.app.buckets, s.data.app.observations, s.data.app.tip,
        s.data.serverBuckets, s.pending, s.gapHistory, s.generation, s.lastAppliedVersion, s.lastCutoff)
    class Sent(val key: GraphKey, val admitted: Boolean) {
        val response = CompletableDeferred<AuthenticatedHttpResponse<GraphV2TabResponse>>()
        var returned = false
        var cancelled = false
    }
    val sent = mutableListOf<Sent>()
    val failures = mutableListOf<Throwable>()
    val disk = FakeDisk()
    val prepareStarted = mutableListOf<GraphV2AccessCapture>()
    var prepareGate: CompletableDeferred<Unit>? = null
    val fetcher = object : GraphV2Fetching {
        override suspend fun catalog(owner: AuthSnapshot, useAdmitted: () -> Boolean) = ok(catalogDto())
        override suspend fun tab(owner: AuthSnapshot, key: GraphKey, useAdmitted: () -> Boolean): AuthenticatedHttpResponse<GraphV2TabResponse> {
            val call = Sent(key, useAdmitted()).also { sent += it }
            try { return call.response.await().also { call.returned = true } }
            catch (cancelled: CancellationException) { call.cancelled = true; throw cancelled }
        }
    }
    val owners = object : GraphOwnerSource {
        override fun currentIdentity() = r.identity
        override suspend fun capture(expected: AuthIdentityFence): AuthSnapshot {
            yes("HTTP owner still current", currentIdentity() == expected)
            return r.credential
        }
    }
    fun writes() = GraphV2WritePorts(GraphV2PrepareWrite { captured, wantsKrx ->
        prepareStarted += captured
        prepareGate?.await()
        val record = need(r.h.store.record, "actual persisted epoch record")
        GraphV2WritePreparation(GraphV2NamespacePreparation.Ready(record),
            GraphV2NamespacePreparation.Ready(record).takeIf { wantsKrx })
    }, { }, { })
    private val assemblies = mutableListOf<GraphRuntimeAssembly>()
    private val holders = mutableListOf<GraphV2ScreenStateHolder>()
    private val selections = mutableListOf<GraphSeriesSelectionSession>()
    private val runtimes = mutableListOf<TopicRuntime>()
    private val extraRequests = mutableListOf<GraphV2RequestCoordinator>()
    fun assembly(readSnapshot: () -> TopicAccessSnapshot = { snapshot }, failConstruction: Boolean = false): GraphRuntimeAssembly {
        val assembly = GraphRuntimeAssembly(readSnapshot, revisions, { r.identity }, r.uses, { true }, fetcher, owners,
            { g ->
                if (failConstruction) throw IllegalArgumentException("boundary-input: assembly cache-port construction fails")
                GraphV2CachePorts(disk, g, { }, writes())
            }, dispatcher, job, clock, { Duration.ZERO }, { failures += it })
        assemblies += assembly
        assembly.fences.setAccess(true, owner, TopicGrantOrigin.NewContext)
        return assembly
    }
    val preparationTrace = mutableListOf<List<Any?>>()
    var duringPrepare: (() -> Unit)? = null
    fun holder(requests: GraphV2RequestCoordinator, accessGate: GraphV2AccessGate,
        fences: () -> TopicSessionFence?, record: GraphRecorder, tab: String): GraphV2ScreenStateHolder {
        val selection = GraphSeriesSelectionSession(object : GraphSelectionStore {
            override suspend fun readGraphSelection(key: GraphSelectionKey) = GraphSelectionReadResult.Present(
                GraphSelectionRecord(1, key.uid, key.audience, key.tab, setOf(GENERAL, KRX), setOf(GENERAL, KRX)))
            override suspend fun confirmGraphSelection(key: GraphSelectionKey) = readGraphSelection(key)
            override suspend fun writeGraphSelection(key: GraphSelectionKey, selection: GraphSeriesSelection) = GraphSelectionWriteResult.Committed
        }, GraphSelectionAudience.PREMIUM, tab, scope, dispatcher).also { selections += it }
        val f = need(owner, "holder owner")
        val holder = GraphV2ScreenStateHolder(tab, requests, selection, { r.identity },
            need(r.session, "live holder session").display, MutableStateFlow(OwnedTopicFocus(f.identity, if (tab == "usd") FreeTab.USD else FreeTab.TETHER)),
            fences, r.uses, accessGate, revisions, scope, dispatcher,
            clock = clock, recorder = record, prepare = { graph, period ->
                preparationTrace.add(listOf(period, graph))
                duringPrepare?.also { duringPrepare = null }?.invoke()
                GraphPreparedBuilder.build(graph, period)
            })
        holders += holder
        holder.start(); holder.onActivated(TopicDisplayOwner(f.identity, r.display.owner!!.grantEpoch)); test.runCurrent()
        return holder
    }
    fun newRequests(requestScope: CoroutineScope, fences: GraphAccessFenceBridge,
        accessGate: GraphV2AccessGate, record: GraphRecorder) = GraphV2RequestCoordinator(fetcher = fetcher, owners = owners,
        currentAccessFence = fences.current, uses = r.uses, protectedAdmission = { true }, accessSnapshot = ::graphSnapshot,
        scope = requestScope, clock = clock, rateLimitJitter = { Duration.ZERO }, onEventFailure = { failures += it },
        cachePorts = GraphV2CachePorts(disk, accessGate, {}, writes()), recorder = record)
    fun seededRequests(p: O03HeadParts) = newRequests(scope, p.fences, p.accessGate, p.record).also { extraRequests += it; it.start() }
    fun startSession(p: O03HeadParts, runtime: TopicRuntime, seconds: Long) {
        runtimes += runtime
        session.attach(runtime) { clock.now().toEpochMilliseconds() }
        val wires = r.wires.size
        runtime.start(); runtime.setOnline(true)
        r.until(c("OFF factory connects a real session"), 2_600) { r.wires.size == wires + 1 }
        r.open(); r.first(); r.ack(seconds = seconds)
        owner = need(p.fences.current(), "factory delivered the live graph fence")
    }
    suspend fun closeParts(p: O03HeadParts) {
        try {
            p.runtime.detachGraph(); p.runtime.stop(); test.runCurrent()
            extraRequests.forEach { it.close() }
        } finally { p.closeParts() }
    }
    fun headParts(): O03HeadParts = object : O03HeadParts {
        private val child = SupervisorJob(job)
        private val graphScope = CoroutineScope(child + dispatcher)
        private lateinit var assembled: GraphV2RequestCoordinator
        override val fences = GraphAccessFenceBridge { assembled.onContextChanged() }
        override val accessGate = GraphV2AccessGate({ r.identity }, fences.current, ::graphSnapshot, { true })
        override val record = GraphRecorder(graphScope, revisions, ::graphSnapshot, fences.current,
            graphRecorderCatalog({ assembled }, fences.current, r.uses), accessGate, clock)
        override val requests = newRequests(graphScope, fences, accessGate, record).also { assembled = it }
        override val inputSink = GraphRecorderTopicSink(graphScope, record, startImmediately = false)
        private val events = GraphRecoveryEvents(graphScope, clock, { requests.state.value.source },
            requests::onRecoveryTrigger, record::retain, {}, { null }, requests::onContextChanged)
        private val input = DetachableTopicGraphSink(inputSink)
        override lateinit var runtime: TopicRuntime
        init { fences.setAccess(true, owner, TopicGrantOrigin.NewContext) }
        override fun startGraph(deferSink: Boolean) { requests.start(); record.start(); if (!deferSink) inputSink.start() }
        override fun startSession(leaseSeconds: Long) {
            runtime = session.runtimeFactory().create(input, fences)
            this@O03HeadWorld.startSession(this, runtime, leaseSeconds)
        }
        override fun seededRequests() = this@O03HeadWorld.seededRequests(this)
        override suspend fun closeParts() {
            try { events.close(); fences.close(); inputSink.close(); requests.close() }
            finally { child.cancelAndJoin() }
        }
    }
    /** HEAD fields only; do not embed integration rig's aggregate toString trace. */
    fun controlTrace(): List<Any?> = listOf(r.events.toList(), r.deliveries.toList(), r.states.toList(), r.offers.toList(),
        r.readLog.map { listOf(it.method, it.snapshot, it.at) }, r.rates, r.display,
        (field(session.cleanup.session, "store") as TopicSubscriptionStateStore).snapshot,
        r.sends.map { listOf(it.request.type, it.request.requestId, it.request.topics, it.accepted, it.at) })
    suspend fun close() {
        holders.forEach { it.close() }; selections.forEach { it.close() }
        assemblies.forEach { it.close() }; extraRequests.forEach { it.close() }; runtimes.forEach { it.stop() }
        job.cancelAndJoin(); session.close()
    }
    companion object {
        const val KRX = "krx.usd-krw-futures"
        const val GENERAL = "investing.usd"
        fun defaultCatalog() = GraphCatalog(1.hours, listOf("usd", "tether").associateWith { tab ->
            GraphCatalogTab(tab, tab, emptyMap(), mapOf(GraphPeriod.ONE_DAY to GraphCatalogPeriod(listOf(GENERAL, KRX), listOf(GENERAL, KRX)))) })
        fun catalogDto() = GraphV2CatalogResponse(listOf("usd", "tether").map { tab ->
            GraphV2CatalogTab(tab, tab, emptyMap(), listOf("1d", "3m").associateWith {
                GraphV2CatalogPeriod(listOf(GENERAL, KRX), listOf(GENERAL, KRX)) }) }, "2026-05-27", listOf("3m"), 3600)
        fun tabDto(key: GraphKey, rate: Double = 1500.0) = GraphV2TabResponse(key.tab, key.period.code,
            listOf(GENERAL, KRX).map { id -> GraphV2Series(id, id, "krw", "KRW", 2,
                listOf(GraphV2Point(O03_AT - 10.minutes, rate, "x")), GraphV2Provenance(false, emptyList()), null) },
            GraphV2Metadata(O03_AT, if (key.period == GraphPeriod.ONE_DAY) "10min" else "1d", GraphV2Range("2026-09-25", "2026-09-26")),
            inProgress = if (key.period == GraphPeriod.ONE_DAY) listOf(GENERAL, KRX).associateWith {
                GraphV2InProgressSeed(O03_AT, rate + 2, rate - 2, rate, O03_AT)
            } else null)
        fun <T> ok(body: T) = AuthenticatedHttpResponse(200, Headers.headersOf(), body, null, byteArrayOf(1))
    }
    /** Fake persistence: no directories, files, production purger or namespace changes. */
    class FakeDisk : GraphV2DiskStore {
        val held = mutableMapOf<GraphV2WriteTicket, GraphV2DiskComponents>()
        val reserved = mutableListOf<GraphV2DiskComponents>()
        val ioTrace = mutableListOf<List<Any?>>()
        val admissions = mutableListOf<Pair<Boolean, Boolean>>()
        val withdrawals = mutableListOf<GraphV2WriteTicket>()
        var seed: GraphV2DiskComponents? = null
        var seedGate: CompletableDeferred<Unit>? = null
        var readCount = 0
        override fun reserveWrite(components: GraphV2DiskComponents): GraphV2WriteReservation {
            reserved += components
            ioTrace.add(listOf("reserve", components))
            val ticket = GraphV2WriteTicket(); held[ticket] = components
            return GraphV2WriteReservation.Reserved(ticket)
        }
        override fun cancelWrite(ticket: GraphV2WriteTicket) { ioTrace.add(listOf("cancel")); held.remove(ticket) }
        override fun withdrawKrx(ticket: GraphV2WriteTicket): Boolean {
            ioTrace.add(listOf("withdrawKRX"))
            withdrawals += ticket
            val before = held[ticket] ?: return false
            held[ticket] = before.copy(krx = null)
            return before.krx != null
        }
        override suspend fun write(ticket: GraphV2WriteTicket, admission: GraphV2IoAdmission): GraphV2WriteReport {
            val g = admission.admits(GraphV2DiskComponent.GENERAL)
            val k = admission.admits(GraphV2DiskComponent.KRX) && held[ticket]?.krx != null
            ioTrace.add(listOf("write", g, k))
            admissions += g to k; held.remove(ticket)
            return GraphV2WriteReport(if (g) GraphV2ComponentWriteOutcome.Replaced else GraphV2ComponentWriteOutcome.Skipped("blocked"),
                if (k) GraphV2ComponentWriteOutcome.Replaced else GraphV2ComponentWriteOutcome.Skipped("blocked"))
        }
        override suspend fun readGeneral(key: GraphV2GeneralKey, catalog: GraphCatalog?, admission: GraphV2IoAdmission): GraphV2DiskRead<GraphV2GeneralEnvelope> {
            ioTrace.add(listOf("readGeneral", key))
            readCount++; val original = seed?.general; seedGate?.await()
            return original?.let { GraphV2DiskRead.Found(it) } ?: GraphV2DiskRead.Absent
        }
        override suspend fun readKrx(key: GraphV2KrxKey, catalog: GraphCatalog?, admission: GraphV2IoAdmission): GraphV2DiskRead<GraphV2KrxEnvelope> {
            ioTrace.add(listOf("readKRX", key))
            return seed?.krx?.let { GraphV2DiskRead.Found(it) } ?: GraphV2DiskRead.Absent
        }
        override suspend fun purge(component: GraphV2DiskComponent, scope: PurgeScope, namespace: PurgeNamespace): TargetOutcome =
            throw AssertionError("premise: S6-3b-1 must not call disk purge")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class O03HeadSession(test: TestScope, val row: String) {
    val cleanup = S6KrxCleanupRig(test, row, on = false)
    val r get() = cleanup.r
    val inputs = mutableListOf<TopicGraphInput>()
    val offers = mutableListOf<Pair<TopicGraphInput, TopicGraphOffer>>()
    fun c(text: String) = "contract($row): $text"
    fun yes(text: String, value: Boolean) = assertTrue(c(text), value)
    fun <T : Any> need(value: T?, text: String): T = value ?: throw AssertionError(c(text))
    fun deliver(text: String) { r.wire.deliver(text); r.drain() }
    fun renewal(topic: String = TopicCatalogue.KRX_FUTURES): TopicSubscribeRequest {
        // A <=180s lease schedules renewal during ACK drain; observe the live lane before waiting.
        fun outstanding(): TopicSubscribeRequest? {
            val live = field(cleanup.session, "connection") ?: return null
            val lane = field(live, "controlOwner") as Long? ?: return null
            @Suppress("UNCHECKED_CAST") val commands = field(live, "commands") as Map<Long, Any>
            val running = commands[lane] ?: return null
            val purpose = field(running, "purpose") as TopicCommandPurpose
            if (purpose !in setOf(TopicCommandPurpose.LEASE_RENEWAL, TopicCommandPurpose.LEASE_RENEWAL_WITH_REVALIDATION)) return null
            return r.sends.lastOrNull { it.accepted && it.request.type == "subscribe" && topic in it.request.topics }?.request
        }
        outstanding()?.let { return it }
        val before = r.sends.size
        // Actual lease policy is max(0, duration-180-jitter). Advance to its deadline, not an invented 1.2s wait.
        val live = need(field(cleanup.session, "connection"), "renewal has a current connection")
        val registry = field(live, if (r.on && TopicCatalogue.carriesKrx(topic)) "krxLeases" else "leases") as com.jay.fxi.domain.model.TopicLeaseRegistry
        val grant = need(registry.held[topic], "renewal has an actual acknowledged lease")
        val wait = (grant.expiresAtMillis - 180_000L - r.clock.nowMillis()).coerceAtLeast(0)
        r.advance(wait)
        r.until(c("actual renewal is SENT on its live control lane"), 1_200) {
            r.sends.drop(before).any { it.accepted && it.request.type == "subscribe" && topic in it.request.topics }
        }
        return need(outstanding(), "actual renewal remains pending on the current control lane")
    }
    fun runtimeFactory(): TopicRuntimeFactory = TopicRuntimeFactory(
        webSocketFactory = {
            KrxWire { text, accepted ->
                r.requests.lastOrNull { "encoded-${it.requestId}" == text }?.let {
                    r.sends += KrxSessionIntegrationRig.WireSend(it, accepted, r.clock.nowMillis())
                }
            }.also { r.wires += it }
        },
        webSocketUrl = TopicSessionKrxHarness.URL,
        decode = TopicFrameDecoder(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })::decode,
        bootstrap = { owner, topic, admitted -> need(r.bootstrapOverride, "actual REST service dependency")(owner, topic, admitted) },
        issuer = r.issuer,
        fences = com.jay.fxi.data.auth.AuthFenceStream { it(r.identity) },
        liveFence = { r.identity }, recoveries = com.jay.fxi.data.auth.AuthCredentialRecoveryStream { },
        tabs = object : com.jay.fxi.data.local.FreeTabStore {
            override suspend fun lastTab(uid: String) = com.jay.fxi.domain.model.FreeTab.USD
            override suspend fun remember(uid: String, tab: com.jay.fxi.domain.model.FreeTab) = Unit
        },
        credentials = field(cleanup.session, "credentials") as TopicCommandCredentials,
        orders = r.h.parts.orders, authority = r.uses, clock = r.clock,
        newBootstrapFloor = { object : TopicBootstrapFloor {
            override fun record(statusCode: Int?, retryAfter: String?) = Unit
            override fun notBeforeMillis() = 0L
        } }, newScope = { r.h.parts.scope },
        encode = { r.requests += it; "encoded-${it.requestId}" },
        newRequestId = { "graph-runtime-${r.requests.size}" }, jitter = { 0.0 },
        krxSendReader = r.uses, krxMemorySnapshot = { r.snapshotSupply() }
    )

    fun attach(runtime: TopicRuntime, wallClock: () -> Long) {
        val assembled = field(runtime, "session") as TopicSessionCoordinator
        setField(assembled, "wallClock", wallClock)
        assertSame("contract($row): scripted TSC wallClock installed before start", wallClock, field(assembled, "wallClock"))
        setField(assembled, "desired", r.desired)
        setField(r, "session", assembled)
        val actualInput = field(assembled, "graphSink") as TopicGraphSink
        setField(assembled, "graphSink", TopicGraphSink { input -> inputs += input; actualInput.tryOffer(input).also { offers += input to it } })
    }
    fun close() = cleanup.close()
}

/** Explicit schema frozen from HEAD constructors/properties. New candidate fields are never discovered or serialized. */
internal class O03HeadProjection(private val column: String) {
    private val ids = linkedMapOf<String, String>()
    private val randomId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private fun responseId(id: String): String = if (randomId.matches(id))
        ids.getOrPut(id) { "response#${ids.size + 1}" } else id
    private fun record(type: String, vararg fields: Pair<String, Any?>): JsonElement = buildJsonObject {
        put("type", type)
        for ((name, item) in fields) put(name, value(item))
    }
    fun input(x: TopicGraphInput): JsonElement = when (x) {
        is TopicGraphInput.Observations -> record("Observations", "sequence" to x.sequence, "topic" to x.topic,
            "path" to x.path, "owner" to x.attribution.owner, "grantEpoch" to x.attribution.grantEpoch,
            "lifetime" to x.attribution.lifetime, "connectionGeneration" to x.connectionGeneration, "candidates" to x.candidates)
        is TopicGraphInput.Continuity -> record("Continuity", "sequence" to x.sequence, "kind" to x.kind,
            "reason" to x.reason, "topics" to x.topics, "paths" to x.paths, "owner" to x.authority.owner,
            "grantEpoch" to x.authority.grantEpoch, "lifetime" to x.authority.lifetime,
            "connectionGeneration" to x.connectionGeneration, "occurredAtEpochMillis" to x.occurredAtEpochMillis, "payload" to x.payload)
    }
    fun value(x: Any?): JsonElement = when (x) {
        null -> JsonNull
        is JsonElement -> x
        is String -> JsonPrimitive(x)
        is Number -> JsonPrimitive(x)
        is Boolean -> JsonPrimitive(x)
        is Enum<*> -> JsonPrimitive(x.name)
        is Instant -> record("Instant", "epochSeconds" to x.epochSeconds, "nanosecondsOfSecond" to x.nanosecondsOfSecond)
        is Duration -> JsonPrimitive(x.inWholeNanoseconds)
        is Throwable -> record("Throwable", "class" to x.javaClass.name, "message" to x.message)
        is Pair<*, *> -> JsonArray(listOf(value(x.first), value(x.second)))
        is Map<*, *> -> JsonArray(x.entries.map { JsonArray(listOf(value(it.key), value(it.value))) }.sortedBy { it[0].toString() })
        is Set<*> -> JsonArray(x.map { value(it) }.sortedBy { it.toString() })
        is List<*> -> JsonArray(x.map { value(it) })
        is ClosedRange<*> -> record("Range", "start" to x.start, "endInclusive" to x.endInclusive)
        is com.jay.fxi.data.graph.GraphObservationSeriesKey -> record("GraphObservationSeriesKey", "scope" to x.scope, "seriesId" to x.seriesId)
        is com.jay.fxi.data.graph.GraphObservationId -> record("GraphObservationId", "source" to x.source, "asset" to x.asset, "observedAt" to x.observedAt, "rate" to x.rate)
        is com.jay.fxi.data.graph.GraphObservation -> record("GraphObservation", "seriesKey" to x.seriesKey, "id" to x.id)
        is com.jay.fxi.data.graph.GraphObservedBucket -> record("GraphObservedBucket", "start" to x.start, "high" to x.high, "low" to x.low, "close" to x.close)
        is com.jay.fxi.data.graph.GraphBucketRange -> record("GraphBucketRange", "high" to x.high, "low" to x.low)
        is com.jay.fxi.data.graph.GraphRecoveryDemand -> record("GraphRecoveryDemand", "generation" to x.generation, "reasons" to x.reasons)
        is com.jay.fxi.data.graph.GraphRecoveryRequest -> record("GraphRecoveryRequest", "seriesKey" to x.seriesKey, "applicationVersion" to x.applicationVersion, "capturedGeneration" to x.capturedGeneration)
        is com.jay.fxi.data.graph.GraphV2GeneralKey -> record("GraphV2GeneralKey", "uid" to x.uid, "userAccessEpoch" to x.userAccessEpoch, "tab" to x.tab, "period" to x.period)
        is com.jay.fxi.data.graph.GraphV2KrxKey -> record("GraphV2KrxKey", "uid" to x.uid, "userAccessEpoch" to x.userAccessEpoch, "krxCapabilityEpoch" to x.krxCapabilityEpoch, "tab" to x.tab, "period" to x.period)
        is com.jay.fxi.data.graph.GraphV2ServerMetadata -> record("GraphV2ServerMetadata", "bucketSize" to x.bucketSize, "fetchedAt" to x.fetchedAt, "rangeStart" to x.rangeStart, "rangeEnd" to x.rangeEnd, "domainStartAt" to x.domainStartAt, "domainEndAt" to x.domainEndAt, "liveDomainMode" to x.liveDomainMode)
        is com.jay.fxi.data.graph.GraphV2IndexedSeries -> record("GraphV2IndexedSeries", "ordinal" to x.ordinal, "series" to x.series)
        is com.jay.fxi.data.graph.GraphV2StoredComponent -> record("GraphV2StoredComponent", "metadata" to x.metadata, "series" to x.series, "inProgress" to x.inProgress)
        is com.jay.fxi.data.graph.GraphV2GeneralEnvelope -> record("GraphV2GeneralEnvelope", "schemaVersion" to x.schemaVersion, "key" to x.key, "responseId" to responseId(x.responseId), "component" to x.component)
        is com.jay.fxi.data.graph.GraphV2KrxEnvelope -> record("GraphV2KrxEnvelope", "schemaVersion" to x.schemaVersion, "key" to x.key, "responseId" to responseId(x.responseId), "component" to x.component)
        is com.jay.fxi.data.graph.GraphV2DiskComponents -> record("GraphV2DiskComponents", "general" to x.general, "krx" to x.krx)
        is com.jay.fxi.data.graph.GraphKey -> record("GraphKey", "tab" to x.tab, "period" to x.period)
        is com.jay.fxi.data.graph.GraphDataScope -> record("GraphDataScope", "uid" to x.uid, "userAccessEpoch" to x.userAccessEpoch)
        is com.jay.fxi.data.graph.GraphEntry -> record("GraphEntry", "tab" to x.tab, "online200At" to x.online200At)
        is com.jay.fxi.data.graph.GraphRequestSource -> record("GraphRequestSource", "fence" to x.fence, "lifetime" to x.lifetime)
        is com.jay.fxi.data.graph.GraphRequestState -> record("GraphRequestState", "dataScope" to x.dataScope, "catalog" to x.catalog, "entries" to x.entries, "inFlight" to x.inFlight, "failures" to x.failures, "source" to x.source)
        is com.jay.fxi.data.graph.GraphV2AccessCapture -> record("GraphV2AccessCapture", "fence" to x.fence, "lifetime" to x.lifetime, "krxCapabilityEpoch" to x.krxCapabilityEpoch, "capabilityInvalidations" to x.capabilityInvalidations)
        is com.jay.fxi.data.graph.GraphV2CapabilityConfiguration -> record("GraphV2CapabilityConfiguration", "recordEpoch" to x.recordEpoch, "allowed" to x.allowed)
        is com.jay.fxi.data.graph.GraphV2AccessBinding -> record("GraphV2AccessBinding", "captured" to x.captured, "configuration" to x.configuration)
        is com.jay.fxi.data.graph.GraphSelectionBinding -> record("GraphSelectionBinding", "identity" to x.identity, "key" to x.key, "bindingGeneration" to x.bindingGeneration)
        is com.jay.fxi.domain.model.GraphAxisGroup -> record("GraphAxisGroup", "unit" to x.unit, "decimals" to x.decimals, "side" to x.side)
        is com.jay.fxi.domain.model.GraphCatalog -> record("GraphCatalog", "ttl" to x.ttl, "tabs" to x.tabs)
        is com.jay.fxi.domain.model.GraphCatalogTab -> record("GraphCatalogTab", "id" to x.id, "label" to x.label, "axisGroups" to x.axisGroups, "periods" to x.periods)
        is com.jay.fxi.domain.model.GraphCatalogPeriod -> record("GraphCatalogPeriod", "allSeries" to x.allSeries, "defaultVisible" to x.defaultVisible)
        is com.jay.fxi.domain.model.GraphV2InProgress -> record("GraphV2InProgress", "bucketStart" to x.bucketStart, "high" to x.high, "low" to x.low, "close" to x.close, "sampledAt" to x.sampledAt)
        is com.jay.fxi.domain.model.GraphV2Tab -> record("GraphV2Tab", "tab" to x.tab, "period" to x.period, "bucketSize" to x.bucketSize, "fetchedAt" to x.fetchedAt, "graph" to x.graph, "inProgress" to x.inProgress)
        is com.jay.fxi.domain.model.FreeGraph -> record("FreeGraph", "bucketSize" to x.bucketSize, "series" to x.series, "rangeStart" to x.rangeStart, "rangeEnd" to x.rangeEnd, "domainStartAt" to x.domainStartAt, "domainEndAt" to x.domainEndAt, "liveDomainMode" to x.liveDomainMode)
        is com.jay.fxi.domain.model.FreeGraphSeries -> record("FreeGraphSeries", "seriesId" to x.seriesId, "points" to x.points, "label" to x.label, "axisGroup" to x.axisGroup, "unit" to x.unit, "decimals" to x.decimals, "insufficientHistory" to x.insufficientHistory, "perPointMetadata" to x.perPointMetadata, "carryIn" to x.carryIn)
        is com.jay.fxi.domain.model.FreeGraphPoint -> record("FreeGraphPoint", "timestamp" to x.timestamp, "rate" to x.rate, "high" to x.high, "low" to x.low, "source" to x.source, "closeBasis" to x.closeBasis, "sourceMethod" to x.sourceMethod, "contractCode" to x.contractCode)
        is com.jay.fxi.domain.model.FreeGraphCarryIn -> record("FreeGraphCarryIn", "rate" to x.rate, "observedAt" to x.observedAt)
        is com.jay.fxi.domain.model.GraphSeriesSelection -> record("GraphSeriesSelection", "visibleSeriesIds" to x.visibleSeriesIds, "initializedSeries" to x.initializedSeries)
        is com.jay.fxi.ui.graph.LinePoint -> record("LinePoint", "ts" to x.ts, "rate" to x.rate)
        is com.jay.fxi.ui.graph.BandPoint -> record("BandPoint", "ts" to x.ts, "low" to x.low, "high" to x.high)
        is com.jay.fxi.ui.graph.PreparedSeries -> record("PreparedSeries", "seriesId" to x.seriesId, "label" to x.label, "axisGroup" to x.axisGroup, "linePoints" to x.linePoints, "bandPoints" to x.bandPoints, "extrema" to x.extrema, "lastObservation" to x.lastObservation, "insufficientHistory" to x.insufficientHistory, "carryInApplied" to x.carryInApplied)
        is com.jay.fxi.ui.graph.DisplayDomain -> record("DisplayDomain", "start" to x.start, "end" to x.end, "mode" to x.mode)
        is com.jay.fxi.ui.graph.TimeRange -> record("TimeRange", "start" to x.start, "end" to x.end)
        is com.jay.fxi.ui.graph.PreparedGraph -> record("PreparedGraph", "period" to x.period, "order" to x.order, "bySeries" to x.bySeries, "domain" to x.domain, "dataBounds" to x.dataBounds)
        is com.jay.fxi.ui.graph.GraphSeriesStyle -> record("GraphSeriesStyle", "colorHex" to x.colorHex, "label" to x.label, "lineWidthDp" to x.lineWidthDp)
        is com.jay.fxi.ui.premium.graph.GraphV2ChartModel -> record("GraphV2ChartModel", "prepared" to x.prepared, "renderedIds" to x.renderedIds, "rightEdgeNow" to x.rightEdgeNow)
        is com.jay.fxi.ui.premium.graph.GraphV2SeriesToggle -> record("GraphV2SeriesToggle", "seriesId" to x.seriesId, "style" to x.style, "selected" to x.selected, "enabled" to x.enabled, "axisGroup" to x.axisGroup)
        is com.jay.fxi.ui.premium.graph.GraphV2UiToken -> record("GraphV2UiToken", "owner" to x.owner, "fence" to x.fence, "lifetime" to x.lifetime, "binding" to x.binding, "tab" to x.tab, "period" to x.period, "screenGeneration" to x.screenGeneration, "surface" to x.surface, "surfaceGeneration" to x.surfaceGeneration)
        is com.jay.fxi.ui.premium.graph.GraphV2ScreenState -> record("GraphV2ScreenState", "tab" to x.tab, "activePeriod" to x.activePeriod, "periods" to x.periods, "content" to x.content, "selectionStatus" to x.selectionStatus, "selection" to x.selection, "toggles" to x.toggles, "chart" to x.chart, "refreshing" to x.refreshing, "requestFailure" to x.requestFailure, "notice" to x.notice, "fullscreenOpen" to x.fullscreenOpen, "inlineToken" to x.inlineToken, "fullscreenToken" to x.fullscreenToken)
        is com.jay.fxi.data.auth.AuthIdentityFence -> record("AuthIdentityFence", "uid" to x.uid, "authGeneration" to x.authGeneration)
        is com.jay.fxi.data.remote.TopicSessionFence -> record("TopicSessionFence", "identity" to x.identity, "userAccessEpoch" to x.userAccessEpoch, "grant" to x.grant)
        is com.jay.fxi.data.remote.TopicDisplayOwner -> record("TopicDisplayOwner", "identity" to x.identity, "grantEpoch" to x.grantEpoch)
        is com.jay.fxi.data.remote.TopicDisplayState -> record("TopicDisplayState", "owner" to x.owner, "rates" to x.rates, "containsSeed" to x.containsSeed, "connection" to x.connection, "recovery" to x.recovery, "cachedRefreshResolved" to x.cachedRefreshResolved, "topicState" to x.topicState)
        is com.jay.fxi.data.remote.TopicGrantToken -> record("TopicGrantToken", "value" to x.value)
        is com.jay.fxi.data.remote.TopicUseLifetime -> record("TopicUseLifetime", "grant" to x.grant, "invalidations" to x.invalidations)
        is com.jay.fxi.data.remote.TopicGraphLoss -> record("TopicGraphLoss", "revision" to x.revision, "count" to x.count, "firstSequence" to x.firstSequence, "lastSequence" to x.lastSequence, "reasons" to x.reasons, "topics" to x.topics, "paths" to x.paths, "firstOccurredAtEpochMillis" to x.firstOccurredAtEpochMillis, "lastOccurredAtEpochMillis" to x.lastOccurredAtEpochMillis, "authorities" to x.authorities)
        is com.jay.fxi.data.remote.TopicGraphAuthorityKey -> record("TopicGraphAuthorityKey", "owner" to x.owner, "grantEpoch" to x.grantEpoch)
        is com.jay.fxi.data.entitlements.TopicAccessFacts -> record("TopicAccessFacts", "token" to x.token, "issuedFor" to x.issuedFor, "binding" to x.binding, "recordFence" to x.recordFence, "decisionGeneration" to x.decisionGeneration, "tokenStanding" to x.tokenStanding, "userBlocks" to x.userBlocks, "capabilityBlocks" to x.capabilityBlocks, "recordUnconfirmed" to x.recordUnconfirmed, "userContextUncertain" to x.userContextUncertain, "capabilityContextUncertain" to x.capabilityContextUncertain)
        is com.jay.fxi.data.entitlements.TopicAccessSnapshot -> record("TopicAccessSnapshot", "revision" to x.revision, "facts" to x.facts, "userInvalidations" to x.userInvalidations, "lastUserEnd" to x.lastUserEnd, "lastCapabilityEnd" to x.lastCapabilityEnd, "userEndInvalidationsFloor" to x.userEndInvalidationsFloor, "krxPermitSeq" to x.krxPermitSeq, "capabilityInvalidations" to x.capabilityInvalidations)
        is com.jay.fxi.data.entitlements.TopicAccessEnd -> record("TopicAccessEnd", "sequence" to x.sequence, "reason" to x.reason, "binding" to x.binding, "ownerUid" to x.ownerUid, "namespace" to x.namespace)
        is com.jay.fxi.data.entitlements.EntitlementsIdentity -> record("EntitlementsIdentity", "ownerUid" to x.ownerUid, "authGeneration" to x.authGeneration)
        is com.jay.fxi.data.entitlements.TopicGrantContext -> record("TopicGrantContext", "identity" to x.identity, "access" to x.access, "generation" to x.generation)
        is com.jay.fxi.data.entitlements.AccessFence -> record("AccessFence", "ownerUid" to x.ownerUid, "userAccessEpoch" to x.userAccessEpoch, "krxCapabilityEpoch" to x.krxCapabilityEpoch)
        is com.jay.fxi.domain.model.TopicQuoteKey -> record("TopicQuoteKey", "source" to x.source, "asset" to x.asset)
        is com.jay.fxi.domain.model.TopicQuote -> record("TopicQuote", "source" to x.source, "asset" to x.asset, "rate" to x.rate, "at" to x.at)
        is com.jay.fxi.domain.model.TopicDollarIndex -> record("TopicDollarIndex", "rate" to x.rate, "at" to x.at, "source" to x.source)
        is com.jay.fxi.domain.model.TopicRates -> record("TopicRates", "quotes" to x.quotes, "dollarIndex" to x.dollarIndex)
        is com.jay.fxi.domain.model.TopicSubscriptionSnapshot -> record("TopicSubscriptionSnapshot", "topics" to x.topics, "controlState" to x.controlState, "wholeFailure" to x.wholeFailure, "authResolution" to x.authResolution)
        is com.jay.fxi.domain.model.TopicSubscriptionState -> record("TopicSubscriptionState", "desired" to x.desired, "confirmed" to x.confirmed, "receiveGeneration" to x.receiveGeneration, "deliveryState" to x.deliveryState, "rejection" to x.rejection, "revalidationAttempt" to x.revalidationAttempt)
        is com.jay.fxi.data.local.GraphSelectionKey -> record("GraphSelectionKey", "uid" to x.uid, "audience" to x.audience, "tab" to x.tab)
        is com.jay.fxi.data.local.GraphSelectionRecord -> record("GraphSelectionRecord", "schemaVersion" to x.schemaVersion, "uid" to x.uid, "audience" to x.audience, "tab" to x.tab, "visibleSeriesIds" to x.visibleSeriesIds, "initializedSeries" to x.initializedSeries)
        is com.jay.fxi.data.graph.GraphServerBucket.Seeds -> record("Seeds", "range" to x.range, "close" to x.close)
        is com.jay.fxi.data.graph.GraphServerBucket.Closed -> record("Closed", "point" to x.point)
        is com.jay.fxi.data.remote.TopicGraphCandidate.Quote -> record("Quote", "source" to x.source, "asset" to x.asset, "rate" to x.rate, "timestamp" to x.timestamp, "rateChangedAt" to x.rateChangedAt)
        is com.jay.fxi.data.remote.TopicGraphCandidate.DollarIndex -> record("DollarIndex", "rate" to x.rate, "timestamp" to x.timestamp, "source" to x.source)
        is com.jay.fxi.data.remote.TopicGraphEventPayload.WsEnded -> record("WsEnded", "cause" to x.cause)
        is com.jay.fxi.data.remote.TopicGraphEventPayload.RestUndelivered -> record("RestUndelivered", "resultKind" to x.resultKind)
        is com.jay.fxi.data.remote.TopicGraphEventPayload.AckApplied -> record("AckApplied", "confirmedTopics" to x.confirmedTopics, "rejected" to x.rejected)
        is com.jay.fxi.data.remote.TopicGraphEventPayload.AuthFailure -> record("AuthFailure", "resolution" to x.resolution)
        is com.jay.fxi.data.remote.TopicRecoveryDisplay.Reconnecting -> record("Reconnecting", "attempt" to x.attempt)
        is com.jay.fxi.data.graph.GraphObservationOrder -> record("GraphObservationOrder", "sourcePriority" to x.sourcePriority)
        is com.jay.fxi.data.graph.GraphObservationState -> record("GraphObservationState", "seriesKey" to x.seriesKey, "order" to x.order, "buckets" to x.buckets, "tip" to x.tip, "observations" to x.observations)
        is com.jay.fxi.data.graph.GraphSeededObservationState -> record("GraphSeededObservationState", "app" to x.app, "serverBuckets" to x.serverBuckets)
        is com.jay.fxi.data.graph.GraphRecoverableState -> record("GraphRecoverableState", "data" to x.data, "pending" to x.pending, "gapHistory" to x.gapHistory, "generation" to x.generation, "lastAppliedVersion" to x.lastAppliedVersion, "lastCutoff" to x.lastCutoff)
        is TopicWholeRequestFailure.TemporarilyUnavailable -> record("TemporarilyUnavailable", "retryAfterSeconds" to x.retryAfterSeconds)
        TopicWholeRequestFailure.InvalidToken -> JsonPrimitive("TopicWholeRequestFailure.InvalidToken")
        TopicWholeRequestFailure.InvalidRequest -> JsonPrimitive("TopicWholeRequestFailure.InvalidRequest")
        TopicWholeRequestFailure.RequestTooLarge -> JsonPrimitive("TopicWholeRequestFailure.RequestTooLarge")
        TopicRecoveryDisplay.None -> JsonPrimitive("TopicRecoveryDisplay.None")
        TopicRecoveryDisplay.Connecting -> JsonPrimitive("TopicRecoveryDisplay.Connecting")
        TopicRecoveryDisplay.Exhausted -> JsonPrimitive("TopicRecoveryDisplay.Exhausted")
        else -> throw AssertionError("contract(O03/$column): HEAD projection has no explicit schema for ${x.javaClass.name}")
    }
}
