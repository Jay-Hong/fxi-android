package com.jay.fxi.data.graph

import com.jay.fxi.data.graph.GraphObservationOutcome.ADDED
import com.jay.fxi.data.graph.GraphObservationOutcome.DUPLICATE
import com.jay.fxi.data.graph.GraphObservationOutcome.REJECTED
import com.jay.fxi.data.graph.GraphObservationOutcome.SAME_TIME_CONFLICT
import com.jay.fxi.data.graph.GraphRecoveryReason.HANDOVER_LOSS
import com.jay.fxi.data.graph.GraphRecoveryReason.INITIAL_SYNC
import com.jay.fxi.data.graph.GraphRecoveryReason.RECEIVE_GAP
import com.jay.fxi.data.graph.GraphRecoveryReason.TIME_ANOMALY
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphV2InProgress
import com.jay.fxi.domain.model.GraphV2Tab
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 D3 contract r1 (JVM): recovery demands, retention and size bounds around the D2 state.
 *
 * Oracles: ANDROID_V2_PLAN.md :1316-1329 (25-hour prune by the device now, never by a long-period response; size bounds on
 * evidence, identities and unrecovered buckets, and a recovery update whenever a bound or a time anomaly drops evidence; every
 * bucket a receive gap spans needs recovery; seed application, past gap history and closed-bucket demands kept apart; a demand
 * survives cache hit, cooldown, in-flight, failure and cancel; a response releases only the buckets it actually provided after
 * scope, generation, series and bucket checks - never everything up to lastClosedTs; a gap during a request is a new generation;
 * per scope·series·period·bucket application versions keep a late response from undoing a newer one), :1368 (gap history is not
 * recovery; sparse responses; size bounds and future times); server graph_v2_intraday.py:46,243 (the 1d window starts at
 * align(now - 24h)); iOS 89e866d GRAPH_LIVE_BAND_IMPLEMENTATION_PLAN.md :125-171 (gap history is display state that survives
 * a seed and does not hide server ranges).
 * Design: R4c/S4 d3_design_codex.r1, cut down by d3_review_claude.r1 after a five-lens verification
 * (d3_design_verify_workflow.result.json), then agreed in d3_review_codex.r2 / d3_review_claude.r2 / d3_review_codex.r3:
 * b = align(now), L = b-25h (data), Ld = b-24h (demands), U = b+600s (observations and demands).
 *  - A transition that creates or refreshes a demand raises the generation by exactly one and stamps only the demands it
 *    created or refreshed; a refreshed demand takes the new generation and the union of reasons. capture changes nothing.
 *  - require: closed slot range align(min)..align(max) cut to [Ld, U]; RECEIVE_GAP and HANDOVER_LOSS also enter the gap history.
 *  - observe: wrong series and implausible prices are rejected by D1 first and make no demand; slot < L is rejected without a
 *    demand; slot > U is rejected with TIME_ANOMALY on b; the rest goes to D2.
 *  - apply: a wrong request series or a tab D2 refuses (scope, period, bucket size, missing series) applies nothing, keeps the
 *    version and releases nothing; so does a version not above the last applied one. Otherwise D2 applies it and B is released
 *    iff this response supplied an on-grid point at B and pending[B].generation <= capturedGeneration. A valid empty series
 *    advances the version. Seeds never release. Gap history survives release.
 *  - retain ends every transition with the same now: (a) slots in [L, lastCutoff) cut to [Ld, U] get TIME_ANOMALY;
 *    (b) slot < L loses app buckets, server records, gap history and identities, slot < Ld loses its demand (expiry, not
 *    success); (c) while the slot union of app buckets, server records, demands and gap history exceeds 152, the furthest
 *    future slot loses everything incl. identities - and if it held an app bucket or a demand right before deletion, b gets
 *    TIME_ANOMALY; (d) a deleted tip becomes the latest remaining app close, or null; (e) more than 4,096 identities drop
 *    the oldest observedAt first (equal times: order not contracted), with no demand and no bucket or tip change;
 *    (f) lastCutoff = L. Running it twice with one now changes nothing.
 *  - retain runs only at the end: a response's normal release is reflected before deciding what an eviction loses (F21).
 *  - observe checks the time window before D2's duplicate check: a known observation outside [L, U] is still REJECTED (F17, F15).
 *
 * Completeness: before handing over, each sentence above was matched against violating mutations by a five-lens workflow
 * (d3_contract.r1/completeness_workflow.result.json); F22-F30 and the added assertions in F01-F21 close its survivors. Not
 * contracted, by reason: a version sentinel in place of null (F2 issues versions from 0 upward, so no request carries one),
 * and running (e) before (c) - it differs only when both bounds trip in one transition, in which redundant identity leaves;
 * buckets, tip and demands are the same.
 *
 * The implementation thread reads but does not edit this file.
 */
class GraphRecoverableStateTest {

    private val scope = GraphDataScope("u1", "e1")
    private val seriesId = "kb.usd"
    private val key = GraphObservationSeriesKey(scope, seriesId)

    private fun at(day: Int, hhmmss: String): Instant = Instant.parse("2026-10-${day.toString().padStart(2, '0')}T$hhmmss+09:00")
    private fun kst(hhmmss: String): Instant = at(6, hhmmss)
    private fun plusMillis(t: Instant, ms: Long): Instant = Instant.fromEpochMilliseconds(t.toEpochMilliseconds() + ms)
    private fun slots(first: Instant, last: Instant): List<Instant> =
        generateSequence(first) { Instant.fromEpochSeconds(it.epochSeconds + 600) }.takeWhile { it <= last }.toList()

    private val a = kst("19:50:00")
    private val b = kst("20:00:00")
    private val c = kst("20:10:00")

    private fun id(t: Instant, rate: Double) = GraphObservationId("kb", "usd-krw", t, rate)
    private fun obs(t: Instant, rate: Double) = GraphObservation(key, id(t, rate))
    private fun point(t: Instant, rate: Double, low: Double? = rate, high: Double? = rate) = FreeGraphPoint(t, rate, high, low)
    private fun seed(start: Instant, low: Double, high: Double, close: Double) = GraphV2InProgress(start, high, low, close, start)

    private fun tab(
        points: List<FreeGraphPoint> = emptyList(),
        seed: GraphV2InProgress? = null,
        period: GraphPeriod = GraphPeriod.ONE_DAY,
        bucketSize: String = "10min",
        seriesPresent: Boolean = true
    ) = GraphV2Tab(
        tab = "usd", period = period, bucketSize = bucketSize, fetchedAt = kst("20:05:00"),
        graph = FreeGraph(bucketSize, if (seriesPresent) listOf(FreeGraphSeries(seriesId, points)) else emptyList()),
        inProgress = if (seed != null && seriesPresent) mapOf(seriesId to seed) else emptyMap()
    )

