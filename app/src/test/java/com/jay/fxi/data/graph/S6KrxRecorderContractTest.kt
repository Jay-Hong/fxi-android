package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.purge.KrxMemoryScope
import com.jay.fxi.data.remote.*
import com.jay.fxi.data.remote.S6KrxCleanupRig.Companion.field
import com.jay.fxi.data.remote.S6KrxCleanupRig.Companion.setField
import com.jay.fxi.data.graph.S6KrxGraphRig.Companion.GENERAL
import com.jay.fxi.data.graph.S6KrxGraphRig.Companion.KRX
import com.jay.fxi.data.remote.S6KrxGraphSessionRig.Companion.AT
import com.jay.fxi.domain.model.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.datetime.Instant
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class S6KrxRecorderContractTest {
    private val offsets = arrayOf("absent", "equal", "past", "below5", "exact5", "above5")
    private fun changed(column: String) = when (column) {
        "absent" -> null; "equal" -> AT; "past" -> AT - 2.seconds
        "below5" -> AT + 4_999.milliseconds; "exact5" -> AT + 5.seconds; else -> AT + 5_001.milliseconds
    }
    private fun times(g: S6KrxGraphRig, column: String) {
        val expected = when (column) {
            "past" -> setOf(AT - 2.seconds, AT)
            "below5" -> setOf(AT, AT + 4_999.milliseconds)
            else -> setOf(AT)
        }
        g.eq("mapper retains both valid original times", expected, g.krx().data.app.observations.map { it.observedAt }.toSet())
        g.eq("five-second anomaly boundary", column in setOf("exact5", "above5"),
            g.krx().pending.values.any { GraphRecoveryReason.TIME_ANOMALY in it.reasons })
    }

    @Test fun g01_wsCarriesOriginalCaptureAndBothTimes() = s63b1Test("G01", *offsets) { g, col ->
        g.start(); val input = g.adopt(changed = changed(col)); times(g, col)
        val buckets = g.krx().data.app.buckets
        val observations = g.krx().data.app.observations
        val tip = g.krx().data.app.tip
        val serverBuckets = g.krx().data.serverBuckets
        val demandReasons = g.krx().pending.mapValues { it.value.reasons }
        val version = g.krx().lastAppliedVersion
        g.recorder.observe(input)
        g.eq("duplicate preserves price buckets", buckets, g.krx().data.app.buckets)
        g.eq("duplicate preserves original observations", observations, g.krx().data.app.observations)
        g.eq("duplicate preserves price tip", tip, g.krx().data.app.tip)
        g.eq("duplicate preserves server buckets", serverBuckets, g.krx().data.serverBuckets)
        g.eq("duplicate retains recovery buckets/reasons", demandReasons, g.krx().pending.mapValues { it.value.reasons })
        g.eq("duplicate cannot apply a server version", version, g.krx().lastAppliedVersion)
        times(g, col)
        g.eq("replay does not issue a new stamp", input.krxGraphStamp, g.raw.krxSeriesOwners[KRX])
    }

    @Test fun g02_restCarriesOriginalIssueAndBothTimes() = s63b1Test("G02", *offsets) { g, col ->
        g.start()
        val call = g.session.cleanup.issue()
        val sourceIssues = g.session.issued.toList()
        g.yes("actual REST source was stamped before completion", sourceIssues.isNotEmpty())
        val issueRevision = call.read.revision
        val open = g.r.snapshot
        // boundary-input: changes revision only; no reapproval, new capture or canceled late response.
        g.r.snapshotSupply = { open.copy(revision = open.revision + 1) }
        g.yes("inside post-issue revision-only window", g.r.snapshotSupply().revision > issueRevision)
        val input = g.session.rest(changed = changed(col))
        g.eq("completion retains original REST source stamp", sourceIssues.last().second, input.krxGraphStamp)
        g.eq("completion issues nothing", sourceIssues.size, g.session.issued.size)
        g.yes("REST mapped into recorder", g.krxKey in g.raw.series); times(g, col)
    }

    @Test fun g03_invalidAndPreAckInputsNeverCreateKrx() = s63b1Test("G03",
        "preACK", "WS-empty", "WS-zero", "WS-negative", "WS-NaN", "WS-infinity", "WS-source", "WS-asset",
        "REST-empty", "REST-zero", "REST-negative", "REST-NaN", "REST-infinity", "REST-source", "REST-asset",
        "GENERAL-WS-source", "GENERAL-WS-asset", "GENERAL-REST-source", "GENERAL-REST-asset",
        "tether-WS-source", "tether-WS-asset", "tether-REST-source", "tether-REST-asset") { g, col ->
        g.start(ack = col != "preACK")
        val before = g.session.inputs.filterIsInstance<TopicGraphInput.Observations>().size
        if (col == "preACK") {
            g.session.deliver(g.session.frame())
            g.eq("inside preACK window: original active capture absent", null, field(g.session.cleanup.live, "krxActive"))
            g.eq("preACK makes no observations", before, g.session.inputs.filterIsInstance<TopicGraphInput.Observations>().size)
            g.session.ack()
        } else if (col.startsWith("WS-") || col.startsWith("REST-")) {
            val text = when (col.substringAfter('-')) {
                "empty" -> """{"type":"snapshot","version":1,"topic":"${TopicCatalogue.KRX_FUTURES}","data":{}}"""
                "zero" -> g.session.frame(0.0)
                "negative" -> g.session.frame(-1.0)
                "NaN" -> g.session.frame(Double.NaN)
                "infinity" -> g.session.frame(Double.POSITIVE_INFINITY)
                "source" -> g.session.frame(source = "kb")
                else -> g.session.frame(asset = "usdt-krw")
            }
            if (col.startsWith("WS-")) g.session.deliver(text)
            else {
                val call = g.session.cleanup.issue()
                g.yes("actual invalid REST producer is awaiting an answer", !call.cancelled && call.outcome == null)
                g.session.cleanup.f.reply(call, KrxA1bFixture.Reply(200, text))
                g.yes("invalid REST actually completed without cancellation", call.outcome != null && !call.cancelled)
            }
            g.eq("invalid dedicated input produces no candidate", before, g.session.inputs.filterIsInstance<TopicGraphInput.Observations>().size)
            g.yes("invalid input creates no series/demand/held batch", g.raw.series.isEmpty() && g.raw.pending.inputs.isEmpty() && g.raw.krxUntransferred.isEmpty())
        } else {
            val topic = if (col.startsWith("GENERAL-")) "fx:usd-krw" else "usdt:krw"
            val source = if (col.endsWith("-source")) "krx" else "kb"
            val asset = if (col.endsWith("-asset")) "usd-krw-futures" else "usd-krw"
            val entry = """{"source":"$source","asset":"$asset","rate":1500.0,"timestamp":"$AT"}"""
            val data = if (topic == "fx:usd-krw") """{"banks":[$entry]}""" else """{"usdt_krw":[],"usd_krw_banks":[$entry]}"""
            val text = """{"type":"snapshot","version":1,"topic":"$topic","data":$data}"""
            val from = g.session.inputs.size
            if ("-WS-" in col) g.session.deliver(text)
            else {
                g.r.until(g.c("actual GENERAL/tether REST transport is waiting"), 2_600) {
                    g.session.cleanup.f.calls.any { it.topic == topic && it.outcome == null && !it.cancelled }
                }
                val call = g.need(g.session.cleanup.f.calls.lastOrNull { it.topic == topic && it.outcome == null && !it.cancelled }, "original GENERAL REST producer")
                g.session.cleanup.f.reply(call, KrxA1bFixture.Reply(200, text))
                g.yes("GENERAL poison REST completed normally", call.outcome != null && !call.cancelled)
            }
            val candidates = g.session.inputs.drop(from).filterIsInstance<TopicGraphInput.Observations>().flatMap { it.candidates }
            g.yes("actual GENERAL/tether source rejects either poison field", candidates.filterIsInstance<TopicGraphCandidate.Quote>()
                .none { it.source == "krx" || it.asset == "usd-krw-futures" })
            g.yes("GENERAL path cannot make KRX series or recovery", g.krxKey !in g.raw.series && g.raw.krxSeriesOwners.isEmpty())
        }
        g.general()
        val general = g.generalPrint()
        g.adopt() // Every negative column includes a real dedicated positive, not a vacuous zero.
        g.eq("valid dedicated stream leaves GENERAL unchanged", general, g.generalPrint())
    }

    @Test fun g04_originalEpochAndHoldFenceEveryConsumer() = s63b1Test("G04", "epoch", "hold") { g, col ->
        g.start(); val old = g.adopt(); g.general()
        val stamp = g.need(old.krxGraphStamp, "original K1")
        val request = g.originalRequest()
        val original = request.copy()
        val raw = g.print(g.krx()); val general = g.generalPrint(); val floor = g.raw.versionFloor
        g.boundaryRoundTrip(col, stamp)
        g.eq("gate checks original use, without any physical tombstone", null, g.gate.bindKrxGraph(old.attribution.owner, old.attribution.lifetime, stamp))
        g.recorder.observe(old); g.recorder.replayPending()
        g.yes("old is not exposed", g.krxKey !in g.exposed())
        g.yes("old cannot capture another recovery", g.requests().none { it.seriesKey == g.krxKey })
        g.recorder.applyResponse(request, response(g), old.attribution.owner, old.attribution.lifetime)
        g.eq("raw old response application refused", raw, g.print(g.krx()))
        g.eq("request immutable", original, request)
        g.eq("GENERAL unchanged", general, g.generalPrint()); g.eq("USER floor unchanged", floor, g.raw.versionFloor)
        val k2 = g.need(g.gate.captureKrxGraph(g.owner!!, g.use)?.captured?.krxGraphStamp, "new source K2")
        g.yes("new K2 is admitted", g.gate.bindKrxGraph(g.owner!!, g.use, k2) != null)
        g.eq("new capture does not reattribute old series", stamp, g.raw.krxSeriesOwners[KRX])
        // An actual REST issue uses the boundary snapshot now; no private handler or late canceled response.
        val fresh = freshAfterBoundary(g, 1777.0, AT + 1.seconds)
        g.eq("K2 owns the replacement series", fresh.krxGraphStamp, g.raw.krxSeriesOwners[KRX])
        g.eq("old K1 observations are not merged into K2", setOf(1777.0), g.krx().data.app.observations.map { it.rate }.toSet())
        g.eq("K2 inherits no old applied version", null, g.krx().lastAppliedVersion)
        g.yes("K2 inherits no old gap/handover demand", g.krx().pending.values.none {
            GraphRecoveryReason.RECEIVE_GAP in it.reasons || GraphRecoveryReason.HANDOVER_LOSS in it.reasons
        })
        val freshRaw = g.print(g.krx())
        g.recorder.applyResponse(request, response(g), old.attribution.owner, old.attribution.lifetime)
        g.eq("late K1 response cannot modify the new K2 series", freshRaw, g.print(g.krx()))
        g.eq("GENERAL and USER floor survive replacement", general to floor, g.generalPrint() to g.raw.versionFloor)
    }

    @Test fun g05_catalogWaitRetainsTheOriginalEnvelope() = s63b1Test("G05") { g, _ ->
        g.catalog = null; g.start()
        val from = g.session.inputs.size
        val one = g.session.ws(1555.0, AT, AT - 1.seconds)
        val two = g.session.ws(1560.0, AT + 2.seconds)
        val general = g.general()
        g.eq("original observation FIFO retained during catalog wait", listOf(one, two, general),
            g.raw.pending.inputs.filterIsInstance<TopicGraphInput.Observations>())
        g.eq("all actual source facts and batches keep their full original FIFO", g.session.inputs.drop(from) + general, g.raw.pending.inputs)
        val retained = g.raw.pending.inputs.toList()
        g.yes("actual resumed continuity precedes its original batch", retained.indexOf(one) > 0 &&
            retained.take(retained.indexOf(one)).any { it is TopicGraphInput.Continuity && it.kind == TopicGraphEventKind.DELIVERY_RESUMED })
        g.yes("catalog wait adopts no price", g.raw.series.isEmpty())
        g.catalog = S6KrxGraphRig.defaultCatalog(); g.recorder.replayPending()
        g.yes("catalog resolves mapping wait", g.raw.pending.inputs.isEmpty())
        g.eq("earliest valid anchor retained", one.krxGraphStamp, g.raw.krxSeriesOwners[KRX])
        g.eq("original two-time data survives replay", setOf(AT - 1.seconds, AT, AT + 2.seconds), g.krx().data.app.observations.map { it.observedAt }.toSet())
        g.yes("GENERAL replayed", g.generalKey in g.raw.series)
    }

    @Test fun g06_replayCannotRecaptureAfterRetirement() = s63b1Test("G06", "end", "D1") { g, col ->
        g.catalog = null; g.start(leaseSeconds = 600); val old = g.session.ws()
        g.eq("K1 actually waiting", listOf(old), g.raw.pending.inputs.filterIsInstance<TopicGraphInput.Observations>())
        if (col == "D1") g.cutoff(old) else { g.session.capEnd(); g.session.capReopen() }
        val issued = g.session.issued.size
        g.catalog = S6KrxGraphRig.defaultCatalog(); g.recorder.replayPending()
        g.yes("retired pending KRX not adopted", g.krxKey !in g.exposed())
        g.yes("retired pending KRX produces no new recovery", g.requests().none { it.seriesKey == g.krxKey })
        g.eq("catalog replay is not a source issuance", issued, g.session.issued.size)
        // A new real REST issue proves K2 without acceptLatest's short-lease/reconnect frame timing.
        val fresh = g.session.rest(1701.0, AT + 1.seconds)
        g.yes("actual K2 original is currently admitted", g.snapshot.admitsKrxUse(fresh.krxGraphStamp!!.scope.use))
        g.yes("fresh source has a new issue", fresh.krxGraphStamp!!.issueSequence > old.krxGraphStamp!!.issueSequence)
        g.yes("fresh K2 positive", g.krxKey in g.exposed())
    }

    @Test fun g07_recorderAdoptsOneSharedKrxSeries() = s63b1Test("G07",
        "REST-before-WS", "WS-before-REST", "WS-before-older-REST", "REST-before-older-WS") { g, col ->
        g.start(); g.general(); val general = g.generalPrint()
        val firstAt = if ("older" in col) AT else AT - 1.seconds
        val secondAt = if ("older" in col) AT - 1.seconds else AT
        val restFirst = col.startsWith("REST-")
        val first = if (restFirst) g.session.rest(1500.0, firstAt) else g.session.ws(1500.0, firstAt)
        g.yes("first actual source adopted", g.krxKey in g.raw.series)
        val second = if (restFirst) g.session.ws(1560.0, secondAt) else g.session.rest(1560.0, secondAt)
        g.yes("two logical source issues differ", second.krxGraphStamp!!.issueSequence != first.krxGraphStamp!!.issueSequence)
        g.eq("same valid namespace/use keeps the original anchor", first.krxGraphStamp, g.raw.krxSeriesOwners[KRX])
        g.eq("out-of-order delivery retains both original prices", setOf(1500.0, 1560.0), g.krx().data.app.observations.map { it.rate }.toSet())
        g.eq("two original times survive", setOf(firstAt, secondAt), g.krx().data.app.observations.map { it.observedAt }.toSet())
        val beforeDuplicate = g.print(g.krx()); g.recorder.observe(first); g.recorder.observe(second)
        g.eq("duplicates do not reset or revise the common series", beforeDuplicate, g.print(g.krx()))
        val request = g.originalRequest()
        g.recorder.applyResponse(request, response(g), first.attribution.owner, first.attribution.lifetime)
        g.eq("valid response supplies one shared applied version", request.applicationVersion, g.krx().lastAppliedVersion)
        val views = listOf("usd", "tether").map { tab ->
            val ids = g.catalog!!.tabs.getValue(tab).periods.getValue(GraphPeriod.ONE_DAY).allSeries
            val shared = g.exposed().filterKeys { it.seriesId in ids && it.seriesId == KRX }
            g.eq("$tab resolves exactly the common key", setOf(g.krxKey), shared.keys)
            shared.getValue(g.krxKey)
        }
        assertSame(g.c("USD/tether read the same shared data/version object"), views[0], views[1])
        g.eq("both views have the same applied version", views[0].lastAppliedVersion, views[1].lastAppliedVersion)
        g.eq("both views have identical buckets and recovery", g.print(views[0]), g.print(views[1]))
        g.eq("one shared KRX key", 1, g.raw.series.keys.count { it.seriesId == KRX })
        g.eq("GENERAL preserved across KRX-only recovery", general, g.generalPrint())
        g.yes("GENERAL prices remain", g.raw.series[g.generalKey]!!.data.app.observations.any { it.rate == 1444.0 })
    }

    @Test fun g08_exposureRechecksAfterPreparation() = s63b1Test("G08", "prepare", "ingress-only", "end") { g, col ->
        g.start(); val input = g.adopt(); publication(g, input, col)
    }

    @Test fun g09_recoveryCaptureKeepsOriginalOwnership() = s63b1Test("G09", "hold", "epoch", "D1") { g, col ->
        g.start(); val old = g.adopt(); g.general()
        g.recorder.observe(g.fact(old))
        val original = g.originalRequest(); val copied = original.copy()
        g.eq("request carries original series anchor", old.krxGraphStamp, original.krxGraphStamp)
        if (col == "D1") g.cutoff(old) else g.boundaryRoundTrip(col, old.krxGraphStamp!!)
        val next = g.requests()
        g.yes("old demand cannot initiate request", next.none { it.seriesKey == g.krxKey })
        g.yes("GENERAL recovery still available", next.any { it.seriesKey == g.generalKey })
        g.eq("retained request remains original", copied, original)
    }

    @Test fun g10_completedProducerCannotApplyAfterCutoff() = s63b1Test("G10") { g, _ -> completion(g, false) }

    @Test fun g12_topicUnavailableDiffersFromWithheldAndRest() = s63b1Test("G12", "D1", "degraded", "dormant", "gap", "lease", "hold") { g, col ->
        g.start(leaseSeconds = 1); val old = g.adopt(); g.general()
        val before = listOf(g.r.snapshot.krxPermitSeq, g.r.snapshot.facts.recordFence, g.r.h.store.record,
            g.r.h.source.calls.size, g.generalPrint())
        val rev = g.session.fence.revisions.value; val calls = g.session.cutoffs.size
        when (col) {
            "D1" -> g.session.d1()
            "degraded", "dormant" -> {
                val call = g.session.cleanup.issue()
                g.session.cleanup.f.reply(call, if (col == "degraded") KrxA1bFixture.Reply(404, """{"error":"topic_unavailable"}""") else KrxA1bFixture.Reply(404, """{"error":"topics_disabled"}"""))
                g.yes("REST producer ended normally", call.outcome != null && !call.cancelled)
            }
            "gap" -> g.recorder.observe(g.fact(old))
            "lease" -> g.r.advance(1_001)
            else -> g.session.cleanup.hold()
        }
        g.eq("only verified D1 invokes ingress", calls + if (col == "D1") 1 else 0, g.session.cutoffs.size)
        g.eq("only D1 advances cutoff revision", rev + if (col == "D1") 1 else 0, g.session.fence.revisions.value)
        if (col == "D1") {
            g.yes("old use blocked in unchanged capability", g.krxKey !in g.exposed())
            g.eq("D1 changes no PAC/epoch/journal/FE/GENERAL", before,
                listOf(g.r.snapshot.krxPermitSeq, g.r.snapshot.facts.recordFence, g.r.h.store.record,
                    g.r.h.source.calls.size, g.generalPrint()))
        }
    }

    private suspend fun extrema(g: S6KrxGraphRig, high: Boolean) {
        g.start(); val input = g.adopt(1500.0)
        val coordinator = g.readyCoordinator()
        coordinator.onActivated(GraphKey("usd", GraphPeriod.ONE_DAY)); g.test.runCurrent()
        val call = g.need(g.sent.lastOrNull { it.key.period == GraphPeriod.ONE_DAY }, "real REST request held")
        g.yes("inside REST wait", !call.response.isCompleted && !call.returned)
        g.session.ws(if (high) 1900.0 else 1100.0, AT + 1.seconds)
        val before = g.krx().data.app.buckets
        call.response.complete(S6KrxGraphRig.ok(S6KrxGraphRig.tabDto(call.key, 1500.0))); g.test.runCurrent()
        g.yes("valid response application positive", call.returned && g.krx().lastAppliedVersion != null)
        g.eq("original anchor survives distinct request/source sequence", input.krxGraphStamp, g.raw.krxSeriesOwners[KRX])
        g.eq("observed ${if (high) "high" else "low"} buckets survive REST", before, g.krx().data.app.buckets)
    }
    @Test fun g13_pendingRestKeepsObservedHigh() = s63b1Test("G13") { g, _ -> extrema(g, true) }
    @Test fun g14_pendingRestKeepsObservedLow() = s63b1Test("G14") { g, _ -> extrema(g, false) }

    @Test fun g16_transientGapKeepsRecoveryDemand() = s63b1Test("G16", "gap", "resume", "lease", "REST-wait", "D1", "end", "hold") { g, col ->
        g.start(leaseSeconds = if (col == "lease") 1 else 600)
        val input = g.adopt(at = AT - 10.minutes); g.general()
        // A real interruption followed by a real resumed observation establishes the closed demand first.
        val from = g.session.inputs.size; val wires = g.r.wires.size
        g.r.wire.drop(); g.r.drain()
        g.yes("actual WS end produced continuity before recovery", g.session.inputs.drop(from).filterIsInstance<TopicGraphInput.Continuity>()
            .any { it.kind == TopicGraphEventKind.DELIVERY_INTERRUPTED && input.topic in it.topics })
        g.r.until(g.c("new connection for actual resumed producer"), 2_600) { g.r.wires.size == wires + 1 }
        g.r.open(); g.r.ack(g.session.cleanup.lastKrxRequest(), seconds = if (col == "lease") 1 else 600)
        g.session.ws(1556.0, AT - 9.minutes)
        g.yes("actual resumed producer ran", g.session.inputs.drop(from).filterIsInstance<TopicGraphInput.Continuity>()
            .any { it.kind == TopicGraphEventKind.DELIVERY_RESUMED && input.topic in it.topics })
        val pending = g.krx().pending.toMap()
        g.yes("T11 holds actual closed recovery demand", AT - 10.minutes in pending)
        val oldRequest = g.originalRequest()
        if (col in setOf("D1", "end", "hold")) {
            if (col == "D1") g.cutoff(input)
            else if (col == "end") { g.session.capEnd(); g.session.capReopen() }
            else g.boundaryRoundTrip("hold", input.krxGraphStamp!!)
            g.yes("old demand cannot initiate another request", g.requests().none { it.seriesKey == g.krxKey })
            val fresh = freshAfterBoundary(g, 1778.0, AT + 2.seconds)
            g.eq("fresh K2 owns its own data", fresh.krxGraphStamp, g.raw.krxSeriesOwners[KRX])
            g.eq("fresh K2 has no old prices", setOf(1778.0), g.krx().data.app.observations.map { it.rate }.toSet())
            g.yes("fresh K2 has no old receive-gap or handover demand", g.krx().pending.values.none {
                GraphRecoveryReason.RECEIVE_GAP in it.reasons || GraphRecoveryReason.HANDOVER_LOSS in it.reasons
            })
            val current = g.print(g.krx())
            g.recorder.applyResponse(oldRequest, response(g), input.attribution.owner, input.attribution.lifetime)
            g.eq("old demand response cannot transfer to K2", current, g.print(g.krx()))
        } else {
            if (col == "lease") {
                val lease = g.session.cleanup.registry
                val deadline = g.need(lease.earliestExpiryMillis(), "actual KRX lease deadline")
                g.r.advance((deadline - g.r.clock.nowMillis()).coerceAtLeast(0) + 1)
                g.yes("inside actual lease-expired window", TopicCatalogue.KRX_FUTURES !in lease.held ||
                    TopicCatalogue.KRX_FUTURES in lease.expiredAt(g.r.clock.nowMillis()))
            } else if (col == "resume") g.session.ws(1557.0, AT - 8.minutes)
            val coordinator = g.readyCoordinator()
            coordinator.onActivated(GraphKey("usd", GraphPeriod.ONE_DAY)); g.test.runCurrent()
            val call = g.need(g.sent.lastOrNull { it.key.period == GraphPeriod.ONE_DAY }, "held valid recovery HTTP")
            g.yes("inside actual HTTP wait", !call.returned && !call.cancelled && !call.response.isCompleted)
            g.yes("transient gap/lease/resume/REST wait preserves T11", g.krx().pending.keys.containsAll(pending.keys))
            call.response.complete(S6KrxGraphRig.ok(S6KrxGraphRig.tabDto(call.key))); g.test.runCurrent()
            g.yes("valid response normally applied", call.returned && g.krx().lastAppliedVersion != null)
            g.yes("covered closed demand clears only after valid response", AT - 10.minutes !in g.krx().pending)
        }
        g.yes("GENERAL remains readable", g.generalKey in g.exposed())
    }

    @Test fun g18_generalOnlyAckDoesNotEraseGraphOwnership() = s63b1Test("G18", "D1", "end", "current-canonical-ACK") { g, col ->
        g.start(); val input = g.adopt(); val stamp = input.krxGraphStamp
        val original = g.print(g.krx()).take(4)
        if (col == "current-canonical-ACK") {
            // M12 route: a real current USD-only request, without replacing the KRX-owning session.
            g.r.generalTick()
            val connection = g.session.cleanup.live
            val canonical = g.need(field(g.session.cleanup.session, "krxCanonicalScope") as? KrxMemoryScope,
                "KRX ACK established canonical scope on this session")
            g.eq("canonical scope is the original source scope", stamp!!.scope, canonical)
            g.yes("original canonical use and exposed KRX live before GENERAL request",
                g.snapshot.admitsKrxUse(canonical.use) && g.session.fence.permits(stamp) && g.krxKey in g.exposed())
            val usd = "fx:usd-krw"
            g.r.store.markDegraded(usd)
            g.eq("only USD is manual-eligible on its tab", setOf(usd),
                (TopicBootstrapOrder.shownBy(FreeTab.USD) + TopicCatalogue.KRX_FUTURES)
                    .filter { it in g.r.store.snapshot.manualRetryTopics }.toSet())
            val sent = g.r.sends.size
            g.r.manual(FreeTab.USD)
            g.r.until(g.c("current GENERAL-only request reaches physical wire"), 750) {
                g.r.sends.drop(sent).any { it.accepted && it.request.type == "subscribe" }
            }
            val request = g.need(g.r.sends.drop(sent).filter { it.accepted && it.request.type == "subscribe" }
                .singleOrNull()?.request, "one actual current GENERAL-only request")
            g.yes("inside current GENERAL-only ACK window with preserved canonical scope",
                request.topics.toSet() == setOf(usd) && g.session.cleanup.live === connection &&
                    field(connection, "opened") == true && field(connection, "ended") == false &&
                    field(g.session.cleanup.session, "krxCanonicalScope") == canonical &&
                    g.r.store.snapshot.stateFor(TopicCatalogue.KRX_FUTURES).confirmed)
            val revision = g.session.fence.revisions.value
            val cutoffs = g.session.cutoffs.size
            val from = g.session.inputs.size
            val entries = request.topics.joinToString(",") {
                """{"topic":"$it","lease_id":"${request.requestId}-$it","lease_duration_seconds":600}"""
            }
            // Normal whole-connection answer to the current request, omitting KRX from accepted/rejected/active.
            g.r.wire.deliver("""{"type":"subscription_ack","request_id":"${request.requestId}","operation":"subscribe","accepted_topics":[$entries],"rejected_topics":[],"removed_topics":[],"active_subscriptions":[$entries]}""")
            g.r.drain()
            g.yes("GENERAL-only ACK really processed before behavior checks",
                g.session.inputs.drop(from).filterIsInstance<TopicGraphInput.Continuity>().any {
                    it.kind == TopicGraphEventKind.ACK_ACTIVE_SET_CHANGED &&
                        (it.payload as? TopicGraphEventPayload.AckApplied)?.let { payload ->
                            usd in payload.confirmedTopics && payload.rejected.isEmpty()
                        } == true
                } && g.session.cleanup.generalRegistry.held[usd]?.leaseId == "${request.requestId}-$usd")
            // No further input between this ACK and the positive contract checks.
            g.eq("current GENERAL-only ACK leaves cutoff revision unchanged", revision, g.session.fence.revisions.value)
            g.eq("current GENERAL-only ACK never invokes early D1", cutoffs, g.session.cutoffs.size)
            g.yes("old stamp still permitted immediately after current GENERAL-only ACK", g.session.fence.permits(stamp))
            g.eq("current GENERAL-only ACK preserves original series attribution", stamp, g.raw.krxSeriesOwners[KRX])
            g.eq("current GENERAL-only ACK preserves original values", original, g.print(g.krx()).take(4))
            g.yes("original KRX remains exposed immediately after current GENERAL-only ACK", g.krxKey in g.exposed())
            return@s63b1Test
        }
        // Stop only the old TSC (not PAC or recorder), then construct a GENERAL-only TSC on the very same grant.
        // This removes actual rates/active captures instead of clearing private fields in a fixture.
        val ended = g.session.cleanup.session
        ended.stop(); g.r.drain()
        g.yes("original active connection really retired", field(ended, "connection") == null)
        val runtime = g.replacementSession(TopicCatalogue.DESIRED)
        val request = g.r.sends.last().request
        g.yes("replacement SENT is GENERAL-only", TopicCatalogue.KRX_FUTURES !in request.topics)
        val reads = g.session.cutoffs.size
        g.r.ack(request)
        g.yes("replacement contains no KRX rates", !S6KrxCleanupRig.hasKrx(g.r.rates) && !S6KrxCleanupRig.hasKrx(g.session.cleanup.liveRates))
        g.eq("replacement contains no KRX active capture", null, field(g.session.cleanup.live, "krxActive"))
        g.eq("replacement contains no KRX pending capture", null, field(g.session.cleanup.live, "krxPending"))
        g.eq("replacement has no KRX lease", emptyMap<Any, Any>(), g.session.cleanup.registry.held)
        g.eq("GENERAL-only ACK must not call D1", reads, g.session.cutoffs.size)
        val general = g.session.cleanup.generalRegistry.held.toMap()
        g.yes("GENERAL leases are positively established", general.isNotEmpty())
        g.eq("original graph-only ownership retained", stamp, g.raw.krxSeriesOwners[KRX])
        g.eq("original graph-only prices retained", original, g.print(g.krx()).take(4))
        g.yes("graph-only original exposed before retirement", g.krxKey in g.exposed())
        if (col == "D1") g.cutoff(input) else g.session.capEnd()
        g.yes("old graph-only stamp denied after retirement", g.krxKey !in g.exposed())
        g.eq("GENERAL lease state preserved", general, g.session.cleanup.generalRegistry.held)
        runtime.setOnline(false); g.r.drain()
    }

    @Test fun g20_unknownTopicPurgeCannotSelectForeignOrCurrent() = s63b1Test("G20") { g, _ ->
        g.start(); val input = g.adopt(); val stamp = input.krxGraphStamp!!
        val original = stamp.scope
        val bad = listOf(null, original.copy(owner = original.owner.copy(identity = AuthIdentityFence("", 1))),
            original.copy(owner = original.owner.copy(userAccessEpoch = null)),
            original.copy(use = original.use.copy(krxCapabilityEpoch = null)))
        for (scope in bad) {
            val rev = g.session.fence.revisions.value
            val result = g.session.ingress.topicUnavailable(scope)
            g.eq("unknown has no namespace selection", null, result.namespace)
            g.eq("unknown is explicitly unresolved", KrxGraphTopicResult.UNRESOLVED, result.result)
            g.eq("unknown cannot advance cutoff", rev, g.session.fence.revisions.value)
            g.yes("unknown does not retire the last issued scope", g.session.fence.permits(stamp))
        }
        val foreign = original.copy(owner = original.owner.copy(identity = AuthIdentityFence("foreign", 2)))
        val result = g.session.ingress.topicUnavailable(foreign)
        g.eq("known foreign namespace only", foreign.owner.identity, result.namespace?.identity)
        g.yes("foreign cutoff leaves current original admitted", g.session.fence.permits(stamp))
        // boundary-input: explicit synthetic envelope using the original source attribution.
        val unattributed = TopicGraphInput.Observations(input.sequence + 100, input.topic, input.path,
            input.attribution, input.connectionGeneration, input.candidates)
        val before = g.print(g.krx()); g.recorder.observe(unattributed)
        g.eq("null original cannot adopt or add recovery", before, g.print(g.krx()))
    }

    @Test fun g21_cutoffIgnoresDataQueueCapacityAndStartState() = s63b1Test("G21", "FULL", "CLOSED", "deferred", "dequeued") { g, col ->
        g.start(); val old = g.adopt(); val general = g.general()
        val originalGeneral = g.generalPrint()
        val consumer = object : GraphTopicInputConsumer {
            override fun observe(input: TopicGraphInput.Observations) {
                if (col == "dequeued" && input === old) g.cutoff(old)
                g.recorder.observe(input)
            }
            override fun observe(input: TopicGraphInput.Continuity) = g.recorder.observe(input)
            override fun loseTopics(ownerScope: GraphDataScope, maxInvalidationsByTopic: Map<String, Long>) = g.recorder.loseTopics(ownerScope, maxInvalidationsByTopic)
            override fun loseKrxTopics(stampsByTopic: Map<String, Set<KrxGraphStamp>>) = g.recorder.loseKrxTopics(stampsByTopic)
            override fun close() = Unit
        }
        val sink = GraphRecorderTopicSink(g.scope, consumer, startImmediately = false)
        try {
            if (col == "CLOSED") sink.close()
            else if (col == "FULL") repeat(512) { g.eq("queue filled before D1", TopicGraphOffer.ENQUEUED, sink.tryOffer(g.fact(general))) }
            val offered = sink.tryOffer(old)
            g.eq("inside requested queue window", when (col) { "FULL" -> TopicGraphOffer.FULL; "CLOSED" -> TopicGraphOffer.CLOSED; else -> TopicGraphOffer.ENQUEUED }, offered)
            if (col != "dequeued") g.cutoff(old)
            sink.start(); g.test.runCurrent()
            g.yes("cutoff independent of queue/start state", !g.session.fence.permits(old.krxGraphStamp!!))
            g.yes("old cannot expose or recapture after resumed drain", g.krxKey !in g.exposed() && g.requests().none { it.seriesKey == g.krxKey })
            g.eq("GENERAL observed prices preserved", originalGeneral.mapValues { it.value.take(4) }, g.generalPrint().mapValues { it.value.take(4) })
        } finally { sink.close() }
    }

    @Test fun g22_cutoffOrderingPreservesTheNextIssue() = s63b1Test("G22") { g, _ ->
        g.start(); val old = g.adopt(); val stamp = old.krxGraphStamp!!
        val result = g.cutoff(old)
        g.eq("cutoff includes latest issue equality", stamp.issueSequence, result.throughIssueSequence)
        g.yes("equal issue rejected", !g.session.fence.permits(stamp))
        val next = g.need(g.session.fence.capture(stamp.scope), "next actual source issuance")
        g.yes("next issue strictly newer", next.issueSequence > result.throughIssueSequence)
        g.yes("same namespace next issue admitted", g.session.fence.permits(next))
        val foreign = g.need(g.session.fence.capture(stamp.scope.copy(owner = stamp.scope.owner.copy(identity = AuthIdentityFence("other", 2)))), "foreign issue")
        g.yes("foreign namespace remains admitted", g.session.fence.permits(foreign))
        val oldAck = g.session.cleanup.lastKrxRequest(); val calls = g.session.cutoffs.size
        g.session.cleanup.unavailable(oldAck)
        g.eq("late ACK does not reach ingress", calls, g.session.cutoffs.size)
        g.eq("delayed processing cannot move an installed cutoff", result.throughIssueSequence, g.session.cutoffs.last().second.throughIssueSequence)
    }

    @Test fun g23_mixedLossRetainsGeneralAccounting() = s63b1Test("G23",
        "FULL-D1", "FULL-hold", "CLOSED-D1", "CLOSED-hold", "overflow-D1", "overflow-hold",
        "pending-eviction-D1", "pending-eviction-hold", "detached-ledger-D1", "detached-ledger-hold", "FAILED-D1", "FAILED-hold", "FAILED-GENERAL") { g, col ->
        g.start(); val old = g.adopt(); val general = g.general()
        if (col in setOf("FAILED-D1", "FAILED-hold")) {
            endingLoss(g, col, old.krxGraphStamp, pricesPresent = true)
            return@s63b1Test
        }
        val oldRaw = g.print(g.krx()); val floor = g.raw.versionFloor
        var retired = false
        fun retire() {
            if (retired) return
            if (col.endsWith("-hold")) g.boundaryRoundTrip("hold", old.krxGraphStamp!!) else g.cutoff(old)
            retired = true
        }
        if (col.startsWith("pending-eviction")) {
            g.catalog = null
            repeat(513) { g.session.ws(1555.0 + it % 2, AT + 1.seconds) }
            g.yes("real KRX batches evicted with their original stamp", g.raw.pending.krxLost[old.topic]?.contains(old.krxGraphStamp) == true)
            g.general(AT + 2.seconds, 1445.0)
            val before = g.raw.pending
            val frozenLoss = before.krxLost.mapValues { it.value.toSet() }
            retire(); g.catalog = S6KrxGraphRig.defaultCatalog(); g.recorder.replayPending()
            g.eq("old evicted/held inputs do not create KRX demand", oldRaw, g.print(g.krx()))
            g.eq("detached pending loss map remains immutable", frozenLoss, before.krxLost)
            g.yes("GENERAL pending survivor is adopted", g.raw.series[g.generalKey]!!.data.app.observations.any { it.rate == 1445.0 })
            g.yes("old pending loss never captures recovery", g.requests().none { it.seriesKey == g.krxKey })
        } else {
            val delivered = mutableListOf<TopicGraphInput>()
            val generalLoss = mutableListOf<Pair<GraphDataScope, Map<String, Long>>>()
            val krxLoss = mutableListOf<Map<String, Set<KrxGraphStamp>>>()
            val consumer = object : GraphTopicInputConsumer {
                override fun observe(input: TopicGraphInput.Observations) { delivered += input; g.recorder.observe(input) }
                override fun observe(input: TopicGraphInput.Continuity) { delivered += input; g.recorder.observe(input) }
                override fun loseTopics(ownerScope: GraphDataScope, maxInvalidationsByTopic: Map<String, Long>) {
                    generalLoss += ownerScope to maxInvalidationsByTopic.toMap()
                    g.recorder.loseTopics(ownerScope, maxInvalidationsByTopic)
                }
                override fun loseKrxTopics(stampsByTopic: Map<String, Set<KrxGraphStamp>>) {
                    // The sink has already taken this map out of its ledger before this real consumer call.
                    krxLoss += stampsByTopic.mapValues { it.value.toSet() }
                    if (col.startsWith("detached-ledger")) retire()
                    g.recorder.loseKrxTopics(stampsByTopic)
                }
                override fun close() = Unit
            }
            val sink = GraphRecorderTopicSink(g.scope, consumer, startImmediately = false)
            // boundary-input: explicit synthetic envelope using the original source attribution.
            val full = TopicGraphInput.Observations(9000, general.topic, general.path, general.attribution,
                general.connectionGeneration, List(512) { general.candidates.single() })
            try {
                if (col == "FAILED-GENERAL") {
                    // boundary-input: explicit synthetic envelope using the original source attribution.
                    val broken = TopicGraphInput.Observations(9001, general.topic, general.path, general.attribution, 1,
                        object : AbstractList<TopicGraphCandidate>() {
                            override val size: Int get() = throw IllegalArgumentException("injected candidate-count failure")
                            override fun get(index: Int) = general.candidates.single()
                        })
                    g.eq("actual sink exception returns FAILED", TopicGraphOffer.FAILED, sink.tryOffer(broken))
                    g.eq("FAILED consumes no queue units", 0, field(sink, "usedUnits"))
                    sink.start(); g.test.runCurrent()
                    g.yes("FAILED GENERAL loss reaches actual consumer", generalLoss.any { general.topic in it.second })
                } else {
                    if (col.startsWith("CLOSED")) sink.close()
                    else {
                        g.eq("whole 512-unit GENERAL batch accepted before loss", TopicGraphOffer.ENQUEUED, sink.tryOffer(full))
                        g.eq("inside exact 512-unit full window", 512, field(sink, "usedUnits"))
                    }
                    g.session.target = sink
                    val lossAt = 1_000_000L + g.r.clock.nowMillis()
                    if (col.startsWith("overflow")) {
                        // boundary-input: explicit synthetic envelope using the original source attribution.
                        val oversized = TopicGraphInput.Observations(9002, old.topic, old.path, old.attribution, old.connectionGeneration,
                            List(513) { old.candidates.single() }, old.krxGraphStamp)
                        g.eq("whole KRX observation overflow is FULL", TopicGraphOffer.FULL, sink.tryOffer(oversized))
                        g.eq("oversize neither evicts nor charges the GENERAL queue", 512, field(sink, "usedUnits"))
                    }
                    g.session.ws(1556.0, AT + 1.seconds)
                    val entry = """{"source":"investing","asset":"usd-krw","rate":1445.0,"timestamp":"$AT"}"""
                    g.session.deliver("""{"type":"snapshot","version":1,"topic":"fx:usd-krw","data":{"banks":[$entry]}}""")
                    val from = g.session.inputs.size
                    g.r.wire.drop(); g.r.drain()
                    val mixed = g.need(g.session.inputs.drop(from).filterIsInstance<TopicGraphInput.Continuity>()
                        .firstOrNull { old.topic in it.topics && general.topic in it.topics }, "actual mixed WS-end continuity producer")
                    g.eq("mixed continuity keeps original KRX authority", old.krxGraphStamp, mixed.authority.krxGraphStamp)
                    val result = if (col.startsWith("CLOSED")) TopicGraphOffer.CLOSED else TopicGraphOffer.FULL
                    val lost = g.session.offers.filter { it.second == result }
                    g.yes("both observed and continuity batches were actually lost", lost.size >= 3)
                    val sessionLoss = g.need(g.session.cleanup.session.graphLoss.value, "actual session cumulative loss view")
                    g.eq("GENERAL/mixed whole-batch accounting", lost.size.toLong(), sessionLoss.count)
                    g.eq("first loss sequence preserved", lost.first().first.sequence, sessionLoss.firstSequence)
                    g.eq("last loss sequence/high-water preserved", lost.last().first.sequence, sessionLoss.lastSequence)
                    g.eq("loss reason is exact", setOf(result), sessionLoss.reasons)
                    g.eq("session loss revision advances exactly once per batch", lost.size.toLong(), sessionLoss.revision)
                    g.eq("first loss uses the original loss clock", lossAt, sessionLoss.firstOccurredAtEpochMillis)
                    g.eq("last loss clock is not replaced on drain", lossAt, sessionLoss.lastOccurredAtEpochMillis)
                    g.yes("both original topics accounted", setOf(old.topic, general.topic).all { it in sessionLoss.topics })
                    g.yes("original GENERAL authority accounted", TopicGraphAuthorityKey(general.attribution.owner, general.attribution.grantEpoch) in sessionLoss.authorities)
                    if (!col.startsWith("detached-ledger")) retire()
                    sink.start(); g.test.runCurrent()
                    if (col.startsWith("CLOSED")) g.eq("CLOSED delivers no queued work", emptyList<TopicGraphInput>(), delivered)
                    else {
                        g.yes("real sink forwards the original loss stamp", krxLoss.any { it[old.topic]?.contains(old.krxGraphStamp) == true })
                        g.yes("GENERAL original scope and invalidation preserved", generalLoss.any {
                            it.first == g.dataScope && it.second[general.topic] == general.attribution.lifetime.invalidations
                        })
                        g.eq("GENERAL queue was neither evicted nor reordered", listOf(full), delivered)
                        g.eq("drain refunds all 512 units", 0, field(sink, "usedUnits"))
                        g.eq("new GENERAL item accepted after refund", TopicGraphOffer.ENQUEUED, sink.tryOffer(general))
                        g.test.runCurrent()
                        g.eq("GENERAL FIFO survives loss drain", listOf(full, general), delivered)
                    }
                    g.yes("inside original-use retirement window before post-drain assertions", retired)
                    g.eq("old mixed loss cannot change raw KRX buckets/demand/version", oldRaw, g.print(g.krx()))
                    if (col.endsWith("-hold")) {
                        g.eq("transient hold preserves current loss", sessionLoss, g.session.cleanup.session.graphLoss.value)
                    } else {
                        // Explicit shared ledger API: this isolated ingress fixture does not claim TSC loop publication.
                        val ledger = field(g.session.cleanup.session, "graphLossLedger") as TopicGraphLossLedger
                        ledger.purgeKrxMemory(KrxGraphPurgePlan.forSnapshot(g.snapshot, g.session.fence.memoryCutoffs()))
                        val currentLoss = g.need(ledger.view, "GENERAL loss survives current-ledger cleanup")
                        g.yes("current selected KRX loss physically removed", currentLoss.krxLost.values.none { old.krxGraphStamp in it })
                        g.yes("current GENERAL topics survive", general.topic in currentLoss.topics)
                        g.eq("one revision for selected physical deletion", sessionLoss.revision + 1, currentLoss.revision)
                        g.yes("detached pre-purge snapshot still owns original", sessionLoss.krxLost.values.any { old.krxGraphStamp in it })
                    }
                    g.yes("old loss cannot initiate a new KRX request", g.requests().none { it.seriesKey == g.krxKey })
                }
            } finally { sink.close() }
        }
        g.eq("USER version floor preserved", floor, g.raw.versionFloor)
        g.yes("GENERAL remains readable", g.generalKey in g.exposed())
    }

    @Test fun g24_releasedRegistrationStillRejectsOldCompletion() = s63b1Test("G24") { g, _ -> completion(g, true) }

    @Test fun g25_envelopesAndCutoffsAreImmutable() = s63b1Test("G25", "collections", "unknown", "overflow", "loss-constructor") { g, col ->
        g.start(); val input = g.adopt(); val stamp = input.krxGraphStamp!!
        if (col == "loss-constructor") {
            val nextStamp = g.need(g.session.fence.capture(stamp.scope), "second original for caller set mutations")
            val callerSet = mutableSetOf(stamp)
            val callerMap: MutableMap<String, Set<KrxGraphStamp>> = linkedMapOf(input.topic to callerSet)
            // boundary-input: shared internal loss constructor, with the same original as actual source/ledger loss.
            // Ledger buildView owns its input map already; this boundary deliberately keeps the mutable caller aliases.
            val loss = TopicGraphLoss(1L, 1L, input.sequence, input.sequence, setOf(TopicGraphOffer.FULL),
                setOf(input.topic), setOf(input.path), 123L, 123L,
                setOf(TopicGraphAuthorityKey(input.attribution.owner, input.attribution.grantEpoch)), callerMap)
            val expected = mapOf(input.topic to setOf(stamp))
            g.eq("constructor path actually stores the original sidecar before caller mutations", expected, loss.krxLost)
            callerMap["caller-added-topic"] = mutableSetOf(nextStamp)
            g.eq("loss constructor owns outer map after caller key addition", expected, loss.krxLost)
            callerMap.remove(input.topic)
            g.eq("loss constructor owns outer map after caller key removal", expected, loss.krxLost)
            callerSet.add(nextStamp)
            g.eq("loss constructor owns inner set after caller stamp addition", expected, loss.krxLost)
            callerSet.remove(stamp)
            g.eq("loss constructor owns inner set after caller stamp removal", expected, loss.krxLost)
            return@s63b1Test
        }
        if (col == "collections") {
            val candidates = input.candidates.toMutableList()
            // boundary-input: explicit synthetic envelope using the original source attribution.
            val envelope = TopicGraphInput.Observations(input.sequence, input.topic, input.path, input.attribution,
                input.connectionGeneration, candidates, stamp)
            val before = envelope.candidates.toList(); candidates.clear()
            g.eq("candidate list defensively copied", before, envelope.candidates)
            var pending = GraphPendingInputs.EMPTY
            repeat(513) { pending = offerGraphPendingInput(pending, envelope) }
            g.yes("eviction stores original KRX loss stamp", pending.krxLost[input.topic]?.contains(stamp) == true)
            val frozen = pending
            val lost = frozen.krxLost.mapValues { it.value.toSet() }
            val nextStamp = g.need(g.session.fence.capture(stamp.scope), "next source stamp for independent eviction")
            // boundary-input: explicit synthetic envelope using the original source attribution.
            val newer = TopicGraphInput.Observations(input.sequence + 1, input.topic, input.path, input.attribution,
                input.connectionGeneration, input.candidates, nextStamp)
            repeat(513) { pending = offerGraphPendingInput(pending, newer) }
            g.yes("new evictions add the second original", pending.krxLost[input.topic]?.contains(nextStamp) == true)
            g.eq("later pending transition cannot mutate an earlier loss sidecar", lost, frozen.krxLost)
            g.catalog = null
            val callerSet = mutableSetOf(stamp)
            val callerMap: MutableMap<String, Set<KrxGraphStamp>> = mutableMapOf(input.topic to callerSet)
            g.recorder.loseKrxTopics(callerMap)
            val heldMap = g.raw.pending.krxLost
            g.yes("valid loss map actually stored before alias mutation", heldMap[input.topic]?.contains(stamp) == true)
            val heldCopy = heldMap.mapValues { it.value.toSet() }
            callerSet.clear(); callerMap.clear()
            g.eq("loss map/set inputs are defensively owned", heldCopy, heldMap)
            g.cutoff(input)
            g.eq("cutoff does not mutate an already detached immutable loss map", lost, frozen.krxLost)
            // boundary-input: explicit synthetic envelope using the original source attribution.
            val legacy = TopicGraphInput.Observations(input.sequence, input.topic, input.path, input.attribution,
                input.connectionGeneration, input.candidates)
            g.eq("public legacy constructor has null original", null, legacy.krxGraphStamp)
            val oldAuthority = TopicGraphAuthority(input.attribution.sessionKey, input.attribution.owner,
                input.attribution.grantEpoch, input.attribution.lifetime)
            g.eq("old authority constructor has no original stamp", null, oldAuthority.krxGraphStamp)
            val topics = mutableSetOf(input.topic, "fx:usd-krw")
            val paths = mutableSetOf(TopicGraphPath.WS, TopicGraphPath.REST_BOOTSTRAP)
            val confirmed = topics.toMutableSet()
            val rejected = mutableMapOf("fx:eur-krw" to TopicRejectionReason.TOPIC_UNAVAILABLE)
            // boundary-input: explicit synthetic envelope using the original source attribution.
            val fact = TopicGraphInput.Continuity(2000, TopicGraphEventKind.ACK_ACTIVE_SET_CHANGED, null,
                topics, paths, TopicGraphAuthority(input.attribution.sessionKey, input.attribution.owner,
                    input.attribution.grantEpoch, input.attribution.lifetime, stamp), 1, 123,
                TopicGraphEventPayload.AckApplied(confirmed, rejected))
            val expectedTopics = topics.toSet(); val expectedPaths = paths.toSet()
            val expectedConfirmed = confirmed.toSet(); val expectedRejected = rejected.toMap()
            topics.clear(); paths.clear(); confirmed.clear(); rejected.clear()
            g.eq("continuity copies topic set", expectedTopics, fact.topics)
            g.eq("continuity copies path set", expectedPaths, fact.paths)
            val payload = fact.payload as TopicGraphEventPayload.AckApplied
            g.eq("continuity copies ACK confirmed set", expectedConfirmed, payload.confirmedTopics)
            g.eq("continuity copies ACK rejection map", expectedRejected, payload.rejected)
            g.eq("authority retains the immutable original", stamp, fact.authority.krxGraphStamp)
        } else if (col == "unknown") {
            val rev = g.session.fence.revisions.value
            val unknown = stamp.scope.copy(owner = stamp.scope.owner.copy(userAccessEpoch = ""))
            g.eq("empty namespace cannot issue", null, g.session.fence.capture(unknown))
            g.eq("empty namespace cutoff unresolved", KrxGraphTopicResult.UNRESOLVED, g.session.fence.topicUnavailable(unknown).result)
            g.eq("unknown changes no revision", rev, g.session.fence.revisions.value)
        } else {
            // boundary-input: state injection only; a missing counter is a premise failure, never a fake contract kill.
            setField(g.session.fence, "nextIssueSequence", Long.MAX_VALUE)
            var failed = false
            try { val issued = g.session.fence.capture(stamp.scope); failed = issued == null }
            catch (_: ArithmeticException) { failed = true }
            catch (_: IllegalStateException) { failed = true }
            g.yes("sequence overflow fails closed without wrapping/reusing", failed)
        }
        g.eq("original immutable scope unchanged", stamp.scope, input.krxGraphStamp!!.scope)
    }

    @Test fun g26_realAssemblyWiresIngressAndDetaches() = s63b1Test("G26", "actual-D1", "ingress-only", "detach", "close", "start-failure", "reassembly") { g, col ->
        if (col == "ingress-only") {
            g.start(); val old = g.adopt(); publication(g, old, "ingress-only")
        } else {
            val (assembly, runtime) = g.ownerAssembly(failStart = col == "start-failure")
            val wrapper = field(runtime, "graphInputs") as DetachableTopicGraphSink
            if (col == "start-failure") {
                g.r.until(g.c("actual owner reports the requested host start failure"), 250) { g.failures.isNotEmpty() }
                val use = g.need(g.snapshot.acquireKrxUse(g.owner!!), "current original use for late call")
                val original = KrxMemoryScope(g.owner!!, use, g.snapshot.revision,
                    g.snapshot.lastUserEnd?.sequence ?: 0, g.snapshot.lastCapabilityEnd?.sequence ?: 0)
                val before = assembly.gate.krxGraphRevisions.value
                g.eq("failed startup detaches ingress", null, wrapper.capture(original))
                g.eq("failed startup late D1 has no effect", KrxGraphTopicResult.NO_EFFECT, wrapper.topicUnavailable(original).result)
                g.eq("failed startup cannot emit revision", before, assembly.gate.krxGraphRevisions.value)
            } else {
                val old = g.session.ws()
                val stamp = g.need(old.krxGraphStamp, "real Owner/factory source stamp")
                g.yes("same assembly gate consumes actual runtime source", assembly.gate.bindKrxGraph(old.attribution.owner, old.attribution.lifetime, stamp) != null)
                if (col == "actual-D1") {
                    val mounted = listOf("usd", "tether").map { g.holder(assembly, it) }
                    g.sent.filter { !it.response.isCompleted }.forEach { it.response.complete(S6KrxGraphRig.ok(S6KrxGraphRig.tabDto(it.key))) }
                    g.test.runCurrent()
                    for (holder in mounted) {
                        val token = g.need(holder.currentState().inlineToken, "real assembly inline token")
                        holder.setSurfaceVisible(token, true); holder.enterFullscreen(token)
                        holder.currentState().fullscreenToken?.let { holder.setSurfaceVisible(it, true) }
                        g.yes("both tabs publish KRX before actual D1", holder.currentState().chart?.renderedIds?.contains(KRX) == true)
                    }
                    val before = assembly.gate.krxGraphRevisions.value
                    g.yes("actual assembly owns selected raw original before D1", stamp in assembly.recorder.state.value.krxSeriesOwners.values ||
                        assembly.recorder.state.value.pending.inputs.any { when (it) {
                            is TopicGraphInput.Observations -> it.krxGraphStamp == stamp
                            is TopicGraphInput.Continuity -> it.authority.krxGraphStamp == stamp
                        } })
                    g.session.d1(); g.test.runCurrent()
                    g.yes("actual verified D1 crosses Owner/factory/TSC into same gate", assembly.gate.krxGraphRevisions.value > before)
                    g.eq("same gate rejects old original", null, assembly.gate.bindKrxGraph(old.attribution.owner, old.attribution.lifetime, stamp))
                    for (holder in mounted) g.yes("actual D1 republishes both mounted tabs without old KRX", holder.state.value.chart?.renderedIds?.contains(KRX) != true)
                    g.yes("actual assembly physically drops selected raw KRX anchor", stamp !in assembly.recorder.state.value.krxSeriesOwners.values)
                    g.yes("actual assembly physically drops selected raw KRX pending", assembly.recorder.state.value.pending.inputs.none {
                        when (it) { is TopicGraphInput.Observations -> it.krxGraphStamp == stamp
                            is TopicGraphInput.Continuity -> it.authority.krxGraphStamp == stamp }
                    })
                } else {
                    if (col == "close") assembly.close() else runtime.detachGraph()
                    val before = assembly.gate.krxGraphRevisions.value
                    g.eq("late old ingress cannot capture", null, wrapper.capture(stamp.scope))
                    g.eq("late old D1 has no effect", KrxGraphTopicResult.NO_EFFECT, wrapper.topicUnavailable(stamp.scope).result)
                    g.eq("detached/closed revision unchanged", before, assembly.gate.krxGraphRevisions.value)
                    val replacement = g.assembly()
                    val fresh = g.need(replacement.krxGraphIngress.capture(stamp.scope), "fresh assembly source")
                    g.eq("new assembly begins with clean revision", 0L, replacement.gate.krxGraphRevisions.value)
                    wrapper.topicUnavailable(stamp.scope)
                    g.eq("old ingress cannot affect replacement", 0L, replacement.gate.krxGraphRevisions.value)
                    g.yes("new assembly permits fresh original", replacement.gate.bindKrxGraph(old.attribution.owner, old.attribution.lifetime, fresh) != null)
                }
            }
        }
    }

    @Test fun g27_mixedHttpKeepsGeneralAndWithdrawsKrxWrite() = s63b1Test("G27", "http-D1", "http-end", "seed-D1", "seed-end", "write-before-D1", "write-before-end", "write-completion-D1", "write-completion-end") { g, col ->
        g.start(); val old = g.adopt(); g.general()
        g.prepareGate = CompletableDeferred()
        val coordinator = g.readyCoordinator()
        val key = GraphKey("usd", GraphPeriod.ONE_DAY)
        if (col.startsWith("seed-")) {
            val server = response(g)
            val split = splitGraphV2ServerTab(server, GraphV2GeneralKey(g.dataScope.uid, g.dataScope.userAccessEpoch, "usd", "1d"),
                old.krxGraphStamp!!.scope.use.krxCapabilityEpoch, "seed-original", g.catalog)
            g.yes("seed fixture converts", split is GraphV2Validation.Valid)
            g.disk.seed = (split as GraphV2Validation.Valid).value
            g.disk.seedGate = CompletableDeferred()
        }
        coordinator.onActivated(key); g.test.runCurrent()
        val call = g.need(g.sent.lastOrNull { it.key == key }, "actual mixed HTTP")
        if (col.startsWith("write-completion-")) {
            call.response.complete(S6KrxGraphRig.ok(S6KrxGraphRig.tabDto(key))); g.test.runCurrent()
            g.yes("write preparation really started and remains held", g.prepareStarted.isNotEmpty() && !g.prepareGate!!.isCompleted)
        }
        if (col.endsWith("-end")) g.session.capEnd() else g.cutoff(old)
        g.disk.seedGate?.complete(Unit)
        if (!call.response.isCompleted) call.response.complete(S6KrxGraphRig.ok(S6KrxGraphRig.tabDto(key)))
        g.test.runCurrent(); g.prepareGate!!.complete(Unit); g.test.runCurrent()
        val entry = g.need(coordinator.protectedEntry(key), "GENERAL response remains exposed")
        g.yes("old slot cannot rebind KRX", entry.tab.graph.series.none { it.seriesId == KRX })
        g.yes("GENERAL HTTP remains", entry.tab.graph.series.any { it.seriesId == GENERAL })
        g.yes("GENERAL write succeeds and KRX admission is closed", g.disk.admissions.any { it.first } && g.disk.admissions.none { it.second })
    }

    @Test fun g29_continuityKeepsFactTimeAndOwnership() = s63b1Test("G29",
        "resumed-D1", "resumed-hold", "lease-D1", "lease-hold", "gap-D1", "gap-hold", "purged-D1", "purged-hold", "purged-boundary-D1", "purged-boundary-hold") { g, col ->
        g.start(leaseSeconds = if (col.startsWith("lease")) 1 else 600)
        val old = g.adopt(); g.general()
        val prices = g.raw.series.mapValues { it.value.data.app.observations.toSet() }
        val actualPurge = col == "purged-D1" || col == "purged-hold"
        val originalKrx = if (actualPurge) g.krx() else null
        val consumed = mutableListOf<TopicGraphInput.Continuity>()
        val consumer = object : GraphTopicInputConsumer {
            override fun observe(input: TopicGraphInput.Observations) = throw AssertionError(g.c("fact-only deferred sink received a price"))
            override fun observe(input: TopicGraphInput.Continuity) { consumed += input; g.recorder.observe(input) }
            override fun loseTopics(ownerScope: GraphDataScope, maxInvalidationsByTopic: Map<String, Long>) = g.recorder.loseTopics(ownerScope, maxInvalidationsByTopic)
            override fun loseKrxTopics(stampsByTopic: Map<String, Set<KrxGraphStamp>>) = g.recorder.loseKrxTopics(stampsByTopic)
            override fun close() = Unit
        }
        val sink = GraphRecorderTopicSink(g.scope, consumer, startImmediately = false)
        try {
            // TSC produces normal facts; the explicitly marked purge boundary columns use the shared consumer directly.
            g.session.target = TopicGraphSink { input ->
                if (input is TopicGraphInput.Continuity) sink.tryOffer(input) else TopicGraphOffer.ENQUEUED
            }
            val from = g.session.inputs.size
            val boundaryFacts = if (col.startsWith("purged-boundary")) {
                // boundary-input: original actual K1 authority, shared consumer TOPIC_PURGED without USER end.
                val fact = g.fact(old, TopicGraphEventKind.TOPIC_PURGED, setOf(old.topic, "fx:usd-krw"),
                    TopicGraphEventReason.CANONICAL_TOPIC_STATE)
                g.eq("boundary purge enters the actual deferred sink", TopicGraphOffer.ENQUEUED, sink.tryOffer(fact))
                g.yes("boundary purge keeps GENERAL and original capability live before fence", g.snapshot.admitsUse(g.use) &&
                    g.snapshot.admitsKrxUse(old.krxGraphStamp!!.scope.use))
                listOf(fact)
            } else emptyList()
            if (boundaryFacts.isEmpty()) when (col.substringBefore('-')) {
                "gap" -> { g.r.wire.drop(); g.r.drain() }
                "lease" -> g.r.advance(1_001)
                "resumed" -> {
                    val wires = g.r.wires.size
                    g.r.wire.drop(); g.r.drain()
                    g.r.until(g.c("real connection for resumed facts"), 2_600) { g.r.wires.size == wires + 1 }
                    g.r.open(); g.r.ack(g.session.cleanup.lastKrxRequest())
                    g.session.ws(1666.0, AT + 1.seconds)
                    g.r.wire.deliver(TopicSessionKrxHarness.usdFrame(1445.0, (AT + 1.seconds).toString())); g.r.drain()
                }
                else -> {
                    // Existing PAC reapproval route (N2-02): the replacement token ends the original use.
                    val grant = g.r.h.issueGrant()
                    g.eq("premise: reapproval starts from the actual original grant", old.attribution.owner.grant, grant)
                    g.r.h.source.identityNulls = 1
                    g.r.h.source.next = { EntitlementsOutcome.StableActive(true) }
                    g.r.pac.onTopicRejected(grant, listOf(TopicRejectionReason.PREMIUM_REQUIRED))
                    g.eq("premise: unreadable identity fault consumed by actual PAC", 0, g.r.h.source.identityNulls)
                    g.r.until(g.c("real PAC reapproves to a replacement grant"), 10_000) {
                        g.r.snapshot.facts.token != null && g.r.snapshot.facts.token != grant
                    }
                    g.r.drain()
                    val replacement = g.need(g.r.issuer.topicGrantResult().fence, "actual replacement grant")
                    g.yes("actual TSC grant moved before purge fact drain", replacement.grant != grant &&
                        field(g.session.cleanup.session, "fence") == replacement)
                    g.owner = replacement // Follow the actually delivered fence; no snapshot/fence-only hold is added.
                }
            }
            val facts = boundaryFacts.ifEmpty { g.session.inputs.drop(from).filterIsInstance<TopicGraphInput.Continuity>() }
            val selected = when (col.substringBefore('-')) {
                "resumed" -> facts.filter { it.kind == TopicGraphEventKind.DELIVERY_RESUMED }
                // GENERAL expiry emits LEASE_EXPIRED for GENERAL, followed by the mixed WS ending.
                "lease" -> facts.filter { it.reason in setOf(TopicGraphEventReason.LEASE_EXPIRED, TopicGraphEventReason.WS_ENDED) }
                "gap" -> facts.filter { it.kind == TopicGraphEventKind.DELIVERY_INTERRUPTED && it.reason == TopicGraphEventReason.WS_ENDED }
                else -> facts.filter { it.kind == TopicGraphEventKind.TOPIC_PURGED }
            }
            g.yes("requested actual producer fact exists before its deferred consumer", selected.isNotEmpty())
            if (col.startsWith("lease")) g.yes("actual GENERAL expiry fact exists before its mixed ending",
                selected.any { it.reason == TopicGraphEventReason.LEASE_EXPIRED })
            g.yes("fact trace covers original KRX and GENERAL topics", setOf(old.topic, "fx:usd-krw").all { topic -> selected.any { topic in it.topics } })
            val krxFacts = selected.filter { old.topic in it.topics }
            for (fact in krxFacts) {
                val original = g.need(fact.authority.krxGraphStamp, "actual KRX fact has original sidecar")
                g.yes("fact stamp came from an actual source issue", g.session.issued.any { it.second === original })
            }
            val captured = facts.map { fact -> listOf(fact, fact.occurredAtEpochMillis, fact.authority.owner,
                fact.authority.lifetime, fact.authority.krxGraphStamp, fact.topics.toSet(), fact.paths.toSet()) }
            g.eq("deferred sink has not consumed a fact", emptyList<TopicGraphInput.Continuity>(), consumed)
            if (actualPurge) {
                // Grant movement already ended the original use in both actual columns.
                g.yes("actual purge window has retired original USER and KRX uses",
                    !g.snapshot.admitsUse(old.attribution.lifetime) && !g.snapshot.admitsKrxUse(old.krxGraphStamp!!.scope.use))
                for (fact in selected) {
                    g.eq("actual purge carries original session owner epoch and lifetime",
                        listOf(old.attribution.sessionKey, old.attribution.owner, old.attribution.grantEpoch, old.attribution.lifetime),
                        listOf(fact.authority.sessionKey, fact.authority.owner, fact.authority.grantEpoch, fact.authority.lifetime))
                    g.eq("actual purge original stamp names the retired source scope",
                        old.krxGraphStamp!!.scope, g.need(fact.authority.krxGraphStamp, "actual purge original stamp").scope)
                }
                val current = g.need(g.owner, "delivered replacement owner")
                val currentUse = g.need(g.snapshot.acquireUse(current), "new grant admits its own GENERAL use")
                g.yes("new grant is eligible before checking old-stamp rebinding",
                    g.snapshot.acquireKrxUse(current) != null && g.gate.bind(current, currentUse) != null)
                g.eq("old stamp cannot be rebound to new grant K2", null,
                    g.gate.bindKrxGraph(current, currentUse, old.krxGraphStamp!!))
            } else if (col.endsWith("-hold")) g.boundaryRoundTrip("hold", old.krxGraphStamp!!) else g.cutoff(old)
            g.r.advance(17) // Different consume time; no fresh stamp/time may be substituted by drain.
            sink.start(); g.test.runCurrent()
            g.eq("all original facts consumed once in FIFO", facts, consumed)
            for ((at, fact) in consumed.withIndex()) {
                val before = captured[at]
                assertSame(g.c("same immutable fact object reaches consumer"), before[0], fact)
                g.eq("original fact time, authority, stamp, topics and paths survive", before.drop(1), listOf(
                    fact.occurredAtEpochMillis, fact.authority.owner, fact.authority.lifetime,
                    fact.authority.krxGraphStamp, fact.topics, fact.paths))
            }
            for ((key, value) in g.raw.series) g.yes("facts synthesize no prices", value.data.app.observations.all { it in prices[key].orEmpty() })
            val exposed = g.recorder.exposed(old.attribution.owner, old.attribution.lifetime)
            val requested = g.recorder.captureRequests(g.raw.series.keys, old.attribution.owner, old.attribution.lifetime)
            g.yes("fact never grants old KRX exposure or new request", g.krxKey !in exposed && requested.none { it.seriesKey == g.krxKey })
            g.eq("GENERAL observations remain exactly the original ones",
                prices[g.generalKey], g.raw.series[g.generalKey]?.data?.app?.observations)
            if (actualPurge) {
                g.eq("actual purge adopts no new KRX version", originalKrx!!.lastAppliedVersion, g.krx().lastAppliedVersion)
                assertSame(g.c("actual purge never reattributes original series stamp"), old.krxGraphStamp, g.raw.krxSeriesOwners[KRX])
                val current = g.need(g.owner, "current grant for exposure")
                val currentUse = g.need(g.snapshot.acquireUse(current), "current use for exposure")
                val currentExposed = g.recorder.exposed(current, currentUse)
                val currentRequests = g.recorder.captureRequests(g.raw.series.keys, current, currentUse)
                g.yes("new grant cannot expose or request old KRX as K2",
                    g.krxKey !in currentExposed && currentRequests.none { it.seriesKey == g.krxKey })
                g.yes("HEAD same-scope GENERAL remains exposed under the new grant", g.generalKey in currentExposed)
            }
        } finally { sink.close() }
    }

    @Test fun g30_sharedBoundaryPreservesOtherAndLastKnownRules() {
        s63b1Test("G30", "USD-tether", "FX-gap", "FX-unavailable", "F2-unarmed-WS", "F2-unarmed-REST",
            "F2-armed-WS", "F2-armed-REST", "last-known", "FX-purge-boundary") { g, col ->
            var restoreCalls = 0
            if (col == "last-known") {
                val restore: suspend (TopicSessionFence) -> com.jay.fxi.data.local.TopicLastKnownRestore = { owner ->
                    restoreCalls++
                    val lifetime = g.need(g.r.uses.acquire(owner), "actual restore original GENERAL use")
                    com.jay.fxi.data.local.TopicLastKnownRestore.Seed(TopicRates().merge(listOf(
                        TopicQuote("investing", "usd-krw", 1440.0, AT - 1.minutes),
                        TopicQuote("krx", "usd-krw-futures", 1888.0, AT - 1.minutes))), owner, lifetime)
                }
                setField(g.session.cleanup.session, "restoreSeed", restore)
            }
            g.start(leaseSeconds = if (col in setOf("FX-gap", "FX-unavailable")) 181 else 600)
            if (col == "last-known") {
                g.eq("actual restore producer was called once", 1, restoreCalls)
                g.yes("GENERAL display seed adopted", g.r.rates.quotes.values.any { it.rate == 1440.0 })
                g.yes("last-known seed never produces any graph observation", g.session.inputs.filterIsInstance<TopicGraphInput.Observations>().isEmpty())
                g.yes("last-known prices are no graph observation/recovery", g.raw.series.isEmpty() && g.raw.pending.inputs.isEmpty())
            }
            val old = g.adopt(); g.general()
            val anchor = g.raw.krxSeriesOwners[KRX]; val before = g.print(g.krx())
            if (col == "FX-gap" || col == "FX-unavailable") {
                // HEAD may combine GENERAL and KRX in the renewal. The rejected topic alone is FX.
                val request = g.session.renewal("fx:usd-krw")
                val cutoffs = g.session.cutoffs.size
                g.r.wire.deliver(g.r.ackFor(request, mapOf("fx:usd-krw" to "topic_unavailable"))); g.r.drain()
                g.eq("inside FX-only canonical unavailable state", TopicRejectionReason.TOPIC_UNAVAILABLE,
                    g.r.store.snapshot.stateFor("fx:usd-krw").rejection)
                g.eq("FX-only unavailable never invokes KRX D1", cutoffs, g.session.cutoffs.size)
                g.eq("FX-only canonical cleanup preserves KRX buckets/demand/version", before.take(8), g.print(g.krx()).take(8))
                if (col == "FX-gap") {
                    val after = g.r.sends.size; val facts = g.session.inputs.size
                    // HEAD foreground keeps an admitted live connection. Retry requires a new connection.
                    g.session.cleanup.session.setForeground(false); g.r.drain()
                    g.r.wire.drop(); g.r.drain()
                    val wires = g.r.wires.size
                    g.session.cleanup.session.setForeground(true); g.r.drain()
                    g.r.until(g.c("foreground opens a new connection for retry"), 2_600) { g.r.wires.size == wires + 1 }
                    g.r.open()
                    g.r.until(g.c("actual foreground FX resume command on the new connection"), 1_000) {
                        g.r.sends.drop(after).any { it.accepted && "fx:usd-krw" in it.request.topics }
                    }
                    val retry = g.r.sends.drop(after).first { it.accepted && "fx:usd-krw" in it.request.topics }.request
                    g.r.ack(retry)
                    g.r.wire.deliver(TopicSessionKrxHarness.usdFrame(1446.0, (AT + 1.seconds).toString())); g.r.drain()
                    g.yes("actual FX-only resumed fact reached recorder", g.session.inputs.drop(facts).filterIsInstance<TopicGraphInput.Continuity>()
                        .any { it.kind == TopicGraphEventKind.DELIVERY_RESUMED && it.topics == setOf("fx:usd-krw") })
                    g.eq("FX resume preserves common KRX prices", before.take(4), g.print(g.krx()).take(4))
                    g.eq("FX resume preserves original applied version", before[7], g.krx().lastAppliedVersion)
                }
            } else if (col == "FX-purge-boundary") {
                // boundary-input: shared consumer control. TSC has no FX-only TOPIC_PURGED producer in HEAD.
                val fact = g.fact(old, TopicGraphEventKind.TOPIC_PURGED, setOf("fx:usd-krw"),
                    TopicGraphEventReason.CANONICAL_TOPIC_STATE)
                val issues = g.session.issued.size
                g.recorder.observe(fact)
                g.eq("FX-only purge fact preserves the common KRX data/demand/version", before, g.print(g.krx()))
                g.eq("FX-only purge fact issues no new source", issues, g.session.issued.size)
            } else if (col.startsWith("F2-")) {
                if (col.startsWith("F2-armed")) g.r.generalTick()
                val session = g.session.cleanup.session
                val state = g.r.store.snapshot.stateFor(TopicCatalogue.TETHER)
                val delivered = field(session, "tetherDelivered")
                val deadline = field(session, "silenceArmedUntilMillis")
                val handled = field(session, "silenceHandledWindowMillis")
                if (col.startsWith("F2-armed")) {
                    g.yes("inside a positively armed tether window", delivered == true && deadline is Long && deadline > g.r.clock.nowMillis())
                } else g.yes("inside unarmed first-delivery window", delivered == false && deadline == null)
                if (col.endsWith("-REST")) g.session.rest(1560.0, AT + 1.seconds) else g.session.ws(1560.0, AT + 1.seconds)
                g.eq("KRX does not count as tether receipt", state, g.r.store.snapshot.stateFor(TopicCatalogue.TETHER))
                g.eq("KRX remains OTHER on both source paths", listOf(delivered, deadline, handled), listOf(
                    field(session, "tetherDelivered"), field(session, "silenceArmedUntilMillis"), field(session, "silenceHandledWindowMillis")))
                if (deadline is Long) {
                    val until = deadline - g.r.clock.nowMillis()
                    g.yes("inside original tether deadline before its boundary", until > 0)
                    g.r.advance(until - 1)
                    g.eq("KRX did not move deadline before expiry", deadline, field(session, "silenceArmedUntilMillis"))
                    g.yes("original window not yet consumed", field(session, "silenceHandledWindowMillis") != deadline)
                    g.r.advance(1)
                    g.eq("original deadline is consumed exactly at its old boundary", deadline, field(session, "silenceHandledWindowMillis"))
                }
            } else if (col == "last-known") {
                g.eq("actual live KRX does not merge a last-known graph price", setOf(1555.0), g.krx().data.app.observations.map { it.rate }.toSet())
            }
            g.eq("USD/tether retain the same valid original anchor", anchor, g.raw.krxSeriesOwners[KRX])
            g.eq("only one common KRX stream", 1, g.raw.series.keys.count { it.seriesId == KRX })
            g.yes("GENERAL remains available", g.generalKey in g.exposed())
        }
        s63b1Test("G30", "OFF", on = false) { g, _ ->
            g.start(); g.r.krxTick(); g.r.generalTick()
            g.eq("OFF issues no graph capture", emptyList<Pair<com.jay.fxi.data.entitlements.purge.KrxMemoryScope, KrxGraphStamp?>>(), g.session.issued)
            g.eq("OFF invokes no D1", 0, g.session.cutoffs.size)
            g.eq("OFF cutoff revision stays zero", 0L, g.session.fence.revisions.value)
            g.yes("OFF never makes a dedicated graph series", g.raw.series.keys.none { it.seriesId == KRX })
        }
    }

    @Test fun g31_handoverEndedLossKeepsOriginalStamp() = s63b1Test("G31",
        "FULL-valid", "FAILED-valid", "CLOSED-valid", "FULL-D1", "FAILED-D1", "FULL-hold", "FAILED-hold") { g, col ->
        // Suppress unrelated automatic REST issues so the shutdown's known source is precisely K1.
        g.r.httpFloor = g.r.clock.nowMillis() + 1_000_000
        g.start(); val old = g.adopt(); g.general()
        endingLoss(g, col, old.krxGraphStamp, pricesPresent = true)
    }

    @Test fun g32_priceLessEndingKeepsKnownOriginal() = s63b1Test("G32",
        "ACK-FULL-valid", "ACK-FAILED-valid", "ACK-CLOSED-valid", "ACK-FULL-D1", "ACK-FAILED-hold",
        "REST-degraded-FULL-valid", "REST-degraded-FAILED-valid", "REST-degraded-CLOSED-valid",
        "REST-dormant-FULL-valid", "REST-dormant-FAILED-valid", "REST-dormant-CLOSED-valid",
        "REST-degraded-FULL-D1", "REST-dormant-FAILED-hold", "unknown-FULL", "unknown-FAILED", "unknown-CLOSED") { g, col ->
        val original: KrxGraphStamp?
        if (col.startsWith("unknown")) {
            g.r.httpFloor = g.r.clock.nowMillis() + 1_000_000
            g.r.approve(); g.r.start() // Connection is deliberately unopened: no subscribe/REST issue exists.
            g.owner = g.need(g.r.issuer.topicGrantResult().fence, "actual unknown-input GENERAL grant")
            g.eq("unknown window has never issued an original", 0, g.session.issued.size)
            original = null
        } else if (col.startsWith("ACK")) {
            g.r.httpFloor = g.r.clock.nowMillis() + 1_000_000
            val knownSources = S6KrxGraphMemoryPurgeRig(g)
            knownSources.observeKnownSources()
            g.start(ack = false)
            val first = g.session.cleanup.lastKrxRequest()
            g.session.cleanup.ackKrxLease(first, "g32-K1", krxSeconds = 1)
            val ack = g.need(g.session.inputs.filterIsInstance<TopicGraphInput.Continuity>().lastOrNull {
                it.kind == TopicGraphEventKind.ACK_ACTIVE_SET_CHANGED && it.authority.krxGraphStamp != null
            }, "actual valid K1 ACK fact")
            val ackOriginal = g.need(ack.authority.krxGraphStamp, "ACK original K1")
            g.yes("actual KRX active before lease expiry", field(g.session.cleanup.live, "krxActive") != null)
            val generalLeases = g.session.cleanup.generalRegistry.held
            g.yes("GENERAL leases outlive the KRX-only lease", generalLeases.isNotEmpty() &&
                generalLeases.values.all { it.expiresAtMillis > g.r.clock.nowMillis() + 1_001 })
            g.r.advance(1_001)
            g.eq("KRX active retired by actual lease expiry", null, field(g.session.cleanup.live, "krxActive"))
            g.eq("GENERAL connection and leases survive", generalLeases, g.session.cleanup.generalRegistry.held)
            // C5-02: the short lease can prepare a newer, unACKed source before active retirement.
            // Freeze the actual known maximum before endingLoss triggers the shutdown producer.
            original = knownSources.knownOriginal(ack.authority, null)
            g.yes("pre-ending maximum retains the actual newer validated renewal prepare", knownSources.knownWsPrepares.any {
                it.stamp == original && it.stamp.issueSequence > ackOriginal.issueSequence &&
                    it.sessionKey === ack.authority.sessionKey && it.grantEpoch == ack.authority.grantEpoch
            })
            g.yes("lease expiry leaves original capability/use admitted", g.snapshot.admitsKrxUse(original.scope.use))
        } else {
            g.start(ack = false)
            g.eq("REST counterexample has no active WS KRX", null, field(g.session.cleanup.live, "krxActive"))
            val call = g.session.cleanup.issue()
            val before = g.session.inputs.size
            g.session.cleanup.f.reply(call, if ("degraded" in col) KrxA1bFixture.Reply(404, """{"error":"topic_unavailable"}""")
                else KrxA1bFixture.Reply(404, """{"error":"topics_disabled"}"""))
            g.yes("price-less REST completed normally", call.outcome != null && !call.cancelled)
            g.eq("exact price-less REST verdict", if ("degraded" in col) TopicSnapshotOutcome.Degraded else TopicSnapshotOutcome.Dormant, call.outcome)
            val fact = g.need(g.session.inputs.drop(before).filterIsInstance<TopicGraphInput.Continuity>()
                .singleOrNull { it.reason == TopicGraphEventReason.REST_UNDELIVERED && TopicCatalogue.KRX_FUTURES in it.topics },
                "actual price-less REST continuity")
            original = g.need(fact.authority.krxGraphStamp, "known original REST issue")
            g.yes("REST original was issued by the actual producer", g.session.issued.any { it.second === original })
            g.eq("REST ending still has no active WS KRX", null, field(g.session.cleanup.live, "krxActive"))
        }
        g.yes("price-less window has no KRX price batch", g.session.inputs.filterIsInstance<TopicGraphInput.Observations>()
            .none { it.topic == TopicCatalogue.KRX_FUTURES })
        g.yes("no KRX series before loss", g.krxKey !in g.raw.series)
        g.general()
        endingLoss(g, col, original, pricesPresent = false)
    }

    /** Actual stop producer -> real bounded sink -> original loss consumer. No private method invocation. */
    private suspend fun endingLoss(g: S6KrxGraphRig, col: String, original: KrxGraphStamp?, pricesPresent: Boolean) {
        val mode = when { "CLOSED" in col -> TopicGraphOffer.CLOSED; "FAILED" in col -> TopicGraphOffer.FAILED; else -> TopicGraphOffer.FULL }
        val consumed = mutableListOf<TopicGraphInput>()
        val krxLoss = mutableListOf<Map<String, Set<KrxGraphStamp>>>()
        val generalLoss = mutableListOf<Pair<GraphDataScope, Map<String, Long>>>()
        val consumer = object : GraphTopicInputConsumer {
            override fun observe(input: TopicGraphInput.Observations) { consumed += input; g.recorder.observe(input) }
            override fun observe(input: TopicGraphInput.Continuity) { consumed += input; g.recorder.observe(input) }
            override fun loseTopics(ownerScope: GraphDataScope, maxInvalidationsByTopic: Map<String, Long>) {
                generalLoss += ownerScope to maxInvalidationsByTopic.toMap()
                g.recorder.loseTopics(ownerScope, maxInvalidationsByTopic)
            }
            override fun loseKrxTopics(stampsByTopic: Map<String, Set<KrxGraphStamp>>) {
                krxLoss += stampsByTopic.mapValues { it.value.toSet() }
                g.recorder.loseKrxTopics(stampsByTopic)
            }
            override fun close() = Unit
        }
        val sink = GraphRecorderTopicSink(g.scope, consumer, startImmediately = false)
        // boundary-input: a 512-unit GENERAL holding opens a real FULL window without losing a price batch.
        val general = g.general()
        // boundary-input: explicit synthetic envelope using the original source attribution.
        val full = TopicGraphInput.Observations(90_000, general.topic, general.path, general.attribution,
            general.connectionGeneration, List(512) { general.candidates.single() })
        try {
            if (mode == TopicGraphOffer.CLOSED) sink.close() else {
                g.eq("GENERAL fills all 512 units before actual ending", TopicGraphOffer.ENQUEUED, sink.tryOffer(full))
                g.eq("inside full queue window before producer", 512, field(sink, "usedUnits"))
                if (mode == TopicGraphOffer.FAILED && original != null) g.failNextKrxLedgerWrite(sink)
                // Unknown has no KRX ledger entry; fault the existing GENERAL ledger instead.
                if (mode == TopicGraphOffer.FAILED && original == null) {
                    @Suppress("UNCHECKED_CAST") val ledger = field(sink, "ledger") as MutableMap<GraphDataScope, MutableMap<String, Long>>
                    var armed = true
                    setField(sink, "ledger", object : MutableMap<GraphDataScope, MutableMap<String, Long>> by ledger {
                        override fun get(key: GraphDataScope): MutableMap<String, Long>? {
                            if (armed) { armed = false; throw IllegalArgumentException("boundary-input: one GENERAL ledger read fails") }
                            return ledger[key]
                        }
                    })
                }
            }
            g.session.target = TopicGraphSink { input ->
                if (input is TopicGraphInput.Continuity && input.kind == TopicGraphEventKind.HANDOVER_ENDED) sink.tryOffer(input)
                else TopicGraphOffer.ENQUEUED
            }
            val issues = g.session.issued.size
            val from = g.session.inputs.size
            val lossTime = 1_000_000L + g.r.clock.nowMillis()
            g.session.cleanup.session.stop(); g.r.drain()
            val facts = g.session.inputs.drop(from).filterIsInstance<TopicGraphInput.Continuity>()
            val ending = g.need(facts.singleOrNull { it.kind == TopicGraphEventKind.HANDOVER_ENDED }, "actual shutdown HANDOVER_ENDED exists")
            g.eq("ending carries the known original, or remains genuinely unknown", original, ending.authority.krxGraphStamp)
            g.eq("actual ending was lost with requested result", mode, g.session.offers.single { it.first === ending }.second)
            g.yes("ending covers KRX and GENERAL", setOf(TopicCatalogue.KRX_FUTURES, general.topic).all { it in ending.topics })
            g.yes("shutdown suppresses subsequent WS_ENDED by authority identity", facts.none { it.reason == TopicGraphEventReason.WS_ENDED })
            g.eq("ending never captures a replacement original", issues, g.session.issued.size)
            val loss = g.need(g.session.cleanup.session.graphLoss.value, "session records the actual ending loss")
            g.eq("one lost original ending batch", 1L, loss.count)
            g.eq("session revision and high-water", listOf(1L, ending.sequence, ending.sequence), listOf(loss.revision, loss.firstSequence, loss.lastSequence))
            g.eq("session keeps the exact loss result", setOf(mode), loss.reasons)
            g.eq("session preserves ending topics/paths/authority", listOf(ending.topics, ending.paths,
                setOf(TopicGraphAuthorityKey(ending.authority.owner, ending.authority.grantEpoch))), listOf(loss.topics, loss.paths, loss.authorities))
            g.eq("session preserves original loss clocks", lossTime to lossTime, loss.firstOccurredAtEpochMillis to loss.lastOccurredAtEpochMillis)
            val expected = original?.let { mapOf(TopicCatalogue.KRX_FUTURES to setOf(it)) }.orEmpty()
            g.eq("session loss retains known K1 even CLOSED; unknown stays empty", expected, loss.krxLost)
            val before = if (pricesPresent) g.print(g.krx()) else null
            val retired = col.endsWith("-D1") || col.endsWith("-hold")
            if (original != null && retired) {
                if (col.endsWith("-D1")) {
                    val cutoff = g.session.ingress.topicUnavailable(original.scope)
                    g.eq("D1 cutoff installed before loss drain", KrxGraphTopicResult.INSTALLED, cutoff.result)
                } else g.boundaryRoundTrip("hold", original)
                g.yes("inside original-refused window before drain", g.gate.bindKrxGraph(original.scope.owner, original.scope.use.lifetime, original) == null)
            }
            g.r.advance(17)
            sink.start(); g.test.runCurrent()
            if (mode == TopicGraphOffer.CLOSED) {
                g.eq("CLOSED retains session evidence and delivers no queue", emptyList<TopicGraphInput>(), consumed)
            } else {
                g.eq("GENERAL queue survives in FIFO", listOf(full), consumed)
                g.eq("drain refunds exactly 512 units", 0, field(sink, "usedUnits"))
                g.yes("GENERAL original loss accounting reaches consumer", generalLoss.any {
                    it.first == g.dataScope && it.second[general.topic] == general.attribution.lifetime.invalidations
                })
                if (original != null) g.yes("real sink delivers the same original loss", krxLoss.any { it == expected })
                else g.yes("unknown has no manufactured KRX loss", krxLoss.all { it.isEmpty() })
                if (retired && pricesPresent) g.eq("refused K1 loss cannot change old demand", before, g.print(g.krx()))
                if (!retired && original != null) {
                    if (pricesPresent) g.yes("valid K1 lost ending creates HANDOVER_LOSS", g.krx().pending.values.any { GraphRecoveryReason.HANDOVER_LOSS in it.reasons })
                    else g.yes("price-less original survives as untransferred loss", g.raw.krxUntransferred[KRX]?.contains(original) == true)
                }
                if (retired && original != null) {
                    val current = g.snapshot; g.r.snapshotSupply = { current }
                    g.replacementSession(g.r.desired)
                    g.r.ack(g.session.cleanup.lastKrxRequest())
                    val fresh = g.session.ws(1777.0, AT + 2.seconds)
                    g.yes("new actual K2 is distinct and admitted", fresh.krxGraphStamp!!.issueSequence > original.issueSequence && g.krxKey in g.exposed())
                    g.yes("K1 loss never becomes K2 recovery demand", g.krx().pending.values.none { GraphRecoveryReason.HANDOVER_LOSS in it.reasons })
                    g.eq("K2 price contains no old observations", setOf(1777.0), g.krx().data.app.observations.map { it.rate }.toSet())
                }
            }
            g.eq("the captured ending remains immutable after drain", original, ending.authority.krxGraphStamp)
            g.eq("the detached session view remains immutable", expected, loss.krxLost)
        } finally { sink.close() }
    }

    private fun freshAfterBoundary(g: S6KrxGraphRig, rate: Double, at: Instant): TopicGraphInput.Observations {
        val current = g.snapshot
        g.r.snapshotSupply = { current }
        val fresh = g.session.rest(rate, at)
        g.yes("fresh REST original is currently admitted", current.admitsKrxUse(fresh.krxGraphStamp!!.scope.use))
        g.yes("fresh REST is adopted and exposed", g.krxKey in g.exposed())
        return fresh
    }

    private fun response(g: S6KrxGraphRig): GraphV2Tab {
        val key = GraphKey("usd", GraphPeriod.ONE_DAY)
        val admitted = GraphV2Domain.admit(S6KrxGraphRig.tabDto(key), key.tab, key.period, g.catalog)
        g.yes("server fixture is valid", admitted is GraphTabAdmission.Accepted)
        return (admitted as GraphTabAdmission.Accepted).tab
    }

    private suspend fun completion(g: S6KrxGraphRig, released: Boolean) {
        g.start(); val old = g.adopt(); g.general()
        val coordinator = g.readyCoordinator()
        coordinator.onActivated(GraphKey("usd", GraphPeriod.ONE_DAY)); g.test.runCurrent()
        val call = g.need(g.sent.lastOrNull { it.key.period == GraphPeriod.ONE_DAY }, "original producer started")
        g.yes("producer live before completion", call.admitted && !call.cancelled && !call.returned)
        @Suppress("UNCHECKED_CAST") val registered = field(coordinator, "tabRequests") as Map<GraphKey, Any>
        val registration = g.need(registered[call.key], "original registration present")
        @Suppress("UNCHECKED_CAST") val captured = field(registration, "recoveryRequests") as List<GraphRecoveryRequest>
        val request = g.need(captured.singleOrNull { it.seriesKey == g.krxKey }, "original registration owns KRX recovery")
        g.eq("registered original anchor", old.krxGraphStamp, request.krxGraphStamp)
        g.eq("original dispatch owns both KRX and GENERAL requests", setOf(g.krxKey, g.generalKey), captured.map { it.seriesKey }.toSet())
        val originals = captured.toList()
        // G24 exercises releaseRegistration inside successful completion, followed by applyRecovery.
        // Public purgeRecoveryCaptures intentionally removes dispatches and is not the C07 release window.
        val before = g.print(g.krx()); val applies = g.applyReads
        var releasedEntryWindows = 0
        g.CompletionBarrier(coordinator).use { barrier ->
            call.response.complete(S6KrxGraphRig.ok(S6KrxGraphRig.tabDto(call.key))); g.test.runCurrent()
            g.yes("producer actually returned normally before D1", call.returned && !call.cancelled)
            g.eq("actual completion queued and withheld", 1, barrier.enqueued)
            g.eq("no early apply before FIFO release", applies, g.applyReads)
            g.cutoff(old)
            if (released) g.onApplyRead = {
                // Public, non-mutating probe: false selector retains every capture. No private invocation.
                var stillRegistered = 0
                val changed = coordinator.purgeRecoveryCaptures { stillRegistered++; false }
                g.eq("released registration has no registered capture at original REC entry", 0, stillRegistered)
                g.eq("release observation changes no capture", false, changed)
                releasedEntryWindows++
            }
            barrier.release()
            if (released) g.eq("every original REC entry first proves the released window", originals.size, releasedEntryWindows)
            g.eq("each original native recovery dispatch reaches the same REC exactly once", applies + originals.size, g.applyReads)
            g.eq("raw KRX buckets/version/demand unchanged", before, g.print(g.krx()))
            g.yes("GENERAL response still applied", g.raw.series[g.generalKey]?.lastAppliedVersion != null)
            g.eq("completion does not rewrite its captured request", old.krxGraphStamp, request.krxGraphStamp)
        }
    }

    private suspend fun publication(g: S6KrxGraphRig, old: TopicGraphInput.Observations, col: String,
        assembled: GraphRuntimeAssembly? = null) {
        val assembly = assembled ?: g.assembly()
        assembly.start(); g.test.runCurrent()
        val stamp = g.need(assembly.krxGraphIngress.capture(old.krxGraphStamp!!.scope), "assembly original source issue")
        // boundary-input: explicit synthetic envelope using the original source attribution.
        val stamped = TopicGraphInput.Observations(old.sequence, old.topic, old.path, old.attribution,
            old.connectionGeneration, old.candidates, stamp)
        val holders = listOf("usd", "tether").map { g.holder(assembly, it) }
        g.sent.filter { !it.response.isCompleted }.forEach { it.response.complete(S6KrxGraphRig.ok(S6KrxGraphRig.tabDto(it.key))) }
        g.test.runCurrent(); assembly.recorder.observe(stamped)
        g.test.runCurrent()
        for (holder in holders) {
            val state = holder.currentState(); val token = g.need(state.inlineToken, "admitted inline token")
            holder.setSurfaceVisible(token, true); holder.enterFullscreen(token); g.test.runCurrent()
            holder.currentState().fullscreenToken?.let { holder.setSurfaceVisible(it, true) }
            g.yes("live/server KRX is published before cutoff", holder.currentState().chart?.renderedIds?.contains(KRX) == true)
        }
        val unchanged = listOf(g.r.snapshot, g.r.display, assembly.recorder.state.value, assembly.coordinator.state.value)
        val cutoffRevision = assembly.gate.krxGraphRevisions.value
        val afterCutoff = mutableListOf<Pair<String, Set<String>>>()
        val observers = holders.map { holder -> g.scope.launch(UnconfinedTestDispatcher(g.test.testScheduler)) {
            holder.state.collect { state ->
                if (assembly.gate.krxGraphRevisions.value > cutoffRevision) {
                    afterCutoff += state.tab to state.chart?.renderedIds.orEmpty()
                }
            }
        } }
        val doCutoff = { assembly.krxGraphIngress.topicUnavailable(stamp.scope) }
        if (col == "prepare") {
            // A new preparation key makes the real prepared builder run; D1 is installed inside preparation.
            g.duringPrepare = { doCutoff(); Unit }
            val token = g.need(holders.first().currentState().inlineToken, "period-change token")
            holders.first().selectPeriod(token, GraphPeriod.THREE_MONTHS)
            g.test.runCurrent() // Start the real request before collecting responses to complete.
            g.yes("period change starts a real 3m request", g.sent.any { it.key == GraphKey("usd", GraphPeriod.THREE_MONTHS) && !it.response.isCompleted })
            g.sent.filter { !it.response.isCompleted }.forEach { it.response.complete(S6KrxGraphRig.ok(S6KrxGraphRig.tabDto(it.key))) }
            g.test.runCurrent(); holders.first().currentState()
            g.eq("preparation hook ran", null, g.duringPrepare)
        } else if (col == "end") g.session.capEnd() else {
            val result = doCutoff()
            g.eq("ingress-only installs cutoff", KrxGraphTopicResult.INSTALLED, result.result)
            g.eq("D1-only leaves other publication flows unchanged", unchanged,
                listOf(g.r.snapshot, g.r.display, assembly.recorder.state.value, assembly.coordinator.state.value))
        }
        g.test.runCurrent() // Main revision boundary; no time event or recorder/display/coordinator signal.
        for (holder in holders) g.yes("already published state drops old KRX without another input",
            holder.state.value.chart?.renderedIds?.contains(KRX) != true)
        for (holder in holders) g.yes("fresh render cannot expose old KRX", holder.currentState().chart?.renderedIds?.contains(KRX) != true)
        g.yes("no unsafe transient publication after a preparation-time cutoff", afterCutoff.none { KRX in it.second })
        for (holder in holders) g.yes("GENERAL rendering continues after cutoff", holder.currentState().chart?.renderedIds?.contains(GENERAL) == true)
        observers.forEach { it.cancel() }
    }
}
