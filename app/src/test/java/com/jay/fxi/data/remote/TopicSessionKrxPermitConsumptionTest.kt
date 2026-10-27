package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.AccessEpochTransitions
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.EpochIdGenerator
import com.jay.fxi.data.entitlements.KrxContestEvent
import com.jay.fxi.data.entitlements.KrxContestIgnore
import com.jay.fxi.data.entitlements.KrxContestPhase
import com.jay.fxi.data.entitlements.KrxContestPolicy
import com.jay.fxi.data.entitlements.KrxContestState
import com.jay.fxi.data.entitlements.KrxContestStep
import com.jay.fxi.data.entitlements.KrxSendMarker
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.applied
import com.jay.fxi.data.entitlements.issueGrant
import com.jay.fxi.data.entitlements.rejectKrx
import com.jay.fxi.data.entitlements.recheckSchedule
import com.jay.fxi.data.entitlements.readPrivate
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.KRX
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.TETHER
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.USD
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.domain.model.TopicRequestPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** α-0 r2 §5.1, one independently runnable method per row. Synthetic boundary inputs are identified in KDoc. */
@OptIn(ExperimentalCoroutinesApi::class)
class TopicSessionKrxPermitConsumptionTest {
    private fun c(r: KrxSessionIntegrationRig, text: String) = "contract(${r.row}): $text"
    private suspend fun opened(r: KrxSessionIntegrationRig, ack: Boolean = true) {
        r.approve(); r.start(); r.open()
        assertTrue(c(r, "FIRST includes KRX"), KRX in r.first().topics)
        assertEquals(c(r, "successful FIRST consumes its permit before ACK"), r.snapshot.krxPermitSeq, r.consumed)
        if (ack) { r.ack(); r.assertGeneral() }
    }
    private fun expectNewSend(r: KrxSessionIntegrationRig, before: Int) {
        r.until(c(r, "one owed KRX send")) { r.krxSends.size == before + 1 }
        assertEquals(c(r, "exactly one new KRX send"), before + 1, r.krxSends.size)
    }
    private fun repeatRevision(r: KrxSessionIntegrationRig, count: Int = 3) {
        repeat(count) { r.revise(); r.drain() }
    }
    private fun markerOf(r: KrxSessionIntegrationRig, request: TopicSubscribeRequest): KrxSendMarker {
        r.processRefusals = false
        r.ack(request, reject = true)
        // The callback is deliberately delayed, so recording the marker is not allowed to ask the latest PAC here.
        r.delayedRefusals.toList().also { r.delayedRefusals.clear() }.forEach { it() }
        r.drain()
        return checkNotNull(r.issuer.reserved.lastOrNull()?.marker) { c(r, "marker reached real issuer") }
    }

    @Test fun w01a_firstDelivery_honoursE() = a1aTest("W01a") { r ->
        r.approve(); r.hold(); r.start(); r.open()
        r.assertWindow(KrxContestPhase.HOLD)
        assertTrue("premise: W01a GENERAL remains in FIRST", TETHER in r.first().topics)
        assertFalse(c(r, "HOLD FIRST omits KRX"), KRX in r.first().topics)
        r.ack(); r.assertGeneral()
        val until = checkNotNull(r.pac.krxContestView?.state?.holdUntilMillis)
        r.advance(until - r.clock.nowMillis()); r.ack()
        r.reconnect()
        assertTrue(c(r, "OPEN consumed permit still belongs in reconnect FIRST"), KRX in r.sends.last().request.topics)
    }

    @Test fun w01b_renewal_honoursE_withoutConsumption() = a1aTest("W01b") { r ->
        opened(r, ack = false)
        r.ack(seconds = 181)
        r.hold()
        val consumed = r.consumed
        val ladders = r.krxCalls.size
        val count = r.sends.size
        r.until(c(r, "renewal owns accepted GENERAL lease"), 1_100) { r.sends.size > count }
        r.assertWindow(KrxContestPhase.HOLD)
        assertFalse(c(r, "renewal filters KRX by E"), KRX in r.sends.last().request.topics)
        assertTrue(c(r, "renewal keeps accepted desired GENERAL"), TETHER in r.sends.last().request.topics)
        assertEquals(c(r, "renewal consumes no permit"), consumed, r.consumed)
        assertEquals(c(r, "renewal creates no G1(ii)"), ladders, r.krxCalls.size)
    }

    @Test fun w01c_recovery_rechecksCurrentPermit() = variants("W01c", "current-p", "old-krx-rejection") { r, variant ->
        authRecoveryPermit(r, staleKrxRejection = variant == "old-krx-rejection", recoveryBeforeDone = false)
    }

    @Test fun w01d_manual_honoursE_preservesGeneral() = a1aTest("W01d") { r ->
        opened(r)
        r.hold()
        val before = r.sends.size
        r.rejectForManual()
        r.manual()
        r.until(c(r, "GENERAL manual retry still sends")) { r.sends.size > before }
        r.assertWindow(KrxContestPhase.HOLD)
        assertFalse(c(r, "manual omits withheld KRX"), KRX in r.sends.last().request.topics)
        assertTrue(c(r, "manual still contains USD"), USD in r.sends.last().request.topics)
        val permit = r.snapshot.krxPermitSeq
        r.ack(); repeatRevision(r)
        assertEquals(c(r, "manual does not issue an entitlement permit"), permit, r.snapshot.krxPermitSeq)
    }

    @Test fun w01e_explicitRest_honoursE_atEveryBoundary() = a1aTest("W01e") { r ->
        opened(r)
        r.until(c(r, "initial KRX call exists for join/cancel control"), 2_100) { r.krxCalls.size == 1 }
        val initial = r.krxCalls.single()
        r.request(KRX); r.request(KRX)
        assertEquals(c(r, "same topic joins an in-flight request"), 1, r.krxCalls.size)
        r.awaiting()
        assertTrue("premise: W01e held token has not exchanged", initial.exchanges.isEmpty())
        assertTrue(c(r, "withheld token request is actually cancelled"), initial.cancelled)
        val count = r.krxCalls.size
        r.assertWindow(KrxContestPhase.AWAITING)
        r.request(KRX)
        assertEquals(c(r, "withheld explicit request issues no HTTP"), count, r.krxCalls.size)
        initial.token.complete(Unit); initial.body.complete(TopicSnapshotOutcome.Unreachable(java.io.IOException("a1a injected unreachable")))
        r.advance(2_000); r.assertWindow(KrxContestPhase.AWAITING)
        assertEquals(c(r, "withheld retry also issues nothing"), count, r.krxCalls.size)
        assertEquals(c(r, "cancelled body never physically exchanges"), emptyList<Boolean>(), initial.exchanges)
    }

    @Test fun w01f_initialPlan_isLast_andConsumesOnlyAtIssue() = variants("W01f", "visible", "hidden-after-plan") { r, variant ->
        r.approve(); r.start()
        val p = r.snapshot.krxPermitSeq
        assertEquals(c(r, "planning alone consumes nothing"), 0L, r.consumed)
        if (variant == "hidden-after-plan") r.awaiting()
        r.advance(2_100)
        assertEquals("premise: W01f original five are issued in HEAD order", TopicBootstrapOrder.plan(com.jay.fxi.domain.model.FreeTab.USD, TopicCatalogue.DESIRED), r.calls.take(5).map { it.topic })
        if (variant == "visible") {
            assertEquals(c(r, "KRX is logical sixth"), KRX, r.calls.getOrNull(5)?.topic)
            assertEquals(c(r, "G1(i) issue consumes p"), p, r.consumed)
        } else {
            r.assertWindow(KrxContestPhase.AWAITING)
            assertEquals(c(r, "hidden initial plan issues no KRX"), 0, r.krxCalls.size)
            assertEquals(c(r, "unissued plan does not consume"), 0L, r.consumed)
        }
    }