    private fun empty() = GraphRecoverableState.empty(key, GraphObservationOrder(emptyList()))
    private fun require(s: GraphRecoverableState, from: Instant, to: Instant, reason: GraphRecoveryReason, now: Instant) =
        requireGraphRecovery(s, from, to, reason, now)
    private fun capture(s: GraphRecoverableState, version: Long) = captureGraphRecoveryRequest(s, version)
    private fun apply(s: GraphRecoverableState, r: GraphRecoveryRequest, t: GraphV2Tab, now: Instant) =
        applyGraphRecoveryResponse(s, scope, r, t, now)
    private fun observe(s: GraphRecoverableState, now: Instant, vararg o: GraphObservation) = observeRecoverable(s, o.toList(), now)
    private fun retain(s: GraphRecoverableState, now: Instant) = retainGraphRecoverable(s, now)
    private fun reasons(s: GraphRecoverableState) = s.pending.mapValues { it.value.reasons }

    private fun assertSameState(label: String, expected: GraphRecoverableState, actual: GraphRecoverableState) {
        assertEquals("$label pending", expected.pending, actual.pending)
        assertEquals("$label gap history", expected.gapHistory, actual.gapHistory)
        assertEquals("$label generation", expected.generation, actual.generation)
        assertEquals("$label version", expected.lastAppliedVersion, actual.lastAppliedVersion)
        assertEquals("$label cutoff", expected.lastCutoff, actual.lastCutoff)
        assertEquals("$label server", expected.data.serverBuckets, actual.data.serverBuckets)
        assertEquals("$label app", expected.data.app.buckets, actual.data.app.buckets)
        assertEquals("$label tip", expected.data.app.tip, actual.data.app.tip)
        assertEquals("$label identities", expected.data.app.observations, actual.data.app.observations)
    }

    /** F00: an empty state has no demand, history, version or cutoff and generation 0; capture reads it without change. */
    @Test fun F00_emptyStateAndCapture() {
        val s = empty()
        assertTrue(s.pending.isEmpty())
        assertTrue(s.gapHistory.isEmpty())
        assertEquals(0L, s.generation)
        assertNull(s.lastAppliedVersion)
        assertNull(s.lastCutoff)
        assertEquals(GraphRecoveryRequest(key, 7, 0), capture(s, 7))
    }

    // --- demands -----------------------------------------------------------------------------------------------------

    /**
     * F01 (D3-01): a 19:58-20:02 gap needs A and B, enters the gap history and keeps everything already recorded - B's
     * observation, A's server record, the identities and the tip.
     */
    @Test fun F01_aGapMarksEverySlotItSpansAndKeepsObservations() {
        val now = kst("20:05:00")
        var observed = observe(empty(), now, obs(kst("20:01:00"), 1342.1)).state
        observed = apply(observed, capture(observed, 0), tab(points = listOf(point(a, 1341.0))), now)
        val s = require(observed, kst("19:58:00"), kst("20:02:00"), RECEIVE_GAP, now)
        assertEquals(mapOf(a to setOf(RECEIVE_GAP), b to setOf(RECEIVE_GAP)), reasons(s))
        assertEquals(setOf(a, b), s.gapHistory)
        assertEquals(observed.data.app.buckets, s.data.app.buckets)
        assertEquals(observed.data.serverBuckets, s.data.serverBuckets)
        assertEquals(observed.data.app.observations, s.data.app.observations)
        assertEquals(observed.data.app.tip, s.data.app.tip)
        assertEquals(id(kst("20:01:00"), 1342.1), s.data.app.buckets.getValue(b).high)
        assertEquals(observed.generation + 1, s.generation)
        assertTrue(s.pending.values.all { it.generation == s.generation })
    }

    /** F02 (D3-02): both ends are inclusive, an instant still needs its slot, and a reversed span is the same span. */
    @Test fun F02_spansAreClosedSlotRanges() {
        val now = kst("20:05:00")
        for ((span, expected) in listOf(
            (kst("19:58:00") to kst("20:00:00")) to setOf(a, b),
            (kst("20:00:00") to kst("20:00:00")) to setOf(b),
            (kst("20:02:00") to kst("19:58:00")) to setOf(a, b)
        )) {
            assertEquals("$span", expected, require(empty(), span.first, span.second, RECEIVE_GAP, now).pending.keys)
        }
    }

    /**
     * F03 (D3-03): a request captures the generation; a new gap on B after it takes a newer generation and the union of reasons,
     * so the response releases A but keeps B.
     */
    @Test fun F03_aGapDuringARequestIsANewGeneration() {
        val now = kst("20:05:00")
        var s = require(empty(), kst("19:58:00"), kst("20:02:00"), RECEIVE_GAP, now)
        val r = capture(s, 1)
        assertEquals(GraphRecoveryRequest(key, 1, s.generation), r)
        val before = s.generation
        s = require(s, kst("20:03:00"), kst("20:04:00"), HANDOVER_LOSS, now)
        assertEquals(before + 1, s.generation)
        assertEquals(before, s.pending.getValue(a).generation)
        assertEquals(s.generation, s.pending.getValue(b).generation)
        s = apply(s, r, tab(points = listOf(point(a, 1341.0), point(b, 1341.8))), kst("20:10:30"))
        assertEquals(mapOf(b to GraphRecoveryDemand(before + 1, setOf(RECEIVE_GAP, HANDOVER_LOSS))), s.pending)
        assertEquals(before + 1, s.generation)
        assertEquals(setOf(a, b), s.gapHistory)
    }

    /** F04 (D3-04): a current seed folds but releases nothing; a seed for a slot that is no longer current does not move. */
    @Test fun F04_seedsNeverRelease() {
        val now = kst("20:05:00")
        val s0 = require(empty(), kst("19:58:00"), kst("20:02:00"), RECEIVE_GAP, now)
        val r = capture(s0, 1)
        val seeded = apply(s0, r, tab(seed = seed(b, 1341.7, 1341.9, 1341.8)), now)
        assertEquals(GraphServerBucket.Seeds(GraphBucketRange(1341.9, 1341.7), 1341.8), seeded.data.serverBuckets[b])
        assertEquals(reasons(s0), reasons(seeded))
        assertEquals(1L, seeded.lastAppliedVersion)
        val stale = apply(s0, r, tab(seed = seed(b, 1341.7, 1341.9, 1341.8)), kst("20:12:00"))
        assertNull(stale.data.serverBuckets[b])
        assertNull(stale.data.serverBuckets[c])
        assertEquals(reasons(s0), reasons(stale))
    }

    /**
     * F05 (D3-05): only the slots the response supplied are released, never the one between them. A missing series is a
     * rejection that keeps the version; a valid empty series advances it and releases nothing.
     */
    @Test fun F05_sparseResponsesAndEmptyVersusRejected() {
        val now = kst("20:09:00")
        val s0 = require(empty(), kst("19:58:00"), kst("20:12:00"), RECEIVE_GAP, now)
        assertEquals(setOf(a, b, c), s0.pending.keys)
        val sparse = apply(s0, capture(s0, 1), tab(points = listOf(point(a, 1341.0), point(c, 1342.0))), kst("20:25:00"))
        assertEquals(setOf(b), sparse.pending.keys)

        val r0 = capture(s0, 0)
        val r1 = capture(s0, 1)
        val missing = apply(s0, r1, tab(points = listOf(point(a, 1341.0)), seriesPresent = false), now)
        assertSameState("missing series", s0, missing)
        val thenOlder = apply(missing, r0, tab(points = listOf(point(a, 1341.0))), now)
        assertEquals(0L, thenOlder.lastAppliedVersion)
        assertEquals(setOf(b, c), thenOlder.pending.keys)

        val emptySeries = apply(s0, r1, tab(), now)
        assertEquals(1L, emptySeries.lastAppliedVersion)
        assertEquals(reasons(s0), reasons(emptySeries))
        val olderAfterEmpty = apply(emptySeries, r0, tab(points = listOf(point(a, 1341.0))), now)
        assertSameState("older after empty", emptySeries, olderAfterEmpty)
    }

