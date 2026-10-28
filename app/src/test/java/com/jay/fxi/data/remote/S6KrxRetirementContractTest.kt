package com.jay.fxi.data.remote

import com.jay.fxi.data.entitlements.KrxContestPhase
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.KRX
import com.jay.fxi.data.remote.S6KrxCleanupRig.Companion.setField
import com.jay.fxi.data.remote.S6KrxCleanupRig.Companion.field
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertTrue
import okhttp3.WebSocket
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class S6KrxRetirementContractTest {
    @Test fun r01_openSocket_sendsKrxUnsubscribeOnce() = s63Test("R01") { s, _ ->
        s.live(); val tuple = s.tuple(); val connection = s.live
        val tokenCalls = s.r.commands.size
        s.hold()
        s.eq("one physical unsubscribe", 1, s.unsubscribes.size)
        val request = s.unsubscribes.single().request
        s.eq("only KRX", listOf(KRX), request.topics)
        s.eq("no token on unsubscribe", null, request.idToken)
        s.yes("requestId differs from subscribe", request.requestId != tuple.requestId)
        s.eq("one encode, no ACK wait", 1, s.unsubscribeRequests.size)
        s.eq("no token-bearing command added", tokenCalls, s.r.commands.size)
        s.eq("old registry cleared", emptyMap<Any, Any>(), s.registry.held)
        s.eq("send true means unconfirmed", KrxRetirementWire.ACCEPTED_UNCONFIRMED, s.record(tuple).wire)
        s.eq("same GENERAL connection", connection, s.live)
        s.generalWorks()
    }

    @Test fun r02_retirementTuple_isIdempotent() {
        val leaseTuples = mutableListOf<KrxSubscriptionRetirement>()
        s63Test("R02", "repeat", "requestId", "leaseId-A", "leaseId-B") { s, column ->
            if (column.startsWith("leaseId")) {
                // The same real generation/request sequence on independent rigs, with only the
                // server-granted lease ID varied. No fabricated private retirement request.
                s.live(ack = false, rates = false)
                val request = s.lastKrxRequest()
                val lease = "server-$column"
                s.ackKrxLease(request, lease)
                s.yes("the server lease variant is actually accepted", s.r.store.snapshot.stateFor(KRX).confirmed &&
                    s.registry.held[KRX]?.leaseId == lease)
                val tuple = s.tuple(request, lease)
                s.hold()
                s.yes("record retains the actual server lease identity", tuple in s.records)
                leaseTuples += tuple
            } else {
                s.live(ack = false, rates = false)
                val first = s.lastKrxRequest()
                val tuple = s.tuple(first, null)
                s.hold()
                s.eq("first SENT pending tuple really dispatched", 1, s.unsubscribes.size)
                val records = s.records; val sends = s.unsubscribes.size
                // A mixed FIRST remains pending for its GENERAL ACK. Every held publication
                // reaches its masked SENT capture again, exercising actual tuple deduplication.
                s.repeatHeldPublication()
                s.eq("same tuple wire at most once", sends, s.unsubscribes.size)
                s.eq("same tuple result unchanged", records, s.records)
                if (column == "requestId") {
                    s.r.ack(first)
                    val after = s.r.sends.size
                    s.release(); val next = s.nextKrx(after); s.r.ack(next)
                    val nextTuple = s.tuple(next)
                    s.yes("new requestId is real", nextTuple.requestId != tuple.requestId)
                    s.hold()
                    s.yes("different request has its own result", nextTuple in s.records)
                    s.yes("returned map is a stable copy", records.keys == setOf(tuple))
                }
            }
        }
        assertTrue("contract(R02): both real lease-ID variants were observed", leaseTuples.size == 2)
        val a = leaseTuples[0]; val b = leaseTuples[1]
        assertTrue("premise: R02 independent fixtures use the same generation and request ID",
            a.connectionGeneration == b.connectionGeneration && a.requestId == b.requestId)
        assertTrue("contract(R02): distinct server lease IDs produce distinct exact tuples", a.leaseId != b.leaseId && a != b)
    }

    @Test fun r03_connectionEnding_neverSendsUnsubscribe() = s63Test("R03", "ended", "old-generation", "unopened", "end-before-flag") { s, column ->
        s.live(); val oldWire = s.r.wire; val old = s.live; val registry = s.registry; val tuple = s.tuple()
        s.yes("end entry starts with ended false", field(old, "ended") == false)
        // Public Online(false) enters end before the implementation sets its ended flag.
        s.session.setOnline(false); s.r.drain()
        s.eq("ending sends no unsubscribe", 0, s.unsubscribes.size)
        s.eq("old local registry still cleared", emptyMap<Any, Any>(), registry.held)
        s.eq("closed outcome recorded", KrxRetirementWire.SKIPPED_CLOSED, s.record(tuple).wire)
        s.eq("local end disposition", KrxRetirementDisposition.LOCAL_CONNECTION_ENDED, s.record(tuple).disposition)
        val records = s.records
        if (column == "old-generation" || column == "unopened") {
            val count = s.r.wires.size
            s.session.setOnline(true)
            s.r.until(s.c("a new generation is created"), 2_600) { s.r.wires.size == count + 1 }
            s.yes("new connection differs from ended one", s.live !== old)
            if (column == "old-generation") { s.r.open(); s.r.ack() }
            else {
                s.yes("replacement is not open yet", field(s.live, "opened") == false)
                s.session.setOnline(false); s.r.drain()
            }
        }
        // Late transport events belong to the actual ended connection; they cannot replay cleanup
        // into a new or still-unopened generation. Never mutate connection flags or invoke end.
        oldWire.deliver(KrxSessionIntegrationRig.krxFrame(1993.0)); s.r.drain()
        s.session.setOnline(false); s.r.drain()
        s.eq("late old events/duplicate end add no old wire", 0, s.unsubscribes.size)
        s.eq("old result remains closed", records[tuple], s.records[tuple])
    }

    @Test fun r04_newCapture_isProtectedInBothOrders() = s63Test("R04",
        "old-first/same", "old-first/different", "pending/same", "pending/different", "promotion/same", "promotion/different", "active/same", "active/different") { s, column ->
        s.live(seconds = 181)
        val old = s.active(); val first = s.lastKrxRequest(); val tuple = s.tuple(first)
        val oldLease = s.need(tuple.leaseId, "original lease")
        val oldExpiry = s.need(s.registry.held[KRX], "original KRX lease").expiresAtMillis
        val same = column.endsWith("/same")
        if (column.startsWith("old-first")) {
            val after = s.r.sends.size
            s.hold(); s.release(); val next = s.nextKrx(after)
            s.ackKrxLease(next, if (same) oldLease else "replacement-lease")
            s.eq("old-first retires once before K2", 1, s.records.keys.count { it == tuple })
            val k2 = s.registry.held; val records = s.records; val wires = s.unsubscribes.size
            s.r.ack(first)
            s.eq("late old ACK cannot clear K2", k2, s.registry.held)
            s.eq("late old ACK cannot repeat retirement", records, s.records)
            s.eq("late old ACK adds no old wire", wires, s.unsubscribes.size)
        } else {
            val next = s.renew()
            s.yes("K2 SENT pending while K1 active", field(s.pending(), "sent") == true && s.active() === old)
            val before = s.unsubscribes.size
            if (column.startsWith("pending")) {
                val pendingLease = s.registry.held
                s.r.ack(first)
                s.eq("late K1 ACK preserves K2 pending ownership", pendingLease, s.registry.held)
                s.yes("K2 remains SENT pending", field(s.pending(), "sent") == true)
            }
            // This actual K2 ACK enters promotion with the implementation's pending/active pointer
            // gap. Its synchronous old retirement must protect the already accepted replacement.
            s.ackKrxLease(next, if (same) oldLease else "replacement-lease",
                krxSeconds = if (column == "promotion/same") 0 else 600)
            s.yes("K2 promoted by its ACK", s.active() !== old)
            s.eq("no old unsubscribe in replacement window", before, s.unsubscribes.size)
            s.eq("newer capture suppresses old wire", KrxRetirementWire.SKIPPED_NEWER_CAPTURE, s.record(tuple).wire)
            if (same) s.eq("same lease preserves original absolute expiry", oldExpiry,
                s.need(s.registry.held[KRX], "K2 lease retained").expiresAtMillis)
            if (column == "promotion/same") {
                // A zero restatement of the still-live lease spends its one immediate renewal.
                // The following real ACK restates the same zero again; it must not launch a fourth.
                val secondIndex = s.r.sends.indexOfLast { it.request == next }
                s.yes("second capture has an actual send index", secondIndex >= 0)
                val third = s.nextKrx(secondIndex + 1)
                s.ackKrxLease(third, oldLease, krxSeconds = 0)
                val sends = s.r.sends.size
                s.r.advance(1)
                s.eq("same lease zero-attempt ledger stays spent", sends, s.r.sends.size)
                s.eq("zero restatement preserves absolute expiry", oldExpiry,
                    s.need(s.registry.held[KRX], "zero-restated lease retained").expiresAtMillis)
            }
            val k2 = s.registry.held; val records = s.records
            s.r.ack(first)
            s.eq("late old ACK preserves active replacement", k2, s.registry.held)
            s.eq("late old ACK preserves retirement history", records, s.records)
        }
        s.r.krxTick(1664.0)
        s.yes("new capture frame positive control", s.r.rates.quotes.values.any { it.rate == 1664.0 })
        s.generalWorks()
    }

    @Test fun r05_unsentCapture_isDiscardOnly() = s63Test("R05", "prepared", "failed", "unsent") { s, column ->
        if (column == "failed") s.r.failNextWire = 1
        if (column == "unsent") s.r.credentialGate = CompletableDeferred()
        var prepared = 0
        var checks = 0
        s.r.onBeforeCheck = { _, _ ->
            checks++
            s.yes("beforeEncode rechecks have a finite bound", checks <= 8)
        }
        if (column == "prepared") s.r.onPrepared = { _, _ ->
            prepared++
            s.yes("PREPARED attempts have a finite bound", prepared <= 4)
            if (prepared == 1) {
                // One publication race only. Reposting on every reprepare makes the input
                // invalidate its own next attempt forever, before runCurrent can return.
                val open = s.r.snapshot
                s.r.snapshotSupply = { open.copy(revision = open.revision + 1,
                    capabilityInvalidations = open.capabilityInvalidations + 1,
                    facts = open.facts.copy(capabilityBlocks = setOf(com.jay.fxi.data.entitlements.TopicAccessBlock.LOSS_CANDIDATE))) }
                s.r.revise()
            }
        }
        s.r.approve(); s.r.start(); s.r.open()
        if (column == "prepared") s.yes("PREPARED boundary reached", s.r.commands.any { it.prepared.isNotEmpty() })
        if (column == "unsent") s.yes("credential wait prevents physical send", s.r.sends.isEmpty())
        if (column == "failed") s.yes("failed send window reached", s.r.sends.any { !it.accepted })
        s.hold()
        s.eq("PREPARED/failed/no-send produces no real retirement", emptyMap<Any, Any>(), s.records)
        s.eq("no unsubscribe for an unsent capture", 0, s.unsubscribes.size)
        s.eq("custom callback unused", 0, s.r.retirements.requests.size)
        // Positive SENT column is on a separate rig in the same method below.
    }.also {
        s63Test("R05", "sent-positive") { s, _ ->
            s.live(); val tuple = s.tuple(); s.hold()
            s.eq("only SENT has a SESSION result", KrxRetirementLocal.CLEARED_OLD, s.record(tuple).local)
        }
    }

    @Test fun r06_generalRegistryAndLane_surviveRetirement() = s63Test("R06", "mixed", "permit") { s, column ->
        s.live(seconds = 181)
        if (column == "mixed") s.renew() else {
            val after = s.r.sends.size
            s.hold(); s.release(); s.nextKrx(after)
        }
        val connection = s.live; val before = s.immutableGeneral()
        s.yes("GENERAL lane/lease exists before cleanup", s.generalRegistry.held.isNotEmpty())
        s.hold()
        s.eq("KRX registry cleared", emptyMap<Any, Any>(), s.registry.held)
        s.eq("KRX timers canceled", true, listOf("krxExpiryTimer", "krxRenewalTimer").all {
            (field(connection, it) as? kotlinx.coroutines.Job)?.isActive != true
        })
        if (column == "mixed") s.eq("GENERAL ticket/control/in-flight state preserved", before, s.immutableGeneral())
        s.eq("same socket survives", connection, s.live)
        s.generalWorks()
        val general = s.r.sends.last { it.request.type == "subscribe" && it.request.topics.any { t -> t != KRX } }.request
        s.r.ack(general)
        s.yes("GENERAL ACK remains usable", s.r.store.snapshot.stateFor(TopicCatalogue.TETHER).confirmed)
    }

    @Test fun r07_oldAck_cannotRestoreRetiredKrxLease() = s63Test("R07", "mixed-first", "renewal") { s, column ->
        s.live(ack = column == "renewal", rates = false, seconds = 181)
        val request = if (column == "renewal") s.renew() else s.r.first()
        s.yes("old request physically sent", s.r.sends.any { it.accepted && it.request == request })
        s.hold(); s.r.ack(request)
        s.eq("old ACK cannot restore KRX registry", emptyMap<Any, Any>(), s.registry.held)
        s.yes("old ACK cannot confirm KRX", !s.r.store.snapshot.stateFor(KRX).confirmed)
        s.yes("retirement dispatched for old SENT", s.records.isNotEmpty())
        s.yes("GENERAL portion accepted", s.r.store.snapshot.stateFor(TopicCatalogue.TETHER).confirmed)
        val count = s.r.sends.size; s.release(); val next = s.nextKrx(count); s.r.ack(next)
        s.r.krxTick(1677.0)
        s.yes("new ACK/frame is accepted", s.r.rates.quotes.values.any { it.rate == 1677.0 })
    }

    @Test fun r08_oldWsCapture_cannotRepopulateRates() = s63Test("R08", "hold", "epoch", "capInv") { s, column ->
        s.live(); val old = s.active(); val tuple = s.tuple()
        when (column) {
            "epoch" -> s.f.loss("epoch")
            "capInv" -> {
                // Boundary-input: a hold/release happened between notifications. Original capture is
                // still installed when a frame reaches the reader; latest use must not replace it.
                val original = s.r.snapshot
                s.r.snapshotSupply = { original.copy(revision = original.revision + 2,
                    capabilityInvalidations = original.capabilityInvalidations + 1) }
                s.yes("old capture still installed before the missed-publication frame", s.active() === old)
            }
            else -> s.hold()
        }
        if (column != "capInv") s.yes("old accepted capture retired", tuple in s.records)
        s.r.krxTick(1881.0)
        s.yes("old WS capture cannot adopt newer-priced payload", s.r.rates.quotes.values.none { it.rate == 1881.0 })
        if (column == "hold") s.release()
        if (column == "capInv") { s.r.revise(); s.r.drain(); s.yes("revised old tuple is retired", tuple in s.records) }
        s.acceptLatest(1882.0)
        s.yes("K2 original use accepted", s.r.rates.quotes.values.any { it.rate == 1882.0 })
        s.generalWorks()
    }

    @Test fun r09_oldBootstrap_cannotRepopulateRates() = s63Test("R09", "delay", "token", "body", "late-200", "timeout-10000") { s, column ->
        s.f.holdTokens = column == "token"
        s.live(rates = column != "delay", bootstrapDelayMillis = if (column == "delay") 8_000 else null)
        if (column == "timeout-10000") {
            val call = s.issue()
            s.eq("inside original real service BODY budget", KrxA1bFixture.Stage.BODY, call.stage)
            s.r.advance(9_999)
            s.eq("service has not timed out before 10 seconds", null, call.outcome)
            s.r.advance(1)
            s.eq("existing 10-second timeout remains exact", TopicSnapshotOutcome.TimedOut, call.outcome)
            return@s63Test
        }
        if (column == "delay") {
            s.r.request(KRX)
            assertTrue("premise: R09 delay is inside the configured pacing/floor window",
                s.r.clock.nowMillis() < s.r.httpFloor && s.f.krx.isEmpty())
            s.hold()
            s.yes("actual retirement occurs before floor", s.records.isNotEmpty())
            s.r.advance(s.r.httpFloor - 1 - s.r.clock.nowMillis())
            s.eq("no KRX exchange before floor", 0, s.f.krx.size)
        } else {
            val call = s.issue()
            s.eq("inside original request stage", if (column == "token") KrxA1bFixture.Stage.TOKEN else KrxA1bFixture.Stage.BODY, call.stage)
            val gate = if (column == "late-200") s.f.InputGate("BootstrapAnswered") else null
            try {
                if (gate != null) { s.f.reply(call, s.f.delivered(1889.0)); s.eq("real produced answer held", 1, gate.count) }
                s.hold()
                s.yes("SESSION old tuple recorded", s.records.isNotEmpty())
                if (gate == null) s.yes("old logical request cancellation observed", call.cancelled)
                if (gate != null) {
                    s.release()
                    s.yes("old answer released only after current use is allowed again", s.r.snapshotSupply().facts.capabilityAllowed)
                }
                gate?.release()
                s.yes("late 200 cannot publish", s.r.rates.quotes.values.none { it.rate == 1889.0 })
                s.yes("no late persistence offer", s.r.offers.none { rates -> rates.quotes.values.any { it.rate == 1889.0 } })
            } finally { gate?.close() }
        }
        val prior = s.f.krx.size
        s.f.holdTokens = false; s.release()
        val next = s.f.next(prior, budget = 10_100)
        s.f.reply(next, s.f.delivered(1890.0, "2026-10-01T10:20:00+09:00"))
        s.yes("new issue positive control", s.r.rates.quotes.values.any { it.rate == 1890.0 })
        s.generalWorks()
    }

    @Test fun r10_wireFailure_isRecordedWithoutRetry() = s63Test("R10", "false/end", "false/new-permit", "encode-exception/end", "encode-exception/new-permit", "send-exception/end", "send-exception/new-permit", "cancellation", "fatal") { s, column ->
        val fault = column.substringBefore('/')
        val observedFailures = mutableListOf<Throwable>()
        if (fault == "cancellation" || fault == "fatal") {
            // Test-only scope assembly: the real session loop still uses the same serial scheduler
            // and parent Job. Collect the deliberately injected fatal error, never an arbitrary one.
            val original = field(s.session, "scope") as CoroutineScope
            setField(s.session, "scope", CoroutineScope(original.coroutineContext + CoroutineExceptionHandler { _, t -> observedFailures += t }))
        }
        s.live(); val tuple = s.tuple()
        if (fault == "cancellation" || fault == "fatal") {
            val failure = if (fault == "cancellation") kotlinx.coroutines.CancellationException("injected cancellation") else AssertionError("injected fatal")
            var injected = false
            s.r.beforeEncode = { if (it.type == "unsubscribe" && !injected) { injected = true; throw failure } }
            val completions = mutableListOf<Throwable?>()
            val loop = field(s.session, "loop") as Job
            loop.invokeOnCompletion { completions += it }
            s.hold()
            s.yes("actual input reached the fault injection", injected)
            s.yes("cancellation/fatal terminates the real loop with the original failure", completions.singleOrNull() === failure)
            s.eq("only the injected fatal reaches the observer", if (fault == "fatal") listOf(failure) else emptyList<Throwable>(), observedFailures)
            s.yes("cancellation/fatal is never ACCEPTED_UNCONFIRMED", s.records[tuple]?.wire != KrxRetirementWire.ACCEPTED_UNCONFIRMED)
            return@s63Test
        }
        if (fault == "false") s.r.wire.failSends = 1
        if (fault == "encode-exception") s.r.beforeEncode = { if (it.type == "unsubscribe") throw IOException("injected encode") }
        if (fault == "send-exception") {
            val transport = field(s.live, "transport") as TopicTransport
            val socket = field(transport, "socket") as WebSocket
            setField(transport, "socket", object : WebSocket by socket {
                override fun send(text: String): Boolean {
                    if (s.r.requests.lastOrNull { "encoded-${it.requestId}" == text }?.type == "unsubscribe") throw IOException("injected send")
                    return socket.send(text)
                }
            })
        }
        s.hold()
        val record = s.record(tuple)
        s.eq("old local removed despite wire failure", KrxRetirementLocal.CLEARED_OLD, record.local)
        s.eq("failure remains failure", if (fault == "false") KrxRetirementWire.FAILED_FALSE else KrxRetirementWire.FAILED_EXCEPTION, record.wire)
        s.eq("live connection owns unresolved remote result", KrxRetirementDisposition.OPEN_CONNECTION_BOUND, record.disposition)
        s.eq("local registry cleared before failure", emptyMap<Any, Any>(), s.registry.held)
        val attempts = s.unsubscribeRequests.size
        s.r.advance(35_000); s.session.setForeground(false); s.session.setForeground(true); s.r.drain()
        s.repeatHeldPublication()
        s.eq("time/foreground/repeat never retries old wire", attempts, s.unsubscribeRequests.size)
        s.generalWorks()
        if (column.endsWith("/new-permit")) {
            val after = s.r.sends.size
            s.release(); val next = s.nextKrx(after); s.r.ack(next)
            s.eq("new current capture never retries old unsubscribe", attempts, s.unsubscribeRequests.size)
            s.eq("supersession preserves original failure", record.wire, s.record(tuple).wire)
            s.eq("new accepted capture supersedes old remote obligation", KrxRetirementDisposition.SUPERSEDED_BY_NEW_CAPTURE, s.record(tuple).disposition)
        } else {
            s.session.setOnline(false); s.r.drain()
            s.eq("end preserves original failure", record.wire, s.record(tuple).wire)
            s.eq("only disposition ends locally", KrxRetirementDisposition.LOCAL_CONNECTION_ENDED, s.record(tuple).disposition)
        }
    }

    @Test fun r11_unsubscribeAck_isIsolatedFromSubscribeState() = s63Test("R11") { s, _ ->
        s.live(seconds = 181); val pending = s.renew(); s.hold()
        val unsubscribe = s.need(s.unsubscribeRequests.lastOrNull(), "actual separate unsubscribe request")
        s.yes("separate request ID", unsubscribe.requestId != pending.requestId)
        val before = s.r.store.snapshot; val leases = s.generalRegistry.held
        val state = s.immutableGeneral()
        val ack = s.r.ackText(pending).replace(pending.requestId, unsubscribe.requestId).replace("\"operation\":\"subscribe\"", "\"operation\":\"unsubscribe\"")
        s.r.wire.deliver(ack); s.r.drain()
        s.eq("unsubscribe whole active snapshot ignored", before, s.r.store.snapshot)
        s.eq("GENERAL lease expiry unchanged", leases, s.generalRegistry.held)
        s.eq("in-flight GENERAL ticket/control unchanged", state, s.immutableGeneral())
        s.r.ack(pending)
        s.yes("original GENERAL ACK still settles", s.r.store.snapshot.stateFor(TopicCatalogue.TETHER).confirmed)
    }

    @Test fun r12_retirementFinishesBeforeNewSubscription() = s63Test("R12", "accepted", "failed", "suppressed") { s, column ->
        s.live(seconds = 181); val old = s.tuple(); val oldWire = s.r.wire
        if (column == "failed") s.r.wire.failSends = 1
        var observed = false
        if (column == "suppressed") {
            val next = s.renew(); s.r.ack(next)
            s.eq("promotion record completed", KrxRetirementWire.SKIPPED_NEWER_CAPTURE, s.record(old).wire)
        }
        s.r.beforeEncode = { request ->
            if (request.type == "subscribe") {
                s.yes("old final result visible synchronously before K2 encode", old in s.records)
                s.yes("old result has final wire outcome", s.records[old] != null)
                observed = true
            }
        }
        s.hold(); s.release(); s.r.drain()
        s.yes("new command observed after old retirement", observed)
        val oldCount = s.unsubscribeRequests.size
        s.r.reconnect(); s.r.ack()
        s.yes("reconnect owns a new socket", s.r.wire !== oldWire)
        s.eq("old record not replayed on reconnect", oldCount, s.unsubscribeRequests.size)
        s.eq("old record retained for session lifetime", true, old in s.records)
        s.generalWorks()
    }

    @Test fun r13_queuedOldBootstrapAnswer_isNotApplied() = s63Test("R13", "queued-before-hold") { s, _ ->
        // No KRX WS frame: it would cancel the original REST job before a 200 could be queued.
        s.live(rates = false)
        val call = s.issue()
        s.eq("original admitted REST call is at the real HTTP body", KrxA1bFixture.Stage.BODY, call.stage)
        s.yes("original REST use is admitted before the answer is produced", call.admitted() && !call.cancelled)
        val connection = s.live; val tuple = s.tuple()
        s.r.generalTick(1443.0)
        fun issues(): Map<*, *> = s.need(field(s.session, "krxRestIssues") as? Map<*, *>, "original REST issue registry").toMap()
        // Reuse alpha's gate and its real current-wire FIFO barrier without editing either helper.
        field(s.session, "inputs"); field(s.session, "onUndecodable") // guarded field-existence preflight
        val gate = KrxA4InputGate(s.r, "BootstrapAnswered") { field(it, "topic") == KRX }
        try {
            s.f.reply(call, s.f.delivered(1913.0, "2026-10-02T10:20:00+09:00"))
            val answer = s.need(gate.captured.singleOrNull(), "one real produced KRX BootstrapAnswered is held")
            val issueId = s.need(field(answer, "krxIssueId") as? Long, "held answer's original issue ID")
            val issue = s.need(issues()[issueId], "held answer still owns its registered original issue")
            val use = s.need(field(issue, "use") as? com.jay.fxi.data.entitlements.KrxUseLifetime, "original issue use")
            val issuedAt = s.need(field(issue, "issuedAtMillis") as? Long, "original issue budget start")
            s.yes("200 completed before withdrawal and is the exact queued outcome",
                call.stage == KrxA1bFixture.Stage.RETURNED && call.outcome is TopicSnapshotOutcome.Delivered &&
                    field(answer, "outcome") === call.outcome && !call.cancelled)
            s.eq("queued answer carries the original GENERAL lifetime", use.lifetime, field(answer, "lifetime"))
            s.eq("answer has not been released before withdrawal", emptyList<Any>(), gate.released)

            // boundary-input: existing same-p/same-epoch hold; exact PAC reachability unconfirmed.
            // The real successful REST producer is already finished, so cancellation cannot erase its answer.
            s.hold()
            s.yes("withdrawal was processed while the completed answer stayed held",
                !s.r.snapshotSupply().facts.capabilityAllowed && !s.r.uses.admitsKrx(use) &&
                    s.r.uses.admits(use.lifetime) && !call.cancelled && gate.released.isEmpty())
            s.yes("original issue survives withdrawal until its answer is handled", issues()[issueId] === issue)
            s.eq("same current GENERAL connection survives withdrawal", connection, s.live)
            s.eq("queued answer still belongs to this grant", field(s.session, "grantEpoch"), field(answer, "grantEpoch"))
            s.yes("answer remains inside its original ten-second application budget",
                s.r.clock.nowMillis() - issuedAt in 0L until TopicBootstrapRetryPolicy.ATTEMPT_BUDGET_MILLIS)
            val ws = s.r.sends.count { it.accepted && it.request.type == "subscribe" && KRX in it.request.topics }
            val rest = s.f.krx.size; val http = s.f.krx.sumOf { it.sends.size }
            val offers = s.r.offers.size; val graph = s.r.graph.size

            gate.release()
            gate.assertProcessedBarrier()
            s.yes("the exact released answer was consumed by the real session handler",
                gate.released.singleOrNull() === answer && issueId !in issues())
            // Behaviour assertions follow the processed-input proof, rather than a cancelled HTTP job.
            s.noKrx()
            s.generalWorks(1448.0)
            s.r.advance(2_100)
            s.noKrx()
            s.yes("queued old 200 emits no KRX persistence offer",
                s.r.offers.drop(offers).none { S6KrxCleanupRig.hasKrx(it) })
            s.yes("queued old 200 emits no KRX graph observation", s.r.graph.drop(graph).none {
                it is TopicGraphInput.Observations && it.topic == KRX })
            s.eq("queued old answer starts no follow-up KRX WS subscribe", ws,
                s.r.sends.count { it.accepted && it.request.type == "subscribe" && KRX in it.request.topics })
            s.eq("queued old answer starts no follow-up KRX logical REST issue", rest, s.f.krx.size)
            s.eq("queued old answer starts no follow-up KRX HTTP exchange", http, s.f.krx.sumOf { it.sends.size })
            s.generalWorks(1449.0)
            // The compile-only stub drops typed KRX REST already, but still lacks SESSION cleanup.
            // Check that real withdrawal effect last so the stub traverses the queued-answer window too.
            s.yes("processed withdrawal dispatched the original SESSION retirement", s.records[tuple] != null)
        } finally { gate.close() }
    }
}
