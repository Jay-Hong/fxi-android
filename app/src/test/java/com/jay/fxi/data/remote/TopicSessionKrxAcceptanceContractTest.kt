package com.jay.fxi.data.remote

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.AccessEpochTransitions
import com.jay.fxi.data.entitlements.EpochIdGenerator
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.applied
import com.jay.fxi.data.entitlements.issueGrant
import com.jay.fxi.data.entitlements.rejectKrx
import com.jay.fxi.data.local.TopicLastKnownClock
import com.jay.fxi.data.local.TopicLastKnownOwner
import com.jay.fxi.data.local.TopicLastKnownStore
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.KRX
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.TETHER
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.USD
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.TopicQuoteKey
import com.jay.fxi.ui.rates.KrxRowGate
import com.jay.fxi.ui.rates.premiumDisplayFor
import com.jay.fxi.ui.rates.displayFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** α-0 r2 §5.3. ACK means completed CMD promotion, not merely server/wire send order.
 * A frame queued before promotion is dropped without replay; G1(ii) REST supplies the same data.
 * A same-grant consumed reconnect FIRST can wait for another tick; no buffer or expiry is added.
 */
class TopicSessionKrxAcceptanceContractTest {
    private fun test(row: String, body: suspend KrxA1bFixture.() -> Unit) = a1bTest(row) { f -> f.body() }
    private fun variants(row: String, vararg labels: String, body: suspend KrxA1bFixture.(String) -> Unit) =
        a1bVariants(row, *labels) { f, label -> f.body(label) }

    @Test fun k01_wsOldEpochRejected_newCaptureAccepted() = test("K01") { oldWs(false) }
    /** boundary-input: 실 PAC 도달 미확인. Same-p/same-epoch capInv round trip is a C-1 consumer contract. */
    @Test fun k01_wsSameEpochHoldRejectsOldCapInv() = test("K01-HOLD/boundary-input:same-p-capInv") { oldWs(true) }
    @Test fun k02_restOldEpochRejected_newIssueAccepted() = test("K02") { oldRest(false) }
    /** boundary-input: 실 PAC 도달 미확인. Does not claim that PAC emits a same-p hold/release in this harness. */
    @Test fun k02_restSameEpochHoldRejectsOldCapInv() = test("K02-HOLD/boundary-input:same-p-capInv") { oldRest(true) }

    @Test fun k03_capabilityLoss_preservesGeneralInSameSession() = test("K03") {
        live(); val call = issue(); val wire = r.wire
        val fence = checkNotNull(r.issuer.topicGrantResult().fence)
        val general = checkNotNull(r.uses.acquire(fence))
        val usd = calls.single { it.topic == USD }
        val leases = generalLeases()
        loss("false")
        assertTrue(c("GENERAL use survives capability loss"), r.uses.admits(general))
        assertEquals(c("GENERAL token and U survive"), fence, r.issuer.topicGrantResult().fence)
        assertSame(c("same socket survives"), wire, r.wire)
        assertFalse(c("capability loss never closes GENERAL socket"), wire.cancelled)
        assertEquals(c("GENERAL registry survives"), leases, generalLeases())
        assertTrue(c("KRX body really cancelled"), call.cancelled)
        assertFalse(c("GENERAL body remains in flight"), usd.cancelled)
        reply(usd, KrxA1bFixture.Reply(200, TopicSessionKrxHarness.usdFrame(1337.0)))
        assertTrue(c("GENERAL REST still applies"), has(1337.0))
        r.assertGeneral(); r.krxTick(1777.0)
        assertTrue("premise: K03 capability remains hidden for negative", !r.snapshot.facts.capabilityAllowed)
        assertFalse(c("KRX applies nothing while hidden"), has(1777.0))
    }

