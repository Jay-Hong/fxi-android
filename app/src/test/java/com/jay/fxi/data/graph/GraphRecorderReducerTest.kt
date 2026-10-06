package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessEnd
import com.jay.fxi.data.entitlements.TopicAccessEndReason
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicGraphAuthority
import com.jay.fxi.data.remote.TopicGraphCandidate
import com.jay.fxi.data.remote.TopicGraphEventKind
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.data.remote.TopicGraphPath
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAttribution
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphCatalogPeriod
import com.jay.fxi.domain.model.GraphCatalogTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphV2Tab
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 F1 contract r2 (JVM; r1 plus the inputs its battery showed missing - K06, K09): the graph recorder's pure
 * reducer - one session data scope of D3 series states and
 * pre-catalog inputs, kept or thrown away by what topic access says, never by an observation.
 *
 * Oracles: ANDROID_V2_PLAN.md :1333-1343 ('보존·폐기': the recorder takes identity, namespace, allowance and explicit ends
 * from entitlements independently of observations; an envelope opens nothing; an unchanged namespace or a token change alone
 * decides nothing; a confirmed interruption keeps data and recovery reasons; an explicit premium cancel or session end makes
 * the scope unusable; KRX cancel touches the capability scope only; D23; a seal stops read, render, adoption, new requests,
 * retries and late completions; S1 owns persistence, journal and unsealing), :1320-1323 (recorder lifetime = user session data
 * scope; bounded pre-mapping holding and its recovery hand-over), :1329 (every bucket a reception gap spans keeps a recovery
 * reason), :1366 (KRX cancel alone discards no non-KRX observation; shared data is checked per consumer). Design: R4c/S4
 * f1_design_codex.r1, cut down by f1_review_claude.r1 after a three-lens verification (f1_design_verify_workflow.result.json),
 * agreed with its API and rows in f1_design_codex.r2.
 *
 * API: `GraphRecorderReducer` (internal object) over an immutable `GraphRecorderState` whose readable fields are scope,
 * series, pending, seenUserEnd, seenRevision, nextVersion and versionFloor; `empty()` is the only other constructor. Plus
 * `markGraphPendingLoss(pending, topics)` beside GraphPendingInputs.
 *
 * Semantics:
 *  - Access is arguments: the snapshot, the current fence (the scope is GraphDataScope(uid, user access epoch) of a fence with
 *    a non-null epoch) and `admission`, the GENERAL gate's answer for the input's or the consumer's own capture.
 *  - syncAccess first, in every mutating call: a snapshot whose revision is below seenRevision rejects the whole call. A user
 *    end whose sequence exceeds seenUserEnd discards every series and the pending holding and sets versionFloor =
 *    nextVersion, whatever its reason, owner, namespace (null included) or jump; the same end again discards nothing. A new
 *    non-null scope different from the held one (another epoch or another uid) discards the same way; no fence, or a fence
 *    with a null epoch, keeps everything. Capability ends and blocks change nothing here.
 *  - Use: userAllowed ∧ admission ∧ a current scope equal to the held one. Without it nothing is adopted, offered, captured,
 *    applied or exposed, and kept data stays.
 *  - observe: an input whose attribution owner scope differs from the held scope is ignored. Allowed: with no catalog it is
 *    offered to the pending holding; with one, E1/E2 map it and each mapped series is observed through D3 - a series absent
 *    until now is created (FX order: no priority; dxy: DXY_GRAPH_OBSERVATION_ORDER) with an INITIAL_SYNC demand on the current
 *    bucket first. Refused while the scope continues: no price is kept; each already existing mapped series gets
 *    requireGraphRecovery(earliest, latest of its mapped times, HANDOVER_LOSS); with no catalog the topic joins lostTopics.
 *    Nothing creates a series except an adopted mapped observation; a response never does.
 *  - offerPending (the caller's path before the catalog): allowed, the input is held as given; refused while the scope
 *    continues, its topics join lostTopics and the input is not held. (How a held continuity's owner is checked on replay is F2.)
 *  - captureRequests: allowed, for each given key whose series has a pending demand, issues
 *    captureGraphRecoveryRequest(series, ++nextVersion); series states are untouched. applyResponse: allowed, version above
 *    versionFloor, the request's series present in the held scope - D3 applies it. The counter is never reset.
 *  - exposed: the series map only when allowed, the held scope is the current one, seenUserEnd equals the snapshot's user end
 *    sequence (0 for none) and the snapshot is not older than seenRevision; otherwise empty.
 *  - Only sourced inputs are kept (observation ids and server buckets); F1 discarding is memory only, never purge completion.
 *
 * Not here (F2): the owner, its serial executor and StateFlow, the holder's read, replaying the pending holding, per-session
 * sequences and connection generations, request sending, retries and the protected-admission send check, purge adapters.
 * The implementation thread reads but does not edit this file.
 */
class GraphRecorderReducerTest {

    private fun at(day: Int, hhmmss: String): Instant = Instant.parse("2026-10-${day.toString().padStart(2, '0')}T$hhmmss+09:00")
    private fun kst(hhmmss: String): Instant = at(7, hhmmss)

    private val a = kst("19:50:00")
    private val b = kst("20:00:00")
    private val now = kst("20:02:00")

    private val grant = TopicGrantToken(7L)
    private val identity = AuthIdentityFence("u1", 1L)
    private val fenceN = TopicSessionFence(identity, "e1", grant)
    private val fenceN2 = TopicSessionFence(identity, "e2", grant)
    private val scopeN = GraphDataScope("u1", "e1")
    private val scopeN2 = GraphDataScope("u1", "e2")
    private val lifetime = TopicUseLifetime(grant, 3L)
    private fun attribution(owner: TopicSessionFence = fenceN) = TopicUseAttribution(Any(), owner, 1L, lifetime)

    private val kb = GraphObservationSeriesKey(scopeN, "kb.usd")
    private val hana = GraphObservationSeriesKey(scopeN, "hana.usd")
    private val dxy = GraphObservationSeriesKey(scopeN, "dxy")

    private val catalog = GraphCatalog(
        1.hours,
        mapOf("usd" to GraphCatalogTab("usd", "usd", emptyMap(), mapOf(
            GraphPeriod.ONE_DAY to GraphCatalogPeriod(listOf("investing.usd", "kb.usd", "hana.usd", "dxy"), listOf("kb.usd"))
        )))
    )

    private fun quote(source: String, rate: Double, t: Instant, asset: String = "usd-krw") =
        TopicGraphCandidate.Quote(source, asset, rate, t, null)
    private fun fx(vararg candidates: TopicGraphCandidate, owner: TopicSessionFence = fenceN, sequence: Long = 1L) =
        TopicGraphInput.Observations(sequence, "fx:usd-krw", TopicGraphPath.WS, attribution(owner), 1L, candidates.toList())
    private fun dollar(rate: Double, t: Instant, source: String = "investing") =
        TopicGraphInput.Observations(1L, "dxy:spot", TopicGraphPath.WS, attribution(), 1L,
            listOf(TopicGraphCandidate.DollarIndex(rate, t, source)))
    private fun fact(sequence: Long = 1L) = TopicGraphInput.Continuity(
        sequence, TopicGraphEventKind.DELIVERY_INTERRUPTED, null, setOf("fx:usd-krw"), setOf(TopicGraphPath.WS),
        TopicGraphAuthority(Any(), fenceN, 1L, lifetime), 1L, 1_000L
    )

    private fun snap(
        revision: Long,
        userEnd: Long? = null,
        reason: TopicAccessEndReason = TopicAccessEndReason.AUTHORITATIVE_LOSS,
        ownerUid: String? = "u1",
        namespace: String? = "e1",
        userBlocks: Set<TopicAccessBlock> = emptySet(),
        capabilityEnd: Long? = null,
        capabilityBlocks: Set<TopicAccessBlock> = emptySet(),
        token: Long = 7L
    ) = TopicAccessSnapshot(
        revision = revision,
        facts = TopicAccessFacts.NONE.copy(
            token = TopicGrantToken(token),
            binding = EntitlementsIdentity("u1", 1L),
            tokenStanding = true,
            userBlocks = userBlocks,
            capabilityBlocks = capabilityBlocks
        ),
        userInvalidations = 3L,
        lastUserEnd = userEnd?.let { TopicAccessEnd(it, reason, EntitlementsIdentity("u1", 1L), ownerUid, namespace) },
        lastCapabilityEnd = capabilityEnd?.let {
            TopicAccessEnd(it, TopicAccessEndReason.CAPABILITY_REVOKED, EntitlementsIdentity("u1", 1L), ownerUid, "K1")
        }
    )

    private val R = GraphRecorderReducer

    /** A recorder holding scope N at revision 1 with no end seen. */
    private fun ready(): GraphRecorderState = R.syncAccess(R.empty(), snap(1), fenceN)

    /** ready() with the kb series made by an adopted 20:01 quote. */
    private fun withKb(): GraphRecorderState = R.observe(ready(), fx(quote("kb", 1341.7, kst("20:01:00"))), catalog, snap(1), fenceN, true, now)

    private data class Print(
        val buckets: Map<Instant, GraphObservedBucket>,
        val tip: GraphObservationId?,
        val observations: Set<GraphObservationId>,
        val server: Map<Instant, GraphServerBucket>,
        val demands: Map<Instant, GraphRecoveryDemand>,
        val gaps: Set<Instant>,
        val generation: Long,
        val applied: Long?,
        val cutoff: Instant?
    )

    private fun fingerprint(s: GraphRecoverableState) = Print(
        s.data.app.buckets, s.data.app.tip, s.data.app.observations, s.data.serverBuckets, s.pending, s.gapHistory,
        s.generation, s.lastAppliedVersion, s.lastCutoff
    )

    private fun observation(key: GraphObservationSeriesKey, source: String, asset: String, rate: Double, t: Instant) =
        GraphObservation(key, GraphObservationId(source, asset, t, rate))

    /** What D3 makes of a series first seen at [now]: INITIAL_SYNC on the current bucket, then the observations. */
    private fun fresh(key: GraphObservationSeriesKey, order: GraphObservationOrder, vararg observations: GraphObservation) =
        observeRecoverable(
            requireGraphRecovery(GraphRecoverableState.empty(key, order), now, now, GraphRecoveryReason.INITIAL_SYNC, now),
            observations.toList(), now
        ).state

    private val noOrder = GraphObservationOrder(emptyList())

    private fun tab(points: List<FreeGraphPoint>, seriesId: String = "kb.usd") =
        GraphV2Tab("usd", GraphPeriod.ONE_DAY, "10min", kst("20:05:00"), FreeGraph("10min", listOf(FreeGraphSeries(seriesId, points))),
            emptyMap())
    private fun closed(t: Instant, rate: Double) = FreeGraphPoint(t, rate, rate, rate)

    private fun assertDiscarded(message: String, s: GraphRecorderState, floor: Long) {
        assertTrue("$message: series", s.series.isEmpty())
        assertTrue("$message: pending inputs", s.pending.inputs.isEmpty())
        assertTrue("$message: lost topics", s.pending.lostTopics.isEmpty())
        assertEquals("$message: floor", floor, s.versionFloor)
    }

    // --- access opens nothing on its own --------------------------------------------------------------------------------

    /** K01: without a grant, or without admission, a valid batch creates no series, holds nothing, captures and exposes nothing. */
    @Test fun K01_anEnvelopeOpensNothing() {
        val batch = fx(quote("kb", 1341.7, kst("20:01:00")))
        for ((blocks, admission) in listOf(setOf(TopicAccessBlock.NOT_GRANTED) to true, emptySet<TopicAccessBlock>() to false)) {
            val access = snap(1, userBlocks = blocks)
            var s = R.observe(R.empty(), batch, catalog, access, fenceN, admission, now)
            s = R.offerPending(s, fx(quote("kb", 1341.8, kst("20:01:10"))), access, fenceN, admission)
            assertTrue("$blocks $admission", s.series.isEmpty())
            assertTrue("$blocks $admission", s.pending.inputs.isEmpty())
            val (after, requests) = R.captureRequests(s, setOf(kb), access, fenceN, admission)
            assertTrue("$blocks $admission", requests.isEmpty())
            assertEquals("$blocks $admission", 0L, after.nextVersion)
            assertTrue("$blocks $admission", R.exposed(s, access, fenceN, admission).isEmpty())
        }
    }

    /**
     * K02: with data kept, a hold, a premium-pending answer (still the old grant, no end), a token change and a refused admission
     * keep the series, their demands and the floor; while use is closed nothing is captured, applied or exposed.
     */
    @Test fun K02_closedUseKeepsTheData() {
        val (s0, earlier) = R.captureRequests(withKb(), setOf(kb), snap(1), fenceN, true)
        val kbPrint = fingerprint(s0.series.getValue(kb))
        val cases = listOf(
            "hold" to Triple(snap(2, userBlocks = setOf(TopicAccessBlock.PERSISTENCE_HOLD)), true, false),
            "uncertain" to Triple(snap(2, userBlocks = setOf(TopicAccessBlock.CONTEXT_UNCERTAIN)), true, false),
            "pending answer" to Triple(snap(2), true, true),
            "token change, old capture" to Triple(snap(2, token = 8L), false, false),
            "admission closed" to Triple(snap(2), false, false)
        )
        for ((name, case) in cases) {
            val (access, admission, usable) = case
            val s = R.syncAccess(s0, access, fenceN)
            assertEquals(name, setOf(kb), s.series.keys)
            assertEquals(name, kbPrint, fingerprint(s.series.getValue(kb)))
            assertEquals(name, s0.versionFloor, s.versionFloor)
            val (_, requests) = R.captureRequests(s, setOf(kb), access, fenceN, admission)
            assertEquals(name, usable, requests.isNotEmpty())
            val applied = R.applyResponse(s, earlier.single(), tab(listOf(closed(b, 1341.5))), access, fenceN, admission, now)
            assertEquals(name, if (usable) 1L else null, applied.series.getValue(kb).lastAppliedVersion)
            assertEquals(name, usable, R.exposed(s, access, fenceN, admission).isNotEmpty())
        }
    }

    // --- user ends --------------------------------------------------------------------------------------------------------

    /**
     * K03: every user end reason - with the namespace null or another owner named - discards all series and the pending
     * holding and moves the floor to the last issued version.
     */
    @Test fun K03_everyUserEndDiscardsTheScope() {
        for (reason in TopicAccessEndReason.entries) {
            for ((owner, namespace) in listOf<Pair<String?, String?>>("u1" to "e1", "u1" to null, "u2" to "e9", null to null)) {
                var s = withKb()
                s = R.offerPending(s, fact(), snap(1), fenceN, true)
                s = R.observe(s, fx(quote("hana", 1341.9, kst("20:01:20"))), null, snap(1), fenceN, false, now)
                val (captured, requests) = R.captureRequests(s, setOf(kb), snap(1), fenceN, true)
                assertEquals("premise", 1, requests.size)
                assertTrue("premise", captured.pending.inputs.isNotEmpty() && captured.pending.lostTopics.isNotEmpty())
                val ended = R.syncAccess(captured, snap(2, userEnd = 1, reason = reason, ownerUid = owner, namespace = namespace), fenceN)
                assertDiscarded("$reason $owner $namespace", ended, captured.nextVersion)
                assertEquals("$reason $owner $namespace", 1L, ended.seenUserEnd)
            }
        }
    }

    /** K04: an end discards on syncAccess alone - no observation or response is needed - and is not exposed before it is synced. */
    @Test fun K04_anEndIsConsumedWithoutAnyObservation() {
        val s0 = withKb()
        val access = snap(2, userEnd = 1)
        assertTrue("an unconsumed end exposes nothing", R.exposed(s0, access, fenceN, true).isEmpty())
        val s = R.syncAccess(s0, access, fenceN)
        assertDiscarded("synced", s, s0.nextVersion)
    }

    /** K05: after an end, the same namespace allowed again starts empty and a valid observation recreates its series afresh. */
    @Test fun K05_theSameNamespaceRestartsEmpty() {
        val ended = R.syncAccess(withKb(), snap(2, userEnd = 1), fenceN)
        val s = R.observe(ended, fx(quote("kb", 1342.1, kst("20:01:30"))), catalog, snap(3, userEnd = 1), fenceN, true, now)
        assertEquals(setOf(kb), s.series.keys)
        assertEquals(
            fingerprint(fresh(kb, noOrder, observation(kb, "kb", "usd-krw", 1342.1, kst("20:01:30")))),
            fingerprint(s.series.getValue(kb))
        )
        assertFalse("nothing of the old series", GraphObservationId("kb", "usd-krw", kst("20:01:00"), 1341.7) in s.series.getValue(kb).data.app.observations)
    }

    /**
     * K06: a response captured before a discard is refused after it; the counter goes on above the floor. A series with no
     * demand is not captured, and a response for a series that does not exist creates none.
     */
    @Test fun K06_aResponseFromBeforeTheDiscardIsRefused() {
        val (s0, old) = R.captureRequests(withKb(), setOf(kb), snap(1), fenceN, true)
        assertEquals(1L, old.single().applicationVersion)
        val ended = R.syncAccess(s0, snap(2, userEnd = 1), fenceN)
        assertEquals(1L, ended.versionFloor)
        val recreated = R.observe(ended, fx(quote("kb", 1342.1, kst("20:01:30"))), catalog, snap(2, userEnd = 1), fenceN, true, now)
        val before = fingerprint(recreated.series.getValue(kb))
        val refused = R.applyResponse(recreated, old.single(), tab(listOf(closed(b, 1341.5))), snap(2, userEnd = 1), fenceN, true, now)
        assertEquals("the old response is refused", before, fingerprint(refused.series.getValue(kb)))
        val (s1, next) = R.captureRequests(refused, setOf(kb), snap(2, userEnd = 1), fenceN, true)
        assertEquals("the counter is not reset", 2L, next.single().applicationVersion)
        val applied = R.applyResponse(s1, next.single(), tab(listOf(closed(b, 1341.5))), snap(2, userEnd = 1), fenceN, true, now)
        assertEquals(2L, applied.series.getValue(kb).lastAppliedVersion)
        assertTrue("premise: the only demand was released", applied.series.getValue(kb).pending.isEmpty())
        val (after, none) = R.captureRequests(applied, setOf(kb), snap(2, userEnd = 1), fenceN, true)
        assertTrue("a series without a demand is not captured", none.isEmpty())
        assertEquals(2L, after.nextVersion)
        val absent = R.applyResponse(after, GraphRecoveryRequest(hana, 3L, 0L), tab(listOf(closed(b, 1341.5)), "hana.usd"),
            snap(2, userEnd = 1), fenceN, true, now)
        assertEquals("a response for a series that does not exist creates none", setOf(kb), absent.series.keys)
    }

    /** K07: the same end delivered again under a newer revision does not discard what came after it. */
    @Test fun K07_theSameEndAgainDiscardsNothing() {
        val ended = R.syncAccess(withKb(), snap(2, userEnd = 1), fenceN)
        val s = R.observe(ended, fx(quote("hana", 1341.9, kst("20:01:20"))), catalog, snap(2, userEnd = 1), fenceN, true, now)
        val again = R.syncAccess(s, snap(3, userEnd = 1, reason = TopicAccessEndReason.SEALED), fenceN)
        assertEquals(setOf(hana), again.series.keys)
        assertEquals(fingerprint(s.series.getValue(hana)), fingerprint(again.series.getValue(hana)))
        assertEquals(s.versionFloor, again.versionFloor)
    }

    /** K08: an end sequence that jumps (n -> n+3) discards, whatever reason the last one carries. */
    @Test fun K08_aJumpedEndDiscards() {
        val ended = R.syncAccess(withKb(), snap(2, userEnd = 1), fenceN)
        val s = R.observe(ended, fx(quote("hana", 1341.9, kst("20:01:20"))), catalog, snap(2, userEnd = 1), fenceN, true, now)
        val jumped = R.syncAccess(s, snap(3, userEnd = 4, reason = TopicAccessEndReason.UNVERIFIED_START), fenceN)
        assertDiscarded("jumped", jumped, s.nextVersion)
        assertEquals(4L, jumped.seenUserEnd)
    }

    /**
     * K09: a snapshot older than the last one seen is refused whole - no input is processed (not even as a refused one: no loss
     * is recorded), nothing is captured, applied or exposed.
     */
    @Test fun K09_anOlderSnapshotIsRefused() {
        val s0 = R.syncAccess(withKb(), snap(5), fenceN)
        val older = snap(4, userEnd = 9)
        val observed = R.observe(s0, fx(quote("hana", 1341.9, kst("20:01:20"))), catalog, older, fenceN, true, now)
        assertEquals(setOf(kb), observed.series.keys)
        assertEquals(fingerprint(s0.series.getValue(kb)), fingerprint(observed.series.getValue(kb)))
        assertEquals(0L, observed.seenUserEnd)
        assertEquals(5L, observed.seenRevision)
        val lossy = R.observe(s0, fx(quote("kb", 1343.0, kst("19:51:00"))), catalog, older, fenceN, true, now)
        assertEquals("not refused either - no loss is recorded", fingerprint(s0.series.getValue(kb)), fingerprint(lossy.series.getValue(kb)))
        val uncatalogued = R.observe(s0, fx(quote("kb", 1343.0, kst("19:51:00"))), null, older, fenceN, true, now)
        assertTrue(uncatalogued.pending.inputs.isEmpty() && uncatalogued.pending.lostTopics.isEmpty())
        val offered = R.offerPending(s0, fact(), older, fenceN, true)
        assertTrue(offered.pending.inputs.isEmpty())
        assertTrue("nor marked lost", offered.pending.lostTopics.isEmpty())
        val (captured, requests) = R.captureRequests(s0, setOf(kb), older, fenceN, true)
        assertTrue(requests.isEmpty())
        assertEquals(s0.nextVersion, captured.nextVersion)
        val (s1, request) = R.captureRequests(s0, setOf(kb), snap(5), fenceN, true)
        val applied = R.applyResponse(s1, request.single(), tab(listOf(closed(b, 1341.5))), older, fenceN, true, now)
        assertEquals(fingerprint(s1.series.getValue(kb)), fingerprint(applied.series.getValue(kb)))
        assertTrue(R.exposed(s0, older, fenceN, true).isEmpty())
        assertTrue("older alone, with no end in it, exposes nothing", R.exposed(s0, snap(4), fenceN, true).isEmpty())
    }

    // --- scopes -----------------------------------------------------------------------------------------------------------

    /**
     * K10: N -> no fence (or a fence with no epoch) -> N keeps everything and uses nothing meanwhile; N -> N' (another epoch
     * or another uid) discards with no copy into N'.
     */
    @Test fun K10_aNewScopeDiscardsAMissingFenceDoesNot() {
        val s0 = withKb()
        for (missing in listOf<TopicSessionFence?>(null, TopicSessionFence(identity, null, grant))) {
            val absent = R.syncAccess(s0, snap(2), missing)
            assertEquals("$missing", scopeN, absent.scope)
            assertEquals("$missing", fingerprint(s0.series.getValue(kb)), fingerprint(absent.series.getValue(kb)))
            assertTrue("$missing", R.exposed(absent, snap(2), missing, true).isEmpty())
            val back = R.syncAccess(absent, snap(3), fenceN)
            assertEquals("$missing", setOf(kb), R.exposed(back, snap(3), fenceN, true).keys)
        }

        val otherUser = TopicSessionFence(AuthIdentityFence("u2", 1L), "e1", grant)
        for ((fence, scope) in listOf(fenceN2 to scopeN2, otherUser to GraphDataScope("u2", "e1"))) {
            val moved = R.syncAccess(s0, snap(2), fence)
            assertEquals("$scope", scope, moved.scope)
            assertDiscarded("N -> $scope", moved, s0.nextVersion)
            assertTrue("no copy into $scope", R.exposed(moved, snap(2), fence, true).isEmpty())
        }
    }

    /** K11: a capability end or capability block changes nothing here, and no krx.* series is ever made. */
    @Test fun K11_capabilityEndsLeaveTheGeneralScope() {
        val (s0, _) = R.captureRequests(withKb(), setOf(kb), snap(1), fenceN, true)
        val access = snap(2, capabilityEnd = 1, capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED))
        val s = R.syncAccess(s0, access, fenceN)
        assertEquals(setOf(kb), s.series.keys)
        assertEquals(fingerprint(s0.series.getValue(kb)), fingerprint(s.series.getValue(kb)))
        assertEquals(s0.versionFloor, s.versionFloor)
        val krx = GraphObservationSeriesKey(scopeN, "krx.usd-krw-futures")
        val (captured, requests) = R.captureRequests(s, setOf(krx), access, fenceN, true)
        assertTrue(requests.isEmpty())
        val responded = R.applyResponse(captured, GraphRecoveryRequest(krx, 99L, 0L),
            tab(listOf(closed(b, 1400.0)), "krx.usd-krw-futures"), access, fenceN, true, now)
        assertTrue(responded.series.keys.none { it.seriesId.startsWith("krx.") })
    }

    // --- refused input while the scope continues ------------------------------------------------------------------------

    /**
     * K12 (C3): a batch refused while the scope continues - an old lifetime after a KRX rotation, or a hold - keeps no price
     * and leaves HANDOVER_LOSS over every bucket its mapped times span on each existing series.
     */
    @Test fun K12_aRefusedBatchLeavesARecoveryReason() {
        for ((access, admission) in listOf(snap(2) to false, snap(2, userBlocks = setOf(TopicAccessBlock.PERSISTENCE_HOLD)) to true)) {
            val s0 = withKb()
            val batch = fx(quote("kb", 1343.0, kst("20:01:40")), quote("kb", 1340.0, kst("19:51:00")))
            val s = R.observe(s0, batch, catalog, access, fenceN, admission, now)
            val expected = requireGraphRecovery(s0.series.getValue(kb), kst("19:51:00"), kst("20:01:40"), GraphRecoveryReason.HANDOVER_LOSS, now)
            assertEquals("$admission", fingerprint(expected), fingerprint(s.series.getValue(kb)))
            assertTrue("$admission", a in s.series.getValue(kb).gapHistory && b in s.series.getValue(kb).gapHistory)
            assertFalse("$admission", s.series.getValue(kb).data.app.observations.any { it.rate == 1343.0 || it.rate == 1340.0 })
            assertEquals("$admission: no new series", setOf(kb), s.series.keys)
        }
    }

    /** K13: refused with no existing series no series is made; refused before the catalog the topic joins lostTopics, the batch is not held. */
    @Test fun K13_aRefusedBatchMakesNothingAndRecordsItsTopic() {
        val s0 = ready()
        val noSeries = R.observe(s0, fx(quote("kb", 1341.7, kst("20:01:00"))), catalog, snap(1), fenceN, false, now)
        assertTrue(noSeries.series.isEmpty())
        val noCatalog = R.observe(s0, fx(quote("kb", 1341.7, kst("20:01:00"))), null, snap(1), fenceN, false, now)
        assertTrue(noCatalog.series.isEmpty())
        assertTrue("the refused batch is not held", noCatalog.pending.inputs.isEmpty())
        assertEquals(setOf("fx:usd-krw"), noCatalog.pending.lostTopics)
        val offered = R.offerPending(s0, fact(), snap(1), fenceN, false)
        assertTrue(offered.pending.inputs.isEmpty())
        assertEquals(setOf("fx:usd-krw"), offered.pending.lostTopics)
    }

    /**
     * K14: an allowed input before the catalog is held as the same object; an end or a new scope drops the holding and its losses.
     * An input attributed to another scope is neither held nor, when refused, recorded as a loss here.
     */
    @Test fun K14_theHoldingIsKeptAsGivenAndDroppedWithTheScope() {
        val batch = fx(quote("kb", 1341.7, kst("20:01:00")))
        var s = R.observe(ready(), batch, null, snap(1), fenceN, true, now)
        s = R.observe(s, fx(quote("hana", 1341.9, kst("20:01:20"))), null, snap(1), fenceN, false, now)
        assertSame(batch, s.pending.inputs.single())
        assertTrue(s.series.isEmpty())
        assertEquals(setOf("fx:usd-krw"), s.pending.lostTopics)
        assertDiscarded("end", R.syncAccess(s, snap(2, userEnd = 1), fenceN), s.nextVersion)
        assertDiscarded("new scope", R.syncAccess(s, snap(2), fenceN2), s.nextVersion)

        val foreign = R.observe(ready(), fx(quote("kb", 1341.7, kst("20:01:00")), owner = fenceN2), null, snap(1), fenceN, true, now)
        assertTrue("an input of another scope is not attributed here", foreign.pending.inputs.isEmpty() && foreign.pending.lostTopics.isEmpty())
        val s1 = withKb()
        val foreignRefused = R.observe(s1, fx(quote("kb", 1343.0, kst("19:51:00")), owner = fenceN2), catalog, snap(1), fenceN, false, now)
        assertEquals("nor is its refusal recorded as a loss here", fingerprint(s1.series.getValue(kb)), fingerprint(foreignRefused.series.getValue(kb)))
    }

    // --- exposure, D3 and sourcing --------------------------------------------------------------------------------------

    /** K15: the same data is exposed to a consumer whose capture admits and not to an old one; an unconsumed end exposes nothing to either. */
    @Test fun K15_exposureIsCheckedPerConsumer() {
        val s = withKb()
        assertEquals(setOf(kb), R.exposed(s, snap(2), fenceN, true).keys)
        assertTrue(R.exposed(s, snap(2), fenceN, false).isEmpty())
        val ended = snap(3, userEnd = 1)
        assertTrue(R.exposed(s, ended, fenceN, true).isEmpty())
        assertTrue(R.exposed(s, ended, fenceN, false).isEmpty())
        assertTrue(R.exposed(s, snap(2), fenceN2, true).isEmpty())
    }

    /**
     * K16: D3 decides what an admitted response does - the latest version wins regardless of completion order, a duplicate
     * completion changes nothing, and a demand made after the capture is not released by it.
     */
    @Test fun K16_responsesKeepD3sRules() {
        val (s1, first) = R.captureRequests(withKb(), setOf(kb), snap(1), fenceN, true)
        val (s2, second) = R.captureRequests(s1, setOf(kb), snap(1), fenceN, true)
        val gap = R.observe(s2, fx(quote("kb", 1343.0, kst("20:01:50"))), catalog, snap(1), fenceN, false, now)
        val response = tab(listOf(closed(b, 1341.5)))
        val later = R.applyResponse(gap, second.single(), response, snap(1), fenceN, true, now)
        val both = R.applyResponse(later, first.single(), response, snap(1), fenceN, true, now)
        var expected = applyGraphRecoveryResponse(gap.series.getValue(kb), scopeN, second.single(), response, now)
        expected = applyGraphRecoveryResponse(expected, scopeN, first.single(), response, now)
        assertEquals(fingerprint(expected), fingerprint(both.series.getValue(kb)))
        assertEquals(2L, both.series.getValue(kb).lastAppliedVersion)
        assertTrue("the demand made after the capture stays", b in both.series.getValue(kb).pending.keys)
        val duplicate = R.applyResponse(both, second.single(), response, snap(1), fenceN, true, now)
        assertEquals(fingerprint(both.series.getValue(kb)), fingerprint(duplicate.series.getValue(kb)))
    }

    /**
     * K17: an empty batch or one that maps to nothing makes no series; what is kept is exactly the sourced observations -
     * FX in no priority order, the dollar index in DXY_GRAPH_OBSERVATION_ORDER - each first seen with INITIAL_SYNC.
     */
    @Test fun K17_onlySourcedInputIsKept() {
        val s0 = ready()
        for (batch in listOf(fx(), fx(quote("kb", 1341.7, kst("20:01:00"), asset = "jpy-krw")), fx(quote("citi", 1341.7, kst("20:01:00"))))) {
            assertTrue(R.observe(s0, batch, catalog, snap(1), fenceN, true, now).series.isEmpty())
        }
        var s = R.observe(s0, fx(quote("kb", 1341.7, kst("20:01:00")), quote("kb", 1341.6, kst("20:01:10"))), catalog, snap(1), fenceN, true, now)
        s = R.observe(s, dollar(105.2, kst("20:01:05")), catalog, snap(1), fenceN, true, now)
        assertEquals(setOf(kb, dxy), s.series.keys)
        assertEquals(
            fingerprint(fresh(kb, noOrder, observation(kb, "kb", "usd-krw", 1341.7, kst("20:01:00")), observation(kb, "kb", "usd-krw", 1341.6, kst("20:01:10")))),
            fingerprint(s.series.getValue(kb))
        )
        assertEquals(
            fingerprint(fresh(dxy, DXY_GRAPH_OBSERVATION_ORDER, observation(dxy, "investing", "dxy", 105.2, kst("20:01:05")))),
            fingerprint(s.series.getValue(dxy))
        )
        assertEquals(DXY_GRAPH_OBSERVATION_ORDER.sourcePriority, s.series.getValue(dxy).data.app.order.sourcePriority)

        val (captured, requests) = R.captureRequests(s, setOf(kb), snap(1), fenceN, true)
        assertEquals("only the given key", listOf(captureGraphRecoveryRequest(s.series.getValue(kb), 1L)), requests)
        assertEquals(1L, captured.nextVersion)
        for (key in listOf(kb, dxy)) {
            assertEquals("capture leaves $key as it was", fingerprint(s.series.getValue(key)), fingerprint(captured.series.getValue(key)))
        }
    }

    /**
     * K18: a loss over several buckets reaches each of them with the times in any order; markGraphPendingLoss adds topics to a
     * read-only set without touching the held inputs, and adding none changes nothing.
     */
    @Test fun K18_lossesCoverTheirWholeSpanAndHoldingsStayImmutable() {
        val s0 = withKb()
        val s = R.observe(s0, fx(quote("kb", 1343.0, kst("20:01:00")), quote("kb", 1343.1, kst("19:31:00"))), catalog, snap(1), fenceN, false, now)
        for (start in listOf("19:30:00", "19:40:00", "19:50:00", "20:00:00")) {
            assertTrue(start, GraphRecoveryReason.HANDOVER_LOSS in s.series.getValue(kb).pending.getValue(kst(start)).reasons)
        }
        val held = offerGraphPendingInput(GraphPendingInputs.EMPTY, fact())
        val marked = markGraphPendingLoss(held, setOf("fx:usd-krw", "dxy:spot"))
        assertEquals(setOf("fx:usd-krw", "dxy:spot"), marked.lostTopics)
        assertSame(held.inputs.single(), marked.inputs.single())
        assertTrue("the original is unchanged", held.lostTopics.isEmpty())
        val again = markGraphPendingLoss(marked, emptySet())
        assertEquals(marked.lostTopics, again.lostTopics)
        assertEquals(marked.inputs, again.inputs)
        assertTrue(runCatching { (marked.lostTopics as MutableSet<String>).add("x") }.isFailure)
    }
}
