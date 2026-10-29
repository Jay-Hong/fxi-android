package com.jay.fxi.service

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.S6Krx3cShellSourceContract
import java.io.File
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.service.S6PendingAlertTapRig.Companion.EXCHANGES
import com.jay.fxi.service.S6PendingAlertTapRig.Companion.sourceEvent
import com.jay.fxi.service.S6PendingAlertTapRig.Companion.comparisonEvent
import com.jay.fxi.service.S6PendingAlertTapRig.Companion.source
import com.jay.fxi.service.S6PendingAlertTapRig.Companion.comparison
import com.jay.fxi.service.S6PendingAlertTapRig.Companion.metadata
import com.jay.fxi.service.S6PendingAlertTapRig.Companion.raw
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class S6PendingAlertTapContractTest {
    @Test fun p01_sourceMixedKeepsOnlyNonKrx() = s63cTest("P01", "forward", "reverse") { p, order ->
        val cases = listOf("krx") + EXCHANGES
        val tickets = (if (order == "reverse") cases.reversed() else cases).map { name ->
            val m = source(name)
            val t = p.capture(sourceEvent(), m, column = name)
            p.eq(name, "exact valid SOURCE classification", if (name == "krx") AlertTapClassification.KRX else AlertTapClassification.NON_KRX, p.owner.classify(t))
            t to p.owned(t, name)
        }
        p.eq(order, "same settingId still has six tickets", 6, tickets.map { it.first }.toSet().size)
        tickets.forEach { p.owned(it.first, order) }
        p.g.session.capEnd(); val report = p.report()
        p.eq(order, "only one KRX removed", 1, report.removedKrx)
        p.eq(order, "nonKRX raw records and FIFO preserved", tickets.filter { it.second.metadata.source != "krx" }, p.inventory().toList())
    }

    @Test fun p02_comparisonChecksBothValidSides() = s63cTest("P02", "forward", "reverse") { p, order ->
        val fixtures = listOf(comparison(left = "krx", right = "kb"), comparison(), comparison(right = "kb"),
            comparison("tether", "upbit", "krx"), comparison("tether", "upbit", "bithumb"),
            comparison("tether", "upbit", "hana"), comparison("jpy", "kb", "hana"), comparison("eur", "kb", "hana"))
        val expected = listOf(true, true, false, true, false, false, false, false)
        val rows = fixtures.zip(expected).let { if (order == "reverse") it.reversed() else it }
        val taps = rows.mapIndexed { n, (m, krx) ->
            val t = p.capture(comparisonEvent(), m, column = "$order-$n")
            p.eq("$order-$n", "both sides validated", if (krx) AlertTapClassification.KRX else AlertTapClassification.NON_KRX, p.owner.classify(t))
            Triple(t, p.owned(t), krx)
        }
        taps.forEach { p.owned(it.first, order) }; p.g.session.capEnd(); p.report()
        p.eq(order, "only complete pairs with either KRX side are removed", taps.filter { !it.third }.map { it.first to it.second }, p.inventory().toList())
    }

    @Test fun p03_bankAckPreservesLegacyAction() = s63cTest("P03", "42", "missing", "empty", "sync") { p, col ->
        val route = p.need(col, AlertPushRouting.route(if (col == "sync") "sync_alerts" else "rate_alert", if (col == "42") "42" else if (col == "empty") "" else null), "HEAD routing positive")
        val expected = if (col == "42") BankAlertAction.Trigger(42) else BankAlertAction.Refresh
        p.eq(col, "HEAD bank action unchanged", expected, AlertPushRouting.bankAction(route.event))
        val t = p.capture(route.event, AlertTapMetadata(), column = col)
        val original = p.owned(t, col)
        p.g.session.capEnd(); p.report()
        p.eq(col, "capability end keeps BANK raw original", original, p.owned(t, col))
        p.eq(col, "HANDLED receipt", AlertTapReceipt.HANDLED, p.owner.consume(t, p.g.snapshot, p.g.r.identity, p::bankConsumer))
        p.eq(col, "one local legacy action", listOf(route.event), p.delivered)
        p.eq(col, "only legacy refresh scheduled", if (col == "42") 0 else 1, p.localRefreshes)
        p.eq(col, "ACK releases owner", emptyMap<AlertTapTicket, PendingAlertTap>(), p.inventory())
    }

    @Test fun p04_invalidMetadataStaysUnknown() = s63cTest("P04") { p, _ ->
        val valid = listOf(source(), comparison())
        val invalids = listOf("missing", "null", "empty", "space", "case", "unknown", "wrong-type", "tuple")
        for ((family, base) in valid.withIndex()) {
            val event = if (family == 0) sourceEvent() else comparisonEvent()
            val control = p.capture(event, base, column = "valid-$family")
            p.eq("valid-$family", "valid control classified KRX", AlertTapClassification.KRX, p.owner.classify(control))
            for (key in base.presentKeys) for (kind in invalids) {
                val raw = raw(base).toMutableMap()
                when (kind) {
                    "missing" -> raw.remove(key)
                    "null" -> raw[key] = null
                    "empty" -> raw[key] = ""
                    "space" -> raw[key] = " "
                    "case" -> raw[key] = (raw[key] as String).uppercase()
                    "unknown" -> raw[key] = "unregistered"
                    "wrong-type" -> raw[key] = 42
                    "tuple" -> raw[key] = if (key.endsWith("asset")) "eur-krw" else if (key == "tab") "jpy" else "upbit"
                }
                val col = "$family-$key-$kind"
                val m = metadata(raw); val t = p.capture(event, m, column = col)
                p.eq(col, "invalid entire tuple quarantined", AlertTapClassification.UNKNOWN, p.owner.classify(t))
                val original = p.owned(t, col); val before = p.audit()
                p.eq(col, "UNKNOWN not consumed", AlertTapReceipt.REJECTED, p.consume(t))
                p.eq(col, "UNKNOWN raw retained", original, p.owned(t, col))
                p.eq(col, "UNKNOWN has reason", true, p.owned(t).reason != null)
                p.eq(col, "no classification lookup", before, p.audit())
            }
        }
        val conflict = p.capture(sourceEvent(), source().copy(typeConflict = true), column = "type-conflict")
        p.eq("type-conflict", "aliases conflict is UNKNOWN", AlertTapClassification.UNKNOWN, p.owner.classify(conflict))
        val unknown = p.inventory().filterValues { it.classification == AlertTapClassification.UNKNOWN }
        p.yes("pre-revoke", "unknown targets still owned", unknown.isNotEmpty())
        p.g.session.capEnd(); p.report()
        p.eq("post-revoke", "no guessed KRX deletion", unknown, p.inventory())
    }

    @Test fun p05_userEndDropsOnlyOldOwner() = s63cTest("P05", "A-B", "A-null-A", "generation", "USER-end", "premium-loss") { p, col ->
        val oldIdentity = p.need(col, p.g.r.identity, "initial identity")
        val old = listOf(p.capture(), p.capture(sourceEvent(), source("upbit")),
            p.capture(AlertEvent.RefreshNeeded, AlertTapMetadata()), p.capture(sourceEvent(), AlertTapMetadata()))
        p.consume(old[1], AlertTapReceipt.UNHANDLED)
        old.forEach { p.owned(it, col) }
        when (col) {
            "A-B" -> p.identity(AuthIdentityFence("user-b", oldIdentity.authGeneration + 1))
            "A-null-A" -> { p.identity(null); p.identity(AuthIdentityFence(oldIdentity.uid, oldIdentity.authGeneration + 1)) }
            "generation" -> p.identity(AuthIdentityFence(oldIdentity.uid, oldIdentity.authGeneration + 1))
            "USER-end" -> { p.g.r.pac.onSignedOut(oldIdentity).applied(); p.g.r.drain() }
            else -> { p.g.r.h.source.next = { EntitlementsOutcome.StableInactive(false) }; p.g.r.pac.refresh(RefreshIntent.FORCE_PREMIUM); p.g.r.drain() }
        }
        p.report()
        p.eq(col, "every old family including UNKNOWN and UNHANDLED is retired", emptySet<AlertTapTicket>(), p.inventory().keys)
        for (t in old) {
            p.owner.classify(t)
            p.eq(col, "late ACK cannot execute old identity", AlertTapReceipt.REJECTED, p.consume(t))
        }
        if (col in listOf("A-B", "A-null-A", "generation")) {
            val fresh = p.capture(sourceEvent(), source("upbit"), context = p.context().copy(scope = null), column = "$col-new")
            p.owned(fresh, "$col-new")
        }
    }

    @Test fun p06_revokeOrdersCannotResurrectTap() = s63cTest("P06", "before-capture", "before-classify", "after-classify", "queued-before-claim", "after-claim", "F01-read-then-revoke", "F01-after-claim") { p, col ->
        if (col.startsWith("F01")) { s63cFinalReadBoundary(p, queued = false, afterClaim = col == "F01-after-claim"); return@s63cTest }
        val context = p.context()
        if (col == "before-capture") p.g.session.capEnd()
        val t = p.capture(context = context, classify = false, column = col)
        p.owned(t, col)
        if (col == "after-claim") {
            p.owner.classify(t)
            val before = p.delivered.size
            val receipt = p.owner.consume(t, p.g.snapshot, p.g.r.identity) { event ->
                // Claim already linearized; nested attempt must not run the consumer twice.
                p.eq(col, "duplicate claim rejected", AlertTapReceipt.REJECTED, p.consume(t))
                p.delivered += event; AlertTapReceipt.HANDLED
            }
            p.eq(col, "legitimate ACK accepted", AlertTapReceipt.HANDLED, receipt)
            p.g.session.capEnd(); p.report()
            p.eq(col, "one accepted action", before + 1, p.delivered.size)
        } else {
            if (col != "before-classify" && col != "before-capture") p.owner.classify(t)
            if (col == "queued-before-claim") p.bus.forwardPendingTap(AlertEvent.PendingTap(t))
            if (col != "before-capture") p.g.session.capEnd()
            if (col != "queued-before-claim") p.report() // queued column tests final gate without a reconcile call.
            p.owner.classify(t)
            p.eq(col, "preclaim revocation executes nothing", AlertTapReceipt.REJECTED, p.consume(t))
            p.report()
            p.yes(col, "late classification never resurrects original", t !in p.inventory())
            p.eq(col, "no callback", emptyList<AlertEvent>(), p.delivered)
        }
    }

    @Test fun p07_regrantAdmitsOnlyFreshTicket() = s63cTest("P07", "regrant", "hold", "coalesced-rotation") { p, col ->
        val old = p.capture(classify = false); val original = p.owned(old, col)
        if (col == "hold") {
            p.g.session.cleanup.hold(); val held = p.report()
            p.eq(col, "hold is not successful deletion", 0, held.removedKrx)
            p.eq(col, "hold retains raw original", listOf(original.event, original.metadata, original.context), p.owned(old, col).let { listOf(it.event, it.metadata, it.context) })
            p.eq(col, "held original cannot consume", AlertTapReceipt.REJECTED, p.consume(old))
            p.g.session.cleanup.f.holdRoundTrip(false); p.g.r.drain()
        } else { p.g.session.capEnd(); if (col != "coalesced-rotation") p.report(); p.g.session.capReopen() }
        val fresh = p.capture(column = "K2")
        p.owned(fresh, "K2"); p.owner.classify(old); p.report()
        p.eq(col, "old use not upgraded", AlertTapReceipt.REJECTED, p.consume(old))
        p.owned(fresh, "K2-after-old-purge")
        p.eq(col, "fresh ticket consumed", AlertTapReceipt.HANDLED, p.consume(fresh))
    }

    @Test fun p08_rootForwarderTransfersCustodyExactlyOnce() = s63cTest("P08", "cold", "restored", "rejected", "late-slot", "no-custody-pending-ticket") { p, col ->
        if (col == "no-custody-pending-ticket") {
            val carrier = AlertEvent.PendingTap(AlertTapTicket("foreign-owner", 17))
            var slot: AlertEvent? = carrier; var consumeCalls = 0; var custodyCalls = 0
            p.eq(col, "pending ticket without custody is refused", false, AlertTapEntry.forwardRoot(carrier, {
                custodyCalls++; p.eq(col, "custody receives the original ticket", carrier, it); false
            }) { expected -> consumeCalls++; slot = AlertTapEntry.clearSlot(slot, expected) })
            p.eq(col, "custody was actually checked", 1, custodyCalls)
            p.eq(col, "refusal calls consumeSlot zero times", 0, consumeCalls)
            p.eq(col, "refusal preserves the original slot", carrier, slot)
            return@s63cTest
        }
        val extras = S6TypedTapExtras(mapOf("type" to "source_rate_alert", "setting_id" to "7") + raw(source()))
        val before = extras.values.toMap(); var captures = 0
        val intake = p.intake(capture = { e, m, c -> captures++; if (col == "rejected") AlertTapCaptureResult.REJECTED else p.owner.capture(e, m, c) })
        val result = AlertTapEntry.intakeNew(extras, col == "restored", intake)
        if (col == "restored" || col == "rejected") {
            p.eq(col, "restoration never recaptures", if (col == "restored") 0 else 1, captures)
            p.eq(col, "no custody retains extras", before, extras.values)
            p.eq(col, "no custody creates no owner record", 0, p.inventory().size)
        } else {
            val ticket = p.need(col, (result as? AlertTapCaptureResult.CAPTURED)?.ticket, "pure cold entry obtained custody")
            p.owned(ticket, col)
            p.yes(col, "custody clears original alert extras", extras.values.keys.none { it in before })
            val expected = AlertEvent.PendingTap(ticket)
            var slot: AlertEvent? = expected; var clears = 0
            val fresh = if (col == "late-slot") AlertEvent.PendingTap(p.capture(column = "new-slot")) else null
            if (fresh != null) slot = fresh
            fun forward() = AlertTapEntry.forwardRoot(expected, { e -> (e as? AlertEvent.PendingTap)?.ticket in p.inventory() }) { e ->
                val next = AlertTapEntry.clearSlot(slot, e)
                if (slot != next) clears++
                slot = next
            }
            p.eq(col, "custody permits root forwarding", true, forward())
            forward()
            p.eq(col, "late old callback cannot clear fresh slot", fresh, slot)
            p.eq(col, "slot change exactly once or zero if replaced", if (fresh == null) 1 else 0, clears)
            p.owned(ticket, col) // custody transfer is distinct from HANDLED.
        }
        var callbacks = 0
        p.eq(col, "no custody blocks callback", false, AlertTapEntry.forwardRoot(AlertEvent.RefreshNeeded, { false }) { callbacks++ })
        p.eq(col, "callback not called before custody", 0, callbacks)
    }

    @Test fun p09_busLossIsNotDeliveryAcknowledgement() = s63cTest("P09", "no-subscriber", "FULL", "cancel", "retired", "throw", "callback-cancel", "F01-read-then-revoke", "F01-after-claim", "F02-two-tickets", "F02-unrelated-turns-own-HANDLED", "F03-detach-reservation") { p, col ->
        if (col.startsWith("F01")) { s63cFinalReadBoundary(p, queued = true, afterClaim = col == "F01-after-claim"); return@s63cTest }
        if (col == "F02-two-tickets") { s63cConcurrentConsumeBoundary(p); return@s63cTest }
        if (col == "F02-unrelated-turns-own-HANDLED") { s63cUnrelatedTicketBoundary(p); return@s63cTest }
        if (col == "F03-detach-reservation") { s63cDetachReadBoundary(p); return@s63cTest }
        val t = p.capture(AlertEvent.SettingTriggered(7), AlertTapMetadata(), column = col)
        val carrier = AlertEvent.PendingTap(t); val original = p.owned(t, col)
        p.eq(col, "no-subscriber SharedFlow accepts without ACK", true, p.bus.forwardPendingTap(carrier))
        p.eq(col, "tryEmit is not ACK", original, p.owned(t, col))
        if (col == "FULL" || col == "cancel") {
            val gate = CompletableDeferred<Unit>()
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { p.bus.events.collect { gate.await() } }
            val lease = p.bus.registerPendingTapConsumer()
            try {
                var full = false
                repeat(32) { if (!p.bus.emit(AlertEvent.SettingTriggered(100 + it))) full = true }
                p.yes(col, "real buffer reached FULL=false boundary", full)
                p.bus.forwardPendingTap(carrier)
                p.eq(col, "FULL still owns original", original, p.owned(t, col))
            } finally { lease.close(); job.cancel(); gate.complete(Unit); runCurrent() }
            p.eq(col, "detached collector cannot ACK", original, p.owned(t, col))
        }
        if (col == "retired") {
            val gate = CompletableDeferred<Unit>()
            val queued = mutableListOf<AlertEvent>()
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                p.bus.events.collect { event -> queued += event; gate.await(); p.bus.consumePendingTap(event, p::bankConsumer) }
            }
            runCurrent()
            val lease = p.bus.registerPendingTapConsumer(); runCurrent()
            try {
                p.yes(col, "retired target is actually emitted and suspended at a collector before claim", carrier in queued)
                p.owned(t, col)
                p.identity(null); p.report()
                gate.complete(Unit); runCurrent()
                p.eq(col, "released actual queued retired carrier executes zero", emptyList<AlertEvent>(), p.delivered)
            } finally { lease.close(); job.cancel(); gate.complete(Unit); runCurrent() }
        }
        if (col == "throw" || col == "callback-cancel") {
            var receipt: AlertTapReceipt? = null
            try { receipt = p.bus.consumePendingTap(carrier) {
                if (col == "throw") throw IllegalStateException("boundary-input: consumer throw")
                else throw CancellationException("boundary-input: consumer cancelled")
            } } catch (_: IllegalStateException) { }
            p.yes(col, "consumer exception is never HANDLED", receipt != AlertTapReceipt.HANDLED)
            p.owned(t, col)
        }
        val previous = p.delivered.size
        p.collect(); runCurrent()
        if (col == "retired") {
            p.eq(col, "retired queued carrier cannot unwrap", AlertTapReceipt.REJECTED, p.bus.consumePendingTap(carrier, p::bankConsumer))
            p.eq(col, "retired consumer executes zero", previous, p.delivered.size)
        } else {
            p.eq(col, "ready replays unacknowledged intent exactly once", previous + 1, p.delivered.size)
            p.eq(col, "HANDLED retires intent", 0, p.inventory().size)
            p.eq(col, "late duplicate rejected", AlertTapReceipt.REJECTED, p.bus.consumePendingTap(carrier, p::bankConsumer))
        }
    }

    @Test fun p10_deletedSettingsNeedNoLookup() = s63cTest("P10", "exists", "deleted", "throw", "timeout", "503", "no-tap") { p, col ->
        S6SettingsEndpointFixture(col).use { endpoint ->
            // Classification has no settings port. Lock that absence against an added fallback (μ17),
            // then audit the real checked PAC/auth alongside the actual runtime classification.
            val sourceText = listOf("PendingAlertTapOwner.kt", "AlertTapEntry.kt").joinToString("\n") {
                File("src/main/java/com/jay/fxi/service/$it").readText()
            }
            val executable = S6Krx3cShellSourceContract.mask(sourceText)
            p.eq(col, "no lookup/HTTP/token/repository fallback dependency", false,
                Regex("""\b(?:FXiApiService|AlertRepository|SourceAlertRepository|ComparisonAlertRepository|AuthenticatedTransport|AuthTokenProvider|currentSnapshot|fetchToken|refreshAfterUnauthorized|requestRefresh)\b""").containsMatchIn(executable))
            endpoint.verifyServing()
            p.yes(col, "installed all three real settings paths on HTTP endpoint", endpoint.baseUrl.isHttps.not() && endpoint.paths.size == 3)
            p.eq(col, "actual settings request counter is zero before tap", emptyList<String>(), endpoint.settingsRequests())
            val before = p.audit()
            val taps = if (col == "no-tap") emptyList() else listOf(p.capture(), p.capture(sourceEvent(), AlertTapMetadata()))
            repeat(3) { taps.forEach(p.owner::classify); p.report(); runCurrent() }
            p.eq(col, "classification causes no FE/token/settings requests", before, p.audit())
            if (taps.isNotEmpty()) {
                p.eq(col, "valid metadata independent of row availability", AlertTapClassification.KRX, p.owner.classify(taps[0]))
                p.eq(col, "missing metadata never infers nonKRX", AlertTapClassification.UNKNOWN, p.owner.classify(taps[1]))
            }
            p.eq(col, "actual settings endpoint calls remain zero after valid/UNKNOWN/no-tap flow", emptyList<String>(), endpoint.settingsRequests())
        }
    }

    @Test fun p11_everyPureEntryRoutePreservesTypedFields() = s63cTest("P11") { p, _ ->
        val normals = listOf("source_rate_alert" to source(), "source_rate_alert" to source("upbit"),
            "comparison_alert" to comparison(left = "krx", right = "kb"), "comparison_alert" to comparison(),
            "comparison_alert" to comparison(right = "kb"), "rate_alert" to AlertTapMetadata())
        val cases = normals.flatMap { (type, m) ->
            listOf(Triple(type, raw(m), "valid")) + m.presentKeys.flatMap { key ->
                listOf("missing", "null", "empty", "unknown", "wrong-type").map { kind ->
                    val map = raw(m).toMutableMap()
                    when (kind) { "missing" -> map.remove(key); "null" -> map[key] = null; "empty" -> map[key] = ""; "unknown" -> map[key] = "unregistered"; else -> map[key] = 12 }
                    Triple(type, map, "$key-$kind")
                }
            }
        }
        for (route in listOf("custom-cold", "server-warm", "foreground-cold", "foreground-warm", "fcm-cold", "fcm-warm")) {
            for ((type, fields, variant) in cases) for (id in listOf<Any?>(42, "42", 0, 2147483647, -1, "-1", "", "x", "4.2", null, true, Unit)) {
                val col = "$route-$type-$variant-id=$id"
                val custom = route.startsWith("custom") || route.startsWith("foreground")
                val typeKey = if (custom) "notification_type" else "type"
                val extras = S6TypedTapExtras((mapOf(typeKey to type) + if (id === Unit) emptyMap() else mapOf("setting_id" to id)) + if (route.startsWith("foreground")) emptyMap() else fields)
                if (route.startsWith("foreground")) {
                    // FCM data is Map<String,String>. Corrupt typed extras at the next input boundary
                    // after executing the real copier, rather than pretending the Map carried an Int.
                    val strings = fields.filterValues { it is String }.mapValues { it.value as String }
                    AlertTapEntry.copyForegroundClassification(strings, extras)
                    p.eq(col, "foreground copier preserves exact present keys and empty", strings, extras.values.filterKeys { it in AlertTapCodec.CLASSIFICATION_KEYS })
                    extras.values.putAll(fields.filterValues { it !is String })
                }
                val original = extras.values.toMap(); var contextBeforeRead = false
                val intake = p.intake(context = { contextBeforeRead = extras.calls.none { it.startsWith("string:") || it.startsWith("int:") }; p.context() })
                val result = if (route.endsWith("warm")) AlertTapEntry.intakeWarm(extras, intake) else AlertTapEntry.intakeNew(extras, false, intake)
                val ticket = p.need(col, (result as? AlertTapCaptureResult.CAPTURED)?.ticket, "pure entry owner custody")
                p.yes(col, "context is captured before raw reads", contextBeforeRead)
                val held = p.owned(ticket, col)
                p.eq(col, "typed raw metadata transported", metadata(fields).copy(rawType = if (custom) null else type,
                    rawCustomType = if (custom) type else null, rawSettingId = if (id is Int && id != -1) id.toString() else id as? String), held.metadata)
                val expectedId = if (id is Int && id != -1) id.toString() else id as? String
                p.eq(col, "legacy id and routing preserved", AlertPushRouting.route(type, expectedId)?.event, held.event)
                p.yes(col, "int getter before string id fallback", extras.calls.indexOf("int:setting_id:-1") >= 0 &&
                    (id is Int && id != -1 || extras.calls.indexOf("int:setting_id:-1") < extras.calls.indexOf("string:setting_id")))
                p.yes(col, "metadata copied immutably before clear", original.keys.none { it in extras.values })
            }
        }
        for ((label, envelope) in listOf(
            "type-conflict" to mapOf("notification_type" to "rate_alert", "type" to "source_rate_alert"),
            "type-int" to mapOf("notification_type" to 12, "type" to "source_rate_alert"),
            "type-other" to mapOf("type" to true),
            "type-unregistered" to mapOf("type" to "new_alert"))) {
            val extras = S6TypedTapExtras(envelope + mapOf("setting_id" to "7") + raw(source()))
            val result = AlertTapEntry.intakeNew(extras, false, p.intake())
            val ticket = p.need(label, (result as? AlertTapCaptureResult.CAPTURED)?.ticket, "invalid envelope is quarantined, not discarded")
            p.eq(label, "invalid type never becomes known nonKRX", AlertTapClassification.UNKNOWN, p.owner.classify(ticket))
            p.owned(ticket, label)
        }
        val irrelevant = S6TypedTapExtras(mapOf("title" to "krx usd-krw-futures", "body" to "source_rate_alert"))
        val irrelevantBefore = irrelevant.values.toMap()
        p.eq("NOT_ALERT", "no type/routed event is not fabricated from title/body", AlertTapCaptureResult.NOT_ALERT,
            AlertTapEntry.intakeNew(irrelevant, false, p.intake()))
        p.eq("NOT_ALERT", "unrelated extras preserved", irrelevantBefore, irrelevant.values)
        val ingress = p.context()
        val racing = S6TypedTapExtras(mapOf("type" to "source_rate_alert", "setting_id" to 7) + raw(source()))
        var changed = false
        racing.beforeRead = { if (!changed) { changed = true; p.g.r.identity = AuthIdentityFence("next-owner", 99) } }
        val result = AlertTapEntry.intakeNew(racing, false, p.intake(context = { ingress }))
        val ticket = p.need("read-race", (result as? AlertTapCaptureResult.CAPTURED)?.ticket, "raw read race still captures original ingress context")
        p.eq("read-race", "raw read cannot replace original identity with latest", ingress, p.owned(ticket).context)
        p.eq("read-race", "final live mismatch blocks consumer", AlertTapReceipt.REJECTED, p.consume(ticket))
        p.eq("keys", "classification key inventory exact", listOf("source", "asset", "tab", "left_source", "left_asset", "right_source", "right_asset"), AlertTapCodec.CLASSIFICATION_KEYS)
    }

    @Test fun p12_unhandledFamiliesRemainOwned() = s63cTest("P12") { p, _ ->
        val items = listOf(sourceEvent() to source(), sourceEvent() to source("upbit"),
            comparisonEvent() to comparison(), comparisonEvent() to comparison(right = "kb"))
        val taps = items.map { (e, m) -> p.capture(e, m) }
        p.collect()
        taps.forEach { p.bus.forwardPendingTap(AlertEvent.PendingTap(it)) }; runCurrent()
        val before = p.carriers.size
        taps.forEach { p.owned(it, "unhandled") }
        repeat(4) { p.report(); runCurrent() }
        p.eq("no-retry", "UNHANDLED not emitted once per revision", before, p.carriers.size)
        p.eq("report", "unhandled counted separately from UNKNOWN", 4, p.report().unhandled)
        val bank = p.capture(AlertEvent.RefreshNeeded, AlertTapMetadata())
        p.bus.forwardPendingTap(AlertEvent.PendingTap(bank)); runCurrent()
        p.yes("BANK", "positive consumer ACK releases BANK", bank !in p.inventory())
        taps.forEach { p.owned(it, "pre-revoke") }; p.g.session.capEnd(); p.report()
        p.eq("selective", "retained KRX only removed", listOf(taps[1], taps[3]), p.inventory().keys.toList())
    }

    @Test fun p13_mixedQueuePreservesKnownOrderAndMultiplicity() = s63cTest("P13", "mixed-FULL-UNKNOWN", "F04-contention", "F04-detach-other-registration", "exclude-UNKNOWN", "exclude-CONTEXT_UNKNOWN", "exclude-withheld-KRX", "exclude-UNHANDLED", "CLAIMED-HANDLED", "CLAIMED-UNHANDLED") { p, col ->
        if (col.startsWith("F04")) { s63cFifoRetryBoundary(p, detachOther = col == "F04-detach-other-registration"); return@s63cTest }
        if (col.startsWith("exclude-")) { s63cFifoExcludedPredecessor(p, col); return@s63cTest }
        if (col.startsWith("CLAIMED-")) { s63cFifoClaimedPredecessor(p, col == "CLAIMED-UNHANDLED"); return@s63cTest }
        val gate = CompletableDeferred<Unit>()
        val blocked = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            p.bus.events.collect { event -> gate.await(); p.bus.consumePendingTap(event, p::bankConsumer) }
        }
        runCurrent()
        val ready = p.bus.registerPendingTapConsumer()
        val expected = mutableListOf<AlertEvent>(); val tickets = mutableListOf<AlertTapTicket>()
        repeat(14) { n ->
            val unknown = n == 3 || n == 8
            val e = if (unknown) sourceEvent() else AlertEvent.SettingTriggered(if (n < 2) 77 else n)
            val extras = S6TypedTapExtras(mapOf("type" to if (unknown) "source_rate_alert" else "rate_alert", "setting_id" to (if (n < 2) 77 else n).toString()))
            val result = if (n % 2 == 0) AlertTapEntry.intakeNew(extras, false, p.intake()) else AlertTapEntry.intakeWarm(extras, p.intake())
            val t = p.need("tap-$n", (result as? AlertTapCaptureResult.CAPTURED)?.ticket, "each cold/warm ingress captures one ticket")
            tickets += t; if (!unknown) expected += e
            p.owned(t, "tap-$n")
            if (n % 2 == 0) AlertTapEntry.forwardRoot(AlertEvent.PendingTap(t), { p.bus.forwardPendingTap(it) }) { }
            runCurrent()
        }
        p.eq("capacity", "all14 distinct taps survive a buffer10 and repeated id", 14, tickets.toSet().size)
        p.eq("capacity", "all payloads held before ready", 14, p.inventory().size)
        p.eq("FULL", "actual bus is FULL after12 known taps exceed buffer10", false, p.bus.emit(sourceEvent(999)))
        ready.close(); blocked.cancel(); gate.complete(Unit); runCurrent()
        p.eq("detach", "cancelling the full collector loses no owner record", 14, p.inventory().size)
        p.collect(); runCurrent()
        p.eq("FIFO", "known FIFO and duplicate setting intent preserved across UNKNOWN gaps", expected, p.delivered)
        p.eq("UNKNOWN", "two isolated UNKNOWN remain", 2, p.inventory().size)
    }

    @Test fun p14_enabledWithoutPermitNeverUsesLegacyBypass() = s63cTest("P14", "OFF", "ON-p0", "ON-grant", "no-context") { p, col ->
        val before = p.g.snapshot
        val snap = if (col == "ON-p0" || col == "no-context") before.copy(krxPermitSeq = 0) else before
        val context = p.context(snap).let { if (col == "no-context") it.copy(identity = null, userAccessEpoch = null, krxCapabilityEpoch = null, scope = null) else it }
        val bus = AlertEventBus(p.owner, col != "OFF", { true }, { snap }, { context.identity })
        p.eq(col, "enabled getter uses mode independently of permits", col != "OFF", bus.pendingTapEnabled)
        if (col == "OFF") {
            p.eq(col, "legacy no subscriber emit true", true, bus.emit(AlertEvent.RefreshNeeded))
            p.eq(col, "OFF creates no owner intent", 0, p.inventory().size)
        } else {
            val result = bus.capturePendingTap(sourceEvent(), source(), context)
            val t = p.need(col, (result as? AlertTapCaptureResult.CAPTURED)?.ticket, "ON installs custody before first permit")
            p.owned(t, col)
            if (col != "ON-grant") {
                p.eq(col, "original missing use never consumes", AlertTapReceipt.REJECTED, p.owner.consume(t, snap, context.identity) { p.delivered += it; AlertTapReceipt.HANDLED })
                p.eq(col, "later grant cannot attach new original", AlertTapReceipt.REJECTED, p.consume(t))
                p.yes(col, "context UNKNOWN honestly counted when attribution missing", col != "no-context" || p.report().unknownContext > 0)
            }
            val non = p.capture(sourceEvent(), source("upbit"), context.copy(scope = null), column = "$col-nonKRX")
            if (context.identity != null) p.eq(col, "nonKRX needs no premium use", AlertTapReceipt.HANDLED, p.consume(non))
        }
    }

    @Test fun p15_unknownReasonsAndTrayBoundaryStayExplicit() = s63cTest("P15", "missing", "empty", "unregistered", "malformed", "conflict", "context", "tray") { p, col ->
        p.eq(col, "untapped tray has no owner ticket", 0, p.inventory().size)
        if (col == "tray") {
            p.g.session.capEnd(); p.g.session.capReopen()
            val fresh = p.capture(); p.owned(fresh, col)
            p.eq(col, "first ingress after grant is fresh, not already-pending K1", AlertTapReceipt.HANDLED, p.consume(fresh))
        } else {
            val m = when (col) {
                "empty" -> source().copy(source = "")
                "unregistered" -> source("new-exchange")
                "malformed" -> source().copy(wrongTypeKeys = setOf("source"), source = null)
                "conflict" -> source().copy(typeConflict = true)
                "context" -> source()
                else -> AlertTapMetadata()
            }
            val ctx = p.context().let { if (col == "context") it.copy(identity = null, scope = null, userAccessEpoch = null, krxCapabilityEpoch = null) else it }
            val t = p.capture(AlertEvent.FamilyRefreshNeeded(AlertFamily.SOURCE), m, ctx, column = col)
            p.owned(t, col); val r = p.report()
            p.eq(col, "incomplete DoD cannot be hidden by memory success", false, r.memoryComplete)
            p.yes(col, "kind-specific UNKNOWN count", if (col == "context") r.unknownContext > 0 else r.unknownClassification > 0)
            val expected = when (col) { "missing" -> AlertTapUnknownReason.MISSING; "empty" -> AlertTapUnknownReason.EMPTY; "unregistered" -> AlertTapUnknownReason.UNREGISTERED; "malformed" -> AlertTapUnknownReason.MALFORMED; "conflict" -> AlertTapUnknownReason.CONFLICT; else -> AlertTapUnknownReason.CONTEXT_UNKNOWN }
            p.eq(col, "reason exact", expected, p.owned(t).reason)
            val compMetadata = if (col == "context") comparison() else AlertTapMetadata()
            val comp = p.capture(AlertEvent.FamilyRefreshNeeded(AlertFamily.COMPARISON), compMetadata, ctx, column = "$col-COMPARISON")
            p.owned(comp, "$col-COMPARISON")
            p.eq(col, "idless COMPARISON retains uncertainty", false, p.report().memoryComplete)
            p.identity(null); p.report()
            p.yes(col, "USER removal is explicit, not fabricated classification success", t !in p.inventory() || ctx.identity == null)
        }
    }
}
