package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.AccessEpochTransitions
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.EpochIdGenerator
import com.jay.fxi.data.entitlements.KrxSendMarker
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.applied
import com.jay.fxi.data.entitlements.issueGrant
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.KRX
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.TETHER
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.USD
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.privateValue
import com.jay.fxi.domain.model.TopicRejectionReason
import kotlin.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*

/** α-4 additive columns. Existing r2/a3 rows and operational sources remain untouched. */
@OptIn(ExperimentalCoroutinesApi::class)
internal object KrxA4Rows {
    private val dormant = KrxA1bFixture.Reply(404, """{"error":"topics_disabled"}""")
    // An unclassified 503 must remain Refused(503), rather than the named TemporarilyUnavailable outcome.
    private val serverFailure = KrxA1bFixture.Reply(503, """{"error":"a4_unclassified_upstream_failure"}""")

    private fun columns(labels: List<String>, body: (String) -> Unit) {
        val failures = mutableListOf<Throwable>()
        for (label in labels) try { body(label) } catch (failure: Throwable) {
            failures += AssertionError("$label: ${failure.message}", failure)
        }
        if (failures.isNotEmpty()) throw AssertionError(failures.joinToString(" || ") { it.message.orEmpty() }).apply {
            failures.forEach(::addSuppressed)
        }
    }

    /** B01: both topics receive the identical actual, unclassified Refused(503). */
    fun b01_general503_isOneExchangeThroughKrxLadderEnd() = a1bTest(
        "B01/a4-unclassified-503", setOf(USD, KRX), gap = Duration.ZERO
    ) { f -> with(f) {
        live(); val first = issue()
        val general = calls.single { it.topic == USD }
        assertTrue("premise: ${r.row} both real HTTP bodies wait on the same input window",
            general.stage == KrxA1bFixture.Stage.BODY && first.stage == KrxA1bFixture.Stage.BODY)
        assertTrue("premise: ${r.row} GENERAL original use is admitted before its 503", general.admitted())
        reply(general, serverFailure)
        val refused = general.outcome as? TopicSnapshotOutcome.Refused
        assertEquals("premise: ${r.row} actual GENERAL service returned HTTP 503 to the session caller", 503, refused?.failure?.statusCode)
        assertEquals("premise: ${r.row} first GENERAL service invocation returned before counting successors", KrxA1bFixture.Stage.RETURNED, general.stage)
        reply(first, serverFailure)
        val second = next(1); reply(second, serverFailure)
        val third = next(2); reply(third, delivered(1555.0))
        assertTrue("premise: ${r.row} actual KRX ladder reached its third successful exchange", has(1555.0) && third.sends.size == 1)
        assertTrue("premise: ${r.row} original ladder is terminal before the negative window", first.job?.isCompleted == true)
        val terminalAt = r.clock.nowMillis(); r.advance(2_000)
        assertTrue("premise: ${r.row} observation is beyond the actual ladder terminal time", r.clock.nowMillis() > terminalAt)
        assertEquals(c("GENERAL 503 has exactly one physical exchange through and after the KRX ladder"), 1,
            calls.filter { it.topic == USD }.sumOf { it.sends.size })
        assertEquals(c("GENERAL 503 creates no second logical service invocation"), 1, calls.count { it.topic == USD })
        assertEquals(c("KRX still has exactly its three physical exchanges"), 3, krx.sumOf { it.sends.size })
        assertTrue(c("the actual GENERAL refusal is also handed to the session's result observer"),
            r.undelivered.any { (topic, outcome) -> topic == USD && outcome === general.outcome })
    } }