    /**
     * F06 (D3-06): B already holds the same closed point; after a new gap, a response without B leaves B pending, the response
     * that supplies B again releases it, and the gap history stays.
     */
    @Test fun F06_releaseNeedsThisResponseNotAStateChange() {
        val now = kst("20:10:30")
        val closed = point(b, 1341.8, 1341.7, 1341.9)
        var s = apply(empty(), capture(empty(), 0), tab(points = listOf(closed)), now)
        s = require(s, kst("20:03:00"), kst("20:04:00"), RECEIVE_GAP, now)
        s = apply(s, capture(s, 1), tab(points = listOf(point(a, 1341.0))), now)
        assertEquals(setOf(b), s.pending.keys)
        s = apply(s, capture(s, 2), tab(points = listOf(closed)), now)
        assertTrue(s.pending.isEmpty())
        assertEquals(setOf(b), s.gapHistory)
        assertEquals(GraphServerBucket.Closed(closed), s.data.serverBuckets[b])
        // releasing every demand neither raises nor lowers the generation, and capture still reads it
        assertEquals(1L, s.generation)
        assertEquals(GraphRecoveryRequest(key, 3, 1), capture(s, 3))
    }

    /**
     * F07 (D3-07): a wrong scope, request series, period or bucket size changes nothing; an off-grid point is a valid response
     * that advances the version but records and releases nothing.
     */
    @Test fun F07_rejectedResponsesAndOffGridPoints() {
        val now = kst("20:05:00")
        val s0 = require(empty(), kst("19:58:00"), kst("20:02:00"), RECEIVE_GAP, now)
        val r = capture(s0, 1)
        val closedB = listOf(point(b, 1341.8))
        val rejected = mapOf(
            "scope" to applyGraphRecoveryResponse(s0, GraphDataScope("u2", "e1"), r, tab(points = closedB), now),
            "request series" to apply(s0, r.copy(seriesKey = GraphObservationSeriesKey(scope, "hana.usd")), tab(points = closedB), now),
            "request scope" to apply(
                s0, r.copy(seriesKey = GraphObservationSeriesKey(GraphDataScope("u2", "e1"), seriesId)), tab(points = closedB), now
            ),
            "period" to apply(s0, r, tab(points = closedB, period = GraphPeriod.ONE_WEEK), now),
            "bucket size" to apply(s0, r, tab(points = closedB, bucketSize = "5min"), now)
        )
        for ((label, s) in rejected) assertSameState(label, s0, s)

        val off = apply(s0, r, tab(points = listOf(point(plusMillis(b, 1), 1341.8))), now)
        assertEquals(1L, off.lastAppliedVersion)
        assertTrue(off.data.serverBuckets.isEmpty())
        assertEquals(reasons(s0), reasons(off))
    }

    /**
     * F08 (D3-08): after v2 applied, a late v1 changes nothing; when v2 never applied (failure), v1 applies. Ordering is by
     * the issued version, not by arrival.
     */
    @Test fun F08_aLateOlderResponseDoesNotUndoANewerOne() {
        val now = kst("20:05:00")
        val later = kst("20:10:30")
        val s0 = require(empty(), kst("19:58:00"), kst("20:02:00"), RECEIVE_GAP, now)
        val r1 = capture(s0, 1)
        val r2 = capture(s0, 2)
        val s2 = apply(s0, r2, tab(points = listOf(point(a, 1341.0))), later)
        assertEquals(setOf(b), s2.pending.keys)
        val late = apply(s2, r1, tab(points = listOf(point(a, 1340.0), point(b, 1341.8))), later)
        assertSameState("late v1", s2, late)

        val s1 = apply(s0, r1, tab(points = listOf(point(b, 1341.8))), later)
        assertEquals(setOf(a), s1.pending.keys)
        assertEquals(1L, s1.lastAppliedVersion)
    }

    /** F09 (D3-09): version 0 is accepted first; the same request delivered again changes nothing. */
    @Test fun F09_firstVersionZeroAndRedelivery() {
        val now = kst("20:10:30")
        var s = require(empty(), kst("19:58:00"), kst("20:02:00"), RECEIVE_GAP, now)
        val r0 = capture(s, 0)
        s = apply(s, r0, tab(points = listOf(point(a, 1341.0))), now)
        assertEquals(0L, s.lastAppliedVersion)
        assertEquals(setOf(b), s.pending.keys)
        s = require(s, kst("20:03:00"), kst("20:03:00"), RECEIVE_GAP, now)
        val again = apply(s, r0, tab(points = listOf(point(a, 1341.0), point(b, 1350.0))), now)
        assertSameState("redelivered v0", s, again)
    }

    // --- retention ---------------------------------------------------------------------------------------------------

    /**
     * F10 (D3-10): at 20:05 data keeps 10/05 19:00 and drops 18:50 (25 h), demands keep 10/05 20:00 and expire 19:50 (24 h),
     * gap history follows the data bound. A long-period response's last point does not prune anything.
     */
    @Test fun F10_dataKeeps25HoursDemandsKeep24Hours() {
        val earlier = kst("19:55:00")
        // 18:52 is neither the low, the high nor the close of its bucket: pruning must drop it with the bucket all the same
        var s = observe(
            empty(), earlier,
            obs(at(5, "18:51:00"), 1339.0), obs(at(5, "18:52:00"), 1339.5), obs(at(5, "18:55:00"), 1340.0),
            obs(at(5, "19:05:00"), 1340.5)
        ).state
        s = require(s, at(5, "19:50:00"), at(5, "20:00:00"), RECEIVE_GAP, earlier)
        assertEquals(setOf(at(5, "19:50:00"), at(5, "20:00:00")), s.pending.keys)
        assertEquals(setOf(at(5, "18:50:00"), at(5, "19:00:00")), s.data.app.buckets.keys)
        val longTab = tab(points = listOf(point(kst("20:00:00"), 1342.0)), period = GraphPeriod.ONE_WEEK, bucketSize = "1h")
        assertSameState("1w response", s, apply(s, capture(s, 1), longTab, earlier))

        s = retain(s, kst("20:05:00"))
        assertEquals(setOf(at(5, "19:00:00")), s.data.app.buckets.keys)
        assertEquals(setOf(id(at(5, "19:05:00"), 1340.5)), s.data.app.observations)
        assertEquals(setOf(at(5, "20:00:00")), s.pending.keys)
        assertEquals(setOf(at(5, "19:50:00"), at(5, "20:00:00")), s.gapHistory)
        assertEquals(1L, s.generation)
    }

