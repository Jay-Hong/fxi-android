package com.jay.fxi.data.remote

import com.jay.fxi.data.remote.KrxA1bFixture.Companion.unavailable
import com.jay.fxi.data.remote.KrxA1bFixture.Companion.unsupported
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.KRX
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.USD
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** α-0 r2 §5.2. Real PAC/session and real 10s service/transport; only Retrofit's endpoint and clocks are fakes. */
class TopicSessionKrxBootstrapIntegrationTest {
    private val desired = setOf(USD, KRX)
    private fun test(row: String, body: suspend KrxA1bFixture.() -> Unit) = a1bTest(row, desired, gap = Duration.ZERO) { f -> f.body() }
    private fun variants(row: String, vararg labels: String, body: suspend KrxA1bFixture.(String) -> Unit) =
        a1bVariants(row, *labels, desired = desired, gap = Duration.ZERO) { f, label -> f.body(label) }

    @Test fun b01_krxThreeAttempts_generalOne() = test("B01") {
        live(); val first = issue()
        val general = calls.single { it.topic == USD }
        reply(general, unavailable); reply(first, unavailable)
        val second = next(1); reply(second, unavailable)
        val third = next(2); reply(third, delivered())
        assertTrue(c("third KRX success is applied"), has(1555.0))
        r.advance(2_000)
        assertEquals(c("KRX has exactly three physical attempts"), 3, krx.sumOf { it.sends.size })
        assertEquals(c("GENERAL remains one physical attempt after identical 503"), 1, calls.filter { it.topic == USD }.sumOf { it.sends.size })
    }

    @Test fun b02_firstBackoff_jitterBoundaries() = variants("B02", "j0", "j05", "j1") { label ->
        r.jitterUnit = when (label) { "j0" -> 0.0; "j05" -> 0.5; else -> 1.0 }
        live(); settleGeneral(); val first = issue()
        val delay = when (label) { "j0" -> 400L; "j05" -> 500L; else -> 600L }
        val failedAt = r.clock.nowMillis(); reply(first, unavailable)
        r.advance(delay - 1)
        assertTrue("premise: ${r.row} capability still OPEN inside retry wait", r.snapshot.facts.capabilityAllowed)
        assertEquals(c("no attempt before exact first backoff"), 1, krx.size)
        r.advance(1); val second = next(1, 0)
        assertEquals(c("first backoff exact boundary"), failedAt + delay, second.at)
    }

    @Test fun b03_secondBackoff_jitterBoundaries() = variants("B03", "j0", "j05", "j1") { label ->
        r.jitterUnit = when (label) { "j0" -> 0.0; "j05" -> 0.5; else -> 1.0 }
        live(); settleGeneral(); reply(issue(), unavailable)
        val second = next(1)
        val delay = when (label) { "j0" -> 1_200L; "j05" -> 1_500L; else -> 1_800L }
        val failedAt = r.clock.nowMillis(); reply(second, unavailable)
        r.advance(delay - 1)
        assertTrue("premise: ${r.row} still inside second retry wait", r.snapshot.facts.capabilityAllowed)
        assertEquals(c("no attempt before second boundary"), 2, krx.size)
        r.advance(1); assertEquals(c("second backoff exact boundary"), failedAt + delay, next(2, 0).at)
    }

    @Test fun b04_retryAfter_andSharedFloor_takeMax() = variants("B04", "retry-after-longer", "backoff-longer", "shared-floor-longer", "backoff-over-shared") { label ->
        live(); settleGeneral(); var call = issue()
        if (label == "backoff-longer") { reply(call, unavailable); call = next(1) }
        val count = krx.size; val failedAt = r.clock.nowMillis()
        val expected = when (label) {
            "retry-after-longer" -> 3_000L
            "backoff-longer" -> 1_200L
            "shared-floor-longer" -> 2_000L
            else -> 400L
        }
        if (label.startsWith("shared") || label == "backoff-over-shared") r.httpFloor = failedAt + if (label.startsWith("shared")) 2_000 else 100
        if (label.contains("retry-after") || label == "backoff-longer")
            reply(call, KrxA1bFixture.Reply(429, """{"error":"limited"}""", if (label == "backoff-longer") "1" else "3"))
        else reply(call, unavailable)
        r.advance(expected - 1)
        assertTrue("premise: ${r.row} shared floor/backoff window still open", r.snapshot.facts.capabilityAllowed)
        assertEquals(c("max floor prevents early retry"), count, krx.size)
        r.advance(1); assertEquals(c("retry occurs at max floor/backoff"), failedAt + expected, next(count, 0).at)
        if (label.contains("retry-after") || label == "backoff-longer") assertEquals(c("HTTP evidence reaches the floor owner once"), 1, floorEvidence.size)
    }