    /** Shared generation guard columns, independently entered by K01-OLDKEY and N2-03 methods. */
    fun oldGenerationInputs(row: String, contexts: List<String>) = columns(contexts.flatMap { context ->
        listOf("pending-old-capture", "accepted-old-capture").map { "$row/a4-queued/$context/$it" }
    }) { label -> a1bTest(label) { f -> with(f) {
        val context = label.substringAfter("a4-queued/").substringBefore('/')
        live(ack = label.endsWith("accepted-old-capture"))
        val oldWire = r.wire; val oldRequest = r.first(); val before = r.snapshot
        val oldConnection = checkNotNull(field("connection")) { "premise: $label original live connection exists" }
        val oldGeneration = privateValue(oldConnection, "generation") as Long
        if (label.endsWith("accepted-old-capture")) {
            r.krxTick(1777.0)
            assertTrue(c("old capture is usable before the queued-input race"), has(1777.0))
        }
        val ackText = r.ackText(oldRequest)
        val gate = KrxA4InputGate(r, "Transport")
        try {
            oldWire.deliver(ackText); oldWire.deliver(KrxA1bFixture.krxFrame(1999.0)); r.drain()
            assertEquals("premise: $label ACK and frame were really posted while the old collector was alive", 2, gate.captured.size)
            assertEquals("premise: $label original connection has not ended at production", false, privateValue(oldConnection, "ended"))
            assertTrue("premise: $label both original inputs retain the old generation", gate.captured.all { privateValue(it, "generation") == oldGeneration })
            val frames = gate.captured.map { (privateValue(it, "event") as? TopicTransportEvent.Received)?.frame }
            assertTrue("premise: $label held objects are a decoded KRX ACK and KRX frame",
                frames[0] is DecodedTopicFrame.Acknowledgement && frames[1] is DecodedTopicFrame.Krx)
            gate.passFutureInputs()
            changeContext(context)
            r.drain()
            val after = r.snapshot
            assertTrue("premise: $label the intended context really changed", when (context) {
                "identity" -> before.facts.binding != after.facts.binding
                "USER" -> before.facts.recordFence?.userAccessEpoch != after.facts.recordFence?.userAccessEpoch
                "generation" -> before.facts.decisionGeneration != after.facts.decisionGeneration
                else -> before.facts.token != after.facts.token
            })
            r.until("premise: $label replacement socket is established before old inputs are released", 2_600) { r.wires.size == 2 }
            r.open(); r.ack(); r.krxTick(1888.0); r.assertGeneral()
            assertTrue(c("replacement capture has its own positive current-generation frame"), has(1888.0))
            val connection = checkNotNull(field("connection")) { "premise: $label replacement connection exists" }
            val generation = privateValue(connection, "generation") as Long
            assertNotEquals("premise: $label replacement generation differs from the held source", oldGeneration, generation)
            assertEquals("premise: $label old connection ended before release", true, privateValue(oldConnection, "ended"))
            assertFalse("premise: $label held stale price has not entered the replacement capture", has(1999.0))
            val capture = krxQuotes(); val state = r.store.snapshot; val leases = generalLeases()
            val consumption = r.consumed; val ackCount = r.acknowledgements.size; val sends = r.krxSends.size
            val retired = retirements.requests.toList(); val displayEvidence = evidence()
            gate.release()
            // Same original FIFO: observing this later, current-generation decoder event proves
            // both released objects have passed through the session loop, even when it rejects them.
            gate.assertProcessedBarrier()
            assertEquals("premise: $label exactly the two held original inputs were released", 2, gate.released.size)
            assertTrue("premise: $label release preserved object identity and source generation", gate.released.zip(gate.captured).all { (released, captured) ->
                released === captured && privateValue(released, "generation") == oldGeneration
            })
            assertSame(c("old inputs cannot replace the current connection"), connection, field("connection"))
            assertEquals(c("queued old KRX frame cannot overwrite the replacement capture"), capture, krxQuotes())
            assertEquals(c("queued old ACK/frame leave the current store unchanged"), state, r.store.snapshot)
            assertEquals(c("queued old ACK/frame leave the current lease registry unchanged"), leases, generalLeases())
            assertEquals(c("queued old inputs consume no current permit"), consumption, r.consumed)
            assertEquals(c("queued old ACK is not accepted as a current command answer"), ackCount, r.acknowledgements.size)
            assertEquals(c("queued old inputs send no extra permit subscribe"), sends, r.krxSends.size)
            assertEquals(c("queued old inputs retire no replacement capture"), retired, retirements.requests.toList())
            assertEquals(c("queued old inputs preserve existing capture delivery evidence"), displayEvidence, evidence())
            r.krxTick(1889.0)
            assertTrue(c("the original replacement capture still accepts a fresh current-generation frame"), has(1889.0))
        } finally { gate.close() }
    } } }

