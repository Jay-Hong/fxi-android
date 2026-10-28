package com.jay.fxi.data.graph

import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.purge.*
import com.jay.fxi.data.remote.*
import com.jay.fxi.data.remote.S6KrxCleanupRig.Companion.field
import com.jay.fxi.data.remote.S6KrxCleanupRig.Companion.setField
import com.jay.fxi.data.remote.S6KrxGraphSessionRig.Companion.AT
import com.jay.fxi.domain.model.*
import com.jay.fxi.data.local.*
import com.jay.fxi.ui.graph.GraphPreparedBuilder
import com.jay.fxi.ui.premium.graph.GraphV2ScreenStateHolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import com.jay.fxi.data.remote.dto.*
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.ui.premium.graph.GraphScreenHost
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*

/** Adds physical ownership inspection to the unchanged 3b-1 fixture. No private method invocation. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class S6KrxGraphMemoryPurgeRig(val g: S6KrxGraphRig) {
    val session get() = g.session
    val recorder get() = g.recorder
    val raw get() = g.raw
    val sinks = mutableListOf<GraphRecorderTopicSink>()
    val consumed = mutableListOf<TopicGraphInput>()
    var beforeConsume: ((TopicGraphInput) -> Unit)? = null
    var beforeKrxLoss: ((Map<String, Set<KrxGraphStamp>>) -> Unit)? = null
    val generalLoss = mutableListOf<Pair<GraphDataScope, Map<String, Long>>>()
    val krxLoss = mutableListOf<Map<String, Set<KrxGraphStamp>>>()
    val ledger get() = field(session.cleanup.session, "graphLossLedger") as TopicGraphLossLedger
    val memoryTarget get() = g.need(PurgeManifest.byId(GraphRecorderPurgeAdapter.TARGET_ID), "manifest memory target exists")
    fun yes(message: String, value: Boolean) = g.yes(message, value)
    fun eq(message: String, expected: Any?, actual: Any?) = g.eq(message, expected, actual)
    fun <T : Any> need(value: T?, message: String): T = g.need(value, message)
    fun plan() = KrxGraphPurgePlan.forSnapshot(g.snapshot, session.fence.memoryCutoffs())
    fun cutoff(stamp: KrxGraphStamp): KrxGraphPurgePlan {
        val result = session.ingress.topicUnavailable(stamp.scope)
        eq("cutoff proof is installed before physical assertions", KrxGraphTopicResult.INSTALLED, result.result)
        return need(KrxGraphPurgePlan.forCutoff(result), "installed cutoff produces immutable plan")
    }
    suspend fun endPlan(): KrxGraphPurgePlan { session.capEnd(); return plan() }
    fun namespace(stamp: KrxGraphStamp, pendingEpoch: String? = stamp.scope.use.krxCapabilityEpoch) = PurgeNamespace(
        stamp.scope.owner.identity.uid, g.snapshot.facts.recordFence?.userAccessEpoch,
        g.snapshot.facts.recordFence?.krxCapabilityEpoch,
        PendingPurge(stamp.scope.owner.identity.uid, stamp.scope.owner.userAccessEpoch, pendingEpoch, setOf(PurgeScope.CAPABILITY)))
    fun adapter(coordinator: GraphV2RequestCoordinator, sink: GraphRecorderTopicSink = g.sink) =
        GraphRecorderPurgeAdapter(recorder, sink, coordinator, g.dispatcher)
    fun request(namespace: PurgeNamespace, target: PurgeTarget = memoryTarget) =
        PurgeRequest(target, PurgeScope.CAPABILITY, PurgeCause.CAPABILITY_REVOKE, namespace)
    fun sink(): GraphRecorderTopicSink = GraphRecorderTopicSink(g.scope, object : GraphTopicInputConsumer {
        override fun observe(input: TopicGraphInput.Observations) { consumed += input; beforeConsume?.invoke(input); recorder.observe(input) }
        override fun observe(input: TopicGraphInput.Continuity) { consumed += input; beforeConsume?.invoke(input); recorder.observe(input) }
        override fun loseTopics(ownerScope: GraphDataScope, maxInvalidationsByTopic: Map<String, Long>) {
            generalLoss += ownerScope to maxInvalidationsByTopic.toMap(); recorder.loseTopics(ownerScope, maxInvalidationsByTopic)
        }
        override fun loseKrxTopics(stampsByTopic: Map<String, Set<KrxGraphStamp>>) {
            krxLoss += copyKrxGraphStamps(stampsByTopic); beforeKrxLoss?.invoke(stampsByTopic); recorder.loseKrxTopics(stampsByTopic)
        }
        override fun close() = Unit
    }, startImmediately = false).also { sinks += it }
    @Suppress("UNCHECKED_CAST")
    fun queued(sink: GraphRecorderTopicSink): List<TopicGraphInput> =
        (field(sink, "queue") as Collection<Any>).map { field(it, "input") as TopicGraphInput }
    @Suppress("UNCHECKED_CAST")
    fun sinkLoss(sink: GraphRecorderTopicSink) = field(sink, "krxLedger") as Map<String, Set<KrxGraphStamp>>
    fun units(sink: GraphRecorderTopicSink) = field(sink, "usedUnits") as Int
    fun stamp(input: TopicGraphInput): KrxGraphStamp? = when (input) {
        is TopicGraphInput.Observations -> input.krxGraphStamp
        is TopicGraphInput.Continuity -> input.authority.krxGraphStamp
    }
    // boundary-input: preserve the actual source attribution; only envelope size/sequence is scripted.
    fun batch(input: TopicGraphInput.Observations, count: Int, sequence: Long = input.sequence) =
        TopicGraphInput.Observations(sequence, input.topic, input.path, input.attribution,
            input.connectionGeneration, List(count) { input.candidates.single() }, input.krxGraphStamp)
    // boundary-input: pure selector/metadata tests never claim this is a new production source.
    fun fact(original: KrxGraphStamp?, sample: TopicGraphInput.Observations, sequence: Long,
        topics: Set<String> = setOf(sample.topic), kind: TopicGraphEventKind = TopicGraphEventKind.DELIVERY_INTERRUPTED) =
        TopicGraphInput.Continuity(sequence, kind, TopicGraphEventReason.WS_ENDED, topics, setOf(sample.path),
            TopicGraphAuthority(sample.attribution.sessionKey, sample.attribution.owner, sample.attribution.grantEpoch,
                sample.attribution.lifetime, original), sample.connectionGeneration, 123L)
    fun unchangedGeneral(before: Map<GraphObservationSeriesKey, List<Any?>>, floor: Long) {
        eq("GENERAL raw data is byte-for-value preserved", before, g.generalPrint())
        eq("USER floor is preserved", floor, raw.versionFloor)
    }
    fun rawRemoved(original: KrxGraphStamp, allowLive: Boolean = false) {
        yes("selected raw series and anchor removed", (allowLive || raw.series.keys.none { it.seriesId == S6KrxGraphRig.KRX }) &&
            original !in raw.krxSeriesOwners.values)
        yes("selected pending envelope removed", raw.pending.inputs.none { stamp(it) == original })
        yes("selected pending loss removed", raw.pending.krxLost.values.none { original in it })
        yes("selected untransferred loss removed", raw.krxUntransferred.values.none { original in it })
    }
    fun purgeHeld(plan: KrxGraphPurgePlan, sink: GraphRecorderTopicSink? = null): KrxGraphPurgeOutcome {
        val rec = recorder.purgeKrxMemory(plan)
        val queue = sink?.purgeKrxMemory(plan) ?: KrxGraphPurgeOutcome(false, false)
        return KrxGraphPurgeOutcome(rec.removed || queue.removed, rec.unresolved || queue.unresolved)
    }
    fun installLossMode(mode: TopicGraphOffer, general: TopicGraphInput.Observations): GraphRecorderTopicSink {
        val sink = sink()
        if (mode == TopicGraphOffer.CLOSED) sink.close() else {
            eq("512 GENERAL units open actual FULL window", TopicGraphOffer.ENQUEUED, sink.tryOffer(batch(general, 512, 90_000)))
            eq("producer is inside FULL window", 512, units(sink))
            if (mode == TopicGraphOffer.FAILED) g.failNextKrxLedgerWrite(sink)
        }
        return sink
    }
    fun recordedFactLoss(input: TopicGraphInput.Observations) {
        eq("actual observation producer reached CLOSED", TopicGraphOffer.CLOSED, session.offers.single { it.first === input }.second)
        val loss = need(ledger.view, "actual observation remains in SESSION ledger")
        yes("actual source is still in raw SESSION loss", input.krxGraphStamp in loss.krxLost.values.flatten())
        yes("actual observation sequence is inside retained window", input.sequence in loss.firstSequence..loss.lastSequence)
    }
    fun recordedFact(fact: TopicGraphInput.Continuity, mode: TopicGraphOffer) {
        eq("actual producer has the requested sink result", mode, session.offers.single { it.first === fact }.second)
        val loss = need(session.cleanup.session.graphLoss.value, "actual producer remains in SESSION loss before purge")
        yes("actual loss still owns the fact original", fact.authority.krxGraphStamp in loss.krxLost.values.flatten())
        yes("actual fact sequence is inside loss window", fact.sequence in loss.firstSequence..loss.lastSequence)
    }
    data class LogicalRestIssue(val sessionKey: Any, val grantEpoch: Long, val stamp: KrxGraphStamp)
    data class KnownWsPrepare(val sessionKey: Any, val connectionGeneration: Long, val grantEpoch: Long,
        val commandId: Long, val requestId: String, val stamp: KrxGraphStamp)
    val logicalRestIssues = mutableListOf<LogicalRestIssue>()
    val knownWsPrepares = mutableListOf<KnownWsPrepare>()
    /** Observe actual REST issue at its public bootstrap dependency, before completion/removal. */
    private fun observeLogicalRestIssues() {
        val bootstrap = need(g.r.bootstrapOverride, "actual REST dependency exists before start")
        g.r.bootstrapOverride = { owner, topic, admitted ->
            if (TopicCatalogue.carriesKrx(topic)) {
                val current = session.cleanup.session
                val key = need(field(current, "sessionKey"), "actual REST issue session identity")
                val issues = field(current, "krxRestIssues") as Map<*, *>
                val issued = issues.values.mapNotNull { issue -> issue?.let {
                    (field(it, "graphStamp") as? KrxGraphStamp)?.let { stamp ->
                        LogicalRestIssue(key, field(it, "grantEpoch") as Long, stamp)
                    }
                } }
                yes("actual REST service entry has a logically issued original", issued.isNotEmpty())
                issued.forEach { if (it !in logicalRestIssues) logicalRestIssues += it }
            }
            bootstrap(owner, topic, admitted)
        }
    }
    private var observingKnownSources = false
    /** C5-02: observe validated prepare returns, including originals not yet ACKed or delivered. */
    fun observeKnownSources() {
        yes("known-source observer is installed once before source input", !observingKnownSources && session.issued.isEmpty())
        observingKnownSources = true
        observeLogicalRestIssues()
        observeWsPrepares()
    }
    private fun observeWsPrepares() {
        val current = session.cleanup.session
        val previous = current.krxAttemptBoundaryDecorator
        // Existing internal test dependency; delegate every original action and observe only its return.
        current.krxAttemptBoundaryDecorator = { boundary ->
            val delegate = previous?.invoke(boundary) ?: boundary
            object : KrxCommandAttemptBoundary {
                override fun prepare(scope: KrxAttemptScope): KrxCommandAttempt? = delegate.prepare(scope).also { attempt ->
                    if (attempt != null && attempt.pendingAckTopics.any(TopicCatalogue::carriesKrx)) {
                        val live = session.cleanup.live
                        val authority = need(field(live, "graphAuthority") as? TopicGraphAuthority, "validated prepare has its connection authority")
                        val pending = need(field(live, "krxPending"), "validated prepare has an actual pending capture")
                        val capture = need(field(pending, "capture"), "actual pending capture at prepare return")
                        val stamp = need(field(capture, "graphStamp") as? KrxGraphStamp, "validated prepare actually issued its original")
                        eq("prepare original belongs to this exact request", attempt.requestId, field(capture, "requestId"))
                        eq("prepare original belongs to this exact command", attempt.commandId, field(capture, "commandId"))
                        eq("prepare belongs to this actual connection", field(live, "generation"), attempt.connectionGeneration)
                        eq("prepare retains original owner", authority.owner, stamp.scope.owner)
                        eq("prepare retains original grant epoch", authority.grantEpoch, attempt.grantEpoch)
                        eq("prepare retains original GENERAL lifetime", authority.lifetime, attempt.generalLifetime)
                        eq("prepare stamp retains the validated KRX use", attempt.krxUse, stamp.scope.use)
                        yes("observed prepare stamp was actually issued by the real ingress", session.issued.any { it.second === stamp })
                        knownWsPrepares += KnownWsPrepare(authority.sessionKey, attempt.connectionGeneration, attempt.grantEpoch,
                            attempt.commandId, attempt.requestId, stamp)
                    }
                }
                override fun beforeEncode(attempt: KrxCommandAttempt) = delegate.beforeEncode(attempt)
                override fun sent(attempt: KrxCommandAttempt) = delegate.sent(attempt)
                override fun discard(attempt: KrxCommandAttempt) = delegate.discard(attempt)
            }
        }
    }
    fun knownOriginal(sample: TopicGraphInput.Observations): KrxGraphStamp = knownOriginal(
        TopicGraphAuthority(sample.attribution.sessionKey, sample.attribution.owner, sample.attribution.grantEpoch,
            sample.attribution.lifetime), sample.connectionGeneration)

    /** Freeze at the call boundary before the fact: never select from the fact under test or later prepares. */
    fun knownOriginal(authority: TopicGraphAuthority, connectionGeneration: Long?): KrxGraphStamp {
        fun sameConnection(generation: Long?) = connectionGeneration == null || generation == connectionGeneration
        val fromInputs = session.inputs.mapNotNull { input -> when (input) {
            is TopicGraphInput.Observations -> input.krxGraphStamp?.takeIf {
                input.attribution.sessionKey === authority.sessionKey && input.attribution.grantEpoch == authority.grantEpoch &&
                    (input.path != TopicGraphPath.WS || sameConnection(input.connectionGeneration))
            }
            is TopicGraphInput.Continuity -> input.authority.krxGraphStamp?.takeIf {
                val ack = input.kind == TopicGraphEventKind.ACK_ACTIVE_SET_CHANGED &&
                    (input.payload as? TopicGraphEventPayload.AckApplied)?.confirmedTopics?.contains(TopicCatalogue.KRX_FUTURES) == true &&
                    sameConnection(input.connectionGeneration)
                val undelivered = input.reason == TopicGraphEventReason.REST_UNDELIVERED
                (ack || undelivered) && input.authority.sessionKey === authority.sessionKey &&
                    input.authority.grantEpoch == authority.grantEpoch
            }
        } }
        val fromRest = logicalRestIssues.filter {
            it.sessionKey === authority.sessionKey && it.grantEpoch == authority.grantEpoch
        }.map { it.stamp }
        val fromPrepare = knownWsPrepares.filter {
            it.sessionKey === authority.sessionKey && it.grantEpoch == authority.grantEpoch && sameConnection(it.connectionGeneration)
        }.map { it.stamp }
        return need((fromInputs + fromRest + fromPrepare).filter {
            it.scope.owner == authority.owner && it.scope.use.lifetime == authority.lifetime
        }.maxByOrNull { it.issueSequence }, "maximum validated WS prepare/ACK/delivery/REST original for this authority before the fact")
    }

    class SessionPurgeFault { var armed = true; var attempts = 0 }
    fun failSessionPurgePreflight(): SessionPurgeFault {
        val state = need(field(ledger, "state"), "SESSION ledger state for fault dependency")
        @Suppress("UNCHECKED_CAST") val aggregates = field(state, "aggregates") as Map<Any?, Any?>
        val fault = SessionPurgeFault()
        fun beforeRead() {
            if (fault.armed && Thread.currentThread().stackTrace.any {
                    it.className == TopicGraphLossLedger::class.java.name && it.methodName.startsWith("purgeKrxMemory") }) {
                fault.attempts++
                throw IllegalStateException("boundary-input: SESSION purge structural preflight fails")
            }
        }
        // Dependency fault only: no record, original, plan or result is fabricated; each read otherwise delegates.
        val dependency = object : Map<Any?, Any?> by aggregates {
            override val entries: Set<Map.Entry<Any?, Any?>> get() { beforeRead(); return aggregates.entries }
            override val keys: Set<Any?> get() { beforeRead(); return aggregates.keys }
            override val values: Collection<Any?> get() { beforeRead(); return aggregates.values }
        }
        setField(state, "aggregates", dependency)
        assertSame(g.c("SESSION fault dependency is installed without replacing records"), dependency, field(state, "aggregates"))
        return fault
    }

    /** Preference I/O dependency supplied before mounting; no private holder/session setup writes. */
    class SelectionStore : GraphSelectionStore {
        var absent = false
        var uncertain = false
        var confirmations = 0
        var writes = 0
        var confirmGate: CompletableDeferred<GraphSelectionReadResult>? = null
        var writeGate: CompletableDeferred<GraphSelectionWriteResult>? = null
        override suspend fun readGraphSelection(key: GraphSelectionKey): GraphSelectionReadResult =
            if (absent) GraphSelectionReadResult.Absent else GraphSelectionReadResult.Present(
                GraphSelectionRecord(1, key.uid, key.audience, key.tab, setOf(S6KrxGraphRig.GENERAL, S6KrxGraphRig.KRX),
                    setOf(S6KrxGraphRig.GENERAL, S6KrxGraphRig.KRX)))
        override suspend fun confirmGraphSelection(key: GraphSelectionKey): GraphSelectionReadResult {
            confirmations++
            return confirmGate?.await() ?: readGraphSelection(key)
        }
        override suspend fun writeGraphSelection(key: GraphSelectionKey, selection: GraphSeriesSelection): GraphSelectionWriteResult {
            writes++
            return writeGate?.await() ?: if (uncertain) GraphSelectionWriteResult.Uncertain(
                java.io.IOException("boundary-input: uncertain preference persistence")) else GraphSelectionWriteResult.Committed
        }
        fun release() {
            confirmGate?.cancel()
            writeGate?.cancel()
        }
    }
    data class Mounted(val holder: GraphV2ScreenStateHolder, val selection: GraphSeriesSelectionSession, val store: SelectionStore)
    private val mounted = mutableListOf<Mounted>()
    fun mount(assembly: GraphRuntimeAssembly, tab: String, main: CoroutineDispatcher = g.dispatcher): Mounted {
        val store = SelectionStore()
        val selection = GraphSeriesSelectionSession(store, GraphSelectionAudience.PREMIUM, tab, g.scope, main)
        val owner = need(g.owner, "actual mounted owner")
        val holder = GraphV2ScreenStateHolder(tab, assembly.coordinator, selection, { g.r.identity },
            need(g.r.session, "actual display session").display,
            MutableStateFlow(OwnedTopicFocus(owner.identity, if (tab == "usd") FreeTab.USD else FreeTab.TETHER)),
            assembly.fences.current, g.r.uses, assembly.gate, g.revisions, g.scope, main,
            clock = g.clock, recorder = assembly.recorder, prepare = { graph, period -> GraphPreparedBuilder.build(graph, period) })
        return Mounted(holder, selection, store).also {
            mounted += it
            holder.start(); holder.onActivated(TopicDisplayOwner(owner.identity, need(g.r.display.owner, "actual display owner").grantEpoch))
            g.test.runCurrent()
        }
    }
    /** A supplied serial dispatcher gate; only scheduling is held, never snapshots or private handlers. */
    @OptIn(InternalCoroutinesApi::class)
    class SerialGate(private val delegate: CoroutineDispatcher) : CoroutineDispatcher(), Delay {
        private val timer = delegate as? Delay ?: throw AssertionError("premise: serial gate requires the test scheduler's Delay")
        private val waiting = ArrayDeque<Pair<CoroutineContext, Runnable>>()
        var paused = false; private set
        val pending get() = waiting.size
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (paused) waiting.addLast(context to block) else delegate.dispatch(context, block)
        }
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) =
            timer.scheduleResumeAfterDelay(timeMillis, continuation)
        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle =
            timer.invokeOnTimeout(timeMillis, Runnable { dispatch(context, block) }, context)
        fun pause() { paused = true }
        fun release() {
            paused = false
            val tasks = waiting.toList(); waiting.clear()
            tasks.forEach { (context, block) -> delegate.dispatch(context, block) }
        }
    }
    private val mainGates = mutableListOf<SerialGate>()
    fun serialGate() = SerialGate(g.dispatcher).also { mainGates += it }
    private val assemblies = mutableListOf<GraphRuntimeAssembly>()
    fun assembly(main: CoroutineDispatcher = g.dispatcher): GraphRuntimeAssembly =
        GraphRuntimeAssembly({ g.snapshot }, g.revisions, { g.r.identity }, g.r.uses, { true }, g.fetcher, g.owners,
            { gate -> GraphV2CachePorts(g.disk, gate, { }, g.writes()) }, main,
            need(g.scope.coroutineContext[Job], "actual Main parent job"), g.clock, { Duration.ZERO }, { g.failures += it })
            .also { assemblies += it; it.fences.setAccess(true, g.owner, TopicGrantOrigin.NewContext) }
    suspend fun ownerAssembly(main: CoroutineDispatcher): Pair<GraphRuntimeAssembly, TopicRuntime> {
        g.r.approve(); g.owner = need(g.r.issuer.topicGrantResult().fence, "actual owner grant for gated Main")
        val assembly = assembly(main)
        val builder = ProcessGraphBuilder { _, _, _, _ ->
            ProcessGraphParts(assembly, com.jay.fxi.data.free.InstallSeedSource({ "s63b2" }, main)) { tab, _, _, _ -> mount(assembly, tab, main).holder }
        }
        val owner = TopicRuntimeOwner(session.runtimeFactory(), MutableStateFlow(true), TopicForegroundStream { it(true) },
            AuthFenceStream { it(g.r.identity) }, { g.r.identity }, C4Prefs(), g.scope, builder,
            reportGraph = { g.failures += it }, newGraphHost = { scope, create, report -> GraphScreenHost(scope, create, report) })
        owner.start()
        val callback = field(owner.consumer, "retryConnection")
        yes("actual owner retains original bound runtime callback", callback is kotlin.jvm.internal.CallableReference)
        val runtime = (callback as kotlin.jvm.internal.CallableReference).boundReceiver as TopicRuntime
        actualRuntimes += runtime; session.attach(runtime)
        g.r.until(g.c("actual gated Owner/factory connection opens"), 2_600) { g.r.wires.isNotEmpty() }
        g.r.open(); g.r.first(); g.r.ack(seconds = 600)
        need(field(session.cleanup.live, "krxActive"), "actual gated factory ACK accepted")
        return assembly to runtime
    }
    fun budgetImage(coordinator: GraphV2RequestCoordinator): Map<Any, List<Any?>> =
        (field(coordinator, "recoveryBudgets") as Map<*, *>).entries.associate { (key, value) ->
            need(key, "actual shared budget key") to need(value, "actual shared budget").let { budget ->
                listOf(budget, field(budget, "rounds"), field(budget, "completionRung"), field(budget, "deadline"),
                    field(budget, "stop"), field(budget, "round"), field(budget, "hold"), field(budget, "permitWait"))
            }
        }
    fun primeSpentBudget(coordinator: GraphV2RequestCoordinator) {
        val key = GraphKey("usd", GraphPeriod.ONE_DAY)
        coordinator.onActivated(key); g.test.runCurrent()
        val call = need(g.sent.lastOrNull { it.key == key && !it.response.isCompleted }, "actual first recovery-budget producer")
        yes("budget producer really departed", call.admitted && !call.returned && !call.cancelled)
        val headers = okhttp3.Headers.headersOf()
        call.response.complete(AuthenticatedHttpResponse(503, headers, null,
            AuthenticatedHttpFailure(503, headers, byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP), byteArrayOf(1)))
        g.test.runCurrent()
        yes("actual retryable producer normally completed", call.returned && !call.cancelled)
        val image = budgetImage(coordinator)
        yes("shared budget is nonempty and has spent an actual departed round", image.isNotEmpty() && image.values.any {
            (it[1] as Int) > 0 && (it[2] as Int) > 0
        })
        coordinator.onDeactivated(); g.test.runCurrent()
        eq("deactivation preserves spent budget identity and counters", image, budgetImage(coordinator))
    }

    private val actualRuntimes = mutableListOf<TopicRuntime>()
    fun startRuntime(assembly: GraphRuntimeAssembly, input: TopicGraphSink, ingress: KrxGraphIngress = assembly.krxGraphIngress): TopicRuntime {
        val before = g.r.wires.size
        val sendsBefore = g.r.sends.size
        val runtime = session.runtimeFactory().create(input, assembly.fences, ingress)
        actualRuntimes += runtime
        session.attach(runtime)
        if (observingKnownSources) observeWsPrepares()
        runtime.start(); runtime.setOnline(true)
        g.r.until(g.c("actual factory runtime opens a current connection"), 2_600) { g.r.wires.size == before + 1 }
        g.r.open()
        g.r.until(g.c("actual replacement factory FIRST is physically SENT"), 1_000) {
            g.r.sends.drop(sendsBefore).any { it.accepted }
        }
        return runtime
    }

    private val writeWindows = mutableListOf<WriteWindow>()
    fun writeWindow(stage: String, failWithdrawals: Boolean = true, cancelWithdrawals: Boolean = false): WriteWindow =
        WriteWindow(stage, failWithdrawals, cancelWithdrawals).also { writeWindows += it }

    /** Real reservation, encoder, atomic file I/O and final admission; only withdraw throws. */
    inner class WriteWindow(val stage: String, val failWithdrawals: Boolean, val cancelWithdrawals: Boolean) {
        val hold = CompletableDeferred<Unit>()
        val prepared = mutableListOf<Pair<GraphV2AccessCapture, GraphV2WritePreparation>>()
        val wants = mutableListOf<Boolean>()
        val reserved = mutableListOf<Pair<GraphV2WriteTicket, GraphV2DiskComponents>>()
        val failedWithdrawals = mutableListOf<GraphV2WriteTicket>()
        val delegatedWithdrawals = mutableListOf<GraphV2WriteTicket>()
        val cancelled = mutableListOf<GraphV2WriteTicket>()
        val entered = mutableListOf<GraphV2WriteTicket>()
        val entryAdmissions = mutableListOf<Pair<Boolean, Boolean>>()
        val commitAdmissions = mutableListOf<Pair<GraphV2DiskComponent, Boolean>>()
        val reports = mutableListOf<GraphV2WriteReport>()
        var delegateWrites = 0
        val key = GraphKey("usd", GraphPeriod.THREE_MONTHS)
        val root: File = run {
            val parent = File("build/tmp/s63b2-write-failures").toPath()
            Files.createDirectories(parent)
            Files.createTempDirectory(parent, "window-").toFile()
        }
        val real = FileGraphV2DiskStore({ root }, JsonGraphV2EnvelopeCodec(), DefaultGraphV2AtomicFileIo(), g.dispatcher)
        private val store = object : GraphV2DiskStore by real {
            override fun reserveWrite(components: GraphV2DiskComponents): GraphV2WriteReservation =
                real.reserveWrite(components).also { result ->
                    if (result is GraphV2WriteReservation.Reserved) reserved += result.ticket to components
                }
            override fun cancelWrite(ticket: GraphV2WriteTicket) { cancelled += ticket; real.cancelWrite(ticket) }
            override fun withdrawKrx(ticket: GraphV2WriteTicket): Boolean {
                if (failWithdrawals && reserved.firstOrNull()?.first === ticket) {
                    failedWithdrawals += ticket
                    // Always throw before delegation, including every automatic retry on K2's revision.
                    if (cancelWithdrawals) throw kotlinx.coroutines.CancellationException("boundary-input: selected withdrawal is cancelled before delegation")
                    throw IllegalStateException("boundary-input: selected ticket withdrawal has no effect")
                }
                delegatedWithdrawals += ticket
                return real.withdrawKrx(ticket)
            }
            override suspend fun write(ticket: GraphV2WriteTicket, admission: GraphV2IoAdmission): GraphV2WriteReport {
                entered += ticket
                entryAdmissions += admission.admits(GraphV2DiskComponent.GENERAL) to admission.admits(GraphV2DiskComponent.KRX)
                if (stage == "ready") hold.await()
                delegateWrites++
                return real.write(ticket, GraphV2IoAdmission { component ->
                    admission.admits(component).also { commitAdmissions += component to it }
                }).also { reports += it }
            }
        }
        private val actualPreparation = IssuerGraphWritePreparation(g.r.pac::markGraphData)
        private val ports = GraphV2WritePorts(GraphV2PrepareWrite { captured, wantsKrx ->
            wants += wantsKrx
            val result = actualPreparation.prepare(captured, wantsKrx)
            prepared += captured to result
            if (stage == "preparing") hold.await()
            result
        }, { }, { })
        val assembly = GraphRuntimeAssembly({ g.snapshot }, g.revisions, { g.r.identity }, g.r.uses, { true },
            g.fetcher, g.owners, { gate -> GraphV2CachePorts(store, gate, { }, ports) }, g.dispatcher,
            need(g.scope.coroutineContext[Job], "actual graph parent job"), g.clock, { Duration.ZERO }, { g.failures += it })
        init { assembly.fences.setAccess(true, g.owner, TopicGrantOrigin.NewContext) }
        val ticket get() = need(reserved.singleOrNull()?.first, "exactly one original K1 reservation")
        val components get() = need(reserved.singleOrNull()?.second, "original K1 components")
        val captured get() = need(prepared.singleOrNull()?.first, "actual original write capture")
        fun task(): Any {
            val tasks = field(assembly.coordinator, "writeTasks") as Map<*, *>
            return need(tasks[ticket], "GRC positively retains original write task")
        }
        fun assertOwnership() {
            val task = task()
            eq("GRC wantsKrx remains true", true, field(task, "wantsKrx"))
            yes("GRC still owns KRX after failed withdrawal", (field(task, "krxOwned") as AtomicBoolean).get())
            yes("whole write remains enabled", (field(task, "enabled") as AtomicBoolean).get())
            val reservations = field(real, "reservations") as Map<*, *>
            val reservation = need(reservations[ticket], "real reservation is still positively held")
            eq("actual store has not claimed held write", false, field(reservation, "claimed"))
            eq("GENERAL reservation remains valid", true, field(reservation, "generalValid"))
            eq("KRX reservation remains valid (withdraw never delegated)", true, field(reservation, "krxValid"))
            val latest = field(real, "latest") as Map<*, *>
            eq("original ticket has not been superseded", field(reservation, "sequence"), latest[components.general.key])
            eq("no second reserve for the same key", 1, reserved.size)
            eq("purge never cancels whole ticket", emptyList<GraphV2WriteTicket>(), cancelled)
        }
        fun start(primeBudget: Boolean = false) {
            yes("real checked ON assembly starts", assembly.start())
            if (primeBudget) primeSpentBudget(assembly.coordinator)
            assembly.coordinator.onActivated(key); g.test.runCurrent()
            val call = need(g.sent.lastOrNull { it.key == key && !it.response.isCompleted }, "actual K1 HTTP producer is in flight")
            yes("actual producer admitted and uncancelled", call.admitted && !call.cancelled && !call.returned)
            val response = GraphV2TabResponse("usd", key.period.code,
                listOf(S6KrxGraphRig.GENERAL, S6KrxGraphRig.KRX).map { id -> GraphV2Series(id, id, "krw", "KRW", 2,
                    listOf(GraphV2Point(AT - 2.days, 1500.0, "x"), GraphV2Point(AT - 1.days, 1501.0, "x")),
                    GraphV2Provenance(false, emptyList()), null) },
                GraphV2Metadata(AT - 1.hours, "1d", GraphV2Range("2026-06-26", "2026-09-26")))
            call.response.complete(S6KrxGraphRig.ok(response)); g.test.runCurrent()
            yes("original HTTP producer really completed normally", call.returned && !call.cancelled)
            eq("K1 KRX requested by actual preparation", listOf(true), wants)
            val result = need(prepared.singleOrNull()?.second, "actual issuer preparation result")
            yes("actual persisted GENERAL and K1 namespaces are Ready", result.general is GraphV2NamespacePreparation.Ready &&
                result.krx is GraphV2NamespacePreparation.Ready)
            val ready = result.krx as GraphV2NamespacePreparation.Ready
            eq("preparation names captured K1", captured.krxCapabilityEpoch, ready.record.krxCapabilityEpoch)
            yes("actual persisted KRX marking is positive", ready.record.mayContainKrxData && g.r.h.store.record.mayContainKrxData)
            yes("reservation contains original K1 candidate", components.krx?.key?.krxCapabilityEpoch == captured.krxCapabilityEpoch)
            eq("store entry matches exact preparation window", if (stage == "ready") listOf(ticket) else emptyList(), entered)
            if (stage == "ready") eq("ready store-entry GENERAL/KRX admission both positive", listOf(true to true), entryAdmissions)
            eq("write delegate has not run", 0, delegateWrites)
            yes("actual preparation/store hold is positive", !hold.isCompleted)
            assertOwnership()
            yes("original live GENERAL and KRX admissions are positive", assembly.gate.admits(captured, GraphV2DiskComponent.GENERAL) &&
                assembly.gate.admits(captured, GraphV2DiskComponent.KRX))
        }
        suspend fun close() {
            hold.cancel()
            assembly.close()
            yes("only this fixture's temporary disk root is removed", !root.exists() || root.deleteRecursively())
        }
    }

    suspend fun close() {
        mainGates.forEach { it.release() }
        writeWindows.forEach { it.close() }
        mounted.forEach { it.store.release() }
        mounted.forEach { it.holder.close(); it.selection.close() }
        sinks.forEach { it.close() }
        assemblies.forEach { it.close() }
        actualRuntimes.forEach { it.stop() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun s63b2Test(row: String, vararg columns: String,
    body: suspend TestScope.(S6KrxGraphMemoryPurgeRig, String) -> Unit) =
    s63b1Test(row, *columns) { g, column ->
        val p = S6KrxGraphMemoryPurgeRig(g)
        try { body(p, column) } finally { p.close() }
    }
