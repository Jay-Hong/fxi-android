package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.HEAD_FACTORY
import com.jay.fxi.data.entitlements.KrxCapabilityState
import com.jay.fxi.data.entitlements.KrxContestPhase
import com.jay.fxi.data.entitlements.KrxContestView
import com.jay.fxi.data.entitlements.KrxHarness
import com.jay.fxi.data.entitlements.KrxHarnessDefaults
import com.jay.fxi.data.entitlements.KrxSendMarker
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.TopicRejectionView
import com.jay.fxi.data.entitlements.applied
import com.jay.fxi.data.entitlements.checkedOnTest
import com.jay.fxi.data.entitlements.grantedKrxVisible
import com.jay.fxi.data.entitlements.issueGrant
import com.jay.fxi.data.entitlements.krxHarness
import com.jay.fxi.data.entitlements.observePublications
import com.jay.fxi.data.entitlements.rejectKrx
import com.jay.fxi.data.entitlements.settleKrx
import com.jay.fxi.data.entitlements.traceLine
import com.jay.fxi.data.remote.TopicSessionKrxHarness.Companion.KRX
import com.jay.fxi.data.remote.TopicSessionKrxHarness.Companion.TETHER
import com.jay.fxi.data.remote.TopicSessionKrxHarness.Companion.TEST_DESIRED
import com.jay.fxi.data.remote.TopicSessionKrxHarness.Companion.UNKNOWN_TOPIC
import com.jay.fxi.data.remote.TopicSessionKrxHarness.Companion.USD
import com.jay.fxi.data.remote.TopicSessionKrxHarness.Companion.tetherFrame
import com.jay.fxi.data.remote.TopicSessionKrxHarness.Companion.usdFrame
import com.jay.fxi.domain.model.TopicRejectionReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6-2b-1b-α contract tests r1 — TA-05 `TopicSessionKrxSendMarkerTest`, WebSocket branch (design r5 §13 T.6 'WS 갈래' and
 * 'OFF·운영 DESIRED'; §5C WS attempt capture; §2 ④ marker carriage off; §3 D-8 marker channel; §5.1 CAP-R01·R04·R05).
 *
 * Rows covered: 05-1, 05-2, 05-3, 05-4, 05-5, 05-6, 05-7, 05-11, 05-24 (WebSocket half), 05-25 (WebSocket half), 05-O1, 05-O3. The
 * REST branch rows (05-8~05-10, 05-12~05-23, 05-O2) and the REST halves of 05-24 and 05-25 are in `TopicSessionKrxRestMarkerTest`; the
 * harness (S-3 [TopicSessionKrxHarness]) is REST-ready and 05-5·05-6·05-7 already drive its gated bootstrap fake.
 *
 * Conventions (T.0): `premise:` holds at HEAD. `premise(ON):` = krxPermitSeq ≥ 1 at the named snapshot; `capabilityAllowed` is asserted
 * separately where a KRX use is acquired while allowed, and `capabilityAllowed = false` with general use allowed where a step sends
 * under a hold or before the first permit. `premise(OFF):` = krxPermitSeq = 0 at every observed snapshot. `contract(<row>):` (tests r2,
 * E-18) = a precondition that rests on α behaviour — another row's contract — placed before the row contract, so on the stub the row
 * fails as a contract failure, not on a premise. Every ON PAC is `CHECKED_ON` inside `checkedOnTest` (S-4); every OFF PAC is
 * `HEAD_FACTORY`. Every assertion names its row. Before a negative assertion the row first asserts that what it judges is inside its
 * window (the attempt was sent, the refusal was reported and processed, the answer reached the session).
 *
 * 'The marker comes from the send read' (tests r2, Codex tests r1 fix 2, E-17) is judged against what supplier B actually returned in
 * that attempt's send ([TopicSessionKrxHarness.Attempt.readerSnapshotsInSend], recorded by S-3; tests r5: attributed by call stack to
 * the session's send call that followed the attempt's encode, a read from any other path left unattributed): B returned exactly one
 * snapshot there (05-4's contract, `contract(05-4):` in other rows) and the carried marker's (epoch, permit) are that snapshot's. The
 * harness's `snapshotAtEncode` is a capture of the published snapshot at the encode; rows still use it for premises and the send-time
 * value.
 *
 * Observation is the S-3 harness's only: recording issuer (S-5), ledger reservation marker, `krxContestView`, counting suppliers and
 * the snapshots supplier B returned, wire, recording graph sink. No observer is added to the session (KW04).
 *
 * Restatements (design text → observable; reported as deviations): 05-7 '`endGraphUse` 0' → the recording graph sink receives no
 * AUTHORITY_ENDED continuity offer with reason USE_WITHHELD; '`endGraphAuthority` 0' → no AUTHORITY_ENDED offer at all. A later
 * non-KRX issue reaching the fake bootstrap is checked as well, but only shows the issue loop still issues — it is not a witness of
 * '`cancelBootstrapIssue` 0'. '`AccessWithheldDue` 0' → the attempt reached the socket and was sent (the SessionInput is private).
 * 'send-read seam calls' / '`authority.admits` calls' of an attempt → reads of supplier B / A between that attempt's encode and its
 * socket send (the send lambda runs synchronously between the two).
 *
 * g4-fix (verifier findings): 05-4 gains the 401 forced-refresh resend variant; 05-7 (ii)·(iii) send KRX inside their AWAITING window;
 * 05-O1 compares the whole `krx` flow; 05-O3 pins HEAD's literal counts and asserts premise(OFF); 05-1 asserts permit 1; the non-KRX
 * bundle checks tether stays confirmed; variant runners report non-assertion failures with the row label.
 *
 * tests r2 (Codex tests r1 REVISE): 05-1, 05-4, 05-5, 05-6, 05-11 judge the carried marker against what B returned (fix 2, E-17);
 * 05-24 added (E-07); 05-O1 asserts its window before the comparisons (E-34); 05-7 (iii)'s window is `contract(05-8):` (E-18);
 * 05-O3's literals are stated as stub-measured (E-19).
 *
 * tests r3 (Codex tests r2): 05-24's KDoc names its REST half, now a separate test in the REST class (REVISE 1); 05-25 added, its
 * WebSocket half here — the internal constructor with `krxSendReader = null`, marker channel present and absent (E-35).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TopicSessionKrxSendMarkerTest {
    private val active = EntitlementsOutcome.StableActive(krxVisible = true)
    private val krxFalse = EntitlementsOutcome.StableActive(krxVisible = false)
    private val activeTopics = listOf(TETHER, USD)
    private val krxRefused = mapOf(KRX to "krx_entitlement_required")
    private var minute = 21

    /** A strictly later wire timestamp each call, so a price always moves (TopicRates folds the later of two). */
    private fun nextStamp(): String = "2026-08-31T10:%02d:00+09:00".format(minute++)

    private fun KrxHarness.view(row: String): KrxContestView =
        checkNotNull(coordinator.krxContestView) { "$row: the ON PAC has a contest view" }

    /** Holds the issuer's next entitlements query at its fetch (KrxHarnessSource.gates), so a handed-over refusal stays AWAITING. */
    private fun KrxHarness.holdNextFetch(): CompletableDeferred<EntitlementsOutcome> =
        CompletableDeferred<EntitlementsOutcome>().also { source.gates.addLast(it) }

    private fun TopicAccessSnapshot.marker(): KrxSendMarker = KrxSendMarker(facts.recordFence?.krxCapabilityEpoch, krxPermitSeq)

    /**
     * The snapshot supplier B returned in this attempt's send (tests r2, Codex tests r1 fix 2, E-17): S-3 records each return of B and
     * (tests r5) attributes it by call stack to the attempt whose `send(encode(…))` it was made in — the session's send call that
     * followed that attempt's encode; a read from any other path is unattributed. B returning exactly one snapshot there is 05-4's
     * contract (§5C, one send read per attempt) — α behaviour, so a row other than 05-4 passes `"<row> contract(05-4):"` as [label]
     * (E-18).
     */
    private fun TopicSessionKrxHarness.Attempt.sendRead(label: String): TopicAccessSnapshot {
        assertEquals(
            "$label B returned exactly one snapshot in the attempt's send (the session's send call that followed its encode)",
            1, readerSnapshotsInSend.size
        )
        return readerSnapshotsInSend.single()
    }

    /** The REST counterpart (05-5): the snapshot B returned for this KRX issue; exactly one is 05-16's contract (§5B, D-4). */
    private fun TopicSessionKrxHarness.BootstrapCall.issueRead(label: String): TopicAccessSnapshot {
        assertEquals(
            "$label B returned exactly one snapshot for the $topic issue (read in the session's REST issue path before the fake " +
                "bootstrap began it)",
            1, readerSnapshotsAtIssue.size
        )
        return readerSnapshotsAtIssue.single()
    }

    /**
     * ON rig: CHECKED_ON PAC inside checkedOnTest, prepared by [prepare] (default: granted, KRX visible, OPEN p = 1), and S-3
     * ([publicConstructor]: built by the session's public constructor, 05-24; `sendReader = false`: the internal constructor with
     * `krxSendReader = null`, 05-25).
     */
    private fun onRig(
        desired: Set<String> = TEST_DESIRED,
        markerChannel: Boolean = true,
        publicConstructor: Boolean = false,
        sendReader: Boolean = true,
        prepare: suspend (KrxHarness) -> Unit = { it.grantedKrxVisible() },
        body: suspend TestScope.(KrxHarness, TopicSessionKrxHarness) -> Unit
    ) = checkedOnTest { h ->
        prepare(h)
        body(h, TopicSessionKrxHarness(h, desired, markerChannel, publicConstructor = publicConstructor, sendReader = sendReader))
    }

    /** OFF rig: HEAD_FACTORY PAC (KrxContestHarness, unchanged), prepared by [prepare], and S-3 over it. */
    private fun offRig(
        desired: Set<String> = TEST_DESIRED,
        markerChannel: Boolean = true,
        prepare: suspend (KrxHarness) -> Unit = { it.grantedKrxVisible() },
        body: suspend TestScope.(KrxHarness, TopicSessionKrxHarness) -> Unit
    ) = runTest {
        val processJob = SupervisorJob()
        try {
            val h = krxHarness(processJob, HEAD_FACTORY)
            prepare(h)
            body(h, TopicSessionKrxHarness(h, desired, markerChannel))
        } finally {
            processJob.cancel()
        }
    }

    /** Goes live and sends the first subscribe; returns that KRX attempt after asserting it reached the socket and was sent. */
    private fun TopicSessionKrxHarness.liveFirstKrxAttempt(row: String): TopicSessionKrxHarness.Attempt {
        goLive()
        assertEquals("$row premise: the deliverer granted one connection", 1, wires.size)
        openWire()
        val attempt = lastKrxAttempt(row)
        assertTrue("$row premise: the KRX attempt reached the socket and was sent", attempt.wireAccepted)
        return attempt
    }

    private fun assertOnAllowedAt(row: String, what: String, snapshot: TopicAccessSnapshot) {
        assertTrue("$row premise(ON): krxPermitSeq ≥ 1 at $what (${snapshot.krxPermitSeq})", snapshot.krxPermitSeq >= 1)
        assertTrue("$row premise(ON): capabilityAllowed at $what (KRX use acquired while allowed)", snapshot.facts.capabilityAllowed)
        assertNotNull("$row premise: the record at $what has a capability epoch", snapshot.facts.recordFence?.krxCapabilityEpoch)
    }

    /** Window premise: exactly [count] refusal reports of the session reached S-5, and the issuer processed each. */
    private fun TopicSessionKrxHarness.reportedAndProcessed(row: String, count: Int) {
        assertEquals("$row premise: the session's refusal reports reached the issuer", count, issuer.reserved.size)
        assertTrue(
            "$row premise: the issuer processed every reported reservation",
            issuer.reserved.all { r -> r.reservation in issuer.processed }
        )
    }

    /**
     * Non-KRX preservation (scope r11:792, the 05-7 bundle): the session keeps its socket and grant, ended no graph authority, a
     * tether and a USD frame still move the store and the prices, and tether stays confirmed.
     */
    private fun TopicSessionKrxHarness.assertNonKrxKept(row: String, grant: TopicGrantToken, wiresBefore: Int) {
        assertEquals("$row non-KRX: no reconnection", wiresBefore, wires.size)
        assertFalse("$row non-KRX: the socket was not ended", wire.cancelled)
        assertTrue("$row non-KRX: the session was handed no other grant: $grantCalls", granted.all { it.grant == grant })
        assertTrue("$row non-KRX: no graph authority ended: ${authorityEndedAny().map { it.reason }}", authorityEndedAny().isEmpty())
        val tetherBefore = store.snapshot.stateFor(TETHER).receiveGeneration
        val usdBefore = store.snapshot.stateFor(USD).receiveGeneration
        val tetherRate = 1_400.0 + minute
        val usdRate = 1_380.0 + minute
        wire.deliver(tetherFrame(tetherRate, nextStamp()))
        wire.deliver(usdFrame(usdRate, nextStamp()))
        step(1)
        assertTrue("$row non-KRX: a tether frame is received", store.snapshot.stateFor(TETHER).receiveGeneration > tetherBefore)
        assertTrue("$row non-KRX: a USD frame is received", store.snapshot.stateFor(USD).receiveGeneration > usdBefore)
        assertTrue("$row non-KRX: tether stays confirmed", store.snapshot.stateFor(TETHER).confirmed)
        val rates = coordinator.rates.value.quotes.values.map { it.rate }
        assertTrue("$row non-KRX: the tether price is adopted ($rates)", tetherRate in rates)
        assertTrue("$row non-KRX: the USD price is adopted ($rates)", usdRate in rates)
    }

    /**
     * Every variant runs, so one variant's failure does not hide whether the others reach their windows; all are reported, the first
     * listed variant's message first. A variant that throws something other than an assertion (e.g. a `checkNotNull`) is reported
     * too, wrapped with its label so the failure names its row (T.0), and the remaining variants still run.
     */
    private fun runVariants(vararg variants: Pair<String, () -> Unit>) {
        val failures = mutableListOf<AssertionError>()
        for ((label, variant) in variants) {
            try {
                variant()
            } catch (failure: Throwable) {
                failures += failure as? AssertionError ?: AssertionError("$label: $failure", failure)
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError(failures.joinToString(" || ") { it.message.orEmpty() }).apply { failures.forEach(::addSuppressed) }
        }
    }

    // ---- WS branch ------------------------------------------------------------------------------------------------------------

    /**
     * 05-1 WS marker carriage (ON). OPEN(p = 1, K1) → the first subscribe carries KRX → ack refuses KRX with KRX_ENTITLEMENT_REQUIRED,
     * active {TETHER, USD}. The marker channel carries the send read's marker to the deliverer's overload, S-5's three-argument form and
     * the ledger; the PAC hands that permit over. Mutations: I-X, T-X, K-NUL (state alone would not see a null marker — the current permit
     * hands over the same way). 'From the send read' is judged against the snapshot B returned in that attempt's send (tests r2).
     */
    @Test fun r05_1_wsMarker_isCarriedFromTheSendRead_toTheLedger() = onRig { h, s ->
        val attempt = s.liveFirstKrxAttempt("05-1")
        val atSend = attempt.snapshotAtEncode
        assertOnAllowedAt("05-1", "the send", atSend)
        assertEquals("05-1 premise(ON): the send is under permit 1", 1L, atSend.krxPermitSeq)
        val sent = atSend.marker()
        val grant = s.granted.last().grant
        val wiresBefore = s.wires.size
        h.holdNextFetch()

        s.wire.deliver(s.ack(attempt.requestId, activeTopics, krxRefused))
        s.step(1)

        assertEquals("05-1 premise: the acknowledgement was applied", 1, s.acknowledgements.size)
        assertEquals(
            "05-1 premise: the acknowledgement refused KRX",
            mapOf(KRX to TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED), s.acknowledgements.single().rejected
        )
        val read = attempt.sendRead("05-1 contract(05-4):")
        assertEquals("05-1: the marker channel is called once", 1, s.markerRejections.size)
        assertEquals("05-1: the marker channel carries the send-time marker", sent, s.markerRejections.single().marker)
        assertEquals(
            "05-1: the carried marker's (epoch, permit) are those of the snapshot B returned in the attempt's send (same read)",
            read.marker(), s.markerRejections.single().marker
        )
        assertEquals("05-1: the public two-argument refusal is not also called", 0, s.publicRejections.size)
        assertEquals("05-1: the issuer's three-argument reservation is made once", 1, s.issuer.threeArgument.size)
        assertEquals("05-1: no two-argument reservation", 0, s.issuer.twoArgument.size)
        val reserved = s.issuer.threeArgument.single()
        assertEquals("05-1: the issuer receives the send-time marker", sent, reserved.marker)
        assertEquals("05-1: the ledger reservation carries the send-time marker", sent, reserved.reservation.marker)
        val view = h.view("05-1")
        assertEquals("05-1: the barrier is that reservation", reserved.reservation.order, view.state.barrier)
        assertEquals("05-1: AWAITING", KrxContestPhase.AWAITING, view.state.phase)
        assertEquals("05-1: the send permit is handed over", sent.permitSeq, view.rejectedPermit)
        s.assertNonKrxKept("05-1", grant, wiresBefore)
    }

    /**
     * 05-2 CAP-R11 (ii). OPEN(p = 1) send (marker (K1, 1)) → ack held → another reservation with the same permit's marker hands over by
     * H1′ (identityNulls, no rotation) → the fresh true opens p = 2 → the ack arrives. Judged as the previous permit: nothing moves.
     * Mutations: K-AKp ((K1, 2) → handover, AWAITING, FE +1), K-AK.
     */
    @Test fun r05_2_capR11ii_aNewPermitBeforeTheAck_isJudgedAsThePreviousPermit() = onRig { h, s ->
        val attempt = s.liveFirstKrxAttempt("05-2")
        val atSend = attempt.snapshotAtEncode
        assertOnAllowedAt("05-2", "the send", atSend)
        assertEquals("05-2 premise(ON): the send is under permit 1", 1L, atSend.krxPermitSeq)
        val epoch = atSend.facts.recordFence?.krxCapabilityEpoch

        val grant = h.issueGrant()
        h.source.identityNulls = 1
        val other = h.coordinator.reserveRejection(grant, KrxHarnessDefaults.KRX_REFUSAL, KrxSendMarker(epoch, 1L))
        h.coordinator.onTopicRejected(other)
        assertEquals("05-2 premise: H1′ consumed the live read failure", 0, h.source.identityNulls)
        assertEquals("05-2 premise: H1′ does not rotate", epoch, h.store.record.krxCapabilityEpoch)
        assertEquals("05-2 premise: H1′ handed permit 1 over", 1L, h.view("05-2").rejectedPermit)
        s.step(H1_PRIME_FLOOR_STEP)
        val before = h.view("05-2")
        assertEquals("05-2 premise: OPEN before the ack", KrxContestPhase.OPEN, before.state.phase)
        assertEquals("05-2 premise: p = 2 before the ack", 2L, before.state.permitSeq)
        assertEquals("05-2 premise: rejectedPermit = 1 before the ack", 1L, before.rejectedPermit)
        assertEquals("05-2 premise: the ack is still owed to that attempt", 0, s.acknowledgements.size)
        assertEquals("05-2 premise: no other attempt was sent meanwhile", listOf(attempt.requestId), s.attempts.map { it.requestId })
        h.holdNextFetch()
        val fetches = h.source.calls.size

        s.wire.deliver(s.ack(attempt.requestId, activeTopics, krxRefused))
        s.step(1)

        assertEquals("05-2 premise: the acknowledgement was applied", 1, s.acknowledgements.size)
        s.reportedAndProcessed("05-2", 1)
        val after = h.view("05-2")
        assertEquals("05-2: still OPEN", KrxContestPhase.OPEN, after.state.phase)
        assertEquals("05-2: p stays 2", 2L, after.state.permitSeq)
        assertEquals("05-2: rejectedPermit stays 1", 1L, after.rejectedPermit)
        assertEquals("05-2: no entitlements query", fetches, h.source.calls.size)
        assertEquals("05-2: krx stays VISIBLE", KrxCapabilityState.VISIBLE, h.coordinator.krx.value)
    }

    /**
     * 05-3 CAP-R11 (v). OPEN(p = 1) send (marker (K1, 1)) → ack held → C1 (`source.next = krxFalse; refresh(FORCE_ENTITLEMENTS)`,
     * A1Test:668-672 form: rotation K2, NONE, p not handed over) → the ack arrives. The epoch guard drops it. Mutation: K-AKe ((K2, 1)
     * handover, FE +1).
     */
    @Test fun r05_3_capR11v_aC1RotationBeforeTheAck_isDroppedByTheEpochGuard() = onRig { h, s ->
        val attempt = s.liveFirstKrxAttempt("05-3")
        val atSend = attempt.snapshotAtEncode
        assertOnAllowedAt("05-3", "the send", atSend)
        val sentEpoch = atSend.facts.recordFence?.krxCapabilityEpoch
        val grant = h.issueGrant()

        h.source.next = { krxFalse }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        s.step(1)
        assertNotEquals("05-3 premise: C1 rotated the record's KRX epoch", sentEpoch, h.store.record.krxCapabilityEpoch)
        assertEquals("05-3 premise: NONE before the ack", KrxContestPhase.NONE, h.view("05-3").state.phase)
        assertEquals("05-3 premise: no permit handed over before the ack", 0L, h.view("05-3").rejectedPermit)
        assertEquals("05-3 premise(A1): the same grant after the KRX rotation", grant, h.issueGrant())
        assertEquals("05-3 premise: the ack is still owed to that attempt", 0, s.acknowledgements.size)
        h.holdNextFetch()
        val fetches = h.source.calls.size

        s.wire.deliver(s.ack(attempt.requestId, activeTopics, krxRefused))
        s.step(1)

        assertEquals("05-3 premise: the acknowledgement was applied", 1, s.acknowledgements.size)
        s.reportedAndProcessed("05-3", 1)
        assertEquals("05-3: no entitlements query is added", fetches, h.source.calls.size)
        assertEquals("05-3: NONE is kept", KrxContestPhase.NONE, h.view("05-3").state.phase)
        assertEquals("05-3: no permit is handed over", 0L, h.view("05-3").rejectedPermit)
    }

    /**
     * 05-4 CAP-R11 (iii) WS. Two variants, both run (failures aggregated):
     *
     * - **retry**: the first attempt's send fails (one injected wire refusal → TopicSubscribeCommand.kt:456-462 retries) → meanwhile p
     *   goes 1 → 2 (05-2 method: H1′ then the fresh true) → the second attempt is sent → ack refuses KRX. The retry wait is lengthened
     *   with jitter 1.0 (TopicCommandRetryPolicy.waitFor: 5 s + 5 s) so the H1′ floor (5 s) and its fresh true land between the two
     *   attempts.
     * - **401 forced refresh** (design: '변형(경로 미확인)'; the path exists — TopicSubscribeCommand.kt RefreshAndReplay →
     *   `credentials.refreshAfterUnauthorized` → `break@waiting` → a new attempt with a new encode and send lambda): the first attempt
     *   is sent; p goes 1 → 2 the 05-2 way while its answer is owed; then a socket `invalid_token` for it, the harness refresh hands
     *   back `token-2` for the same identity, and the replay attempt is sent at once → ack refuses KRX.
     *
     * In both, the later attempt reads anew: marker (K1, 2), p 2 handed over (AWAITING, FE +1). One send read per attempt and no
     * `authority.admits` in a KRX attempt (restated: supplier-B / supplier-A reads between each attempt's encode and its socket send).
     * Mutations: K-1st, RD-2.
     */
    @Test fun r05_4_capR11iii_theSecondAttemptReadsAnew() = runVariants(
        "05-4 [retry]" to { onRig { h, s -> retryAttempt("05-4 [retry]", h, s) } },
        "05-4 [401 forced refresh]" to { onRig { h, s -> refreshReplay("05-4 [401 forced refresh]", h, s) } }
    )

    /** 05-4, the retry variant: the socket refuses the first send, and the command's retry is the second attempt. */
    private suspend fun TestScope.retryAttempt(row: String, h: KrxHarness, s: TopicSessionKrxHarness) {
        s.jitterUnit = 1.0
        s.failSendsOnNextWire = 1
        s.goLive()
        assertEquals("$row premise: the deliverer granted one connection", 1, s.wires.size)
        s.openWire()
        val first = s.lastKrxAttempt(row)
        assertTrue("$row premise: the first attempt reached the socket", first.reachedWire)
        assertFalse("$row premise: the socket refused the first attempt", first.wireAccepted)
        assertOnAllowedAt(row, "the first attempt", first.snapshotAtEncode)
        assertEquals("$row premise(ON): the first attempt is under permit 1", 1L, first.snapshotAtEncode.krxPermitSeq)
        val epoch = first.snapshotAtEncode.facts.recordFence?.krxCapabilityEpoch

        val grant = h.issueGrant()
        h.source.identityNulls = 1
        h.coordinator.onTopicRejected(h.coordinator.reserveRejection(grant, KrxHarnessDefaults.KRX_REFUSAL, KrxSendMarker(epoch, 1L)))
        assertEquals("$row premise: H1′ consumed the live read failure", 0, h.source.identityNulls)
        s.step(H1_PRIME_FLOOR_STEP)
        assertEquals("$row premise: OPEN with p = 2 before the second attempt", KrxContestPhase.OPEN, h.view(row).state.phase)
        assertEquals("$row premise: p = 2 before the second attempt", 2L, h.view(row).state.permitSeq)
        assertEquals("$row premise: H1′ did not rotate", epoch, h.store.record.krxCapabilityEpoch)
        assertEquals("$row premise: still one attempt before the retry wait ends", 1, s.attempts.size)
        s.step(5_000)
        assertEquals("$row premise: the command sent its second attempt", 2, s.attempts.size)
        val second = s.attempts.last()
        assertTrue("$row premise: the second attempt carries KRX", second.carriesKrx)
        assertTrue("$row premise: the second attempt was sent", second.wireAccepted)
        assertOnAllowedAt(row, "the second attempt", second.snapshotAtEncode)
        assertEquals("$row premise(ON): the second attempt is under permit 2", 2L, second.snapshotAtEncode.krxPermitSeq)
        h.holdNextFetch()
        val fetches = h.source.calls.size

        s.wire.deliver(s.ack(second.requestId, activeTopics, krxRefused))
        s.step(1)

        assertEquals("$row premise: the acknowledgement was applied", 1, s.acknowledgements.size)
        val expected = KrxSendMarker(epoch, 2L)
        assertEquals("$row: the marker channel is called once", 1, s.markerRejections.size)
        assertEquals("$row: the second attempt's own marker (K1, 2)", expected, s.markerRejections.single().marker)
        val read = second.sendRead("$row:")
        assertEquals(
            "$row: the carried marker's (epoch, permit) are those of the snapshot B returned in the second attempt's send (same read)",
            read.marker(), s.markerRejections.single().marker
        )
        assertEquals("$row: the issuer's three-argument reservation carries (K1, 2)", listOf(expected), s.issuer.threeArgument.map { it.marker })
        assertEquals("$row: the ledger reservation carries (K1, 2)", expected, s.issuer.threeArgument.single().reservation.marker)
        assertEquals("$row: AWAITING", KrxContestPhase.AWAITING, h.view(row).state.phase)
        assertEquals("$row: permit 2 handed over", 2L, h.view(row).rejectedPermit)
        assertEquals("$row: FE +1", fetches + 1, h.source.calls.size)
        assertEquals("$row: one send read in the first attempt", 1, first.readerReadsInSend)
        assertEquals("$row: one send read in the second attempt", 1, second.readerReadsInSend)
        assertEquals("$row: no authority.admits in the first KRX attempt", 0, first.authorityReadsInSend)
        assertEquals("$row: no authority.admits in the second KRX attempt", 0, second.authorityReadsInSend)
    }

    /** 05-4, the 401 forced-refresh variant: a socket `invalid_token` for the first attempt, a usable refresh, and the replay attempt. */
    private suspend fun TestScope.refreshReplay(row: String, h: KrxHarness, s: TopicSessionKrxHarness) {
        s.refreshResult = AuthSnapshot(TopicSessionKrxHarness.IDENTITY.uid, TopicSessionKrxHarness.IDENTITY.authGeneration, "token-2")
        val first = s.liveFirstKrxAttempt(row)
        assertOnAllowedAt(row, "the first attempt", first.snapshotAtEncode)
        assertEquals("$row premise(ON): the first attempt is under permit 1", 1L, first.snapshotAtEncode.krxPermitSeq)
        val epoch = first.snapshotAtEncode.facts.recordFence?.krxCapabilityEpoch

        val grant = h.issueGrant()
        h.source.identityNulls = 1
        h.coordinator.onTopicRejected(h.coordinator.reserveRejection(grant, KrxHarnessDefaults.KRX_REFUSAL, KrxSendMarker(epoch, 1L)))
        assertEquals("$row premise: H1′ consumed the live read failure", 0, h.source.identityNulls)
        s.step(H1_PRIME_FLOOR_STEP)
        assertEquals("$row premise: OPEN before the replay", KrxContestPhase.OPEN, h.view(row).state.phase)
        assertEquals("$row premise: p = 2 before the replay", 2L, h.view(row).state.permitSeq)
        assertEquals("$row premise: H1′ did not rotate", epoch, h.store.record.krxCapabilityEpoch)
        assertEquals("$row premise: the first attempt's answer is still owed", 0, s.acknowledgements.size)
        assertEquals("$row premise: no other attempt was sent meanwhile", listOf(first.requestId), s.attempts.map { it.requestId })

        s.wire.deliver(s.subscriptionError(first.requestId, "invalid_token"))
        s.step(1)

        assertEquals("$row premise: the forced refresh replayed the subscribe as a new attempt", 2, s.attempts.size)
        val replay = s.attempts.last()
        assertNotEquals("$row premise: the replay is its own request", first.requestId, replay.requestId)
        assertTrue("$row premise: the replay carries KRX", replay.carriesKrx)
        assertTrue("$row premise: the replay was sent", replay.wireAccepted)
        assertEquals("$row premise: on the same connection", 1, s.wires.size)
        assertOnAllowedAt(row, "the replay", replay.snapshotAtEncode)
        assertEquals("$row premise(ON): the replay is under permit 2", 2L, replay.snapshotAtEncode.krxPermitSeq)
        h.holdNextFetch()
        val fetches = h.source.calls.size

        s.wire.deliver(s.ack(replay.requestId, activeTopics, krxRefused))
        s.step(1)

        assertEquals("$row premise: the replay's acknowledgement was applied", 1, s.acknowledgements.size)
        val expected = KrxSendMarker(epoch, 2L)
        assertEquals("$row: the marker channel is called once", 1, s.markerRejections.size)
        assertEquals("$row: the replay's own marker (K1, 2)", expected, s.markerRejections.single().marker)
        val read = replay.sendRead("$row:")
        assertEquals(
            "$row: the carried marker's (epoch, permit) are those of the snapshot B returned in the replay's send (same read)",
            read.marker(), s.markerRejections.single().marker
        )
        assertEquals("$row: the issuer's three-argument reservation carries (K1, 2)", listOf(expected), s.issuer.threeArgument.map { it.marker })
        assertEquals("$row: the ledger reservation carries (K1, 2)", expected, s.issuer.threeArgument.single().reservation.marker)
        assertEquals("$row: AWAITING", KrxContestPhase.AWAITING, h.view(row).state.phase)
        assertEquals("$row: permit 2 handed over", 2L, h.view(row).rejectedPermit)
        assertEquals("$row: FE +1", fetches + 1, h.source.calls.size)
        assertEquals("$row: one send read in the first attempt", 1, first.readerReadsInSend)
        assertEquals("$row: one send read in the replay", 1, replay.readerReadsInSend)
        assertEquals("$row: no authority.admits in the first KRX attempt", 0, first.authorityReadsInSend)
        assertEquals("$row: no authority.admits in the replay (a KRX attempt)", 0, replay.authorityReadsInSend)
    }

    private enum class Report { WS, REST }

    /**
     * 05-5 CAP-R01 (draft RU-11). The same epoch's WS refusal and REST unknown_topic, in both orders, each with the first one handed over
     * by H1 (rotation) or by H1′ (identityNulls, no rotation): four variants. One entitlements query, the streak +1 once, the first
     * report handed over (barrier = its order), the second ended without a handover; both markers non-null at their send values; the REST
     * reason is KRX_ENTITLEMENT_REQUIRED; non-KRX kept. Mutations: R-MAP, a doubled REST reservation, K-NUL, epoch guard and permit
     * judgement removed together. The expected values do not depend on OD-B05·OD-B08 (no new permit between send and processing).
     */
    @Test fun r05_5_capR01_sameEpochWsAndRestRefusals_handOverOnce() = runVariants(
        *Report.values().flatMap { first ->
            listOf(false, true).map { byH1Prime ->
                val row = "05-5[${first} first, ${if (byH1Prime) "H1′" else "H1"}]"
                row to { onRig { h, s -> capR01(row, h, s, first, byH1Prime) } }
            }
        }.toTypedArray()
    )

    private fun TestScope.capR01(row: String, h: KrxHarness, s: TopicSessionKrxHarness, first: Report, byH1Prime: Boolean) {
        s.goLive()
        val restIssue = s.bootstrapOf(KRX, row)
        assertTrue("$row premise: the KRX bootstrap is held before its exchange", restIssue.exchanges.isEmpty())
        assertOnAllowedAt(row, "the KRX issue", restIssue.snapshotAtCall)
        s.openWire()
        val attempt = s.lastKrxAttempt(row)
        assertTrue("$row premise: the KRX attempt was sent", attempt.wireAccepted)
        assertOnAllowedAt(row, "the WS send", attempt.snapshotAtEncode)
        val sent = attempt.snapshotAtEncode.marker()
        assertEquals(
            "$row premise: the harness's issue-time and send-time captures of the published snapshot have the same epoch and permit " +
                "(captures, not B's returns)",
            restIssue.snapshotAtCall.marker(), sent
        )
        val grant = s.granted.last().grant
        val wiresBefore = s.wires.size
        val streak = h.view(row).state.streak
        val fe = h.holdNextFetch()
        val fetches = h.source.calls.size
        if (byH1Prime) h.source.identityNulls = 1

        fun report(which: Report) = when (which) {
            Report.WS -> s.wire.deliver(s.ack(attempt.requestId, activeTopics, krxRefused))
            Report.REST -> {
                s.releaseBootstrap(KRX, UNKNOWN_TOPIC, row)
                Unit
            }
        }
        report(first)
        s.step(1)
        report(if (first == Report.WS) Report.REST else Report.WS)
        s.step(1)
        s.step(H1_PRIME_FLOOR_STEP)

        assertEquals("$row premise: the acknowledgement was applied", 1, s.acknowledgements.size)
        assertTrue(
            "$row premise: the REST answer reached the session's undelivered hand-over",
            s.undelivered.any { it.second == KRX && it.third == UNKNOWN_TOPIC }
        )
        val restRead = restIssue.issueRead("$row contract(05-16):")
        val wsRead = attempt.sendRead("$row contract(05-4):")
        assertEquals("$row: both refusals are reserved, in report order", 2, s.issuer.reserved.size)
        val (r1, r2) = s.issuer.reserved.sortedBy { it.reservation.order }
        if (byH1Prime) {
            assertEquals("$row premise(variant): H1′ consumed the live read failure", 0, h.source.identityNulls)
            assertEquals("$row premise(variant): H1′ did not rotate", sent.krxCapabilityEpoch, h.store.record.krxCapabilityEpoch)
        } else {
            assertNotEquals("$row premise(variant): H1 rotated", sent.krxCapabilityEpoch, h.store.record.krxCapabilityEpoch)
        }
        val view = h.view(row)
        assertEquals("$row: the first report is handed over (barrier)", r1.reservation.order, view.state.barrier)
        assertEquals("$row: AWAITING while the query is held", KrxContestPhase.AWAITING, view.state.phase)
        assertEquals("$row: the send permit is handed over", sent.permitSeq, view.rejectedPermit)
        assertEquals("$row: the first report is the one taken over", r1.reservation.order, h.coordinator.rejectionView(grant).latestCurrent)
        assertTrue("$row: the second report has ended", r2.reservation.order !in h.coordinator.rejectionView(grant).pendingOrders)
        assertEquals("$row: one entitlements query", fetches + 1, h.source.calls.size)
        assertEquals("$row: both reservations carry the send-time marker", listOf(sent, sent), listOf(r1, r2).map { it.reservation.marker })
        val rest = if (first == Report.REST) r1 else r2
        val ws = if (first == Report.WS) r1 else r2
        assertEquals(
            "$row: the WS reservation's marker (epoch, permit) are those of the snapshot B returned in the attempt's send (same read)",
            wsRead.marker(), ws.reservation.marker
        )
        assertEquals(
            "$row: the REST reservation's marker (epoch, permit) are those of the snapshot B returned at the KRX issue (same read)",
            restRead.marker(), rest.reservation.marker
        )
        assertEquals("$row: the REST reservation's reason", KrxHarnessDefaults.KRX_REFUSAL, rest.reasons)

        fe.complete(active)
        s.step(1)
        assertEquals("$row: OPEN after the fresh true", KrxContestPhase.OPEN, h.view(row).state.phase)
        assertEquals("$row: the streak moved once", streak + 1, h.view(row).state.streak)
        assertEquals("$row: still one entitlements query", fetches + 1, h.source.calls.size)
        s.assertNonKrxKept(row, grant, wiresBefore)
    }

    /**
     * 05-6 CAP-R04 (draft RU-04). (a) REST KRX Dormant (404 topics_disabled) (b) REST KRX Degraded (404 topic_unavailable) (c) WS ack
     * {KRX: TOPIC_UNAVAILABLE} (d) contrast REST USD Unsupported (e) REST KRX TemporarilyUnavailable / Refused(403). No query and no
     * contest change; only (c) reserves — reason [TOPIC_UNAVAILABLE], marker non-null — and the PAC ends it at once
     * (`TopicRejection.of` null). Non-KRX kept. Mutations: R-DEG, R-NK, WS-MAP, status alone merged.
     */
    @Test fun r05_6_capR04_unavailableDisabledAndOtherAnswers_demandNothing() = onRig { h, s ->
        val row = "05-6"
        val attempt = s.liveFirstKrxAttempt(row)
        assertOnAllowedAt(row, "the send", attempt.snapshotAtEncode)
        val sent = attempt.snapshotAtEncode.marker()
        val grant = s.granted.last().grant
        val wiresBefore = s.wires.size
        val view0 = h.view(row)
        val fetches = h.source.calls.size

        s.wire.deliver(s.ack(attempt.requestId, activeTopics, mapOf(KRX to "topic_unavailable")))
        s.step(1)
        assertEquals("$row (c) premise: the acknowledgement was applied", 1, s.acknowledgements.size)
        assertEquals(
            "$row (c) premise: it refused KRX as unavailable",
            mapOf(KRX to TopicRejectionReason.TOPIC_UNAVAILABLE), s.acknowledgements.single().rejected
        )
        val answers = listOf(
            "(a)" to TopicSnapshotOutcome.Dormant,
            "(b)" to TopicSnapshotOutcome.Degraded,
            "(e) 503" to TopicSnapshotOutcome.TemporarilyUnavailable,
            "(e) 403" to TopicSnapshotOutcome.Refused(
                AuthenticatedHttpFailure(403, Headers.headersOf(), byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP)
            )
        )
        answers.forEachIndexed { index, (label, outcome) ->
            if (index > 0) {
                val issued = s.bootstrapCalls.size
                s.coordinator.requestBootstrap(KRX)
                s.step(1)
                assertEquals("$row $label premise: asking again issued KRX once more", issued + 1, s.bootstrapCalls.size)
            }
            s.releaseBootstrap(KRX, outcome, "$row $label")
            s.step(1)
            assertTrue(
                "$row $label premise: the answer reached the session's undelivered hand-over",
                s.undelivered.any { it.second == KRX && it.third == outcome }
            )
        }
        s.releaseBootstrap(USD, UNKNOWN_TOPIC, "$row (d)")
        s.step(1)
        assertTrue(
            "$row (d) premise: the USD answer reached the session's undelivered hand-over",
            s.undelivered.any { it.second == USD && it.third == UNKNOWN_TOPIC }
        )
        val read = attempt.sendRead("$row contract(05-4):")

        assertEquals("$row: no entitlements query", fetches, h.source.calls.size)
        assertEquals("$row: the contest state is unchanged", view0.state, h.view(row).state)
        assertEquals("$row: rejectedPermit is unchanged", view0.rejectedPermit, h.view(row).rejectedPermit)
        assertEquals("$row: only (c) reserves", 1, s.issuer.reserved.size)
        val reserved = s.issuer.reserved.single()
        assertEquals("$row (c): the reason", listOf(TopicRejectionReason.TOPIC_UNAVAILABLE), reserved.reasons)
        assertTrue("$row (c): the PAC ended it", reserved.reservation.order !in h.coordinator.rejectionView(grant).pendingOrders)
        assertEquals("$row (c): the WS reservation carries the send-time marker (non-null)", sent, reserved.reservation.marker)
        assertEquals(
            "$row (c): the WS reservation's marker (epoch, permit) are those of the snapshot B returned in the attempt's send (same read)",
            read.marker(), reserved.reservation.marker
        )
        s.assertNonKrxKept(row, grant, wiresBefore)
    }

    /**
     * 05-7 CAP-R05 + scope r11:792. In three states — (i) HOLD (a second contradiction within 600 s), the socket dropped and its
     * reconnection's first subscribe carrying KRX; (ii) after the 05-1 input; (iii) after the 05-8 input — the KRX send still goes out
     * (the send predicate is the general judgement, §5C ③), the grant and fence stay, the in-flight USD bootstrap (held gate) completes and
     * applies, the KRX bootstrap's `useAdmitted()` is true, tether and USD frames move the store and prices, and no graph authority ends
     * (restatement of '`endGraphUse` 0': no AUTHORITY_ENDED/USE_WITHHELD offer). Note (design): a KRX send during HOLD is the α test
     * desired's result; 2b-2 re-agrees it when effective decides the desired KRX (D4 r7 3.6). Mutations: W-X (WS), U-X.
     * Windows: (i) HOLD at the send and through the checks; (ii) AWAITING after the WS refusal was handed over; (iii) AWAITING after the
     * REST unknown_topic was handed over — that hand-over is α behavior (§6.2 (나)), so (iii)'s window is not a premise: it is written
     * `contract(05-8):` and placed before the row contract (tests r2, E-18), and a session that never hands it over fails (iii) as a
     * contract failure. In (ii) and (iii) the KRX send under the withheld capability is a reconnection's first subscribe made at the
     * end of the state (g4-fix). 'A later non-KRX issue reaches the fake bootstrap' shows the issue loop still issues; it does not by
     * itself witness '`cancelBootstrapIssue` 0' (a W-X issue stop leaves KRX owed while USD, ranked ahead, still goes) — W-X is caught
     * by the KRX sends under HOLD and AWAITING.
     */
    @Test fun r05_7_capR05_holdAndKrxRefusalsKeepEverythingGeneral() = runVariants(
        "05-7 (i)" to { onRig { h, s -> holdState("05-7 (i)", h, s) } },
        "05-7 (ii)" to { onRig { h, s -> afterRefusal("05-7 (ii)", h, s, rest = false) } },
        "05-7 (iii)" to { onRig { h, s -> afterRefusal("05-7 (iii)", h, s, rest = true) } }
    )

    /** The checks every 05-7 state shares, run in that state. */
    private suspend fun TestScope.generalKept(row: String, h: KrxHarness, s: TopicSessionKrxHarness, grant: TopicGrantToken) {
        val usd = s.bootstrapOf(USD, row)
        assertTrue("$row premise: the USD bootstrap is still in flight, held before its exchange", usd.exchanges.isEmpty())
        val usdRate = 1_300.0 + minute
        s.releaseBootstrap(USD, s.delivered(usdFrame(usdRate, nextStamp())), row)
        s.step(1)
        assertEquals("$row: the USD bootstrap's exchange is admitted", listOf(true), usd.exchanges)
        assertTrue("$row: the USD bootstrap applies", usdRate in s.coordinator.rates.value.quotes.values.map { it.rate })
        val krxIssue = s.bootstrapOf(KRX, row)
        assertTrue("$row: the KRX bootstrap's useAdmitted() is true", krxIssue.useAdmitted())
        val tether = s.store.snapshot.stateFor(TETHER)
        val tetherRate = 1_400.0 + minute
        s.wire.deliver(tetherFrame(tetherRate, nextStamp()))
        val usdWsRate = 1_380.0 + minute
        s.wire.deliver(usdFrame(usdWsRate, nextStamp()))
        s.step(1)
        assertTrue("$row: a tether frame is received", s.store.snapshot.stateFor(TETHER).receiveGeneration > tether.receiveGeneration)
        assertTrue("$row: tether stays confirmed", s.store.snapshot.stateFor(TETHER).confirmed)
        val rates = s.coordinator.rates.value.quotes.values.map { it.rate }
        assertTrue("$row: the tether price is adopted ($rates)", tetherRate in rates)
        assertTrue("$row: the USD price is adopted ($rates)", usdWsRate in rates)
        assertTrue("$row: the session was handed no other grant: ${s.grantCalls}", s.granted.all { it.grant == grant })
        assertEquals("$row premise(A1): the issuer's grant is unchanged", grant, h.issueGrant())
        assertTrue(
            "$row: no graph use was withheld (restated endGraphUse 0)",
            s.authorityEnded(TopicGraphEventReason.USE_WITHHELD).isEmpty()
        )
        assertTrue("$row: no graph authority ended: ${s.authorityEndedAny().map { it.reason }}", s.authorityEndedAny().isEmpty())
    }

    /** A tether frame on the current socket, and the store's tether state after it (confirmed, receive generation > 0). */
    private fun TopicSessionKrxHarness.baselineTether(row: String): com.jay.fxi.domain.model.TopicSubscriptionState {
        wire.deliver(tetherFrame(1_400.0 + minute, nextStamp()))
        step(1)
        val state = store.snapshot.stateFor(TETHER)
        assertTrue("$row premise: tether is confirmed before the event", state.confirmed)
        assertTrue("$row premise: tether was received before the event", state.receiveGeneration > 0)
        return state
    }

    /** The store kept tether across the event: still confirmed, and its receive generation not reset. */
    private fun TopicSessionKrxHarness.assertTetherKept(row: String, before: com.jay.fxi.domain.model.TopicSubscriptionState) {
        val now = store.snapshot.stateFor(TETHER)
        assertTrue("$row: tether stays confirmed across the event", now.confirmed)
        assertTrue("$row: tether's receive generation is kept", now.receiveGeneration >= before.receiveGeneration)
    }

    private suspend fun TestScope.holdState(row: String, h: KrxHarness, s: TopicSessionKrxHarness) {
        val first = s.liveFirstKrxAttempt(row)
        s.wire.deliver(s.ack(first.requestId, listOf(TETHER, USD, KRX)))
        s.step(1)
        val grant = s.granted.last().grant
        val tether = s.baselineTether(row)
        h.rejectKrx(h.issueGrant())
        s.step(100)
        assertEquals("$row premise: the first contradiction opened p = 2", KrxContestPhase.OPEN, h.view(row).state.phase)
        h.rejectKrx(h.issueGrant())
        s.step(100)
        assertEquals("$row premise: the second contradiction within 600 s holds", KrxContestPhase.HOLD, h.view(row).state.phase)
        s.assertTetherKept("$row (hold, before the drop)", tether)
        val wiresBefore = s.wires.size
        s.wire.drop()
        s.step(2_500)
        assertEquals("$row premise: the session reconnected once during the hold", wiresBefore + 1, s.wires.size)
        assertEquals("$row premise: still HOLD at the reconnection", KrxContestPhase.HOLD, h.view(row).state.phase)
        val attemptsBefore = s.attempts.size
        s.openWire()
        assertEquals("$row premise: the reconnection's first subscribe was encoded", attemptsBefore + 1, s.attempts.size)
        val attempt = s.attempts.last()
        assertTrue("$row premise: it carries KRX", attempt.carriesKrx)
        val atSend = attempt.snapshotAtEncode
        assertTrue("$row premise(ON): krxPermitSeq ≥ 1 at the send (${atSend.krxPermitSeq})", atSend.krxPermitSeq >= 1)
        assertFalse("$row premise(ON): capabilityAllowed = false at the send (HOLD)", atSend.facts.capabilityAllowed)
        assertTrue("$row premise: tokenStanding at the send", atSend.facts.tokenStanding)
        assertTrue("$row premise: the user axis allows general use at the send", atSend.facts.userAllowed)
        assertEquals("$row premise(A1): the token is unchanged at the send", grant, atSend.facts.token)
        assertTrue("$row: the KRX send went out under the hold (no AccessWithheldDue)", attempt.wireAccepted)
        s.wire.deliver(s.ack(attempt.requestId, activeTopics, krxRefused))
        s.step(1)
        assertTrue("$row: tether confirmed again on the new connection", s.store.snapshot.stateFor(TETHER).confirmed)
        assertTrue(
            "$row: tether's receive generation is not reset by the reconnection",
            s.store.snapshot.stateFor(TETHER).receiveGeneration >= tether.receiveGeneration
        )
        generalKept(row, h, s, grant)
        assertEquals("$row premise: the checks ran inside the hold", KrxContestPhase.HOLD, h.view(row).state.phase)
    }

    /**
     * (ii) after the 05-1 input, (iii) after the 05-8 input. Both windows are the state after the KRX refusal was handed to the issuer
     * (AWAITING, its query held). (iii)'s window needs the α REST hand-over (§6.2 (나)), so in (iii) every assertion of that window —
     * the hand-over itself, AWAITING and the withheld capability, at the refusal and again at the reconnection's send — is written
     * `contract(05-8):` (tests r2, E-18; in (ii) the same window holds at HEAD and stays `premise`), and the window comes before the
     * row's own checks of that state. Each state ends with a KRX send inside its window (g4-fix): the socket drops and the
     * reconnection's first subscribe, carrying KRX, still goes out.
     */
    private suspend fun TestScope.afterRefusal(row: String, h: KrxHarness, s: TopicSessionKrxHarness, rest: Boolean) {
        // The labels of the AWAITING window: α-dependent in (iii), a HEAD premise in (ii).
        val window = if (rest) "$row contract(05-8):" else "$row premise:"
        val windowOn = if (rest) "$row contract(05-8):" else "$row premise(ON):"
        val attempt = s.liveFirstKrxAttempt(row)
        val grant = s.granted.last().grant
        h.holdNextFetch()
        var tether: com.jay.fxi.domain.model.TopicSubscriptionState? = null
        if (rest) {
            s.wire.deliver(s.ack(attempt.requestId, listOf(TETHER, USD, KRX)))
            s.step(1)
            tether = s.baselineTether(row)
            s.releaseBootstrap(KRX, UNKNOWN_TOPIC, row)
            s.step(1)
            assertTrue(
                "$row premise: the KRX unknown_topic reached the session's undelivered hand-over",
                s.undelivered.any { it.second == KRX && it.third == UNKNOWN_TOPIC }
            )
            assertEquals("$row contract(05-8): the REST refusal was handed to the issuer and processed", 1, s.issuer.processed.size)
        } else {
            s.wire.deliver(s.ack(attempt.requestId, activeTopics, krxRefused))
            s.step(1)
            assertEquals("$row premise: the KRX refusal was reported and processed (05-1)", 1, s.issuer.processed.size)
        }
        val now = h.coordinator.accessSnapshot
        assertEquals("$window the issuer is AWAITING the held query", KrxContestPhase.AWAITING, h.view(row).state.phase)
        assertTrue("$row premise(ON): krxPermitSeq ≥ 1 (${now.krxPermitSeq})", now.krxPermitSeq >= 1)
        assertFalse("$windowOn capabilityAllowed = false after the handover", now.facts.capabilityAllowed)
        assertTrue("$row premise: tokenStanding (general use allowed)", now.facts.tokenStanding)
        assertTrue("$row premise: the user axis allows general use", now.facts.userAllowed)
        if (rest) {
            s.assertTetherKept(row, checkNotNull(tether))
        } else {
            assertTrue("$row: tether stays confirmed after the refusal", s.store.snapshot.stateFor(TETHER).confirmed)
            assertTrue("$row: USD stays confirmed after the refusal", s.store.snapshot.stateFor(USD).confirmed)
        }
        assertEquals("$row: no reconnection", 1, s.wires.size)
        assertFalse("$row: the socket was not ended", s.wire.cancelled)
        generalKept(row, h, s, grant)
        val issued = s.bootstrapCalls.size
        s.coordinator.requestBootstrap(USD)
        s.step(1)
        assertEquals(
            "$row: a later non-KRX issue still reaches the fake bootstrap (the issue loop still issues; not a witness of " +
                "'cancelBootstrapIssue 0')",
            issued + 1, s.bootstrapCalls.size
        )
        // A KRX send inside this AWAITING window, the (i) pattern: the socket drops and the reconnection's first subscribe carries KRX.
        s.wire.drop()
        s.step(2_500)
        assertEquals("$row premise: the session reconnected once", 2, s.wires.size)
        val attemptsBefore = s.attempts.size
        s.openWire()
        assertEquals("$row premise: the reconnection's first subscribe was encoded", attemptsBefore + 1, s.attempts.size)
        val again = s.attempts.last()
        assertTrue("$row premise: it carries KRX", again.carriesKrx)
        assertEquals("$window still AWAITING at the send", KrxContestPhase.AWAITING, h.view(row).state.phase)
        val atSend = again.snapshotAtEncode
        assertTrue("$row premise(ON): krxPermitSeq ≥ 1 at the send (${atSend.krxPermitSeq})", atSend.krxPermitSeq >= 1)
        assertFalse("$windowOn capabilityAllowed = false at the send (AWAITING)", atSend.facts.capabilityAllowed)
        assertTrue("$row premise: tokenStanding at the send (general use allowed)", atSend.facts.tokenStanding)
        assertTrue("$row premise: the user axis allows general use at the send", atSend.facts.userAllowed)
        assertTrue("$row: the KRX send went out while AWAITING (no AccessWithheldDue)", again.wireAccepted)
    }

    /**
     * 05-11 ON, p = 0: a WS send, then the first permit. The first connection's subscribe (KRX) → ack held → StableActive(true) →
     * OPEN(p = 1) → the ack refuses KRX. The send goes out; the marker is (that epoch, 0) non-null; the ack is judged as the earlier
     * permit and nothing moves. Mutations: K-NUL (p = 0 marker dropped → handed over under p 1, B4), W-X.
     */
    @Test fun r05_11_onPermitZero_aSendBeforeTheFirstPermit_isNotHandedOver() = onRig(prepare = { h ->
        h.source.next = { krxFalse }
        h.coordinator.onIdentityChanged(AuthIdentityFence(KrxHarnessDefaults.OWNER, 1L)).applied()
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        h.test.settleKrx()
        assertEquals("05-11 premise: premium confirmed", PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals("05-11 premise: KRX hidden", KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        h.store.record = h.store.record.copy(mayContainPremiumData = true)
    }) { h, s ->
        val row = "05-11"
        s.goLive()
        assertEquals("$row premise: the deliverer granted one connection", 1, s.wires.size)
        s.openWire()
        val attempt = s.lastKrxAttempt(row)
        assertTrue("$row premise: the KRX attempt reached the socket", attempt.reachedWire)
        val atSend = attempt.snapshotAtEncode
        assertEquals("$row premise: krxPermitSeq = 0 at the send", 0L, atSend.krxPermitSeq)
        assertFalse("$row premise: capabilityAllowed = false at the send", atSend.facts.capabilityAllowed)
        assertTrue("$row premise: general use allowed at the send (standing)", atSend.facts.tokenStanding)
        assertTrue("$row premise: general use allowed at the send (user axis)", atSend.facts.userAllowed)
        val sent = atSend.marker()

        h.source.next = { active }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        s.step(100)
        assertEquals("$row premise: OPEN after the first permit", KrxContestPhase.OPEN, h.view(row).state.phase)
        assertEquals("$row premise: p = 1", 1L, h.view(row).state.permitSeq)
        assertEquals("$row premise: rejectedPermit = 0", 0L, h.view(row).rejectedPermit)
        assertEquals("$row premise: the ack is still owed to that attempt", 0, s.acknowledgements.size)
        h.holdNextFetch()
        val fetches = h.source.calls.size

        s.wire.deliver(s.ack(attempt.requestId, activeTopics, krxRefused))
        s.step(1)

        assertEquals("$row premise: the acknowledgement was applied", 1, s.acknowledgements.size)
        s.reportedAndProcessed(row, 1)
        val read = attempt.sendRead("$row contract(05-4):")
        assertTrue("$row: the KRX send went out before the first permit (no AccessWithheldDue)", attempt.wireAccepted)
        assertEquals("$row: the marker channel is called once", 1, s.markerRejections.size)
        assertEquals("$row: the marker is (send epoch, 0), non-null", sent, s.markerRejections.single().marker)
        assertEquals(
            "$row: the carried marker's (epoch, permit) are those of the snapshot B returned in the attempt's send (same read)",
            read.marker(), s.markerRejections.single().marker
        )
        assertEquals("$row: the ledger reservation carries (send epoch, 0)", sent, s.issuer.reserved.single().reservation.marker)
        assertEquals("$row: still OPEN", KrxContestPhase.OPEN, h.view(row).state.phase)
        assertEquals("$row: p stays 1", 1L, h.view(row).state.permitSeq)
        assertEquals("$row: rejectedPermit stays 0", 0L, h.view(row).rejectedPermit)
        assertEquals("$row: no entitlements query", fetches, h.source.calls.size)
    }

    /**
     * 05-24 a session built by the PUBLIC constructor (tests r2, E-07; stub r2 internal-constructor KDoc D-13, design r6 candidate):
     * it has no send read and no marker channel. Test desired (KRX included), ON PAC, OPEN p = 1. The KRX attempt takes the HEAD path:
     * its send is judged by `authority.admits` alone (one read of supplier A between its encode and its socket send), and the KRX
     * refusal goes through the public two-argument `onRejected` only (the issuer's two-argument reservation once, no three-argument
     * one, the ledger marker null). That no reader exists is the setup — supplier B is never handed to the session, so its zero reads
     * are a premise, not evidence. HEAD behaviour: GREEN on the stub as well (h). Mutations: the reader-less KRX attempt withheld or
     * judged other than by `authority.admits`; its refusal dropped or reported other than on the public two-argument form (the silent
     * branch E-07 locks).
     *
     * Two halves (tests r3, Codex tests r2 REVISE 1): this test is 05-24's **WebSocket half**. Its REST half — the KRX REST issue's
     * lifetime from `authority.acquire` (supplier A), the Unsupported answer reaching the undelivered path, no issuer hand-over or
     * reservation, and the public undelivered callback once with HEAD arguments — runs on its own, independent rig as
     * `TopicSessionKrxRestMarkerTest.r05_24_rest_publicConstructor_aKrxIssueKeepsTheHeadPath`, so its outcome is not mixed with
     * this half's WebSocket reservation. The `bootstrapOf(KRX)` line below only shows the plan issued KRX; the REST half judges it.
     */
    @Test fun r05_24_publicConstructor_aKrxAttemptKeepsTheHeadPath() = onRig(markerChannel = false, publicConstructor = true) { h, s ->
        val row = "05-24"
        assertTrue("$row premise: the test desired carries KRX", KRX in TEST_DESIRED)
        s.goLive()
        assertEquals("$row premise: the deliverer granted one connection", 1, s.wires.size)
        s.openWire()
        val attempt = s.lastKrxAttempt(row)
        assertOnAllowedAt(row, "the send", attempt.snapshotAtEncode)
        assertEquals("$row premise(ON): the send is under permit 1", 1L, attempt.snapshotAtEncode.krxPermitSeq)
        s.bootstrapOf(KRX, row)
        assertEquals("$row premise: supplier B was never handed to the session (no reader exists; it is not read)", 0, s.readerReads)
        val grant = s.granted.last().grant

        assertTrue("$row: the KRX attempt reached the socket and was sent (the HEAD path: no AccessWithheldDue)", attempt.wireAccepted)
        assertEquals("$row: the KRX attempt's send is judged by authority.admits (one supplier-A read in the send)", 1, attempt.authorityReadsInSend)
        h.holdNextFetch()

        s.wire.deliver(s.ack(attempt.requestId, activeTopics, krxRefused))
        s.step(1)

        assertEquals("$row premise: the acknowledgement was applied", 1, s.acknowledgements.size)
        assertEquals(
            "$row premise: the acknowledgement refused KRX",
            mapOf(KRX to TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED), s.acknowledgements.single().rejected
        )
        s.reportedAndProcessed(row, 1)
        assertEquals("$row: the public two-argument onRejected is called once", 1, s.publicRejections.size)
        assertEquals("$row: for the session's grant", grant, s.publicRejections.single().first.grant)
        assertEquals(
            "$row: with the KRX refusal",
            mapOf(KRX to TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED), s.publicRejections.single().second
        )
        assertEquals("$row: the issuer's two-argument reservation is made once", 1, s.issuer.twoArgument.size)
        assertEquals("$row: no three-argument reservation", 0, s.issuer.threeArgument.size)
        assertNull("$row: the ledger reservation carries no marker (the HEAD two-argument path)", s.issuer.reserved.single().reservation.marker)
    }

    /**
     * 05-25 WebSocket half (tests r3, Codex tests r2 E-35: 'reader absence and marker-channel absence are distinct'). The session is
     * built with its INTERNAL constructor and `krxSendReader = null` named (stub r3: nullable, no default) — the reader-less session —
     * with the marker channel present or absent. Test desired (KRX included), ON PAC, OPEN p = 1. Each channel setting is its own
     * test on its own rig, so the RED one does not hide the other's result:
     *
     * - **channel present** ([r05_25_ws_readerNull_channelPresent_refusalGoesToTheChannelWithNullMarker]): the KRX attempt is
     *   judged by `authority` alone (sent; one supplier-A read in its send — h), and its KRX refusal goes to the marker channel once
     *   with marker = null (no reader, so nothing captured; D-8 as E-35 keeps it), never to the public two-argument `onRejected`; the
     *   refusal reaches the issuer once and the ledger marker is null. On the stub the session never calls the channel, so the
     *   routing clause is RED (α D-8 routing). It is the row contract and comes after the window (the acknowledgement was applied and
     *   refused KRX). The 'judged by authority' clauses before it are HEAD and run on the stub; the clauses after it do not run there
     *   (the E-18 side effect: no stub evidence for them).
     * - **channel absent** ([r05_25_ws_readerNull_channelAbsent_refusalKeepsThePublicForm]): the same attempt, and the refusal goes to
     *   the public two-argument `onRejected` once (the issuer's two-argument reservation once, no three-argument one, the ledger
     *   marker null) — 05-24's WebSocket path. GREEN on the stub (h).
     *
     * Supplier B exists in the harness but is never handed to the session, so its zero reads are a premise by construction, not
     * evidence. 05-25's REST half is in `TopicSessionKrxRestMarkerTest` (`r05_25_rest_readerNull_…`). Mutations: a reader-less KRX
     * attempt withheld or judged other than by `authority.admits`; with a channel, its refusal sent on the public form instead or as
     * well, or with a non-null marker; without one, dropped or sent anywhere but the public two-argument form.
     */
    @Test fun r05_25_ws_readerNull_channelPresent_refusalGoesToTheChannelWithNullMarker() =
        onRig(markerChannel = true, sendReader = false) { h, s ->
            readerlessWs("05-25 [WS, reader null, channel present]", h, s, channel = true)
        }

    /** 05-25 WebSocket half, channel absent: see [r05_25_ws_readerNull_channelPresent_refusalGoesToTheChannelWithNullMarker]. */
    @Test fun r05_25_ws_readerNull_channelAbsent_refusalKeepsThePublicForm() =
        onRig(markerChannel = false, sendReader = false) { h, s ->
            readerlessWs("05-25 [WS, reader null, channel absent]", h, s, channel = false)
        }

    /** 05-25 WebSocket half on a reader-less session ([channel]: whether the marker channel is wired). */
    private fun readerlessWs(row: String, h: KrxHarness, s: TopicSessionKrxHarness, channel: Boolean) {
        assertTrue("$row premise: the test desired carries KRX", KRX in TEST_DESIRED)
        s.goLive()
        assertEquals("$row premise: the deliverer granted one connection", 1, s.wires.size)
        s.openWire()
        val attempt = s.lastKrxAttempt(row)
        assertOnAllowedAt(row, "the send", attempt.snapshotAtEncode)
        assertEquals("$row premise(ON): the send is under permit 1", 1L, attempt.snapshotAtEncode.krxPermitSeq)
        assertEquals(
            "$row premise: supplier B was never handed to the session (krxSendReader = null by construction; it is not read)",
            0, s.readerReads
        )
        val grant = s.granted.last().grant

        assertTrue(
            "$row: the KRX attempt reached the socket and was sent (judged by authority alone: no AccessWithheldDue)",
            attempt.wireAccepted
        )
        assertEquals(
            "$row: the KRX attempt's send is judged by authority.admits alone (one supplier-A read in the send)",
            1, attempt.authorityReadsInSend
        )
        h.holdNextFetch()

        s.wire.deliver(s.ack(attempt.requestId, activeTopics, krxRefused))
        s.step(1)

        // The window: the refusal this row routes was produced and applied (HEAD), before any routing or negative assertion.
        val refusal = mapOf(KRX to TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED)
        assertEquals("$row premise: the acknowledgement was applied", 1, s.acknowledgements.size)
        assertEquals("$row premise: the acknowledgement refused KRX", refusal, s.acknowledgements.single().rejected)
        if (channel) {
            // The row contract (α D-8 routing): RED on the stub, whose session never calls the channel.
            assertEquals(
                "$row: the KRX refusal goes to the marker channel once (D-8: a wired channel takes the WS refusal)",
                1, s.markerRejections.size
            )
            val routed = s.markerRejections.single()
            assertNull("$row: with marker = null (no reader, so nothing was captured)", routed.marker)
            assertEquals("$row: for the session's grant", grant, routed.owner.grant)
            assertEquals("$row: with the KRX refusal", refusal, routed.reasons)
            assertEquals("$row: the public two-argument onRejected is not called", 0, s.publicRejections.size)
        } else {
            assertEquals("$row: the public two-argument onRejected is called once", 1, s.publicRejections.size)
            assertEquals("$row: for the session's grant", grant, s.publicRejections.single().first.grant)
            assertEquals("$row: with the KRX refusal", refusal, s.publicRejections.single().second)
        }
        s.reportedAndProcessed(row, 1)
        if (!channel) {
            assertEquals("$row: the issuer's two-argument reservation is made once", 1, s.issuer.twoArgument.size)
            assertEquals("$row: no three-argument reservation", 0, s.issuer.threeArgument.size)
        }
        assertNull("$row: the ledger reservation carries no marker", s.issuer.reserved.single().reservation.marker)
    }

    // ---- OFF and the operating DESIRED ----------------------------------------------------------------------------------------

    private class OffRun(
        val krxFlow: List<KrxCapabilityState>,
        val traces: List<String>,
        val published: List<TopicAccessSnapshot>,
        val sendSnapshot: TopicAccessSnapshot,
        val markers: List<KrxSendMarker?>,
        val threeArgument: List<KrxSendMarker?>,
        val ledgerMarkers: List<KrxSendMarker?>,
        val publicCount: Int
    )

    private fun offRun(markerChannel: Boolean): OffRun {
        var out: OffRun? = null
        offRig(markerChannel = markerChannel, prepare = {}) { h, s ->
            val published = h.coordinator.observePublications()
            // The whole `krx` flow, not only its value at the checkpoints: a short flip between two traceLines would show here.
            val krxFlow = mutableListOf<KrxCapabilityState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.coordinator.krx.collect { krxFlow += it } }
            val traces = mutableListOf<String>()
            h.grantedKrxVisible()
            traces += h.traceLine("granted")
            s.goLive()
            traces += h.traceLine("live")
            s.openWire()
            traces += h.traceLine("sent")
            val attempt = s.lastKrxAttempt("05-O1")
            s.wire.deliver(s.ack(attempt.requestId, activeTopics, krxRefused))
            s.step(1)
            traces += h.traceLine("refused")
            s.step(6_000)
            traces += h.traceLine("settled")
            out = OffRun(
                krxFlow.toList(), traces, published.toList(), attempt.snapshotAtEncode, s.markerRejections.map { it.marker },
                s.issuer.threeArgument.map { it.marker }, s.issuer.reserved.map { it.reservation.marker }, s.publicRejections.size
            )
        }
        return checkNotNull(out)
    }

    /**
     * 05-O1 WS OFF (§2 ④ (i)). OFF PAC, test desired, 05-1's input; the same input run through the two-argument path (no marker channel)
     * is the comparison. PAC decisions (traceLine at 5 checkpoints), the whole `krx` flow (collected, g4-fix), the published snapshots
     * and traceLine lines are the same; no permit guard on the WS
     * marker — off, the marker is (e, 0) non-null (D4 r7:174). Mutation: reading the marker in a decision while off. (K-NUL shows no
     * difference off; 05-11 catches it.)
     *
     * Order (tests r2, E-34, the 06-O1 order of B-29): the 'the same' comparisons are no-difference assertions, so before them the row
     * first asserts its window — as far as the session's surface shows, the marker run really took the marker path: the marker channel,
     * the issuer's three-argument reservation and the ledger reservation each carry (e, 0). Without it, a session that never uses the
     * marker path makes both runs the same two-argument run and the comparisons pass vacuously (as on the stub).
     */
    @Test fun r05_O1_wsOff_markerRunEqualsTheTwoArgumentRun() {
        val withChannel = offRun(markerChannel = true)
        val twoArgument = offRun(markerChannel = false)
        assertTrue("05-O1 premise(OFF): published snapshots were observed", withChannel.published.isNotEmpty())
        assertTrue(
            "05-O1 premise(OFF): krxPermitSeq = 0 at every observed snapshot",
            (withChannel.published + twoArgument.published + withChannel.sendSnapshot + twoArgument.sendSnapshot)
                .all { it.krxPermitSeq == 0L }
        )
        assertEquals("05-O1 premise: the two-argument run reported on the public path", 1, twoArgument.publicCount)
        assertTrue("05-O1 premise: the krx flow was observed", withChannel.krxFlow.isNotEmpty())
        val sent = withChannel.sendSnapshot.marker()
        assertNotNull("05-O1 premise: the send-time record has a capability epoch", sent.krxCapabilityEpoch)
        val offMarker: KrxSendMarker? = KrxSendMarker(sent.krxCapabilityEpoch, 0L)
        // The window (E-34): the marker run reached the marker channel, the three-argument reservation and the ledger marker.
        assertEquals("05-O1: off, the marker channel carries (e, 0) non-null", listOf(offMarker), withChannel.markers)
        assertEquals("05-O1: the issuer's three-argument reservation carries (e, 0)", listOf(offMarker), withChannel.threeArgument)
        assertEquals("05-O1: the ledger reservation carries (e, 0)", listOf(offMarker), withChannel.ledgerMarkers)
        assertEquals("05-O1: the krx flow is the same", twoArgument.krxFlow, withChannel.krxFlow)
        assertEquals("05-O1: traceLine lines are the same", twoArgument.traces, withChannel.traces)
        assertEquals("05-O1: the published snapshots are the same", twoArgument.published, withChannel.published)
    }

    private class OperatingRun(
        val sends: List<List<String>>,
        val authorityReads: Int,
        val readerReads: Int,
        val markers: List<KrxSendMarker?>,
        val markerCount: Int,
        val publicCount: Int,
        val ledger: List<Triple<Long, List<TopicRejectionReason>, KrxSendMarker?>>,
        val views: List<TopicRejectionView>,
        val trace: String,
        val attemptTopics: List<List<String>>,
        /** Every snapshot the issuer published from just before goLive, and each attempt's encode-time snapshot. */
        val observed: List<TopicAccessSnapshot>
    )

    private suspend fun TestScope.operating(row: String, h: KrxHarness, s: TopicSessionKrxHarness): OperatingRun {
        val published = h.coordinator.observePublications()
        s.goLive()
        assertEquals("$row premise: the deliverer granted one connection", 1, s.wires.size)
        s.openWire()
        val attempt = s.attempts.lastOrNull() ?: throw AssertionError("$row premise: no subscribe was sent")
        assertTrue("$row premise: the operating DESIRED carries no KRX", s.attempts.none { it.carriesKrx })
        assertTrue("$row premise: the attempt was sent", attempt.wireAccepted)
        val jpy = "fx:jpy-krw"
        s.wire.deliver(s.ack(attempt.requestId, (TopicCatalogue.DESIRED - jpy).toList(), mapOf(jpy to "premium_required")))
        s.step(1)
        s.step(6_000)
        assertEquals("$row premise: the acknowledgement was applied", 1, s.acknowledgements.size)
        return OperatingRun(
            sends = s.wires.map { it.sent.toList() },
            authorityReads = s.authorityReads,
            readerReads = s.readerReads,
            markers = s.markerRejections.map { it.marker },
            markerCount = s.markerRejections.size,
            publicCount = s.publicRejections.size,
            ledger = s.issuer.reserved.map { Triple(it.reservation.order, it.reservation.reasons, it.reservation.marker) },
            views = s.issuer.reserved.map { h.coordinator.rejectionView(it.grant) },
            trace = h.traceLine("operating"),
            attemptTopics = s.attempts.map { it.topics },
            observed = published.toList() + s.attempts.map { it.snapshotAtEncode }
        )
    }

    /**
     * 05-O3 the operating DESIRED (off and on). desired default (no KRX), marker channel wired, against the same run on the HEAD path
     * (no marker channel). Sends, `authority.admits` reads and snapshot reads are the HEAD path's, and the send read is never called
     * (r3 §3: a KRX-less attempt keeps the existing call). (D-8) The refusal goes to onRejectedWithMarker(marker = null) once, the public
     * two-argument form not at all, and the ledger is the HEAD two-argument path's. Mutations: a KRX-less attempt sent through the seam;
     * a marker on a KRX-less attempt.
     *
     * The 'HEAD path' run is the same α session built without the marker channel, so it shares the α code for KRX-less sends; the
     * paired comparison alone would let a change in that shared code move both runs together. g4-fix pins the absolute counts too
     * ([HEAD_O3_SENDS], [HEAD_O3_AUTHORITY_READS]), and the OFF variant asserts premise(OFF) over every snapshot published from just
     * before goLive and every attempt's encode-time snapshot.
     *
     * Where the literals come from (tests r2, E-19): they were measured on the S6-2b-1b-α stub r2's KRX-less path, NOT on de50885 —
     * this test builds the session with the internal constructor, so it does not compile at de50885 and cannot be re-measured there.
     * They stand for HEAD only by the stub diff: on TopicSessionCoordinator.kt the stub adds the internal constructor, two fields it
     * sets and nothing reads, and `TopicCatalogue.carriesKrx`, which nothing calls; SnapshotTopicUseAuthority's `acquire`/`admits`
     * are HEAD's. HEAD-equivalent by that diff, not measured at HEAD.
     */
    @Test fun r05_O3_operatingDesired_offAndOn_keepTheHeadPath() {
        val pairs = listOf(false, true).map { on ->
            val label = if (on) "05-O3 ON" else "05-O3 OFF"
            var wired: OperatingRun? = null
            var head: OperatingRun? = null
            if (on) {
                onRig(desired = TopicCatalogue.DESIRED, markerChannel = true) { h, s -> wired = operating(label, h, s) }
                onRig(desired = TopicCatalogue.DESIRED, markerChannel = false) { h, s -> head = operating(label, h, s) }
            } else {
                offRig(desired = TopicCatalogue.DESIRED, markerChannel = true) { h, s -> wired = operating(label, h, s) }
                offRig(desired = TopicCatalogue.DESIRED, markerChannel = false) { h, s -> head = operating(label, h, s) }
            }
            Triple(label, checkNotNull(wired), checkNotNull(head))
        }
        // The HEAD-path halves first, off and on, then the marker channel's own (D-8).
        for ((label, a, b) in pairs) {
            if (label.endsWith("OFF")) {
                val observed = a.observed + b.observed
                assertTrue("$label premise(OFF): published snapshots were observed", a.observed.size > a.attemptTopics.size)
                assertTrue(
                    "$label premise(OFF): krxPermitSeq = 0 at every observed snapshot (${observed.map { it.krxPermitSeq }.distinct()})",
                    observed.all { it.krxPermitSeq == 0L }
                )
            }
            assertEquals("$label premise: the HEAD path reported the refusal on the public form once", 1, b.publicCount)
            assertEquals("$label premise: the same attempts", b.attemptTopics, a.attemptTopics)
            assertEquals("$label: the sends are the HEAD path's", b.sends, a.sends)
            assertEquals("$label: authority.admits reads are the HEAD path's", b.authorityReads, a.authorityReads)
            assertEquals("$label: snapshot reads are the HEAD path's", b.authorityReads + b.readerReads, a.authorityReads + a.readerReads)
            assertEquals("$label: the send read is never called", 0, a.readerReads)
            // Absolute HEAD counts, so a change in code the two runs share cannot move both together and still pass (g4-fix).
            assertEquals("$label: the sends = HEAD literal", HEAD_O3_SENDS, a.sends)
            assertEquals("$label: the HEAD-path run's sends = HEAD literal", HEAD_O3_SENDS, b.sends)
            assertEquals("$label: authority reads = HEAD literal", HEAD_O3_AUTHORITY_READS, a.authorityReads)
            assertEquals("$label: the HEAD-path run's authority reads = HEAD literal", HEAD_O3_AUTHORITY_READS, b.authorityReads)
            assertEquals("$label: the HEAD-path run never calls the send read either", 0, b.readerReads)
            assertEquals("$label: the ledger is the HEAD two-argument path's", b.ledger, a.ledger)
            assertEquals("$label: the rejection views are the HEAD path's", b.views, a.views)
            assertEquals("$label: the issuer's trace is the HEAD path's", b.trace, a.trace)
        }
        for ((label, a, _) in pairs) {
            assertEquals("$label: the refusal goes to onRejectedWithMarker once", 1, a.markerCount)
            assertEquals("$label: with marker = null", listOf<KrxSendMarker?>(null), a.markers)
            assertEquals("$label: the public two-argument form is not called", 0, a.publicCount)
        }
    }

    private companion object {
        /** Past the H1′ demand's floor (PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS = 5 s), so its query starts. */
        const val H1_PRIME_FLOOR_STEP = 5_100L

        /**
         * 05-O3's HEAD literals, OFF and ON alike: the sends on each socket and the reads of supplier A (`acquire` and `admits` of the
         * session's authority) over `operating`. Measured on the S6-2b-1b-α stub r2's KRX-less path (2026-10-24, g4-fix) — NOT measured
         * on de50885, where this test does not compile (it uses the internal constructor) (tests r2, E-19). HEAD-equivalent by the stub
         * diff only: stub TopicSessionCoordinator.kt sha256 80f703b36b4775fb1dc3a4093f93a89898f91f3f5b4476065c04fa8dc021ea5a adds the
         * internal constructor, two fields it sets and nothing reads, and `TopicCatalogue.carriesKrx`, which nothing calls;
         * SnapshotTopicUseAuthority `acquire`/`admits` are HEAD's.
         */
        val HEAD_O3_SENDS: List<List<String>> = listOf(listOf("encoded-r1"))
        const val HEAD_O3_AUTHORITY_READS = 20
    }
}