    /** boundary-input: 실 PAC 도달 미확인 for same-p C-1 hold/release; GENERAL/KRX uses are real SUA values. */
    @Test fun k04_restoreNeedsNewKrxUse_generalUseSurvives() = test("K04/boundary-input:same-p-capInv") {
        live(); val fence = checkNotNull(r.issuer.topicGrantResult().fence)
        val general = checkNotNull(r.uses.acquire(fence)); val old = checkNotNull(r.uses.acquireKrx(fence))
        holdRoundTrip(true)
        assertTrue(c("GENERAL held use remains valid during hold"), r.uses.admits(general))
        assertFalse(c("old KRX use withheld during hold"), r.uses.admitsKrx(old))
        holdRoundTrip(false)
        assertTrue(c("GENERAL held use remains valid after release"), r.uses.admits(general))
        assertFalse(c("old KRX use cannot revive after same-epoch release"), r.uses.admitsKrx(old))
        val fresh = checkNotNull(r.uses.acquireKrx(fence))
        assertNotEquals(c("new KRX use carries a new capInv"), old, fresh)
        r.krxTick(1777.0)
        assertFalse(c("old active capture cannot use the fresh use"), has(1777.0))
        r.until(c("restore sends a new actual capture")) { r.krxSends.size == 2 }
        r.ack(); r.krxTick(1888.0)
        assertTrue(c("only a newly accepted capture applies"), has(1888.0))
        r.assertGeneral()
    }

    @Test fun h01_validWsKrx_releasesF2_withoutTetherEvidence() = a1bTest("H01", restore = true) { f -> with(f) {
        live(seed = true); resetF2(); val before = evidence(); val count = r.store.snapshot.stateFor(KRX).receiveGeneration
        r.krxTick(1555.0)
        assertTrue(c("validated desired accepted KRX WS applies"), has(1555.0))
        assertTrue(c("valid KRX WS releases F2"), r.display.cachedRefreshResolved)
        assertEquals(c("WS records only KRX receive generation"), count + 1, r.store.snapshot.stateFor(KRX).receiveGeneration)
        assertEquals(c("KRX is OTHER delivery, never tether or D14 evidence"), before, evidence())
        assertFalse(c("no KRX graph observations before S6-3"), r.graph.filterIsInstance<TopicGraphInput.Observations>().any { it.topic == KRX })
    } }

    @Test fun h02_validRestKrx_releasesF2_withoutTetherEvidence() = a1bTest("H02", restore = true) { f -> with(f) {
        live(ack = false, seed = true); val call = issue(); resetF2()
        val before = evidence(); val generation = r.store.snapshot.stateFor(KRX).receiveGeneration
        reply(call, delivered())
        assertTrue(c("valid REST KRX applies under its issue use"), has(1555.0))
        assertTrue(c("valid REST KRX releases F2"), r.display.cachedRefreshResolved)
        assertEquals(c("REST never fabricates a WS frame"), generation, r.store.snapshot.stateFor(KRX).receiveGeneration)
        assertEquals(c("REST leaves tether/D14 evidence unchanged"), before, evidence())
        assertFalse(c("REST KRX graph remains dormant"), r.graph.filterIsInstance<TopicGraphInput.Observations>().any { it.topic == KRX })
    } }

    @Test fun h03_invalidKrx_changesNothing() = a1bVariants("H03", "WS/rate", "WS/time", "WS/source", "WS/asset", "REST/rate", "REST/time", "REST/source", "REST/asset", restore = true) { f, label -> with(f) {
        live(seed = true); val call = issue(); resetF2(); val before = observation()
        val frame = when (label.substringAfter('/')) {
            "rate" -> KrxA1bFixture.krxFrame(-1.0)
            "time" -> KrxA1bFixture.krxFrame(1555.0, "invalid-time")
            "source" -> KrxA1bFixture.krxFrame(1555.0).replace("\"source\":\"krx\"", "\"source\":\"other\"")
            else -> KrxA1bFixture.krxFrame(1555.0).replace("\"asset\":\"usd-krw-futures\"", "\"asset\":\"usd-krw\"")
        }
        if (label.startsWith("WS")) { r.wire.deliver(frame); r.drain() } else reply(call, KrxA1bFixture.Reply(200, frame))
        assertTrue("premise: ${r.row} same admitted window for invalid input", r.snapshot.facts.capabilityAllowed)
        assertEquals(c("invalid KRX changes no rates/store/F2/tether/last-known"), before, observation())
        if (label.startsWith("WS")) r.krxTick(1666.0) else {
            val count = krx.size; r.request(KRX); reply(next(count), delivered(1666.0))
        }
        assertTrue(c("same live consumer admits a subsequent valid KRX positive control"), has(1666.0))
    } }