    /**
     * F11 (D3-11): at 20:09:58 (b = B, U = C) observations at 20:10:00 and 20:19:59.999 enter C; 20:20:00 and 20:30:00 are
     * rejected and leave one TIME_ANOMALY on B in one generation; a gap in C can still be demanded. Outcomes keep input order
     * across every kind, D1's SAME_TIME_CONFLICT included. A rejected future observation is not remembered, so it is accepted
     * once its slot is in the window. A wrong series or scope, or an implausible price, far in the future makes no demand.
     */
    @Test fun F11_futureToleranceIsOneSlot() {
        val now = kst("20:09:58")
        val red = observe(
            empty(), now,
            obs(kst("20:10:00"), 1342.0),
            GraphObservation(GraphObservationSeriesKey(scope, "hana.usd"), id(kst("20:11:00"), 1342.0)),
            obs(at(5, "18:55:00"), 1340.0),
            obs(kst("20:20:00"), 1344.0),
            obs(kst("20:10:00"), 1342.5),
            obs(kst("20:10:00"), 1342.0),
            obs(kst("20:30:00"), 1345.0),
            obs(kst("20:19:59.999"), 1343.0)
        )
        assertEquals(listOf(ADDED, REJECTED, REJECTED, REJECTED, SAME_TIME_CONFLICT, DUPLICATE, REJECTED, ADDED), red.outcomes)
        assertEquals(setOf(c), red.state.data.app.buckets.keys)
        val bucketC = red.state.data.app.buckets.getValue(c)
        assertEquals(1343.0 to 1342.0, bucketC.high.rate to bucketC.low.rate)
        assertEquals(id(kst("20:19:59.999"), 1343.0), red.state.data.app.tip)
        assertEquals(
            setOf(id(kst("20:10:00"), 1342.0), id(kst("20:10:00"), 1342.5), id(kst("20:19:59.999"), 1343.0)),
            red.state.data.app.observations
        )
        assertEquals(mapOf(b to GraphRecoveryDemand(1, setOf(TIME_ANOMALY))), red.state.pending)
        assertTrue(red.state.gapHistory.isEmpty())
        assertEquals(1L, red.state.generation)

        val gapped = require(red.state, kst("20:10:30"), kst("20:12:00"), RECEIVE_GAP, now)
        assertEquals(mapOf(b to setOf(TIME_ANOMALY), c to setOf(RECEIVE_GAP)), reasons(gapped))

        val inWindow = observe(red.state, kst("20:20:30"), obs(kst("20:20:00"), 1344.0))
        assertEquals(listOf(ADDED), inWindow.outcomes)
        assertTrue(kst("20:20:00") in inWindow.state.data.app.buckets)

        val far = kst("20:30:00")
        val bad = listOf(
            GraphObservation(GraphObservationSeriesKey(scope, "hana.usd"), id(far, 1342.0)),
            GraphObservation(GraphObservationSeriesKey(GraphDataScope("u2", "e1"), seriesId), id(far, 1342.0)),
            obs(far, Double.NaN), obs(far, Double.POSITIVE_INFINITY), obs(far, 0.0), obs(far, -1.0), obs(far, 1e9)
        )
        val other = observeRecoverable(empty(), bad, now)
        assertEquals(List(bad.size) { REJECTED }, other.outcomes)
        assertTrue(other.state.pending.isEmpty())
        assertEquals(0L, other.state.generation)
    }

    /**
     * F12 (D3-12): 4,097 identities in one slot keep 4,096 by dropping the oldest - here the high (or the low). The bucket,
     * tip, demands and generation stay; redelivering the dropped identity changes none of them.
     */
    @Test fun F12_identityBoundDoesNotTouchEvidence() {
        val now = kst("20:05:00")
        for ((oldestRate, isHigh) in listOf(1350.0 to true, 1330.0 to false)) {
            val list = (0 until 4097).map { i -> obs(plusMillis(b, i.toLong()), if (i == 0) oldestRate else 1340.0) }
            val s = observe(empty(), now, *list.toTypedArray()).state
            val oldest = list.first().id
            assertEquals("$oldestRate", 4096, s.data.app.observations.size)
            assertFalse("$oldestRate", oldest in s.data.app.observations)
            val bucket = s.data.app.buckets.getValue(b)
            assertEquals("$oldestRate", oldest, if (isHigh) bucket.high else bucket.low)
            assertEquals("$oldestRate", list.last().id, bucket.close)
            assertEquals("$oldestRate", list.last().id, s.data.app.tip)
            assertTrue("$oldestRate", s.pending.isEmpty())
            assertEquals("$oldestRate", 0L, s.generation)

            val again = observe(s, now, list.first()).state
            assertEquals("$oldestRate", s.data.app.buckets, again.data.app.buckets)
            assertEquals("$oldestRate", s.data.app.tip, again.data.app.tip)
            assertTrue("$oldestRate", again.pending.isEmpty())
            assertEquals("$oldestRate", s.generation, again.generation)
            assertTrue("$oldestRate", again.data.app.observations.size <= 4096)
        }
    }

    /**
     * F13 (D3-13): a jump to 10/07 22:05 prunes everything; back at 10/06 20:05 all 146 demandable slots get TIME_ANOMALY in
     * one generation and the applied version stays. Regressions of a second, one slot or one hour expose nothing demandable;
     * 1 h 10 min exposes exactly 10/05 18:50.
     */
    @Test fun F13_clockRegressionReExposesPrunedSlots() {
        val now = kst("20:05:00")
        var s = observe(empty(), now, obs(kst("20:01:00"), 1342.1)).state
        s = apply(s, capture(s, 0), tab(points = listOf(point(a, 1341.0))), now)
        s = retain(s, at(7, "22:05:00"))
        assertTrue(s.data.app.buckets.isEmpty())
        assertTrue(s.data.serverBuckets.isEmpty())
        assertNull(s.data.app.tip)
        val before = s.generation
        s = retain(s, now)
        val expected = slots(at(5, "20:00:00"), c)
        assertEquals(146, expected.size)
        assertEquals(expected.associateWith { setOf(TIME_ANOMALY) }, reasons(s))
        assertEquals(before + 1, s.generation)
        assertTrue(s.pending.values.all { it.generation == s.generation })
        assertEquals(0L, s.lastAppliedVersion)
        assertTrue(s.gapHistory.isEmpty())
        assertEquals(at(5, "19:00:00"), s.lastCutoff)
        assertSameState("again after the return", s, retain(s, now))

        val base = retain(empty(), kst("20:05:30"))
        assertEquals(at(5, "19:00:00"), base.lastCutoff)
        for (back in listOf(kst("20:05:29"), kst("19:59:59"), kst("19:05:30"))) {
            val r = retain(base, back)
            assertTrue("$back", r.pending.isEmpty())
            assertEquals("$back", base.generation, r.generation)
        }
        val overHour = retain(base, kst("18:55:30"))
        assertEquals(mapOf(at(5, "18:50:00") to setOf(TIME_ANOMALY)), reasons(overHour))
        assertEquals(at(5, "17:50:00"), overHour.lastCutoff)
        assertSameState("again at the reversed time", overHour, retain(overHour, kst("18:55:30")))
    }