    private fun KrxA1bFixture.krxQuotes() = r.rates.quotes.filterKeys { it.source == "krx" || it.asset == "usd-krw-futures" }

    private suspend fun KrxA1bFixture.changeContext(context: String) {
        when (context) {
            "identity" -> {
                r.authFenceChanged(AuthIdentityFence("a4-new-user", 2))
                r.pac.onIdentityChanged(checkNotNull(r.identity) { "premise: ${r.row} replacement identity is present" }).applied()
                r.h.source.next = { EntitlementsOutcome.StableActive(true) }; r.pac.refresh(RefreshIntent.FORCE_PREMIUM)
            }
            "USER" -> {
                r.h.store.record = AccessEpochTransitions.rotate(r.h.store.record, true, false, EpochIdGenerator { "a4-new-user-epoch" })
                r.h.issueGrant(); r.pac.refresh(RefreshIntent.FORCE_PREMIUM)
            }
            "generation" -> {
                loss("premium")
                r.h.source.next = { EntitlementsOutcome.StableActive(true) }; r.pac.refresh(RefreshIntent.FORCE_PREMIUM)
            }
            "grant" -> {
                val grant = r.h.issueGrant(); r.h.source.identityNulls = 1
                r.pac.onTopicRejected(grant, listOf(TopicRejectionReason.PREMIUM_REQUIRED))
                r.until("premise: ${r.row} actual PAC grant-only reapproval completes", 10_000) {
                    r.snapshot.facts.token != grant && r.snapshot.facts.token != null
                }
            }
            else -> error(c("unknown context $context"))
        }
    }

    /** N2-02: failed replacement FIRST cannot conceal a consumption reset by consuming p again.
     * boundary-input: a temporary shared floor isolates that observation from the new grant's
     * independently legal G1(i) initial REST plan. It is restored before testing that plan.
     */
    fun n202_failedReplacementFirst_preservesConsumption() = a1bTest("N2-02/a4-failed-FIRST/boundary-input:floor-isolation") { f -> with(f) {
        live(); val initial = issue(); reply(initial, dormant); r.assertGeneral()
        val before = r.snapshot; val p = before.krxPermitSeq
        assertEquals(c("original successful FIRST has consumed the real positive p"), p, r.consumed)
        assertTrue("premise: ${r.row} original automatic ladder is complete", p > 0 && initial.job?.isCompleted == true)
        val priorFloor = r.httpFloor; val isolationFloor = r.clock.nowMillis() + 20_000
        r.httpFloor = isolationFloor; r.failNextWire = 1
        val grant = r.h.issueGrant(); r.h.source.identityNulls = 1
        r.pac.onTopicRejected(grant, listOf(TopicRejectionReason.PREMIUM_REQUIRED))
        assertEquals("premise: ${r.row} real refusal consumed the identity fault", 0, r.h.source.identityNulls)
        r.until("premise: ${r.row} real grant-only reapproval replaces the token", 10_000) {
            r.snapshot.facts.token != grant && r.snapshot.facts.token != null
        }
        val after = r.snapshot
        assertEquals("premise: ${r.row} binding is unchanged", before.facts.binding, after.facts.binding)
        assertEquals("premise: ${r.row} USER/capability context is unchanged", before.facts.recordFence, after.facts.recordFence)
        assertEquals("premise: ${r.row} decision generation is unchanged", before.facts.decisionGeneration, after.facts.decisionGeneration)
        assertEquals("premise: ${r.row} actual grant-only replacement has the same p", p, after.krxPermitSeq)
        r.until("premise: ${r.row} replacement grant created its own socket", 2_600) { r.wires.size == 2 }
        val offset = r.sends.size; r.open()
        val failed = r.sends.drop(offset).single()
        assertFalse("premise: ${r.row} replacement FIRST really returned send=false", failed.accepted)
        assertTrue("premise: ${r.row} failed FIRST really carried KRX", KRX in failed.request.topics)
        assertTrue("premise: ${r.row} observation precedes isolated REST floor and FIRST retry", r.clock.nowMillis() < isolationFloor)
        assertEquals("premise: ${r.row} no replacement REST issue could reconsume p", 1, krx.size)
        assertEquals(c("failed FIRST leaves the already consumed p intact across grant replacement"), p, r.consumed)
        assertEquals(c("failed FIRST adds no successful same-p KRX subscribe"), 1, r.krxSends.size)
        repeat(3) { r.revise(); r.drain() }
        assertEquals(c("same-p revision cannot manufacture a PERMIT while FIRST is pending"), 0,
            r.commands.flatMap { it.sent }.count { it.source == KrxAttemptSource.PERMIT })
        r.httpFloor = priorFloor; r.revise(); r.drain()
        r.until(c("replacement FIRST's normal physical retry succeeds"), 6_000) { r.krxSends.size == 2 }
        r.ack(); r.assertGeneral()
        r.until(c("new grant retains its one independent legal initial REST plan"), 2_100) { krx.size == 2 }
        val newGrantInitial = krx.last()
        assertEquals(c("replacement initial plan is bound to the new grant"), after.facts.token, newGrantInitial.read.facts.token)
        reply(newGrantInitial, dormant); r.advance(1_500)
        assertEquals(c("same p adds no independent G1(ii) automatic ladder beyond the two grant plans"), 2, krx.size)
        assertEquals(c("same p adds no PERMIT subscribe after replacement FIRST settles"), 0,
            r.commands.flatMap { it.sent }.count { it.source == KrxAttemptSource.PERMIT })
        val sends = r.krxSends.size
        r.falseThenTrue()
        assertTrue("premise: ${r.row} positive control publishes a genuinely new p", r.snapshot.krxPermitSeq > p)
        r.until(c("new p has exactly one successful PERMIT subscribe")) { r.krxSends.size == sends + 1 }
        assertEquals(c("new p is consumed by its own successful send"), r.snapshot.krxPermitSeq, r.consumed)
        assertEquals(c("the added subscribe is actually PERMIT for the new p"), 1,
            r.commands.flatMap { it.sent }.count { it.source == KrxAttemptSource.PERMIT && it.stamp.permitSeq == r.snapshot.krxPermitSeq })
        r.ack(); r.until(c("new p starts exactly one independent automatic ladder"), 2_100) { krx.size == 3 }
        reply(krx.last(), dormant); r.advance(1_500)
        assertEquals(c("new-p positive control has no duplicate automatic ladder"), 3, krx.size)
        assertEquals(c("new-p positive control has no duplicate subscribe"), sends + 1, r.krxSends.size)
    } }

