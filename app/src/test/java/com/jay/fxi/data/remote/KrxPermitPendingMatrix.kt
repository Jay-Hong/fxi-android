package com.jay.fxi.data.remote

import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.IdentityStep
import com.jay.fxi.data.entitlements.KrxContestPhase
import com.jay.fxi.data.entitlements.KrxHarnessDefaults
import com.jay.fxi.data.entitlements.KrxSendMarker
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.closeApprovalOnTheSameKey
import com.jay.fxi.data.entitlements.issueGrant
import com.jay.fxi.data.entitlements.readPrivate
import com.jay.fxi.data.entitlements.recheckDemandSnapshot
import com.jay.fxi.data.entitlements.recheckSchedule
import com.jay.fxi.data.entitlements.rejectKrx
import com.jay.fxi.data.entitlements.reopenApproval
import com.jay.fxi.data.entitlements.TopicRejectionReservation
import com.jay.fxi.domain.model.TopicRejectionReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** Exact original PAC matrices run inside a session whose first KRX physical subscribe still awaits its ACK. */
@OptIn(ExperimentalCoroutinesApi::class)
internal object KrxPermitPendingMatrix {
    val labels: List<String> = buildList {
        add("N1/open-next-old-marker"); add("N1b/h2-t2-old-marker"); add("N2/duplicate-during-HOLD")
        add("N3/ineligible-triplet")
        add("N4/stable-true"); add("N4/pending-true")
        add("N5/process"); add("N5/abandon")
        for (first in listOf("live", "store")) for (n in listOf(1, 3))
            for (at in listOf("before", "during", "after")) for (pre in listOf(false, true))
                add("R06/$first/n$n/$at/pre$pre")
        for (n in listOf(1, 3)) for (at in listOf("before", "during", "after"))
            add("RO2/approval-closed/n$n/$at")
    }
    private fun c(r: KrxSessionIntegrationRig, text: String) = "contract(W08b/${r.row.substringAfter("W08b/")}): $text"
    private suspend fun full(r: KrxSessionIntegrationRig): List<Any?> {
        val schedule = r.h.recheckSchedule()
        return listOf(r.pac.krxContestView, r.h.recheckDemandSnapshot()?.toString(),
            schedule.readPrivate("consecutiveAttempts"), schedule.readPrivate("earliestAllowedAtMillis"), schedule.readPrivate("revision"),
            r.pac.heldLossCandidateCount(), r.h.source.calls.size, r.h.store.record.krxCapabilityEpoch)
    }
    private fun marker(r: KrxSessionIntegrationRig, p: Long = r.snapshot.krxPermitSeq) = KrxSendMarker(r.h.store.record.krxCapabilityEpoch, p)
    private fun reserve(r: KrxSessionIntegrationRig, m: KrxSendMarker) =
        r.pac.reserveRejection(checkNotNull(r.snapshot.facts.token), KrxHarnessDefaults.KRX_REFUSAL, m)
    private suspend fun waitForFe(r: KrxSessionIntegrationRig, count: Int) {
        r.until("premise: ${r.row} existing FE starts", 60_000) { r.h.source.calls.size == count + 1 }
        assertTrue("premise: ${r.row} FE body still held", r.pac.recheckDiagnostics().inFlightOrders.isNotEmpty())
    }
    suspend fun exercise(r: KrxSessionIntegrationRig, label: String) {
        r.approve()
        if (label.endsWith("pretrue") || label.startsWith("N2/")) { r.h.rejectKrx(r.h.issueGrant()); r.drain(); r.advance(100) }
        r.httpFloor = r.clock.nowMillis() + 120_000 // isolates WS consumption from G1(i) REST issue consumption
        r.start(); r.open()
        val first = r.first(); val oldWire = r.wire
        val p = r.snapshot.krxPermitSeq
        assertEquals(c(r, "real successful FIRST consumes p before pending matrix"), p, r.consumed)
        val firstSends = r.krxSends.size
        val gate = CompletableDeferred<EntitlementsOutcome>()
        var oldAcked = false
        val pending = mutableListOf<TopicRejectionReservation>()
        val kind = label.substringBefore('/')
        if (kind == "N3" || kind == "N1b") {
            val epoch = r.h.store.record.krxCapabilityEpoch
            r.h.source.next = { EntitlementsOutcome.Pending(false, 0) }
            r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
            r.h.source.gates += gate
            val calls = r.h.source.calls.size
            waitForFe(r, calls)
            val q = r.pac.recheckDiagnostics().inFlightOrders.single()
            if (kind != "N1b") {
                pending += reserve(r, KrxSendMarker(epoch, p))
                pending += reserve(r, marker(r, p - 1))
                pending += r.pac.reserveRejection(r.h.issueGrant(), listOf(TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED, TopicRejectionReason.PREMIUM_REQUIRED), marker(r))
            }
            if (kind == "N3") assertTrue("premise: ${r.row} exactly three ineligible reservations reported after q", pending.size == 3 && pending.all { it.order > q })
            gate.complete(EntitlementsOutcome.StableActive(true)); r.drain()
            if (kind == "N1b") pending += reserve(r, marker(r, p))
            assertNull(c(r, "ineligible triplet cannot leave withheld"), r.pac.krxContestView?.withheld)
            // These three stay pending through ACK and are then abandoned, exactly preserving the no-blocker input.
        } else if (kind == "N4") {
            r.h.source.next = { EntitlementsOutcome.StableActive(false) }; r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
            assertEquals("premise: ${r.row} C1 entered NONE", KrxContestPhase.NONE, r.pac.krxContestView?.state?.phase)
            r.h.source.gates += gate
            val answer = if (label.endsWith("pending-true")) EntitlementsOutcome.Pending(true, 0) else EntitlementsOutcome.StableActive(true)
            r.h.source.next = { answer }
            val caller = r.h.parts.scope.launch(start = CoroutineStart.UNDISPATCHED) { r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
            val q = r.pac.recheckDiagnostics().inFlightOrders.single()
            pending += reserve(r, marker(r))
            assertTrue("premise: ${r.row} eligible reservation is later than q", pending.single().order > q)
            gate.complete(answer)
            caller.join(); r.drain()
            assertNull(c(r, "NONE ignores pending eligible blocker"), r.pac.krxContestView?.withheld)
        } else {
            val n = if (kind in setOf("N1", "N2", "N5")) 1 else label.substringAfter("/n").substringBefore('/').toInt()
            val at = if (kind in setOf("N1", "N2", "N5")) "pending" else label.split('/').first { it in setOf("before", "during", "after") }
            val r1 = reserve(r, marker(r))
            val dups = if (kind in setOf("N1", "N2")) emptyList() else List(n) { reserve(r, marker(r)) }
            var approval: IdentityStep.AwaitPersistence? = null
            when {
                kind == "RO2" -> approval = r.h.closeApprovalOnTheSameKey()
                kind in setOf("N1", "N2", "N5") || label.contains("/live/") -> r.h.source.identityNulls = 1
                else -> r.h.store.loadFailures = 1
            }
            r.pac.onTopicRejected(r1)
            assertEquals("premise: ${r.row} first handover is AWAITING", KrxContestPhase.AWAITING, r.pac.krxContestView?.state?.phase)
            assertEquals(c(r, "first handover records p once"), p, r.pac.krxContestView?.rejectedPermit)
            val fetches = r.h.source.calls.size
            r.h.source.gates += gate
            suspend fun processDups() {
                for ((index, dup) in dups.withIndex()) {
                    if (kind == "R06" && index % 3 == 1) r.h.source.identityNulls = 1
                    if (kind == "R06" && index % 3 == 2) r.h.store.loadFailures = 1
                    val before = full(r)
                    r.pac.onTopicRejected(dup)
                    assertEquals(c(r, "duplicate $index changes no view/RS/demand/fetch/candidate"), before, full(r))
                    if (kind == "R06" && index % 3 == 1) {
                        assertEquals(c(r, "duplicate does not consume live fault"), 1, r.h.source.identityNulls); r.h.source.identityNulls = 0
                    }
                    if (kind == "R06" && index % 3 == 2) {
                        assertEquals(c(r, "duplicate does not read store"), 1, r.h.store.loadFailures); r.h.store.loadFailures = 0
                    }
                    assertTrue(c(r, "candidate bound remains <=1"), r.pac.heldLossCandidateCount() <= 1)
                }
            }
            if (at == "before") processDups()
            if (approval != null) r.h.reopenApproval(approval)
            waitForFe(r, fetches)
            if (at == "during") processDups()
            if (kind == "N5") {
                // N5's duplicate is reported after q, not just held since the first rejection.
                dups.forEach(r.pac::abandonRejection)
                pending += reserve(r, marker(r, p))
                val q = r.pac.recheckDiagnostics().inFlightOrders.single()
                assertTrue("premise: ${r.row} duplicate reported after in-flight q", pending.single().order > q)
            }
            gate.complete(EntitlementsOutcome.StableActive(true)); r.drain()
            if (kind == "N1") pending += reserve(r, marker(r, p))
            if (label.endsWith("pretrue") || kind == "N2") {
                assertEquals("premise: ${r.row} second contradiction HOLD", KrxContestPhase.HOLD, r.pac.krxContestView?.state?.phase)
                if (at == "after") processDups()
                if (kind == "N2") {
                    val beforeAck = r.h.source.calls.size
                    r.assertWindow(KrxContestPhase.HOLD)
                    oldWire.deliver(r.ackText(first, reject = true)); r.drain(); oldAcked = true
                    assertEquals(c(r, "same-p duplicate ACK during HOLD adds no FE"), beforeAck, r.h.source.calls.size)
                    assertEquals(c(r, "HOLD ACK does not resubscribe"), firstSends, r.krxSends.size)
                }
                val until = checkNotNull(r.pac.krxContestView?.state?.holdUntilMillis)
                r.advance(until - r.clock.nowMillis() - 1)
                r.assertWindow(KrxContestPhase.HOLD)
                assertEquals(c(r, "no extra FE before HOLD expiry"), fetches + 1, r.h.source.calls.size)
                r.advance(1)
            } else if (at == "after") processDups()
            assertEquals(c(r, "one FE for the whole duplicate matrix"), fetches + 1, r.h.source.calls.size)
        }
        assertEquals(c(r, "new permit issued exactly once"), p + 1, r.snapshot.krxPermitSeq)
        if (!oldAcked) assertEquals(c(r, "same connection has <=1 KRX pending before ACK"), firstSends, r.krxSends.size)
        assertTrue("premise: ${r.row} old ACK still before timeout", r.clock.nowMillis() - r.sends.first().at < 20_000)
        val fetches = r.h.source.calls.size
        if (!oldAcked) { oldWire.deliver(r.ackText(first, reject = true)); r.drain() }
        if (kind == "RO2") {
            assertTrue("premise: ${r.row} existing USER approval hold ended original use", oldWire.cancelled)
            r.until("premise: ${r.row} existing GENERAL resume reconnects", 2_600) { r.wires.size >= 2 }
            r.open()
        }
        r.until(c(r, "ACK/termination/reconnect wakes one current-p send"), 1_000) { r.krxSends.size == firstSends + 1 }
        assertEquals(c(r, "old rejection adds no FE"), fetches, r.h.source.calls.size)
        assertEquals(c(r, "current p consumed by real send"), p + 1, r.consumed)
        if (kind != "N3") for (reservation in pending) {
            val before = full(r)
            if (label.endsWith("abandon")) r.pac.abandonRejection(reservation) else r.pac.onTopicRejected(reservation)
            assertEquals(c(r, "ending earlier-permit pending reservation changes nothing"), before, full(r))
        } else pending.forEach(r.pac::abandonRejection)
        r.drain()
        assertEquals(c(r, "all pending endings preserve one follow-up"), firstSends + 1, r.krxSends.size)
    }
}
