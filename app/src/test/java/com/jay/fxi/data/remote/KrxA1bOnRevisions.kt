package com.jay.fxi.data.remote

import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.KrxContestPhase
import com.jay.fxi.data.entitlements.KrxSendMarker
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.rejectKrx
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.KRX
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.USD
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*

/** The seven explicitly amended ON methods delegate here; their OFF factories and all other methods stay byte-identical. */
internal object KrxA1bOnRevisions {
    fun rest09() {
        a1bTest("ON-05-9/blocked-then-p2") { f -> with(f) {
            r.approve(); val answer = r.awaiting(); r.start(); r.open()
            r.assertWindow(KrxContestPhase.AWAITING)
            r.ack(); r.advance(2_100)
            assertEquals(c("AWAITING logical issue and HTTP are zero"), 0, krx.size)
            assertEquals(c("unissued blocked request hands no marker"), 0, r.issuer.reserved.size)
            answer.complete(EntitlementsOutcome.StableActive(true)); r.drain()
            val call = next(0)
            assertEquals(c("new permitted issue reads p2"), 2L, call.read.krxPermitSeq)
            val gate = CompletableDeferred<EntitlementsOutcome>(); r.h.source.gates += gate
            reply(call, KrxA1bFixture.unsupported)
            assertEquals(c("new issue forwards its own p2 marker"), KrxSendMarker(call.read.facts.recordFence?.krxCapabilityEpoch, 2), r.issuer.reserved.last().marker)
        } }
        queuedOld("ON-05-9/queued-old-marker", false)
    }
    fun rest12() {
        a1bTest("ON-05-12/cancel-body") { f -> with(f) {
            live(); val call = issue(); assertTrue("premise: ${r.row} first exchange already happened", call.sends.size == 1)
            h1Prime()
            assertTrue(c("real withdrawal cancels the old body wait"), call.cancelled)
            reply(call, KrxA1bFixture.unsupported)
            assertEquals(c("cancelled old completion creates no marker handover"), 0, r.issuer.reserved.size)
            val current = next(1)
            assertEquals(c("new logical issue reads p2"), 2L, current.read.krxPermitSeq)
            assertFalse(c("old body produced no protected data"), has(1555.0))
        } }
        queuedOld("ON-05-12/queued-old-marker", false)
    }
    fun rest13() {
        a1bTest("ON-05-13/cancel-before-token") { f -> with(f) {
            holdTokens = true; live(); val call = issue()
            assertTrue("premise: ${r.row} no exchange has happened", call.sends.isEmpty())
            loss("false")
            assertTrue(c("C1 epoch withdrawal really cancels token wait"), call.cancelled)
            call.token.complete(Unit); reply(call, KrxA1bFixture.unsupported)
            assertEquals(c("retired token never exchanges"), 0, call.sends.size)
            assertEquals(c("cancelled job fabricates no old-marker report"), 0, r.issuer.reserved.size)
        } }
        queuedOld("ON-05-13/queued-old-epoch", true)
    }
    fun rest15() = a1bVariants("ON-05-15", "401-withdrawn", "401-admitted") { f, label -> with(f) {
        holdRefresh = true; live(); val call = issue()
        reply(call, KrxA1bFixture.Reply(401, """{"error":"invalid_token"}"""))
        assertEquals("premise: ${r.row} real auth provider waits on forced refresh", KrxA1bFixture.Stage.REFRESH, call.stage)
        if (label == "401-withdrawn") {
            loss("false"); assertTrue(c("withdrawal cancels the actual replay waiter"), call.cancelled)
            call.refresh.complete(Unit); reply(call, KrxA1bFixture.unsupported)
            assertEquals(c("withdrawn 401 replay performs no second HTTP"), 1, call.sends.size)
            assertEquals(c("cancelled replay reports no marker"), 0, r.issuer.reserved.size)
            holdTokens = false; holdRefresh = false; r.falseThenTrue()
            val current = next(1)
            assertTrue(c("new logical issue uses a newer p"), current.read.krxPermitSeq > call.read.krxPermitSeq)
        } else {
            call.refresh.complete(Unit); r.drain()
            assertEquals("premise: ${r.row} admitted replay makes exactly two HTTP sends", 2, call.sends.size)
            r.h.source.gates += CompletableDeferred<EntitlementsOutcome>()
            reply(call, KrxA1bFixture.unsupported)
            assertEquals(c("one logical issue carries one original marker across 401"),
                KrxSendMarker(call.read.facts.recordFence?.krxCapabilityEpoch, call.read.krxPermitSeq), r.issuer.reserved.single().marker)
        }
    } }
    /** boundary-input: null record epoch; no KRX use can be issued even though the GENERAL use is valid. */
    fun rest19() = a1bTest("ON-05-19/boundary-input:null-epoch") { f -> with(f) {
        r.approve(); r.start()
        r.snapshotSupply = { r.snapshot.copy(facts = r.snapshot.facts.copy(recordFence = r.snapshot.facts.recordFence?.copy(krxCapabilityEpoch = null))) }
        val fence = checkNotNull(r.issuer.topicGrantResult().fence)
        assertNotNull("premise: ON-05-19 GENERAL lifetime still exists", r.uses.acquire(fence))
        assertNull("premise: ON-05-19 KRX use cannot exist without epoch", r.uses.acquireKrx(fence))
        r.open(); r.ack(); r.advance(2_100)
        assertEquals(c("ON null-epoch issue and HTTP are zero"), 0, krx.size)
        assertEquals(c("no unissued marker is handed over"), 0, r.issuer.reserved.size)
        r.assertGeneral()
    } }
    fun rest23() = a1bTest("ON-05-23/HOLD-general-next") { f -> with(f) {
        r.approve(); r.hold(); r.start(); r.open(); r.ack(); r.advance(2_100)
        r.assertWindow(KrxContestPhase.HOLD)
        assertEquals(c("HOLD has zero KRX logical issues/HTTP"), 0, krx.size)
        r.request(KRX); assertEquals(c("explicit blocked issue remains zero"), 0, krx.size)
        val usd = calls.single { it.topic == USD }; reply(usd, KrxA1bFixture.Reply(404, """{"error":"topics_disabled"}"""))
        val before = calls.count { it.topic == USD }; r.request(USD)
        r.until(c("next GENERAL request still issues")) { calls.count { it.topic == USD } == before + 1 }
        assertEquals(c("GENERAL issue does not revive owed KRX"), 0, krx.size)
        assertEquals(c("no blocked marker handover"), 0, r.issuer.reserved.size); r.assertWindow(KrxContestPhase.HOLD)
    } }
    fun ws07() = a1bVariants("ON-05-7", "HOLD", "AWAITING-WS", "AWAITING-REST") { f, state -> with(f) {
        live(ack = state != "AWAITING-WS"); val call = issue()
        var answer: CompletableDeferred<EntitlementsOutcome>? = null
        if (state == "HOLD") r.hold() else {
            answer = CompletableDeferred<EntitlementsOutcome>().also { r.h.source.gates += it }
            if (state == "AWAITING-WS") r.ack(reject = true) else reply(call, KrxA1bFixture.unsupported)
            r.assertWindow(KrxContestPhase.AWAITING)
        }
        val usd = calls.single { it.topic == USD }
        val token = r.snapshot.facts.token; val epoch = r.snapshot.facts.recordFence?.userAccessEpoch
        if (state != "AWAITING-REST") assertTrue(c("old KRX in-flight job is actually cancelled"), call.cancelled)
        assertFalse(c("GENERAL in-flight job remains alive"), usd.cancelled)
        assertTrue(c("GENERAL use stays admitted"), usd.admitted())
        r.reconnect(); val first = r.sends.last().request
        assertFalse(c("hidden reconnect FIRST omits KRX"), KRX in first.topics)
        assertTrue(c("GENERAL reconnect FIRST remains populated"), USD in first.topics)
        r.ack(first); reply(usd, KrxA1bFixture.Reply(200, TopicSessionKrxHarness.usdFrame(1337.0)))
        assertTrue(c("GENERAL REST still applies under its original use"), has(1337.0)); r.assertGeneral()
        assertEquals(c("capability-only hold keeps token"), token, r.snapshot.facts.token)
        assertEquals(c("capability-only hold keeps U"), epoch, r.snapshot.facts.recordFence?.userAccessEpoch)
        val before = r.krxSends.size
        if (state == "HOLD") {
            val until = checkNotNull(r.pac.krxContestView?.state?.holdUntilMillis); r.advance(until - r.clock.nowMillis())
        } else { answer!!.complete(EntitlementsOutcome.StableActive(true)); r.drain() }
        r.until(c("OPEN restores exactly one current-p physical subscribe")) { r.krxSends.size == before + 1 }
        r.ack(); r.revise(); r.drain()
        assertEquals(c("OPEN revision adds no duplicate"), before + 1, r.krxSends.size)
    } }
    private suspend fun KrxA1bFixture.h1Prime() {
        val epoch = r.snapshot.facts.recordFence?.krxCapabilityEpoch
        // Grant issuance itself reads identity. Capture its real result before injecting the refusal's read failure.
        val grant = r.issuer.topicGrantResult().fence?.grant
        assertNotNull("premise: ${r.row} standing grant exists before H1prime identity fault", grant)
        r.h.source.identityNulls = 1
        r.h.rejectKrx(requireNotNull(grant) { "premise: ${r.row} standing grant before H1prime" }); r.drain()
        assertEquals("premise: ${r.row} refusal consumed the intended identity fault", 0, r.h.source.identityNulls)
        assertEquals("premise: ${r.row} H1prime AWAITING", KrxContestPhase.AWAITING, r.pac.krxContestView?.state?.phase)
        r.until("premise: ${r.row} existing PAC opens p2", 6_000) { r.snapshot.krxPermitSeq == 2L && r.snapshot.facts.capabilityAllowed }
        assertEquals("premise: ${r.row} H1prime keeps the same epoch", epoch, r.snapshot.facts.recordFence?.krxCapabilityEpoch)
    }
    private fun queuedOld(row: String, epochLoss: Boolean) = a1bTest(row) { f -> with(f) {
        live(); val call = issue(); val gate = InputGate("BootstrapAnswered")
        try {
            reply(call, KrxA1bFixture.unsupported); assertTrue(c("real control answer produced before withdrawal"), gate.count > 0)
            if (epochLoss) loss("false") else h1Prime()
            val before = r.h.source.calls.size
            gate.release()
            assertEquals(c("already queued control preserves the original issue marker"),
                KrxSendMarker(call.read.facts.recordFence?.krxCapabilityEpoch, call.read.krxPermitSeq), r.issuer.reserved.single().marker)
            assertEquals(c("queued old p/epoch cannot cause another FE"), before, r.h.source.calls.size)
            assertFalse(c("queued control never applies protected data"), has(1555.0))
        } finally { gate.close() }
    } }
}