    /**
     * F14 (D3-14): 152 dense slots at 20:05 - C holds three observations and a server closed record - then the clock goes
     * back to 19:05 and an old observation opens 10/05 18:10. The furthest future slot C goes with every record and every
     * identity in it, b = 19:00 gets TIME_ANOMALY (the closed record does not make the lost app evidence harmless), the tip
     * falls back to B's close, the kept future slots keep their identities and the gap history stays empty. Back at 20:05
     * C's middle observation - neither its low, high nor close - can be delivered again.
     */
    @Test fun F14_slotUnionBoundEvictsTheFurthestFutureSlot() {
        val now = kst("20:05:00")
        val all = slots(at(5, "19:00:00"), c)
        assertEquals(152, all.size)
        val list = all.dropLast(1).mapIndexed { i, start -> obs(plusMillis(start, 60_000), 1340.0 + i * 0.01) }
        val inC = listOf(obs(kst("20:10:10"), 1341.0), obs(kst("20:10:20"), 1341.5), obs(kst("20:10:30"), 1343.0))
        var dense = observe(empty(), now, *(list + inC).toTypedArray()).state
        dense = apply(dense, capture(dense, 0), tab(points = listOf(point(c, 1342.0))), now)
        assertEquals(all.toSet(), dense.data.app.buckets.keys)
        assertEquals(inC.last().id, dense.data.app.tip)

        val red = observe(dense, kst("19:05:00"), obs(at(5, "18:10:30"), 1339.0))
        assertEquals(listOf(ADDED), red.outcomes)
        val s = red.state
        assertEquals(all.toSet() - c + at(5, "18:10:00"), s.data.app.buckets.keys)
        assertNull(s.data.serverBuckets[c])
        assertEquals(mapOf(kst("19:00:00") to setOf(TIME_ANOMALY)), reasons(s))
        assertTrue(s.gapHistory.isEmpty())
        assertEquals(list[150].id, s.data.app.tip)
        assertTrue(inC.none { it.id in s.data.app.observations })
        assertTrue(list.takeLast(6).all { it.id in s.data.app.observations })
        assertEquals(dense.generation + 1, s.generation)

        val restored = observe(s, now, inC[1])
        assertEquals(listOf(ADDED), restored.outcomes)
        assertTrue(c in restored.state.data.app.buckets)
        assertEquals(inC[1].id, restored.state.data.app.tip)
    }

    /**
     * F15 (eviction without loss): with the window dense, a server-only closed record past U (a slow device clock) is evicted
     * on every response without a demand. With room, records past U stay - a reversal alone deletes nothing, demands included.
     */
    @Test fun F15_serverOnlyEvictionMakesNoDemandAndReversalAloneDeletesNothing() {
        val now = kst("20:05:00")
        val dense = observe(empty(), now, *slots(at(5, "19:00:00"), c).map { obs(plusMillis(it, 60_000), 1340.0) }.toTypedArray()).state
        val future = kst("20:20:00")
        val s1 = apply(dense, capture(dense, 0), tab(points = listOf(point(future, 1342.0))), now)
        assertNull(s1.data.serverBuckets[future])
        assertTrue(s1.pending.isEmpty())
        assertEquals(dense.generation, s1.generation)
        assertEquals(0L, s1.lastAppliedVersion)
        assertEquals(dense.data.app.buckets, s1.data.app.buckets)
        val s2 = apply(s1, capture(s1, 1), tab(points = listOf(point(future, 1342.0))), now)
        assertTrue(s2.pending.isEmpty())
        assertEquals(dense.generation, s2.generation)

        val sparse = apply(empty(), capture(empty(), 0), tab(points = listOf(point(future, 1342.0))), now)
        assertEquals(GraphServerBucket.Closed(point(future, 1342.0)), sparse.data.serverBuckets[future])
        var kept = require(empty(), c, c, RECEIVE_GAP, now)
        kept = observe(kept, now, obs(kst("20:11:00"), 1342.0)).state
        kept = retain(kept, kst("19:05:00"))
        assertEquals(setOf(c), kept.pending.keys)
        assertEquals(setOf(c), kept.data.app.buckets.keys)
        assertEquals(setOf(c), kept.gapHistory)

        // the time window comes before D2's duplicate check: a known observation now past U is rejected with a demand
        val known = obs(kst("20:01:00"), 1342.1)
        val red = observe(observe(empty(), now, known).state, kst("19:45:00"), known)
        assertEquals(listOf(REJECTED), red.outcomes)
        assertEquals(mapOf(kst("19:40:00") to setOf(TIME_ANOMALY)), reasons(red.state))
        assertTrue(b in red.state.data.app.buckets)
    }

    /** F16 (D3-15): B closed and the late 20:09:59/1343 in either order, then retain: B shows closed, the tip is 1343. */
    @Test fun F16_retentionKeepsTheD2ClosedPrecedence() {
        val now = kst("20:10:30")
        val closed = point(b, 1341.8, 1341.7, 1341.9)
        val late = obs(kst("20:09:59"), 1343.0)
        val closedFirst = observe(apply(empty(), capture(empty(), 0), tab(points = listOf(closed)), now), now, late).state
        val observationFirst = apply(observe(empty(), now, late).state, capture(empty(), 0), tab(points = listOf(closed)), now)
        for ((label, s) in listOf("closed first" to closedFirst, "observation first" to observationFirst)) {
            val kept = retain(s, now)
            assertEquals(label, GraphComposedBucket.Closed(closed), composeGraphBucket(kept.data, b))
            assertEquals(label, late.id, kept.data.app.tip)
        }
    }

    /**
     * F17 (D3-16): an observation exactly at L is kept with its identity, one older than L is rejected; an empty observe and
     * a second retain with the same now change nothing; once the clock moves on, a known observation and a new one that fall
     * below the new L are both rejected, and a pruned bucket, its tip and identities do not come back on redelivery.
     */
    @Test fun F17_oldInputsDoNotReviveAndRetainIsIdempotent() {
        val now = kst("20:05:00")
        var s = require(empty(), kst("19:58:00"), kst("20:02:00"), RECEIVE_GAP, now)
        val atL = observe(s, now, obs(at(5, "19:00:00"), 1340.0), obs(at(5, "19:05:00"), 1340.0))
        assertEquals(listOf(ADDED, ADDED), atL.outcomes)
        s = atL.state
        assertTrue(id(at(5, "19:00:00"), 1340.0) in s.data.app.observations)
        val tooOld = observe(s, now, obs(at(5, "18:55:00"), 1340.0))
        assertEquals(listOf(REJECTED), tooOld.outcomes)
        assertSameState("too old", s, tooOld.state)
        val none = observe(s, now)
        assertTrue(none.outcomes.isEmpty())
        assertSameState("empty observe", s, none.state)

        val band = observe(s, kst("20:15:00"), obs(at(5, "19:05:00"), 1340.0), obs(at(5, "19:08:00"), 1341.0))
        assertEquals(listOf(REJECTED, REJECTED), band.outcomes)
        assertTrue(band.state.data.app.buckets.isEmpty())

        val once = retain(s, kst("20:15:00"))
        assertSameState("retain twice", once, retain(once, kst("20:15:00")))
        assertTrue(once.data.app.buckets.isEmpty())
        assertNull(once.data.app.tip)
        assertTrue(once.data.app.observations.isEmpty())
        assertEquals(setOf(a, b), once.pending.keys)
        val redelivered = observe(once, kst("20:15:00"), obs(at(5, "19:05:00"), 1340.0))
        assertEquals(listOf(REJECTED), redelivered.outcomes)
        assertNull(redelivered.state.data.app.tip)
    }