    @Test fun h04_emptyUnwantedOrUnacceptedKrx_changesNothing() {
        for (path in listOf("WS", "REST")) for (kind in listOf("empty", "unwanted", "unaccepted", "retired")) {
            // REST admission belongs to its issue/use, not the WS ACK. The unaccepted variant is WS-only.
            if (path == "REST" && kind == "unaccepted") continue
            val desired = if (kind == "unwanted") TopicCatalogue.DESIRED else TopicCatalogue.DESIRED + KRX
            a1bTest("H04/$path/$kind", desired, restore = true) { f -> with(f) {
                live(ack = kind != "unaccepted", seed = true)
                val call = if (kind != "unwanted") issue() else null
                if (kind == "retired") loss("false")
                resetF2(); val before = observation()
                val frame = if (kind == "empty") """{"type":"snapshot","version":1,"topic":"$KRX","data":{}}""" else KrxA1bFixture.krxFrame(1777.0)
                if (path == "WS") { r.wire.deliver(frame); r.drain() }
                else if (kind == "unwanted") r.request(KRX)
                else reply(checkNotNull(call), KrxA1bFixture.Reply(200, frame))
                assertEquals(c("empty/unwanted/unaccepted/retired KRX changes nothing"), before, observation())
                if (kind == "unwanted") assertEquals(c("unwanted REST issues zero"), 0, krx.size)
                if (kind == "retired") assertTrue(c("retired service actually cancelled"), checkNotNull(call).cancelled)
            } }
        }
        a1bTest("H04/positive-control") { f -> with(f) {
            live(); r.krxTick(1888.0)
            assertTrue(c("the admitted desired accepted KRX control can apply"), has(1888.0))
        } }
    }

    @Test fun h05_krxHasNoExpiryOrRowBeforeTether() = a1bTest("H05", setOf(TETHER, KRX)) { f -> with(f) {
        live(); r.krxTick(1555.0)
        assertTrue(c("a valid KRX quote exists before tether"), has(1555.0))
        val without = r.rates.premiumDisplayFor(FreeTab.TETHER, krx = KrxRowGate(true, field("tetherDelivered") as Boolean))
        assertFalse(c("KRX is never a standalone tether row before first tether receipt"), without.scales.flatMap { it.groups }.flatMap { it.quotes }.any { it.id == "krx" })
        val before = evidence()
        r.advance(30_000)
        assertTrue(c("KRX does not expire merely with time"), has(1555.0))
        assertEquals(c("KRX never arms its own silence window"), before, evidence())
        r.generalTick()
        val afterTether = r.rates.premiumDisplayFor(FreeTab.TETHER, krx = KrxRowGate(true, field("tetherDelivered") as Boolean))
        assertTrue(c("first tether enables the already held KRX row"), afterTether.scales.flatMap { it.groups }.flatMap { it.quotes }.any { it.id == "krx" })
        val armed = evidence(); r.krxTick(1555.0)
        assertEquals(c("later KRX does not re-arm tether D14"), armed, evidence())
        repeat(3) {
            r.advance(30_000)
            assertTrue(c("held KRX survives time without KRX ticks"), has(1555.0))
            r.generalTick() // Keep GENERAL's separate D14 window healthy, without refreshing the KRX quote.
        }
    } }

