package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityFence
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
import com.jay.fxi.data.entitlements.acquireUse
import com.jay.fxi.data.entitlements.admitsUse
import com.jay.fxi.data.entitlements.applied
import com.jay.fxi.data.entitlements.checkedOnTest
import com.jay.fxi.data.entitlements.grantedKrxVisible
import com.jay.fxi.data.entitlements.issueGrant
import com.jay.fxi.data.entitlements.krxHarness
import com.jay.fxi.data.entitlements.observePublications
import com.jay.fxi.data.entitlements.recheckDemandSnapshot
import com.jay.fxi.data.entitlements.rejectKrx
import com.jay.fxi.data.entitlements.settleKrx
import com.jay.fxi.data.entitlements.traceLine
import com.jay.fxi.data.entitlements.undecidedRejection
import com.jay.fxi.data.remote.TopicSessionKrxHarness.Companion.KRX
import com.jay.fxi.data.remote.TopicSessionKrxHarness.Companion.TETHER
import com.jay.fxi.data.remote.TopicSessionKrxHarness.Companion.TEST_DESIRED
import com.jay.fxi.data.remote.TopicSessionKrxHarness.Companion.UNKNOWN_TOPIC
import com.jay.fxi.data.remote.TopicSessionKrxHarness.Companion.USD
import com.jay.fxi.data.remote.TopicSessionKrxHarness.Companion.tetherFrame
import com.jay.fxi.data.remote.TopicSessionKrxHarness.Companion.usdFrame
import com.jay.fxi.domain.model.TopicRejectionReason
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6-2b-1b-α contract tests r1 — TA-05 REST branch, `TopicSessionKrxRestMarkerTest` (design r5 §13 T.6 'REST 갈래' and the REST row
 * of 'OFF·운영 DESIRED'; §5B issue-time capture (OD-B05 (a)); §6.2 (나) the REST hand-over in the BootstrapAnswered branch; §6.4
 * OD-B09; §6.5 OD-B10; §6.6 CX-10 (가); §5B.5 W-X). A sibling of the WS class `TopicSessionKrxSendMarkerTest` (same TA-05 row set,
 * same S-3 harness), kept apart only for size.
 *
 * Rows covered: 05-8, 05-9, 05-10, 05-12, 05-13, 05-14, 05-15, 05-16, 05-17, 05-18, 05-19, 05-20, 05-21, 05-22, 05-23, 05-24 (REST
 * half), 05-25 (REST half), 05-O2. The WebSocket halves of 05-24 and 05-25 are in the WS class.
 *
 * Conventions (T.0): `premise:` holds at HEAD. `premise(ON):` = krxPermitSeq ≥ 1 at the named snapshot; `capabilityAllowed = true` is
 * asserted separately where a KRX issue is made while allowed, and `capabilityAllowed = false` with general use allowed where an
 * issue is made under H1′ or HOLD or before the first permit. `premise(OFF):` = krxPermitSeq = 0 at every observed snapshot.
 * `contract(<row>):` (tests r2, E-18) = a precondition that rests on α behaviour — another row's contract — placed before the row
 * contract, so on the stub the row fails as a contract failure, not on a premise. Every ON PAC is `CHECKED_ON` inside `checkedOnTest`
 * (S-4); the OFF PAC is `HEAD_FACTORY` (form (나)). Every assertion names its row. Before a negative assertion the row first asserts
 * that the answer it judges was produced by the fake and reached the session.
 *
 * The issue-time snapshot of a KRX issue is [TopicSessionKrxHarness.BootstrapCall.snapshotAtCall]: the harness's capture of the
 * issuer's published snapshot when the fake bootstrap began, in the same loop turn as the issue and with no issuer event in between
 * (every row holds the issuer still across that instant). It gives the premises and the issue-time value a row expects, that
 * snapshot's `(recordFence.krxCapabilityEpoch, krxPermitSeq)`. It is not what the session read: 'the forwarded marker comes from the
 * same read' (tests r2, Codex tests r1 fix 2, E-17) is judged against what supplier B actually returned for that issue
 * ([TopicSessionKrxHarness.BootstrapCall.readerSnapshotsAtIssue], recorded by S-3 and claimed by the KRX call that next entered the
 * fake; tests r5: only B's returns that S-3 attributes by call stack to the session's REST issue path are claimed — a read made anywhere
 * else is unattributed, never counted as an issue read) — B returned exactly one snapshot for the issue (05-16's contract,
 * `contract(05-16):` in other rows) and the forwarded marker's (epoch, permit) are that snapshot's. The marker value the send-read
 * seam itself returns is evidenced only by 04-O1, 11-4 and 11-5, which observe the marker the seam returns (04-O1 and 11-4 call it;
 * 11-5 records, through a delegating wrapper, what a real session's `acquireWithKrx` call returned); the rows here see a marker only
 * as the session forwards it, or — 05-O2, where nothing is forwarded — not at all.
 *
 * Observation is the S-3 harness's only: recording issuer (S-5), ledger reservation marker, `krxContestView`, `rejectionView`, the
 * counting suppliers and the snapshots supplier B returned, the fake bootstrap, the session's public undelivered hand-over, the
 * recording graph sink. No observer is added to the session (KW04).
 *
 * Restatements (design text → observable; reported as deviations): 05-22 '채널 호출 0, 그 답의 표지 null' → the USD issue makes 0 calls
 * on the krxSendReader supplier and the marker channel is called 0 times (the marker lives in the private BootstrapAnswered).
 * 05-O2 '표지 = (epoch, 0)' → B was read once at the KRX issue, and the snapshot B supplied has epoch/permit equal to the expected
 * (e, 0) (g4-fix; judged last; tests r2 narrowed the claim: the (e, 0) compared is the test helper's value over that snapshot, not the
 * α marker itself, which off is not forwarded and so not observed here). 05-16 → one call on the reader's
 * supplier per logical issue payload (α-2: validation rechecks counted separately), and marker/lifetime come from that payload; the lifetime's source is told apart at the
 * second issue, where A disagrees with B (g4-fix). 05-23 '`endGraphAuthority` 0' → no AUTHORITY_ENDED continuity offer;
 * '`cancelBootstrapIssue` 0' has no direct witness (W-X is caught by the KRX issue under HOLD and by the KRX count staying put across
 * the USD request). 05-10 → the window premise 'no query started and the recheck demand unchanged' is added before the order-sequence
 * assertion.
 *
 * Mutation classification notes (g4-fix, verifier P2; EQUIV classification still needs Codex agreement per T.0, and E-13 holds the
 * candidates below unclassified): K-RP placed literally at the plan line (TSC:2648/2661) is held as an EQUIV candidate on 05-14's
 * input, not classified — the pacing wait resumes through BootstrapIssueDue → pump → issueOwedBootstraps, which re-plans in the KRX
 * issue's own turn; 05-14 catches the stored-first-plan / earlier-issue-value forms. Two different mutations are kept apart (tests r2,
 * E-13): (a) deleting the 'topic is KRX' conjunct of the REST forwarding condition (§6.2 ② condition 2) is held as an EQUIV candidate —
 * a correct capture leaves a non-KRX answer's marker null, so condition 3 (marker non-null) still stops the hand-over of a non-KRX
 * issue, and the KRX issue of 05-8 is unaffected; 05-22 catches 'capture a marker for non-KRX via B' and 'hand non-KRX over'. (b) A
 * capture that fails to recognise KRX (the KRX issue captures no marker) is a different mutation, caught by 05-8. 'A guard on capInv' is
 * caught by 05-8, not 05-O2.
 *
 * tests r2 (Codex tests r1 REVISE): 05-8, 05-9, 05-12~05-15, 05-19, 05-20, 05-23 judge the forwarded marker against what B returned
 * at the issue (fix 2, E-17); 05-O2's marker clause and this KDoc no longer claim an observed (e, 0) marker (fix 2); the EQUIV notes
 * above separate the two mutations (E-13).
 *
 * tests r3 (Codex tests r2): 05-24's REST half added as its own test on an independent rig (REVISE 1); 05-25's REST half added — the
 * internal constructor with `krxSendReader = null`, marker channel present and absent (E-35). Both share [readerlessRest].
 *
 * tests r3b (verifier g4): in [readerlessRest] the KRX REST issue is a row contract; the answer is a fresh Unsupported checked by
 * identity; the undelivered callback is 'exactly one in the run', placed before the hand-over negatives as their window. Stub
 * classification of those three tests: GREEN, mixed (h+v).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TopicSessionKrxRestMarkerTest {
    private val active = EntitlementsOutcome.StableActive(krxVisible = true)
    private val krxFalse = EntitlementsOutcome.StableActive(krxVisible = false)
    private val restRefusal = mapOf(KRX to TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED)
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
     * ON rig: CHECKED_ON PAC inside checkedOnTest, prepared by [prepare] (default: granted, KRX visible, OPEN p = 1), and S-3
     * ([publicConstructor]: built by the session's public constructor, 05-24 REST half; `sendReader = false`: the internal constructor
     * with `krxSendReader = null`, 05-25 REST half — both tests r3).
     */
    private fun onRig(
        desired: Set<String> = TEST_DESIRED,
        markerChannel: Boolean = true,
        gap: Duration = Duration.ZERO,
        publicConstructor: Boolean = false,
        sendReader: Boolean = true,
        prepare: suspend (KrxHarness) -> Unit = { it.grantedKrxVisible() },
        body: suspend TestScope.(KrxHarness, TopicSessionKrxHarness) -> Unit
    ) = checkedOnTest { h ->
        prepare(h)
        body(
            h,
            TopicSessionKrxHarness(
                h, desired, markerChannel, bootstrapIssueGap = gap, publicConstructor = publicConstructor, sendReader = sendReader
            )
        )
    }

    /** OFF rig: HEAD_FACTORY PAC (KrxContestHarness, unchanged), prepared by [prepare], and S-3 over it. */
    private fun offRig(
        prepare: suspend (KrxHarness) -> Unit = { it.grantedKrxVisible() },
        body: suspend TestScope.(KrxHarness, TopicSessionKrxHarness) -> Unit
    ) = runTest {
        val processJob = SupervisorJob()
        try {
            val h = krxHarness(processJob, HEAD_FACTORY)
            prepare(h)
            body(h, TopicSessionKrxHarness(h))
        } finally {
            processJob.cancel()
        }
    }

    /** ON, premium confirmed with KRX not visible (StableActive(krxVisible = false)): the contest has issued no permit, p = 0. */
    private fun permitZero(row: String): suspend (KrxHarness) -> Unit = { h ->
        h.source.next = { krxFalse }
        h.coordinator.onIdentityChanged(AuthIdentityFence(KrxHarnessDefaults.OWNER, 1L)).applied()
        h.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        h.test.settleKrx()
        assertEquals("$row premise: premium confirmed", PremiumAccessState.PremiumConfirmed, h.coordinator.state.value.state)
        assertEquals("$row premise: KRX hidden", KrxCapabilityState.HIDDEN, h.coordinator.krx.value)
        h.store.record = h.store.record.copy(mayContainPremiumData = true)
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

    private fun assertOnAllowedAt(row: String, what: String, snapshot: TopicAccessSnapshot) {
        assertTrue("$row premise(ON): krxPermitSeq ≥ 1 at $what (${snapshot.krxPermitSeq})", snapshot.krxPermitSeq >= 1)
        assertTrue("$row premise(ON): capabilityAllowed at $what (KRX issue while allowed)", snapshot.facts.capabilityAllowed)
        assertNotNull("$row premise: the record at $what has a capability epoch", snapshot.facts.recordFence?.krxCapabilityEpoch)
    }

    /** An issue under H1′ or HOLD: p ≥ 1, the capability withheld, general use allowed (T.0 (r5)). */
    private fun assertWithheldAt(row: String, what: String, snapshot: TopicAccessSnapshot) {
        assertTrue("$row premise(ON): krxPermitSeq ≥ 1 at $what (${snapshot.krxPermitSeq})", snapshot.krxPermitSeq >= 1)
        assertFalse("$row premise(ON): capabilityAllowed = false at $what", snapshot.facts.capabilityAllowed)
        assertTrue("$row premise: general use allowed at $what (standing)", snapshot.facts.tokenStanding)
        assertTrue("$row premise: general use allowed at $what (user axis)", snapshot.facts.userAllowed)
    }

    /** The latest KRX issue, asserted to be still held before its exchange. */
    private fun TopicSessionKrxHarness.krxIssue(row: String): TopicSessionKrxHarness.BootstrapCall {
        val call = bootstrapOf(KRX, row)
        assertTrue("$row premise: the KRX bootstrap is held before its exchange", call.exchanges.isEmpty())
        return call
    }

    /** Window premise: [call] was answered [outcome] by the fake after an admitted exchange, and that answer reached the session. */
    private fun TopicSessionKrxHarness.reachedSession(row: String, call: TopicSessionKrxHarness.BootstrapCall, outcome: TopicSnapshotOutcome) {
        assertEquals("$row premise: the ${call.topic} exchange was admitted", true, call.exchanges.lastOrNull())
        assertFalse("$row premise: the ${call.topic} use was not withheld", call.withheld)
        assertEquals("$row premise: the fake answered the ${call.topic} issue", outcome, call.answered)
        assertTrue(
            "$row premise: the ${call.topic} answer reached the session's undelivered hand-over",
            undelivered.any { it.second == call.topic && it.third == outcome }
        )
    }

    /** Opens the socket and acknowledges its first subscribe with every desired topic active, so the WebSocket side stays quiet. */
    private fun TopicSessionKrxHarness.quietWire(row: String) {
        assertEquals("$row premise: the deliverer granted one connection", 1, wires.size)
        openWire()
        val attempt = attempts.lastOrNull() ?: throw AssertionError("$row premise: no subscribe was sent")
        assertTrue("$row premise: the first subscribe was sent", attempt.wireAccepted)
        wire.deliver(ack(attempt.requestId, listOf(TETHER, USD, KRX)))
        step(1)
        assertEquals("$row premise: the subscribe was acknowledged", 1, acknowledgements.size)
        assertTrue("$row premise: the acknowledgement refused nothing", acknowledgements.single().rejected.isEmpty())
    }

    /** A new permit the 05-2 way: another reservation with permit 1's marker hands over by H1′ (no rotation), then the fresh true. */
    private suspend fun TestScope.newPermitByH1Prime(row: String, h: KrxHarness, s: TopicSessionKrxHarness, epoch: String?) {
        val grant = h.issueGrant()
        h.source.identityNulls = 1
        h.coordinator.onTopicRejected(h.coordinator.reserveRejection(grant, KrxHarnessDefaults.KRX_REFUSAL, KrxSendMarker(epoch, 1L)))
        assertEquals("$row premise: H1′ consumed the live read failure", 0, h.source.identityNulls)
        assertEquals("$row premise: H1′ does not rotate", epoch, h.store.record.krxCapabilityEpoch)
        s.step(H1_PRIME_FLOOR_STEP)
        assertEquals("$row premise: OPEN after the fresh true", KrxContestPhase.OPEN, h.view(row).state.phase)
        assertEquals("$row premise: p = 2 after the fresh true", 2L, h.view(row).state.permitSeq)
        assertEquals("$row premise: rejectedPermit = 1 after the fresh true", 1L, h.view(row).rejectedPermit)
    }

    /**
     * The snapshot supplier B returned for this KRX issue (tests r2, Codex tests r1 fix 2, E-17): S-3 records each return of B, and the
     * KRX call claims the issue reads made before it entered the fake (tests r5: the returns made in the session's REST issue path,
     * attributed by call stack; a read from any other path is unattributed and not claimed). B returning exactly one snapshot for the issue is 05-16's
     * contract (§5B, D-4) — α behaviour, so a row other than 05-16 passes `"<row> contract(05-16):"` as [label] (E-18).
     */
    private fun TopicSessionKrxHarness.BootstrapCall.issueRead(label: String): TopicAccessSnapshot {
        assertEquals(
            "$label B returned exactly one snapshot for the $topic issue (read in the session's REST issue path before the fake " +
                "bootstrap began it)",
            1, readerSnapshotsAtIssue.size
        )
        return readerSnapshotsAtIssue.single()
    }

    /**
     * The one REST hand-over the row expects: once on the marker channel, KRX_ENTITLEMENT_REQUIRED, [marker] (the issue-time value),
     * to S-5 and the ledger. Its marker is also judged against what B returned for [call]'s issue (tests r2): first
     * `contract(05-16):` that B returned exactly one snapshot there, before this row contract, then the forwarded marker's (epoch,
     * permit) equal that snapshot's.
     */
    private fun TopicSessionKrxHarness.assertOneHandOver(
        row: String,
        call: TopicSessionKrxHarness.BootstrapCall,
        marker: KrxSendMarker
    ): TopicSessionKrxHarness.MarkerRejection {
        val read = call.issueRead("$row contract(05-16):")
        assertEquals("$row: the marker channel is called once", 1, markerRejections.size)
        val forwarded = markerRejections.single()
        assertEquals("$row: the reason is KRX_ENTITLEMENT_REQUIRED", restRefusal, forwarded.reasons)
        assertNotNull("$row: the marker is non-null", forwarded.marker)
        assertEquals("$row: the marker is the issue-time value", marker, forwarded.marker)
        assertEquals(
            "$row: the forwarded marker's (epoch, permit) are those of the snapshot B returned at this issue (same read)",
            read.marker(), forwarded.marker
        )
        assertEquals("$row: the public two-argument refusal is not called", 0, publicRejections.size)
        assertEquals("$row: the issuer's three-argument reservation is made once", 1, issuer.threeArgument.size)
        assertEquals("$row: no two-argument reservation", 0, issuer.twoArgument.size)
        val reserved = issuer.threeArgument.single()
        assertEquals("$row: the issuer receives the issue-time marker", marker, reserved.marker)
        assertEquals("$row: the ledger reservation carries the issue-time marker", marker, reserved.reservation.marker)
        return forwarded
    }

    /**
     * Non-KRX preservation (scope r11:781, :792): the session keeps its socket and grant, ended no graph authority, and a tether and a
     * USD frame still move the store and the prices.
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

    // ---- REST branch ----------------------------------------------------------------------------------------------------------

    /**
     * 05-8 REST unknown_topic (ON) (draft RU-03). OPEN(p), epoch e. The KRX bootstrap answers Unsupported, with the list [] and with
     * the contradictory list [KRX, USD] (OD-B09: the list is not a condition). Both: the marker channel once → S-5 three-argument once,
     * exactly one reservation, reason [KRX_ENTITLEMENT_REQUIRED], marker (e, p) non-null at the issue. PAC hand-over H1 (AWAITING, FE 1
     * after the floor, rejectedPermit = p, the epoch rotated). Non-KRX prices, store, receive generation and connection kept.
     * Mutations: R-MAP (UNKNOWN_TOPIC → PAC returns at once), K-NUL, the list used as a condition; and (g4-fix classification) the
     * guard on capInv that 05-O2 names: capInv is 0 at this issue (premise), so that guard hands nothing over and 'the marker channel is
     * called once' fails.
     */
    @Test fun r05_8_restUnknownTopic_handsTheIssueMarkerOver_whateverTheList() = runVariants(
        "05-8 []" to { onRig { h, s -> restUnknownTopic("05-8 []", h, s, UNKNOWN_TOPIC) } },
        "05-8 [KRX, USD]" to {
            onRig { h, s -> restUnknownTopic("05-8 [KRX, USD]", h, s, TopicSnapshotOutcome.Unsupported(listOf(KRX, USD))) }
        }
    )

    private suspend fun TestScope.restUnknownTopic(row: String, h: KrxHarness, s: TopicSessionKrxHarness, outcome: TopicSnapshotOutcome) {
        s.goLive()
        val krx = s.krxIssue(row)
        val atIssue = krx.snapshotAtCall
        assertOnAllowedAt(row, "the KRX issue", atIssue)
        assertEquals(
            "$row premise: capabilityInvalidations = 0 at the issue (so a guard on capInv would hand nothing over; g4-fix, the " +
                "mutation 05-O2 names but cannot catch off)",
            0L, atIssue.capabilityInvalidations
        )
        val issued = atIssue.marker()
        s.quietWire(row)
        val grant = s.granted.last().grant
        val wiresBefore = s.wires.size
        val epochBefore = h.store.record.krxCapabilityEpoch
        assertEquals("$row premise: nothing moved the epoch since the issue", issued.krxCapabilityEpoch, epochBefore)
        h.holdNextFetch()
        val fetches = h.source.calls.size
        assertEquals("$row premise: capabilityInvalidations = 0 just before the answer", 0L, h.coordinator.accessSnapshot.capabilityInvalidations)

        s.releaseBootstrap(KRX, outcome, row)
        s.step(1)

        s.reachedSession(row, krx, outcome)
        val forwarded = s.assertOneHandOver(row, krx, issued)
        assertEquals("$row: the owner is the session's grant", grant, forwarded.owner.grant)
        assertEquals("$row: the reservation's reasons", KrxHarnessDefaults.KRX_REFUSAL, s.issuer.threeArgument.single().reasons)
        s.step(H1_PRIME_FLOOR_STEP)
        val view = h.view(row)
        assertEquals("$row: H1 hands it over (barrier = that reservation)", s.issuer.threeArgument.single().reservation.order, view.state.barrier)
        assertEquals("$row: AWAITING", KrxContestPhase.AWAITING, view.state.phase)
        assertEquals("$row: the issue permit is handed over", issued.permitSeq, view.rejectedPermit)
        assertEquals("$row: FE 1 after the floor", fetches + 1, h.source.calls.size)
        assertNotEquals("$row: H1 rotates the KRX epoch", epochBefore, h.store.record.krxCapabilityEpoch)
        s.assertNonKrxKept(row, grant, wiresBefore)
    }

    /** α-1b explicit ON amendment, seam r2 §4.1; old/new and independent inputs: .alpha_evidence/a1b/on7_revisions.md. */
    @Test fun r05_9_restIiPrime_aPermitBetweenIssueAndExchange_isJudgedAsTheIssuePermit() = KrxA1bOnRevisions.rest09()

    /**
     * 05-10 ON, p = 0 REST (CX-10, draft RU-02). Before the first permit (premium confirmed with KRX not visible, p = 0) a KRX
     * Unsupported. No reservation, `rejectionView` pendingOrders and latestReported unchanged, issuer reserve 0, the ledger sequence
     * not consumed (the next order = the order taken just before + 1). Window premise added: no query started and the recheck demand
     * unchanged in between, so nothing else took an order. Mutation: R-OFF.
     */
    @Test fun r05_10_onPermitZero_restUnknownTopic_reservesNothing() = onRig(prepare = permitZero("05-10")) { h, s ->
        val row = "05-10"
        assertNotNull("$row premise: the PAC is ON", h.coordinator.krxContestView)
        s.goLive()
        val krx = s.krxIssue(row)
        val atIssue = krx.snapshotAtCall
        assertEquals("$row premise: krxPermitSeq = 0 at the issue", 0L, atIssue.krxPermitSeq)
        assertFalse("$row premise: capabilityAllowed = false at the issue", atIssue.facts.capabilityAllowed)
        assertTrue("$row premise: general use allowed at the issue (standing)", atIssue.facts.tokenStanding)
        assertTrue("$row premise: general use allowed at the issue (user axis)", atIssue.facts.userAllowed)
        val grant = s.granted.last().grant
        val view0 = h.coordinator.rejectionView(grant)
        val fetches = h.source.calls.size
        val demand0 = h.recheckDemandSnapshot()
        val probe = h.parts.orders.next()

        s.releaseBootstrap(KRX, UNKNOWN_TOPIC, row)
        s.step(1)

        s.reachedSession(row, krx, UNKNOWN_TOPIC)
        assertEquals("$row premise: no entitlements query started in the window", fetches, h.source.calls.size)
        assertEquals("$row premise: the recheck demand is unchanged in the window", demand0, h.recheckDemandSnapshot())
        assertEquals("$row: no reservation (issuer reserve 0, either form)", 0, s.issuer.reserved.size)
        assertEquals("$row: the marker channel is not called", 0, s.markerRejections.size)
        assertEquals("$row: the public two-argument refusal is not called", 0, s.publicRejections.size)
        val view1 = h.coordinator.rejectionView(grant)
        assertEquals("$row: rejectionView pendingOrders unchanged", view0.pendingOrders, view1.pendingOrders)
        assertEquals("$row: rejectionView latestReported unchanged", view0.latestReported, view1.latestReported)
        assertEquals("$row: the ledger sequence was not consumed", probe + 1, h.parts.orders.next())
    }

    /** α-1b explicit ON amendment, seam r2 §4.1; old/new and independent inputs: .alpha_evidence/a1b/on7_revisions.md. */
    @Test fun r05_12_restIi_aPermitAfterTheExchange_isJudgedAsTheIssuePermit() = KrxA1bOnRevisions.rest12()

    /** α-1b explicit ON amendment, seam r2 §4.1; old/new and independent inputs: .alpha_evidence/a1b/on7_revisions.md. */
    @Test fun r05_13_restVPrime_aC1RotationBetweenIssueAndExchange_isDroppedByTheEpochGuard() = KrxA1bOnRevisions.rest13()

    /**
     * 05-14 (iii)-REST plan against issue (draft RB-04). The plan (TSC:2648) is made under p; during the pacing wait (TSC:2677-2679) a new
     * permit opens; then KRX is issued. The marker is (e, p + 1), the issue's. The pacing is the harness gap 4 s: USD at 0 s, TETHER at
     * 4 s, KRX at 8 s; the new permit opens at about 5.2 s. Mutations: K-RP (plan-time read) in its stored-value forms (the first plan's
     * value kept, or an earlier issue's value reused). K-RP placed literally at the plan line (TSC:2648/2661) is held as an EQUIV
     * candidate here, not classified (g4-fix; E-13 keeps it held): the pacing wait resumes through BootstrapIssueDue → pumpBootstraps →
     * issueOwedBootstraps, which re-plans in the same turn as the KRX issue.
     */
    @Test fun r05_14_restIii_aPermitDuringThePacingWait_isReadAtTheIssue() = onRig(gap = 4.seconds) { h, s ->
        val row = "05-14"
        s.goLive()
        val usd = s.bootstrapOf(USD, row)
        assertEquals("$row premise(ON): the plan is made under permit 1 (USD issued with it)", 1L, usd.snapshotAtCall.krxPermitSeq)
        assertTrue("$row premise: KRX waits on the pacing", s.bootstrapCalls.none { it.topic == KRX })
        val epoch = usd.snapshotAtCall.facts.recordFence?.krxCapabilityEpoch
        s.quietWire(row)
        newPermitByH1Prime(row, h, s, epoch)
        assertTrue("$row premise: KRX still waits on the pacing after the new permit", s.bootstrapCalls.none { it.topic == KRX })

        s.step(3_000)

        val krx = s.krxIssue(row)
        val atIssue = krx.snapshotAtCall
        assertOnAllowedAt(row, "the KRX issue", atIssue)
        assertEquals("$row premise(ON): the KRX issue is under permit 2", 2L, atIssue.krxPermitSeq)
        val issued = atIssue.marker()
        assertEquals("$row premise: the issue-time marker is (e, 2)", KrxSendMarker(epoch, 2L), issued)
        h.holdNextFetch()

        s.releaseBootstrap(KRX, UNKNOWN_TOPIC, row)
        s.step(1)

        s.reachedSession(row, krx, UNKNOWN_TOPIC)
        s.assertOneHandOver(row, krx, issued)
    }

    /** α-1b explicit ON amendment, seam r2 §4.1; old/new and independent inputs: .alpha_evidence/a1b/on7_revisions.md. */
    @Test fun r05_15_restIii401_oneMarkerPerIssue_aReRequestReadsAnew() = KrxA1bOnRevisions.rest15()

    /** α-2 explicit ON amendment: one payload per logical issue, separate original-use rechecks.
     * Source/lifetime column holds B stable. Independent epoch/capInv columns reject before transport.
     */
    @Test fun r05_16_restI_oneReadPerIssue_markerAndLifetimeFromThatRead() = runVariants(
        "05-16 payload/lifetime" to { onRig { h, s -> issuePayloadLifetime("05-16 payload/lifetime", h, s) } },
        "05-16 epoch recheck" to { onRig { h, s -> issueRecheck("05-16 epoch recheck", h, s, epoch = true) } },
        "05-16 capInv recheck" to { onRig { h, s -> issueRecheck("05-16 capInv recheck", h, s, epoch = false) } }
    )

    private fun TestScope.issuePayloadLifetime(row: String, h: KrxHarness, s: TopicSessionKrxHarness) {
        // Observe the real channel without letting its first refusal retire the second issue's source context.
        s.forwardMarkerRejections = false
        s.readerSnapshot = { h.coordinator.accessSnapshot }
        s.goLive()
        val krx = s.krxIssue(row)
        val first = krx.issueRead("$row contract(05-16):")
        assertOnAllowedAt(row, "first issue payload", first)
        val fence = s.granted.last()
        val payloads = s.readerReturns.count { it.path == TopicSessionKrxHarness.ReadPath.ISSUE }
        assertEquals("$row: exactly one payload read for the first issue", 1, payloads)
        val validationBefore = s.readerReturns.count { it.path == TopicSessionKrxHarness.ReadPath.REST_RECHECK }
        s.releaseCall(krx, UNKNOWN_TOPIC); s.step(1)
        assertEquals("$row premise: unchanged B admits exactly one physical exchange", listOf(true), krx.exchanges)
        s.reachedSession(row, krx, UNKNOWN_TOPIC)
        assertEquals("$row contract(05-16): one original marker is forwarded", 1, s.markerRejections.size)
        assertEquals("$row: first marker comes from the issue payload", first.marker(), s.markerRejections.single().marker)
        assertEquals("$row: first lifetime comes from the same payload", first.acquireUse(fence), s.undelivered.single { it.second == KRX }.first.lifetime)
        assertTrue("$row contract(05-16): transport/afterAttempt rechecks are actually observed",
            s.readerReturns.count { it.path == TopicSessionKrxHarness.ReadPath.REST_RECHECK } > validationBefore)
        assertEquals("$row: validation does not create a second payload", payloads,
            s.readerReturns.count { it.path == TopicSessionKrxHarness.ReadPath.ISSUE })
        assertTrue("$row: rechecks are never claimed by an issue", s.readerReturns.filter {
            it.path == TopicSessionKrxHarness.ReadPath.REST_RECHECK }.all { it.claimedBy == null })

        val real = h.coordinator.accessSnapshot
        val aOther = real.copy(userInvalidations = real.userInvalidations + 1)
        val bUse = checkNotNull(real.acquireUse(fence)) { "$row premise: B supplies a standing GENERAL lifetime" }
        assertNotNull("$row premise: A still wants an issue", aOther.acquireUse(fence))
        assertNotEquals("$row premise: A and B acquire distinguishable lifetimes", bUse, aOther.acquireUse(fence))
        assertFalse("$row premise: A does not admit B's lifetime", aOther.admitsUse(bUse))
        assertTrue("$row premise: B admits its own lifetime", real.admitsUse(bUse))
        val endedBefore = s.authorityEndedAny().size
        s.authoritySnapshot = { aOther }
        val issueCount = s.bootstrapCalls.count { it.topic == KRX }
        s.coordinator.requestBootstrap(KRX); s.step(1)
        assertEquals("$row premise: second logical KRX issue reaches fake", issueCount + 1, s.bootstrapCalls.count { it.topic == KRX })
        val again = s.krxIssue(row)
        val second = again.issueRead("$row contract(05-16):")
        assertEquals("$row: second issue payload is the consistent current B", real, second)
        assertEquals("$row: issue payload count increases by exactly one", payloads + 1,
            s.readerReturns.count { it.path == TopicSessionKrxHarness.ReadPath.ISSUE })
        assertFalse("$row: original lifetime is B's, not A's", again.useAdmitted())
        assertEquals("$row: no unrelated graph authority ended", endedBefore, s.authorityEndedAny().size)
        s.authoritySnapshot = { h.coordinator.accessSnapshot }
        assertTrue("$row: restoring A admits the original B lifetime", again.useAdmitted())
        s.releaseCall(again, UNKNOWN_TOPIC); s.step(1)
        assertEquals("$row premise: second issue really exchanges once", listOf(true), again.exchanges)
        s.reachedSession(row, again, UNKNOWN_TOPIC)
        assertEquals("$row: exactly two original markers", listOf(first.marker(), second.marker()), s.markerRejections.map { it.marker })
        val answers = s.undelivered.filter { it.second == KRX }
        assertEquals("$row: exactly two original lifetimes handed over", 2, answers.size)
        assertEquals("$row: second lifetime is its payload's", second.acquireUse(fence), answers[1].first.lifetime)
        assertEquals("$row: all rechecks leave issue payload count unchanged", payloads + 1,
            s.readerReturns.count { it.path == TopicSessionKrxHarness.ReadPath.ISSUE })
    }

    private fun TestScope.issueRecheck(row: String, h: KrxHarness, s: TopicSessionKrxHarness, epoch: Boolean) {
        s.forwardMarkerRejections = false
        s.goLive()
        val call = s.krxIssue(row)
        val original = call.issueRead("$row contract(05-16):")
        assertOnAllowedAt(row, "original issue payload", original)
        assertTrue("$row premise: original use is admitted before changing B", call.useAdmitted())
        assertTrue("$row premise: no exchange or result before changing B", call.exchanges.isEmpty() && call.answered == null)
        val changed = if (epoch) original.copy(facts = original.facts.copy(
            recordFence = original.facts.recordFence?.copy(krxCapabilityEpoch = "a2-other-epoch")
        )) else original.copy(capabilityInvalidations = original.capabilityInvalidations + 1)
        assertNotEquals("$row premise: the selected C-1 axis changes", original, changed)
        s.readerSnapshot = { changed }
        val before = s.readerReturns.count { it.path == TopicSessionKrxHarness.ReadPath.REST_RECHECK }
        s.releaseCall(call, UNKNOWN_TOPIC); s.step(1)
        assertEquals("$row contract(05-16): transport guard actually rejects the original use", listOf(false), call.exchanges)
        assertTrue("$row contract(05-16): real use-withheld unwind is observed", call.withheld)
        assertTrue("$row contract(05-16): transport recheck is attributed separately",
            s.readerReturns.count { it.path == TopicSessionKrxHarness.ReadPath.REST_RECHECK } > before)
        assertEquals("$row: physical admitted HTTP count is zero", 0, call.exchanges.count { it })
        assertNull("$row: rejected use produces no result", call.answered)
        assertTrue("$row: rejected use hands over no control", s.undelivered.none { it.second == KRX })
        assertTrue("$row: recheck cannot replace the original payload", call.readerSnapshotsAtIssue.single() === original)
        assertEquals("$row: recheck makes no fresh issue payload", 1,
            s.readerReturns.count { it.path == TopicSessionKrxHarness.ReadPath.ISSUE })
    }

    /**
     * S-3 self-check (tests r5, harness read attribution; not a design row, labelled `S-3 self-check`). Supplier B's returns are
     * attributed by the call stack they are made on, not by their order. Input: 05-14's pacing (gap 4 s), so the socket's subscribe and
     * its acknowledgement come between the USD issue and the KRX issue ([selfCheckDrive]). The check run reads B twice more: once inside
     * the session's acknowledgement callback ([TopicSessionKrxHarness.onAcknowledgementHook]), through the very seam method the issue path
     * uses (`acquireWithKrx`) — the shape of a mutant that re-reads the marker when the acknowledgement arrives — and once from the test
     * body (no session frame). Both are UNATTRIBUTED with an origin tag and never claimed, and the KRX issue's reads are the same number
     * as in a plain run of the same input without them: those reads do not change the KRX bootstrap's issue reads. Before r5 the
     * acknowledgement-time read had no open attempt and was claimed by that KRX call (one more issue read), the false kill the mutation
     * battery found on 05-14, 05-15 and 05-23.
     *
     * The check asserts harness behaviour only, so a production mutant does not fail it by itself: it compares with the plain run (same
     * production) instead of a literal count, and asserts that its two reads are among the unattributed ones, not that they are all of
     * them (a mutant's own acknowledgement-time read is a third, rightly unattributed). Its windows (an issue read and a send read exist)
     * hold on the implementation; in the r5 re-run of 47 battery mutants they failed only under `rest_capture_not_krx` (no issue read;
     * 05-16 fails too) and `tsc_reader_downcast` (no send read; 05-4 fails too), so the check was never a mutant's only failure there.
     */
    @Test fun harnessSelfCheck_anAcknowledgementTimeBRead_isUnattributed_andLeavesTheKrxIssueReads() {
        val label = "S-3 self-check"
        var plainIssueReads: Int? = null
        onRig(gap = 4.seconds) { _, s ->
            plainIssueReads = s.selfCheckDrive("$label [plain run]", ackHook = null, afterAck = {}).readerSnapshotsAtIssue.size
        }
        onRig(gap = 4.seconds) { _, s ->
            val ackTime = mutableListOf<TopicSessionKrxHarness.ReaderRead>()
            var direct: TopicSessionKrxHarness.ReaderRead? = null
            val krx = s.selfCheckDrive(
                label, ackHook = { ackTime += s.readBForSelfCheck() }, afterAck = { direct = s.readBForSelfCheck() }
            )

            val subscribe = s.attempts.firstOrNull { it.reachedWire && it.carriesKrx }
                ?: throw AssertionError("$label premise: a physical KRX subscribe exists")
            assertTrue("$label premise: the physical KRX subscribe was accepted", subscribe.wireAccepted)
            val index = s.attempts.indexOf(subscribe)
            assertTrue("$label contract(S-3): physical subscribe has a preceding probe", index > 0)
            val probe = s.attempts[index - 1]
            assertFalse("$label contract(S-3): preceding probe never reached wire", probe.reachedWire)
            assertTrue("$label contract(S-3): probe carries the same KRX request scope", probe.carriesKrx && probe.topics == subscribe.topics)
            assertNotEquals("$label contract(S-3): probe ID differs from physical ID", probe.requestId, subscribe.requestId)
            val pStack = checkNotNull(probe.encodeStack) { "$label premise: probe has an encode stack" }.below
            val tStack = checkNotNull(subscribe.encodeStack) { "$label premise: physical send has an encode stack" }.below
            assertTrue("$label premise: P/T stacks contain their CMD caller", pStack.isNotEmpty() && tStack.isNotEmpty())
            assertEquals("$label contract(S-3): P/T have the same CMD caller", pStack.first().methodName, tStack.first().methodName)
            assertEquals("$label contract(S-3): P/T share the command activation stack tail", pStack.drop(1), tStack.drop(1))
            assertEquals("$label contract(S-3): probe owns exactly one SEND payload read", 1, probe.readerSnapshotsInSend.size)
            assertTrue("$label contract(S-3): physical send does not read another payload", subscribe.readerSnapshotsInSend.isEmpty())
            assertTrue(
                "$label: every SEND read belongs to an attempt",
                s.readerReturns.filter { it.path == TopicSessionKrxHarness.ReadPath.SEND }.all { it.attempt != null }
            )
            assertEquals("$label premise: B was read once inside the acknowledgement callback", 1, ackTime.size)
            val ackRead = ackTime.single()
            assertEquals("$label: the acknowledgement-time read is UNATTRIBUTED", TopicSessionKrxHarness.ReadPath.UNATTRIBUTED, ackRead.path)
            assertNull("$label: the acknowledgement-time read belongs to no attempt", ackRead.attempt)
            assertTrue(
                "$label: its origin names the acknowledgement path (${ackRead.origin})",
                ackRead.origin.orEmpty().contains("TopicSubscribeCommand.settle")
            )
            val bodyRead = checkNotNull(direct) { "$label premise: the test body read B" }
            assertEquals("$label: a read from the test body is UNATTRIBUTED", TopicSessionKrxHarness.ReadPath.UNATTRIBUTED, bodyRead.path)
            assertTrue(
                "$label: its origin is outside the session (${bodyRead.origin})",
                bodyRead.origin.orEmpty().startsWith("outside the session")
            )
            assertTrue("$label premise: both extra reads came before the KRX call began", bodyRead.callsBegun <= krx.issue)
            assertTrue(
                "$label: both extra reads are among the unattributed reads",
                s.unattributedReads.any { it === ackRead } && s.unattributedReads.any { it === bodyRead }
            )

            val plain = checkNotNull(plainIssueReads) { "$label premise: the plain run reached its KRX issue" }
            assertTrue("$label premise: the plain run's KRX issue has an issue read to compare ($plain)", plain >= 1)
            val claimed = s.readerReturns.filter { it.claimedBy === krx }
            assertTrue(
                "$label: every read the KRX call claimed is in the REST issue path",
                claimed.all { it.path == TopicSessionKrxHarness.ReadPath.ISSUE }
            )
            assertEquals(
                "$label: the call's issue snapshots are exactly the reads it claimed, in order",
                claimed.map { it.snapshot }, krx.readerSnapshotsAtIssue
            )
            assertNull("$label: the acknowledgement-time read is not claimed", ackRead.claimedBy)
            assertNull("$label: the test-body read is not claimed", bodyRead.claimedBy)
            assertEquals(
                "$label: the extra reads do not change the KRX issue's reads (same number as the plain run)",
                plain, krx.readerSnapshotsAtIssue.size
            )
        }
    }

    /**
     * The S-3 self-check's input (05-14's pacing): goes live, acknowledges the socket's first subscribe with [ackHook] run inside the
     * session's acknowledgement callback, runs [afterAck] from the test body, then waits out the pacing and returns the KRX issue.
     */
    private fun TopicSessionKrxHarness.selfCheckDrive(
        label: String,
        ackHook: ((TopicCommandAcknowledgement) -> Unit)?,
        afterAck: () -> Unit
    ): TopicSessionKrxHarness.BootstrapCall {
        goLive()
        assertTrue("$label premise: KRX waits on the pacing", bootstrapCalls.none { it.topic == KRX })
        onAcknowledgementHook = ackHook
        quietWire(label)
        onAcknowledgementHook = null
        afterAck()
        step(10_000)
        return krxIssue(label)
    }

    /** One read of supplier B through the issue path's own seam method (`acquireWithKrx`), from wherever it is called; its record. */
    private fun TopicSessionKrxHarness.readBForSelfCheck(): TopicSessionKrxHarness.ReaderRead {
        krxSendReader.acquireWithKrx(granted.last())
        return readerReturns.last()
    }

    /**
     * 05-17 ON, issued at p = 0, then OPEN (draft RU-02b). Issued while p = 0; OPEN(p = 1) before the answer. No reservation (the guard
     * reads the marker). Mutation: G-SN (guarding with the processing-time snapshot reserves).
     */
    @Test fun r05_17_onPermitZero_issueThenFirstPermit_reservesNothing() = onRig(prepare = permitZero("05-17")) { h, s ->
        val row = "05-17"
        s.goLive()
        val krx = s.krxIssue(row)
        val atIssue = krx.snapshotAtCall
        assertEquals("$row premise: krxPermitSeq = 0 at the issue", 0L, atIssue.krxPermitSeq)
        assertFalse("$row premise: capabilityAllowed = false at the issue", atIssue.facts.capabilityAllowed)
        assertTrue("$row premise: general use allowed at the issue", atIssue.facts.tokenStanding && atIssue.facts.userAllowed)

        h.source.next = { active }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        s.step(100)
        assertEquals("$row premise: OPEN before the answer", KrxContestPhase.OPEN, h.view(row).state.phase)
        assertEquals("$row premise: p = 1 before the answer", 1L, h.view(row).state.permitSeq)
        assertEquals("$row premise: rejectedPermit = 0 before the answer", 0L, h.view(row).rejectedPermit)
        assertTrue("$row premise(ON): capabilityAllowed before the answer", h.coordinator.accessSnapshot.facts.capabilityAllowed)
        assertTrue("$row premise: the exchange has not happened yet", krx.exchanges.isEmpty())
        h.holdNextFetch()
        val fetches = h.source.calls.size

        s.releaseBootstrap(KRX, UNKNOWN_TOPIC, row)
        s.step(1)

        s.reachedSession(row, krx, UNKNOWN_TOPIC)
        assertEquals("$row: no reservation", 0, s.issuer.reserved.size)
        assertEquals("$row: the marker channel is not called", 0, s.markerRejections.size)
        assertEquals("$row: OPEN is kept", KrxContestPhase.OPEN, h.view(row).state.phase)
        assertEquals("$row: rejectedPermit stays 0", 0L, h.view(row).rejectedPermit)
        assertEquals("$row: FE 0", fetches, h.source.calls.size)
    }

    /**
     * 05-18 no marker channel (draft RU-06, D-8). S-3 with `onRejectedWithMarker = null`, the public `onRejected` only. Under the same
     * ON snapshot (p ≥ 1) a KRX Unsupported, and the same condition's WebSocket KRX refusal. REST: the public `onRejected` 0 (no stand-in);
     * WS: the public two-argument form once (the HEAD path). Mutation: CH-2.
     */
    @Test fun r05_18_noMarkerChannel_restSendsNoStandIn_wsKeepsTheHeadPath() = onRig(markerChannel = false) { h, s ->
        val row = "05-18"
        s.goLive()
        val krx = s.krxIssue(row)
        assertOnAllowedAt(row, "the KRX issue", krx.snapshotAtCall)
        s.openWire()
        val attempt = s.lastKrxAttempt(row)
        assertTrue("$row premise: the KRX attempt was sent", attempt.wireAccepted)
        assertOnAllowedAt(row, "the WS send", attempt.snapshotAtEncode)
        assertEquals(
            "$row premise: the harness's issue-time and send-time captures of the published snapshot have the same epoch and permit " +
                "(captures, not B's returns)",
            krx.snapshotAtCall.marker(), attempt.snapshotAtEncode.marker()
        )
        h.holdNextFetch()

        s.releaseBootstrap(KRX, UNKNOWN_TOPIC, row)
        s.step(1)

        s.reachedSession(row, krx, UNKNOWN_TOPIC)
        assertEquals("$row (REST): the public onRejected is not called in its stead", 0, s.publicRejections.size)
        assertEquals("$row (REST): no reservation", 0, s.issuer.reserved.size)

        s.wire.deliver(s.ack(attempt.requestId, listOf(TETHER, USD), mapOf(KRX to "krx_entitlement_required")))
        s.step(1)

        assertEquals("$row premise: the acknowledgement was applied", 1, s.acknowledgements.size)
        assertEquals("$row (WS): the public two-argument refusal is called once (HEAD path)", 1, s.publicRejections.size)
        assertEquals("$row (WS): with the KRX refusal", restRefusal, s.publicRejections.single().second)
        assertEquals("$row (WS): the issuer's two-argument reservation once", 1, s.issuer.twoArgument.size)
        assertEquals("$row: no marker channel call exists", 0, s.markerRejections.size)
    }

    /** α-1b explicit ON amendment, seam r2 §4.1; old/new and independent inputs: .alpha_evidence/a1b/on7_revisions.md. */
    @Test fun r05_19_nullEpochMarker_isCarriedNonNull() = KrxA1bOnRevisions.rest19()

    /**
     * 05-20 hand-over after the use is withdrawn (draft RU-08). Same grant epoch, no latch, the use withdrawn (`authority.admits` false)
     * between the exchange and the answer. The marker channel is called once (control flow, §6.2 ③). The withdrawal is a hand
     * snapshot on the authority's supplier (user invalidations + 1). Mutation: R-ADM (filtering by the use loses the approval-closed H1′).
     */
    @Test fun r05_20_useWithdrawnBeforeTheAnswer_stillHandsOver() = onRig { h, s ->
        val row = "05-20"
        s.holdAnswer = { _, topic -> topic == KRX }
        s.goLive()
        val krx = s.krxIssue(row)
        val atIssue = krx.snapshotAtCall
        assertOnAllowedAt(row, "the KRX issue", atIssue)
        val issued = atIssue.marker()
        val fence = s.granted.last()
        val lifetime = checkNotNull(atIssue.acquireUse(fence)) { "$row premise: the issue's general use" }
        val grantCalls = s.grantCalls.toList()
        s.releaseBootstrap(KRX, UNKNOWN_TOPIC, row)
        s.step(1)
        assertEquals("$row premise: the exchange happened and was admitted", listOf(true), krx.exchanges)
        assertNull("$row premise: the answer is still held", krx.answered)

        val real = h.coordinator.accessSnapshot
        s.authoritySnapshot = { real.copy(userInvalidations = real.userInvalidations + 1) }
        assertFalse("$row premise: the use is withdrawn (authority.admits false)", s.authority.admits(lifetime))
        assertEquals("$row premise: no grant moved since the issue (same grant epoch)", grantCalls, s.grantCalls)
        assertEquals("$row premise: no acknowledgement (no socket latch)", 0, s.acknowledgements.size)
        assertEquals("$row premise: the live identity is the session's", TopicSessionKrxHarness.IDENTITY, s.liveFence)

        s.answerGate(krx.issue).complete(Unit)
        s.step(1)

        s.reachedSession(row, krx, UNKNOWN_TOPIC)
        assertTrue(
            "$row premise: the session saw the use withdrawn (graph use ended USE_WITHHELD)",
            s.authorityEnded(TopicGraphEventReason.USE_WITHHELD).isNotEmpty()
        )
        val read = krx.issueRead("$row contract(05-16):")
        assertEquals("$row: the marker channel is called once", 1, s.markerRejections.size)
        assertEquals("$row: with the KRX refusal", restRefusal, s.markerRejections.single().reasons)
        assertEquals("$row: and the issue-time marker", issued, s.markerRejections.single().marker)
        assertEquals(
            "$row: the forwarded marker's (epoch, permit) are those of the snapshot B returned at the issue (same read)",
            read.marker(), s.markerRejections.single().marker
        )
    }

    /** α-2 explicit ON amendment: cancellation before production and HEAD filtering after production are separate. */
    @Test fun r05_21_headFilters_comeBeforeTheHandOver() = runVariants(
        "05-21 control" to { onRig { h, s -> latchVariant("05-21 control", h, s, latch = false, queued = true) } },
        "05-21 latch/cancel-first" to { onRig { h, s -> latchVariant("05-21 latch/cancel-first", h, s, latch = true, queued = false) } },
        "05-21 latch/queued" to { onRig { h, s -> latchVariant("05-21 latch/queued", h, s, latch = true, queued = true) } },
        "05-21 grant/cancel-first" to { onRig { h, s -> grantEpochVariant("05-21 grant/cancel-first", h, s, queued = false) } },
        "05-21 grant/queued" to { onRig { h, s -> grantEpochVariant("05-21 grant/queued", h, s, queued = true) } }
    )

    private fun TopicSessionKrxHarness.assertCancelledBeforeProduction(row: String, call: TopicSessionKrxHarness.BootstrapCall) {
        assertTrue("$row contract(05-21): transition actually cancels the held job", call.job?.isCancelled == true && call.cancellation != null)
        assertTrue("$row: cancelled job has no exchange", call.exchanges.isEmpty())
        assertNull("$row: cancelled job has no produced result", call.answered)
        releaseCall(call, UNKNOWN_TOPIC); step(1)
        assertTrue("$row: opening the old gate adds no physical HTTP", call.exchanges.isEmpty())
        assertNull("$row: opening the old gate cannot produce a result", call.answered)
    }

    private fun TopicSessionKrxHarness.produceQueued(row: String, call: TopicSessionKrxHarness.BootstrapCall, gate: TopicSessionKrxHarness.InputGate) {
        releaseCall(call, UNKNOWN_TOPIC); step(1)
        assertEquals("$row premise: original exchange is admitted before transition", listOf(true), call.exchanges)
        assertTrue("$row premise: original control result is actually produced", call.answered === UNKNOWN_TOPIC)
        assertEquals("$row premise: original result input is actually queued", 1, gate.count("BootstrapAnswered"))
        assertTrue("$row premise: queued result has not been handed over", undelivered.none { it.second == KRX })
    }

    private fun TestScope.latchVariant(row: String, h: KrxHarness, s: TopicSessionKrxHarness, latch: Boolean, queued: Boolean) {
        s.forwardPublicRejections = false; s.forwardMarkerRejections = false
        s.goLive()
        val krx = s.krxIssue(row)
        assertOnAllowedAt(row, "original issue", krx.snapshotAtCall)
        assertTrue("$row premise: original KRX job is active", krx.job?.isActive == true)
        s.openWire()
        val attempt = s.lastKrxAttempt(row)
        assertTrue("$row premise: physical subscribe was accepted", attempt.reachedWire && attempt.wireAccepted)
        val gate = s.InputGate(row, "BootstrapAnswered")
        try {
            if (queued) s.produceQueued(row, krx, gate)
            val refused = if (latch) mapOf(USD to "premium_required") else emptyMap()
            s.wire.deliver(s.ack(attempt.requestId, if (latch) listOf(TETHER, KRX) else listOf(TETHER, USD, KRX), refused)); s.step(1)
            assertEquals("$row premise: ACK actually processed", 1, s.acknowledgements.size)
            if (latch) {
                assertTrue("$row premise: PREMIUM_REFUSED latch actually ended authority", s.authorityEnded(TopicGraphEventReason.PREMIUM_REFUSED).isNotEmpty())
                assertEquals("$row premise: latch refusal recorded once", 1, s.markerRejections.size + s.publicRejections.size)
            } else assertTrue("$row premise: no latch", s.authorityEndedAny().isEmpty())
            assertEquals("$row premise: no refusal was forwarded to issuer", 0, s.issuer.reserved.size)
            val channelBefore = s.markerRejections.size
            val undeliveredBefore = s.undelivered.size
            if (!queued) s.assertCancelledBeforeProduction(row, krx)
            gate.release("BootstrapAnswered"); s.step(1)
            if (!latch) {
                assertEquals("$row contract(05-21): no-latch queued control reaches handover", undeliveredBefore + 1, s.undelivered.size)
                assertTrue("$row: positive control preserves the original result", s.undelivered.single { it.second == KRX }.third === UNKNOWN_TOPIC)
            } else {
                assertEquals("$row: HEAD latch filter precedes undelivered handover", undeliveredBefore, s.undelivered.size)
                assertEquals("$row: HEAD latch filter precedes marker handover", channelBefore, s.markerRejections.size)
            }
            assertEquals("$row: issuer receives no reservation", 0, s.issuer.reserved.size)
        } finally { gate.close() }
    }

    private fun TestScope.grantEpochVariant(row: String, h: KrxHarness, s: TopicSessionKrxHarness, queued: Boolean) {
        s.goLive()
        val old = s.krxIssue(row)
        assertOnAllowedAt(row, "old KRX issue", old.snapshotAtCall)
        s.quietWire(row)
        assertTrue("$row premise: old job is active before transition", old.job?.isActive == true)
        val fence = s.granted.last()
        val count = s.bootstrapCalls.count { it.topic == KRX }
        val gate = s.InputGate(row, "BootstrapAnswered")
        try {
            if (queued) s.produceQueued(row, old, gate)
            s.coordinator.setAccess(false, fence, TopicGrantOrigin.NewContext)
            s.coordinator.setAccess(true, fence, TopicGrantOrigin.NewContext); s.step(1)
            assertTrue("$row premise: grant withdrawal actually ended authority", s.authorityEnded(TopicGraphEventReason.GRANT_WITHDRAWN).isNotEmpty())
            assertEquals("$row premise: new epoch issues a new KRX job", count + 1, s.bootstrapCalls.count { it.topic == KRX })
            val channelBefore = s.markerRejections.size
            val deliveredBefore = s.undelivered.size
            if (!queued) s.assertCancelledBeforeProduction(row, old)
            gate.release("BootstrapAnswered"); s.step(1)
            assertEquals("$row: HEAD epoch filter precedes old undelivered handover", deliveredBefore, s.undelivered.size)
            assertEquals("$row: HEAD epoch filter precedes old marker handover", channelBefore, s.markerRejections.size)
            assertEquals("$row: old control makes no reservation", 0, s.issuer.reserved.size)
            val currentUsd = s.bootstrapOf(USD, row)
            s.releaseCall(currentUsd, TopicSnapshotOutcome.Dormant); s.step(1)
            assertEquals("$row premise: current epoch's GENERAL result is processed after the old input", deliveredBefore + 1, s.undelivered.size)
            assertEquals("$row: only the current GENERAL result reaches handover", USD, s.undelivered.last().second)
        } finally { gate.close() }
    }

    /**
     * 05-22 non-KRX (draft RU-10). A USD Unsupported under a p ≥ 1 snapshot. Restated: the USD issue makes 0 calls on the krxSendReader
     * supplier, and the marker channel is called 0 times. Mutations: R-NK, capturing and passing a marker for non-KRX. Deleting the
     * 'topic is KRX' conjunct of the forwarding condition (§6.2 ② condition 2) on its own is held as an EQUIV candidate, not classified
     * (g4-fix; tests r2, E-13): a correct capture leaves a non-KRX answer's marker null and condition 3 (marker non-null) stops the
     * hand-over. A capture that fails to recognise KRX is a different mutation, caught by 05-8, not here.
     */
    @Test fun r05_22_nonKrxUnsupported_readsNoMarker_handsNothingOver() = onRig { h, s ->
        val row = "05-22"
        s.goLive()
        s.quietWire(row)
        val planned = s.bootstrapOf(USD, row)
        s.releaseCall(planned, TopicSnapshotOutcome.Dormant)
        s.step(1)
        s.reachedSession(row, planned, TopicSnapshotOutcome.Dormant)
        val channelBefore = s.markerRejections.size
        val reservedBefore = s.issuer.reserved.size
        val readsBefore = s.readerReads
        val usdIssued = s.bootstrapCalls.count { it.topic == USD }

        s.coordinator.requestBootstrap(USD)
        s.step(1)

        assertEquals("$row premise: USD issued once more", usdIssued + 1, s.bootstrapCalls.count { it.topic == USD })
        val usd = s.bootstrapOf(USD, row)
        assertTrue("$row premise(ON): krxPermitSeq ≥ 1 at the USD issue", usd.snapshotAtCall.krxPermitSeq >= 1)
        assertEquals("$row: the USD issue makes no call on the send-read supplier", readsBefore, s.readerReads)

        s.releaseCall(usd, UNKNOWN_TOPIC)
        s.step(1)

        s.reachedSession(row, usd, UNKNOWN_TOPIC)
        assertEquals("$row: the marker channel is not called", channelBefore, s.markerRejections.size)
        assertEquals("$row: no reservation", reservedBefore, s.issuer.reserved.size)
    }

    /** α-1b explicit ON amendment, seam r2 §4.1; old/new and independent inputs: .alpha_evidence/a1b/on7_revisions.md. */
    @Test fun r05_23_restUnderHold_issuesKrx_andTheNextIssueGoesOn() = KrxA1bOnRevisions.rest23()

    // ---- Reader-less sessions: 05-24 REST half, 05-25 REST half (tests r3) ------------------------------------------------------

    /**
     * The published snapshot as supplier A hands it out in the reader-less REST rows (05-24 and 05-25 REST halves): the user axis's
     * invalidation count moved by one. The session cannot tell it from a real count, and every read of A from before goLive gets
     * it, so the session's own uses stay consistent with A. A lifetime acquired through A then differs from one acquired on the
     * published snapshot — what tells the issue's lifetime source apart (the 05-16 device, here for the whole run).
     */
    private fun TopicAccessSnapshot.asSuppliedByA(): TopicAccessSnapshot = copy(userInvalidations = userInvalidations + 1)

    /**
     * 05-24 REST half (tests r3, Codex tests r2 REVISE 1; design r6 §13 05-24 'KRX REST 발행 → Unsupported … REST 발행 lifetime은
     * `authority.acquire`(HEAD `useLifetime()`)이고 issuer 전달 0, public 미전달 콜백은 HEAD 인자로 1회'). A session built by the
     * PUBLIC constructor (no send read, no marker channel), test desired (KRX included), ON PAC, OPEN p = 1, on its own rig —
     * independent of the WebSocket half (`TopicSessionKrxSendMarkerTest.r05_24_publicConstructor_aKrxAttemptKeepsTheHeadPath`), and
     * the socket is never opened, so no WebSocket reservation mixes in. The KRX REST issue is made (not stopped); its lifetime comes
     * from `authority.acquire` (supplier A); the fake answers a fresh Unsupported (404 unknown_topic); the public undelivered callback
     * is called once in the run with owner = the session fence, topic = KRX, outcome = that Unsupported (same object), lifetime = the
     * issue's lifetime — this is also the window (the answer reached the undelivered path); then no issuer hand-over, no reservation
     * added, no stand-in on the public two-argument form. Expected on the stub: GREEN, mixed (h+v) — the issue, the lifetime source and
     * the undelivered callback are h; 'issuer handed nothing', 'no reservation', 'no stand-in' (and 'channel 0' in 05-25 with a
     * channel) are v (tests r3b). See [readerlessRest] for what each clause can and cannot tell apart. Mutations: the reader-less KRX
     * issue stopped, or its lifetime taken from another read than A's at the issue; its Unsupported reserved, stood in for on the
     * public two-argument form, or kept from the undelivered callback.
     */
    @Test fun r05_24_rest_publicConstructor_aKrxIssueKeepsTheHeadPath() = onRig(markerChannel = false, publicConstructor = true) { h, s ->
        readerlessRest("05-24 [REST]", h, s, channel = false)
    }

    /**
     * 05-25 REST half (tests r3, Codex tests r2 E-35: 'reader absence and marker-channel absence are distinct'). The session is built
     * with its INTERNAL constructor and `krxSendReader = null` named (stub r3: nullable, no default), test desired, ON PAC, OPEN p = 1,
     * the socket never opened. Two settings, each its own test on its own rig:
     *
     * - **channel present** (this test): a channel is wired, but with no reader no marker is captured, so the KRX Unsupported is NOT
     *   handed to the issuer — the channel is not called for the REST answer and no reservation is added — and the public undelivered
     *   callback is called once with HEAD arguments (lifetime from `authority.acquire`, as in 05-24's REST half).
     * - **channel absent** ([r05_25_rest_readerNull_channelAbsent_unsupportedIsNotHandedOver]): the same, with no channel at all.
     *
     * Expected on the stub: GREEN, mixed (h+v) for both (tests r3b; the same split as 05-24's REST half). Note for the reviewer: on
     * the stub no session ever hands a REST answer over (the α hand-over branch does not exist yet), so 'not handed over' holds there
     * for that reason too — for the channel-present setting it is the α negative contract 'a null marker stops the hand-over', and its
     * stub GREEN is not evidence of that branch (the 05-18 REST-half precedent). 05-25's WebSocket half is in
     * `TopicSessionKrxSendMarkerTest` (`r05_25_ws_readerNull_…`). Mutations: a reader-less REST answer handed over on a wired channel
     * (with a null or made-up marker), stood in for on the public form, or kept from the undelivered callback; the issue stopped, or its
     * lifetime from another read than A's.
     */
    @Test fun r05_25_rest_readerNull_channelPresent_unsupportedIsNotHandedOver() = onRig(markerChannel = true, sendReader = false) { h, s ->
        readerlessRest("05-25 [REST, reader null, channel present]", h, s, channel = true)
    }

    /** 05-25 REST half, channel absent: see [r05_25_rest_readerNull_channelPresent_unsupportedIsNotHandedOver]. */
    @Test fun r05_25_rest_readerNull_channelAbsent_unsupportedIsNotHandedOver() = onRig(markerChannel = false, sendReader = false) { h, s ->
        readerlessRest("05-25 [REST, reader null, channel absent]", h, s, channel = false)
    }

    /**
     * The KRX REST issue and its Unsupported answer on a session with no send read ([channel]: whether a marker channel is wired).
     *
     * The lifetime's source: supplier A hands out [asSuppliedByA] snapshots for the whole run, so A's lifetime and the published
     * snapshot's differ (premise). The fake's `useAdmitted` is the session's `authority.admits(lifetime)` over the issue's lifetime
     * (TSC startBootstrap, HEAD; 05-16 uses the same closure): it admits under A's snapshot and refuses under the published one, so
     * the lifetime is A's acquire — the HEAD `useLifetime()`. With A the session's only reader, this cannot tell `authority.acquire`
     * from a down-cast `acquireWithKrx` on the same instance (same snapshot, one read either way); KW13's down-cast scan covers that.
     * The undelivered callback's lifetime is then compared with that A lifetime.
     *
     * The issue itself is a row contract (tests r3b, the 05-23 form): a reader-less issue that α stops (the W-X form →
     * `cancelBootstrapIssue`) fails as 'the KRX REST issue is made', not on [krxIssue]'s premise.
     *
     * The answer is a fresh `Unsupported(emptyList())`, equal to the fake's default UNKNOWN_TOPIC but not the same object, so the
     * callback's outcome is tied to this release by identity. After the release the window's premises (reachedSession's first three,
     * written out) and 'TETHER/USD stay held at their gates' come first; then the undelivered callback is a contract — exactly one in
     * the run, with HEAD arguments — so a dropped callback fails as a contract. That callback is the window for what follows: it sits
     * in the same `delivered == null` branch as α's hand-over (§6.2 ①).
     *
     * The hand-over clauses ('the issuer is handed nothing', 'no reservation added', 'no stand-in', and with a channel 'the channel is
     * not called') come after that window. On the stub no session hands a REST answer over at all, so there they hold for that reason
     * too: their stub GREEN shows the HEAD outcome is kept, not that an α hand-over branch stops on a reader-less session (05-18's
     * REST half is the same case). So these rows are GREEN, mixed (h+v), on the stub: the issue, the lifetime source and the
     * undelivered callback are h; the hand-over clauses are v.
     */
    private fun readerlessRest(row: String, h: KrxHarness, s: TopicSessionKrxHarness, channel: Boolean) {
        assertTrue("$row premise: the test desired carries KRX", KRX in TEST_DESIRED)
        s.authoritySnapshot = { h.coordinator.accessSnapshot.asSuppliedByA() }
        s.goLive()
        assertTrue(
            "$row: the KRX REST issue is made on the reader-less session (HEAD useLifetime() → startBootstrap, not stopped; the 05-23 form)",
            s.bootstrapCalls.any { it.topic == KRX }
        )
        val krx = s.krxIssue(row)
        val atIssue = krx.snapshotAtCall
        assertOnAllowedAt(row, "the KRX issue", atIssue)
        assertEquals("$row premise(ON): the issue is under permit 1", 1L, atIssue.krxPermitSeq)
        assertEquals("$row premise: supplier B was never handed to the session (no reader exists; it is not read)", 0, s.readerReads)
        assertTrue(
            "$row premise: no subscribe attempt was made (the socket is never opened, so no WebSocket refusal mixes in)",
            s.attempts.isEmpty()
        )
        val fence = s.granted.last()
        val grant = fence.grant
        val viaA = atIssue.asSuppliedByA().acquireUse(fence)
        val viaPublished = atIssue.acquireUse(fence)
        assertNotNull("$row premise: A's snapshot at the issue starts the general use", viaA)
        assertNotNull("$row premise: the published snapshot at the issue starts it too", viaPublished)
        assertNotEquals("$row premise: A's lifetime differs from the published snapshot's (what tells the sources apart)", viaPublished, viaA)
        assertEquals(
            "$row premise: the published snapshot still acquires the same lifetime as at the issue (nothing moved since)",
            viaPublished, h.coordinator.accessSnapshot.acquireUse(fence)
        )

        // The row contract on the lifetime's source (h): admitted under A's snapshot, refused under the published one.
        assertTrue("$row: the issue's lifetime is admitted under A's snapshot (useAdmitted = authority.admits(lifetime))", krx.useAdmitted())
        s.authoritySnapshot = { h.coordinator.accessSnapshot }
        val underPublished = krx.useAdmitted()
        s.authoritySnapshot = { h.coordinator.accessSnapshot.asSuppliedByA() }
        assertFalse(
            "$row: the issue's lifetime is refused under the published snapshot — it came from authority.acquire (supplier A)",
            underPublished
        )
        val reservedBefore = s.issuer.reserved.size
        val view0 = h.coordinator.rejectionView(grant)
        val channelBefore = s.markerRejections.size
        // A fresh answer (tests r3b): equal to the fake's default UNKNOWN_TOPIC (404 unknown_topic) but not the same object, so the
        // callback's 'outcome = the answered Unsupported (the same object)' ties it to this release, not to the fake's shared default.
        val answer = TopicSnapshotOutcome.Unsupported(emptyList())
        assertNotSame("$row premise: the released answer is a fresh object, not the fake's default answer", UNKNOWN_TOPIC, answer)

        s.releaseBootstrap(KRX, answer, row)
        s.step(1)

        // The window first. reachedSession's first three premises, written out (the third as the same object): the fake answered
        // this Unsupported after an admitted exchange. No other issue was answered: the TETHER and USD issues stay held at their gates.
        assertEquals("$row premise: the ${krx.topic} exchange was admitted", true, krx.exchanges.lastOrNull())
        assertFalse("$row premise: the ${krx.topic} use was not withheld", krx.withheld)
        assertSame("$row premise: the fake answered the ${krx.topic} issue (this answer, the same object)", answer, krx.answered)
        val others = s.bootstrapCalls.filter { it.topic != KRX }
        assertEquals(
            "$row premise: the other issues are TETHER and USD (${others.map { it.topic }})",
            setOf(TETHER, USD), others.map { it.topic }.toSet()
        )
        assertTrue(
            "$row premise: TETHER/USD stay held at their gates — no exchange, no answer " +
                "(${others.map { "${it.topic}: exchanges=${it.exchanges}, answered=${it.answered}" }})",
            others.all { it.exchanges.isEmpty() && it.answered == null }
        )
        // reachedSession's fourth premise (the answer reached the session's undelivered hand-over) is stated here as the row contract,
        // so a dropped callback fails as a contract. It is also the window for the hand-over negatives below: the callback sits in the
        // same `delivered == null` branch as α's hand-over (§6.2 ①).
        assertEquals(
            "$row: the public undelivered callback is called once in the run (only the KRX issue was answered; TETHER/USD stay held " +
                "at their gates)",
            1, s.undelivered.size
        )
        val (attribution, topic, outcome) = s.undelivered.single()
        assertEquals("$row: HEAD arguments — topic = KRX", KRX, topic)
        assertSame("$row: HEAD arguments — outcome = the answered Unsupported (the same object)", answer, outcome)
        assertEquals("$row: HEAD arguments — owner = the session's fence", fence, attribution.owner)
        assertEquals("$row: HEAD arguments — lifetime = the issue's lifetime (A's acquire at the issue)", viaA, attribution.lifetime)

        // Then the hand-over negatives.
        assertEquals("$row: the issuer is handed nothing (no reservation of either form)", reservedBefore, s.issuer.reserved.size)
        val view1 = h.coordinator.rejectionView(grant)
        assertEquals("$row: no reservation was added (rejectionView pendingOrders unchanged)", view0.pendingOrders, view1.pendingOrders)
        assertEquals("$row: no reservation was added (rejectionView latestReported unchanged)", view0.latestReported, view1.latestReported)
        assertEquals("$row: no stand-in on the public two-argument onRejected (D-8)", 0, s.publicRejections.size)
        if (channel) assertEquals("$row: the marker channel is not called for the REST answer", channelBefore, s.markerRejections.size)
    }

    // ---- OFF ------------------------------------------------------------------------------------------------------------------

    /**
     * 05-O2 REST OFF (§2 ④ (ii), draft RB-07·RU-01). OFF + test desired + KRX Unsupported. No reservation, no PAC decision, FE or state
     * change, traceLine the same before and after, the ledger sequence not consumed. The public undelivered callback once, with HEAD's
     * arguments (TSC de50885 :1232-1237 frozen: `TopicUseAttribution(sessionKey, owner, grantEpoch, input.lifetime)`, `input.topic`,
     * `input.outcome`). The '표지 = (epoch, 0)' clause (g4-fix, restored at session level; narrowed in tests r2, Codex tests r1 fix 2):
     * B was read once at the KRX issue, and the snapshot B supplied has epoch/permit equal to the expected (e, 0) — new α behaviour
     * (the read), judged last, so this row is RED on the stub like 05-O1 while its HEAD half passes. The (e, 0) compared is the test
     * helper `marker()` applied to that supplied snapshot, not the α marker: off, nothing is forwarded, so the marker the issue
     * captured is not observed here (a sentinel or K-NUL capture would still pass this clause). The marker value the send-read seam
     * itself returns is evidenced only by 04-O1, 11-4 and 11-5. Mutations: R-OFF, G-OFF, G-GE. The design's 'a guard on capInv' cannot
     * be caught off (capInv is fixed at 0 off, PAC:3173-3174, so it equals the correct guard here); 05-8 catches it (capInv = 0 at that
     * ON issue, premise there).
     */
    @Test fun r05_O2_restOff_reservesNothing_keepsTheHeadCallback() = offRig { h, s ->
        val row = "05-O2"
        val published = h.coordinator.observePublications()
        val start = h.coordinator.accessSnapshot
        val bReads = mutableListOf<TopicAccessSnapshot>()
        s.readerSnapshot = { h.coordinator.accessSnapshot.also { bReads += it } }
        s.goLive()
        val krx = s.krxIssue(row)
        // Taken at the issue and judged last, so the HEAD-preserving half below is shown first (the socket is never opened, so no
        // WebSocket attempt reads B, and the answer's processing reads no snapshot of B).
        val bReadsAtIssue = bReads.toList()
        val fence = s.granted.last()
        val grant = fence.grant
        val traceBefore = h.traceLine(row)
        val view0 = h.coordinator.rejectionView(grant)
        val fetches = h.source.calls.size
        val demand0 = h.recheckDemandSnapshot()
        val probe = h.parts.orders.next()

        s.releaseBootstrap(KRX, UNKNOWN_TOPIC, row)
        s.step(1)

        s.reachedSession(row, krx, UNKNOWN_TOPIC)
        val observed = published + start + krx.snapshotAtCall + h.coordinator.accessSnapshot
        assertTrue(
            "$row premise(OFF): krxPermitSeq = 0 at every observed snapshot (${observed.map { it.krxPermitSeq }})",
            observed.all { it.krxPermitSeq == 0L }
        )
        assertEquals("$row: no reservation", 0, s.issuer.reserved.size)
        assertEquals("$row: the marker channel is not called", 0, s.markerRejections.size)
        assertEquals("$row: the public two-argument refusal is not called", 0, s.publicRejections.size)
        assertEquals("$row: no entitlements query", fetches, h.source.calls.size)
        assertEquals("$row: no recheck demand change", demand0, h.recheckDemandSnapshot())
        assertEquals("$row: traceLine is the same before and after", traceBefore, h.traceLine(row))
        assertEquals("$row: rejectionView is unchanged", view0, h.coordinator.rejectionView(grant))
        assertEquals("$row: the ledger sequence was not consumed", probe + 1, h.parts.orders.next())
        val krxUndelivered = s.undelivered.filter { it.second == KRX }
        assertEquals("$row: the public undelivered callback is called once for KRX", 1, krxUndelivered.size)
        val (attribution, topic, outcome) = krxUndelivered.single()
        assertEquals("$row: HEAD arguments — the owner is the session's fence", fence, attribution.owner)
        assertEquals("$row: HEAD arguments — the topic", KRX, topic)
        assertSame("$row: HEAD arguments — the outcome as answered", krx.answered, outcome)
        assertEquals("$row: HEAD arguments — the lifetime the issue acquired", krx.snapshotAtCall.acquireUse(fence), attribution.lifetime)
        // The marker clause (g4-fix, verifier P2; narrowed in tests r2): off, B was read once at the KRX issue, and the snapshot B
        // supplied has epoch/permit equal to the expected (e, 0). This compares the helper's value over that snapshot, not the α marker.
        assertEquals("$row: B was read once at the issue", 1, bReadsAtIssue.size)
        assertEquals("$row: B was not read again after the issue", bReadsAtIssue.size, bReads.size)
        assertEquals(
            "$row: the supplied snapshot's epoch/permit equal the expected (e, 0)",
            KrxSendMarker(krx.snapshotAtCall.facts.recordFence?.krxCapabilityEpoch, 0L), bReadsAtIssue.single().marker()
        )
    }

    private companion object {
        /** Past the H1′ demand's floor (PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS = 5 s), so its query starts. */
        const val H1_PRIME_FLOOR_STEP = 5_100L
    }
    /** α-3 additive row; original locked r2 methods are unchanged. */
    @Test fun a3_r3_restPreparation_rechecksPublicationBeforeTgdNotification() = KrxA3Rows.r3_restPreparation_rechecksPublicationBeforeTgdNotification()

    /** α-3 additive row; original locked r2 methods are unchanged. */
    @Test fun a3_metadataClassification_keepsLogicalPayloadAttribution() = KrxA3Rows.metadataClassification_keepsLogicalPayloadAttribution()

}
