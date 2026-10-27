package com.jay.fxi.data.remote

import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.KrxSessionStamp
import com.jay.fxi.data.entitlements.KrxSendMarker
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.acquireKrxUse
import com.jay.fxi.data.entitlements.admitsKrxUse
import com.jay.fxi.data.entitlements.applied
import com.jay.fxi.data.entitlements.checkedOnTest
import com.jay.fxi.data.entitlements.grantedKrxVisible
import com.jay.fxi.data.entitlements.rejectKrx
import com.jay.fxi.data.entitlements.settleKrx
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.KRX
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.TETHER
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.USD
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*

/** α-3 additive rows. Locked r2 methods stay unchanged. S:219/323/325/826 is the oracle.
 * Boundary snapshots are named explicitly; publication rows otherwise run the real PAC and TGD.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object KrxA3Rows {
    private val desired = setOf(TETHER, USD, KRX)
    private val dormant = KrxA1bFixture.Reply(404, """{"error":"topics_disabled"}""")

    private fun fixture(row: String, on: Boolean = true, frozen: Boolean = false,
        body: suspend TestScope.(KrxA1bFixture) -> Unit) = runTest {
        val r = KrxSessionIntegrationRig(this, row, on = on, frozenHead = frozen, desired = desired)
        val f = KrxA1bFixture(r)
        var failure: Throwable? = null
        try { body(f) } catch (t: Throwable) { failure = t; throw t } finally {
            try {
                if (on) checkNotNull(r.beta) { "premise: $row real checked ON fixture exists" }.check.assertHeld()
            } catch (t: Throwable) {
                if (failure != null) failure.addSuppressed(t) else throw t
            } finally { r.close() }
        }
    }

    /** Run all independent columns, retaining their row labels even on a stub contract failure. */
    private fun columns(labels: List<String>, body: (String) -> Unit) {
        val failures = mutableListOf<Throwable>()
        for (label in labels) try { body(label) } catch (t: Throwable) {
            failures += AssertionError("$label: ${t.message}", t)
        }
        if (failures.isNotEmpty()) throw AssertionError(failures.joinToString(" || ") { it.message.orEmpty() }).apply {
            failures.forEach(::addSuppressed)
        }
    }

    private data class StageWindow(val stage: String, val call: KrxA1bFixture.Call, val job: Job,
        val sleep: KrxSessionIntegrationRig.SleepProbe?)

    private fun KrxA1bFixture.stageWindow(stage: String): StageWindow {
        val call = issue()
        val job = checkNotNull(call.job) { "premise: ${r.row} actual ladder owns the bootstrap call" }
        val sleep = if (stage == "delay") {
            reply(call, KrxA1bFixture.unavailable)
            r.sleeps.singleOrNull { it.job === job && it.duration.inWholeMilliseconds == 400L && !it.returned && !it.cancelled }
                ?: throw AssertionError(c("real KRX 400ms retry delay is entered"))
        } else null
        if (stage == "token" || stage == "body") {
            assertEquals("premise: ${r.row} actual service is in $stage", if (stage == "token") KrxA1bFixture.Stage.TOKEN else KrxA1bFixture.Stage.BODY, call.stage)
        }
        assertTrue("premise: ${r.row} original job is active inside the named window", job.isActive)
        assertTrue("premise: ${r.row} cancellation window is before the 10s attempt deadline", r.clock.nowMillis() - call.at < 10_000)
        return StageWindow(stage, call, job, sleep)
    }

    private fun KrxA1bFixture.assertActualCancellation(window: StageWindow) {
        assertTrue(c("original ${window.stage} job is actually cancelled now"), window.job.isCancelled)
        if (window.stage == "delay") {
            val sleep = checkNotNull(window.sleep) { "premise: ${r.row} actual delay probe was captured" }
            assertTrue(c("the actual retry sleep caught cancellation"), sleep.cancelled)
            assertFalse(c("cancelled retry sleep never returned normally"), sleep.returned)
        } else {
            assertTrue(c("actual ${window.stage} wait caught cancellation"), window.call.cancelled)
            assertNotNull(c("actual cancellation has an exception witness"), window.call.cancellation)
            assertFalse(c("a service timeout is not counted as cancellation"), window.call.cancellation is TimeoutCancellationException)
        }
    }

    /** boundary-input: same real grant/epoch/p; only revision, capability block and capInv change. */
    private fun KrxA1bFixture.samePermitRoundTrip(conflated: Boolean): TopicAccessSnapshot {
        val open = r.snapshotSupply()
        val hidden = open.copy(revision = open.revision + 1, capabilityInvalidations = open.capabilityInvalidations + 1,
            facts = open.facts.copy(capabilityBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE)))
        val restored = open.copy(revision = hidden.revision + 1, capabilityInvalidations = hidden.capabilityInvalidations)
        r.snapshotSupply = { hidden }; r.revise()
        if (!conflated) r.drain()
        r.snapshotSupply = { restored }; r.revise(); r.drain()
        assertEquals("premise: ${r.row} boundary-input preserves p", open.krxPermitSeq, restored.krxPermitSeq)
        assertEquals("premise: ${r.row} boundary-input preserves epoch", open.facts.recordFence?.krxCapabilityEpoch, restored.facts.recordFence?.krxCapabilityEpoch)
        assertTrue("premise: ${r.row} restored boundary input is effective", restored.facts.capabilityAllowed)
        return restored
    }

    /** R1 / W14a: ladderStarted(p) survives cancellation and completion, including a conflated hide. */
    fun w14a_startedSamePermitLadder_isNotRestarted() = columns(
        listOf("delay", "token", "body", "completed").flatMap { stage ->
            listOf("observed", "conflated").map { "R1/W14a/boundary-input:$stage/$it" }
        } + "R1/W14a/real-PAC-new-p-same-epoch"
    ) { row -> fixture(row) { f -> with(f) {
        if (row.endsWith("real-PAC-new-p-same-epoch")) {
            newPermitPositive()
            return@with
        }
        val stage = row.substringAfter(':').substringBefore('/')
        holdTokens = stage == "token"
        live(); val first = issue()
        val window = if (stage == "completed") {
            reply(first, dormant)
            assertEquals("premise: $row first automatic result was produced", TopicSnapshotOutcome.Dormant, first.outcome)
            assertTrue("premise: $row first automatic ladder completed", first.job?.isCompleted == true)
            null
        } else stageWindow(stage)
        val originalP = first.read.krxPermitSeq
        assertTrue("premise: $row first automatic ladder has a positive permit", originalP > 0)
        assertEquals(c("exactly one automatic attempt exists before the round trip"), 1, krx.size)
        val beforeSends = r.krxSends.size
        val general = calls.single { it.topic == TETHER }
        assertTrue("premise: $row GENERAL job is live before the capability-only round trip", general.job?.isActive == true)
        samePermitRoundTrip(row.endsWith("conflated"))
        if (window != null) assertActualCancellation(window)
        r.until(c("same p has one real WS resend")) { r.krxSends.size == beforeSends + 1 }
        assertEquals(c("same-p resend consumes exactly the original p"), originalP, r.consumed)
        r.ack()
        assertFalse(c("GENERAL job was not cancelled with KRX"), general.cancelled)
        r.assertGeneral()
        val oldPhysical = first.sends.size
        first.token.complete(Unit); reply(first, delivered(1777.0))
        r.advance(2_100)
        assertFalse(c("cancelled/completed old completion cannot apply"), has(1777.0))
        assertEquals(c("old logical call cannot perform another physical HTTP"), oldPhysical, first.sends.size)
        assertEquals(c("started p never gets a second automatic ladder"), 1, krx.size)
        repeat(3) { r.revise(); r.drain() }
        assertEquals(c("revision repeats add neither a WS resend nor another ladder"), beforeSends + 1, r.krxSends.size)
        assertEquals(c("automatic ladder remains spent for this p"), 1, krx.size)

        r.assertGeneral()
    } } }

    /** Independent real PAC control: no synthetic capInv/revision is reset back to a lower real value. */
    private suspend fun KrxA1bFixture.newPermitPositive() {
        live(); val first = issue(); reply(first, dormant)
        assertTrue("premise: ${r.row} p1 initial ladder really completed", first.job?.isCompleted == true)
        r.assertGeneral()
        val p1 = first.read.krxPermitSeq
        val epoch = r.snapshot.facts.recordFence?.krxCapabilityEpoch
        val sends = r.krxSends.size
        val grant = checkNotNull(r.issuer.topicGrantResult().fence) { "premise: ${r.row} standing grant captured before identity fault" }.grant
        r.h.source.identityNulls = 1
        r.h.rejectKrx(grant); r.drain()
        assertEquals("premise: ${r.row} real H1prime consumed the intended identity failure", 0, r.h.source.identityNulls)
        r.until("premise: ${r.row} real same-epoch reapproval opens a new p", 6_000) {
            r.snapshot.krxPermitSeq > p1 && r.snapshot.facts.capabilityAllowed
        }
        assertEquals("premise: ${r.row} real H1prime keeps the same capability epoch", epoch, r.snapshot.facts.recordFence?.krxCapabilityEpoch)
        r.until(c("new p has its own physical permit subscribe")) { r.krxSends.size == sends + 1 }
        r.ack()
        r.until(c("new p starts one automatic ladder despite the same epoch"), 2_100) { krx.size == 2 }
        val current = krx.last()
        assertEquals(c("new ladder carries the new p"), r.snapshot.krxPermitSeq, current.read.krxPermitSeq)
        assertEquals(c("new ladder stays in the original epoch"), epoch, current.read.facts.recordFence?.krxCapabilityEpoch)
        reply(current, dormant); r.advance(1_500)
        repeat(3) { r.revise(); r.drain() }
        assertEquals(c("new p gets exactly one automatic ladder"), 2, krx.size)
        assertEquals(c("new p gets exactly one permit WS"), sends + 1, r.krxSends.size)
        r.assertGeneral()
    }

    /** R2 / R11-iv: explicit origin cannot settle the independent pending PERMIT automatic origin. */
    fun r11iv_explicitBeforePermit_doesNotSpendAutomaticOrigin() = columns(
        listOf("R2/R11-iv/explicit-terminal-before-ACK", "R2/R11-iv/explicit-inflight-at-ACK")
    ) { row -> fixture(row) { f -> with(f) {
        live(); val initial = issue(); reply(initial, dormant)
        assertTrue("premise: $row initial automatic ladder really completed", initial.job?.isCompleted == true)
        r.assertGeneral()
        r.rejectForManual(); r.manual()
        val lane = r.sends.last().request
        assertTrue("premise: $row GENERAL manual command actually owns the lane", USD in lane.topics && KRX !in lane.topics)
        assertTrue("premise: $row registered GENERAL command still owns its pending ACK", r.commandJob(r.commands.last())?.isActive == true)
        val laneAck = r.acknowledgements.size
        val oldSends = r.krxSends.size
        r.falseThenTrue()
        val p2 = r.snapshot.krxPermitSeq
        assertTrue("premise: $row new p2 is effective while GENERAL ACK is owed", p2 > initial.read.krxPermitSeq && r.snapshot.facts.capabilityAllowed)
        assertEquals(c("permit send waits for the GENERAL lane"), oldSends, r.krxSends.size)
        assertEquals(c("no automatic p2 ladder before its successful subscribe"), 1, krx.size)
        val consumedBeforeExplicit = r.consumed
        r.request(KRX)
        r.until(c("explicit KRX request reaches its own real logical issue"), 2_100) { krx.size == 2 }
        val explicit = krx.last()
        assertEquals(c("explicit request reads p2"), p2, explicit.read.krxPermitSeq)
        assertEquals(c("explicit issue does not spend permit consumption"), consumedBeforeExplicit, r.consumed)
        assertEquals(c("explicit has not released the GENERAL ACK obligation"), laneAck, r.acknowledgements.size)
        if (row.endsWith("terminal-before-ACK")) reply(explicit, dormant)
        else assertTrue("premise: $row explicit HTTP is actually in flight", explicit.job?.isActive == true && explicit.stage == KrxA1bFixture.Stage.BODY)
        r.ack(lane)
        assertEquals(c("GENERAL ACK is applied exactly once"), laneAck + 1, r.acknowledgements.size)
        r.until(c("pending p2 PERMIT really reaches wire")) { r.krxSends.size == oldSends + 1 }
        val permit = r.commands.flatMap { it.sent }.lastOrNull { it.source == KrxAttemptSource.PERMIT && it.stamp.permitSeq == p2 }
            ?: throw AssertionError(c("registered PERMIT source carries the current p2"))
        assertEquals(c("successful PERMIT consumes p2"), p2, r.consumed)
        assertEquals(c("the typed PERMIT is the physical request"), permit.requestId, r.krxSends.last().request.requestId)
        if (row.endsWith("inflight-at-ACK")) {
            assertEquals(c("automatic obligation waits behind same-topic explicit in-flight"), 2, krx.size)
            reply(explicit, dormant)
        }
        r.until(c("successful PERMIT has one independent automatic ladder after explicit settles"), 2_100) { krx.size == 3 }
        val automatic = krx.last()
        assertNotSame(c("automatic origin is not the explicit logical call"), explicit, automatic)
        assertEquals(c("automatic origin carries p2"), p2, automatic.read.krxPermitSeq)
        assertTrue(c("automatic issue starts only after p2 physically succeeded"), automatic.at >= r.krxSends.last().at)
        r.ack(); reply(automatic, dormant)
        repeat(4) { r.revise(); r.drain() }; r.advance(1_500)
        assertEquals(c("one initial, one explicit, one PERMIT automatic origin; no fourth"), 3, krx.size)
        assertEquals(c("no second current-p PERMIT physical send"), oldSends + 1, r.krxSends.size)
        assertTrue(c("GENERAL USD confirmation survives both origins"), r.store.snapshot.stateFor(USD).confirmed)
        r.assertGeneral()
    } } }

    /** R3: real PAC publication occurs inside supplier A's return; only TGD accessRevised is held.
     * boundary-input columns preserve A's original use while exposing the new published revision/p.
     * They do not claim that PAC itself publishes an unchanged-use p increment.
     */
    fun r3_restPreparation_rechecksPublicationBeforeTgdNotification() = columns(listOf(
        "R3/PAC-E-false", "R3/boundary-input:revision-only", "R3/boundary-input:revision-new-p"
    )) { row -> fixture(row) { f -> with(f) {
        holdTokens = true
        r.approve(); r.start() // No socket open: initial REST obligation still owns unconsumed p.
        assertEquals("premise: $row no WS has consumed the initial permit", 0L, r.consumed)
        assertTrue("premise: $row KRX waits behind GENERAL pacing", krx.isEmpty())
        val fence = checkNotNull(r.issuer.topicGrantResult().fence) { "premise: $row real GENERAL grant exists" }
        var stale: TopicAccessSnapshot? = null
        var replacement: TopicAccessSnapshot? = null
        var consumedAtFreshRead: Long? = null
        var fired = false
        val queries = r.h.source.calls.size
        r.holdRevisionNotifications = true
        val forwarded = r.forwardedRevisionNotifications
        r.afterPayloadRead = { read ->
            if (read.method == "acquireWithKrx") {
                if (!fired) {
                    fired = true; stale = read.snapshot
                    runBlocking {
                        r.h.source.next = { EntitlementsOutcome.StableActive(false) }
                        r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
                        if (row.contains("boundary-input:")) {
                            r.h.source.next = { EntitlementsOutcome.StableActive(true) }
                            r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
                        }
                    }
                    val published = r.snapshot
                    assertTrue("premise: $row real PAC publication advances revision during A's return", published.revision > read.snapshot.revision)
                    val next = if (!row.contains("boundary-input:")) published else read.snapshot.copy(
                        revision = published.revision,
                        krxPermitSeq = if (row.endsWith("revision-new-p")) published.krxPermitSeq else read.snapshot.krxPermitSeq
                    )
                    if (row.contains("boundary-input:")) {
                        val originalUse = checkNotNull(read.snapshot.acquireKrxUse(fence)) { "premise: $row A has an original KRX use" }
                        assertTrue("premise: $row boundary B still admits A's original use", next.admitsKrxUse(originalUse))
                        if (row.endsWith("revision-new-p")) assertTrue("premise: $row boundary B has a distinct marker p", next.krxPermitSeq > read.snapshot.krxPermitSeq)
                    } else assertFalse("premise: $row real published B withholds E", next.facts.capabilityAllowed)
                    replacement = next
                    r.snapshotSupply = { next }
                } else if (consumedAtFreshRead == null && read.snapshot.revision == replacement?.revision) consumedAtFreshRead = r.consumed
            }
        }
        r.until(c("real issue preparation returned A and then compared metadata against published B"), 2_100) {
            fired && r.readLog.any { it.kind == KrxSessionIntegrationRig.ReadKind.METADATA && it.snapshot.revision == replacement?.revision }
        }
        val a = checkNotNull(stale) { "premise: $row actual A payload was captured" }
        var b = checkNotNull(replacement) { "premise: $row published/reported B was captured" }
        assertTrue("premise: $row real PAC fetch ran during A's return", r.h.source.calls.size > queries)
        assertTrue("premise: $row actual TGD notification is held", r.heldRevisionNotifications > 0)
        assertEquals("premise: $row no new accessRevised reached TSC", forwarded, r.forwardedRevisionNotifications)
        assertTrue(c("stale A never becomes a logical issue"), krx.none { it.read === a || it.read.revision == a.revision })
        if (row.endsWith("E-false")) {
            assertEquals(c("blocked B consumes zero"), 0L, r.consumed)
            assertTrue(c("blocked B has no logical KRX issue or HTTP"), krx.isEmpty())
            r.afterPayloadRead = null; r.snapshotSupply = { r.snapshot }
            r.h.source.next = { EntitlementsOutcome.StableActive(true) }
            r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
            b = r.snapshot
            assertTrue("premise: $row restoration is an effective new PAC permit", b.facts.capabilityAllowed && b.krxPermitSeq > a.krxPermitSeq)
            r.releaseRevisionNotifications()
            r.until(c("initial obligation survived blocked preparation without opening WS"), 2_100) { krx.size == 1 }
        } else {
            r.until(c("fresh B is adopted even before TGD notification release"), 2_100) { krx.size == 1 }
            assertEquals(c("A consumed nothing before fresh B preparation"), 0L, consumedAtFreshRead)
            assertEquals("premise: $row still no TGD notification reached TSC", forwarded, r.forwardedRevisionNotifications)
        }
        r.afterPayloadRead = null
        val current = krx.single()
        assertEquals(c("the only logical issue uses B's revision"), b.revision, current.read.revision)
        assertEquals(c("the only logical issue consumes B's p"), b.krxPermitSeq, r.consumed)
        assertEquals(c("fresh payload reached the real token gate"), KrxA1bFixture.Stage.TOKEN, current.stage)
        assertEquals(c("token gate has not performed HTTP"), 0, current.sends.size)
        val metadata = r.readLog.filter { it.kind == KrxSessionIntegrationRig.ReadKind.METADATA }
        val payload = r.readLog.filter { it.method == "acquireWithKrx" && it.kind == KrxSessionIntegrationRig.ReadKind.PAYLOAD }
        assertTrue(c("preparation has distinct A and fresh payload reads"), payload.any { it.snapshot === a } && payload.any { it.snapshot.revision == b.revision })
        assertTrue(c("metadata is observed separately from those payload reads"), metadata.isNotEmpty() && metadata.none { it.method == "acquireWithKrx" })
        current.token.complete(Unit); r.drain()
        assertEquals("premise: $row the fresh issue makes exactly one admitted HTTP", 1, current.sends.size)
        r.h.source.gates += CompletableDeferred<EntitlementsOutcome>()
        reply(current, KrxA1bFixture.unsupported)
        assertEquals(c("control handover carries the fresh issue's epoch/p marker"),
            KrxSendMarker(b.facts.recordFence?.krxCapabilityEpoch, b.krxPermitSeq), r.issuer.reserved.single().marker)
        assertEquals(c("no stale logical issue was resurrected"), 1, krx.size)
    } } }

    private suspend fun TestScope.preparePermitZero(f: KrxA1bFixture) = with(f) {
        r.h.source.next = { EntitlementsOutcome.StableActive(false) }
        r.pac.onIdentityChanged(checkNotNull(r.identity) { "premise: ${r.row} initial identity exists" }).applied()
        r.pac.refresh(RefreshIntent.FORCE_PREMIUM); settleKrx()
        r.h.store.record = r.h.store.record.copy(mayContainPremiumData = true)
        assertEquals("premise: ${r.row} initial p is zero", 0L, r.snapshot.krxPermitSeq)
        assertTrue("premise: ${r.row} GENERAL is allowed at p0", r.snapshot.facts.userAllowed)
        assertFalse("premise: ${r.row} capability starts hidden", r.snapshot.facts.capabilityAllowed)
        r.start(); r.open(); r.ack()
        assertTrue("premise: ${r.row} GENERAL token stands after grant delivery", r.snapshot.facts.tokenStanding)
        settleGeneral(); val legacy = issue(); reply(legacy, dormant)
        assertTrue("premise: ${r.row} legacy plan actually completed", legacy.job?.isCompleted == true)
        r.assertGeneral()
    }

    /** R4: first p>0 discovered from the ordinary revision pull, with no new lifecycle/manual/renewal input. */
    fun w15_firstPositivePermit_isDiscoveredWithoutAnotherStimulus() = fixture("R4/W15/ON-p0-to-p1") { f -> with(f) {
        preparePermitZero(f)
        val token = r.snapshot.facts.token
        val wire = r.wire
        val connection = checkNotNull(field("connection")) { "premise: ${r.row} p0 connection is live" }
        val sends = r.krxSends.size; val callsBefore = krx.size
        assertEquals(c("p0 consumed no typed permit"), 0L, r.consumed)
        r.h.source.next = { EntitlementsOutcome.StableActive(true) }
        r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS); r.drain()
        assertEquals("premise: ${r.row} real PAC published its first p1", 1L, r.snapshot.krxPermitSeq)
        assertEquals("premise: ${r.row} capability-only first permit keeps GENERAL grant", token, r.snapshot.facts.token)
        assertSame(c("first permit keeps the live socket"), wire, r.wire)
        assertSame(c("first permit keeps the current connection"), connection, field("connection"))
        r.until(c("first p1 triggers one actual PERMIT subscribe")) { r.krxSends.size == sends + 1 }
        val permit = r.commands.flatMap { it.sent }.singleOrNull { it.source == KrxAttemptSource.PERMIT && it.stamp.permitSeq == 1L }
            ?: throw AssertionError(c("registered current-p PERMIT attempt was actually sent"))
        assertEquals(c("first successful permit consumes p1"), 1L, r.consumed)
        r.until(c("first p1 owns one automatic obligation before its ACK"), 2_100) { krx.size == callsBefore + 1 }
        val automatic = krx.last()
        assertEquals(c("new automatic obligation uses p1"), 1L, automatic.read.krxPermitSeq)
        assertEquals(c("no new connection is needed to discover p1"), 1, r.wires.size)
        assertEquals(c("physical send is the registered PERMIT"), permit.requestId, r.krxSends.last().request.requestId)
        reply(automatic, dormant); r.ack()
        r.advance(1_500)
        assertEquals(c("first p1 produces no duplicate WS"), sends + 1, r.krxSends.size)
        assertEquals(c("first p1 produces no duplicate automatic origin"), callsBefore + 1, krx.size)
        r.assertGeneral()
    } }

    /** OFF contrast: same real false→true input; no extra B payload/metadata in the revision turn, plus frozen HEAD comparison. */
    fun w15_offRevision_addsNoPayloadOrMetadata() {
        var plain: List<String>? = null
        for (frozen in listOf(true, false)) fixture("R4/W15/OFF/${if (frozen) "HEAD" else "candidate"}", on = false, frozen = frozen) { f -> with(f) {
            preparePermitZero(f)
            val offset = r.readLog.size; val at = r.clock.nowMillis(); val sends = r.krxSends.size
            r.h.source.next = { EntitlementsOutcome.StableActive(true) }
            r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS); r.drain()
            assertEquals("premise: ${r.row} OFF publication remains p0", 0L, r.snapshot.krxPermitSeq)
            assertEquals("premise: ${r.row} exact revision turn has not advanced to a new initial-plan pacing timer", at, r.clock.nowMillis())
            val delta = r.readLog.drop(offset)
            assertEquals(c("OFF adds zero metadata reads"), 0, delta.count { it.kind == KrxSessionIntegrationRig.ReadKind.METADATA })
            assertEquals(c("OFF adds zero KRX payload reads in the revision turn"), 0, delta.count { it.method in setOf("acquireWithKrx", "admitsWithKrx", "acquireKrx", "admitsKrx") })
            assertEquals(c("OFF creates no permit-triggered physical subscribe"), sends, r.krxSends.size)
            val trace = delta.map { it.method }
            if (frozen) plain = trace else assertEquals(c("OFF keeps the frozen GENERAL read sequence"), checkNotNull(plain) { "premise: ${r.row} frozen contrast completed" }, trace)
        } }
    }

    /** R5: actual cancellation is required immediately after AccessRevised while WS reconnect is still in backoff. */
    fun b07b10_disconnectedCapabilityLoss_cancelsActualKrxStage() = columns(
        listOf("false", "epoch").flatMap { loss -> listOf("delay", "token", "body").map { "R5/B07-B10/$loss/$it" } }
    ) { row -> fixture(row) { f -> with(f) {
        val stage = row.substringAfterLast('/'); val epochRoundTrip = row.contains("/epoch/")
        holdTokens = stage == "token"; live(); val window = stageWindow(stage)
        val first = window.call; val oldP = first.read.krxPermitSeq; val oldEpoch = first.read.facts.recordFence?.krxCapabilityEpoch
        assertTrue("premise: $row original KRX issue has a positive p", oldP > 0)
        assertNotNull("premise: $row original KRX issue has a capability epoch", oldEpoch)
        assertTrue("premise: $row original KRX issue began with E allowed", first.read.facts.capabilityAllowed)
        val general = calls.single { it.topic == TETHER }
        assertTrue("premise: $row GENERAL job really waits on HTTP body", general.job?.isActive == true && general.stage == KrxA1bFixture.Stage.BODY)
        val usd = calls.single { it.topic == USD }
        reply(usd, KrxA1bFixture.Reply(200, TopicSessionKrxHarness.usdFrame(1337.0)))
        assertTrue("premise: $row GENERAL REST is positive before disconnection", has(1337.0))
        val floor = r.clock.nowMillis() + 700L; r.httpFloor = floor; r.request(USD)
        val wireCount = r.wires.size; val droppedAt = r.clock.nowMillis()
        r.wire.drop(); r.drain()
        assertNull("premise: $row WS ended before capability transition", field("connection"))
        assertEquals("premise: $row reconnect has not opened another socket", wireCount, r.wires.size)
        val reconnect = r.sleeps.lastOrNull { it.at == droppedAt && it.duration.inWholeMilliseconds == 1_600L && !it.cancelled && !it.returned }
            ?: throw AssertionError("premise: $row real reconnect backoff is active")
        assertTrue(c("WS drop alone preserves the original active REST job until capability loss"), window.job.isActive)
        assertTrue("premise: $row floor and pacing probes fit before reconnect", floor + 500 < reconnect.at + reconnect.duration.inWholeMilliseconds)
        val physical = first.sends.size; val krxBefore = krx.size
        r.h.source.next = { EntitlementsOutcome.StableActive(false) }
        r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS); r.drain()
        assertNull("premise: $row still has no connection at revision processing", field("connection"))
        assertActualCancellation(window) // Before any clock advance: a later timeout is not evidence.
        assertTrue("premise: $row actual loss changed the capability epoch", oldEpoch != r.snapshot.facts.recordFence?.krxCapabilityEpoch)
        assertFalse(c("GENERAL job survives disconnected capability cancellation"), general.cancelled)
        assertTrue(c("GENERAL original use remains admitted"), general.admitted())
        assertEquals(c("capability cancellation does not reset the shared HTTP floor"), floor, r.httpFloor)
        if (epochRoundTrip) {
            r.h.source.next = { EntitlementsOutcome.StableActive(true) }
            r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS); r.drain()
            assertTrue("premise: $row actual epoch round trip issued a new p", r.snapshot.krxPermitSeq > oldP)
        }
        first.token.complete(Unit); reply(first, delivered(1777.0))
        val allBeforeFloor = calls.size
        r.advance(floor - r.clock.nowMillis() - 1)
        assertEquals(c("GENERAL request cannot bypass the preserved floor"), allBeforeFloor, calls.size)
        r.advance(1)
        val nextUsd = calls.last()
        assertEquals(c("GENERAL request resumes at the original floor"), floor, nextUsd.at)
        assertEquals(c("floor-delayed request is GENERAL USD"), USD, nextUsd.topic)
        reply(nextUsd, dormant); reply(general, dormant); r.request(TETHER)
        val paced = calls.size; r.advance(499)
        assertEquals(c("GENERAL 500ms pacing is preserved"), paced, calls.size)
        r.advance(1)
        assertEquals(c("next GENERAL issue keeps floor plus 500ms"), floor + 500, calls.last().at)
        assertEquals(c("paced next issue is GENERAL tether"), TETHER, calls.last().topic)
        assertEquals(c("all observations are still inside reconnect backoff"), wireCount, r.wires.size)
        assertFalse(c("retired late KRX completion cannot apply"), has(1777.0))
        assertEquals(c("cancelled original call adds no physical HTTP"), physical, first.sends.size)
        assertTrue(c("no successor revives the cancelled old p/epoch ladder"), krx.drop(krxBefore).none {
            it.job === window.job || it.read.krxPermitSeq == oldP && it.read.facts.recordFence?.krxCapabilityEpoch == oldEpoch
        })
    } } }

    /** Source guard for direct construction, typed receivers, seam-return locals and their simple aliases.
     * This is a source regression guard, not Kotlin type resolution; hidden reflection/collection aliases need diff review.
     */
    private fun payloadCopyHits(raw: String): List<String> {
        val text = KrxSessionSourceContract.strip(raw)
        if (listOf("KrxSendAdmission", "KrxSendAcquisition", "admitsWithKrx", "acquireWithKrx").none { it in text }) return emptyList()
        val types = "(?:KrxSendAdmission|KrxSendAcquisition)"
        val receivers = Regex("""\b(\w+)\s*:\s*$types\b""").findAll(text).map { it.groupValues[1] }.toMutableSet()
        val assignments = Regex("""\b(?:val|var)\s+(\w+)(?:\s*:[^=\n]+)?\s*=\s*([^\n]+)""").findAll(text).toList()
        val call = Regex("""\b(?:KrxSendAdmission|KrxSendAcquisition|admitsWithKrx|acquireWithKrx)\s*\(""")
        val firstToken = Regex("""^\s*([\w.]+)""")
        while (true) {
            var changed = false
            for (assignment in assignments) {
                val rhs = assignment.groupValues[2]
                val first = firstToken.find(rhs)?.groupValues?.get(1)
                if (call.containsMatchIn(rhs) || first != null && first in receivers) {
                    if (receivers.add(assignment.groupValues[1])) changed = true
                }
            }
            if (!changed) break
        }
        val hits = mutableListOf<String>()
        for (copy in Regex("""\b([A-Za-z_]\w*(?:\??\.[A-Za-z_]\w*)*)\s*\??\.\s*copy\s*\(""").findAll(text)) {
            if (copy.groupValues[1].split(Regex("""\??\.""")).any { it in receivers })
                hits += "receiver:${copy.groupValues[1]}:${text.take(copy.range.first).count { it == '\n' } + 1}"
        }
        // A direct constructor expression may not have a local name.
        for (ctor in Regex("""\b$types\s*\(""").findAll(text)) {
            var depth = 1; var end = ctor.range.last + 1
            while (end < text.length && depth > 0) {
                if (text[end] == '(') depth++
                if (text[end] == ')') depth--
                end++
            }
            if (depth == 0 && Regex("""^\s*\.\s*copy\s*\(""").containsMatchIn(text.substring(end)))
                hits += "constructor:${text.take(ctor.range.first).count { it == '\n' } + 1}"
        }
        return hits
    }

    /** SUA alternative stamp representation is safe only while production never copies a payload. */
    fun sua_sessionStampIsFromThePayloadSnapshot_andCopiesAreAbsent() = fixture("SUA-STAMP") { f -> with(f) {
        val root = File("src/main/java")
        val sources = root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
        val sua = "com/jay/fxi/data/entitlements/SnapshotTopicUseAuthority.kt"
        assertTrue("premise: ${r.row} source guard scans real production sources", sources.size > 100 && sua in sources)
        val hits = sources.flatMap { (path, text) -> payloadCopyHits(text).map { "$path:$it" } }
        val baselineHits = payloadCopyHits(sources.getValue(sua)).size
        for (type in listOf("KrxSendAdmission", "KrxSendAcquisition")) {
            val injected = sources.getValue(sua) + "\nprivate fun a3BadCopy(value: $type) = value.copy()\n"
            assertEquals("premise: ${r.row} injected $type copy adds one hit in the same scan", baselineHits + 1, payloadCopyHits(injected).size)
            val alias = sources.getValue(sua) + "\nprivate fun a3Alias(value: $type) { val later = value\n later.copy() }\n"
            assertEquals("premise: ${r.row} simple alias of $type is still caught", baselineHits + 1, payloadCopyHits(alias).size)
        }
        assertEquals("premise: ${r.row} unrelated copy is not a payload copy", emptyList<String>(), payloadCopyHits("fun unrelated(value: TopicRates) = value.copy()"))
        assertEquals(c("production admission/acquisition copy calls are absent"), emptyList<String>(), hits)

        r.approve()
        val fence = checkNotNull(r.issuer.topicGrantResult().fence) { "premise: ${r.row} original standing grant exists" }
        val real = r.snapshot
        val alternate = real.copy(revision = real.revision + 101, krxPermitSeq = real.krxPermitSeq + 4,
            capabilityInvalidations = real.capabilityInvalidations + 7,
            facts = real.facts.copy(recordFence = real.facts.recordFence?.copy(krxCapabilityEpoch = "a3-source-epoch")))
        var supplied = real
        val returns = mutableListOf<TopicAccessSnapshot>()
        val reader = SnapshotTopicUseAuthority { supplied.also { returns += it } }
        for ((column, snapshot) in listOf("real" to real, "boundary-input:alternate" to alternate)) {
            supplied = snapshot
            val expectedUse = snapshot.acquireKrxUse(fence)
            assertNotNull("premise: ${r.row}/$column selected source can acquire KRX use", expectedUse)
            val offset = returns.size
            val acquisition = reader.acquireWithKrx(fence)
            assertEquals("premise: ${r.row}/$column acquisition reads the supplier once", offset + 1, returns.size)
            assertSame("premise: ${r.row}/$column acquisition sees the selected snapshot", snapshot, returns.last())
            assertEquals(c("$column acquisition carries its source's original KRX use"), expectedUse, acquisition.krxUse)
            val stamp = KrxSessionStamp(snapshot.revision, snapshot.krxPermitSeq, snapshot.capabilityInvalidations)
            assertEquals(c("$column acquisition stamp comes from its one snapshot"), stamp, acquisition.session)
            assertEquals(c("$column acquisition marker comes from that snapshot"), KrxSendMarker(snapshot.facts.recordFence?.krxCapabilityEpoch, snapshot.krxPermitSeq), acquisition.marker)
            assertEquals(c("$column acquisition use has the same capInv as stamp"), stamp.capabilityInvalidations, acquisition.krxUse?.capabilityInvalidations)
            val lifetime = checkNotNull(acquisition.lifetime) { "premise: ${r.row}/$column GENERAL lifetime exists" }
            val beforeSend = returns.size
            val admission = reader.admitsWithKrx(lifetime)
            assertEquals("premise: ${r.row}/$column admission reads the supplier once", beforeSend + 1, returns.size)
            assertSame("premise: ${r.row}/$column admission sees the selected snapshot", snapshot, returns.last())
            assertTrue("premise: ${r.row}/$column GENERAL send is admitted", admission.admitted)
            assertEquals(c("$column admission carries the same original KRX use"), expectedUse, admission.krxUse)
            assertEquals(c("$column admission stamp comes from its one snapshot"), stamp, admission.session)
            assertEquals(c("$column admission and acquisition carry the same source marker"), acquisition.marker, admission.marker)
            assertEquals(c("$column admission use has the same capInv as stamp"), stamp.capabilityInvalidations, admission.krxUse?.capabilityInvalidations)
        }
    } }

    /** METADATA is an orthogonal observation; the existing payload ISSUE/SEND/ACK paths keep their meaning. */
    fun metadataClassification_keepsLogicalPayloadAttribution() {
        val row = "META-CLASSIFICATION"
        var plainPaths: List<TopicSessionKrxHarness.ReadPath>? = null
        var plainMetadata: Int? = null
        for (extra in listOf(false, true)) checkedOnTest { h ->
            h.grantedKrxVisible()
            val s = TopicSessionKrxHarness(h)
            var injected: TopicSessionKrxHarness.ReaderRead? = null
            var once = false
            if (extra) s.readerSnapshot = {
                val frames = Thread.currentThread().stackTrace
                val issue = frames.any { it.className.startsWith(TopicSessionCoordinator::class.java.name) && it.methodName.startsWith("issueOwedBootstraps") }
                if (issue && !once) {
                    once = true
                    s.krxSendReader.currentRevision()
                    injected = s.readerReturns.last()
                }
                h.coordinator.accessSnapshot
            }
            s.goLive(); s.openWire()
            val sent = s.attempts.firstOrNull { it.reachedWire && it.carriesKrx }
                ?: throw AssertionError("premise: $row real KRX request reached the wire")
            assertTrue("premise: $row request was physically accepted", sent.wireAccepted)
            s.wire.deliver(s.ack(sent.requestId, listOf(TETHER, USD, KRX))); s.step(1)
            val call = s.bootstrapOf(KRX, row)
            assertEquals("premise: $row real issue owns one payload", 1, call.readerSnapshotsAtIssue.size)
            val paths = s.readerReturns.filter { it.kind == TopicSessionKrxHarness.ReadKind.PAYLOAD }.map { it.path }
            if (!extra) {
                plainPaths = paths; plainMetadata = s.metadataReturns.size
            } else {
                val read = checkNotNull(injected) { "premise: $row currentRevision was called inside the actual issue supplier path" }
                assertEquals("contract($row): nested currentRevision is METADATA", TopicSessionKrxHarness.ReadKind.METADATA, read.kind)
                assertEquals("contract($row): metadata never becomes an ISSUE payload", TopicSessionKrxHarness.ReadPath.UNATTRIBUTED, read.path)
                assertNull("contract($row): metadata belongs to no attempt", read.attempt)
                assertNull("contract($row): metadata is never claimed by a logical issue", read.claimedBy)
                assertEquals("contract($row): payload classifications equal the plain run", checkNotNull(plainPaths) { "premise: $row plain run completed" }, paths)
                assertEquals("contract($row): only the injected metadata read is added", checkNotNull(plainMetadata) { "premise: $row plain metadata count exists" } + 1, s.metadataReturns.size)
            }
        }
    }
}