    @Test fun h06_krxNeverEntersLastKnownOrFallback() = test("H06") {
        val memory = object : DataStore<Preferences> {
            val state = MutableStateFlow(emptyPreferences())
            override val data = state
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
        }
        val saved = TopicLastKnownStore(memory, TopicLastKnownClock { r.clock.nowMillis() }, 0, r.h.parts.scope)
        live(); val fence = checkNotNull(r.issuer.topicGrantResult().fence)
        val owner = TopicLastKnownOwner(fence.identity.uid, checkNotNull(fence.userAccessEpoch))
        r.onOffer = { saved.offer(owner, it) }
        r.krxTick(1555.0)
        assertTrue(c("KRX positive is present in protected memory"), has(1555.0))
        r.wire.deliver(TopicSessionKrxHarness.usdFrame(1337.0)); r.drain()
        assertTrue("premise: H06 GENERAL offers a real save control", r.offers.isNotEmpty())
        assertTrue(c("offerAdopted contains no KRX"), r.offers.all { rates -> rates.quotes.keys.none { it.source == "krx" || it.asset == "usd-krw-futures" } })
        val restored = saved.restore(owner)
        assertTrue(c("GENERAL actually round-trips through last-known storage"), restored.quotes.values.any { it.rate == 1337.0 })
        assertFalse(c("storage restores no KRX"), restored.quotes.keys.any { it.source == "krx" || it.asset == "usd-krw-futures" })
        assertFalse(c("ordinary fallback projection never consumes KRX"), r.rates.displayFor(FreeTab.TETHER).scales.flatMap { it.groups }.flatMap { it.quotes }.any { it.id == "krx" })
    }

    @Test fun k01_frameBeforeAck_isDroppedWithoutReplay() = test("K01-PREACK") {
        live(ack = false); val before = observation(); r.krxTick(1555.0)
        assertEquals(c("preACK frame is dropped without any evidence"), before, observation())
        r.ack()
        assertFalse(c("ACK does not replay the preACK frame"), has(1555.0))
        r.krxTick(1666.0); assertTrue(c("new frame after completed promotion applies"), has(1666.0)); r.assertGeneral()
    }

    @Test fun k01_ackBeforeFrame_installsTheOriginalCapture() = variants("K01-ACKFIRST", "completed-promotion", "ACK-frame-batch") { label ->
        live(ack = false); val first = r.first(); val reads = r.readLog.count { it.method == "acquireKrx" }
        if (label == "completed-promotion") {
            r.ack(first); r.krxTick(1555.0)
            assertTrue(c("completed original capture accepts next frame"), has(1555.0))
            assertEquals(c("ACK never acquires a newer KRX use"), reads, r.readLog.count { it.method == "acquireKrx" })
        } else {
            r.wire.deliver(r.ackText(first)); r.wire.deliver(KrxA1bFixture.krxFrame(1555.0)); r.drain()
            assertFalse(c("wire ACK order does not promise prior CMD promotion"), has(1555.0))
            r.drain(); assertFalse(c("queued early frame is never replayed"), has(1555.0))
            reply(issue(), delivered(1666.0)); assertTrue(c("G1(ii) REST supplies the same permit value"), has(1666.0))
            r.wire.deliver(KrxA1bFixture.krxFrame(1777.0)); r.drain(); assertTrue(c("later tick after promotion remains usable"), has(1777.0))
        }
        r.assertGeneral()
    }