    @Test fun b05_tokenWait_countsTowardTenSeconds() = test("B05") {
        holdTokens = true; live(); settleGeneral(); val call = issue()
        assertEquals("premise: B05 actual service waits on provider token", KrxA1bFixture.Stage.TOKEN, call.stage)
        r.advance(9_999)
        assertEquals(c("no physical HTTP while token is held"), 0, call.sends.size)
        r.advance(1)
        assertEquals(c("real service budget includes token wait"), TopicSnapshotOutcome.TimedOut, call.outcome)
        call.token.complete(Unit); r.drain(); r.advance(2_000)
        assertEquals(c("late shared token completion cannot start an expired attempt"), 0, call.sends.size)
        assertEquals(c("timeout is terminal, no new attempt"), 1, krx.size)
    }

    @Test fun b06a_budgetBoundary_justBeforeAndAtTenSeconds() = variants("B06a", "body-9999", "body-10000", "queued-9999", "queued-10000") { label ->
        live(); settleGeneral(); val call = issue(); val start = call.at
        val boundary = label.substringAfter('-').toLong()
        if (label.startsWith("queued")) {
            val gate = InputGate("BootstrapAnswered")
            try {
                r.advance(start + 9_998 - r.clock.nowMillis()); reply(call, delivered())
                assertTrue(c("real answer was produced inside budget"), gate.count > 0)
                r.advance(start + boundary - r.clock.nowMillis()); gate.release()
            } finally { gate.close() }
        } else {
            r.advance(start + boundary - r.clock.nowMillis()); reply(call, delivered())
        }
        if (boundary == 9_999L) assertTrue(c("just-inside delivery applies"), has(1555.0))
        else {
            assertTrue("premise: ${r.row} original attempt reached absolute deadline", r.clock.nowMillis() - start >= 10_000)
            assertFalse(c("at-deadline delivery cannot apply"), has(1555.0))
        }
    }

    @Test fun b06b_validWsSnapshot_stopsRemainingAttempts() = variants("B06b", "accepted-valid", "invalid", "preACK") { label ->
        live(ack = label != "preACK"); settleGeneral(); reply(issue(), unavailable)
        assertTrue("premise: ${r.row} retry window has started", r.snapshot.facts.capabilityAllowed)
        if (label == "invalid") r.wire.deliver(KrxA1bFixture.krxFrame(-1.0)) else r.krxTick(1555.0)
        r.drain()
        if (label == "accepted-valid") assertTrue(c("accepted KRX WS is positive before stop assertion"), has(1555.0))
        else assertFalse(c("invalid/preACK frame did not become a snapshot"), has(1555.0))
        r.advance(400)
        assertEquals(c("only accepted valid WS suppresses retry"), if (label == "accepted-valid") 1 else 2, krx.size)
        if (label == "preACK") { r.ack(); r.krxTick(1666.0); assertTrue(c("fresh frame after ACK applies"), has(1666.0)) }
    }

    @Test fun b07a_uidLoss_cancelsDelay() = test("B07a") { cancellation("uid", "delay") }
    @Test fun b07b_uidLoss_cancelsTokenWait() = test("B07b") { cancellation("uid", "token") }
    @Test fun b07c_uidLoss_cancelsHttpBody() = test("B07c") { cancellation("uid", "body") }
    @Test fun b08a_falseLoss_cancelsDelay() = test("B08a") { cancellation("false", "delay") }
    @Test fun b08b_falseLoss_cancelsTokenWait() = test("B08b") { cancellation("false", "token") }
    @Test fun b08c_falseLoss_cancelsHttpBody() = test("B08c") { cancellation("false", "body") }
    @Test fun b09_capabilityEpochLoss_cancelsEveryStage() = variants("B09", "delay", "token", "body") { stage -> cancellation("epoch", stage) }
    @Test fun b10a_premiumLoss_cancelsEveryKrxStage() = variants("B10a", "delay", "token", "body") { stage -> cancellation("premium", stage) }