    /**
     * F18: every reason makes a demand in a new generation; only RECEIVE_GAP and HANDOVER_LOSS enter the gap history - also
     * when they refresh a slot already pending for another reason - and the history is pruned with the data at L.
     */
    @Test fun F18_gapHistoryReasonsAndPrune() {
        val now = kst("20:05:00")
        for (reason in GraphRecoveryReason.values()) {
            val s = require(empty(), a, a, reason, now)
            assertEquals("$reason", GraphRecoveryDemand(1, setOf(reason)), s.pending.getValue(a))
            assertEquals("$reason", 1L, s.generation)
            assertEquals("$reason", reason == RECEIVE_GAP || reason == HANDOVER_LOSS, a in s.gapHistory)
        }
        val refreshed = require(require(empty(), a, a, INITIAL_SYNC, now), a, a, RECEIVE_GAP, now)
        assertEquals(GraphRecoveryDemand(2, setOf(INITIAL_SYNC, RECEIVE_GAP)), refreshed.pending.getValue(a))
        assertEquals(setOf(a), refreshed.gapHistory)
        val h = require(empty(), at(5, "20:00:00"), at(5, "20:00:00"), RECEIVE_GAP, now)
        assertEquals(setOf(at(5, "20:00:00")), retain(h, kst("21:05:00")).gapHistory)
        assertTrue(retain(h, kst("21:15:00")).gapHistory.isEmpty())
    }

    /**
     * F19: a span outside [Ld, U] changes nothing, not even the generation; a span crossing a bound is cut to it, in the
     * demands and in the gap history alike. The window comes from require's own now, and require ends with retain.
     */
    @Test fun F19_spansAreCutToTheDemandWindow() {
        val now = kst("20:05:00")
        val base = retain(empty(), now)
        assertSameState("below", base, require(base, at(5, "19:00:00"), at(5, "19:59:59"), RECEIVE_GAP, now))
        assertSameState("above", base, require(base, kst("20:20:00"), kst("20:40:00"), RECEIVE_GAP, now))
        val low = require(base, at(5, "19:40:00"), at(5, "20:05:00"), RECEIVE_GAP, now)
        assertEquals(setOf(at(5, "20:00:00")), low.pending.keys)
        assertEquals(setOf(at(5, "20:00:00")), low.gapHistory)
        val high = require(base, kst("20:05:00"), kst("20:40:00"), RECEIVE_GAP, now)
        assertEquals(setOf(b, c), high.pending.keys)
        assertEquals(setOf(b, c), high.gapHistory)

        val withOld = observe(base, now, obs(at(5, "19:05:00"), 1340.0)).state
        val moved = require(withOld, kst("20:30:00"), kst("20:30:00"), RECEIVE_GAP, kst("20:25:00"))
        assertEquals(setOf(kst("20:30:00")), moved.pending.keys)
        assertEquals(at(5, "19:20:00"), moved.lastCutoff)
        assertTrue(moved.data.app.buckets.isEmpty())
    }

    /**
     * F20: the union counts demands and gap history. Evicting a future slot that held only a demand leaves TIME_ANOMALY on b;
     * one that held only a released server record and its history leaves none; a margin slot holding only history still
     * counts toward the bound.
     */
    @Test fun F20_theUnionCountsDemandsAndHistory() {
        val now = kst("20:05:00")
        val back = kst("19:05:00")
        val margin = obs(at(5, "18:10:30"), 1339.0)
        val base = observe(empty(), now, *slots(at(5, "19:00:00"), b).map { obs(plusMillis(it, 60_000), 1340.0) }.toTypedArray()).state
        assertEquals(151, base.data.app.buckets.size)

        for (reason in GraphRecoveryReason.values()) {
            val demandOnly = observe(require(base, c, c, reason, now), back, margin).state
            assertFalse("$reason", c in demandOnly.pending)
            assertFalse("$reason", c in demandOnly.gapHistory)
            assertEquals("$reason", mapOf(kst("19:00:00") to setOf(TIME_ANOMALY)), reasons(demandOnly))
        }

        var released = require(base, c, c, RECEIVE_GAP, now)
        released = apply(released, capture(released, 0), tab(points = listOf(point(c, 1342.0))), now)
        assertTrue(released.pending.isEmpty())
        assertEquals(setOf(c), released.gapHistory)
        val q = observe(released, back, margin).state
        assertNull(q.data.serverBuckets[c])
        assertTrue(q.gapHistory.isEmpty())
        assertTrue(q.pending.isEmpty())
        assertEquals(released.generation, q.generation)

        val x = at(5, "19:00:00")
        var h = require(empty(), x, x, RECEIVE_GAP, kst("19:05:00"))
        h = observe(h, now, *slots(at(5, "19:10:00"), c).map { obs(plusMillis(it, 60_000), 1340.0) }.toTypedArray()).state
        assertTrue(h.pending.isEmpty())
        assertEquals(setOf(x), h.gapHistory)
        assertEquals(151, h.data.app.buckets.size)
        val evicted = observe(h, back, margin).state
        assertFalse(c in evicted.data.app.buckets)
        assertEquals(mapOf(kst("19:00:00") to setOf(TIME_ANOMALY)), reasons(evicted))
    }

    /**
     * F21 (d3_review_codex.r3, end-only retain): 151 server records, Y = C pending (INITIAL_SYNC) only, a request captured,
     * then the clock goes back to 18:05 and the response supplies Y. The response releases Y first; the six re-exposed slots
     * then push out six server-only future slots, so b = 18:00 gets no demand.
     */
    @Test fun F21_aResponseReleasesBeforeEvictionDecidesWhatIsLost() {
        val now = kst("20:05:00")
        val records = slots(at(5, "19:00:00"), b)
        assertEquals(151, records.size)
        var s = apply(empty(), capture(empty(), 0), tab(points = records.map { point(it, 1340.0) }), now)
        s = require(s, c, c, INITIAL_SYNC, now)
        assertEquals(setOf(c), s.pending.keys)
        val r = capture(s, 1)
        val before = s.generation

        val back = apply(s, r, tab(points = listOf(point(c, 1342.0))), kst("18:05:00"))
        val reExposed = slots(at(5, "18:00:00"), at(5, "18:50:00"))
        assertEquals(reExposed.associateWith { setOf(TIME_ANOMALY) }, reasons(back))
        assertEquals(before + 1, back.generation)
        assertEquals(slots(at(5, "19:00:00"), kst("19:10:00")).toSet(), back.data.serverBuckets.keys)
        assertEquals(1L, back.lastAppliedVersion)

        // a re-exposed slot the same response fills still gets its demand: (a) does not look at the records
        val filledPoint = point(at(5, "18:50:00"), 1339.0)
        val filled = apply(s, r, tab(points = listOf(point(c, 1342.0), filledPoint)), kst("18:05:00"))
        assertEquals(reExposed.associateWith { setOf(TIME_ANOMALY) }, reasons(filled))
        assertEquals(GraphServerBucket.Closed(filledPoint), filled.data.serverBuckets[at(5, "18:50:00")])
    }

