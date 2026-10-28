package com.jay.fxi.data.graph

import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.purge.*
import com.jay.fxi.data.remote.*
import com.jay.fxi.data.remote.S6KrxCleanupRig.Companion.field
import com.jay.fxi.data.remote.S6KrxCleanupRig.Companion.setField
import com.jay.fxi.data.remote.S6KrxGraphSessionRig.Companion.AT
import com.jay.fxi.data.graph.S6KrxGraphRig.Companion.GENERAL
import com.jay.fxi.data.graph.S6KrxGraphRig.Companion.KRX
import com.jay.fxi.domain.model.*
import com.jay.fxi.data.local.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class S6KrxGraphMemoryPurgeContractTest {
    @Test fun g11_capabilityTargetSweepsOldAndPreservesLive() = s63b2Test("G11",
        "sweep", "repeat", "forged", "owner", "blank", "null-owner", "live", "null-current", "selector-throw", "preflight-publication") { p, col ->
        if (col == "preflight-publication") {
            preflightPublication(p)
            return@s63b2Test
        }
        val g = p.g; g.start(); val old = g.adopt(); g.general()
        val k1 = p.need(old.krxGraphStamp, "actual K1 original")
        val coordinator = g.readyCoordinator()
        val general = g.generalPrint(); val floor = g.raw.versionFloor
        val current = p.namespace(k1)
        if (col == "selector-throw") {
            p.eq("selected queue ownership is positive before preflight", TopicGraphOffer.ENQUEUED, g.sink.tryOffer(old))
            g.session.capEnd()
            val namespace = p.namespace(k1)
            val before = g.raw; val queued = p.queued(g.sink)
            val slots = field(coordinator, "protectedSlots")
            val supply = g.r.snapshotSupply; var reads = 0
            try {
                g.r.snapshotSupply = { reads++; throw IllegalStateException("boundary-input: live plan preflight fails") }
                val result = ManifestScopePurger(mapOf(p.memoryTarget.id to p.adapter(coordinator)), listOf(p.memoryTarget))
                    .purgeCapabilityScope(namespace)
                p.yes("actual live preflight read reaches injected failure", reads > 0)
                p.yes("preflight exception is a failed target", result is PurgeResult.Failed)
                assertSame(g.c("preflight failure leaves REC untouched"), before, g.raw)
                p.eq("preflight failure leaves queue untouched", queued, p.queued(g.sink))
                assertSame(g.c("preflight failure leaves GRC untouched"), slots, field(coordinator, "protectedSlots"))
            } finally { g.r.snapshotSupply = supply }
            return@s63b2Test
        }
        if (col in setOf("forged", "owner", "blank", "null-owner", "live", "null-current")) {
            val before = g.raw
            val namespace = when (col) {
                "owner" -> current.copy(ownerUid = "foreign")
                "blank" -> current.copy(ownerUid = " ", pending = current.pending.copy(ownerUid = " "))
                "null-owner" -> current.copy(ownerUid = null, pending = current.pending.copy(ownerUid = null))
                "null-current" -> current.copy(currentKrxCapabilityEpoch = null)
                else -> current
            }
            val target = if (col == "forged") p.memoryTarget.copy(owner = "forged") else p.memoryTarget
            p.yes("live old raw cache is positive before refusal", k1 in g.raw.krxSeriesOwners.values)
            val outcome = p.adapter(coordinator).purge(p.request(namespace, target))
            p.yes("invalid/live preflight refuses", outcome is TargetOutcome.Failed)
            assertSame(g.c("preflight refusal preserves entire REC state"), before, g.raw)
        } else {
            p.eq("memory manifest declares both axes", setOf(PurgeScope.USER, PurgeScope.CAPABILITY), p.memoryTarget.scopes)
            // boundary-input: multiple original namespaces in the pure selector; real consumers own K1.
            g.session.capEnd(); g.session.capReopen()
            val namespace = p.namespace(k1)
            p.yes("actual confirmed current namespace differs from retired", namespace.currentKrxCapabilityEpoch != k1.scope.use.krxCapabilityEpoch)
            val plan = p.need(KrxGraphPurgePlan.forCapability(namespace, g.snapshot), "validated CAP sweep plan")
            val k0 = k1.copy(scope = k1.scope.copy(use = k1.scope.use.copy(krxCapabilityEpoch = "K0")))
            p.eq("sweep is not narrowed to pending epoch", KrxGraphPurgeDecision.REMOVE, plan.classify(k0, namespace.ownerUid))
            p.eq("original retired namespace selected", KrxGraphPurgeDecision.REMOVE, plan.classify(k1, namespace.ownerUid))
            // boundary-input: raw queue inventory contains K0/K1 and foreign originals; source admission is not reissued.
            fun held(stamp: KrxGraphStamp, seq: Long) = TopicGraphInput.Observations(seq, old.topic, old.path,
                TopicUseAttribution(old.attribution.sessionKey, stamp.scope.owner, old.attribution.grantEpoch, stamp.scope.use.lifetime),
                old.connectionGeneration, old.candidates, stamp)
            val foreign = k1.copy(scope = k1.scope.copy(owner = k1.scope.owner.copy(identity = k1.scope.owner.identity.copy(uid = "foreign"))))
            val old0 = held(k0, 1000); val old1 = held(k1, 1001); val other = held(foreign, 1002)
            for (input in listOf(old0, old1, other)) p.eq("raw inventory actually queued", TopicGraphOffer.ENQUEUED, g.sink.tryOffer(input))
            val fresh = g.session.rest(1777.0, AT + 1.seconds); val k2 = fresh.krxGraphStamp!!
            p.yes("current K2 is positively adopted before old sweep", g.raw.krxSeriesOwners[KRX] == k2 && g.krxKey in g.exposed())
            p.yes("old K0/K1 and foreign still held before adapter", listOf(old0, old1, other).all { it in p.queued(g.sink) })
            val liveRaw = g.print(g.krx())
            p.eq("real adapter removes selected memory", TargetOutcome.Removed, p.adapter(coordinator).purge(p.request(namespace)))
            p.rawRemoved(k1, allowLive = true)
            p.eq("current K2 raw data survives broad old sweep", liveRaw, g.print(g.krx()))
            p.eq("only foreign original survives in queue", listOf(other), p.queued(g.sink))
            if (col == "repeat") p.eq("repeat only answers for empty target", TargetOutcome.NothingToRemove,
                p.adapter(coordinator).purge(p.request(namespace)))
        }
        p.unchangedGeneral(general, floor)
    }

    private suspend fun preflightPublication(p: S6KrxGraphMemoryPurgeRig) {
        val g = p.g; g.start(); val k1 = g.adopt().krxGraphStamp!!; g.general()
        g.session.capEnd(); g.session.capReopen()
        val current = g.session.rest(1666.0, AT); val k2 = p.need(current.krxGraphStamp, "actual K2 before queued preflight")
        val coordinator = g.readyCoordinator(); val request = p.request(p.namespace(k1))
        p.eq("stale-request candidate K2 is physically queued", TopicGraphOffer.ENQUEUED, g.sink.tryOffer(current))
        p.yes("actual K2 raw anchor is positive before namespace publication", k2 in g.raw.krxSeriesOwners.values)
        val serial = p.serialGate(); serial.pause()
        val pending = g.scope.async(serial) { p.adapter(coordinator).purge(request) }
        g.test.runCurrent()
        p.yes("actual adapter request is pending before preflight starts", serial.pending > 0 && !pending.isCompleted)
        val oldCurrent = request.namespace.currentKrxCapabilityEpoch
        g.session.capEnd(); g.session.capReopen()
        p.yes("actual PAC publishes a different confirmed live namespace while request waits", g.snapshot.facts.capabilityAllowed &&
            oldCurrent != g.snapshot.facts.recordFence?.krxCapabilityEpoch)
        val before = g.raw; val queue = p.queued(g.sink); val slots = field(coordinator, "protectedSlots")
        p.yes("old live K2 payload is still in the physical preflight window", k2 in before.krxSeriesOwners.values && current in queue)
        serial.release(); g.test.runCurrent()
        p.yes("stale current-namespace preflight fails closed", pending.await() is TargetOutcome.Failed)
        assertSame(g.c("invalid current namespace preserves entire raw REC"), before, g.raw)
        p.eq("invalid current namespace preserves queued original", queue, p.queued(g.sink))
        assertSame(g.c("invalid current namespace preserves GRC before mutation"), slots, field(coordinator, "protectedSlots"))
    }

    @Test fun g15_purgeRemovesQueuePendingLossAndRecovery() = s63b2Test("G15",
        "queue", "pending", "pending-eviction", "FULL-ledger", "dequeued", "detached-ledger", "late-sink", "late-resume", "late-FULL", "registered-recovery", "mixed-queue", "mixed-pending") { p, col ->
        if (col.startsWith("mixed-")) {
            mixedContinuityProjection(p, col == "mixed-pending")
            return@s63b2Test
        }
        val g = p.g; g.start(); val old = g.adopt(); val original = old.krxGraphStamp!!; val general = g.general()
        val sink = p.sink(); val floor = g.raw.versionFloor
        val recovery = if (col == "registered-recovery") g.readyCoordinator().also {
            it.onActivated(GraphKey("usd", GraphPeriod.ONE_DAY)); runCurrent()
        } else null
        val registration = recovery?.let { coordinator ->
            val held = field(coordinator, "tabRequests") as Map<*, *>
            p.need(held[GraphKey("usd", GraphPeriod.ONE_DAY)], "actual registration positively owns recovery")
        }
        if (registration != null) {
            val requests = field(registration, "recoveryRequests") as List<*>
            p.yes("selected recovery payload positive before purge", requests.filterIsInstance<GraphRecoveryRequest>().any { it.krxGraphStamp == original })
        }
        when (col) {
            "pending", "pending-eviction" -> {
                g.catalog = null
                repeat(if (col == "pending-eviction") 513 else 1) { g.session.ws(1556.0 + it % 2, AT + 1.seconds) }
                g.general(AT + 2.seconds, 1445.0)
                p.yes("catalog wait positively owns KRX envelopes", g.raw.pending.inputs.any { p.stamp(it) == original })
                if (col == "pending-eviction") p.yes("eviction positively owns original loss", original in g.raw.pending.krxLost.values.flatten())
            }
            "FULL-ledger", "detached-ledger" -> {
                p.eq("whole GENERAL batch fills queue", TopicGraphOffer.ENQUEUED, sink.tryOffer(p.batch(general, 512)))
                p.eq("selected KRX actually FULL", TopicGraphOffer.FULL, sink.tryOffer(old))
                p.yes("selected original is present in sink ledger", original in p.sinkLoss(sink).values.flatten())
            }
            else -> {
                p.eq("selected KRX is queued", TopicGraphOffer.ENQUEUED, sink.tryOffer(old))
                p.eq("GENERAL survivor is queued", TopicGraphOffer.ENQUEUED, sink.tryOffer(general))
                p.yes("purge window still owns selected input", old in p.queued(sink))
            }
        }
        if (col == "dequeued" || col == "detached-ledger") {
            var entered = 0
            val removeAtConsumer = {
                entered++
                p.yes("selected raw owner still held inside actual consumer window", original in g.raw.krxSeriesOwners.values)
                val heldPlan = p.cutoff(original)
                p.yes("consumer-window purge removes REC ownership", p.purgeHeld(heldPlan, sink).removed)
            }
            if (col == "dequeued") p.beforeConsume = { input ->
                if (input === old) {
                    p.yes("input actually dequeued before cleanup", old !in p.queued(sink))
                    removeAtConsumer()
                }
            } else p.beforeKrxLoss = { loss ->
                if (original in loss.values.flatten()) {
                    p.yes("original loss already detached before cleanup", original !in p.sinkLoss(sink).values.flatten())
                    removeAtConsumer()
                }
            }
            sink.start(); runCurrent()
            p.eq("actual detached/dequeued boundary consumed once", 1, entered)
            p.rawRemoved(original)
            p.eq("consumer-window drain refunds whole budget", 0, p.units(sink))
            return@s63b2Test
        }
        val plan = p.cutoff(original)
        val outcome = p.purgeHeld(plan, sink)
        p.yes("physical purge reports actual removal", outcome.removed && !outcome.unresolved)
        p.rawRemoved(original)
        if (recovery != null && registration != null) {
            p.yes("registered recovery payload physically removed", recovery.purgeKrxRecoveryCaptures(plan).removed)
            p.yes("registration no longer owns selected payload", (field(registration, "recoveryRequests") as List<*>).filterIsInstance<GraphRecoveryRequest>().none { it.krxGraphStamp == original })
        }
        p.yes("sink raw queue and ledger no longer own original", p.queued(sink).none { p.stamp(it) == original } &&
            original !in p.sinkLoss(sink).values.flatten())
        p.eq("USER floor does not move", floor, g.raw.versionFloor)
        p.eq("purge refunds exactly selected units", p.queued(sink).sumOf { it.pendingUnits() }, p.units(sink))
        if (col == "late-FULL") {
            sink.start(); runCurrent()
            p.eq("new GENERAL whole batch fills real budget", TopicGraphOffer.ENQUEUED, sink.tryOffer(p.batch(general, 512, 90_012)))
            p.eq("late original really hits FULL", TopicGraphOffer.FULL, sink.tryOffer(old))
            p.yes("late FULL temporarily owns original ledger entry", original in p.sinkLoss(sink).values.flatten())
            runCurrent(); p.rawRemoved(original)
            p.yes("actual drain removes temporary old sink ledger", original !in p.sinkLoss(sink).values.flatten())
            p.eq("late FULL drain refunds GENERAL budget", 0, p.units(sink))
        } else if (col == "late-resume") {
            g.catalog = null
            val resume = p.fact(original, old, 90_010, setOf(old.topic, general.topic), TopicGraphEventKind.DELIVERY_RESUMED)
            p.eq("late mixed resume actually queues", TopicGraphOffer.ENQUEUED, sink.tryOffer(resume))
            p.yes("late input is positively held before drain", resume in p.queued(sink))
            sink.start(); runCurrent()
            p.yes("REC seal removes old continuity before catalog wait", g.raw.pending.inputs.none { p.stamp(it) == original })
            p.yes("GENERAL resume still waits for catalog", g.raw.pending.inputs.filterIsInstance<TopicGraphInput.Continuity>().any { general.topic in it.topics })
        } else if (col == "late-sink") {
            p.eq("late old input may temporarily enqueue", TopicGraphOffer.ENQUEUED, sink.tryOffer(old))
            p.yes("late old temporary ownership is proven", old in p.queued(sink))
            sink.start(); runCurrent(); p.rawRemoved(original)
        } else {
            val survivors = p.queued(sink)
            sink.start(); runCurrent()
            p.eq("GENERAL FIFO survivors consumed exactly once", survivors, p.consumed)
            p.eq("budget refunded exactly", 0, p.units(sink))
            g.catalog = S6KrxGraphRig.defaultCatalog(); g.recorder.replayPending()
            p.rawRemoved(original)
            p.yes("GENERAL remains accessible", g.generalKey in g.exposed())
        }
    }

    private suspend fun mixedContinuityProjection(p: S6KrxGraphMemoryPurgeRig, pending: Boolean) {
        val g = p.g; g.start(); val old = g.adopt(); val general = g.general()
        val stamp = old.krxGraphStamp!!
        // boundary-input: mixed original-attributed resume; no claim of a synthetic TSC producer.
        val mixed = TopicGraphInput.Continuity(90_020, TopicGraphEventKind.DELIVERY_RESUMED,
            TopicGraphEventReason.WS_ENDED, setOf(old.topic, general.topic), setOf(TopicGraphPath.WS, TopicGraphPath.REST_BOOTSTRAP),
            TopicGraphAuthority(old.attribution.sessionKey, old.attribution.owner, old.attribution.grantEpoch,
                old.attribution.lifetime, stamp), old.connectionGeneration, 123_456)
        val sink = p.sink()
        if (pending) {
            g.catalog = null; g.recorder.observe(mixed); g.general(AT, 1445.0)
            p.yes("mixed resume is positively held in real REC pending", mixed in g.raw.pending.inputs)
        } else {
            p.eq("mixed resume is positively queued", TopicGraphOffer.ENQUEUED, sink.tryOffer(mixed))
            p.eq("GENERAL original positively follows it", TopicGraphOffer.ENQUEUED, sink.tryOffer(general))
            p.eq("mixed FIFO window has two units", 2, p.units(sink))
        }
        val plan = p.cutoff(stamp)
        p.yes("selected mixed KRX part is physically removed", p.purgeHeld(plan, if (pending) null else sink).removed)
        val held = if (pending) g.raw.pending.inputs else p.queued(sink)
        val projected = p.need(held.firstOrNull() as? TopicGraphInput.Continuity, "GENERAL continuity survivor remains first")
        p.eq("only GENERAL topic survives projection", setOf(general.topic), projected.topics)
        p.eq("selected stamp is cleared", null, projected.authority.krxGraphStamp)
        assertSame(g.c("GENERAL session identity retained"), mixed.authority.sessionKey, projected.authority.sessionKey)
        p.eq("GENERAL owner/grant/lifetime remain original", listOf(mixed.authority.owner, mixed.authority.grantEpoch, mixed.authority.lifetime),
            listOf(projected.authority.owner, projected.authority.grantEpoch, projected.authority.lifetime))
        p.eq("original sequence/time/path/kind/reason/payload retained", listOf(mixed.sequence, mixed.occurredAtEpochMillis,
            mixed.paths, mixed.connectionGeneration, mixed.kind, mixed.reason, mixed.payload), listOf(projected.sequence,
            projected.occurredAtEpochMillis, projected.paths, projected.connectionGeneration, projected.kind, projected.reason, projected.payload))
        p.eq("frozen original still owns KRX stamp", stamp, mixed.authority.krxGraphStamp)
        p.eq("GENERAL survivor ordering preserved", 2, held.size)
        if (!pending) {
            assertSame(g.c("GENERAL observation remains original reference"), general, held[1])
            p.eq("whole mixed survivor still costs one unit", 2, p.units(sink))
            sink.start(); g.test.runCurrent()
            p.eq("actual drain consumes projected FIFO exactly once", held, p.consumed)
        }
    }

    @Test fun g17_graphOnlyMemoryPurgesWithoutSessionPrices() = s63b2Test("G17", "server-slot", "continuity-loss", "untransferred", "server-slot-rotation", "untransferred-rotation", "continuity-loss-rotation") { p, col ->
        val mode = col.removeSuffix("-rotation")
        val g = p.g; g.r.httpFloor = g.r.clock.nowMillis() + 1_000_000; g.start(ack = false)
        g.session.cleanup.ackKrxLease(g.session.cleanup.lastKrxRequest(), "g17-K1", krxSeconds = 1)
        p.yes("SESSION starts without KRX display prices", !S6KrxCleanupRig.hasKrx(g.r.rates))
        val ack = p.need(g.session.inputs.filterIsInstance<TopicGraphInput.Continuity>().lastOrNull {
            it.authority.krxGraphStamp != null }, "actual ACK original without prices")
        val serial = p.serialGate()
        val assembly = p.assembly(serial)
        assembly.start()
        assembly.coordinator.onActivated(GraphKey("usd", GraphPeriod.THREE_MONTHS)); runCurrent()
        g.sent.filter { !it.response.isCompleted }.forEach { call ->
            val body = S6KrxGraphRig.tabDto(call.key)
            val response = if (mode == "server-slot") body else body.copy(series = body.series.filter { it.id != KRX })
            call.response.complete(S6KrxGraphRig.ok(response))
        }
        runCurrent(); g.r.advance(1_001)
        p.eq("price-less active pointer really expired", null, field(g.session.cleanup.live, "krxActive"))
        p.yes("completed graph HTTP leaves no registration", (field(assembly.coordinator, "tabRequests") as Map<*, *>).isEmpty())
        if (mode == "server-slot") {
            val slots = field(assembly.coordinator, "protectedSlots") as Map<*, *>
            p.yes("actual HTTP producer owns graph-only KRX slot", slots.values.any {
                (field(p.need(it, "slot"), "components") as GraphV2DiskComponents).krx != null })
        } else {
            // boundary-input: actual ACK authority, graph-only loss through actual consumers; no synthetic price.
            val original = p.need(assembly.krxGraphIngress.capture(ack.authority.krxGraphStamp!!.scope), "assembly graph-only source")
            if (mode == "untransferred") {
                assembly.recorder.loseKrxTopics(mapOf(TopicCatalogue.KRX_FUTURES to setOf(original)))
                p.yes("graph-only untransferred original exists without a price", assembly.recorder.state.value.krxUntransferred.isNotEmpty() ||
                    assembly.recorder.state.value.pending.krxLost.isNotEmpty())
            } else p.yes("continuity-only SESSION inventory has no REC holding", assembly.recorder.state.value.krxUntransferred.isEmpty() &&
                assembly.recorder.state.value.pending.krxLost.isEmpty() && assembly.recorder.state.value.pending.inputs.isEmpty())
            if (mode == "continuity-loss") {
                p.yes("SESSION loss recorded without prices", p.ledger.record(ack, TopicGraphOffer.CLOSED, 123))
                p.yes("graph-only SESSION loss exists", p.ledger.view?.krxLost?.isNotEmpty() == true)
            }
        }
        p.yes("no KRX price series needed to initiate cleanup", assembly.recorder.state.value.series.keys.none { it.seriesId == KRX })
        val reportBefore = assembly.krxMemoryPurgeState
        if (col.endsWith("-rotation")) serial.pause()
        g.session.capEnd()
        if (col.endsWith("-rotation")) {
            g.session.capReopen()
            p.yes("actual Main work is held across both PAC publications", serial.pending > 0)
            assertSame(g.c("paused Main did not consume intermediate end publication"), reportBefore, assembly.krxMemoryPurgeState)
            p.yes("coalesced confirmed rotation actually changes and reopens namespace", g.snapshot.facts.capabilityAllowed &&
                g.snapshot.facts.recordFence?.krxCapabilityEpoch != ack.authority.krxGraphStamp?.scope?.use?.krxCapabilityEpoch)
            serial.release()
        }
        runCurrent()
        p.yes("automatic Main collector reports its own graph-only target without Now", assembly.krxMemoryPurgeState?.outcome?.let {
            !it.unresolved && (mode == "continuity-loss" || it.removed)
        } == true)
        p.yes("graph-only REC payload is gone", assembly.recorder.state.value.krxUntransferred.isEmpty() && assembly.recorder.state.value.pending.krxLost.isEmpty())
        if (mode == "continuity-loss") p.yes("TSC independently clears selected graph loss", p.ledger.view?.krxLost.orEmpty().isEmpty())
    }

    @Test fun g19_unknownEndAndRotationRemainUnresolved() = s63b2Test("G19",
        "end-sidecar", "end-uid", "end-namespace", "rotation-sidecar", "rotation-uid", "rotation-namespace", "foreign-K2") { p, col ->
        val g = p.g; g.start(); val original = g.adopt().krxGraphStamp!!; g.general()
        val before = g.raw; val general = g.generalPrint(); val floor = before.versionFloor
        if (col.startsWith("rotation")) g.boundaryRoundTrip("epoch", original) else g.session.capEnd()
        val plan = p.plan()
        val unknown = when {
            col.endsWith("sidecar") -> null
            col.endsWith("uid") -> original.copy(scope = original.scope.copy(owner = original.scope.owner.copy(identity = original.scope.owner.identity.copy(uid = ""))))
            else -> original.copy(scope = original.scope.copy(use = original.scope.use.copy(krxCapabilityEpoch = null)))
        }
        p.yes("known raw selected original is still inside purge window", original in g.raw.krxSeriesOwners.values)
        p.eq("known retired portion is selectable", KrxGraphPurgeDecision.REMOVE, plan.classify(original, original.scope.owner.identity.uid))
        if (col != "foreign-K2") p.eq("unknown related original never guessed", KrxGraphPurgeDecision.UNRESOLVED,
            plan.classify(unknown, original.scope.owner.identity.uid))
        val foreign = original.copy(scope = original.scope.copy(owner = original.scope.owner.copy(identity = original.scope.owner.identity.copy(uid = "foreign"))))
        val future = original.copy(scope = original.scope.copy(acquiredRevision = plan.accessRevision + 1))
        p.eq("foreign owner remains", KrxGraphPurgeDecision.KEEP, plan.classify(foreign, "foreign"))
        p.eq("future K2 remains", KrxGraphPurgeDecision.KEEP, plan.classify(future, future.scope.owner.identity.uid))
        g.catalog = null
        // boundary-input: actual original authority with absent/contradictory KRX provenance, through real REC continuity admission.
        val sample = p.need(g.session.inputs.filterIsInstance<TopicGraphInput.Observations>().firstOrNull { it.krxGraphStamp == original }, "actual sample for unknown continuity")
        val unknownFact = p.fact(unknown, sample, 90_030, setOf(sample.topic, "fx:usd-krw"), TopicGraphEventKind.DELIVERY_RESUMED)
        g.recorder.observe(unknownFact)
        p.yes("related unknown is physically held before purge", unknownFact in g.raw.pending.inputs)
        fun kept(stamp: KrxGraphStamp, sequence: Long) = TopicGraphInput.Observations(sequence, sample.topic, sample.path,
            TopicUseAttribution(sample.attribution.sessionKey, stamp.scope.owner, sample.attribution.grantEpoch, stamp.scope.use.lifetime),
            sample.connectionGeneration, sample.candidates, stamp)
        val foreignInput = kept(foreign, 90_032); val futureInput = kept(future, 90_033)
        p.eq("raw foreign sentinel is positively queued", TopicGraphOffer.ENQUEUED, g.sink.tryOffer(foreignInput))
        p.eq("raw future sentinel is positively queued", TopicGraphOffer.ENQUEUED, g.sink.tryOffer(futureInput))
        val outcome = g.recorder.purgeKrxMemory(plan)
        p.eq("known removal cannot erase unknown failure", KrxGraphPurgeOutcome(true, true), outcome)
        p.yes("unknown raw continuity remains after partial cleanup", unknownFact in g.raw.pending.inputs)
        p.rawRemoved(original)
        val coordinator = g.readyCoordinator()
        val answer = p.adapter(coordinator).purge(p.request(p.namespace(original)))
        p.yes("partial unknown target cannot become successful", answer is TargetOutcome.Failed)
        p.yes("failed target still preserves unknown original input", unknownFact in g.raw.pending.inputs)
        p.eq("unknown cannot authorize a whole-KRX sweep of foreign/future raw sentinels", listOf(foreignInput, futureInput), p.queued(g.sink))
        p.unchangedGeneral(general, floor)
    }

    @Test fun g28_fourPacInputsRemoveOnlySelectedKrx() = s63b2Test("G28", "WS", "REST", "false", "rotation", "coalesced-rotation") { p, col ->
        val g = p.g; val serial = p.serialGate(); val (assembly, _) = p.ownerAssembly(serial)
        val old = g.session.ws(); val original = old.krxGraphStamp!!
        // Adopt a catalog through the real producer before inspecting recorder raw ownership.
        assembly.coordinator.onActivated(GraphKey("usd", GraphPeriod.ONE_DAY)); runCurrent()
        g.sent.filter { !it.response.isCompleted }.forEach { it.response.complete(S6KrxGraphRig.ok(S6KrxGraphRig.tabDto(it.key))) }; runCurrent()
        p.yes("actual assembly positively owns original KRX", original in assembly.recorder.state.value.krxSeriesOwners.values ||
            assembly.recorder.state.value.pending.inputs.any { p.stamp(it) == original })
        g.r.generalTick(1444.0)
        p.yes("real GENERAL FE quote is positive before KRX PAC input", g.r.rates.quotes.any { it.key.source != "krx" && it.value.rate == 1444.0 })
        val before = g.snapshot
        val floor = assembly.recorder.state.value.versionFloor
        val identity = before.facts.recordFence?.ownerUid
        assembly.coordinator.onRefreshRequested(force = true); runCurrent()
        val call = p.need(g.sent.lastOrNull { it.key == GraphKey("usd", GraphPeriod.ONE_DAY) && !it.response.isCompleted },
            "actual force-refresh producer remains admitted before PAC input")
        p.yes("producer starts uncancelled inside original use", call.admitted && !call.cancelled && !call.returned)
        g.CompletionBarrier(assembly.coordinator).use { barrier ->
            call.response.complete(S6KrxGraphRig.ok(S6KrxGraphRig.tabDto(call.key))); runCurrent()
            p.yes("actual producer completed before PAC input", call.returned && !call.cancelled)
            p.eq("original native completion is positively held in FIFO", 1, barrier.enqueued)
            val priorReport = assembly.krxMemoryPurgeState
            serial.pause()
            when (col) {
                "WS" -> g.session.rejectKrxFromWsForPurge()
                "REST" -> g.session.rejectKrxFromRestForPurge()
                "false" -> g.session.capEnd()
                "rotation" -> {
                    g.session.capEnd(); serial.release(); runCurrent()
                    serial.pause(); g.session.capReopen()
                }
                else -> { g.session.capEnd(); g.session.capReopen() }
            }
            p.yes("automatic Main evaluation is positively queued behind actual PAC input", serial.pending > 0)
            if (col == "coalesced-rotation") {
                assertSame(g.c("Main consumed neither intermediate publication"), priorReport, assembly.krxMemoryPurgeState)
                p.yes("actual final coalesced publication is OPEN K2", g.snapshot.facts.capabilityAllowed &&
                    g.snapshot.facts.recordFence?.krxCapabilityEpoch != original.scope.use.krxCapabilityEpoch)
            }
            // Control effects of the actual PAC input have landed; the queued graph cleanup must preserve them exactly.
            val control = g.r.h.store.record
            val records = g.session.cleanup.records
            p.yes("actual retirement ledger sentinel exists before graph Main cleanup", records.isNotEmpty())
            val fe = listOf(g.r.display.owner, g.r.rates.quotes.filterKeys { it.source != "krx" },
                g.r.store.snapshot.topics.filterKeys { !isKrxGraphTopic(it) }, g.r.store.snapshot.controlState,
                g.r.store.snapshot.authResolution, g.r.store.snapshot.wholeFailure)
            p.yes("GENERAL FE sentinel is still positive in the held Main window", g.r.rates.quotes.any { it.key.source != "krx" && it.value.rate == 1444.0 })
            serial.release(); runCurrent()
            p.eq("physical Main cleanup preserves raw control/journal record", control, g.r.h.store.record)
            p.eq("physical Main cleanup preserves exact existing retirement ledger", records, g.session.cleanup.records)
            p.eq("physical Main cleanup preserves exact GENERAL FE and control state", fe,
                listOf(g.r.display.owner, g.r.rates.quotes.filterKeys { it.source != "krx" },
                    g.r.store.snapshot.topics.filterKeys { !isKrxGraphTopic(it) }, g.r.store.snapshot.controlState,
                    g.r.store.snapshot.authResolution, g.r.store.snapshot.wholeFailure))
            p.eq("PAC owner identity is preserved", identity, g.snapshot.facts.recordFence?.ownerUid)
            p.eq("GENERAL token unchanged by KRX PAC input", before.facts.token, g.snapshot.facts.token)
            p.eq("GENERAL USER namespace and floor preserved", before.facts.recordFence?.userAccessEpoch to before.userEndInvalidationsFloor,
                g.snapshot.facts.recordFence?.userAccessEpoch to g.snapshot.userEndInvalidationsFloor)
            p.eq("physical cleanup preserves REC USER floor", floor, assembly.recorder.state.value.versionFloor)
            p.yes("old original withdrawn by actual PAC input", !g.snapshot.admitsKrxUse(original.scope.use))
            val latest = p.need(assembly.krxMemoryPurgeState, "automatic collector evaluated actual PAC revision")
            p.yes("latest evaluation is complete even after a later no-change revision", latest.failure == null && latest.outcome?.unresolved == false)
            p.yes("old original no longer in actual REC cache", original !in assembly.recorder.state.value.krxSeriesOwners.values &&
                assembly.recorder.state.value.pending.inputs.none { p.stamp(it) == original })
            val fresh = if (col.endsWith("rotation")) g.session.rest(1777.0, AT).also {
                p.yes("actual K2 raw is positive before old completion consumption", assembly.recorder.state.value.krxSeriesOwners[KRX] == it.krxGraphStamp &&
                    it.krxGraphStamp?.scope?.use?.krxCapabilityEpoch != original.scope.use.krxCapabilityEpoch)
            } else null
            barrier.release()
            p.yes("GENERAL completion is still adopted after physical cleanup", assembly.coordinator.protectedEntry(call.key)?.tab?.graph?.series?.any { it.seriesId == GENERAL } == true)
            p.yes("consuming native old FIFO cannot recreate old KRX", original !in assembly.recorder.state.value.krxSeriesOwners.values)
            if (fresh != null) p.eq("consuming old native completion preserves actual current K2", fresh.krxGraphStamp,
                assembly.recorder.state.value.krxSeriesOwners[KRX])
        }
    }

    @Test fun b12_d1DeletesMemoryWithoutRevokingCapability() = s63b2Test("B12", "D1", "degraded", "dormant", "gap", "lease", "hold") { p, col ->
        val g = p.g; g.start(leaseSeconds = 1); val old = g.adopt(); g.general()
        val before = g.print(g.krx()); val general = g.generalPrint(); val floor = g.raw.versionFloor
        val control = listOf(g.snapshot.krxPermitSeq, g.snapshot.facts.recordFence, g.r.h.store.record)
        val calls = g.r.h.source.calls.size
        when (col) {
            "D1" -> g.session.d1()
            "degraded", "dormant" -> {
                val call = g.session.cleanup.issue()
                g.session.cleanup.f.reply(call, if (col == "degraded") KrxA1bFixture.Reply(404, """{"error":"topic_unavailable"}""")
                    else KrxA1bFixture.Reply(404, """{"error":"topics_disabled"}"""))
                p.yes("REST producer really completed", call.outcome != null && !call.cancelled)
            }
            "gap" -> { g.r.wire.drop(); g.r.drain() }
            "lease" -> g.r.advance(1_001)
            else -> g.session.cleanup.hold()
        }
        p.yes("raw KRX still held before explicit purge", old.krxGraphStamp in g.raw.krxSeriesOwners.values)
        val retainedBeforePurge = g.print(g.krx()); val retainedGeneral = g.generalPrint()
        val outcome = p.purgeHeld(p.plan())
        if (col == "D1") {
            p.yes("D1 physically removes current topic original", outcome.removed)
            p.rawRemoved(old.krxGraphStamp!!)
            p.eq("D1 preserves grant, p, epoch and journal", control, listOf(g.snapshot.krxPermitSeq, g.snapshot.facts.recordFence, g.r.h.store.record))
            p.eq("D1 does not fetch entitlements", calls, g.r.h.source.calls.size)
        } else {
            p.eq("transient state grants no purge authority", KrxGraphPurgeOutcome(false, false), outcome)
            p.eq("transient state retains raw verification and T11 need", retainedBeforePurge, g.print(g.krx()))
        }
        p.unchangedGeneral(if (col == "D1") general else retainedGeneral, floor)
    }

    @Test fun b20_partialUnknownD1NeverBecomesCompleted() = s63b2Test("B20", "missing-sidecar", "null-namespace", "foreign", "K2", "actual-report") { p, col ->
        if (col == "actual-report") {
            val g = p.g; val (assembly, _) = g.ownerAssembly(); val old = g.session.ws(); val original = old.krxGraphStamp!!
            val unknown = p.fact(null, old, 90_031, setOf(old.topic, "fx:usd-krw"), TopicGraphEventKind.DELIVERY_RESUMED)
            assembly.recorder.observe(unknown)
            p.yes("actual catalog-wait REC owns known and unknown inputs before D1", unknown in assembly.recorder.state.value.pending.inputs &&
                assembly.recorder.state.value.pending.inputs.any { p.stamp(it) == original })
            g.session.d1(); runCurrent()
            val state = p.need(assembly.krxMemoryPurgeState, "partial D1 publishes actual Main report")
            p.yes("actual Main reports partial removal and unresolved provenance", state.outcome?.let { it.removed && it.unresolved } == true)
            p.yes("unknown survives actual automatic D1", unknown in assembly.recorder.state.value.pending.inputs)
            val answer = GraphRecorderPurgeAdapter(assembly.recorder, assembly.sink, assembly.coordinator, g.dispatcher)
                .purge(p.request(p.namespace(original)))
            p.yes("current/unknown CAP target cannot complete partial D1 obligation", answer is TargetOutcome.Failed)
            return@s63b2Test
        }
        val g = p.g; g.start(); val original = g.adopt().krxGraphStamp!!
        val old = g.session.ws()
        val cutoff = g.session.ingress.topicUnavailable(original.scope)
        val plan = p.need(KrxGraphPurgePlan.forCutoff(cutoff), "D1 plan exists")
        val unknown = if (col == "missing-sidecar") null else original.copy(scope = original.scope.copy(use = original.scope.use.copy(krxCapabilityEpoch = null)))
        val future = p.need(g.session.fence.capture(original.scope), "same namespace future K2 issued")
        p.yes("K2 strictly follows fixed cutoff", future.issueSequence > cutoff.throughIssueSequence)
        // boundary-input: raw pending accepts original envelopes without manufacturing REC admission.
        fun envelope(stamp: KrxGraphStamp?, seq: Long) = TopicGraphInput.Observations(seq, old.topic, old.path,
            TopicUseAttribution(old.attribution.sessionKey, stamp?.scope?.owner ?: old.attribution.owner,
                old.attribution.grantEpoch, stamp?.scope?.use?.lifetime ?: old.attribution.lifetime),
            old.connectionGeneration, old.candidates, stamp)
        val foreign = original.copy(scope = original.scope.copy(owner = original.scope.owner.copy(identity = original.scope.owner.identity.copy(uid = "foreign"))))
        val foreignInput = envelope(foreign, 103)
        val knownInput = envelope(original, 100); val unknownInput = envelope(unknown, 101); val futureInput = envelope(future, 102)
        var pending = offerGraphPendingInput(GraphPendingInputs.EMPTY, knownInput)
        pending = offerGraphPendingInput(pending, unknownInput)
        pending = offerGraphPendingInput(pending, futureInput)
        pending = offerGraphPendingInput(pending, foreignInput)
        p.yes("known/unknown/K2/foreign all positively held before projection", listOf(knownInput, unknownInput, futureInput, foreignInput).all { it in pending.inputs })
        val (remaining, outcome) = pending.purgeKrxMemory(plan)
        p.eq("partial physical deletion remains unresolved", KrxGraphPurgeOutcome(true, true), outcome)
        p.yes("known cutoff envelope removed", knownInput !in remaining.inputs)
        p.eq("unknown and K2 survive in original FIFO", listOf(unknownInput, futureInput, foreignInput), remaining.inputs)
        p.eq("unknown is never backfilled from current scope", null, p.stamp(unknownInput)?.scope?.use?.krxCapabilityEpoch)
        p.eq("known cutoff portion selected", KrxGraphPurgeDecision.REMOVE, plan.classify(original, original.scope.owner.identity.uid))
        p.eq("unknown portion is unresolved", KrxGraphPurgeDecision.UNRESOLVED, plan.classify(unknown, original.scope.owner.identity.uid))
        val unknownCutoff = g.session.ingress.topicUnavailable(null)
        p.eq("unknown cutoff cannot become a successful plan", null, KrxGraphPurgePlan.forCutoff(unknownCutoff))
        p.eq("K2 protected", KrxGraphPurgeDecision.KEEP, plan.classify(future, future.scope.owner.identity.uid))
    }

    @Test fun b21_fullClosedAndFailedCleanupStayHonest() = s63b2Test("B21",
        "FULL", "deferred", "CLOSED-runtime", "empty-runtime", "late-ledger-FULL", "late-ledger-CLOSED", "late-ledger-FAILED", "late-sink",
        "write-preparing-withdraw-fails", "write-ready-withdraw-fails", "write-ready-withdraw-cancels") { p, col ->
        if (col == "write-ready-withdraw-cancels") {
            writeWithdrawalCancellation(p)
            return@s63b2Test
        }
        if (col.startsWith("write-")) {
            writeWithdrawalFailure(p, if (col.startsWith("write-ready")) "ready" else "preparing")
            return@s63b2Test
        }
        val g = p.g; g.start(); val old = g.adopt(); val original = old.krxGraphStamp!!; val general = g.general()
        val coordinator = g.readyCoordinator(); val sink = p.sink()
        if (col.startsWith("late-ledger")) {
            val ledger = TopicGraphLossLedger()
            val fact = p.fact(original, old, 100)
            p.yes("ledger owns old original before purge", ledger.record(fact, TopicGraphOffer.CLOSED, 1_000))
            val plan = p.cutoff(original)
            p.yes("ledger removes selected original", ledger.purgeKrxMemory(plan).removed)
            p.eq("selected-only view becomes empty", null, ledger.view)
            val mode = TopicGraphOffer.valueOf(col.substringAfterLast('-'))
            val late = p.fact(original, old, 101)
            p.eq("late higher sequence is refused by persistent seal", false, ledger.record(late, mode, 900))
            p.eq("late refusal does not recreate a view", null, ledger.view)
            val mixed = p.fact(original, old, 102, setOf(old.topic, general.topic))
            p.yes("late mixed batch keeps GENERAL once", ledger.record(mixed, mode, 800))
            val view = p.need(ledger.view, "GENERAL survives late mixed batch")
            p.eq("one GENERAL batch", 1L, view.count)
            p.eq("no persistent old stamp", emptyMap<String, Set<KrxGraphStamp>>(), view.krxLost)
            p.eq("only GENERAL topic", setOf(general.topic), view.topics)
        } else {
            val slots = field(coordinator, "protectedSlots") as Map<*, *>
            p.yes("runtime owns a protected KRX component before sink checks", slots.values.any {
                (field(p.need(it, "slot"), "components") as GraphV2DiskComponents).krx != null
            })
            if (col == "CLOSED-runtime") sink.close()
            if (col == "FULL") {
                p.eq("GENERAL occupies 512 units", TopicGraphOffer.ENQUEUED, sink.tryOffer(p.batch(general, 512)))
                p.eq("old original reaches FULL ledger", TopicGraphOffer.FULL, sink.tryOffer(old))
                p.yes("FULL ledger owns old source", original in p.sinkLoss(sink).values.flatten())
            } else if (col == "deferred" || col == "late-sink") {
                p.eq("unstarted sink owns KRX input", TopicGraphOffer.ENQUEUED, sink.tryOffer(old))
                p.yes("input held before cutoff", old in p.queued(sink))
            }
            val plan = p.cutoff(original)
            p.purgeHeld(plan, sink)
            val runtime = coordinator.purgeKrxMemory(plan)
            p.yes("closed/empty sink never hides runtime work", runtime.removed && !runtime.unresolved)
            p.yes("runtime protected components are physically stripped", (field(coordinator, "protectedSlots") as Map<*, *>).values.all {
                (field(p.need(it, "remaining slot"), "components") as GraphV2DiskComponents).krx == null
            })
            if (col == "late-sink") {
                p.eq("late old temporarily enqueues", TopicGraphOffer.ENQUEUED, sink.tryOffer(old))
                p.yes("temporary old queue ownership", old in p.queued(sink))
                sink.start(); runCurrent(); p.rawRemoved(original)
            }
        }
    }

    private suspend fun writeWithdrawalCancellation(p: S6KrxGraphMemoryPurgeRig) {
        val g = p.g; g.start()
        val window = p.writeWindow("ready", cancelWithdrawals = true); window.start()
        g.session.capEnd()
        p.yes("actual automatic purge reached cancelled withdrawal", window.failedWithdrawals.contains(window.ticket))
        val state = p.need(window.assembly.krxMemoryPurgeState, "cancelled purge retains an incomplete report")
        p.yes("cancelled target never becomes a completed purge", state.failure is CancellationException)
        window.assertOwnership()
        p.eq("cancelled withdrawal never delegates", emptyList<GraphV2WriteTicket>(), window.delegatedWithdrawals)
        p.yes("unrelated GENERAL write is still held", !window.hold.isCompleted && window.delegateWrites == 0)
        window.hold.complete(Unit); g.test.runCurrent()
        p.eq("GENERAL write survives purge cancellation", GraphV2ComponentWriteOutcome.Replaced, window.reports.singleOrNull()?.general)
        p.yes("cancelled purge cannot admit old K1", window.reports.singleOrNull()?.krx is GraphV2ComponentWriteOutcome.Skipped)
        p.yes("cancellation is not overwritten by GENERAL completion", window.assembly.krxMemoryPurgeState?.failure != null)
    }

    /** Only the ready column isolates the final admission gate (muB21g). */
    private suspend fun writeWithdrawalFailure(p: S6KrxGraphMemoryPurgeRig, stage: String) {
        val g = p.g; g.start()
        val window = p.writeWindow(stage); window.start()
        val selected = g.session.ws(1555.0, AT)
        val generalInput = g.general()
        window.assembly.recorder.observe(selected); window.assembly.recorder.observe(generalInput)
        val recBefore = window.assembly.recorder.state.value
        p.yes("earlier REC target positively owns an actual original before later write target fails", selected.krxGraphStamp in recBefore.krxSeriesOwners.values)
        val generalBefore = recBefore.series.filterKeys { it.seriesId != KRX }.mapValues { g.print(it.value) }
        p.yes("earlier REC target has a positive GENERAL sentinel", generalBefore.isNotEmpty())
        val before = g.snapshot; val captured = window.captured
        val k1 = p.need(captured.krxCapabilityEpoch, "original K1 write epoch")
        val original = p.need(captured.krxGraphStamp, "actual original write source")
        val floor = window.assembly.recorder.state.value.versionFloor
        g.session.capEnd()
        p.yes("automatic purge attempted selected ticket before write delegate", window.failedWithdrawals.contains(window.ticket))
        val failed = p.need(window.assembly.krxMemoryPurgeState, "automatic withdrawal failure is reported")
        p.yes("failed withdrawal is never reported as completed", failed.failure != null)
        p.yes("earlier REC physical success survives later write-target exception", selected.krxGraphStamp !in window.assembly.recorder.state.value.krxSeriesOwners.values &&
            window.assembly.recorder.state.value.series.keys.none { it.seriesId == KRX })
        p.eq("partial target cleanup preserves actual GENERAL raw data", generalBefore,
            window.assembly.recorder.state.value.series.filterKeys { it.seriesId != KRX }.mapValues { g.print(it.value) })
        val attemptsBeforeK2 = window.failedWithdrawals.size
        window.assertOwnership()
        g.session.capReopen()
        p.yes("K2 revision actually retries the failed plan", window.failedWithdrawals.size > attemptsBeforeK2)
        p.eq("no withdrawal attempt ever delegates for selected ticket", emptyList<GraphV2WriteTicket>(), window.delegatedWithdrawals)
        window.assertOwnership()
        val after = g.snapshot
        p.eq("rotation preserves GENERAL token", before.facts.token, after.facts.token)
        p.yes("GENERAL token and use remain live", after.facts.userAllowed && after.facts.tokenStanding && after.admitsUse(captured.lifetime))
        p.eq("rotation preserves GENERAL lifetime invalidations", before.userInvalidations, after.userInvalidations)
        p.eq("rotation preserves USER epoch", before.facts.recordFence?.userAccessEpoch, after.facts.recordFence?.userAccessEpoch)
        p.eq("rotation preserves USER end/floor", before.lastUserEnd to before.userEndInvalidationsFloor,
            after.lastUserEnd to after.userEndInvalidationsFloor)
        p.eq("REC USER floor unchanged", floor, window.assembly.recorder.state.value.versionFloor)
        val k2 = p.need(after.facts.recordFence?.krxCapabilityEpoch, "confirmed K2 epoch")
        p.yes("actual rotation and K2 reapproval are positive", k2 != k1 && after.facts.capabilityAllowed)
        p.yes("K1 original admission is closed while GENERAL is open", !window.assembly.gate.admits(captured, GraphV2DiskComponent.KRX) &&
            window.assembly.gate.admits(captured, GraphV2DiskComponent.GENERAL))
        val fresh = p.need(window.assembly.gate.captureKrxGraph(captured.fence, captured.lifetime), "actual fresh K2 source admitted")
        p.yes("fresh source really belongs to K2", fresh.captured.krxCapabilityEpoch == k2 &&
            fresh.captured.krxGraphStamp?.issueSequence?.let { it > original.issueSequence } == true)
        p.yes("failure plan remains unresolved after K2 revision", window.assembly.krxMemoryPurgeState?.failure != null)
        p.eq("real write delegate still untouched before hold release", 0, window.delegateWrites)
        p.yes("actual write hold still positive", !window.hold.isCompleted)
        p.eq("purge and all failure retries never cancel the whole held ticket", emptyList<GraphV2WriteTicket>(), window.cancelled)
        window.hold.complete(Unit); g.test.runCurrent()
        p.eq("actual delegate consumed exactly the original ticket", 1, window.delegateWrites)
        p.eq("real write report completed exactly once", 1, window.reports.size)
        val report = window.reports.single()
        p.eq("GENERAL really replaces its file", GraphV2ComponentWriteOutcome.Replaced, report.general)
        p.eq("KRX blocked specifically by actual admission", GraphV2ComponentWriteOutcome.Skipped("Graph I/O admission is closed"), report.krx)
        p.yes("real store queried closed KRX commit admission", window.commitAdmissions.any { it.first == GraphV2DiskComponent.KRX && !it.second })
        p.yes("GENERAL file exists after real write", window.real.readGeneral(window.components.general.key, null) { true } is GraphV2DiskRead.Found)
        val key = p.need(window.components.krx, "original K1 candidate key").key
        p.yes("K1 file remains absent after real write attempt", window.real.readKrx(key, null) { true } !is GraphV2DiskRead.Found)
        p.eq("all withdrawal retries failed before delegation", emptyList<GraphV2WriteTicket>(), window.delegatedWithdrawals)
        p.eq("normal write completion releases exactly its original ticket once", listOf(window.ticket), window.cancelled)
        p.yes("successful GENERAL completion does not falsify purge failure", window.assembly.krxMemoryPurgeState?.failure != null)
    }

    @Test fun b22_oldPlansCannotDeleteFutureK2() = s63b2Test("B22", "D1", "rotation", "foreign", "other-epoch", "repeat") { p, col ->
        if (col == "rotation") {
            actualRotationDelayedPlan(p)
            return@s63b2Test
        }
        val g = p.g; g.start(); val old = g.adopt(); val original = old.krxGraphStamp!!
        val plan = if (col == "rotation") {
            g.boundaryRoundTrip("epoch", original); p.plan()
        } else p.cutoff(original)
        val fixed = plan.accessRevision to plan.cutoffRevision
        val future = p.need(g.session.fence.capture(original.scope), "future source issued after plan")
        p.yes("future sequence is strictly newer", future.issueSequence > original.issueSequence)
        val futureRevision = future.copy(scope = future.scope.copy(acquiredRevision = plan.accessRevision + 1))
        val foreign = original.copy(scope = original.scope.copy(owner = original.scope.owner.copy(identity = original.scope.owner.identity.copy(uid = "foreign"))))
        val other = original.copy(scope = original.scope.copy(use = original.scope.use.copy(krxCapabilityEpoch = "other")))
        p.eq("original is selected by frozen proof", KrxGraphPurgeDecision.REMOVE, plan.classify(original, original.scope.owner.identity.uid))
        p.eq("future revision always kept", KrxGraphPurgeDecision.KEEP, plan.classify(futureRevision, future.scope.owner.identity.uid))
        if (col != "rotation") {
            p.eq("same namespace future sequence is not D1-selected", KrxGraphPurgeDecision.KEEP, plan.classify(future, future.scope.owner.identity.uid))
            p.eq("different capability epoch is outside cutoff", KrxGraphPurgeDecision.KEEP, plan.classify(other, other.scope.owner.identity.uid))
        }
        p.eq("foreign owner outside proof", KrxGraphPurgeDecision.KEEP, plan.classify(foreign, "foreign"))
        if (col != "rotation") {
            val sink = p.sink()
            p.eq("old queue ownership predates fresh completion", TopicGraphOffer.ENQUEUED, sink.tryOffer(old))
            fun outside(stamp: KrxGraphStamp, sequence: Long) = TopicGraphInput.Observations(sequence, old.topic, old.path,
                TopicUseAttribution(old.attribution.sessionKey, stamp.scope.owner, old.attribution.grantEpoch, stamp.scope.use.lifetime),
                old.connectionGeneration, old.candidates, stamp)
            val foreignInput = outside(foreign, 90_040); val otherInput = outside(other, 90_041)
            p.eq("foreign payload positively held before delayed plan", TopicGraphOffer.ENQUEUED, sink.tryOffer(foreignInput))
            p.eq("other epoch payload positively held before delayed plan", TopicGraphOffer.ENQUEUED, sink.tryOffer(otherInput))
            val fresh = g.session.rest(1777.0, AT + 2.seconds)
            p.yes("actual K2 producer completed and owns current raw anchor", fresh.krxGraphStamp != original &&
                g.raw.krxSeriesOwners[KRX] == fresh.krxGraphStamp)
            val live = g.print(g.krx())
            p.yes("old input still held when delayed plan executes", old in p.queued(sink))
            p.yes("old queue payload physically removed", p.purgeHeld(plan, sink).removed)
            p.eq("delayed old plan preserves current K2 data", live, g.print(g.krx()))
            p.eq("foreign and other epoch raw payloads survive in FIFO", listOf(foreignInput, otherInput), p.queued(sink))
            p.rawRemoved(original, allowLive = true)
        } else {
            p.yes("old raw still present before delayed purge", original in g.raw.krxSeriesOwners.values)
            p.yes("delayed purge removes old raw", g.recorder.purgeKrxMemory(plan).removed)
            p.rawRemoved(original)
        }
        p.eq("plan revisions remain frozen", fixed, plan.accessRevision to plan.cutoffRevision)
        p.eq("repeat is idempotent", KrxGraphPurgeOutcome(false, false), g.recorder.purgeKrxMemory(plan))
    }

    private suspend fun actualRotationDelayedPlan(p: S6KrxGraphMemoryPurgeRig) {
        val g = p.g; g.start(); val old = g.adopt(); val k1 = old.krxGraphStamp!!; g.general()
        val sink = p.sink()
        p.eq("actual old original is positively held before rotation", TopicGraphOffer.ENQUEUED, sink.tryOffer(old))
        g.session.capEnd()
        val frozen = p.plan(); val revisions = frozen.accessRevision to frozen.cutoffRevision
        g.session.capReopen()
        val fresh = g.session.rest(1777.0, AT + 2.seconds); val k2 = p.need(fresh.krxGraphStamp, "actual K2 after confirmed rotation")
        p.yes("actual future K2 acquisition follows the frozen end plan", k2.scope.acquiredRevision > frozen.accessRevision &&
            k2.scope.use.krxCapabilityEpoch != k1.scope.use.krxCapabilityEpoch)
        // This coordinator is first assembled after both actual publications; it sees only the final OPEN K2 snapshot.
        val coordinator = g.readyCoordinator()
        val key = GraphKey("usd", GraphPeriod.THREE_MONTHS)
        val slot = p.need((field(coordinator, "protectedSlots") as Map<*, *>)[key], "actual K2 protected slot")
        val components = field(slot, "components") as GraphV2DiskComponents
        val slotStamp = p.need(field(slot, "krxGraphStamp") as? KrxGraphStamp, "actual K2 server slot source")
        p.yes("fresh slot owns actual K2 beyond old plan", components.krx?.key?.krxCapabilityEpoch == k2.scope.use.krxCapabilityEpoch &&
            slotStamp.scope.acquiredRevision > frozen.accessRevision)
        val raw = g.print(g.krx()); val publicEntry = coordinator.protectedEntry(key)
        p.yes("old K1 input still held beside actual current K2 before delayed purge", old in p.queued(sink) && g.raw.krxSeriesOwners[KRX] == k2)
        p.eq("old frozen plan selects K1", KrxGraphPurgeDecision.REMOVE, frozen.classify(k1, k1.scope.owner.identity.uid))
        p.eq("old frozen plan excludes actual future K2", KrxGraphPurgeDecision.KEEP, frozen.classify(k2, k2.scope.owner.identity.uid))
        p.yes("delayed plan removes only old queued payload", p.purgeHeld(frozen, sink).removed)
        p.eq("delayed old plan does no K2 runtime work", KrxGraphPurgeOutcome(false, false), coordinator.purgeKrxMemory(frozen))
        assertSame(g.c("actual K2 protected slot object is unchanged"), slot, (field(coordinator, "protectedSlots") as Map<*, *>)[key])
        p.eq("actual K2 raw data survives delayed old plan", raw, g.print(g.krx()))
        p.eq("actual K2 public entry survives delayed old plan", publicEntry, coordinator.protectedEntry(key))
        p.eq("old queue is physically empty", emptyList<TopicGraphInput>(), p.queued(sink))
        p.eq("repeat cannot delete future raw K2", KrxGraphPurgeOutcome(false, false), p.purgeHeld(frozen, sink))
        p.eq("plan revisions remain frozen after actual rotation", revisions, frozen.accessRevision to frozen.cutoffRevision)
        // boundary-input: counterfactual old-epoch source acquired after the plan isolates the revision bound itself.
        val futureOldNamespace = k1.copy(scope = k1.scope.copy(acquiredRevision = k2.scope.acquiredRevision), issueSequence = k2.issueSequence + 1)
        p.eq("rotation never sweeps a counterfactual future original", KrxGraphPurgeDecision.KEEP,
            frozen.classify(futureOldNamespace, k1.scope.owner.identity.uid))
    }

    @Test fun b23_mixedLossPreservesGeneralBatchAccounting() = s63b2Test("B23", "FULL", "FAILED", "CLOSED") { p, col ->
        val g = p.g; g.start(); val old = g.adopt(); val original = old.krxGraphStamp!!; val general = g.general()
        val ledger = TopicGraphLossLedger(); val mode = TopicGraphOffer.valueOf(col)
        // boundary-input: six original-attributed batches, same USER invalidations, reversed wall clock.
        for (sequence in 1L..6L) {
            val topics = when { sequence <= 3 -> setOf(old.topic); sequence <= 5 -> setOf(general.topic); else -> setOf(old.topic, general.topic) }
            val fact = p.fact(if (sequence <= 3 || sequence == 6L) original else null, old, sequence, topics)
            p.yes("each independent batch accepted exactly once", ledger.record(fact, mode, 1_000L - sequence * 100))
        }
        val before = p.need(ledger.view, "six batch loss window exists")
        p.eq("six batches before physical projection", 6L, before.count)
        p.yes("original present before projection", original in before.krxLost.values.flatten())
        p.eq("duplicate high-water refused before purge", false, ledger.record(p.fact(original, old, 6), mode, 0))
        val plan = p.cutoff(original)
        p.eq("selected purge reports removal without unknown", KrxGraphPurgeOutcome(true, false), ledger.purgeKrxMemory(plan))
        val after = p.need(ledger.view, "GENERAL2 plus mixed1 survive")
        p.eq("pure KRX3 removed, mixed counts GENERAL once", 3L, after.count)
        p.eq("surviving first/last sequence", 4L to 6L, after.firstSequence to after.lastSequence)
        p.eq("original times follow surviving sequences, not sorted clocks", 600L to 400L,
            after.firstOccurredAtEpochMillis to after.lastOccurredAtEpochMillis)
        p.eq("one removal revision", before.revision + 1, after.revision)
        p.eq("GENERAL topics only", setOf(general.topic), after.topics)
        p.eq("paths preserved", before.paths, after.paths)
        p.eq("authority preserved", before.authorities, after.authorities)
        p.eq("selected stamp not retained", emptyMap<String, Set<KrxGraphStamp>>(), after.krxLost)
        val state = p.need(field(ledger, "state"), "ledger state for raw key inspection")
        val keys = (field(state, "aggregates") as Map<*, *>).keys
        for (key in keys) {
            @Suppress("UNCHECKED_CAST") val originals = field(p.need(key, "owned key"), "originalKrxLost") as Map<String, Set<KrxGraphStamp>>
            p.yes("re-keying removes selected original from raw keys too", original !in originals.values.flatten())
        }
        p.eq("old/duplicate sequence still refused after purge", false, ledger.record(p.fact(null, general, 5), mode, 50))
        assertSame(g.c("refusal changes neither revision nor immutable view"), after, ledger.view)
        p.eq("detached historical view remains intact", 6L, before.count)
        p.yes("late-seal proof cannot delete a future source", g.session.fence.capture(original.scope)?.let {
            ledger.record(p.fact(it, old, 7), mode, 300)
        } == true)
    }

    @Test fun b26_realAssemblyPurgesMountedCachesAndSessionLoss() = s63b2Test("B26",
        "D1", "end", "CONFIRMING-end", "CONFIRMING-rotation", "INITIALIZING-end", "Main-first", "hidden", "detach", "close", "reassembly",
        *listOf("READY", "CONFIRMING", "INITIALIZING").flatMap { selection ->
            listOf("visible", "hidden").flatMap { visibility -> listOf("Main-first", "TSC-first").map { order ->
                "matrix:$selection:$visibility:$order"
            } }
        }.toTypedArray()) { p, col ->
        if (col.startsWith("matrix:")) {
            samePriceMountedRotation(p, col)
            return@s63b2Test
        }
        val g = p.g; val (assembly, runtime) = g.ownerAssembly()
        val old = g.session.ws(); val original = old.krxGraphStamp!!
        val mounted = listOf("usd", "tether").map { p.mount(assembly, it) }
        val holders = mounted.map { it.holder }
        g.sent.filter { !it.response.isCompleted }.forEach { it.response.complete(S6KrxGraphRig.ok(S6KrxGraphRig.tabDto(it.key))) }; runCurrent()
        for (holder in holders) {
            val token = p.need(holder.currentState().inlineToken, "mounted inline token")
            holder.setSurfaceVisible(token, true); holder.enterFullscreen(token)
            holder.currentState().fullscreenToken?.let { holder.setSurfaceVisible(it, true) }
            p.yes("READY chart has positive KRX before invalidation", holder.currentState().chart?.renderedIds?.contains(KRX) == true)
            p.yes("prepared cache is positive before invalidation", field(holder, "prepared") != null && field(holder, "preparationKey") != null)
            holder.onTimeEvent()
            val prepared = p.need(field(holder, "prepared") as? com.jay.fxi.ui.graph.PreparedGraph, "actual prepared geometry before invalidation")
            p.yes("prepared geometry positively owns KRX", KRX in prepared.bySeries)
            val publication = p.need(field(holder, "livePublication"), "actual live publication before invalidation")
            p.yes("live geometry positively owns KRX", KRX in (field(publication, "prepared") as com.jay.fxi.ui.graph.PreparedGraph).bySeries)
        }
        try {
            if (col.startsWith("CONFIRMING")) for (mount in mounted) {
                val holder = mount.holder
                mount.store.uncertain = true
                mount.store.confirmGate = CompletableDeferred()
                holder.toggleSeries(p.need(holder.currentState().inlineToken, "toggle token"), GENERAL); runCurrent()
                p.eq("actual write enters CONFIRMING before CAP change", com.jay.fxi.ui.premium.graph.GraphV2SelectionStatus.CONFIRMING,
                    holder.currentState().selectionStatus)
                p.yes("KRX preparation is still cached during confirmation window", field(holder, "prepared") != null)
            }
            if (col.startsWith("INITIALIZING")) for (mount in mounted) {
                val holder = mount.holder
                val binding = p.need(holder.currentState().inlineToken, "READY public token before restore").binding
                mount.store.absent = true
                mount.store.writeGate = CompletableDeferred()
                val confirmations = mount.store.confirmations; val writes = mount.store.writes
                g.scope.launch { mount.selection.restore(binding) }; runCurrent()
                p.yes("public restore actually read the absent preference dependency", mount.store.confirmations > confirmations)
                p.yes("actual initialization write is positively suspended", mount.store.writes > writes && mount.store.writeGate?.isCompleted == false)
                p.eq("actual restore enters INITIALIZING before CAP change", com.jay.fxi.ui.premium.graph.GraphV2SelectionStatus.INITIALIZING,
                    holder.currentState().selectionStatus)
                p.yes("old preparation positive during initialization", field(holder, "prepared") != null)
            }
            if (col == "hidden") holders.forEach { holder ->
                holder.currentState().inlineToken?.let { holder.setSurfaceVisible(it, false) }
                holder.currentState().fullscreenToken?.let { holder.setSurfaceVisible(it, false) }
            }
            // boundary-input: actual source authority in a real SESSION ledger, no fabricated producer claim.
            val fact = p.fact(original, old, 90_001)
            p.yes("SESSION loss positively held before cleanup", p.ledger.record(fact, TopicGraphOffer.CLOSED, 100))
            if (col == "close") assembly.close() else if (col == "detach") runtime.detachGraph()
            else if (col == "D1") g.session.d1() else if (col == "Main-first") {
                assembly.krxGraphIngress.topicUnavailable(original.scope); runCurrent()
                p.yes("Main collector runs before TSC receives its own cutoff", assembly.krxMemoryPurgeState != null)
                g.session.d1()
            } else {
                g.session.capEnd()
                if (col.endsWith("rotation")) g.session.capReopen()
            }
            runCurrent()
            if (col == "close" || col == "detach") {
                val wrapper = field(runtime, "graphInputs") as DetachableTopicGraphSink
                p.eq("detached ingress cannot issue a new source", null, wrapper.capture(original.scope))
                p.eq("detached ingress cannot install cutoff", KrxGraphTopicResult.NO_EFFECT, wrapper.topicUnavailable(original.scope).result)
            } else {
                p.yes("automatic Main report exists without Now", assembly.krxMemoryPurgeState != null)
                p.yes("selected raw cache cleared", original !in assembly.recorder.state.value.krxSeriesOwners.values)
                p.yes("TSC loop independently reports its work", g.session.cleanup.session.krxGraphLossPurgeState != null)
                p.yes("SESSION selected original physically gone", p.ledger.view?.krxLost.orEmpty().values.none { original in it })
                for (holder in holders) {
                    holder.currentState()
                    val key = field(holder, "preparationKey") as? com.jay.fxi.ui.premium.graph.GraphV2PreparationKey
                    p.yes("cached key no longer references old KRX graph", key?.graph?.series?.none { it.seriesId == KRX } != false)
                    if (col.startsWith("CONFIRMING") || col.startsWith("INITIALIZING")) p.eq("uncertain selection invalidates prepared cache before READY", null, field(holder, "prepared"))
                    p.yes("published chart no longer contains old KRX", holder.state.value.chart?.renderedIds?.contains(KRX) != true)
                    val prepared = field(holder, "prepared") as? com.jay.fxi.ui.graph.PreparedGraph
                    p.yes("raw prepared geometry contains no old KRX", prepared?.bySeries?.containsKey(KRX) != true)
                    val publication = field(holder, "livePublication")
                    p.yes("raw live geometry contains no old KRX", publication?.let {
                        KRX !in (field(it, "prepared") as com.jay.fxi.ui.graph.PreparedGraph).bySeries
                    } != false)
                    val entry = p.need(assembly.coordinator.protectedEntry(GraphKey(holder.state.value.tab, GraphPeriod.ONE_DAY)), "GENERAL runtime slot survives holder cleanup")
                    p.yes("GENERAL chart data survives mounted/hidden/uncertain cleanup", entry.tab.graph.series.any { it.seriesId == GENERAL })
                }
            }
            if (col == "reassembly") {
                assembly.close()
                val next = g.assembly(); next.start(); runCurrent()
                p.yes("new assembly has no old REC ownership", original !in next.recorder.state.value.krxSeriesOwners.values)
                p.eq("new assembly report starts independently", null, next.krxMemoryPurgeState)
            }
        } finally { mounted.forEach { it.store.release() } }
    }

    private suspend fun samePriceMountedRotation(p: S6KrxGraphMemoryPurgeRig, column: String) {
        val (_, selection, visibility, order) = column.split(':')
        val g = p.g; val serial = p.serialGate(); val (assembly, _) = p.ownerAssembly(serial)
        val old = g.session.ws(1555.0, AT); val k1 = old.krxGraphStamp!!
        val mounts = listOf("usd", "tether").map { p.mount(assembly, it, serial) }
        g.sent.filter { !it.response.isCompleted }.forEach { it.response.complete(S6KrxGraphRig.ok(S6KrxGraphRig.tabDto(it.key))) }; g.test.runCurrent()
        val oldPrepared = mounts.associateWith { mount ->
            val holder = mount.holder; val token = p.need(holder.currentState().inlineToken, "actual READY inline token before matrix window")
            holder.setSurfaceVisible(token, true); holder.enterFullscreen(token)
            holder.currentState().fullscreenToken?.let { holder.setSurfaceVisible(it, true) }
            holder.onTimeEvent()
            p.yes("actual READY chart renders KRX before uncertainty or rotation", holder.currentState().chart?.renderedIds?.contains(KRX) == true)
            val rest = p.need(field(holder, "prepared") as? com.jay.fxi.ui.graph.PreparedGraph, "actual positive REST preparation before matrix window")
            val publication = p.need(field(holder, "livePublication"), "actual live publication before matrix window")
            val live = p.need(field(publication, "prepared") as? com.jay.fxi.ui.graph.PreparedGraph, "actual positive live preparation before matrix window")
            p.yes("REST and live cache owners both contain KRX before matrix window", KRX in rest.bySeries && KRX in live.bySeries)
            assertSame(g.c("visible baseline chart uses the actual live preparation"), live, holder.currentState().chart?.prepared)
            rest to live
        }
        for (mount in mounts) {
            val holder = mount.holder
            when (selection) {
                "CONFIRMING" -> {
                    mount.store.uncertain = true; mount.store.confirmGate = CompletableDeferred()
                    holder.toggleSeries(p.need(holder.currentState().inlineToken, "actual toggle token"), GENERAL)
                    g.test.runCurrent()
                    p.eq("actual uncertain selection is CONFIRMING before rotation", com.jay.fxi.ui.premium.graph.GraphV2SelectionStatus.CONFIRMING, holder.currentState().selectionStatus)
                }
                "INITIALIZING" -> {
                    mount.store.absent = true; mount.store.writeGate = CompletableDeferred()
                    val binding = p.need(holder.currentState().inlineToken, "actual restore token").binding
                    val writes = mount.store.writes
                    g.scope.launch { mount.selection.restore(binding) }; g.test.runCurrent()
                    p.yes("actual initialization persistence is suspended before rotation", mount.store.writes > writes && mount.store.writeGate?.isCompleted == false)
                    p.eq("actual selection is INITIALIZING before rotation", com.jay.fxi.ui.premium.graph.GraphV2SelectionStatus.INITIALIZING, holder.currentState().selectionStatus)
                }
            }
            if (visibility == "hidden") {
                holder.currentState().inlineToken?.let { holder.setSurfaceVisible(it, false) }
                holder.currentState().fullscreenToken?.let { holder.setSurfaceVisible(it, false) }
            }
            p.yes("old KRX preparation is still positively held before cleanup", KRX in (field(holder, "prepared") as com.jay.fxi.ui.graph.PreparedGraph).bySeries)
        }
        // boundary-input: selected actual source in SESSION's owned target, independent of rendered prices.
        p.yes("SESSION selected original is positively held before either cleanup order", p.ledger.record(p.fact(k1, old, 90_100), TopicGraphOffer.CLOSED, 100))
        if (order == "Main-first") {
            assembly.krxGraphIngress.topicUnavailable(k1.scope); g.test.runCurrent()
            p.yes("Main report is present while TSC still owns its original loss", assembly.krxMemoryPurgeState != null &&
                k1 in p.need(p.ledger.view, "TSC target is still positive before actual end").krxLost.values.flatten())
            p.yes("Main independently removed old REC original first", k1 !in assembly.recorder.state.value.krxSeriesOwners.values)
            for (mount in mounts) if (selection != "READY") {
                mount.holder.currentState()
                p.eq("uncertain old preparation is cleared by Main before READY", null, field(mount.holder, "prepared"))
            }
        }
        serial.pause()
        g.session.capEnd(); g.session.capReopen()
        p.yes("actual Main work is queued across confirmed end and K2 OPEN", serial.pending > 0 && g.snapshot.facts.capabilityAllowed &&
            g.snapshot.facts.recordFence?.krxCapabilityEpoch != k1.scope.use.krxCapabilityEpoch)
        p.yes("TSC loop independently reports and removes selected loss", g.session.cleanup.session.krxGraphLossPurgeState?.failure == null &&
            g.session.cleanup.session.krxGraphLossPurgeState != null && p.ledger.view?.krxLost.orEmpty().values.none { k1 in it })
        if (order == "TSC-first") {
            p.yes("Main raw original is still physically present after TSC cleanup", k1 in assembly.recorder.state.value.krxSeriesOwners.values)
            oldPrepared.forEach { (mount, cached) ->
                assertSame(g.c("paused Main still owns exact old REST prepared object"), cached.first, field(mount.holder, "prepared"))
                if (visibility == "visible") assertSame(g.c("paused visible Main still owns exact old live prepared object"), cached.second,
                    field(p.need(field(mount.holder, "livePublication"), "old visible live publication is still physically held"), "prepared"))
                else p.eq("hidden surface already dropped its live publication before cleanup", null, field(mount.holder, "livePublication"))
            }
        }
        serial.release(); g.test.runCurrent()
        for (mount in mounts) {
            val holder = mount.holder; holder.currentState()
            p.yes("raw old REC anchor is gone after automatic Main", k1 !in assembly.recorder.state.value.krxSeriesOwners.values)
            p.yes("private key cannot retain old KRX geometry", (field(holder, "preparationKey") as? com.jay.fxi.ui.premium.graph.GraphV2PreparationKey)?.graph?.series?.none { it.seriesId == KRX } != false)
            p.yes("raw live publication contains no old KRX", field(holder, "livePublication")?.let {
                KRX !in (field(it, "prepared") as com.jay.fxi.ui.graph.PreparedGraph).bySeries
            } != false)
            if (selection != "READY") p.eq("selection pending window clears preparation before READY", null, field(holder, "prepared"))
            val key = GraphKey(holder.state.value.tab, GraphPeriod.ONE_DAY)
            p.yes("GENERAL slot survives every visibility/order/selection window", assembly.coordinator.protectedEntry(key)?.tab?.graph?.series?.any { it.seriesId == GENERAL } == true)
        }
        // Actual K2 carries the identical quote and timestamp; no fabricated snapshot or prepared geometry.
        val fresh = g.session.rest(1555.0, AT)
        p.yes("same-price K2 original is real and distinct", fresh.krxGraphStamp != k1 &&
            fresh.krxGraphStamp?.scope?.use?.krxCapabilityEpoch == g.snapshot.facts.recordFence?.krxCapabilityEpoch)
        for (mount in mounts) {
            val tab = mount.holder.state.value.tab
            mount.store.uncertain = false; mount.store.absent = false
            mount.store.confirmGate?.complete(GraphSelectionReadResult.Present(GraphSelectionRecord(1,
                p.need(g.owner, "actual preference owner").identity.uid, GraphSelectionAudience.PREMIUM, tab, setOf(GENERAL, KRX), setOf(GENERAL, KRX))))
            mount.store.writeGate?.complete(GraphSelectionWriteResult.Committed)
            assembly.coordinator.onActivated(GraphKey(tab, GraphPeriod.ONE_DAY)); assembly.coordinator.onRefreshRequested(force = true)
        }
        g.test.runCurrent()
        val freshCalls = g.sent.filter { !it.response.isCompleted }
        p.yes("actual fresh K2 producer is positive before completion", freshCalls.isNotEmpty() && freshCalls.all { it.admitted && !it.cancelled })
        freshCalls.forEach { it.response.complete(S6KrxGraphRig.ok(S6KrxGraphRig.tabDto(it.key))) }; g.test.runCurrent()
        p.yes("fresh K2 HTTP producers completed normally", freshCalls.all { it.returned && !it.cancelled })
        for ((mount, cached) in oldPrepared) {
            val holder = mount.holder; holder.onTimeEvent()
            val current = holder.currentState()
            p.eq("actual preference completion restores READY", com.jay.fxi.ui.premium.graph.GraphV2SelectionStatus.READY, current.selectionStatus)
            // Visible 1d prepares only the live projection; non-1d also requires a REST base.
            val rest = field(holder, "prepared") as? com.jay.fxi.ui.graph.PreparedGraph
            val next = if (visibility == "visible") {
                val publication = p.need(field(holder, "livePublication"), "actual new visible K2 live publication")
                if (current.activePeriod != GraphPeriod.ONE_DAY) p.yes("non-1d live projection has a positive REST base", rest != null)
                p.need(field(publication, "prepared") as? com.jay.fxi.ui.graph.PreparedGraph, "actual new visible K2 live geometry")
            } else p.need(rest, "actual new hidden K2 REST fallback geometry")
            assertSame(g.c("current public chart uses the selected actual K2 preparation"), next, current.chart?.prepared)
            val prepared = if (visibility == "visible") cached.second else cached.first
            p.yes("fresh K2 geometry is positive", KRX in next.bySeries)
            p.eq("public KRX graph value is identical across actual epoch rotation", prepared.bySeries[KRX], next.bySeries[KRX])
            assertNotSame(g.c("same public value cannot reuse old epoch's cached prepared object"), prepared, next)
            p.yes("fresh GENERAL geometry remains available", GENERAL in next.bySeries)
        }
    }

    @Test fun b27_selectedPayloadRemovalDiffersFromRelease() = s63b2Test("B27", "selected", "released", "mixed-http", "write-before", "write-after", "seed-before", "seed-after") { p, col ->
        if (col.startsWith("write-")) {
            selectedWriteRemoval(p, if (col == "write-before") "preparing" else "ready")
            return@s63b2Test
        }
        if (col.startsWith("seed-")) {
            selectedSeedRemoval(p, col == "seed-before")
            return@s63b2Test
        }
        val g = p.g; g.start(); val old = g.adopt(); val original = old.krxGraphStamp!!; g.general()
        val coordinator = g.readyCoordinator()
        coordinator.onActivated(GraphKey("usd", GraphPeriod.ONE_DAY)); runCurrent()
        val call = p.need(g.sent.lastOrNull { it.key.period == GraphPeriod.ONE_DAY }, "actual original producer started")
        p.yes("producer live and admitted before completion", call.admitted && !call.cancelled && !call.returned)
        @Suppress("UNCHECKED_CAST") val registrations = field(coordinator, "tabRequests") as Map<GraphKey, Any>
        val registration = p.need(registrations[call.key], "registered original producer")
        @Suppress("UNCHECKED_CAST") val requests = field(registration, "recoveryRequests") as List<GraphRecoveryRequest>
        p.eq("original recovery owns KRX and GENERAL", setOf(g.krxKey, g.generalKey), requests.map { it.seriesKey }.toSet())
        val request = p.need(requests.singleOrNull { it.seriesKey == g.krxKey }, "registered KRX payload")
        p.eq("payload owns original stamp", original, request.krxGraphStamp)
        val applies = g.applyReads; val http = g.sent.size
        g.CompletionBarrier(coordinator).use { barrier ->
            call.response.complete(S6KrxGraphRig.ok(S6KrxGraphRig.tabDto(call.key))); runCurrent()
            p.yes("real producer returned normally before cutoff", call.returned && !call.cancelled)
            p.eq("actual FIFO completion enqueued and withheld", 1, barrier.enqueued)
            p.eq("no early REC consumption", applies, g.applyReads)
            val plan = p.cutoff(original)
            if (col == "selected") {
                p.yes("selected purge removes registered recovery payload", coordinator.purgeKrxRecoveryCaptures(plan).removed)
                @Suppress("UNCHECKED_CAST") val after = field(registration, "recoveryRequests") as List<GraphRecoveryRequest>
                p.yes("selected KRX payload absent before completion release", after.none { it.krxGraphStamp == original })
            } else if (col == "released") {
                var releasedWindows = 0
                g.onApplyRead = {
                    var held = 0
                    coordinator.purgeRecoveryCaptures { held++; false }
                    p.eq("completion released registration before original REC entry", 0, held)
                    releasedWindows++
                }
                barrier.release()
                p.eq("each original request really consumed after internal release", requests.size, releasedWindows)
            }
            if (col != "released") barrier.release()
            p.eq("only selected purge suppresses original KRX dispatch", applies + requests.size - if (col == "selected") 1 else 0, g.applyReads)
            p.yes("GENERAL response still applies", g.raw.series[g.generalKey]?.lastAppliedVersion != null)
            p.eq("no extra HTTP for cleanup", http, g.sent.size)

        }
    }

    private suspend fun selectedWriteRemoval(p: S6KrxGraphMemoryPurgeRig, stage: String) {
        val g = p.g; g.start()
        val window = p.writeWindow(stage, failWithdrawals = false); window.start(primeBudget = true)
        val before = g.snapshot; val calls = g.sent.size
        val budget = p.budgetImage(window.assembly.coordinator)
        p.yes("write window positively retains a spent shared budget", budget.isNotEmpty() && budget.values.any { (it[1] as Int) > 0 })
        val stamp = p.need(window.captured.krxGraphStamp, "actual selected write original")
        val cutoff = window.assembly.krxGraphIngress.topicUnavailable(stamp.scope)
        val plan = p.need(KrxGraphPurgePlan.forCutoff(cutoff), "selected write cutoff plan")
        p.yes("selected runtime purge removes write ownership", window.assembly.coordinator.purgeKrxMemory(plan).removed)
        p.yes("selected write no longer owns KRX", !(field(window.task(), "krxOwned") as java.util.concurrent.atomic.AtomicBoolean).get())
        p.eq("normal selected purge delegates one KRX withdrawal", listOf(window.ticket), window.delegatedWithdrawals)
        p.eq("selected purge preserves spent shared budget identity and all counters", budget, p.budgetImage(window.assembly.coordinator))
        p.eq("selected purge preserves PAC control and epochs", before, g.snapshot)
        p.eq("whole GENERAL ticket remains live", emptyList<GraphV2WriteTicket>(), window.cancelled)
        p.eq("selected write is still held before actual delegate", 0, window.delegateWrites)
        window.hold.complete(Unit); g.test.runCurrent()
        p.eq("GENERAL write completes once after selected cleanup", 1, window.reports.size)
        p.eq("selected cleanup preserves GENERAL write", GraphV2ComponentWriteOutcome.Replaced, window.reports.single().general)
        p.yes("selected KRX reservation cannot write", window.reports.single().krx is GraphV2ComponentWriteOutcome.Skipped)
        val oldKey = p.need(window.components.krx, "selected original K1 key").key
        p.yes("selected old K1 file absent", window.real.readKrx(oldKey, null) { true } !is GraphV2DiskRead.Found)
        p.eq("cleanup adds no HTTP", calls, g.sent.size)
    }

    private suspend fun selectedSeedRemoval(p: S6KrxGraphMemoryPurgeRig, beforeRead: Boolean) {
        val g = p.g; g.start(); val old = g.adopt(); g.general()
        val coordinator = g.readyCoordinator(); val key = GraphKey("usd", GraphPeriod.ONE_DAY)
        p.primeSpentBudget(coordinator)
        val budget = p.budgetImage(coordinator)
        val admitted = GraphV2Domain.admit(S6KrxGraphRig.tabDto(key), key.tab, key.period, g.catalog)
        p.yes("seed server response is valid", admitted is GraphTabAdmission.Accepted)
        val split = splitGraphV2ServerTab((admitted as GraphTabAdmission.Accepted).tab,
            GraphV2GeneralKey(g.dataScope.uid, g.dataScope.userAccessEpoch, key.tab, key.period.code),
            old.krxGraphStamp!!.scope.use.krxCapabilityEpoch, "b27-original-seed", g.catalog)
        p.yes("seed contains actual GENERAL and K1 components", split is GraphV2Validation.Valid && split.value.krx != null)
        g.disk.seed = (split as GraphV2Validation.Valid).value
        val hold = CompletableDeferred<Unit>(); g.disk.seedGate = hold
        val reads = g.disk.readCount
        coordinator.onActivated(key); coordinator.onRefreshRequested(force = true); g.test.runCurrent()
        p.yes("actual seed producer entered GENERAL read before cutoff", g.disk.readCount > reads && !hold.isCompleted)
        p.yes("actual seed registration is positive", field(coordinator, "seedRegistration") != null)
        val call = p.need(g.sent.lastOrNull { it.key == key && !it.response.isCompleted }, "parallel original HTTP producer remains in flight")
        if (!beforeRead) {
            hold.complete(Unit); g.test.runCurrent()
            val slot = p.need((field(coordinator, "protectedSlots") as Map<*, *>)[key], "actual adopted seed slot before cutoff")
            p.yes("seed K1 half is positive before purge", (field(slot, "components") as GraphV2DiskComponents).krx != null)
        }
        val calls = g.sent.size
        val plan = p.cutoff(old.krxGraphStamp!!)
        p.yes("selected purge reaches pending/adopted seed ownership", coordinator.purgeKrxMemory(plan).removed)
        p.eq("selected seed cleanup preserves positively spent shared budget", budget, p.budgetImage(coordinator))
        if (beforeRead) hold.complete(Unit)
        g.test.runCurrent()
        val slot = p.need((field(coordinator, "protectedSlots") as Map<*, *>)[key], "GENERAL seed survives selected purge")
        val remaining = field(slot, "components") as GraphV2DiskComponents
        p.eq("selected seed KRX half is physically absent", null, remaining.krx)
        p.eq("GENERAL seed retains original response identity", "b27-original-seed", remaining.general.responseId)
        p.yes("GENERAL seed remains exposed", coordinator.protectedEntry(key)?.tab?.graph?.series?.any { it.seriesId == GENERAL } == true)
        p.yes("parallel original HTTP was not cancelled", !call.cancelled && !call.returned)
        call.response.complete(S6KrxGraphRig.ok(S6KrxGraphRig.tabDto(key))); g.test.runCurrent()
        p.yes("actual original HTTP completes normally after seed cleanup", call.returned && !call.cancelled)
        val final = p.need(coordinator.protectedEntry(key), "GENERAL HTTP still exposed after original completion")
        p.yes("GENERAL HTTP remains while original K1 cannot reappear", final.tab.graph.series.any { it.seriesId == GENERAL } &&
            final.tab.graph.series.none { it.seriesId == KRX })
        p.eq("seed cleanup adds no HTTP", calls, g.sent.size)
    }

    @Test fun e01_authorityEndedOriginsDriveSelectivePurge() = s63b2Test("E01", "FULL", "FAILED", "CLOSED",
        "FULL-CAP", "FAILED-CAP", "CLOSED-CAP", "FULL-USER", "FAILED-USER", "CLOSED-USER",
        "FULL-outside", "FAILED-outside", "CLOSED-outside") { p, col ->
        val g = p.g; g.r.httpFloor = g.r.clock.nowMillis() + 1_000_000; p.observeKnownSources()
        g.start(); val old = g.adopt(); val original = p.knownOriginal(old); val general = g.general()
        val mode = TopicGraphOffer.valueOf(col.substringBefore('-')); val sink = p.installLossMode(mode, general)
        g.session.target = TopicGraphSink { input ->
            if (input is TopicGraphInput.Continuity && input.kind == TopicGraphEventKind.AUTHORITY_ENDED) sink.tryOffer(input)
            else TopicGraphOffer.ENQUEUED
        }
        val start = g.session.inputs.size; val issued = g.session.issued.size
        // Real grant-delivery boundary; no PAC capability end or synthetic continuity invocation.
        g.session.cleanup.session.setAccess(false, g.owner, TopicGrantOrigin.NewContext); g.r.drain()
        val ending = p.need(g.session.inputs.drop(start).filterIsInstance<TopicGraphInput.Continuity>()
            .singleOrNull { it.kind == TopicGraphEventKind.AUTHORITY_ENDED }, "real AUTHORITY_ENDED producer reached")
        p.eq("ending preserves original source", original, ending.authority.krxGraphStamp)
        p.eq("ending creates no new source", issued, g.session.issued.size)
        p.recordedFact(ending, mode)
        p.yes("fact name alone does not revoke capability", g.snapshot.admitsKrxUse(original.scope.use))
        p.eq("fact without separate evidence preserves loss", KrxGraphPurgeOutcome(false, false), p.ledger.purgeKrxMemory(p.plan()))
        p.yes("selected original still held before explicit proof", original in p.need(p.ledger.view, "loss still held").krxLost.values.flatten())
        if (col.endsWith("-outside")) {
            val frozen = p.cutoff(original)
            val connections = g.r.wires.size; val sent = g.r.sends.size
            g.session.cleanup.session.setAccess(true, g.owner, TopicGrantOrigin.NewContext)
            g.r.until(g.c("actual next authority opens a current connection"), 2_600) { g.r.wires.size == connections + 1 }
            g.r.open()
            g.r.until(g.c("actual next authority KRX command is SENT"), 1_000) { g.r.sends.drop(sent).any {
                it.accepted && TopicCatalogue.KRX_FUTURES in it.request.topics
            } }
            g.r.ack(g.session.cleanup.lastKrxRequest())
            val next = g.session.ws(1777.0, AT + 1.seconds)
            val k2 = p.knownOriginal(next)
            p.yes("actual future K2 was issued after frozen cutoff", k2.issueSequence > original.issueSequence)
            if (mode == TopicGraphOffer.FAILED) g.failNextKrxLedgerWrite(sink)
            val from = g.session.inputs.size
            g.session.cleanup.session.setAccess(false, g.owner, TopicGrantOrigin.NewContext); g.r.drain()
            val later = p.need(g.session.inputs.drop(from).filterIsInstance<TopicGraphInput.Continuity>().singleOrNull {
                it.kind == TopicGraphEventKind.AUTHORITY_ENDED }, "actual later K2 AUTHORITY_ENDED producer")
            p.eq("actual K2 ending carries its own known original", k2, later.authority.krxGraphStamp)
            p.recordedFact(later, mode)
            // boundary-input: a foreign owner's fact cannot be produced by this admitted owner; preserve its full provenance at the shared target.
            val owner = k2.scope.owner.copy(identity = k2.scope.owner.identity.copy(uid = "foreign"))
            val foreign = k2.copy(scope = k2.scope.copy(owner = owner))
            val outsider = TopicGraphInput.Continuity(maxOf(later.sequence, p.need(p.ledger.view, "current loss high-water").lastSequence) + 1, later.kind, later.reason, later.topics, later.paths,
                TopicGraphAuthority(later.authority.sessionKey, owner, later.authority.grantEpoch, later.authority.lifetime, foreign),
                later.connectionGeneration, later.occurredAtEpochMillis, later.payload)
            p.yes("boundary-input: foreign ending is physically held before selected cleanup", p.ledger.record(outsider, mode, 123))
            if (mode != TopicGraphOffer.CLOSED) {
                if (mode == TopicGraphOffer.FAILED) g.failNextKrxLedgerWrite(sink)
                p.eq("boundary-input: foreign ending reaches actual holding sink", mode, sink.tryOffer(outsider))
            }
            val before = p.need(p.ledger.view, "selected and outside ending facts coexist")
            p.yes("all three originals are positive before old plan", setOf(original, k2, foreign).all { it in before.krxLost.values.flatten() })
            purgeEnding(p, original, sink, frozenPlan = frozen)
            val after = p.need(p.ledger.view, "actual K2 and foreign GENERAL mixed facts survive")
            p.yes("old proof preserves actual K2 and boundary foreign original", setOf(k2, foreign).all { it in after.krxLost.values.flatten() })
            if (mode != TopicGraphOffer.CLOSED) p.yes("raw sink also preserves actual K2 and foreign original", setOf(k2, foreign).all { it in p.sinkLoss(sink).values.flatten() })
        } else if (col.endsWith("-CAP") || col.endsWith("-USER")) {
            val before = p.need(p.ledger.view, "actual authority-ending loss positive before explicit end")
            if (col.endsWith("-CAP")) g.session.capEnd() else {
                g.r.h.source.next = { EntitlementsOutcome.StableInactive(false) }
                g.r.pac.refresh(RefreshIntent.FORCE_PREMIUM); g.r.drain()
                p.yes("actual USER end is positive", g.snapshot.lastUserEnd != null && !g.snapshot.facts.userAllowed)
            }
            val report = p.need(g.session.cleanup.session.krxGraphLossPurgeState, "real TSC loop reports explicit end cleanup")
            p.yes("actual TSC end removes selected loss without pretending whole completion", report.failure == null &&
                report.outcome?.let { it.removed && !it.unresolved } == true)
            val after = p.need(p.ledger.view, "GENERAL authority-ending metadata survives explicit end")
            p.yes("explicit end removes selected raw original", original !in after.krxLost.values.flatten())
            p.eq("GENERAL mixed batch still counted once", before.count, after.count)
            p.eq("original sequence/time pair retained", listOf(before.firstSequence, before.lastSequence,
                before.firstOccurredAtEpochMillis, before.lastOccurredAtEpochMillis), listOf(after.firstSequence,
                after.lastSequence, after.firstOccurredAtEpochMillis, after.lastOccurredAtEpochMillis))
            p.yes("GENERAL topic facts survive actual TSC end", after.topics.any { !isKrxGraphTopic(it) })
            val plan = p.plan()
            sink.purgeKrxMemory(plan)
            p.yes("the separate sink target also drops selected original", original !in p.sinkLoss(sink).values.flatten())
        } else purgeEnding(p, original, sink)
    }

    @Test fun e02_handoverEndedOriginsDriveSelectivePurge() = s63b2Test("E02", "FULL", "FAILED", "CLOSED") { p, col ->
        val g = p.g; g.r.httpFloor = g.r.clock.nowMillis() + 1_000_000; p.observeKnownSources()
        g.start(); val old = g.adopt(); val general = g.general(); val original = p.knownOriginal(old)
        val mode = TopicGraphOffer.valueOf(col); val sink = p.installLossMode(mode, general)
        g.session.target = TopicGraphSink { input ->
            if (input is TopicGraphInput.Continuity && input.kind == TopicGraphEventKind.HANDOVER_ENDED) sink.tryOffer(input)
            else TopicGraphOffer.ENQUEUED
        }
        val start = g.session.inputs.size; val issued = g.session.issued.size
        g.session.cleanup.session.stop(); g.r.drain()
        val ending = p.need(g.session.inputs.drop(start).filterIsInstance<TopicGraphInput.Continuity>()
            .singleOrNull { it.kind == TopicGraphEventKind.HANDOVER_ENDED }, "real HANDOVER_ENDED producer reached")
        p.eq("original session, owner, epoch and lifetime retained", listOf(old.attribution.sessionKey, old.attribution.owner,
            old.attribution.grantEpoch, old.attribution.lifetime), listOf(ending.authority.sessionKey, ending.authority.owner,
            ending.authority.grantEpoch, ending.authority.lifetime))
        p.eq("handover uses known source without new capture", original, ending.authority.krxGraphStamp)
        p.eq("handover does not recapture", issued, g.session.issued.size)
        p.recordedFact(ending, mode)
        p.eq("stop itself is no capability purge", KrxGraphPurgeOutcome(false, false), p.ledger.purgeKrxMemory(p.plan()))
        val oldLedger = p.ledger
        val frozen = p.cutoff(original)
        val nextAssembly = g.assembly()
        p.startRuntime(nextAssembly, TopicGraphSink { input ->
            if (input is TopicGraphInput.Continuity && input.kind == TopicGraphEventKind.HANDOVER_ENDED) sink.tryOffer(input)
            else TopicGraphOffer.ENQUEUED
        }, g.session.ingress)
        g.r.ack(g.session.cleanup.lastKrxRequest())
        if (mode == TopicGraphOffer.FAILED) g.failNextKrxLedgerWrite(sink)
        val nextAck = p.need(g.session.inputs.filterIsInstance<TopicGraphInput.Continuity>().lastOrNull {
            it.kind == TopicGraphEventKind.ACK_ACTIVE_SET_CHANGED && it.authority.krxGraphStamp != null }, "actual later SESSION ACK")
        val k2 = p.knownOriginal(nextAck.authority, nextAck.connectionGeneration)
        val nextFrom = g.session.inputs.size
        g.session.cleanup.session.stop(); g.r.drain()
        val nextEnding = p.need(g.session.inputs.drop(nextFrom).filterIsInstance<TopicGraphInput.Continuity>().singleOrNull {
            it.kind == TopicGraphEventKind.HANDOVER_ENDED }, "actual later K2 handover fact")
        p.eq("actual later K2 handover carries the pre-fact known maximum", k2, nextEnding.authority.krxGraphStamp)
        p.yes("later K2 source is beyond old frozen cutoff", k2.issueSequence > original.issueSequence)
        p.recordedFact(nextEnding, mode)
        val nextLedger = p.ledger; val nextLoss = p.need(nextLedger.view, "later K2 loss positively held before old cleanup")
        p.eq("old proof cannot delete later K2 loss", KrxGraphPurgeOutcome(false, false), nextLedger.purgeKrxMemory(frozen))
        assertSame(g.c("old proof preserves current K2 immutable loss view"), nextLoss, nextLedger.view)
        purgeEnding(p, original, sink, oldLedger, frozen)
        p.yes("later K2 fact retains its original after old cleanup", nextEnding.authority.krxGraphStamp == k2 &&
            k2 in p.need(nextLedger.view, "K2 loss survives old cleanup").krxLost.values.flatten())
        if (mode != TopicGraphOffer.CLOSED) p.yes("common sink retains later K2 loss while selected K1 is removed", k2 in p.sinkLoss(sink).values.flatten())
    }

    @Test fun e03_priceLessEndingStillPurgesOriginalLoss() = s63b2Test("E03",
        "ACK-FULL", "ACK-FAILED", "ACK-CLOSED", "REST-degraded-FULL", "REST-degraded-FAILED", "REST-degraded-CLOSED",
        "REST-dormant-FULL", "REST-dormant-FAILED", "REST-dormant-CLOSED", "unknown-FULL", "unknown-FAILED", "unknown-CLOSED", "automatic-ACK", "automatic-REST-degraded", "automatic-REST-dormant") { p, col ->
        if (col.startsWith("automatic-")) {
            priceLessAutomaticAssembly(p, col)
            return@s63b2Test
        }
        val g = p.g
        if (col.startsWith("unknown")) {
            g.r.httpFloor = g.r.clock.nowMillis() + 1_000_000
            g.r.approve(); g.r.start() // Actual connection deliberately unopened: no accepted KRX command or REST source.
            g.owner = p.need(g.r.issuer.topicGrantResult().fence, "actual GENERAL owner for unknown-source termination")
            p.eq("unknown source has never been issued", 0, g.session.issued.size)
            val mode = TopicGraphOffer.valueOf(col.substringAfterLast('-'))
            val sink = p.installLossMode(mode, g.general())
            if (mode == TopicGraphOffer.FAILED) {
                @Suppress("UNCHECKED_CAST") val map = field(sink, "ledger") as MutableMap<GraphDataScope, MutableMap<String, Long>>
                var armed = true
                setField(sink, "ledger", object : MutableMap<GraphDataScope, MutableMap<String, Long>> by map {
                    override fun get(key: GraphDataScope): MutableMap<String, Long>? {
                        if (armed) { armed = false; throw IllegalArgumentException("boundary-input: unknown-source GENERAL ledger fault") }
                        return map[key]
                    }
                })
            }
            g.session.target = TopicGraphSink { input ->
                if (input is TopicGraphInput.Continuity && input.kind == TopicGraphEventKind.HANDOVER_ENDED) sink.tryOffer(input)
                else TopicGraphOffer.ENQUEUED
            }
            val from = g.session.inputs.size
            g.session.cleanup.session.stop(); g.r.drain()
            val fact = p.need(g.session.inputs.drop(from).filterIsInstance<TopicGraphInput.Continuity>().singleOrNull {
                it.kind == TopicGraphEventKind.HANDOVER_ENDED }, "actual unknown-source termination")
            p.eq("termination does not manufacture an original", null, fact.authority.krxGraphStamp)
            p.eq("unknown termination really hits requested sink mode", mode, g.session.offers.single { it.first === fact }.second)
            val loss = p.need(p.ledger.view, "unknown termination positively retained")
            p.yes("unknown KRX topic remains in raw loss window", TopicCatalogue.KRX_FUTURES in loss.topics)
            g.session.capEnd()
            p.eq("explicit end cannot guess missing original", KrxGraphPurgeOutcome(false, true), p.ledger.purgeKrxMemory(p.plan()))
            assertSame(g.c("unknown raw evidence survives unresolved purge"), loss, p.ledger.view)
            return@s63b2Test
        }
        p.observeKnownSources()
        val original: KrxGraphStamp
        if (col.startsWith("ACK")) {
            g.r.httpFloor = g.r.clock.nowMillis() + 1_000_000
            g.start(ack = false)
            g.session.cleanup.ackKrxLease(g.session.cleanup.lastKrxRequest(), "e03-K1", krxSeconds = 1)
            val ack = p.need(g.session.inputs.filterIsInstance<TopicGraphInput.Continuity>().lastOrNull {
                it.kind == TopicGraphEventKind.ACK_ACTIVE_SET_CHANGED && it.authority.krxGraphStamp != null
            }, "real price-less ACK original")
            p.yes("ACK active before actual lease expiry", field(g.session.cleanup.live, "krxActive") != null)
            g.r.advance(1_001)
            p.eq("lease expiry actually clears KRX active pointer", null, field(g.session.cleanup.live, "krxActive"))
            original = p.knownOriginal(ack.authority, null)
        } else {
            g.start(ack = false)
            val call = g.session.cleanup.issue(); val from = g.session.inputs.size
            val degraded = "degraded" in col
            g.session.cleanup.f.reply(call, if (degraded) KrxA1bFixture.Reply(404, """{"error":"topic_unavailable"}""")
                else KrxA1bFixture.Reply(404, """{"error":"topics_disabled"}"""))
            p.yes("real REST producer returns normally", call.outcome != null && !call.cancelled)
            p.eq("exact price-less outcome", if (degraded) TopicSnapshotOutcome.Degraded else TopicSnapshotOutcome.Dormant, call.outcome)
            val fact = p.need(g.session.inputs.drop(from).filterIsInstance<TopicGraphInput.Continuity>().singleOrNull {
                it.reason == TopicGraphEventReason.REST_UNDELIVERED && TopicCatalogue.KRX_FUTURES in it.topics
            }, "real price-less REST continuity")
            p.need(fact.authority.krxGraphStamp, "REST issue still owns original without a price")
            original = p.knownOriginal(fact.authority, null)
        }
        p.yes("no KRX observation was produced", g.session.inputs.filterIsInstance<TopicGraphInput.Observations>().none { it.topic == TopicCatalogue.KRX_FUTURES })
        p.yes("no adopted KRX price series", g.krxKey !in g.raw.series)
        p.yes("known source actually issued", g.session.issued.any { it.second === original })
        val mode = TopicGraphOffer.valueOf(col.substringAfterLast('-')); val sink = p.installLossMode(mode, g.general())
        g.session.target = TopicGraphSink { input ->
            if (input is TopicGraphInput.Continuity && input.kind == TopicGraphEventKind.HANDOVER_ENDED) sink.tryOffer(input)
            else TopicGraphOffer.ENQUEUED
        }
        val from = g.session.inputs.size
        g.session.cleanup.session.stop(); g.r.drain()
        val ending = p.need(g.session.inputs.drop(from).filterIsInstance<TopicGraphInput.Continuity>().singleOrNull {
            it.kind == TopicGraphEventKind.HANDOVER_ENDED
        }, "price-less actual handover producer")
        p.eq("ending selects known original independently of prices/active", original, ending.authority.krxGraphStamp)
        p.recordedFact(ending, mode)
        purgeEnding(p, original, sink)
    }

    private suspend fun priceLessAutomaticAssembly(p: S6KrxGraphMemoryPurgeRig, col: String) {
        val g = p.g; p.observeKnownSources(); g.r.approve()
        g.owner = p.need(g.r.issuer.topicGrantResult().fence, "actual factory GENERAL owner")
        val assembly = g.assembly(); assembly.start()
        val general = g.general(); val full = p.batch(general, 512, 90_060)
        p.startRuntime(assembly, TopicGraphSink { input ->
            if (input is TopicGraphInput.Continuity && input.kind == TopicGraphEventKind.HANDOVER_ENDED) {
                p.eq("actual handover producer opens FULL window before its offer", TopicGraphOffer.ENQUEUED, assembly.sink.tryOffer(full))
                p.eq("actual handover is inside 512-unit window", 512, p.units(assembly.sink))
            }
            assembly.sink.tryOffer(input)
        })
        val original: KrxGraphStamp
        if (col == "automatic-ACK") {
            g.session.cleanup.ackKrxLease(g.session.cleanup.lastKrxRequest(), "e03-auto-K1", krxSeconds = 1)
            val ack = p.need(g.session.inputs.filterIsInstance<TopicGraphInput.Continuity>().lastOrNull {
                it.kind == TopicGraphEventKind.ACK_ACTIVE_SET_CHANGED && it.authority.krxGraphStamp != null }, "actual price-less factory ACK")
            p.yes("factory ACK really installs active KRX", field(g.session.cleanup.live, "krxActive") != null)
            g.r.advance(1_001)
            p.eq("factory ACK lease really expires without prices", null, field(g.session.cleanup.live, "krxActive"))
            original = p.knownOriginal(ack.authority, null)
        } else {
            val call = g.session.cleanup.issue(); val from = g.session.inputs.size
            val degraded = col.endsWith("degraded")
            g.session.cleanup.f.reply(call, if (degraded) KrxA1bFixture.Reply(404, """{"error":"topic_unavailable"}""")
                else KrxA1bFixture.Reply(404, """{"error":"topics_disabled"}"""))
            p.yes("actual factory REST completes normally", call.outcome != null && !call.cancelled)
            p.eq("actual factory price-less REST result", if (degraded) TopicSnapshotOutcome.Degraded else TopicSnapshotOutcome.Dormant, call.outcome)
            val fact = p.need(g.session.inputs.drop(from).filterIsInstance<TopicGraphInput.Continuity>().firstOrNull {
                it.reason == TopicGraphEventReason.REST_UNDELIVERED && it.authority.krxGraphStamp != null }, "actual price-less factory REST fact")
            original = p.knownOriginal(fact.authority, null)
        }
        p.yes("factory has produced no KRX price", g.session.inputs.filterIsInstance<TopicGraphInput.Observations>().none { it.topic == TopicCatalogue.KRX_FUTURES })
        p.yes("factory REC has no KRX price inventory", assembly.recorder.state.value.series.keys.none { it.seriesId == KRX })
        val from = g.session.inputs.size
        g.session.cleanup.session.stop(); g.r.drain()
        val ending = p.need(g.session.inputs.drop(from).filterIsInstance<TopicGraphInput.Continuity>().singleOrNull {
            it.kind == TopicGraphEventKind.HANDOVER_ENDED }, "actual factory handover ending without prices")
        p.eq("factory ending retains known price-less source", original, ending.authority.krxGraphStamp)
        p.recordedFact(ending, TopicGraphOffer.FULL)
        p.yes("actual sink drain transfers price-less loss into REC", original in assembly.recorder.state.value.pending.krxLost.values.flatten() ||
            original in assembly.recorder.state.value.krxUntransferred.values.flatten())
        val generalPending = assembly.recorder.state.value.pending.inputs.toList()
        p.yes("GENERAL 512-unit holding survives actual drain", full in generalPending)
        g.session.capEnd(); g.test.runCurrent()
        val state = p.need(assembly.krxMemoryPurgeState, "automatic Main evaluates price-less actual factory inventory")
        p.yes("automatic Main removes price-less original without a KRX price series", state.failure == null && state.outcome?.removed == true)
        p.yes("automatic Main physically removes price-less pending/untransferred loss", original !in assembly.recorder.state.value.pending.krxLost.values.flatten() &&
            original !in assembly.recorder.state.value.krxUntransferred.values.flatten())
        p.eq("GENERAL pending holding survives physical KRX cleanup", generalPending, assembly.recorder.state.value.pending.inputs)
        // This TSC has stopped; separately exercise its owned shared-ledger target, without claiming a loop report.
        val plan = KrxGraphPurgePlan.forSnapshot(g.snapshot, KrxGraphCutoffSnapshot(0, emptyMap()))
        p.yes("stopped SESSION target still has original before explicit target purge", original in p.need(p.ledger.view, "stopped SESSION retained loss").krxLost.values.flatten())
        p.yes("stopped SESSION shared target also removes original", p.ledger.purgeKrxMemory(plan).removed)
    }

    @Test fun e04_wsInterruptionKeepsConnectionOriginalForPurge() = s63b2Test("E04",
        "WS_ENDED", "known-only", "active-known-max", "LEASE_EXPIRED", "AuthFailure",
        "boundary-DELIVERY_SUSPECT", "boundary-DEGRADED", "boundary-LEASE_EXPIRED", "foreign-connection-late-K2") { p, col ->
        if (col == "foreign-connection-late-K2") {
            foreignConnectionAndLateOriginal(p)
            return@s63b2Test
        }
        val g = p.g; g.r.httpFloor = g.r.clock.nowMillis() + 1_000_000
        p.observeKnownSources()
        if (col == "known-only") {
            g.start(ack = false)
            g.session.cleanup.ackKrxLease(g.session.cleanup.lastKrxRequest(), "e04-known-K1", krxSeconds = 1)
        } else g.start(leaseSeconds = if (col == "LEASE_EXPIRED") 1 else 600)
        val old = g.adopt(); var original = p.knownOriginal(old); val general = g.general()
        if (col == "active-known-max") {
            g.r.httpFloor = 0
            g.session.rest(1556.0, AT + 1.seconds)
            original = p.knownOriginal(old)
            p.yes("known REST source has greater sequence than active WS", original.issueSequence > old.krxGraphStamp!!.issueSequence)
        }
        if (col == "known-only") {
            g.r.advance(1_001)
            p.eq("known-only has no active source", null, field(g.session.cleanup.live, "krxActive"))
            original = p.knownOriginal(old)
        }
        val sink = p.installLossMode(TopicGraphOffer.FULL, general)
        val lossHistoryFrom = g.session.offers.size
        if (col == "AuthFailure") p.eq("SESSION loss is empty before recording the auth-failure window", null, p.ledger.view)
        g.session.target = TopicGraphSink { input ->
            if (input is TopicGraphInput.Continuity) {
                val captures = g.session.issued.size
                sink.tryOffer(input).also {
                    if (col == "AuthFailure") p.eq("fact handoff and loss projection issue no source", captures, g.session.issued.size)
                }
            } else TopicGraphOffer.ENQUEUED
        }
        val from = g.session.inputs.size; var issues = g.session.issued.size
        val laterRenewalSources = mutableListOf<KrxGraphStamp>()
        if (col.startsWith("boundary")) {
            // boundary-input: these metadata alternatives have no demonstrated typed KRX producer.
            // This exercises shared projection only; report.md records the exact producer inventory and limits.
            val reason = when {
                col.endsWith("SUSPECT") -> TopicGraphEventReason.DELIVERY_SUSPECT
                col.endsWith("DEGRADED") -> TopicGraphEventReason.DELIVERY_DEGRADED
                else -> TopicGraphEventReason.LEASE_EXPIRED
            }
            val fact = TopicGraphInput.Continuity(90_002, TopicGraphEventKind.DELIVERY_INTERRUPTED, reason,
                setOf(old.topic, general.topic), setOf(TopicGraphPath.WS), TopicGraphAuthority(old.attribution.sessionKey,
                    old.attribution.owner, old.attribution.grantEpoch, old.attribution.lifetime, original), old.connectionGeneration, 123)
            p.yes("boundary-input: metadata recorded as a whole lost batch", p.ledger.record(fact, TopicGraphOffer.FULL, 123))
            p.eq("boundary-input: sink really FULL", TopicGraphOffer.FULL, sink.tryOffer(fact))
        } else {
            when (col) {
                "AuthFailure" -> {
                    val connection = g.session.cleanup.live
                    val beforeRenewal = g.session.issued.size
                    val request = g.session.renewal()
                    p.yes("renewal prepared a new source on the same current connection", connection === g.session.cleanup.live &&
                        g.session.issued.size > beforeRenewal)
                    original = p.knownOriginal(old)
                    val preceding = p.need(g.r.commands.singleOrNull { record -> record.prepared.any { it.requestId == request.requestId } },
                        "actual renewal command before invalid_token")
                    val prepared = p.need(p.knownWsPrepares.singleOrNull {
                        it.requestId == request.requestId && it.commandId == preceding.id &&
                            it.connectionGeneration == old.connectionGeneration && it.sessionKey === old.attribution.sessionKey
                    }, "actual validated renewal prepare before the auth-failure fact")
                    p.yes("validated renewal prepare is newer than the earlier active WS source",
                        prepared.stamp.issueSequence > old.krxGraphStamp!!.issueSequence)
                    p.eq("validated pre-fact renewal retains the original grant epoch", old.attribution.grantEpoch, prepared.grantEpoch)
                    p.yes("unacknowledged renewal is included in the pre-fact known-source maximum",
                        original.issueSequence >= prepared.stamp.issueSequence)
                    p.yes("renewal prepare has not been ACKed before the auth-failure window", g.session.inputs
                        .filterIsInstance<TopicGraphInput.Continuity>().none {
                            it.kind == TopicGraphEventKind.ACK_ACTIVE_SET_CHANGED && it.authority.krxGraphStamp == prepared.stamp })
                    // General and typed KRX leases both schedule RenewalDue at the same deadline.
                    p.eq("another ordinary renewal is already deferred before the auth failure", true, field(connection, "renewalDeferred"))
                    issues = g.session.issued.size
                    val previousObserver = g.r.onPrepared
                    g.r.onPrepared = { record, attempt ->
                        previousObserver?.invoke(record, attempt)
                        if (attempt.pendingAckTopics.any(TopicCatalogue::carriesKrx)) {
                            val pending = p.need(field(connection, "krxPending"), "actual deferred renewal pending capture")
                            val capture = p.need(field(pending, "capture"), "actual pending capture after prepare")
                            val source = p.need(field(capture, "graphStamp") as? KrxGraphStamp, "actual deferred renewal source")
                            p.eq("observed source belongs to this original prepare", attempt.requestId, field(capture, "requestId"))
                            p.yes("control-lane release starts a different ordinary renewal command", record.id != preceding.id &&
                                record.purpose == TopicCommandPurpose.LEASE_RENEWAL && attempt.source == KrxAttemptSource.RENEWAL)
                            p.eq("deferred renewal stays on the original connection", old.connectionGeneration, attempt.connectionGeneration)
                            laterRenewalSources += source
                        }
                    }
                    try {
                        g.session.deliver("""{"type":"subscription_error","request_id":"${request.requestId}","error":"invalid_token"}""")
                    } finally { g.r.onPrepared = previousObserver }
                    p.yes("existing deferred renewal really prepared a new source", laterRenewalSources.isNotEmpty())
                    p.yes("later renewal prepares are beyond the frozen pre-fact known maximum",
                        laterRenewalSources.all { it.issueSequence > original.issueSequence })
                    p.eq("every later issued source is accounted for by the ordinary renewal prepare", laterRenewalSources,
                        g.session.issued.drop(issues).mapNotNull { it.second })
                }
                "LEASE_EXPIRED" -> {
                    original = p.knownOriginal(old)
                    g.r.advance(1_001)
                }
                else -> { g.r.wire.drop(); g.r.drain() }
            }
            val expectedReason = if (col == "AuthFailure") TopicGraphEventReason.INVALID_TOKEN else TopicGraphEventReason.WS_ENDED
            val fact = p.need(g.session.inputs.drop(from).filterIsInstance<TopicGraphInput.Continuity>().firstOrNull {
                it.reason == expectedReason && TopicCatalogue.KRX_FUTURES in it.topics
            }, "actual WS interruption producer reaches KRX original (lease path ends WS)")
            p.eq("connection interruption retains maximum original source", original, fact.authority.krxGraphStamp)
            p.recordedFact(fact, TopicGraphOffer.FULL)
            if (col == "AuthFailure") p.yes("later deferred-renewal source never becomes the earlier auth-failure authority",
                laterRenewalSources.none { it == fact.authority.krxGraphStamp })
            else p.eq("interruption does not issue a replacement source", issues, g.session.issued.size)
        }
        val capturesBeforePurge = g.session.issued.size
        p.eq("interruption name is not explicit purge authority", KrxGraphPurgeOutcome(false, false), p.ledger.purgeKrxMemory(p.plan()))
        p.yes("original loss still present before explicit D1", original in p.need(p.ledger.view, "original loss").krxLost.values.flatten())
        val recordedLosses = if (col == "AuthFailure") g.session.offers.drop(lossHistoryFrom).filter {
            it.second in setOf(TopicGraphOffer.FULL, TopicGraphOffer.CLOSED, TopicGraphOffer.FAILED)
        }.map { (input, offer) ->
            p.eq("actual auth-failure window loses each continuity batch through FULL", TopicGraphOffer.FULL, offer)
            p.need(input as? TopicGraphInput.Continuity, "only actual continuity inputs enter this loss window")
        } else null
        purgeEnding(p, original, sink, recordedLosses = recordedLosses)
        p.eq("explicit plan purge and loss projection issue no source", capturesBeforePurge, g.session.issued.size)
    }

    private suspend fun foreignConnectionAndLateOriginal(p: S6KrxGraphMemoryPurgeRig) {
        val g = p.g; g.r.httpFloor = g.r.clock.nowMillis() + 1_000_000; p.observeKnownSources()
        g.start(); val old = g.adopt(); val k1 = p.knownOriginal(old); val general = g.general()
        val sink = p.installLossMode(TopicGraphOffer.FULL, general)
        g.session.target = TopicGraphSink { input ->
            when (input) {
                is TopicGraphInput.Continuity -> sink.tryOffer(input)
                is TopicGraphInput.Observations -> { g.recorder.observe(input); TopicGraphOffer.ENQUEUED }
            }
        }
        val foreignWire = g.r.wire; val from = g.session.inputs.size
        foreignWire.drop(); g.r.drain()
        val ended = p.need(g.session.inputs.drop(from).filterIsInstance<TopicGraphInput.Continuity>().firstOrNull {
            it.reason == TopicGraphEventReason.WS_ENDED && TopicCatalogue.KRX_FUTURES in it.topics
        }, "actual original connection WS_ENDED producer")
        p.eq("actual old connection interruption retains original", k1, ended.authority.krxGraphStamp)
        p.recordedFact(ended, TopicGraphOffer.FULL)
        val frozen = p.cutoff(k1)
        val sent = g.r.sends.size
        g.r.until(g.c("actual replacement connection differs from old socket"), 2_600) { g.r.wires.size >= 2 && g.r.wire !== foreignWire }
        g.r.open()
        g.r.until(g.c("actual replacement KRX command is SENT"), 1_000) { g.r.sends.drop(sent).any {
            it.accepted && TopicCatalogue.KRX_FUTURES in it.request.topics
        } }
        g.r.ack(g.session.cleanup.lastKrxRequest())
        val fresh = g.session.ws(1777.0, AT + 1.seconds); val k2 = p.knownOriginal(fresh)
        p.yes("actual replacement source and connection are positively newer", k2.issueSequence > k1.issueSequence &&
            fresh.connectionGeneration != old.connectionGeneration && g.raw.krxSeriesOwners[KRX] == fresh.krxGraphStamp)
        val beforeForeign = p.need(p.ledger.view, "actual replacement ACK loss holds K2 before stale callbacks")
        val inputCount = g.session.inputs.size; val captures = g.session.issued.size
        foreignWire.deliver(g.session.frame(1222.0, AT + 2.seconds)); foreignWire.drop(); g.r.drain()
        p.eq("foreign old socket emits no current graph facts", inputCount, g.session.inputs.size)
        p.eq("foreign old socket cannot issue a replacement source", captures, g.session.issued.size)
        assertSame(g.c("foreign socket cannot change current raw loss"), beforeForeign, p.ledger.view)
        val currentFrom = g.session.inputs.size
        g.r.wire.drop(); g.r.drain()
        val currentEnd = p.need(g.session.inputs.drop(currentFrom).filterIsInstance<TopicGraphInput.Continuity>().firstOrNull {
            it.reason == TopicGraphEventReason.WS_ENDED && TopicCatalogue.KRX_FUTURES in it.topics
        }, "actual current K2 WS_ENDED producer")
        p.eq("current interruption chooses current connection maximum known source", k2, currentEnd.authority.krxGraphStamp)
        p.recordedFact(currentEnd, TopicGraphOffer.FULL)
        val afterEndIssues = g.session.issued.size
        val before = p.need(p.ledger.view, "actual old and K2 loss coexist before delayed plan")
        p.yes("old and future stamps are positively held together", setOf(k1, k2).all { it in before.krxLost.values.flatten() })
        purgeEnding(p, k1, sink, frozenPlan = frozen)
        p.yes("delayed old proof retains actual K2 fact original", k2 in p.need(p.ledger.view, "K2 loss remains").krxLost.values.flatten())
        val currentRaw = g.print(g.krx())
        g.recorder.purgeKrxMemory(frozen)
        val late = p.sink()
        p.eq("actual historical old payload is positively enqueued late", TopicGraphOffer.ENQUEUED, late.tryOffer(old))
        p.yes("late old payload is inside drain window", old in p.queued(late))
        late.start(); g.test.runCurrent()
        p.eq("late old drain preserves actual current K2 raw data", currentRaw, g.print(g.krx()))
        p.eq("late old drain consumes held payload without retaining it", emptyList<TopicGraphInput>(), p.queued(late))
        p.eq("purge and late consumption issue no source", afterEndIssues, g.session.issued.size)
    }

    private fun purgeEnding(p: S6KrxGraphMemoryPurgeRig, original: KrxGraphStamp, sink: GraphRecorderTopicSink,
        ledger: TopicGraphLossLedger = p.ledger, frozenPlan: KrxGraphPurgePlan? = null,
        recordedLosses: List<TopicGraphInput.Continuity>? = null) {
        val before = p.need(ledger.view, "actual ending loss is positively held")
        p.yes("selected source exists immediately before purge", original in before.krxLost.values.flatten())
        val plan = frozenPlan ?: p.cutoff(original)
        // Other callers keep their proven mixed-batch count expectation. AuthFailure supplies its
        // complete actual offer history: its renewal window can also contain pure KRX facts.
        val removedPureBatches = recordedLosses?.let { batches ->
            p.eq("history oracle uses the newly installed explicit cutoff", null, frozenPlan)
            p.yes("actual recorded input history is positive", batches.isNotEmpty())
            p.eq("actual history accounts for every retained SESSION batch", batches.size.toLong(), before.count)
            p.eq("no prior loss or removal is hidden outside the recorded window", before.count, before.revision)
            p.yes("actual recorded sequences strictly increase", batches.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
            p.eq("actual history has the exact retained sequence window", batches.first().sequence to batches.last().sequence,
                before.firstSequence to before.lastSequence)
            p.eq("actual history has the exact retained topic inventory", batches.flatMap { it.topics }.toSet(), before.topics)
            val originals = mutableMapOf<String, MutableSet<KrxGraphStamp>>()
            val selectedNamespace = p.need(krxGraphNamespace(original.scope), "explicit cutoff has the original namespace")
            val cutoff = p.need(p.session.fence.memoryCutoffs().cutoffs[selectedNamespace], "actual explicit cutoff bound exists")
            p.yes("actual explicit cutoff selects the held original", original.issueSequence in 1L..cutoff)
            val pureSelected = batches.filter { batch ->
                val stamp = batch.authority.krxGraphStamp
                val krxTopics = batch.topics.filter(::isKrxGraphTopic)
                if (stamp != null && krxTopics.isNotEmpty()) {
                    p.eq("recorded stamp has its original owner", batch.authority.owner, stamp.scope.owner)
                    p.eq("recorded stamp has its original lifetime", batch.authority.lifetime, stamp.scope.use.lifetime)
                    krxTopics.forEach { originals.getOrPut(it) { mutableSetOf() } += stamp }
                }
                val bound = if (stamp?.let { krxGraphNamespace(it.scope) } == selectedNamespace) cutoff else null
                // Independent cutoff arithmetic on the actual source, not plan.classify/project output.
                val selected = stamp != null && bound != null && stamp.issueSequence in 1L..bound
                val shape = when {
                    krxTopics.isEmpty() -> "GENERAL"
                    krxTopics.size == batch.topics.size -> "pure-KRX"
                    else -> "mixed"
                }
                val origin = when {
                    stamp == null -> "unstamped"
                    stamp == original -> "selected-original"
                    selected -> "selected-other-original"
                    else -> "other-original"
                }
                println("contract(E04/AuthFailure): recorded batch seq=${batch.sequence} kind=${batch.kind} reason=${batch.reason}" +
                    " topics=${batch.topics.sorted()} shape=$shape origin=$origin issue=${stamp?.issueSequence} cutoff=$bound")
                selected && krxTopics.isNotEmpty() && krxTopics.size == batch.topics.size
            }
            p.eq("actual history has the exact retained original inventory", originals.mapValues { it.value.toSet() }, before.krxLost)
            pureSelected.size.toLong()
        } ?: 0L
        if (recordedLosses != null) println("contract(E04/AuthFailure): SESSION before=${before.count}" +
            " selectedPure=$removedPureBatches expectedAfter=${before.count - removedPureBatches}")
        val outcome = ledger.purgeKrxMemory(plan)
        p.yes("SESSION owned metadata physically projects selected original", outcome.removed && !outcome.unresolved)
        val after = ledger.view
        p.yes("current ledger contains no selected original", after?.krxLost.orEmpty().values.none { original in it })
        p.yes("GENERAL ending topics survive", after != null && after.topics.any { !isKrxGraphTopic(it) })
        if (after != null) {
            if (recordedLosses == null) p.eq("mixed batch count remains once", before.count, after.count)
            else p.eq("count removes exactly the selected pure KRX batches from actual history (pure=$removedPureBatches)",
                before.count - removedPureBatches, after.count)
            p.eq("one revision for actual removal", before.revision + 1, after.revision)
            p.eq("original time pair survives", before.firstOccurredAtEpochMillis to before.lastOccurredAtEpochMillis,
                after.firstOccurredAtEpochMillis to after.lastOccurredAtEpochMillis)
        }
        val pending = p.sinkLoss(sink)
        if (pending.isNotEmpty()) p.yes("sink selected ledger positive before its purge", original in pending.values.flatten())
        sink.purgeKrxMemory(plan)
        p.yes("selected sink ledger gone", original !in p.sinkLoss(sink).values.flatten())
        p.yes("detached historical loss still retains selected stamp", original in before.krxLost.values.flatten())
    }

    @Test fun e05_failedOrUnknownCleanupCanRetrySamePlan() = s63b2Test("E05", "Main-throw", "Main-cancel", "unknown", "close-before-dispatch", "TSC-retry", "TSC-unknown") { p, col ->
        if (col == "TSC-retry") {
            val g = p.g; g.r.httpFloor = g.r.clock.nowMillis() + 1_000_000
            g.start(leaseSeconds = 1); val old = g.adopt()
            g.session.target = TopicGraphSink { TopicGraphOffer.CLOSED }
            val lost = g.session.ws(1556.0, AT)
            p.recordedFactLoss(lost)
            val request = g.session.renewal()
            // Later ACK facts are consumed normally so they cannot replace the injected loss-map dependency.
            g.session.target = TopicGraphSink { TopicGraphOffer.ENQUEUED }
            val fault = p.failSessionPurgePreflight()
            val revision = g.snapshot.revision; val queries = g.r.h.source.calls.size; val restCalls = g.session.cleanup.f.calls.size
            g.session.cleanup.unavailable(request)
            p.yes("actual D1 reached failing SESSION preflight", fault.attempts > 0)
            val failed = p.need(g.session.cleanup.session.krxGraphLossPurgeState, "SESSION retains failed actual plan")
            p.yes("SESSION preflight failure is not successful cleanup", failed.failure != null)
            p.yes("selected original still owned in failed target window", old.krxGraphStamp in p.need(p.ledger.view, "failed target loss still positive").krxLost.values.flatten())
            val cutoffBeforeRetry = g.session.fence.revisions.value
            p.yes("failed plan was backed by a positive installed cutoff", cutoffBeforeRetry > 0)
            fault.armed = false
            g.r.revise(); g.r.drain() // A real public AccessRevised loop input, no timer or synthetic private handler.
            val retried = p.need(g.session.cleanup.session.krxGraphLossPurgeState, "real next loop input retries retained plan")
            p.eq("same snapshot revision retry", revision, g.snapshot.revision)
            p.eq("retry installs no new cutoff", cutoffBeforeRetry, g.session.fence.revisions.value)
            p.yes("SESSION retry succeeds only after fault clears", retried.failure == null && retried.outcome?.unresolved == false)
            p.yes("SESSION retry actually removes original payload", p.ledger.view?.krxLost.orEmpty().values.none { old.krxGraphStamp in it })
            p.eq("SESSION retry asks no entitlements producer", queries, g.r.h.source.calls.size)
            p.eq("SESSION retry starts no REST HTTP", restCalls, g.session.cleanup.f.calls.size)
            return@s63b2Test
        }
        if (col == "TSC-unknown") {
            val g = p.g; g.r.httpFloor = g.r.clock.nowMillis() + 1_000_000
            // boundary-input: legacy ingress cannot issue sidecars; real ACK/termination producers still run.
            setField(g.session.cleanup.session, "krxGraphIngress", KrxGraphIngress.NONE)
            g.session.target = TopicGraphSink { TopicGraphOffer.CLOSED }
            g.start()
            val loss = p.need(p.ledger.view, "actual unstamped ACK loss exists")
            p.yes("unknown KRX metadata is positively retained", TopicCatalogue.KRX_FUTURES in loss.topics && loss.krxLost.isEmpty())
            g.session.capEnd()
            val first = p.need(g.session.cleanup.session.krxGraphLossPurgeState, "explicit end reports unknown SESSION provenance")
            p.yes("unknown SESSION cannot complete", first.outcome?.unresolved == true)
            p.yes("unknown raw topic survives end", p.ledger.view?.topics?.contains(TopicCatalogue.KRX_FUTURES) == true)
            val revision = g.snapshot.revision
            g.session.capReopen()
            p.yes("real K2 revision is newer", g.snapshot.revision > revision)
            p.yes("new revision cannot fill unknown original", g.session.cleanup.session.krxGraphLossPurgeState?.outcome?.unresolved == true &&
                p.ledger.view?.krxLost.orEmpty().isEmpty())
            p.yes("unknown raw evidence still retained at K2", p.ledger.view?.topics?.contains(TopicCatalogue.KRX_FUTURES) == true)
            return@s63b2Test
        }
        val g = p.g; val (assembly, _) = g.ownerAssembly(); val old = g.session.ws(); val original = old.krxGraphStamp!!
        assembly.coordinator.onActivated(GraphKey("usd", GraphPeriod.ONE_DAY)); runCurrent()
        g.sent.filter { !it.response.isCompleted }.forEach { it.response.complete(S6KrxGraphRig.ok(S6KrxGraphRig.tabDto(it.key))) }; runCurrent()
        if (col == "close-before-dispatch") {
            assembly.close()
            val result = assembly.krxMemoryPurgeState
            p.yes("closing is not a fabricated completed purge", result == null || result.failure != null || result.outcome?.unresolved == true)
        } else if (col == "unknown") {
            val cutoff = assembly.krxGraphIngress.topicUnavailable(null)
            p.eq("unknown ingress remains unresolved", KrxGraphTopicResult.UNRESOLVED, cutoff.result)
            p.eq("unknown cannot create successful plan", null, KrxGraphPurgePlan.forCutoff(cutoff))
            p.yes("unknown leaves positive raw original held", original in assembly.recorder.state.value.krxSeriesOwners.values ||
                assembly.recorder.state.value.pending.inputs.any { p.stamp(it) == original })
        } else {
            // boundary-input: target structural preflight fails once through its existing collection dependency.
            @Suppress("UNCHECKED_CAST") val slots = field(assembly.coordinator, "protectedSlots") as Map<GraphKey, Any>
            p.yes("GRC has positive selected ownership before fault", slots.isNotEmpty())
            var armed = true
            val failure: Throwable = if (col == "Main-cancel") CancellationException("boundary-input: transient target cancellation")
                else IllegalStateException("boundary-input: transient target failure")
            val fault = object : Map<GraphKey, Any> by slots {
                override val entries: Set<Map.Entry<GraphKey, Any>> get() {
                    if (armed) { armed = false; throw failure }; return slots.entries
                }
                override val values: Collection<Any> get() {
                    if (armed) { armed = false; throw failure }; return slots.values
                }
            }
            setField(assembly.coordinator, "protectedSlots", fault)
            val revision = g.snapshot.revision
            val cutoff = assembly.krxGraphIngress.topicUnavailable(original.scope)
            runCurrent()
            val failed = p.need(assembly.krxMemoryPurgeState, "failed automatic attempt is retained")
            p.yes("failure/cancellation cannot publish success", failed.failure != null)
            p.eq("same access revision is still current", revision, g.snapshot.revision)
            p.eq("same cutoff revision retained", cutoff.revision, failed.cutoffRevision)
            setField(assembly.coordinator, "protectedSlots", slots)
            val http = g.sent.size
            val outcome = assembly.purgeKrxMemoryNow(); runCurrent()
            p.yes("explicit same-plan retry completes real remaining work", !outcome.unresolved && assembly.krxMemoryPurgeState?.failure == null)
            p.eq("retry does not create HTTP", http, g.sent.size)
            p.yes("retry removes remaining KRX component", (field(assembly.coordinator, "protectedSlots") as Map<*, *>).values.all {
                (field(p.need(it, "remaining slot"), "components") as GraphV2DiskComponents).krx == null
            })
        }
    }
}