    @Test fun w01g_permitSend_startsOneLadder() = a1aTest("W01g") { r ->
        opened(r)
        r.until(c(r, "initial ladder"), 2_100) { r.krxCalls.size == 1 }
        r.complete(r.krxCalls.single(), r.delivered(KrxSessionIntegrationRig.krxFrame(1551.0)))
        val epoch = r.snapshot.facts.recordFence?.krxCapabilityEpoch
        val grant = r.h.issueGrant()
        r.h.source.identityNulls = 1
        r.h.rejectKrx(grant); r.drain()
        // H1′ holds the record epoch while the next confirmation issues a new p.
        r.advance(5_010)
        r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS); r.drain()
        assertEquals("premise: W01g same record epoch", epoch, r.snapshot.facts.recordFence?.krxCapabilityEpoch)
        r.until(c(r, "new permit starts a second ladder despite same epoch"), 2_100) { r.krxCalls.size == 2 }
        repeatRevision(r)
        assertEquals(c(r, "one ladder for that p"), 2, r.krxCalls.size)
    }

    @Test fun w01h_unconsumedPermit_sendsOnce() = a1aTest("W01h") { r ->
        opened(r)
        val before = r.krxSends.size
        r.falseThenTrue(); expectNewSend(r, before)
        val p = r.snapshot.krxPermitSeq
        assertEquals(c(r, "successful permit send consumed"), p, r.consumed)
        repeatRevision(r)
        assertTrue("premise: W01h still OPEN with pending KRX", r.snapshot.facts.capabilityAllowed)
        assertEquals(c(r, "same p revisions cannot send again"), before + 1, r.krxSends.size)
        r.ack(); repeatRevision(r)
        assertTrue("premise: W01h no next p appeared", p == r.snapshot.krxPermitSeq)
        assertEquals(c(r, "ACK and revisions cannot duplicate consumed p"), before + 1, r.krxSends.size)
    }

    @Test fun w06_firstFlipPermit_bothOrders_merge() = variants("W06", "ws-first", "rest-first") { r, variant ->
        r.approve(); r.start()
        if (variant == "rest-first") r.advance(2_100)
        r.open(); r.ack(); r.advance(2_100)
        assertEquals(c(r, "one initial KRX subscribe across FIRST/flip/permit"), 1, r.krxSends.size)
        assertEquals(c(r, "G1(i)/G1(ii) merge to one ladder"), 1, r.krxCalls.size)
        assertEquals(c(r, "merged successful send/issue consumes the actual permit"), r.snapshot.krxPermitSeq, r.consumed)
        val sent = r.commands.flatMap { it.sent }.single { KRX in it.topics }
        assertAttemptRead(r, sent)
        assertEquals(c(r, "merged FIRST still consumes its own marker p"), sent.marker.permitSeq, sent.consumePermit)
        assertEquals(c(r, "merged REST issue and physical FIRST carry the same p"), r.krxCalls.single().read.krxPermitSeq, sent.marker.permitSeq)
        repeatRevision(r)
        assertTrue("premise: W06 same permit still OPEN", r.snapshot.facts.capabilityAllowed)
        assertEquals(c(r, "no second initial ladder"), 1, r.krxCalls.size)
    }

    /** ACK promotion must complete before an ACK-first positive frame; wire ordering alone may drop the first frame. */
    @Test fun w07a_reconnectAfterConsumption_includesKrx() = a1aTest("W07a") { r ->
        opened(r)
        val p = r.consumed
        r.reconnect()
        assertTrue(c(r, "consumed OPEN permit belongs in reconnect FIRST"), KRX in r.sends.last().request.topics)
        r.ack(); repeatRevision(r)
        assertEquals(c(r, "TSC consumption survives connection replacement"), p, r.consumed)
        assertEquals(c(r, "FIRST only; no extra permit subscribe"), 2, r.krxSends.size)
    }

    /** Reconnect FIRST can wait until the next KRX tick if its queued first frame precedes promotion; memory has no expiry. */
    @Test fun w07b_disconnectBeforeAck_keepsSessionConsumption() = a1aTest("W07b") { r ->
        opened(r, ack = false)
        val p = r.consumed
        r.reconnect()
        assertEquals(c(r, "successful unacknowledged send stays consumed"), p, r.consumed)
        assertTrue(c(r, "next FIRST still carries KRX"), KRX in r.sends.last().request.topics)
        r.ack(); repeatRevision(r)
        assertEquals(c(r, "no independent duplicate after reconnect ACK"), 2, r.krxSends.size)
    }

    @Test fun w07c_rejectAckBeforePac_oldRefusalAddsNoFe() = variants("W07c", "pac-delayed", "ack-delayed") { r, variant ->
        opened(r, ack = false)
        val first = r.first()
        val marker = KrxSendMarker(r.snapshot.facts.recordFence?.krxCapabilityEpoch, r.snapshot.krxPermitSeq)
        r.processRefusals = false
        if (variant == "pac-delayed") r.ack(first, reject = true)
        r.falseThenTrue()
        if (variant == "ack-delayed") r.ack(first, reject = true)
        val before = r.h.source.calls.size
        assertTrue("premise: W07c newer permit exists before handover", r.snapshot.krxPermitSeq > marker.permitSeq)
        r.delayedRefusals.toList().also { r.delayedRefusals.clear() }.forEach { it() }; r.drain()
        assertEquals(c(r, "reservation kept old attempt marker"), marker, r.issuer.reserved.last().marker)
        assertEquals(c(r, "old refusal adds no FE"), before, r.h.source.calls.size)
    }

    @Test fun w07d_awaitingReconnect_sendsNoKrx() = a1aTest("W07d") { r ->
        opened(r); r.awaiting(); r.reconnect(); r.assertWindow(KrxContestPhase.AWAITING)
        assertFalse(c(r, "AWAITING reconnect omits KRX"), KRX in r.sends.last().request.topics)
        r.ack(); r.assertGeneral()
    }

    @Test fun w07e_holdReconnect_sendsNoKrx() = a1aTest("W07e") { r ->
        opened(r); r.hold(); r.reconnect(); r.assertWindow(KrxContestPhase.HOLD)
        assertFalse(c(r, "HOLD reconnect omits KRX"), KRX in r.sends.last().request.topics)
        r.ack(); r.assertGeneral()
    }

    @Test fun w08a_pendingThenAccept_rechecksNewPermit() = a1aTest("W08a") { r ->
        opened(r, ack = false)
        val first = r.first(); val before = r.krxSends.size
        r.falseThenTrue()
        assertTrue("premise: W08a still within old ACK deadline", r.clock.nowMillis() - r.sends.first().at < TopicRequestPolicy.ACK_TIMEOUT.inWholeMilliseconds)
        assertEquals(c(r, "no second KRX pending before ACK"), before, r.krxSends.size)
        r.ack(first); expectNewSend(r, before)
        assertEquals(c(r, "new send consumed current p"), r.snapshot.krxPermitSeq, r.consumed)
        assertNotEquals(c(r, "different request ids"), first.requestId, r.sends.last().request.requestId)
    }

    @Test fun w08b_pendingThenReject_rechecksNewPermit() = variants("W08b", *KrxPermitPendingMatrix.labels.toTypedArray()) { r, label ->
        KrxPermitPendingMatrix.exercise(r, label)
    }

    @Test fun w08c_pendingThenDisconnect_reconnectsOnce() = a1aTest("W08c") { r ->
        opened(r, ack = false); r.falseThenTrue()
        r.reconnect(); r.ack(); repeatRevision(r)
        assertEquals(c(r, "current permit reconnect FIRST only"), 2, r.krxSends.size)
        assertEquals(c(r, "current p consumed by reconnect"), r.snapshot.krxPermitSeq, r.consumed)
    }

    @Test fun w13a_generalLaneRelease_wakesPermit() = variants("W13a", "renewal", "revalidation", "manual") { r, variant ->
        opened(r, ack = false)
        if (variant == "renewal") {
            r.wire.deliver(r.ackFor(r.first(), emptyMap(), seconds = 181, leaseTopics = r.desired - KRX)); r.drain(); r.generalTick()
            r.until("premise: W13a natural renewal owns GENERAL lane", 1_100) { r.commands.any { it.purpose == TopicCommandPurpose.LEASE_RENEWAL } }
        } else {
            r.ack(); r.generalTick()
            if (variant == "manual") { r.rejectForManual(); r.manual() }
            else {
                // Finish the initial delivery owner's 45s window, then arm a fresh tether window before timing it out.
                r.advance(45_010); r.generalTick(1450.0)
                r.until("premise: W13a natural D14 revalidation owns GENERAL lane", 45_100) {
                    r.commands.any { it.purpose == TopicCommandPurpose.REVALIDATION }
                }
            }
        }
        val lane = r.sends.last().request
        val owner = r.commands.last()
        assertTrue("premise: W13a $variant control request is GENERAL", KRX !in lane.topics)
        assertTrue("premise: W13a $variant has actual registered command", owner.id > 0)
        val before = r.krxSends.size
        r.falseThenTrue()
        assertTrue("premise: W13a $variant still before ACK timeout", r.clock.nowMillis() - r.sends.last().at < 20_000)
        assertEquals(c(r, "permit waits for $variant lane"), before, r.krxSends.size)
        r.ack(lane); expectNewSend(r, before)
        assertEquals(c(r, "follow-up source is PERMIT"), KrxAttemptSource.PERMIT, r.commands.flatMap { it.sent }.last().source)
        r.ack(); repeatRevision(r)
        assertEquals(c(r, "one permit after $variant"), before + 1, r.krxSends.size)
    }

    @Test fun w13b_authEndAndRecovery_keepPermitOwed() = variants("W13b", "recovery-before-command-done", "recovery-after-command-done") { r, variant ->
        authRecoveryPermit(r, staleKrxRejection = false, recoveryBeforeDone = variant.startsWith("recovery-before"))
    }

    /** boundary-input: 실 PAC 도달 미확인. Existing H1′ hold probes reissue p; they do not establish a same-p hold.
     * Consumer C-1 inputs preserve the real PAC grant/epoch/p and vary only capability block, revision and capInv (D-E). */
    @Test fun w14a_samePermitHideReopen_resendsOnce() = variants("W14a", "boundary-input:LOSS_CANDIDATE", "boundary-input:DERIVED_SEAL", "boundary-input:hidden-reconnect", "boundary-input:conflated") { r, label ->
        val variant = label.removePrefix("boundary-input:")
        opened(r)
        val before = r.krxSends.size
        val open = r.snapshot
        val held = open.copy(revision = open.revision + 1, capabilityInvalidations = open.capabilityInvalidations + 1,
            facts = open.facts.copy(capabilityBlocks = setOf(if (variant == "DERIVED_SEAL") TopicAccessBlock.DERIVED_SEAL else TopicAccessBlock.LOSS_CANDIDATE)))
        val released = open.copy(revision = held.revision + 1, capabilityInvalidations = held.capabilityInvalidations)
        r.snapshotSupply = { held }
        if (variant != "conflated") { r.revise(); r.drain() }
        if (variant == "hidden-reconnect") {
            r.reconnect()
            assertTrue("premise: W14a synthetic same-p hold still supplied", !r.snapshotSupply().facts.capabilityAllowed)
            assertFalse(c(r, "hidden FIRST has no KRX"), KRX in r.sends.last().request.topics)
            r.ack()
        }
        r.snapshotSupply = { released }; r.revise(); r.drain()
        expectNewSend(r, before)
        assertEquals(c(r, "same p is consumed again after C-1 invalidation"), open.krxPermitSeq, r.consumed)
        r.ack(); repeatRevision(r)
        assertEquals(c(r, "one resend after $variant"), before + 1, r.krxSends.size)
        r.assertGeneral()
    }

    @Test fun w14b_rejectAck_doesNotRefundConsumption() = a1aTest("W14b") { r ->
        opened(r, ack = false)
        r.processRefusals = false
        val p = r.consumed; val sends = r.krxSends.size
        r.ack(reject = true); repeatRevision(r)
        assertTrue("premise: W14b PAC handover still delayed; E OPEN", r.snapshot.facts.capabilityAllowed && r.delayedRefusals.size == 1)
        assertEquals(c(r, "ACK does not refund p"), p, r.consumed)
        assertEquals(c(r, "ACK alone cannot resend p"), sends, r.krxSends.size)
    }

    @Test fun w15_successConsumesTheAttemptPermit() = variants("W15", "send-false", "blocked", "success", "rest-issue") { r, variant ->
        r.approve()
        if (variant == "send-false") r.failNextWire = 1
        if (variant == "blocked") r.awaiting()
        r.start()
        if (variant == "rest-issue") {
            assertEquals(c(r, "unissued plan consumes zero"), 0L, r.consumed)
            r.until(c(r, "real G1(i) logical issue"), 2_100) { r.krxCalls.isNotEmpty() }
            assertEquals(c(r, "G1(i) consumes issue p"), r.krxCalls.single().read.krxPermitSeq, r.consumed)
        } else {
            r.open()
            if (variant == "send-false" || variant == "blocked") {
                if (variant == "blocked") r.assertWindow(KrxContestPhase.AWAITING)
                else assertTrue("premise: W15 failed physical send observed", r.sends.any { !it.accepted })
                assertEquals(c(r, "failure/blocked consumes zero"), 0L, r.consumed)
                if (variant == "send-false") {
                    r.until(c(r, "retry succeeds"), 6_000) { r.krxSends.isNotEmpty() }
                    assertEquals(c(r, "retry consumes success p"), r.snapshot.krxPermitSeq, r.consumed)
                }
            } else assertEquals(c(r, "success consumes p"), r.snapshot.krxPermitSeq, r.consumed)
        }
    }

    @Test fun r11iv_allEightPaths_carryMarkers() = variants("R11-iv", "FIRST", "RENEWAL", "RECOVERY", "MANUAL", "EXPLICIT", "G1i", "G1ii", "PERMIT", "CAP-R01/ws-rest/H1", "CAP-R01/ws-rest/H1prime", "CAP-R01/rest-ws/H1", "CAP-R01/rest-ws/H1prime", "CAP-R04/rest-topics_disabled", "CAP-R04/rest-topic_unavailable", "CAP-R04/ws-topic_unavailable", "CAP-R04/rest-nonkrx-unsupported", "CAP-R04/rest-503", "CAP-R04/rest-403", "EXPLICIT/supported-list", "G1i/pacing-new-p") { r, path ->
        if (path.startsWith("CAP-R01/")) {
            overlappingRefusals(r, path.contains("/ws-rest/"), path.endsWith("H1prime")); return@variants
        }
        if (path.startsWith("CAP-R04/")) { nonContestAnswer(r, path.substringAfter('/')); return@variants }
        val route = path.substringBefore('/')
        r.approve(); r.start()
        if (path == "G1i/pacing-new-p") r.falseThenTrue()
        if (route == "G1i") {
            r.until(c(r, "G1(i) issues before WS FIRST"), 2_100) { r.krxCalls.size == 1 }
        } else {
            r.open()
            assertEquals(c(r, "FIRST actual p consumption"), r.snapshot.krxPermitSeq, r.consumed)
        }
        var request: TopicSubscribeRequest? = if (route == "FIRST") r.first() else null
        val expectedSource = when (route) {
            "FIRST" -> KrxAttemptSource.FIRST
            "RENEWAL" -> KrxAttemptSource.RENEWAL
            "RECOVERY" -> KrxAttemptSource.RECOVERY
            "MANUAL" -> KrxAttemptSource.MANUAL
            else -> KrxAttemptSource.PERMIT
        }
        if (route in setOf("RENEWAL", "MANUAL", "EXPLICIT", "G1ii", "PERMIT")) {
            r.ack(seconds = if (route == "RENEWAL") 181 else 600); r.generalTick()
        }
        when (route) {
            "RENEWAL" -> r.until(c(r, "natural renewal carries KRX"), 1_100) {
                r.commands.any { record -> record.sent.any { it.source == KrxAttemptSource.RENEWAL && KRX in it.topics } }
            }
            "MANUAL" -> {
                r.rejectForManual(includeKrx = true); r.manual()
                r.until(c(r, "real public manual path carries KRX")) {
                    r.commands.any { record -> record.sent.any { it.source == KrxAttemptSource.MANUAL && KRX in it.topics } }
                }
            }
            "RECOVERY" -> {
                r.wire.deliver("""{"type":"subscription_error","request_id":"${r.first().requestId}","error":"invalid_token"}""")
                r.drain(); r.recover()
                r.until(c(r, "auth recovery carries KRX in a new actual attempt")) {
                    r.commands.any { record -> record.sent.any { it.source == KrxAttemptSource.RECOVERY && KRX in it.topics } }
                }
            }
            "PERMIT" -> {
                val before = r.krxSends.size; r.falseThenTrue(); expectNewSend(r, before)
            }
            "EXPLICIT", "G1ii" -> {
                r.until(c(r, "initial G1(i) call ready to settle"), 2_100) { r.krxCalls.size == 1 }
                r.complete(r.krxCalls.single(), r.delivered(KrxSessionIntegrationRig.krxFrame(1552.0)))
                val before = r.krxCalls.size
                if (route == "EXPLICIT") r.request(KRX) else r.falseThenTrue()
                r.until(c(r, "$path creates its own new logical issue"), 2_100) { r.krxCalls.size == before + 1 }
            }
        }
        if (route in setOf("EXPLICIT", "G1i", "G1ii")) {
            val call = r.krxCalls.last()
            assertTrue(c(r, "$path payload read is an actual issue read"), r.readLog.any { it.method == "acquireWithKrx" && it.snapshot === call.read })
            val marker = KrxSendMarker(call.read.facts.recordFence?.krxCapabilityEpoch, call.read.krxPermitSeq)
            if (route == "G1i") assertEquals(c(r, "G1(i) consumes its actual issue p"), marker.permitSeq, r.consumed)
            r.complete(call, TopicSnapshotOutcome.Unsupported(if (path == "EXPLICIT/supported-list") listOf(KRX, USD) else emptyList()))
            assertEquals(c(r, "$path actual issue marker reaches real issuer"), marker, r.issuer.reserved.lastOrNull()?.marker)
            if (route == "G1i") { r.open(); r.ack() }
        } else {
            val attempt = r.commands.flatMap { it.sent }.last { it.source == expectedSource && KRX in it.topics }
            assertAttemptRead(r, attempt)
            request = request ?: r.sends.single { it.request.requestId == attempt.requestId }.request
            assertEquals(c(r, "$path marker reaches real issuer"), attempt.marker, markerOf(r, checkNotNull(request)))
            if (route in setOf("RENEWAL", "RECOVERY", "MANUAL")) assertNull(c(r, "$path does not consume entitlement permit"), attempt.consumePermit)
            else assertEquals(c(r, "$path success consumes its own p"), attempt.marker.permitSeq, attempt.consumePermit)
        }
        r.assertGeneral(1461.0)
    }

    /** Each payload read alternates actual PAC publications A/B. Revision/admission reads see the publication selected by that payload,
     * so they cannot become a second payload read or silently mix A's marker with B's epoch/use/p. */
    @Test fun r11vi_markerUseAndConsumption_shareOneRead() = a1aTest("R11-vi") { r ->
        r.approve(); r.start(); val a = r.snapshot
        r.falseThenTrue(); val b = r.snapshot
        assertTrue("premise: R11-vi actual PAC publications differ in epoch and permit", a.krxPermitSeq != b.krxPermitSeq && a.facts.recordFence?.krxCapabilityEpoch != b.facts.recordFence?.krxCapabilityEpoch)
        assertTrue("premise: R11-vi both captures have the standing GENERAL token", a.facts.token != null && a.facts.token == b.facts.token && a.userInvalidations == b.userInvalidations)
        var turn = 0; var current = a
        r.snapshotSupply = {
            val payload = Thread.currentThread().stackTrace.any {
                it.className == com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority::class.java.name && it.methodName in setOf("admitsWithKrx", "acquireWithKrx")
            }
            if (payload) { current = if (turn++ % 2 == 0) a else b }
            current
        }
        r.open()
        r.until(c(r, "actual FIRST reaches sent with one payload")) { r.commands.flatMap { it.sent }.isNotEmpty() }
        val first = r.commands.flatMap { it.sent }.last()
        assertAttemptRead(r, first)
        assertEquals(c(r, "actual successful FIRST consumes selected A"), first.marker.permitSeq, r.consumed)
        val sends = r.readLog.filter { it.method == "admitsWithKrx" }
        assertEquals(c(r, "one payload read for one successful attempt"), 1, sends.size)
        assertEquals(c(r, "selected A reached actual wire"), a.krxPermitSeq, first.marker.permitSeq)
        r.refreshResult = AuthSnapshot(checkNotNull(r.identity).uid, checkNotNull(r.identity).authGeneration, "fixture-replay")
        r.wire.deliver("""{"type":"subscription_error","request_id":"${first.requestId}","error":"invalid_token"}""")
        r.drain()
        r.until(c(r, "401 replay obtains a second actual attempt")) { r.commands.flatMap { it.sent }.size == 2 }
        val replay = r.commands.flatMap { it.sent }.last()
        assertAttemptRead(r, replay)
        assertEquals(c(r, "replay consumes selected B, not A"), b.krxPermitSeq, r.consumed)
        assertEquals(c(r, "one new payload read for replay"), 2, r.readLog.count { it.method == "admitsWithKrx" })
        assertEquals(c(r, "B epoch reaches replay use"), b.facts.recordFence?.krxCapabilityEpoch, replay.krxUse?.krxCapabilityEpoch)
        assertNotEquals(c(r, "fresh replay id"), first.requestId, replay.requestId)
    }

    @Test fun n1_hookPublication_repreparesPendingScope() = commandVariant("N1-01", hook = "started")
    @Test fun n1_beforeEncodeRevisionChange_repreparesBeforeWire() = commandVariant("N1-02", hook = "before-check")

    /** D-C: publication occurs inside fake transport.send after READY and before sent. No buffer/replay is introduced. */
    @Test fun n1_publicationAfterRevisionCheck_reconcilesWithoutLock() = variants("N1-03", "hidden-in-send", "new-p-in-send") { r, variant ->
        r.approve(); r.start()
        r.until(c(r, "initial KRX job enters token wait before FIRST"), 2_100) { r.krxCalls.size == 1 }
        val call = r.krxCalls.single()
        val old = r.snapshot
        var injected = false
        var consumedInside: Long? = null
        r.wireWindow = { send ->
            if (!injected && KRX in send.request.topics && r.readyInWire) {
                injected = true
                consumedInside = r.consumed
                runBlocking {
                    r.h.source.next = { EntitlementsOutcome.StableActive(false) }
                    r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
                    if (variant == "new-p-in-send") {
                        r.h.source.next = { EntitlementsOutcome.StableActive(true) }
                        r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
                    }
                }
            }
        }
        r.open()
        assertTrue(c(r, "publication reached READY -> physical send -> sent window"), injected)
        val attempt = r.commands.flatMap { it.sent }.single()
        assertEquals(c(r, "physical attempt carries original p"), old.krxPermitSeq, attempt.marker.permitSeq)
        assertEquals(c(r, "physical attempt carries original epoch"), old.facts.recordFence?.krxCapabilityEpoch, attempt.krxUse?.krxCapabilityEpoch)
        assertEquals(c(r, "exactly A went on wire"), listOf(attempt.requestId), r.krxSends.map { it.request.requestId })
        assertEquals(c(r, "send window had not consumed new p"), old.krxPermitSeq, consumedInside)
        if (variant == "hidden-in-send") {
            assertTrue("premise: N1-03 still hidden before late frame/ACK", !r.snapshot.facts.capabilityAllowed)
            assertEquals(c(r, "next turn refunds hidden p"), old.krxPermitSeq - 1, r.consumed)
            assertTrue(c(r, "existing KRX token job is actually cancelled"), call.cancelled)
            r.krxTick(1555.0) // reaches receive before any ACK promotion as well as while E is hidden
            r.ack(r.krxSends.single().request); r.krxTick(1556.0)
            assertTrue("premise: N1-03 still hidden for negative adoption assertions", !r.snapshot.facts.capabilityAllowed)
            assertFalse(c(r, "hidden/pre-ACK captures cannot adopt"), r.rates.quotes.values.any { it.rate == 1555.0 || it.rate == 1556.0 })
            r.assertGeneral()
        } else {
            // The completed callback consumes A only. A later session reconciliation may refund A's hidden round trip,
            // but it must never mark the newer p consumed before a second successful physical attempt.
            assertTrue(c(r, "new p is still owed while old ACK pending"), r.consumed < r.snapshot.krxPermitSeq)
            assertEquals(c(r, "at most one pending KRX send before ACK"), 1, r.krxSends.size)
            r.ack(r.krxSends.single().request); expectNewSend(r, 1)
            assertEquals(c(r, "follow-up consumes current p"), r.snapshot.krxPermitSeq, r.consumed)
            assertEquals(c(r, "original sent callback used A's p"), old.krxPermitSeq, attempt.consumePermit)
        }
    }

    @Test fun n1_reprepare_dropsOnlyItsOwnTicket() = variants("N1-04", "replacement-ticket", "stale-ack", "budget-after-reprepare") { r, label ->
        commandScenario(r, if (label == "replacement-ticket") "ticket" else if (label == "budget-after-reprepare") "budget" else "mixed")
    }
    @Test fun n1_emptyFinalScope_sendsNothing() = commandVariant("N1-05", hook = "empty")
    @Test fun n1_mixedScope_preservesGeneralAckScope() = commandVariant("N1-06", hook = "mixed")

    @Test fun n1_sendFalse_discardsCaptureWithoutConsumption() = a1aTest("N1-07") { r ->
        r.approve(); r.failNextWire = 1; r.start(); r.open()
        val failed = r.sends.last().request
        assertTrue("premise: N1-07 failed socket send reached fake", r.sends.single().accepted.not())
        r.wire.deliver(r.ackText(failed)); r.krxTick(); r.drain()
        assertTrue("premise: N1-07 before retry cooldown ends", r.clock.nowMillis() - r.sends.single().at < 5_000)
        assertEquals(c(r, "false send cannot consume"), 0L, r.consumed)
        assertFalse(c(r, "false capture never promotes"), 1555.0 in r.rates.quotes.values.map { it.rate })
        r.until(c(r, "next successful send"), 6_000) { r.krxSends.size == 1 }
        r.ack(); r.krxTick(1666.0)
        assertTrue(c(r, "new successful capture applies"), 1666.0 in r.rates.quotes.values.map { it.rate })
    }

    @Test fun n1_ws401_replayReadsANewAttempt() = a1aTest("N1-08") { r ->
        opened(r, ack = false)
        val old = r.first(); val reads = r.reads.size
        r.refreshResult = AuthSnapshot(checkNotNull(r.identity).uid, 1L, "fixture-replay")
        r.afterRefresh = { runBlocking { r.falseThenTrue() } }
        r.wire.deliver("""{"type":"subscription_error","request_id":"${old.requestId}","error":"invalid_token"}""")
        r.drain()
        r.until(c(r, "401 replay sends new attempt")) { r.sends.size >= 2 }
        assertNotEquals(c(r, "new replay request id"), old.requestId, r.sends.last().request.requestId)
        assertTrue(c(r, "new payload read on replay"), r.reads.size > reads)
        assertEquals(c(r, "replay consumes its new p"), r.snapshot.krxPermitSeq, r.consumed)
        assertEquals(c(r, "replay new marker"), KrxSendMarker(r.snapshot.facts.recordFence?.krxCapabilityEpoch, r.snapshot.krxPermitSeq), markerOf(r, r.sends.last().request))
    }

    @Test fun n2_pacPermitNeverDecreasesAcrossContextChanges() = a1aTest("N2-01") { r ->
        r.approve()
        val seen = mutableListOf<TopicAccessSnapshot>(r.snapshot)
        r.beta!!.check.observe { seen += it }
        val first = r.snapshot.krxPermitSeq
        r.h.source.next = { EntitlementsOutcome.StableActive(false) }; r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals(c(r, "false answer retains p"), first, r.snapshot.krxPermitSeq)
        r.h.store.record = AccessEpochTransitions.rotate(r.h.store.record, true, false, EpochIdGenerator { "n2-external-user" })
        r.h.issueGrant()
        r.h.source.next = { EntitlementsOutcome.Pending(false, 600) }; r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        assertEquals(c(r, "KeyChanged without opening retains p"), first, r.snapshot.krxPermitSeq)
        // Pending's stated 600s floor blocks every caller, including FP. Move to that real absolute floor first.
        val floor = r.h.recheckSchedule().readPrivate("earliestAllowedAtMillis") as Long
        val calls = r.h.source.calls.size
        r.h.source.next = { EntitlementsOutcome.StableActive(true) }
        r.pac.refresh(RefreshIntent.FORCE_PREMIUM)
        assertEquals("premise: N2-01 direct FP is actually held by the pending floor", calls, r.h.source.calls.size)
        assertEquals(c(r, "floor-held caller retains p"), first, r.snapshot.krxPermitSeq)
        r.test.testScheduler.advanceTimeBy(floor - r.clock.nowMillis())
        r.pac.refresh(RefreshIntent.FORCE_PREMIUM); r.drain()
        val opened = r.snapshot.krxPermitSeq
        r.events += "N2-01:permit-publications=${seen.map { it.krxPermitSeq to it.facts.capabilityAllowed }}"
        assertTrue("premise: N2-01 FP actually opens premium and capability: ${r.events.last()}", r.snapshot.facts.userAllowed && r.snapshot.facts.capabilityAllowed)
        assertEquals(c(r, "opening increases by exactly one: ${r.events.last()}"), first + 1, opened)
        r.h.store.rotationFailures = 1
        r.h.source.next = { EntitlementsOutcome.StableInactive(false) }; r.pac.refresh(RefreshIntent.FORCE_PREMIUM)
        assertEquals(c(r, "generation-only loss does not reset p"), opened, r.snapshot.krxPermitSeq)
        r.h.store.rotationFailures = 0
        r.authFenceChanged(AuthIdentityFence("user-b", 2L))
        r.pac.onIdentityChanged(checkNotNull(r.identity)).applied()
        r.h.source.next = { EntitlementsOutcome.StableActive(true) }; r.pac.refresh(RefreshIntent.FORCE_PREMIUM)
        r.drain()
        assertTrue(c(r, "all actual PAC publications are monotonic"), seen.zipWithNext().all { (a, b) -> b.krxPermitSeq >= a.krxPermitSeq })
        assertTrue(c(r, "each observed permit increment is +1"), seen.zipWithNext().all { (a, b) -> b.krxPermitSeq == a.krxPermitSeq || b.krxPermitSeq == a.krxPermitSeq + 1 })
        val max = KrxContestState.INITIAL.copy(permitSeq = Long.MAX_VALUE)
        assertEquals(c(r, "MAX refuses overflow"), KrxContestStep.Ignored(KrxContestIgnore.INVALID_INPUT), KrxContestPolicy.next(max, KrxContestEvent.KeyChanged(true), 0))
        val preserved = KrxContestPolicy.next(max, KrxContestEvent.KeyChanged(false), 0)
        assertTrue(c(r, "MAX closed key preserves or ignores same state"), preserved is KrxContestStep.Ignored || (preserved as KrxContestStep.Applied).state.permitSeq == Long.MAX_VALUE)
        assertEquals(c(r, "initial boundary p=0"), 0L, KrxContestState.INITIAL.permitSeq)
        assertFalse(c(r, "session does not inspect contest keys"), source("TopicSessionCoordinator.kt").contains("krxContestView"))
    }

    /** Real PAC route: an unreadable-identity premium refusal while OPEN is reapproved without a KRX contradiction or new p. */
    @Test fun n2_grantReplacement_keepsSessionConsumption() = a1aTest("N2-02") { r ->
        opened(r)
        val before = r.snapshot; val p = r.consumed
        val grant = r.h.issueGrant()
        r.h.source.identityNulls = 1
        r.pac.onTopicRejected(grant, listOf(com.jay.fxi.domain.model.TopicRejectionReason.PREMIUM_REQUIRED))
        assertEquals("premise: N2-02 premium refusal fault was consumed", 0, r.h.source.identityNulls)
        r.until("premise: N2-02 real PAC reapproves to a new token", 10_000) { r.snapshot.facts.token != grant && r.snapshot.facts.token != null }
        val replacement = checkNotNull(r.issuer.topicGrantResult().fence)
        assertNotEquals("premise: N2-02 actual reapproval replaced token", grant, replacement.grant)
        assertEquals("premise: N2-02 same binding", before.facts.binding, r.snapshot.facts.binding)
        assertEquals("premise: N2-02 same record context", before.facts.recordFence, r.snapshot.facts.recordFence)
        assertEquals("premise: N2-02 same decision generation", before.facts.decisionGeneration, r.snapshot.facts.decisionGeneration)
        assertEquals(c(r, "real grant-only reapproval preserves p"), p, r.snapshot.krxPermitSeq)
        r.until("premise: N2-02 replacement grant reconnects", 2_600) { r.wires.size == 2 }; r.open()
        assertEquals(c(r, "grant replacement keeps consumed p"), p, r.consumed)
        assertTrue(c(r, "replacement FIRST includes consumed OPEN p"), KRX in r.sends.last().request.topics)
        r.ack(); repeatRevision(r)
        assertEquals(c(r, "no extra independent permit send"), 2, r.krxSends.size)
    }

    @Test fun n2_oldAckAndContextChange_bothOrders() = variants("N2-03", "identity-before", "identity-after", "user-before", "user-after", "generation-before", "generation-after") { r, variant ->
        opened(r, ack = false)
        val oldWire = r.wire; val old = r.first(); val before = r.snapshot
        if (variant.endsWith("after")) r.ack(old)
        when {
            variant.startsWith("identity") -> {
                r.authFenceChanged(AuthIdentityFence("user-b", 2L))
                r.pac.onIdentityChanged(checkNotNull(r.identity)).applied()
                r.h.source.next = { EntitlementsOutcome.StableActive(true) }; r.pac.refresh(RefreshIntent.FORCE_PREMIUM)
            }
            variant.startsWith("user") -> {
                r.h.store.record = AccessEpochTransitions.rotate(r.h.store.record, true, false, EpochIdGenerator { "n2-new-user" })
                r.h.issueGrant(); r.pac.refresh(RefreshIntent.FORCE_PREMIUM)
            }
            else -> {
                r.h.source.next = { EntitlementsOutcome.StableInactive(false) }; r.pac.refresh(RefreshIntent.FORCE_PREMIUM)
                r.h.source.next = { EntitlementsOutcome.StableActive(true) }; r.pac.refresh(RefreshIntent.FORCE_PREMIUM)
            }
        }
        r.drain()
        assertTrue("premise: N2-03 context changed", before.facts.binding != r.snapshot.facts.binding || before.facts.recordFence?.userAccessEpoch != r.snapshot.facts.recordFence?.userAccessEpoch || before.facts.decisionGeneration != r.snapshot.facts.decisionGeneration)
        val consumed = r.consumed
        if (variant.endsWith("before")) { oldWire.deliver(r.ackText(old)); r.drain() }
        assertEquals(c(r, "$variant old ACK cannot consume latest p"), consumed, r.consumed)
        oldWire.deliver(KrxSessionIntegrationRig.krxFrame(1777.0)); r.drain()
        assertFalse(c(r, "$variant old capture cannot apply"), 1777.0 in r.rates.quotes.values.map { it.rate })
        assertFalse(c(r, "$variant stale KRX not confirmed"), r.store.snapshot.stateFor(KRX).confirmed)
        r.until(c(r, "$variant new context creates a real replacement connection"), 2_600) { r.wires.size >= 2 }
        r.open()
        val k2 = r.sends.last().request
        r.ack(k2); r.krxTick(1888.0)
        assertTrue(c(r, "$variant new K2 capture has positive adoption"), 1888.0 in r.rates.quotes.values.map { it.rate })
        val consumedK2 = r.consumed
        oldWire.deliver(r.ackText(old)); oldWire.deliver(KrxSessionIntegrationRig.krxFrame(1999.0)); r.drain()
        assertTrue("premise: N2-03 K2 still OPEN on its new context", r.snapshot.facts.capabilityAllowed)
        assertEquals(c(r, "$variant old ACK does not change K2 consumption"), consumedK2, r.consumed)
        assertFalse(c(r, "$variant stale generation cannot overwrite K2"), 1999.0 in r.rates.quotes.values.map { it.rate })
        r.assertGeneral(1449.0)

    }

    private suspend fun nonContestAnswer(r: KrxSessionIntegrationRig, label: String) {
        opened(r, ack = false)
        val view = r.pac.krxContestView
        val fetches = r.h.source.calls.size
        if (label == "ws-topic_unavailable") {
            val attempt = r.commands.flatMap { it.sent }.single()
            r.wire.deliver(r.ackFor(r.first(), mapOf(KRX to "topic_unavailable"))); r.drain()
            val reserved = r.issuer.reserved.single()
            assertEquals(c(r, "WS unavailable carries the actual attempt marker"), attempt.marker, reserved.marker)
            assertEquals(c(r, "WS unavailable retains its non-entitlement reason"),
                listOf(com.jay.fxi.domain.model.TopicRejectionReason.TOPIC_UNAVAILABLE), reserved.reasons)
            assertFalse(c(r, "PAC ends non-entitlement reservation"),
                reserved.reservation.order in r.pac.rejectionView(reserved.grant).pendingOrders)
        } else {
            r.ack()
            val topic = if (label == "rest-nonkrx-unsupported") USD else KRX
            r.until(c(r, "actual $topic logical issue reaches held body"), 2_100) { r.calls.any { it.topic == topic } }
            val call = r.calls.single { it.topic == topic }
            val outcome = when (label) {
                "rest-topics_disabled" -> TopicSnapshotOutcome.Dormant
                "rest-topic_unavailable" -> TopicSnapshotOutcome.Degraded
                "rest-nonkrx-unsupported" -> TopicSnapshotOutcome.Unsupported(emptyList())
                "rest-503" -> TopicSnapshotOutcome.TemporarilyUnavailable
                "rest-403" -> TopicSnapshotOutcome.Refused(AuthenticatedHttpFailure(
                    403, okhttp3.Headers.headersOf(), byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP))
                else -> error(c(r, "unknown CAP-R04 input $label"))
            }
            r.complete(call, outcome)
            if (label == "rest-503") {
                // The ON ladder owns retries; exhaust its three existing policy attempts before its final callback.
                for (count in 2..3) {
                    r.until(c(r, "503 retry $count reaches the same topic transport"), 2_000) { r.krxCalls.size == count }
                    r.complete(r.krxCalls.last(), outcome)
                }
            }
            assertTrue(c(r, "actual answer reaches the undelivered callback"), (topic to outcome) in r.undelivered)
            assertEquals(c(r, "REST non-entitlement answers reserve nothing"), 0, r.issuer.reserved.size)
        }
        assertTrue("premise: ${r.row} capability still OPEN for non-entitlement contrast", r.snapshot.facts.capabilityAllowed)
        assertEquals(c(r, "non-entitlement answer adds no FE"), fetches, r.h.source.calls.size)
        assertEquals(c(r, "non-entitlement answer changes no contest state or rejected p"), view, r.pac.krxContestView)
        r.assertGeneral()
    }

    private suspend fun overlappingRefusals(r: KrxSessionIntegrationRig, wsFirst: Boolean, byH1Prime: Boolean) {
        r.approve(); r.start()
        r.until(c(r, "same-permit REST issue is in flight"), 2_100) { r.krxCalls.size == 1 }
        r.open()
        assertEquals(c(r, "actual FIRST consumes the same issue permit"), r.snapshot.krxPermitSeq, r.consumed)
        val marker = KrxSendMarker(r.snapshot.facts.recordFence?.krxCapabilityEpoch, r.snapshot.krxPermitSeq)
        val grant = r.h.issueGrant()
        val fetches = r.h.source.calls.size
        val gate = CompletableDeferred<EntitlementsOutcome>()
        r.h.source.gates += gate
        if (byH1Prime) r.h.source.identityNulls = 1
        r.processRefusals = false
        val ws = { r.ack(r.first(), reject = true) }
        val rest = { r.complete(r.krxCalls.single(), TopicSnapshotOutcome.Unsupported(emptyList())) }
        if (wsFirst) { ws(); rest() } else { rest(); ws() }
        assertEquals(c(r, "both actual sources reached the marker channel"), 2, r.delayedRefusals.size)
        r.flushRefusals()
        r.until(c(r, "one joined reapproval FE"), 6_000) { r.h.source.calls.size == fetches + 1 }
        r.assertWindow(KrxContestPhase.AWAITING)
        val reports = r.issuer.reserved.takeLast(2)
        assertEquals(c(r, "first report is the contest barrier"), reports.first().reservation.order, r.pac.krxContestView?.state?.barrier)
        assertEquals(c(r, "first report is the only current handover"), reports.first().reservation.order, r.pac.rejectionView(grant).latestCurrent)
        assertFalse(c(r, "duplicate report is ended"), reports.last().reservation.order in r.pac.rejectionView(grant).pendingOrders)
        assertEquals(c(r, "both sources kept the same issued marker"), listOf(marker, marker), r.issuer.reserved.takeLast(2).map { it.marker })
        if (byH1Prime) {
            assertEquals("premise: ${r.row} H1prime consumed the live read failure", 0, r.h.source.identityNulls)
            assertEquals("premise: ${r.row} H1prime did not rotate capability", marker.krxCapabilityEpoch, r.h.store.record.krxCapabilityEpoch)
        } else assertNotEquals("premise: ${r.row} H1 rotated capability", marker.krxCapabilityEpoch, r.h.store.record.krxCapabilityEpoch)
        gate.complete(EntitlementsOutcome.StableActive(true)); r.drain()
        assertEquals(c(r, "two source refusals cause only one contest step"), 1, r.pac.krxContestView?.state?.streak)
        r.until(c(r, "new permit has one follow-up physical send")) { r.krxSends.size == 2 }
        assertTrue("premise: ${r.row} new permit remains OPEN", r.snapshot.facts.capabilityAllowed)
        assertEquals(c(r, "overlapping refusals do not add a second FE"), fetches + 1, r.h.source.calls.size)
        r.assertGeneral()
    }

    private fun assertAttemptRead(r: KrxSessionIntegrationRig, attempt: KrxCommandAttempt) {
        val record = r.commands.single { it.id == attempt.commandId }
        val scope = record.events.filter { it.name == "prepare" }.mapNotNull { it.value as? KrxAttemptScope }.single { it.requestId == attempt.requestId }
        val read = scope.read
        assertNotNull(c(r, "typed read carries session stamp"), read.session)
        assertEquals(c(r, "attempt carries the exact read marker"), read.marker, attempt.marker)
        assertEquals(c(r, "attempt carries the exact read use"), read.krxUse, attempt.krxUse)
        assertEquals(c(r, "attempt carries the exact read stamp"), read.session, attempt.stamp)
        assertEquals(c(r, "marker and stamp p agree"), attempt.stamp.permitSeq, attempt.marker.permitSeq)
        if (attempt.consumePermit != null) assertEquals(c(r, "consumption and marker p agree"), attempt.marker.permitSeq, attempt.consumePermit)
    }

    private suspend fun authRecoveryPermit(r: KrxSessionIntegrationRig, staleKrxRejection: Boolean, recoveryBeforeDone: Boolean) {
        opened(r, ack = false)
        // A GENERAL-only manual request supplies the authentication ending, independently of KRX pending.
        r.processRefusals = false
        val rejected = if (staleKrxRejection) mapOf(KRX to "krx_entitlement_required") else emptyMap()
        r.wire.deliver(r.ackFor(r.first(), rejected)); r.drain(); r.generalTick()
        r.rejectForManual(); r.manual()
        val failed = r.sends.last().request
        assertTrue("premise: ${r.row} manual GENERAL request reached wire", USD in failed.topics && KRX !in failed.topics)
        val failedOwner = r.commands.last()
        val gate = r.holdCommandDone()
        try {
            r.wire.deliver("""{"type":"subscription_error","request_id":"${failed.requestId}","error":"invalid_token"}""")
            r.drain()
            assertTrue("premise: ${r.row} actual auth ending posted CommandDone", gate.intercepted > 0)
            val before = r.krxSends.size
            r.falseThenTrue()
            if (staleKrxRejection) assertEquals("premise: ${r.row} KRX rejection still recorded while new p is owed",
                com.jay.fxi.domain.model.TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED, r.store.snapshot.stateFor(KRX).rejection)
            if (!recoveryBeforeDone) gate.release()
            r.recover()
            if (recoveryBeforeDone) {
                assertTrue("premise: ${r.row} recovery arrived while actual command still registered", r.commandJob(failedOwner) != null)
                gate.release()
            }
            r.until(c(r, "GENERAL recovery request reaches wire")) { r.sends.last().request.requestId != failed.requestId }
            val recovered = r.sends.last().request
            assertTrue(c(r, "recovery preserves GENERAL obligations"), USD in recovered.topics)
            r.ack(recovered)
            r.until(c(r, "current permit sends after authentication/lane ends")) { r.krxSends.size == before + 1 }
            assertEquals(c(r, "current permit consumed once"), r.snapshot.krxPermitSeq, r.consumed)
            r.ack(); repeatRevision(r)
            assertEquals(c(r, "no duplicate current-p send"), before + 1, r.krxSends.size)
            r.assertGeneral(1447.0)
        } finally { gate.close() }
    }

    private fun source(name: String) = java.io.File("src/main/java/com/jay/fxi/data/remote/$name").readText()
    /** D-B: only a real FIRST RunningCommand is decorated. No synthetic command id, command, or store is introduced. */
    private fun commandVariant(row: String, hook: String) = a1aTest(row,
        desired = if (hook == "empty") setOf(KRX) else TopicCatalogue.DESIRED + KRX
    ) { r -> commandScenario(r, hook) }

    private suspend fun commandScenario(r: KrxSessionIntegrationRig, hook: String) {
        r.approve(); r.httpFloor = r.clock.nowMillis() + 100_000; r.start()
        var firstAttempt: KrxCommandAttempt? = null
        var original: KrxSessionIntegrationRig.CommandRecord? = null
        var injected = false
        var replacement: com.jay.fxi.domain.model.TopicRequestTicket? = null
        var replacementSnapshot: com.jay.fxi.domain.model.TopicSubscriptionSnapshot? = null
        fun withdraw() {
            if (injected) return
            injected = true
            runBlocking {
                r.h.source.next = { EntitlementsOutcome.StableActive(false) }
                r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
            }
        }
        r.onPrepared = { record, attempt ->
            if (firstAttempt == null) { firstAttempt = attempt; original = record }
        }
        if (hook == "started") r.onStarted = { record -> if (record === original) withdraw() }
        else r.onBeforeCheck = { record, _ ->
            if (record === original && !injected) {
                if (hook == "ticket") {
                    replacement = r.store.beginRequest()
                    replacementSnapshot = r.store.snapshot
                }
                withdraw()
            }
        }
        if (hook == "ticket") r.onDiscarded = { record, _ -> r.commandJob(record)?.cancel() }
        r.open()
        r.until(c(r, "real RunningCommand reaches typed prepare"), 250) { firstAttempt != null }
        r.until(c(r, "publication reached the intended typed boundary"), 250) { injected }
        val old = checkNotNull(firstAttempt)
        val owner = checkNotNull(original)
        assertTrue(c(r, "decorated attempt belongs to registered real command"), old.commandId == owner.id)
        r.until(c(r, "obsolete prepared attempt is discarded"), 250) { owner.discarded.any { it.requestId == old.requestId } }
        assertFalse(c(r, "obsolete typed attempt is never encoded"), r.requests.any { it.requestId == old.requestId })
        assertFalse(c(r, "obsolete typed attempt never reaches socket"), r.sends.any { it.request.requestId == old.requestId })
        if (hook == "ticket") {
            r.drain()
            assertEquals(c(r, "old task leaves replacement ticket owned"), replacement, KrxSessionIntegrationRig.privateValue(r.store, "currentTicket"))
            assertEquals(c(r, "old cleanup leaves replacement request PENDING"), replacementSnapshot?.controlState, r.store.snapshot.controlState)
            checkNotNull(replacement).let(r.store::releaseRequest)
            assertNull(c(r, "replacement's own release still succeeds"), KrxSessionIntegrationRig.privateValue(r.store, "currentTicket"))
        } else if (hook == "empty") {
            assertTrue("premise: N1-05 still hidden inside empty-scope window", !r.snapshot.facts.capabilityAllowed)
            assertTrue(c(r, "no final request or send for empty KRX scope"), owner.sent.isEmpty() && r.sends.isEmpty())
            assertEquals(c(r, "empty scope consumes nothing"), 0L, r.consumed)
            assertEquals(c(r, "empty scope issues no KRX HTTP"), 0, r.krxCalls.size)
        } else {
            r.until(c(r, "GENERAL re-prepared without spending a retry cooldown"), 250) { r.sends.any { USD in it.request.topics && KRX !in it.request.topics } }
            val replacementSend = r.sends.last()
            val replacementAttempt = r.commands.flatMap { it.sent }.single { it.requestId == replacementSend.request.requestId }
            assertEquals(c(r, "final wire equals pending ACK scope"), replacementSend.request.topics.toSet(), replacementAttempt.pendingAckTopics)
            assertNotEquals(c(r, "reprepare uses a fresh request id"), old.requestId, replacementAttempt.requestId)
            if (hook == "budget") {
                repeat(3) { index ->
                    val request = r.sends.last().request
                    r.wire.deliver("""{"type":"subscription_error","request_id":"${request.requestId}","error":"temporarily_unavailable","retry_after_seconds":1}""")
                    r.drain()
                    if (index < 2) r.until(c(r, "retry ${index + 2} retains the unspent command budget"), 2_000) { r.sends.size == index + 2 }
                }
                r.advance(1_100)
                assertTrue("premise: N1-04 budget check still in hidden scope", !r.snapshot.facts.capabilityAllowed)
                assertEquals(c(r, "three physical attempts remain after unencoded REPREPARE"), 3, r.sends.size)
                return
            }
            r.wire.deliver(r.ackText(r.requests.last().copy(requestId = old.requestId, topics = old.topics.toList())))
            r.drain()
            assertTrue("premise: N1 old ACK still within original ACK deadline", r.clock.nowMillis() - replacementSend.at < TopicRequestPolicy.ACK_TIMEOUT.inWholeMilliseconds)
            assertFalse(c(r, "stale ACK cannot confirm KRX or replacement"), r.store.snapshot.stateFor(KRX).confirmed || r.store.snapshot.stateFor(USD).confirmed)
            r.ack(replacementSend.request)
            r.wire.deliver(TopicSessionKrxHarness.usdFrame(1337.0)); r.drain()
            assertTrue(c(r, "GENERAL replacement ACK and data are adopted"), 1337.0 in r.rates.quotes.values.map { it.rate })
            assertEquals(c(r, "no failed send or retry budget was used for REPREPARE"), 1, r.sends.size)
        }
    }

    private fun variants(row: String, vararg labels: String, body: suspend TestScope.(KrxSessionIntegrationRig, String) -> Unit) = runTest {
        for (label in labels) {
            val r = KrxSessionIntegrationRig(this, "$row/$label")
            var failure: Throwable? = null
            try { body(r, label) } catch (t: Throwable) { failure = t; throw t } finally {
                try { r.beta!!.check.assertHeld() } catch (t: Throwable) {
                    if (failure != null) failure.addSuppressed(t) else throw t
                } finally { r.close() }
            }
        }
    }
    /** α-3 additive row; original locked r2 methods are unchanged. */
    @Test fun a3_w14a_startedSamePermitLadder_isNotRestarted() = KrxA3Rows.w14a_startedSamePermitLadder_isNotRestarted()

    /** α-3 additive row; original locked r2 methods are unchanged. */
    @Test fun a3_r11iv_explicitBeforePermit_doesNotSpendAutomaticOrigin() = KrxA3Rows.r11iv_explicitBeforePermit_doesNotSpendAutomaticOrigin()

    /** α-3 additive row; original locked r2 methods are unchanged. */
    @Test fun a3_w15_firstPositivePermit_isDiscoveredWithoutAnotherStimulus() = KrxA3Rows.w15_firstPositivePermit_isDiscoveredWithoutAnotherStimulus()

    /** α-4 independent column; existing locked r2/a3 methods are unchanged. */
    @Test fun a4_n202_failedReplacementFirst_preservesConsumption() = KrxA4Rows.n202_failedReplacementFirst_preservesConsumption()

    /** α-4 independent column; existing locked r2/a3 methods are unchanged. */
    @Test fun a4_n203_oldGenerationQueuedBeforeTransition_isRejected() = KrxA4Rows.oldGenerationInputs("N2-03", listOf("identity", "USER", "generation"))

}