    @Test fun k01_lateOldContextAck_cannotPromoteOrConsumeNewPermit() = variants("K01-OLDKEY", "identity", "USER", "generation", "grant") { kind ->
        live(ack = false); val old = r.first(); val oldWire = r.wire
        when (kind) {
            "identity" -> { r.authFenceChanged(AuthIdentityFence("a1b-new", 2)); r.pac.onIdentityChanged(checkNotNull(r.identity)).applied(); r.h.source.next = { EntitlementsOutcome.StableActive(true) }; r.pac.refresh(RefreshIntent.FORCE_PREMIUM) }
            "USER" -> { r.h.store.record = AccessEpochTransitions.rotate(r.h.store.record, true, false, EpochIdGenerator { "a1b-new-user-epoch" }); r.h.issueGrant(); r.pac.refresh(RefreshIntent.FORCE_PREMIUM) }
            "generation" -> { loss("premium"); r.h.source.next = { EntitlementsOutcome.StableActive(true) }; r.pac.refresh(RefreshIntent.FORCE_PREMIUM) }
            else -> { val g = r.h.issueGrant(); r.h.source.identityNulls = 1; r.pac.onTopicRejected(g, listOf(com.jay.fxi.domain.model.TopicRejectionReason.PREMIUM_REQUIRED)); r.until("premise: ${r.row} actual grant replaced", 10_000) { r.snapshot.facts.token != g && r.snapshot.facts.token != null } }
        }
        r.drain(); val consumed = r.consumed
        oldWire.deliver(r.ackText(old)); r.drain()
        assertEquals(c("old ACK cannot consume new context p"), consumed, r.consumed)
        assertFalse(c("old ACK installs no KRX store confirmation"), r.store.snapshot.stateFor(KRX).confirmed)
        r.until(c("new context really starts a new socket"), 2_600) { r.wires.size == 2 }; r.open(); r.ack(); r.krxTick(1888.0)
        assertTrue(c("new context has a positive accepted capture"), has(1888.0))
        val leases = generalLeases(); val current = r.consumed
        oldWire.deliver(r.ackText(old)); oldWire.deliver(KrxA1bFixture.krxFrame(1999.0)); r.drain()
        assertFalse(c("stale generation cannot overwrite new capture"), has(1999.0))
        assertEquals(c("old ACK cannot revive a GENERAL registry lease"), leases, generalLeases())
        assertEquals(c("old ACK leaves K2 consumption"), current, r.consumed); r.assertGeneral()
    }

    @Test fun k01_lateRetryAck_cannotPromoteReplacement() = test("K01-OLDATTEMPT") {
        r.failNextWire = 1; live(ack = false); val old = r.first()
        r.until(c("new real attempt follows failed physical send"), 6_000) { r.krxSends.size == 1 }
        val current = r.sends.last().request
        assertNotEquals("premise: K01-OLDATTEMPT different request ids", old.requestId, current.requestId)
        r.wire.deliver(r.ackText(old)); r.krxTick(1555.0)
        assertFalse(c("old request cannot promote replacement capture"), has(1555.0))
        assertFalse(c("old request cannot confirm replacement store"), r.store.snapshot.stateFor(KRX).confirmed)
        r.ack(current); r.krxTick(1666.0); assertTrue(c("current request promotes its own capture"), has(1666.0))
    }

