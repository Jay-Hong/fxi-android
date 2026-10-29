package com.jay.fxi

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.purge.*
import com.jay.fxi.data.graph.*
import com.jay.fxi.data.remote.*
import com.jay.fxi.data.remote.S6KrxGraphSessionRig.Companion.AT
import com.jay.fxi.domain.model.*
import com.jay.fxi.service.*
import com.jay.fxi.service.S6PendingAlertTapRig.Companion.source
import com.jay.fxi.service.S6PendingAlertTapRig.Companion.sourceEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class S6Krx3cIntegrationContractTest {
    @Test fun i01_fourRevocationsReachAllMemoryOwners() {
        val columns = listOf("WS", "REST", "false", "rotation", "coalesced-rotation", "USER", "stale-REST")
            .flatMap { cause -> listOf("Main-first", "TSC-first").map { "$cause/$it" } }
        s63b2Test("I01", *columns.toTypedArray()) { m, col ->
            val g = m.g; val serial = m.serialGate(); val (assembly, _) = m.ownerAssembly(serial)
            val p = S6PendingAlertTapRig(this, "I01/$col", g)
            val old = g.session.ws(); val stamp = p.need(col, old.krxGraphStamp, "actual old KRX source")
            assembly.coordinator.onActivated(GraphKey("usd", GraphPeriod.ONE_DAY)); runCurrent()
            g.sent.filter { !it.response.isCompleted }.forEach { it.response.complete(S6KrxGraphRig.ok(S6KrxGraphRig.tabDto(it.key))) }; runCurrent()
            p.yes(col, "real assembly owns raw original before revocation", stamp in assembly.recorder.state.value.krxSeriesOwners.values ||
                assembly.recorder.state.value.pending.inputs.any { m.stamp(it) == stamp })
            g.r.generalTick(1444.0)
            val krx = p.capture(); val non = p.capture(sourceEvent(), source("upbit"))
            val nonBefore = p.owned(non, col); p.owned(krx, col)
            val unknown = if (col.startsWith("USER")) p.capture(sourceEvent(), AlertTapMetadata()) else null
            val bank = if (col.startsWith("USER")) p.capture(AlertEvent.RefreshNeeded, AlertTapMetadata()) else null
            val lateAck = g.session.renewal()
            p.yes(col, "old ACK request is physically SENT inside original window", g.r.sends.any { it.accepted && it.request == lateAck })
            val oldPending = g.session.cleanup.issue()
            p.yes(col, "old REST is live inside original window", !oldPending.cancelled && oldPending.outcome == null)
            val cause = col.substringBefore('/')
            if (col.endsWith("Main-first")) {
                // Independent Main topic cleanup first, while the SESSION ledger is still positive.
                assembly.krxGraphIngress.topicUnavailable(stamp.scope); runCurrent()
            }
            serial.pause()
            when (cause) {
                "WS" -> g.session.rejectKrxFromWsForPurge()
                "REST" -> {
                    // Existing actual Retrofit fixture; no new rejection literal inventory.
                    g.session.cleanup.f.reply(oldPending, KrxA1bFixture.unsupported)
                    p.yes(col, "actual REST rejection reached checked PAC", g.snapshot.capabilityInvalidations > 0)
                }
                "false" -> g.session.capEnd()
                "rotation", "coalesced-rotation" -> { g.session.capEnd(); g.session.capReopen() }
                "USER" -> g.session.cleanup.f.loss("premium")
                "stale-REST" -> { g.session.capEnd(); g.session.capReopen(); g.session.rest(1777.0, AT) }
            }
            val control = g.r.h.store.record
            val general = g.r.rates.quotes.filterKeys { it.source != "krx" }
            serial.release(); runCurrent()
            // The bus bridge must react to actual revisions; calling owner.reconcile here would hide μ26.
            val tapAudit = p.audit()
            val tapReport = p.bus.pendingTapReport()
            p.eq(col, "tap evaluation adds no actual FE/auth/HTTP exchange", tapAudit, p.audit())
            p.yes(col, "same production bus observed PAC end", krx !in p.inventory() && tapReport.removedKrx + tapReport.removedUser > 0)
            if (cause == "USER") {
                p.yes(col, "USER fanout retires all families", listOfNotNull(krx, non, unknown, bank).none { it in p.inventory() })
            } else p.eq(col, "capability fanout preserves nonKRX raw identity/FIFO", nonBefore, p.owned(non, col))
            p.eq(col, "graph cleanup preserves control/journal", control, g.r.h.store.record)
            p.eq(col, "graph cleanup preserves GENERAL FE values", general, g.r.rates.quotes.filterKeys { it.source != "krx" })
            p.yes(col, "old raw graph original removed", stamp !in assembly.recorder.state.value.krxSeriesOwners.values &&
                assembly.recorder.state.value.pending.inputs.none { m.stamp(it) == stamp })
            val deliveredBefore = g.session.inputs.size
            g.session.deliver(g.session.frame(1999.0, AT))
            if (!g.snapshot.facts.capabilityAllowed) p.eq(col, "T12 old WS application zero", deliveredBefore, g.session.inputs.size)
            if (cause == "REST") p.yes(col, "rejection source completed before retirement", oldPending.outcome != null)
            else p.yes(col, "T12 old bootstrap cancellation is real", oldPending.cancelled)
            g.r.ack(lateAck)
            p.yes(col, "T12 late old ACK cannot confirm retired KRX", !g.r.store.snapshot.stateFor(TopicCatalogue.KRX_FUTURES).confirmed)
            if (cause == "stale-REST") {
                val current = g.snapshot
                g.session.cleanup.f.reply(oldPending, KrxA1bFixture.unsupported)
                p.eq(col, "late cancelled K1 REST refusal cannot retire K2", current, g.snapshot)
            }
            val oldRequests = g.r.requests.size
            runCurrent()
            p.eq(col, "T12 bootstrap retry without fresh input zero", oldRequests, g.r.requests.size)
            p.yes(col, "T12 old recovery publication zero", g.r.rates.quotes.values.none { it.rate == 1999.0 })
            p.yes(col, "T12 old graph completion application zero", stamp !in assembly.recorder.state.value.krxSeriesOwners.values)
            if (cause.endsWith("rotation") || cause == "stale-REST") {
                val fresh = p.capture(column = "$col-K2"); p.owned(fresh, col)
                p.owner.classify(krx)
                p.eq(col, "late old carrier cannot acquire K2", AlertTapReceipt.REJECTED, p.bus.consumePendingTap(AlertEvent.PendingTap(krx)) { AlertTapReceipt.HANDLED })
                p.owned(fresh, "$col-K2-after-late")
            }
        }
        queuedOriginalsAcrossEveryRevocation()
    }

    private fun queuedOriginalsAcrossEveryRevocation() {
        val columns = listOf("WS", "REST", "false", "rotation").flatMap { cause ->
            listOf("Main-first", "TSC-first").map { "$cause/$it/queued-originals" }
        }
        s63b2Test("I01", *columns.toTypedArray()) { m, col ->
            val g = m.g; val serial = m.serialGate(); val (assembly, _) = m.ownerAssembly(serial)
            val p = S6PendingAlertTapRig(this, "I01", g)
            val old = g.session.ws(); val stamp = p.need(col, old.krxGraphStamp, "actual K1 source before deferred completions")
            assembly.coordinator.onActivated(GraphKey("usd", GraphPeriod.ONE_DAY)); runCurrent()
            g.sent.filter { !it.response.isCompleted }.forEach { it.response.complete(S6KrxGraphRig.ok(S6KrxGraphRig.tabDto(it.key))) }; runCurrent()
            p.yes(col, "actual assembly positively owns K1 raw data", stamp in assembly.recorder.state.value.krxSeriesOwners.values)
            val krx = p.capture(column = col); val non = p.capture(sourceEvent(), source("upbit"), column = col)
            val nonRaw = p.owned(non, col); p.owned(krx, col)
            // Finish renewal waiting before constructing bounded REST/graph response windows.
            g.session.renewal()
            g.r.generalTick(1444.0)
            val generalInput = g.general()
            assembly.recorder.observe(generalInput)
            p.yes(col, "GENERAL raw recorder sentinel is positive before selective cleanup", assembly.recorder.state.value.series.keys.any { it.seriesId == S6KrxGraphRig.GENERAL })
            val heldSink = m.sink() // actual production queue, explicitly not started; no fabricated queue contents.
            val generalQueued = m.batch(generalInput, 2, 90_001)
            p.eq(col, "GENERAL raw queue positively owns complete input", TopicGraphOffer.ENQUEUED, heldSink.tryOffer(generalQueued))
            p.eq(col, "same queue positively owns K1 target", TopicGraphOffer.ENQUEUED, heldSink.tryOffer(old))
            p.eq(col, "real FULL installs GENERAL raw loss ledger", TopicGraphOffer.FULL, heldSink.tryOffer(m.batch(generalInput, 512, 90_002)))
            // Public ledger inputs preserve actual source attribution. No private cleanup is invoked.
            p.yes(col, "SESSION positively holds GENERAL loss", m.ledger.record(m.batch(generalInput, 1, 91_001), TopicGraphOffer.CLOSED, 123L))
            p.yes(col, "SESSION positively holds K1 loss beside GENERAL", m.ledger.record(m.fact(stamp, old, 91_002), TopicGraphOffer.CLOSED, 124L))
            fun generalLedger(): Map<Any?, Any?> {
                val state = com.jay.fxi.data.remote.S6KrxCleanupRig.field(m.ledger, "state")
                    ?: throw AssertionError("premise: I01 SESSION loss state exists")
                val aggregates = com.jay.fxi.data.remote.S6KrxCleanupRig.field(state, "aggregates") as? Map<*, *>
                    ?: throw AssertionError("premise: I01 SESSION aggregate inventory exists")
                return aggregates.entries.filter { (key, _) -> key != null &&
                    (com.jay.fxi.data.remote.S6KrxCleanupRig.field(key, "originalKrxLost") as? Map<*, *>)?.isEmpty() == true &&
                    (com.jay.fxi.data.remote.S6KrxCleanupRig.field(key, "generalTopics") as? Set<*>)?.isNotEmpty() == true
                }.associate { it.key to it.value }
            }
            fun generalQueueAndLoss(): List<Any?> {
                @Suppress("UNCHECKED_CAST") val loss = com.jay.fxi.data.remote.S6KrxCleanupRig.field(heldSink, "ledger") as Map<GraphDataScope, Map<String, Long>>
                val sessionState = com.jay.fxi.data.remote.S6KrxCleanupRig.field(m.ledger, "state")
                    ?: throw AssertionError("premise: I01 raw SESSION ledger state exists")
                @Suppress("UNCHECKED_CAST") val retired = com.jay.fxi.data.remote.S6KrxCleanupRig.field(sessionState, "retiredScopes") as Set<GraphDataScope>
                @Suppress("UNCHECKED_CAST") val floors = com.jay.fxi.data.remote.S6KrxCleanupRig.field(sessionState, "endedUseFloors") as Map<GraphDataScope, Long>
                return listOf(m.queued(heldSink).filter { m.stamp(it) == null }, loss.mapValues { it.value.toMap() }, generalLedger(),
                    retired.toSet(), floors.toMap(), com.jay.fxi.data.remote.S6KrxCleanupRig.field(sessionState, "highestSequence"))
            }
            fun generalRecorder(): List<Any?> {
                val raw = assembly.recorder.state.value
                return listOf(raw.series.filterKeys { it.seriesId != S6KrxGraphRig.KRX }.mapValues { g.print(it.value) },
                    raw.pending.inputs.filter { m.stamp(it) == null }, raw.pending.lostTopics,
                    raw.untransferredSeries.filter { it != S6KrxGraphRig.KRX }.toSet(), raw.versionFloor)
            }
            val queueAndLoss = generalQueueAndLoss()
            p.yes(col, "GENERAL queue/loss positive controls are nonempty", m.queued(heldSink).any { it === generalQueued } &&
                (queueAndLoss[1] as Map<*, *>).isNotEmpty() && generalLedger().isNotEmpty())
            val rest = g.session.cleanup.issue()
            p.yes(col, "REST200 target is live before actual completion", !rest.cancelled && rest.outcome == null)
            S6QueuedBootstrap200(g, "I01/$col").use { restGate ->
                g.session.cleanup.f.reply(rest, g.session.cleanup.f.delivered(1889.0))
                p.yes(col, "actual REST200 producer has returned before revoke", rest.outcome is TopicSnapshotOutcome.Delivered && !rest.cancelled)
                p.eq(col, "actual completed REST200 retained in native FIFO", 1, restGate.count)
                val heldRest = restGate.retained()
                assembly.coordinator.onRefreshRequested(force = true); runCurrent()
                val call = p.need(col, g.sent.lastOrNull { !it.response.isCompleted && it.key == GraphKey("usd", GraphPeriod.ONE_DAY) }, "actual graph request admitted before revocation")
                p.yes(col, "graph producer is still inside old request window", call.admitted && !call.cancelled && !call.returned)
                g.CompletionBarrier(assembly.coordinator).use { graphGate ->
                    call.response.complete(S6KrxGraphRig.ok(S6KrxGraphRig.tabDto(call.key))); runCurrent()
                    p.yes(col, "native graph producer really completed before revoke", call.returned && !call.cancelled)
                    p.eq(col, "native graph completion retained positively", 1, graphGate.enqueued)
                    p.eq(col, "original REST200 still held before source input", heldRest, restGate.retained())
                    val queueAndLossBeforeSource = generalQueueAndLoss()
                    val recorderBefore = generalRecorder()
                    p.owned(krx, col)
                    if (col.contains("Main-first")) { assembly.krxGraphIngress.topicUnavailable(stamp.scope); runCurrent() }
                    serial.pause()
                    when (col.substringBefore('/')) {
                        "WS" -> g.session.rejectKrxFromWsForPurge()
                        "REST" -> g.session.rejectKrxFromRestForPurge() // selective gate lets Unsupported through.
                        "false" -> g.session.capEnd()
                        "rotation" -> { g.session.capEnd(); g.session.capReopen() }
                    }
                    p.yes(col, "source posted withdrawal before deferred deliveries", !g.snapshot.admitsKrxUse(stamp.scope.use))
                    val control = g.r.h.store.record
                    serial.release(); runCurrent()
                    val tapReport = p.bus.pendingTapReport()
                    p.yes(col, "same bus independently observed the real source", krx !in p.inventory() && tapReport.removedKrx > 0)
                    p.eq(col, "capability-only source preserves nonKRX original", nonRaw, p.owned(non, col))
                    // Exercise the same production adapter on the held production queue. This fixture
                    // queue is additional to the factory's running sink; it is not an Activity/OS queue.
                    p.yes(col, "K1 target still physically queued immediately before adapter", m.queued(heldSink).any { m.stamp(it) == stamp })
                    val namespace = m.namespace(stamp)
                    val outcome = GraphRecorderPurgeAdapter(assembly.recorder, heldSink, assembly.coordinator, g.dispatcher)
                        .purge(PurgeRequest(m.memoryTarget, PurgeScope.CAPABILITY, PurgeCause.CAPABILITY_REVOKE, namespace))
                    p.eq(col, "production adapter selected K1 held queue", TargetOutcome.Removed, outcome)
                    p.eq(col, "GENERAL raw queue and both loss ledgers unchanged by source/cleanup", queueAndLossBeforeSource, generalQueueAndLoss())
                    // Main-first D1 may mark KRX only. GENERAL REC must retain all original fields.
                    p.eq(col, "GENERAL raw recorder data/pending/loss/floor unchanged", recorderBefore, generalRecorder())
                    p.yes(col, "selected K1 absent before releasing old completions", stamp !in assembly.recorder.state.value.krxSeriesOwners.values &&
                        m.queued(heldSink).none { m.stamp(it) == stamp })
                    p.eq(col, "physical cleanup preserves PAC control/journal", control, g.r.h.store.record)
                    val fresh = if (col.startsWith("rotation")) g.session.rest(1777.0, AT) else null
                    if (fresh != null) p.yes(col, "fresh K2 positive before releasing either old completion", fresh.krxGraphStamp in assembly.recorder.state.value.krxSeriesOwners.values)
                    p.eq(col, "original completed REST still retained immediately before release", heldRest, restGate.retained())
                    val lateControl = g.r.h.store.record
                    restGate.release(); graphGate.release(); runCurrent()
                    p.yes(col, "late old REST200 application zero", g.r.rates.quotes.values.none { it.rate == 1889.0 } &&
                        g.r.offers.none { rates -> rates.quotes.values.any { it.rate == 1889.0 } })
                    p.yes(col, "late native graph completion cannot recreate K1", stamp !in assembly.recorder.state.value.krxSeriesOwners.values &&
                        assembly.recorder.state.value.pending.inputs.none { m.stamp(it) == stamp })
                    p.eq(col, "GENERAL held queue/loss remain complete after late deliveries", queueAndLossBeforeSource, generalQueueAndLoss())
                    p.eq(col, "late producers preserve PAC control and journal", lateControl, g.r.h.store.record)
                    p.eq(col, "nonKRX tap stays owned after both late completions", nonRaw, p.owned(non, col))
                    if (fresh != null) p.eq(col, "old FIFO release preserves K2 raw owner", fresh.krxGraphStamp, assembly.recorder.state.value.krxSeriesOwners[S6KrxGraphRig.KRX])
                    p.yes(col, "GENERAL portion of old native completion still adopted", assembly.coordinator.protectedEntry(call.key)?.tab?.graph?.series?.any { it.seriesId == S6KrxGraphRig.GENERAL } == true)
                }
            }
        }
    }

    @Test fun i02_holdD1AndRestDegradedKeepTapIntent() {
        // Each invocation owns a fresh runTest/PAC/session. Only renewal/expiry columns need a
        // short lease: the real six-topic REST plan's 500ms issue gap can outlast a 1s ACK lease.
        for (column in listOf("D1", "degraded", "topics-disabled", "gap", "lease", "hold", "REST-high", "REST-low")) {
            s63cTest("I02", column, leaseSeconds = if (column in setOf("D1", "lease")) 1 else 600) { p, col ->
                val g = p.g; val old = g.adopt(if (col.startsWith("REST-")) 1500.0 else 1555.0); g.general()
                val krx = p.capture(); val non = p.capture(sourceEvent(), source("upbit"))
                val inventory = p.inventory(); inventory.keys.forEach { p.owned(it, col) }
                val before = g.snapshot; val control = g.r.h.store.record; val requests = g.r.h.source.calls.size
                when (col) {
                    "D1" -> g.session.d1()
                    "degraded", "topics-disabled" -> {
                        val call = g.session.cleanup.issue()
                        g.session.cleanup.f.reply(call, KrxA1bFixture.Reply(404, if (col == "degraded") """{"error":"topic_unavailable"}""" else """{"error":"topics_disabled"}"""))
                        p.yes(col, "real degraded REST completed", call.outcome != null && !call.cancelled)
                    }
                    "gap" -> { g.r.wire.drop(); g.r.drain() }
                    "lease" -> g.r.advance(1_001)
                    "hold" -> g.session.cleanup.hold()
                    else -> {
                        // Same graph HTTP request and response application as G13/G14; a session
                        // bootstrap is a different producer and can be superseded by the WS frame.
                        val coordinator = g.readyCoordinator()
                        coordinator.onActivated(GraphKey("usd", GraphPeriod.ONE_DAY)); runCurrent()
                        val call = p.need(col, g.sent.lastOrNull { it.key.period == GraphPeriod.ONE_DAY }, "real graph REST request held")
                        p.yes(col, "inside real graph REST wait", !call.response.isCompleted && !call.returned && !call.cancelled)
                        val extreme = if (col == "REST-high") 1900.0 else 1100.0
                        p.yes(col, "REST issue has not expired the acknowledged WS lease",
                            g.session.cleanup.registry.held[TopicCatalogue.KRX_FUTURES]?.expiresAtMillis?.let { it > g.r.clock.nowMillis() } == true)
                        p.yes(col, "actual WS topic remains confirmed before the extrema frame",
                            g.r.store.snapshot.stateFor(TopicCatalogue.KRX_FUTURES).confirmed)
                        g.session.ws(extreme, AT + 1.seconds)
                        val buckets = g.krx().data.app.buckets
                        call.response.complete(S6KrxGraphRig.ok(S6KrxGraphRig.tabDto(call.key, 1500.0))); runCurrent()
                        p.yes(col, "real REST extrema window completed and valid response applied", call.returned && !call.cancelled && g.krx().lastAppliedVersion != null)
                        p.eq(col, "original graph anchor survives REST", old.krxGraphStamp, g.raw.krxSeriesOwners[S6KrxGraphRig.KRX])
                        p.eq(col, "observed high/low buckets survive REST", buckets, g.krx().data.app.buckets)
                    }
                }
                runCurrent()
                p.bus.pendingTapReport()
                p.eq(col, "topic interruption never retires capability tap intent", inventory, p.inventory())
                p.eq(col, "no tap metadata FE lookup", requests, g.r.h.source.calls.size)
                if (col != "hold" && col != "lease") {
                    p.eq(col, "topic degradation does not rotate capability", before.facts.recordFence, g.snapshot.facts.recordFence)
                    p.eq(col, "control journal unchanged", control, g.r.h.store.record)
                }
                if (col == "D1") {
                    val memory = S6KrxGraphMemoryPurgeRig(g)
                    p.yes(col, "D1 selectively purges graph topic", memory.purgeHeld(memory.plan()).removed)
                    p.owned(krx, col); p.owned(non, col)
                }
                if (col == "hold") p.eq(col, "hold blocks original tap consumption", AlertTapReceipt.REJECTED, p.consume(krx))
            }
        }
        // Keep each original high/low and T11 recovery oracle, rather than merely checking a request label.
        S6KrxRecorderContractTest().g13_pendingRestKeepsObservedHigh()
        S6KrxRecorderContractTest().g14_pendingRestKeepsObservedLow()
        S6KrxRecorderContractTest().g16_transientGapKeepsRecoveryDemand()
        S6KrxGraphMemoryPurgeContractTest().b12_d1DeletesMemoryWithoutRevokingCapability()
    }

    @Test fun i03_parentCompletionIncludesTapUnknownAndFailures() = s63cTest("I03", "known", "UNKNOWN", "context", "consumer-throw") { p, col ->
        val g = p.g; val old = g.adopt(); g.general()
        val memory = S6KrxGraphMemoryPurgeRig(g)
        val context = p.context().let { if (col == "context") it.copy(identity = null, scope = null, userAccessEpoch = null, krxCapabilityEpoch = null) else it }
        val tap = p.capture(sourceEvent(), if (col == "UNKNOWN") AlertTapMetadata() else source(), context, column = col)
        p.owned(tap, col)
        if (col == "consumer-throw") {
            try { p.owner.consume(tap, g.snapshot, g.r.identity) { throw IllegalStateException("boundary-input: action rejected") } }
            catch (_: IllegalStateException) { }
            p.owned(tap, col)
        }
        val original = p.need(col, old.krxGraphStamp, "raw graph original")
        g.session.capEnd(); val control = g.r.h.store.record
        val graphOutcome = memory.purgeHeld(memory.plan())
        p.yes(col, "graph memory genuinely cleaned", graphOutcome.removed && !graphOutcome.unresolved)
        memory.rawRemoved(original)
        val report = p.report()
        p.eq(col, "tap incompleteness must survive graph success", col == "known", report.memoryComplete)
        val target = PurgeManifest.TARGETS.single { it.id == "file:graph_v2_krx" }
        val ns = memory.namespace(original)
        for (disk in listOf("Completed", "Deferred", "Failed")) {
            val adapters = if (disk == "Deferred") emptyMap() else mapOf(target.id to PurgeTargetAdapter {
                if (disk == "Failed") TargetOutcome.Failed("boundary-input: fake disk") else TargetOutcome.Removed
            })
            val result = ManifestScopePurger(adapters, listOf(target)).purgeCapabilityScope(ns)
            p.eq("$col-$disk", "fake fixture journal settlement distinct from tap memory", disk == "Completed", result == PurgeResult.Completed)
            p.eq("$col-$disk", "fake disk cannot mask tap UNKNOWN/failure", col == "known", report.memoryComplete)
            p.eq("$col-$disk", "fake disk never rewrites actual PAC control/journal", control, g.r.h.store.record)
        }
    }
}