    @Test fun b10b_capabilityLoss_preservesGeneralAndPacing() = a1bTest("B10b", setOf(USD, TopicCatalogue.TETHER, KRX)) { f -> with(f) {
        live(); issue()
        val usd = calls.single { it.topic == USD }
        assertEquals("premise: B10b GENERAL body is still held", KrxA1bFixture.Stage.BODY, usd.stage)
        reply(usd, KrxA1bFixture.Reply(200, TopicSessionKrxHarness.usdFrame(1337.0)))
        val before = calls.size; val floor = r.clock.nowMillis() + 2_000; r.httpFloor = floor
        r.request(USD); loss("false")
        assertTrue(c("capability loss actually cancels only the KRX body"), krx.single().cancelled)
        assertTrue(c("GENERAL use remains admitted"), usd.admitted())
        assertTrue(c("GENERAL data applied under the same use"), has(1337.0))
        assertFalse(c("GENERAL in-flight tether body was not cancelled"), calls.single { it.topic == TopicCatalogue.TETHER }.cancelled)
        r.advance(floor - r.clock.nowMillis() - 1)
        assertEquals(c("existing shared floor is preserved"), before, calls.size)
        r.advance(1); assertEquals(c("GENERAL request resumes exactly at shared floor"), floor, calls.last().at)
        reply(calls.last(), KrxA1bFixture.Reply(404, """{"error":"topics_disabled"}"""))
        val tether = calls.first { it.topic == TopicCatalogue.TETHER }
        reply(tether, KrxA1bFixture.Reply(404, """{"error":"topics_disabled"}""")); r.request(TopicCatalogue.TETHER)
        val count = calls.size; r.advance(499)
        assertEquals(c("GENERAL pacing remains 500ms"), count, calls.size)
        r.advance(1); assertEquals(c("next GENERAL issue keeps the original gap"), floor + 500, calls.last().at)
    } }

    @Test fun b10c_cancelledLateCompletion_cannotApplyOrRetry() = variants("B10c", "cancel-first/200", "cancel-first/Unsupported", "reply-first/200", "reply-first/Unsupported") { label ->
        live(); settleGeneral(); val call = issue()
        val before = r.h.source.calls.size
        val gate = InputGate("BootstrapAnswered")
        try {
            val value = if (label.endsWith("/200")) delivered(1777.0) else unsupported
            if (label.startsWith("reply-first")) { reply(call, value); assertTrue(c("actual old outcome is queued before retirement"), gate.count > 0) }
            loss("false")
            if (label.startsWith("cancel-first")) {
                assertTrue(c("real body cancellation precedes late completion"), call.cancelled)
                reply(call, value)
            }
            assertTrue("premise: ${r.row} still hidden when old answer is released", !r.snapshot.facts.capabilityAllowed)
            gate.release(); r.advance(2_000)
            assertFalse(c("retired answer cannot apply protected data"), has(1777.0))
            assertEquals(c("old queued marker cannot cause a new FE"), before + 1, r.h.source.calls.size)
            assertEquals(c("retired completion cannot retry"), 1, krx.size)
            if (label.startsWith("cancel-first")) assertEquals(c("cancelled job produces no answer"), 0, gate.count)
        } finally { gate.close() }
    }

    @Test fun b10d_cancellationException_isNotRetryable() = test("B10d") {
        live(); settleGeneral(); val call = issue(); val cancelled = CancellationException("injected body cancellation")
        call.throwOnBody = cancelled; reply(call, unavailable)
        assertEquals(c("real service propagates cancellation rather than wrapping an outcome"), cancelled.message, call.cancellation?.message)
        assertNull(c("cancellation is never an Unreachable answer"), call.outcome)
        r.advance(2_000); assertEquals(c("cancellation starts no policy retry"), 1, krx.size)
    }