    /** The real constructor-injected sink receives each original capture/lease tuple once, across repeated hide/end orders. */
    @Test fun k01_retirementIsIdempotent_andPreservesGeneral() = variants("K01-RETIRED", "hide-hide-end", "end-hide-hide", "hide-end-hide") { order ->
        live(); r.krxTick(1555.0); assertTrue(c("original active capture positive"), has(1555.0))
        val expected = expectedRetirement(r.krxSends.single().request.requestId)
        assertTrue(c("an active unretired capture has emitted no request"), retirements.requests.isEmpty())
        val wire = r.wire; val leases = generalLeases()
        val originalConnection = checkNotNull(field("connection")) { "premise: ${r.row} original accepted connection exists" }
        val originalGeneration = KrxSessionIntegrationRig.privateValue(originalConnection, "generation")
        var ended = false
        for (step in order.split('-')) {
            if (step == "hide") { loss("false"); r.revise(); r.drain() } else { wire.drop(); r.drain(); ended = true }
            wire.deliver(KrxA1bFixture.krxFrame(1777.0)); r.drain()
            assertFalse(c("retired capture cannot adopt after repeated $step"), has(1777.0))
            assertEquals(c("actual retirement remains the one original generation/request/lease after $step"),
                listOf(expected), retirements.requests.map { it.tuple })
            assertEquals(c("one original tuple is delivered, never one per hide/end input"),
                listOf(expected), retirements.deliveries.map { it.request.tuple })
            if (!ended) {
                assertSame(c("hide while live preserves the current connection"), originalConnection, field("connection"))
                assertEquals(c("hide while live preserves the current generation"), originalGeneration,
                    KrxSessionIntegrationRig.privateValue(originalConnection, "generation"))
                assertEquals(c("hide while live never marks the connection ended"), false,
                    KrxSessionIntegrationRig.privateValue(originalConnection, "ended"))
                assertEquals(c("capability retirement preserves GENERAL leases"), leases, generalLeases())
                r.assertGeneral()
            } else {
                assertEquals(c("drop marks the original connection ended"), true,
                    KrxSessionIntegrationRig.privateValue(originalConnection, "ended"))
                assertNull(c("drop releases the current connection before any reconnect"), field("connection"))
                assertFalse(c("drop resets GENERAL tether confirmation"), r.store.snapshot.stateFor(TopicCatalogue.TETHER).confirmed)
                assertFalse(c("drop resets GENERAL USD confirmation"), r.store.snapshot.stateFor(USD).confirmed)
                assertFalse(c("drop resets KRX confirmation"), r.store.snapshot.stateFor(KRX).confirmed)
            }
        }
        assertEquals(c("sink receives and delivers the same request exactly once"), listOf("received:1", "delivered:1"), retirements.timeline)
    }

    @Test fun k01_oldAckCannotInstallKrxLeaseInGeneralRegistry() = test("K01-LEASES") {
        live(ack = false); val old = r.first(); r.falseThenTrue()
        r.wire.deliver(r.ackText(old)); r.drain()
        assertFalse(c("stale full active_subscriptions cannot install KRX in GENERAL registry"), KRX in generalLeases())
        assertFalse(c("stale ACK cannot confirm retired KRX"), r.store.snapshot.stateFor(KRX).confirmed)
        r.until(c("K2 current permit sent")) { r.krxSends.size == 2 }; r.ack(); r.krxTick(1888.0)
        assertTrue(c("K2 positive after valid ACK"), has(1888.0))
        assertFalse(c("even current KRX lease remains outside GENERAL registry"), KRX in generalLeases()); r.assertGeneral()
    }