    // --- completeness rows (d3_contract.r1/completeness_workflow.result.json) ------------------------------------------

    /** F22: a new gap on B for the same reason is still a refresh: new generation, so the earlier request cannot release B. */
    @Test fun F22_aSameReasonGapStillRefreshesTheGeneration() {
        val now = kst("20:05:00")
        var s = require(empty(), kst("19:58:00"), kst("20:02:00"), RECEIVE_GAP, now)
        val r = capture(s, 1)
        s = require(s, kst("20:03:00"), kst("20:04:00"), RECEIVE_GAP, now)
        assertEquals(2L, s.generation)
        assertEquals(GraphRecoveryDemand(1, setOf(RECEIVE_GAP)), s.pending.getValue(a))
        assertEquals(GraphRecoveryDemand(2, setOf(RECEIVE_GAP)), s.pending.getValue(b))
        s = apply(s, r, tab(points = listOf(point(a, 1341.0), point(b, 1341.8))), kst("20:10:30"))
        assertEquals(mapOf(b to setOf(RECEIVE_GAP)), reasons(s))
    }

    /**
     * F23: every apply ends with retain at its own now - a rejected tab, a missing series, a stale version and a valid empty
     * series alike prune the old bucket and move lastCutoff without touching the demands. Records a response writes below L
     * are pruned in the same apply.
     */
    @Test fun F23_everyApplyPathEndsWithRetain() {
        val now = kst("20:05:00")
        val later = kst("20:15:00")
        var s0 = observe(empty(), now, obs(at(5, "19:05:00"), 1340.0)).state
        s0 = require(s0, kst("19:58:00"), kst("20:02:00"), RECEIVE_GAP, now)
        s0 = apply(s0, capture(s0, 5), tab(), now)
        assertEquals(5L, s0.lastAppliedVersion)
        val paths = mapOf(
            "wrong scope" to applyGraphRecoveryResponse(s0, GraphDataScope("u2", "e1"), capture(s0, 6), tab(), later),
            "missing series" to apply(s0, capture(s0, 6), tab(seriesPresent = false), later),
            "stale version" to apply(s0, capture(s0, 5), tab(points = listOf(point(a, 1341.0))), later),
            "valid empty" to apply(s0, capture(s0, 6), tab(), later)
        )
        for ((label, s) in paths) {
            assertTrue(label, s.data.app.buckets.isEmpty())
            assertEquals(label, at(5, "19:10:00"), s.lastCutoff)
            assertEquals(label, setOf(a, b), s.pending.keys)
        }

        val old = apply(
            empty(), capture(empty(), 0),
            tab(points = listOf(point(at(5, "18:50:00"), 1339.0), point(at(5, "19:00:00"), 1339.5))), now
        )
        assertEquals(setOf(at(5, "19:00:00")), old.data.serverBuckets.keys)
    }

    /**
     * F24: release ignores the reasons; the response gate is D2's - this series by id wherever it sits, a seed without its
     * series is a rejection, and the tab's bucket size decides, not the graph's.
     */
    @Test fun F24_theResponseGateIsD2sAndReleaseIgnoresReasons() {
        val now = kst("20:05:00")
        var s0 = require(empty(), a, a, TIME_ANOMALY, now)
        s0 = require(s0, b, b, HANDOVER_LOSS, now)
        s0 = require(s0, c, c, INITIAL_SYNC, now)
        val r = capture(s0, 1)
        val all = apply(s0, r, tab(points = listOf(point(a, 1341.0), point(b, 1341.8), point(c, 1342.0))), kst("20:25:00"))
        assertTrue(all.pending.isEmpty())

        fun manual(graphSize: String, series: List<FreeGraphSeries>, inProgress: Map<String, GraphV2InProgress> = emptyMap()) =
            GraphV2Tab("usd", GraphPeriod.ONE_DAY, "10min", now, FreeGraph(graphSize, series), inProgress)
        val second = apply(
            s0, r,
            manual("10min", listOf(FreeGraphSeries("hana.usd", listOf(point(b, 1341.8))), FreeGraphSeries(seriesId, listOf(point(a, 1341.0))))),
            now
        )
        assertEquals(setOf(b, c), second.pending.keys)
        assertEquals(setOf(a), second.data.serverBuckets.keys)
        val seedOnly = apply(
            s0, r, manual("10min", listOf(FreeGraphSeries("hana.usd", emptyList())), mapOf(seriesId to seed(b, 1341.7, 1341.9, 1341.8))), now
        )
        assertSameState("seed without series", s0, seedOnly)
        val graphSize = apply(s0, r, manual("5min", listOf(FreeGraphSeries(seriesId, listOf(point(a, 1341.0))))), now)
        assertEquals(setOf(b, c), graphSize.pending.keys)
        assertEquals(1L, graphSize.lastAppliedVersion)
    }

    /**
     * F25: a TIME_ANOMALY that lands on a pending slot refreshes it like any demand - new generation, reasons merged - and
     * leaves the other demands in their generation. Both on the observe path (a far-future observation) and on the
     * eviction path, where b also already holds a server closed record.
     */
    @Test fun F25_timeAnomaliesRefreshPendingSlots() {
        val now = kst("20:09:58")
        var s = require(empty(), kst("19:58:00"), kst("20:01:00"), RECEIVE_GAP, now)
        val r = capture(s, 1)
        s = observe(s, now, obs(kst("20:20:00"), 1344.0)).state
        assertEquals(2L, s.generation)
        assertEquals(GraphRecoveryDemand(1, setOf(RECEIVE_GAP)), s.pending.getValue(a))
        assertEquals(GraphRecoveryDemand(2, setOf(RECEIVE_GAP, TIME_ANOMALY)), s.pending.getValue(b))
        s = apply(s, r, tab(points = listOf(point(a, 1341.0), point(b, 1341.8))), now)
        assertEquals(mapOf(b to setOf(RECEIVE_GAP, TIME_ANOMALY)), reasons(s))

        val then = kst("20:05:00")
        val back = kst("19:05:00")
        val b19 = kst("19:00:00")
        var dense = observe(empty(), then, *slots(at(5, "19:00:00"), c).map { obs(plusMillis(it, 60_000), 1340.0) }.toTypedArray()).state
        dense = apply(dense, capture(dense, 0), tab(points = listOf(point(b19, 1340.0))), then)
        var e = require(dense, kst("18:01:00"), kst("19:01:00"), RECEIVE_GAP, then)
        val er = capture(e, 1)
        e = observe(e, back, obs(at(5, "18:10:30"), 1339.0)).state
        assertEquals(2L, e.generation)
        assertEquals(GraphRecoveryDemand(2, setOf(RECEIVE_GAP, TIME_ANOMALY)), e.pending.getValue(b19))
        assertEquals(GraphRecoveryDemand(1, setOf(RECEIVE_GAP)), e.pending.getValue(kst("18:50:00")))
        e = apply(e, er, tab(points = slots(kst("18:00:00"), b19).map { point(it, 1340.0) }), back)
        assertEquals(mapOf(b19 to setOf(RECEIVE_GAP, TIME_ANOMALY)), reasons(e))
    }