    private suspend fun KrxA1bFixture.cancellation(cause: String, stage: String) {
        holdTokens = stage == "token"; live(); settleGeneral(); val call = issue()
        val oldEpoch = call.read.facts.recordFence?.krxCapabilityEpoch
        val probe = if (stage == "delay") {
            reply(call, unavailable)
            r.sleeps.singleOrNull { it.duration.inWholeMilliseconds == 400L && !it.cancelled && !it.returned }
                .also { assertNotNull(c("actual retry delay probe entered"), it) }
        } else null
        if (stage != "delay") assertEquals("premise: ${r.row} real service still in $stage", if (stage == "token") KrxA1bFixture.Stage.TOKEN else KrxA1bFixture.Stage.BODY, call.stage)
        loss(cause)
        if (stage == "delay") assertTrue(c("actual KRX delay is cancelled"), checkNotNull(probe).cancelled)
        else assertTrue(c("actual KRX $stage wait catches cancellation"), call.cancelled)
        val count = krx.size
        call.token.complete(Unit); reply(call, delivered(1777.0)); r.advance(2_000)
        assertFalse(c("late old completion cannot apply"), has(1777.0))
        if (cause == "epoch") {
            assertNotEquals("premise: ${r.row} real C1 changed capability epoch", oldEpoch, r.snapshot.facts.recordFence?.krxCapabilityEpoch)
            assertTrue(c("any replacement issue belongs to the new epoch"), krx.drop(1).all { it.read.facts.recordFence?.krxCapabilityEpoch != oldEpoch })
        } else {
            assertTrue("premise: ${r.row} use remains unavailable", !r.snapshot.facts.capabilityAllowed)
            assertEquals(c("no successor after cancelled old job"), count, krx.size)
        }
    }
    /** α-3 additive row; original locked r2 methods are unchanged. */
    @Test fun a3_b07b10_disconnectedCapabilityLoss_cancelsActualKrxStage() = KrxA3Rows.b07b10_disconnectedCapabilityLoss_cancelsActualKrxStage()

    /** α-4 independent column; existing locked r2/a3 methods are unchanged. */
    @Test fun a4_b01_general503_isOneExchangeThroughKrxLadderEnd() = KrxA4Rows.b01_general503_isOneExchangeThroughKrxLadderEnd()

    /** α-4 independent column; existing locked r2/a3 methods are unchanged. */
    @Test fun a4_b04_reprepareYield_rechecksRaisedGeneralFloor() = KrxA4Rows.b04_reprepareYield_rechecksRaisedGeneralFloor()