    /** A blocked renewal credential isolates expiry of the one active KRX lease from any second physical attempt. */
    @Test fun k01_krxLeaseExpiry_retiresKrxOnly() = test("K01-EXPIRY") {
        live(ack = false); val first = r.first()
        val expected = expectedRetirement(first.requestId)
        r.credentialGate = CompletableDeferred<Unit>()
        val ack = r.ackText(first).replace("\"lease_id\":\"${first.requestId}-$KRX\",\"lease_duration_seconds\":600", "\"lease_id\":\"${first.requestId}-$KRX\",\"lease_duration_seconds\":2")
        val acknowledgedAt = r.clock.nowMillis()
        r.wire.deliver(ack); r.drain(); r.krxTick(1555.0)
        assertTrue(c("short KRX lease is initially active"), has(1555.0))
        assertTrue(c("valid active lease has not been retired"), retirements.requests.isEmpty())
        val wire = r.wire; val leases = generalLeases()
        r.advance(1_999); assertFalse("premise: K01-EXPIRY socket still alive just before KRX expiry", wire.cancelled)
        assertEquals("premise: K01-EXPIRY credential gate prevented a second physical KRX attempt", 1, r.krxSends.size)
        assertEquals(c("no retirement before exact KRX lease deadline"), 0, retirements.requests.size)
        r.advance(1); r.krxTick(1777.0)
        assertFalse(c("expired KRX capture rejects frame"), has(1777.0))
        assertEquals(c("expiry emits exactly the active KRX generation/request/lease tuple"), listOf(expected), retirements.requests.map { it.tuple })
        assertEquals(c("retirement occurs at the original ACK lease deadline"), acknowledgedAt + 2_000, retirements.requests.single().at)
        assertEquals(c("expiry hands off one actual request"), listOf(expected), retirements.deliveries.map { it.request.tuple })
        assertSame(c("KRX expiry never replaces GENERAL socket"), wire, r.wire)
        assertFalse(c("KRX expiry never closes GENERAL socket"), wire.cancelled)
        assertEquals(c("GENERAL leases are unchanged at KRX expiry"), leases, generalLeases()); r.assertGeneral()
        repeat(3) { r.revise(); r.drain() }
        r.krxTick(1888.0)
        assertFalse(c("expired capture cannot revive after repeated reconciliation"), has(1888.0))
        assertEquals(c("reconciliation cannot duplicate the expiry retirement"), listOf(expected), retirements.requests.map { it.tuple })
        assertEquals(c("KRX expiry still leaves GENERAL leases intact"), leases, generalLeases())
    }

    /** The real K1 request is received at retirement; its consumer delivery is held before/after completed K2 acceptance.
     * This recording sink performs no actual unsubscribe or registry clear (S6-3), and never fabricates a retirement tuple. */
    @Test fun k02_retirementAndNewCapture_bothOrders() = variants("K02-CANCELRACE", "retirement-before-K2", "retirement-after-K2") { label ->
        live(); val old = issue(); val oldTuple = expectedRetirement(r.krxSends.single().request.requestId)
        val leasesBefore = generalLeases(); val wire = r.wire
        assertTrue(c("K1 is active before the first real retirement"), retirements.requests.isEmpty())
        retirements.holdDelivery = true
        val gate = InputGate("BootstrapAnswered")
        try {
            // Its timestamp is newer than K2's later tick, so rejection cannot be explained by first-wins merge order.
            reply(old, delivered(1777.0, "2026-09-28T10:20:00+09:00")); assertTrue(c("actual K1 answer queued before loss"), gate.count > 0)
            r.falseThenTrue()
            assertEquals(c("actual K1 retirement request received once with its original tuple"), listOf(oldTuple), retirements.requests.map { it.tuple })
            val request = retirements.requests.single()
            assertEquals(c("actual request is held at the recording consumer"), 1, retirements.pendingCount)
            assertTrue(c("holding retirement delivery is nonblocking and does not invent a completion"), retirements.deliveries.isEmpty())
            if (label.endsWith("before-K2")) {
                retirements.release(); gate.release()
                assertFalse(c("old completion before K2 cannot apply under its retired use"), has(1777.0))
            }
            r.until(c("new actual K2 command reaches wire")) { r.krxSends.size == 2 }
            val k2 = r.krxSends.last().request
            val k2Tuple = expectedRetirement(k2.requestId)
            assertNotEquals(c("K2 owns a different request and lease tuple"), oldTuple, k2Tuple)
            r.ack(); r.krxTick(1888.0)
            assertTrue(c("K2 positive before old completion assertion"), has(1888.0))
            assertTrue(c("K2 ACK really confirmed KRX"), r.store.snapshot.stateFor(KRX).confirmed)
            retirements.mark("K2_ACCEPTED")
            val consumed = r.consumed; val leases = generalLeases()
            if (label.endsWith("after-K2")) { retirements.release(); gate.release() }
            assertEquals(c("late consumer receives only the actual old tuple"), listOf(oldTuple), retirements.deliveries.map { it.request.tuple })
            assertSame(c("deferred delivery is the very request emitted by the real session"), request, retirements.deliveries.single().request)
            val deliveredAt = retirements.timeline.indexOf("delivered:${request.sequence}")
            val acceptedAt = retirements.timeline.indexOf("mark:K2_ACCEPTED")
            assertTrue(c("retirement consumer delivery and K2 acceptance reached the requested order"),
                deliveredAt >= 0 && acceptedAt >= 0 && (if (label.endsWith("before-K2")) deliveredAt < acceptedAt else acceptedAt < deliveredAt))
            assertFalse(c("old completion cannot apply after retirement"), has(1777.0))
            assertTrue(c("old completion cannot retire K2"), has(1888.0))
            assertEquals(c("no duplicate old retirement and no K2 retirement"), listOf(oldTuple), retirements.requests.map { it.tuple })
            r.krxTick(1999.0)
            assertTrue(c("K2 still accepts a fresh tick after late old retirement delivery"), has(1999.0))
            assertEquals(c("K2 consumption unchanged"), consumed, r.consumed)
            assertSame(c("capability replacement preserves the GENERAL socket"), wire, r.wire)
            assertFalse(c("old retirement never closes GENERAL socket"), wire.cancelled)
            assertEquals(c("GENERAL registry unchanged"), leases, generalLeases())
            assertEquals(c("GENERAL registry also equals its original K1-era state"), leasesBefore, generalLeases()); r.assertGeneral()
        } finally { gate.close() }
    }