    /**
     * F26: demands from the event and from the end retain of one transition share one new generation. A require on a slot
     * the clock reversal re-exposes merges both reasons; an observation in a re-exposed slot does not spare it the demand;
     * a require that pushes the union over the bound evicts in its own end retain.
     */
    @Test fun F26_oneTransitionOneGeneration() {
        val base = retain(empty(), kst("20:05:30"))
        val back = kst("18:55:30")
        val s = require(base, at(5, "18:51:00"), at(5, "19:01:00"), RECEIVE_GAP, back)
        assertEquals(
            mapOf(
                at(5, "18:50:00") to GraphRecoveryDemand(1, setOf(RECEIVE_GAP, TIME_ANOMALY)),
                at(5, "19:00:00") to GraphRecoveryDemand(1, setOf(RECEIVE_GAP))
            ),
            s.pending
        )
        assertEquals(1L, s.generation)
        assertEquals(setOf(at(5, "18:50:00"), at(5, "19:00:00")), s.gapHistory)

        val o = observe(base, back, obs(at(5, "18:51:00"), 1339.0), obs(kst("19:10:00"), 1344.0))
        assertEquals(listOf(ADDED, REJECTED), o.outcomes)
        assertEquals(setOf(at(5, "18:50:00")), o.state.data.app.buckets.keys)
        assertEquals(
            mapOf(
                at(5, "18:50:00") to GraphRecoveryDemand(1, setOf(TIME_ANOMALY)),
                kst("18:50:00") to GraphRecoveryDemand(1, setOf(TIME_ANOMALY))
            ),
            o.state.pending
        )
        assertEquals(1L, o.state.generation)

        val now = kst("20:05:00")
        val rev = kst("19:05:00")
        val holes = slots(at(5, "19:00:00"), c) - kst("18:00:00")
        var h = observe(empty(), now, *holes.map { obs(plusMillis(it, 60_000), 1340.0) }.toTypedArray()).state
        h = observe(h, rev, obs(at(5, "18:10:30"), 1339.0)).state
        assertTrue(c in h.data.app.buckets)
        h = require(h, kst("18:01:00"), kst("18:01:00"), RECEIVE_GAP, rev)
        assertFalse(c in h.data.app.buckets)
        assertEquals(
            mapOf(kst("18:00:00") to GraphRecoveryDemand(1, setOf(RECEIVE_GAP)), kst("19:00:00") to GraphRecoveryDemand(1, setOf(TIME_ANOMALY))),
            h.pending
        )
    }

    /**
     * F27: the identity bound is global and drops by observedAt, not by insertion: 2,049 + 2,049 identities over two slots,
     * the two oldest delivered last, keep 4,096 without those two; an old identity arriving after 4,096 newer ones is the one
     * dropped, while its bucket stays.
     */
    @Test fun F27_identityBoundOrdersByObservedAt() {
        val now = kst("20:05:00")
        val inA = (0 until 2049).map { i -> obs(plusMillis(a, i.toLong()), 1340.0) }
        val inB = (0 until 2049).map { i -> obs(plusMillis(b, i.toLong()), 1341.0) }
        val s = observe(empty(), now, *(inB + inA.reversed()).toTypedArray()).state
        assertEquals(4096, s.data.app.observations.size)
        assertFalse(inA[0].id in s.data.app.observations)
        assertFalse(inA[1].id in s.data.app.observations)
        assertTrue(inA[2].id in s.data.app.observations)
        assertTrue(inB[0].id in s.data.app.observations)

        val newer = (0 until 4096).map { i -> obs(plusMillis(b, i.toLong()), 1341.0) }
        val full = observe(empty(), now, *newer.toTypedArray()).state
        val late = obs(kst("19:59:00"), 1340.0)
        val after = observe(full, now, late)
        assertEquals(listOf(ADDED), after.outcomes)
        assertEquals(4096, after.state.data.app.observations.size)
        assertFalse(late.id in after.state.data.app.observations)
        assertTrue(newer[0].id in after.state.data.app.observations)
        assertTrue(a in after.state.data.app.buckets)
    }

    /**
     * F28: the bound is checked again after b's demand: with b holding no record, evicting C and adding b's demand would
     * leave 153, so the next furthest slot (20:00) goes as well.
     */
    @Test fun F28_theUnionIsCheckedAgainAfterTheDemandOnB() {
        val now = kst("20:05:00")
        val holes = slots(at(5, "19:00:00"), c) - kst("19:00:00")
        assertEquals(151, holes.size)
        var s = observe(empty(), now, *holes.map { obs(plusMillis(it, 60_000), 1340.0) }.toTypedArray()).state
        s = observe(s, kst("19:05:00"), obs(at(5, "18:10:30"), 1339.0), obs(at(5, "18:20:30"), 1339.0)).state
        assertEquals(holes.toSet() - c - b + at(5, "18:10:00") + at(5, "18:20:00"), s.data.app.buckets.keys)
        assertEquals(mapOf(kst("19:00:00") to setOf(TIME_ANOMALY)), reasons(s))
    }

    /**
     * F29: expiry (slot < Ld) and the gap-history prune (slot < L) come before the bound: the stale 10/05 20:00 entry does
     * not push out the server record at 21:30.
     */
    @Test fun F29_expiryAndHistoryPruneComeBeforeTheBound() {
        val t0 = kst("20:05:00")
        val t1 = kst("21:15:00")
        val future = kst("21:30:00")
        var s = require(empty(), at(5, "20:00:00"), at(5, "20:00:00"), RECEIVE_GAP, t0)
        s = apply(s, capture(s, 0), tab(points = listOf(point(future, 1342.0))), t0)
        val window = slots(at(5, "20:10:00"), kst("21:10:00"))
        assertEquals(151, window.size)
        s = observe(s, t1, *window.map { obs(plusMillis(it, 60_000), 1340.0) }.toTypedArray()).state
        assertTrue(s.pending.isEmpty())
        assertTrue(s.gapHistory.isEmpty())
        assertEquals(GraphServerBucket.Closed(point(future, 1342.0)), s.data.serverBuckets[future])
    }

    /** F30: when eviction removes the only app bucket - the tip's - the tip becomes null. */
    @Test fun F30_evictingTheLastAppBucketClearsTheTip() {
        val now = kst("20:05:00")
        var s = apply(empty(), capture(empty(), 0), tab(points = slots(at(5, "19:00:00"), b).map { point(it, 1340.0) }), now)
        s = observe(s, now, obs(kst("20:11:00"), 1342.0)).state
        assertEquals(id(kst("20:11:00"), 1342.0), s.data.app.tip)
        s = apply(s, capture(s, 1), tab(points = listOf(point(at(5, "18:10:00"), 1339.0))), kst("19:05:00"))
        assertTrue(s.data.app.buckets.isEmpty())
        assertNull(s.data.app.tip)
        assertEquals(mapOf(kst("19:00:00") to setOf(TIME_ANOMALY)), reasons(s))
    }
}