    /** α-5: real GENERAL 429 continuation is queued after issue admission, before the first KRX job. */
    @Test fun a5_firstAttempt_waitsForFloorRaisedAfterIssueAdmission() = a1bTest("A5-B04-FIRST", desired) { f -> with(f) {
        live()
        val general = calls.singleOrNull { it.topic == USD }
            ?: throw AssertionError("premise: ${r.row} original GENERAL service reached its endpoint")
        assertEquals("premise: ${r.row} GENERAL body is still pending before KRX issue", KrxA1bFixture.Stage.BODY, general.stage)
        assertTrue("premise: ${r.row} KRX has not started behind the original 500ms pacing", krx.isEmpty())
        assertEquals("premise: ${r.row} initial shared floor is clear", 0L, r.httpFloor)

        var issueRead: KrxSessionIntegrationRig.ReadEvent? = null
        var queuedAt: Long? = null
        var raisedAt: Long? = null
        var physicalBeforeRaise: Int? = null
        var logicalBeforeRaise: Int? = null
        var recordedFloor: Long? = null
        var issuePayloadCount: Int? = null
        val evidenceOwner = r.httpEvidence
        r.httpEvidence = { status, retry ->
            if (status == 429 && retry == "3") {
                raisedAt = r.clock.nowMillis()
                physicalBeforeRaise = krx.sumOf { it.sends.size }
                logicalBeforeRaise = krx.size
            }
            evidenceOwner(status, retry)
            if (status == 429 && retry == "3") recordedFloor = r.httpFloor
        }
        r.afterPayloadRead = { read ->
            if (read.method == "acquireWithKrx" && issueRead == null) {
                assertTrue("premise: ${r.row} payload is acquired after the real issueOwedBootstraps admission", Thread.currentThread().stackTrace.any {
                    it.className.startsWith(TopicSessionCoordinator::class.java.name) && it.methodName == "issueOwedBootstraps"
                })
                assertTrue("premise: ${r.row} issue admission passed while floor was at or below now", r.httpFloor <= r.clock.nowMillis())
                assertTrue("premise: ${r.row} no KRX physical HTTP exists before queuing GENERAL 429", krx.sumOf { it.sends.size } == 0)
                issueRead = read
                queuedAt = r.clock.nowMillis()
                issuePayloadCount = r.readLog.count { it.method == "acquireWithKrx" }
                // No scheduler drain in this callback. The GENERAL body's real continuation
                // is queued now; startKrxBootstrap queues its default-start job afterwards.
                reply(general, KrxA1bFixture.Reply(429, """{"error":"a5_limited"}""", "3"), drain = false)
                assertEquals("premise: ${r.row} queueing the body did not synchronously raise the floor", 0L, r.httpFloor)
            }
        }
        try {
            r.advance(500)
            val prepared = checkNotNull(issueRead) { "premise: ${r.row} first KRX issue payload was actually acquired" }
            val issueAt = checkNotNull(queuedAt) { "premise: ${r.row} original issue admission time was recorded" }
            val floor = issueAt + 3_000
            assertEquals("premise: ${r.row} real GENERAL continuation raised floor in that same dispatcher turn", queuedAt, raisedAt)
            assertEquals("premise: ${r.row} floor rose before any KRX logical service invocation", 0, logicalBeforeRaise)
            assertEquals("premise: ${r.row} floor rose before any KRX physical HTTP", 0, physicalBeforeRaise)
            assertEquals("premise: ${r.row} actual GENERAL service returned 429", 429,
                (general.outcome as? TopicSnapshotOutcome.Refused)?.failure?.statusCode)
            assertTrue("premise: ${r.row} actual GENERAL 429 reached the session result observer", r.undelivered.any {
                (topic, outcome) -> topic == USD && outcome === general.outcome
            })
            assertEquals("premise: ${r.row} delegated real Retry-After evidence set the new floor", floor, recordedFloor)
            assertEquals("premise: ${r.row} shared floor is still the real GENERAL floor", floor, r.httpFloor)
            assertTrue("premise: ${r.row} observation remains inside the first-attempt floor window", r.clock.nowMillis() < floor)
            assertEquals(c("first KRX HTTP cannot bypass a floor raised after issue admission"), 0, krx.sumOf { it.sends.size })
            assertEquals(c("floor wait does not invoke the KRX service early"), 0, krx.size)
            r.advance(floor - r.clock.nowMillis() - 1)
            assertEquals("premise: ${r.row} negative observation is exactly one millisecond before floor", floor - 1, r.clock.nowMillis())
            assertTrue("premise: ${r.row} GENERAL original use remains admitted through the wait", general.admitted())
            assertEquals(c("zero KRX physical HTTP through floor minus one"), 0, krx.sumOf { it.sends.size })
            assertEquals(c("waiting adds no issue-preparation payload acquisition"), issuePayloadCount,
                r.readLog.count { it.method == "acquireWithKrx" })
            r.advance(1)
            val first = next(0, 0)
            assertEquals(c("exactly the first logical attempt starts at the floor"), floor, first.at)
            assertEquals(c("exactly one first physical HTTP starts at the floor"), listOf(floor), first.sends)
            assertEquals(c("first service invocation is still the only logical attempt"), 1, krx.size)
            assertSame(c("floor wait preserves the one original issue payload"), prepared.snapshot, first.read)
            assertEquals(c("first send required no additional issue-preparation payload read"), issuePayloadCount,
                r.readLog.count { it.method == "acquireWithKrx" })
            r.afterPayloadRead = null

            // Prove that the wait reserved no attempt number: the first actual failure must
            // still get the first (400ms) backoff, then the second (1200ms), then attempt 3.
            reply(first, unavailable)
            r.advance(399)
            assertTrue("premise: ${r.row} original first-attempt use is valid inside its first backoff", first.admitted())
            assertEquals(c("floor waiting did not consume first backoff or an attempt slot"), 1, krx.size)
            r.advance(1); val second = next(1, 0)
            assertEquals(c("first actual failure uses attempt-1 backoff of 400ms"), floor + 400, second.at)
            reply(second, unavailable)
            r.advance(1_199)
            assertTrue("premise: ${r.row} second-attempt use remains valid inside its second backoff", second.admitted())
            assertEquals(c("second actual failure retains the third attempt slot"), 2, krx.size)
            r.advance(1); val third = next(2, 0)
            assertEquals(c("second actual failure uses 1200ms before attempt 3"), floor + 1_600, third.at)
            reply(third, delivered(1555.0))
            assertTrue(c("third legitimate attempt applies its protected result"), has(1555.0))
            r.advance(2_000)
            assertEquals(c("floor waiting created no fourth logical attempt"), 3, krx.size)
            assertEquals(c("exactly three physical exchanges remain available after floor waiting"), 3, krx.sumOf { it.sends.size })
            assertEquals(c("GENERAL completed its real 429 with one physical exchange"), 1, general.sends.size)
            assertEquals(c("GENERAL Retry-After evidence was delegated exactly once"), 1, floorEvidence.count { it == (429 to "3") })
        } finally {
            r.afterPayloadRead = null
            r.httpEvidence = evidenceOwner
        }
    } }

}