    /** B04: retry Issue → A/B metadata mismatch → yield → real GENERAL 429 raises floor.
     * boundary-input: B changes only revision, preserving both original uses and the permit marker.
     */
    fun b04_reprepareYield_rechecksRaisedGeneralFloor() = a1bTest(
        "B04/a4-Reprepare-yield/boundary-input:revision-only", setOf(TETHER, USD, KRX), gap = Duration.ZERO
    ) { f -> with(f) {
        live(); val first = issue()
        val general = calls.single { it.topic == TETHER }
        reply(calls.single { it.topic == USD }, dormant)
        val p = r.consumed; val ws = r.krxSends.size
        val failedAt = r.clock.nowMillis(); reply(first, serverFailure)
        val wait = r.sleeps.singleOrNull { it.job === first.job && it.duration.inWholeMilliseconds == 400L && !it.returned && !it.cancelled }
        assertNotNull(c("actual first retry delay is entered"), wait)
        val due = failedAt + 400; var stale: TopicAccessSnapshot? = null; var fresh: TopicAccessSnapshot? = null
        var metadataAt: Long? = null; var queued429 = false
        r.afterPayloadRead = { read ->
            if (read.method == "acquireWithKrx" && stale == null) {
                stale = read.snapshot
                fresh = read.snapshot.copy(revision = read.snapshot.revision + 1)
                r.snapshotSupply = { checkNotNull(fresh) { "premise: ${r.row} boundary B exists" } }
                r.afterMetadataRead = {
                    metadataAt = r.clock.nowMillis()
                    // Queue the actual GENERAL body continuation before the retry's ensuing yield.
                    // No runCurrent here: 429 must be processed in that suspension, not before Issue.
                    reply(general, KrxA1bFixture.Reply(429, """{"error":"a4_limited"}""", "3"), drain = false)
                    queued429 = true
                }
            }
        }
        r.advance(399)
        assertTrue("premise: ${r.row} before retry Issue, GENERAL still waits on its real HTTP body", general.stage == KrxA1bFixture.Stage.BODY)
        assertEquals("premise: ${r.row} no 429 has raised the floor before retry Issue", 0L, r.httpFloor)
        r.advance(1)
        val a = checkNotNull(stale) { c("retry actually prepares payload A after the first backoff") }
        val b = checkNotNull(fresh) { "premise: ${r.row} boundary B is derived from actual retry payload A" }
        assertEquals("premise: ${r.row} mismatch is created at the actual due retry, after Issue admission", due, metadataAt)
        assertTrue("premise: ${r.row} actual metadata returned B rather than A", r.readLog.any {
            it.kind == KrxSessionIntegrationRig.ReadKind.METADATA && it.snapshot === b && it.at == due
        } && a.revision != b.revision)
        assertTrue("premise: ${r.row} GENERAL 429 was queued from that metadata comparison", queued429)
        assertEquals("premise: ${r.row} the real GENERAL service returned 429", 429, (general.outcome as? TopicSnapshotOutcome.Refused)?.failure?.statusCode)
        assertTrue("premise: ${r.row} actual 429 evidence reached the floor owner", (429 to "3") in floorEvidence)
        assertTrue("premise: ${r.row} session processed the actual GENERAL 429 result", r.undelivered.any { (topic, result) -> topic == TETHER && result === general.outcome })
        val floor = due + 3_000
        assertEquals("premise: ${r.row} that real Retry-After raised the shared floor", floor, r.httpFloor)
        assertTrue("premise: ${r.row} both original uses stay admitted in the new floor window", first.admitted() && general.admitted())
        assertTrue("premise: ${r.row} current clock is before the newly raised floor", r.clock.nowMillis() < floor)
        assertEquals(c("Reprepare/yield must not issue a retry ahead of the new floor"), 1, krx.sumOf { it.sends.size })
        r.afterPayloadRead = null
        r.advance(floor - r.clock.nowMillis() - 1)
        assertTrue("premise: ${r.row} just-before-floor still has valid original use", first.admitted())
        assertEquals(c("zero KRX retry HTTP before the exact new floor"), 1, krx.sumOf { it.sends.size })
        r.advance(1); val second = next(1, 0)
        assertEquals(c("the same second attempt issues exactly at the new floor"), floor, second.at)
        assertEquals(c("the second attempt has one physical exchange"), listOf(floor), second.sends)
        assertSame(c("Reprepare preserves the original ladder Job"), first.job, second.job)
        assertSame(c("second attempt uses the fresh B payload"), b, second.read)
        assertEquals(c("repreparation did not consume another permit"), p, r.consumed)
        assertEquals(c("repreparation did not send another WS subscribe"), ws, r.krxSends.size)
        reply(second, serverFailure)
        r.advance(1_199)
        assertTrue("premise: ${r.row} admission remains open during the ordinary second backoff", second.admitted())
        assertEquals(c("Reprepare did not spend the third attempt or its backoff"), 2, krx.size)
        r.advance(1); val third = next(2, 0)
        assertEquals(c("third attempt retains its ordinary second backoff"), floor + 1_200, third.at)
        assertSame(c("the third attempt preserves the fresh B payload source too"), b, third.read)
        val gate = KrxA4InputGate(r, "BootstrapAnswered") { privateValue(it, "topic") == KRX }
        try {
            reply(third, delivered(1888.0))
            val answer = gate.captured.singleOrNull() ?: throw AssertionError(c("the terminal actual outcome was posted with its marker"))
            assertEquals(c("ladder outcome marker comes from fresh B"),
                KrxSendMarker(b.facts.recordFence?.krxCapabilityEpoch, b.krxPermitSeq), privateValue(answer, "marker"))
            gate.release(); r.drain()
        } finally { gate.close() }
        assertTrue(c("third legitimate attempt is applied"), has(1888.0))
        r.advance(2_000)
        assertEquals(c("no extra physical attempt was minted by Reprepare"), 3, krx.sumOf { it.sends.size })
        assertEquals(c("the entire ladder still consumed only its original p"), p, r.consumed)
    } }
}
