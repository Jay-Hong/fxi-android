package com.jay.fxi.data.remote

import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.KRX
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.TETHER
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.USD
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * α-0 r2 §5.4. Each side has its own scheduler and real OFF PAC; the HEAD implementation is an immutable executable fixture,
 * copied from 8501b6d (same production bytes as f79d334), never a trace captured from this candidate. O1 GOLDEN stays separate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TopicSessionKrxOffTraceTest {
    private fun compare(row: String, scenario: suspend (KrxSessionIntegrationRig) -> Unit) {
        fun capture(head: Boolean): List<String> {
            var trace = emptyList<String>()
            runTest {
                val r = KrxSessionIntegrationRig(this, row, on = false, frozenHead = head)
                try {
                    scenario(r)
                    if (!head) {
                        assertTrue("premise: $row actual RunningCommand boundary was installed", r.commands.isNotEmpty())
                        assertEquals("contract($row): OFF prepare/beforeEncode/sent/discard = 0", emptyList<KrxSessionIntegrationRig.BoundaryEvent>(), r.commands.flatMap { it.events })
                        assertTrue("contract($row): OFF invokes no retirement sink", r.retirements.requests.isEmpty())
                        val dispatches = r.commands.flatMap { it.dispatches }
                        assertTrue("premise: $row actual dispatch results recorded", dispatches.isNotEmpty())
                        assertTrue("contract($row): every OFF dispatch is Legacy", dispatches.all { it.second is KrxAttemptDispatch.Legacy })
                        for (send in r.sends) {
                            assertTrue("contract($row): physical send has corresponding Legacy result", dispatches.any {
                                it.first.requestId == send.request.requestId && (it.second as? KrxAttemptDispatch.Legacy)?.sent == send.accepted
                            })
                        }
                    }
                    trace = r.trace()
                } finally { r.close() }
            }
            return trace
        }
        val expected = capture(true)
        val actual = capture(false)
        assertTrue("premise: $row baseline trace includes wire and store observations", expected.size >= 8)
        assertEquals("contract($row): OFF trace equals executable frozen HEAD", expected, actual)
    }
    private suspend fun live(r: KrxSessionIntegrationRig, ack: Boolean = true) {
        r.approve(); r.start(); r.open(); r.first()
        if (ack) r.ack()
        assertEquals("premise: ${r.row} real OFF PAC p=0", 0L, r.snapshot.krxPermitSeq)
    }

    @Test fun o2_sessionTrace_matchesHead() = compare("O2-01") { r ->
        live(r); r.generalTick(1411.0); r.krxTick(1511.0)
        r.wire.deliver(TopicSessionKrxHarness.usdFrame(1311.0)); r.drain()
        r.revise(); r.drain(); r.reconnect(); r.ack()
        // Equal merge timestamps deliberately keep the first quote. Reconnect's positive uses a genuinely newer quote.
        r.wire.deliver(TopicSessionKrxHarness.tetherFrame(1412.0, "2026-08-31T10:20:01+09:00")); r.drain()
        assertTrue("premise: O2-01 HEAD GENERAL adoption", 1412.0 in r.rates.quotes.values.map { it.rate })
    }

    @Test fun o2_bootstrapTrace_matchesHead() = compare("O2-02") { r ->
        r.httpFloor = r.clock.nowMillis() + 3_600_600
        live(r)
        assertTrue("premise: O2-02 floor still ahead", r.clock.nowMillis() < r.httpFloor)
        assertEquals("contract(O2-02): floor has not issued", 0, r.calls.size)
        r.advance(r.httpFloor - r.clock.nowMillis())
        r.advance(2_100)
        assertEquals("premise: O2-02 all HEAD desired calls issued", 6, r.calls.size)
        for (call in r.calls.toList()) r.complete(call, TopicSnapshotOutcome.Unreachable(java.io.IOException("a1a injected unreachable")))
        val calls = r.calls.size
        r.advance(2_000)
        assertTrue("premise: O2-02 GENERAL session still usable", r.snapshot.facts.userAllowed)
        assertEquals("contract(O2-02): HEAD does not retry failed KRX or GENERAL", calls, r.calls.size)
    }

    @Test fun o2_f2AndD14Trace_matchesHead() = compare("O2-03") { r ->
        live(r)
        val before = r.store.snapshot.stateFor(TETHER)
        val display = r.display
        r.krxTick(1522.0)
        assertEquals("contract(O2-03): OFF KRX leaves tether receipt unchanged", before, r.store.snapshot.stateFor(TETHER))
        assertEquals("contract(O2-03): OFF KRX leaves F2 display unchanged", display, r.display)
        assertFalse("contract(O2-03): OFF KRX not adopted", 1522.0 in r.rates.quotes.values.map { it.rate })
        r.generalTick(); r.advance(30_001)
    }

    @Test fun o2_authRecoveryTrace_matchesHead() = compare("O2-04") { r ->
        live(r, ack = false)
        r.wire.deliver("""{"type":"subscription_error","request_id":"${r.first().requestId}","error":"invalid_token"}""")
        r.drain(); r.recover()
        r.until("premise: O2-04 recovery reopens HEAD command") { r.requests.size == 2 }
        r.ack(); r.generalTick()
    }

    @Test fun o2_mixedFrameTrace_matchesHead() = compare("O2-05") { r ->
        live(r, ack = false)
        assertTrue("premise: O2-05 HEAD mixed test desired carries KRX", KRX in r.first().topics && USD in r.first().topics)
        r.ack(); r.generalTick(); r.krxTick(1533.0)
        r.wire.deliver(TopicSessionKrxHarness.usdFrame(1333.0)); r.drain()
        assertTrue("premise: O2-05 HEAD GENERAL ACK scope confirmed", r.store.snapshot.stateFor(USD).confirmed)
    }

    @Test fun o2_failedSendTrace_matchesHead() = compare("O2-06") { r ->
        r.failNextWire = 1
        live(r, ack = false)
        assertTrue("premise: O2-06 failed send reached socket", r.sends.any { !it.accepted })
        r.until("premise: O2-06 HEAD retries a failed send", 6_000) { r.sends.any { it.accepted } }
        r.ack(reject = true)
        assertEquals("contract(O2-06): marker remains non-null with epoch and zero permit", 0L, r.issuer.reserved.last().marker?.permitSeq)
    }

    @Test fun o2_typedBoundaryIsUnusedOff() = compare("O2-07") { r ->
        live(r, ack = false); r.ack(seconds = 181)
        r.until("premise: O2-07 actual HEAD renewal sends", 1_100) { r.sends.size == 2 }
        r.ack(); r.rejectForManual(); r.manual(); r.ack(); r.generalTick()
        r.request(KRX); r.advance(1_100)
        KrxSessionSourceContract.assertClosed()
        r.reconnect(); r.ack()
        assertTrue("premise: O2-07 legacy KRX send reader is exercised", r.reads.any { it.first == "payload" })
        assertEquals("contract(O2-07): no metadata comparison at p=0", 0, r.reads.count { it.first == "revision" })
        if (!r.frozenHead) assertEquals("contract(O2-07): no OFF permit consumption", 0L, r.consumed)
        val fence = checkNotNull(r.issuer.topicGrantResult().fence)
        val lifetime = checkNotNull(r.uses.acquire(fence))
        val read = r.uses.admitsWithKrx(lifetime)
        assertEquals("contract(O2-07): OFF read has no session stamp", null, read.session)
        assertEquals("contract(O2-07): OFF no KRX use issued", null, read.krxUse)
    }

    @Test fun o2_metadataDoesNotChangeOffRevisions() = compare("O2-08") { r ->
        live(r)
        val revision = r.snapshot.revision
        val deliveries = r.deliveries.toList()
        repeat(3) { r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS); r.revise(); r.drain() }
        assertEquals("contract(O2-08): identical OFF recomputations add no publication", revision, r.snapshot.revision)
        assertEquals("contract(O2-08): same grant deliveries", deliveries, r.deliveries)
        assertEquals("contract(O2-08): OFF session metadata reads zero", 0, r.reads.count { it.first == "revision" })
    }
    /** α-3 additive row; original locked r2 methods are unchanged. */
    @Test fun a3_w15_offRevision_addsNoPayloadOrMetadata() = KrxA3Rows.w15_offRevision_addsNoPayloadOrMetadata()

}