    private suspend fun KrxA1bFixture.oldWs(sameEpoch: Boolean) {
        live(ack = false); val old = r.first(); val oldEpoch = r.snapshot.facts.recordFence?.krxCapabilityEpoch
        if (sameEpoch) { holdRoundTrip(true); holdRoundTrip(false) } else r.falseThenTrue()
        r.ack(old); r.krxTick(1555.0)
        assertFalse(c("retired original use cannot promote or adopt before K2 ACK"), has(1555.0))
        assertFalse(c("stale ACK never confirms retired KRX"), r.store.snapshot.stateFor(KRX).confirmed)
        if (sameEpoch) assertEquals("premise: ${r.row} epoch held constant", oldEpoch, r.snapshot.facts.recordFence?.krxCapabilityEpoch)
        else assertNotEquals("premise: ${r.row} actual epoch changed", oldEpoch, r.snapshot.facts.recordFence?.krxCapabilityEpoch)
        r.until(c("new capture sent after old ACK")) { r.krxSends.size == 2 }; r.ack(); r.krxTick(1666.0)
        assertTrue(c("new capture positive after its own ACK"), has(1666.0)); r.assertGeneral()
    }
    private suspend fun KrxA1bFixture.oldRest(sameEpoch: Boolean) {
        live(); val call = issue(); val gate = InputGate("BootstrapAnswered")
        try {
            reply(call, delivered(1555.0)); assertTrue(c("actual old REST answer reached queue"), gate.count > 0)
            if (sameEpoch) { holdRoundTrip(true); holdRoundTrip(false) } else r.falseThenTrue()
            gate.release(); assertFalse(c("old REST capture cannot rebind to current use"), has(1555.0))
            if (krx.size == 1) r.request(KRX)
            val current = if (krx.size == 1) next(1) else krx.last()
            assertNotEquals(c("new issue uses a new C-1 attribution"), call.read.capabilityInvalidations to call.read.facts.recordFence, current.read.capabilityInvalidations to current.read.facts.recordFence)
            reply(current, delivered(1666.0)); assertTrue(c("new REST issue has positive adoption"), has(1666.0)); r.assertGeneral()
        } finally { gate.close() }
    }
    /** α-4 independent column; existing locked r2/a3 methods are unchanged. */
    @Test fun a4_k01_oldGenerationQueuedBeforeTransition_isRejected() = KrxA4Rows.oldGenerationInputs("K01-OLDKEY", listOf("identity", "USER", "generation", "grant"))

}
