package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.*
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.datetime.Instant
import okhttp3.Headers
import org.junit.Assert.*

/** All consumers are real. Fakes provide only HTTP, clock, preference persistence and disk ports. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class S6KrxGraphRig(val test: TestScope, val row: String, on: Boolean = true) {
    val session = S6KrxGraphSessionRig(test, row, on)
    val r get() = session.r
    val dispatcher = StandardTestDispatcher(test.testScheduler)
    private val job = SupervisorJob()
    val scope = CoroutineScope(job + dispatcher)
    private val base = test.testScheduler.currentTime
    var clockReads = 0
    val clockTrace = mutableListOf<Instant>()
    val clock = AppClock {
        clockReads++
        (S6KrxGraphSessionRig.AT + (test.testScheduler.currentTime - base).milliseconds).also { clockTrace += it }
    }
    val graphSnapshotTrace = mutableListOf<List<Any?>>()
    fun graphSnapshot(): TopicAccessSnapshot = snapshot.also {
        graphSnapshotTrace.add(listOf(it.revision, it.krxPermitSeq, it.userInvalidations, it.capabilityInvalidations, it.facts))
    }
    val revisions get() = r.pac.accessRevisions
    var snapshotOverride: TopicAccessSnapshot? = null
    val snapshot get() = snapshotOverride ?: r.snapshotSupply()
    var gateReads = 0
    var applyReads = 0
    var onApplyRead: (() -> Unit)? = null
    var owner: TopicSessionFence? = null
    var catalog: GraphCatalog? = defaultCatalog()
    val gate = GraphV2AccessGate({ r.identity }, { owner }, { gateReads++; snapshot }, { true }, session.fence)
    val recorder = GraphRecorder(scope, revisions, {
        if (Thread.currentThread().stackTrace.any { it.className == GraphRecorder::class.java.name && it.methodName == "applyResponse" }) {
            applyReads++; onApplyRead?.invoke()
        }
        snapshot
    }, { owner }, { catalog }, gate, clock)
    val sink = GraphRecorderTopicSink(scope, recorder, startImmediately = false)
    val raw get() = recorder.state.value
    val dataScope get() = GraphDataScope(need(owner, "owner").identity.uid, need(owner?.userAccessEpoch, "USER epoch"))
    val krxKey get() = GraphObservationSeriesKey(dataScope, KRX)
    val generalKey get() = GraphObservationSeriesKey(dataScope, GENERAL)
    val use get() = need(snapshot.acquireUse(need(owner, "owner")), "GENERAL consumer lifetime")
    private val assemblies = mutableListOf<GraphRuntimeAssembly>()
    private val holders = mutableListOf<GraphV2ScreenStateHolder>()
    private val selections = mutableListOf<GraphSeriesSelectionSession>()
    fun c(text: String) = session.c(text)
    fun yes(text: String, value: Boolean) = session.yes(text, value)
    fun eq(text: String, expected: Any?, actual: Any?) = session.eq(text, expected, actual)
    fun <T : Any> need(value: T?, text: String): T = session.need(value, text)
    suspend fun start(ack: Boolean = true, leaseSeconds: Long = 600) {
        session.start(ack, leaseSeconds)
        owner = need(r.issuer.topicGrantResult().fence, "actual delivered fence")
        yes("consumer is inside admitted GENERAL window", snapshot.admitsUse(use))
        session.target = TopicGraphSink { input ->
            when (input) { is TopicGraphInput.Observations -> recorder.observe(input); is TopicGraphInput.Continuity -> recorder.observe(input) }
            TopicGraphOffer.ENQUEUED
        }
    }
    fun adopt(rate: Double = 1555.0, at: Instant = S6KrxGraphSessionRig.AT, changed: Instant? = null): TopicGraphInput.Observations {
        val input = session.ws(rate, at, changed)
        yes("new KRX series adopted before exercising consumer", krxKey in raw.series)
        eq("recorder retains the original series anchor", input.krxGraphStamp, raw.krxSeriesOwners[KRX])
        return input
    }
    fun exposed() = recorder.exposed(need(owner, "owner"), use)
    fun krx() = need(raw.series[krxKey], "raw KRX series")
    fun print(s: GraphRecoverableState) = listOf(s.data.app.buckets, s.data.app.observations, s.data.app.tip,
        s.data.serverBuckets, s.pending, s.gapHistory, s.generation, s.lastAppliedVersion, s.lastCutoff)
    fun generalPrint() = raw.series.filterKeys { it.seriesId != KRX }.mapValues { print(it.value) }
    /** boundary-input: synthetic valid GENERAL observation, using the actual admitted owner/use. */
    fun general(at: Instant = S6KrxGraphSessionRig.AT, rate: Double = 1444.0): TopicGraphInput.Observations {
        val input = TopicGraphInput.Observations(100, "fx:usd-krw", TopicGraphPath.WS,
            TopicUseAttribution(Any(), need(owner, "owner"), 1, use), 1,
            listOf(TopicGraphCandidate.Quote("investing", "usd-krw", rate, at, null)))
        recorder.observe(input)
        assertTrue("premise: $row GENERAL fixture maps or waits for catalog",
            if (catalog == null) input in raw.pending.inputs else generalKey in raw.series)
        return input
    }
    /** boundary-input: synthetic continuity tests shared consumer fences; actual producers use session.inputs. */
    fun fact(input: TopicGraphInput.Observations, kind: TopicGraphEventKind = TopicGraphEventKind.DELIVERY_INTERRUPTED,
        topics: Set<String> = setOf(TopicCatalogue.KRX_FUTURES),
        reason: TopicGraphEventReason = TopicGraphEventReason.WS_ENDED): TopicGraphInput.Continuity = TopicGraphInput.Continuity(
        input.sequence + 1, kind, reason, topics, setOf(TopicGraphPath.WS),
        TopicGraphAuthority(input.attribution.sessionKey, input.attribution.owner, input.attribution.grantEpoch,
            input.attribution.lifetime, input.krxGraphStamp), input.connectionGeneration, clock.now().toEpochMilliseconds())
    /** boundary-input: fault only the first ledger write; the real sink catches it and records the loss. */
    fun failNextKrxLedgerWrite(sink: GraphRecorderTopicSink) {
        @Suppress("UNCHECKED_CAST") val original = field(sink, "krxLedger") as MutableMap<String, Set<KrxGraphStamp>>
        var armed = true
        val fault = object : MutableMap<String, Set<KrxGraphStamp>> by original {
            override fun put(key: String, value: Set<KrxGraphStamp>): Set<KrxGraphStamp>? {
                if (armed) { armed = false; throw IllegalArgumentException("boundary-input: one ledger write fails") }
                return original.put(key, value)
            }
        }
        setField(sink, "krxLedger", fault)
        assertSame("premise: $row ledger fault dependency installed", fault, field(sink, "krxLedger"))
    }
    fun requests() = recorder.captureRequests(raw.series.keys, need(owner, "owner"), use)
    fun originalRequest(): GraphRecoveryRequest = need(requests().singleOrNull { it.seriesKey == krxKey }, "KRX recovery request captured")
    fun cutoff(input: TopicGraphInput.Observations): KrxGraphTopicCutoff {
        val before = session.fence.revisions.value
        val result = session.ingress.topicUnavailable(need(input.krxGraphStamp, "original stamp").scope)
        eq("cutoff installed synchronously", KrxGraphTopicResult.INSTALLED, result.result)
        yes("inside cutoff window before behavior assertions", session.fence.revisions.value > before && !session.fence.permits(input.krxGraphStamp!!))
        return result
    }
    /** boundary-input: snapshot-only C-1 boundary, with no physical purge or source recapture. */
    fun boundaryRoundTrip(kind: String, stamp: KrxGraphStamp) {
        val before = snapshot
        val record = need(before.facts.recordFence, "record fence")
        snapshotOverride = if (kind == "epoch") before.copy(revision = before.revision + 2,
            facts = before.facts.copy(recordFence = record.copy(krxCapabilityEpoch = record.krxCapabilityEpoch + "-K2")))
        else before.copy(revision = before.revision + 2, capabilityInvalidations = before.capabilityInvalidations + 1)
        yes("boundary-input only, old use withdrawn without physical purge", !snapshot.admitsKrxUse(stamp.scope.use))
        eq("same GENERAL use remains admitted", true, snapshot.admitsUse(use))
    }
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
    fun coordinator(): GraphV2RequestCoordinator = GraphV2RequestCoordinator(fetcher = fetcher, owners = owners,
        currentAccessFence = { owner }, uses = r.uses, protectedAdmission = { true }, accessSnapshot = { snapshot },
        scope = scope, clock = clock, rateLimitJitter = { Duration.ZERO }, onEventFailure = { failures += it },
        cachePorts = GraphV2CachePorts(disk, gate, { }, writes()), recorder = recorder)
    private val coordinators = mutableListOf<GraphV2RequestCoordinator>()
    val coordinatorInstances get() = coordinators.size
    fun readyCoordinator(): GraphV2RequestCoordinator {
        val coordinator = coordinator().also { coordinators += it }
        coordinator.start(); coordinator.onActivated(GraphKey("usd", GraphPeriod.THREE_MONTHS)); test.runCurrent()
        val call = need(sent.lastOrNull(), "catalog and initial actual HTTP started")
        call.response.complete(ok(tabDto(call.key))); test.runCurrent()
        need(coordinator.state.value.catalog, "catalog adopted by actual HTTP")
        return coordinator
    }
    /** Holds actual produced completion objects, without canceling their jobs or invoking a private handler. */
    inner class CompletionBarrier(val coordinator: GraphV2RequestCoordinator) : AutoCloseable {
        @Suppress("UNCHECKED_CAST") private val original = field(coordinator, "inbox") as Channel<Any>
        private val held = ArrayDeque<Any>()
        var enqueued = 0; private set
        init {
            setField(coordinator, "inbox", object : Channel<Any> by original {
                override fun trySend(element: Any): ChannelResult<Unit> {
                    if (element.javaClass.simpleName == "TabFinished") {
                        held.addLast(element); enqueued++
                        val acceptance = Channel<Any>(1)
                        return acceptance.trySend(element).also { acceptance.close() }
                    }
                    return original.trySend(element)
                }
            })
        }
        fun release() {
            val frozen = held.toList(); held.clear()
            frozen.forEach { yes("held original completion enters serial FIFO", original.trySend(it).isSuccess) }
            test.runCurrent()
        }
        override fun close() { setField(coordinator, "inbox", original); release() }
    }
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
    private val runtimeOwners = mutableListOf<TopicRuntime>()
    suspend fun ownerAssembly(failStart: Boolean = false): Pair<GraphRuntimeAssembly, TopicRuntime> {
        r.approve()
        owner = need(r.issuer.topicGrantResult().fence, "actual owner grant")
        val assembly = assembly()
        val builder = ProcessGraphBuilder { _, _, _, _ ->
            ProcessGraphParts(assembly, com.jay.fxi.data.free.InstallSeedSource({ "s63b1" }, dispatcher)) { tab, _, _, _ ->
                holder(assembly, tab)
            }
        }
        val actual = TopicRuntimeOwner(session.runtimeFactory(), MutableStateFlow(true), TopicForegroundStream { it(true) },
            AuthFenceStream { it(r.identity) }, { r.identity }, C4Prefs(), scope, builder,
            reportGraph = { failures += it },
            newGraphHost = { hostScope, create, report ->
                if (failStart) throw IllegalStateException("contract($row): requested host start failure")
                GraphScreenHost(hostScope, create, report)
            })
        actual.start()
        // The owner keeps the runtime through these original bound method references. Read it before dispatch/start.
        val callback = field(actual.consumer, "retryConnection")
        assertTrue("premise: $row consumer retry is the runtime's original bound reference", callback is kotlin.jvm.internal.CallableReference)
        val runtime = (callback as kotlin.jvm.internal.CallableReference).boundReceiver as TopicRuntime
        runtimeOwners += runtime
        session.attach(runtime)
        r.until(c("actual Owner/factory opens runtime"), 2_600) { r.wires.isNotEmpty() }
        r.open(); r.first(); r.ack(seconds = 1)
        need(field(session.cleanup.live, "krxActive"), "actual runtime KRX ACK accepted")
        return assembly to runtime
    }
    fun replacementSession(desired: Set<String>): TopicRuntime {
        val before = r.wires.size
        val sendsBefore = r.sends.size
        val runtime = session.runtimeFactory().create(TopicGraphSink { input ->
            when (input) {
                is TopicGraphInput.Observations -> recorder.observe(input)
                is TopicGraphInput.Continuity -> recorder.observe(input)
            }
            TopicGraphOffer.ENQUEUED
        }, krxGraphIngress = session.ingress)
        runtimeOwners += runtime
        session.attach(runtime, desired)
        runtime.start(); runtime.setOnline(true)
        r.until(c("replacement session opens on the same admitted grant"), 2_600) { r.wires.size == before + 1 }
        r.open()
        r.until(c("replacement FIRST physically SENT"), 1_000) { r.sends.drop(sendsBefore).any { it.accepted } }
        return runtime
    }
    val preparationTrace = mutableListOf<List<Any?>>()
    var duringPrepare: (() -> Unit)? = null
    fun holder(assembly: GraphRuntimeAssembly, tab: String): GraphV2ScreenStateHolder =
        holder(assembly.coordinator, assembly.gate, assembly.fences.current, assembly.recorder, tab)

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

    suspend fun close() {
        holders.forEach { it.close() }; selections.forEach { it.close() }
        assemblies.forEach { it.close() }; coordinators.forEach { it.close() }
        runtimeOwners.forEach { it.stop() }
        sink.close(); recorder.close(); job.cancel(); session.close()
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
                listOf(GraphV2Point(S6KrxGraphSessionRig.AT - 10.minutes, rate, "x")), GraphV2Provenance(false, emptyList()), null) },
            GraphV2Metadata(S6KrxGraphSessionRig.AT, if (key.period == GraphPeriod.ONE_DAY) "10min" else "1d", GraphV2Range("2026-09-25", "2026-09-26")),
            inProgress = if (key.period == GraphPeriod.ONE_DAY) listOf(GENERAL, KRX).associateWith {
                GraphV2InProgressSeed(S6KrxGraphSessionRig.AT, rate + 2, rate - 2, rate, S6KrxGraphSessionRig.AT)
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
internal fun s63b1Test(row: String, vararg columns: String, on: Boolean = true, body: suspend TestScope.(S6KrxGraphRig, String) -> Unit) {
    for (column in columns.ifEmpty { arrayOf("main") }) {
        try {
            runTest(timeout = 30.seconds) {
                val rig = S6KrxGraphRig(this, "$row/$column", on)
                var primary: Throwable? = null
                try { body(rig, column) } catch (failure: Throwable) { primary = failure; throw failure }
                finally {
                    var cleanupFailure: Throwable? = null
                    try { if (on) rig.r.beta!!.check.assertHeld() } catch (failure: Throwable) { cleanupFailure = failure }
                    try { rig.close() } catch (failure: Throwable) {
                        if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure.addSuppressed(failure)
                    }
                    cleanupFailure?.let { if (primary != null) primary.addSuppressed(it) else throw it }
                }
            }
        } catch (failure: Throwable) {
            if (failure is AssertionError && (failure.message.orEmpty().startsWith("contract(") || failure.message.orEmpty().startsWith("premise:"))) throw failure
            throw AssertionError("premise: $row/$column helper or bounded coroutine execution failed", failure)
        }
    }
}
