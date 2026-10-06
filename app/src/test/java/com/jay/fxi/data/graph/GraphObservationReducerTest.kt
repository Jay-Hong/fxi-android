package com.jay.fxi.data.graph

import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 D1 contract r1 (JVM): the pure observation and 600-second bucket model - which bucket an observation belongs
 * to, what counts as the same observation, and which observation is a bucket's high, low and close and the series' tip.
 *
 * Oracles: ANDROID_V2_PLAN.md :1299-1304 (a bucket is assigned by the observation's own server time - never the app's receive
 * time or now, and the time is never corrected toward now), :1311-1316 (close = the bucket's observation with the latest
 * observation time; a same-time price conflict follows a rule fixed per adapter; timers make no observation and no empty-bucket
 * high/low); the server's `graph_v2_intraday._bucket_align` (`ts - ts % 600`, floor); iOS 89e866d
 * GRAPH_LIVE_BAND_IMPLEMENTATION_PLAN.md :250-252, :265-266 (T02, T04, T17, T18). Design: R4c/S4 c13b_d1_codex.r1 §B-§D as
 * reviewed by d1_review_claude.r1 (no provenance in the reducer; identity = (source, asset, observedAt, rate) within one
 * (scope, series); a source priority fixed when the state is made, unlisted sources after every listed one and equal among
 * themselves, so an empty list keeps the first adopted; no clock anywhere). Seed merge, closed-bucket replacement, prune and
 * size limits (D2, D3), the real FX/DXY adapters (E1, E2) and timers (C2) are not here: passing D1 does not complete those T's.
 *
 * The implementation thread reads but does not edit this file.
 */
class GraphObservationReducerTest {

    private val scope = GraphDataScope("u1", "e1")
    private val key = GraphObservationSeriesKey(scope, "kb.usd")
    private fun kst(hhmmss: String): Instant = Instant.parse("2026-10-06T${hhmmss}+09:00")
    private val b1950 = kst("19:50:00")
    private val b2000 = kst("20:00:00")
    private val b2010 = kst("20:10:00")

    private fun id(at: Instant, rate: Double, source: String = "kb", asset: String = "usd-krw") =
        GraphObservationId(source, asset, at, rate)
    private fun obs(at: Instant, rate: Double, source: String = "kb", asset: String = "usd-krw", seriesKey: GraphObservationSeriesKey = key) =
        GraphObservation(seriesKey, id(at, rate, source, asset))

    private fun empty(order: GraphObservationOrder = GraphObservationOrder(emptyList())) = GraphObservationState.empty(key, order)
    private fun reduce(state: GraphObservationState, vararg o: GraphObservation) = reduceGraphObservations(state, o.toList())
    private fun range(state: GraphObservationState, start: Instant) =
        state.buckets.getValue(start).let { it.low.rate to it.high.rate }

    private val ADDED = GraphObservationOutcome.ADDED
    private val DUPLICATE = GraphObservationOutcome.DUPLICATE
    private val CONFLICT = GraphObservationOutcome.SAME_TIME_CONFLICT
    private val REJECTED = GraphObservationOutcome.REJECTED

    /** D01: the bucket start is the epoch floor to 600 seconds, below zero too, with the boundary in the next bucket. */
    @Test fun D01_theBucketStartIsTheEpochFloorTo600Seconds() {
        assertEquals(Instant.fromEpochSeconds(-600), graphObservationBucketStart(Instant.fromEpochMilliseconds(-1)))
        assertEquals(Instant.fromEpochSeconds(0), graphObservationBucketStart(Instant.fromEpochMilliseconds(0)))
        assertEquals(Instant.fromEpochSeconds(0), graphObservationBucketStart(Instant.fromEpochMilliseconds(599_999)))
        assertEquals(Instant.fromEpochSeconds(600), graphObservationBucketStart(Instant.fromEpochMilliseconds(600_000)))
        assertEquals(b2000, graphObservationBucketStart(kst("20:09:59.999")))
    }

    /** D02 (T02): observations received at 20:02 but observed before 20:00 belong to the earlier bucket only. */
    @Test fun D02_anObservationGoesToItsOwnTimesBucketNotTheReceivingOne() {
        val r = reduce(empty(), obs(kst("19:59:58"), 1342.0), obs(kst("19:59:55"), 1342.0))
        assertEquals(listOf(ADDED, ADDED), r.outcomes)
        assertEquals(setOf(b1950), r.state.buckets.keys)
        assertEquals(id(kst("19:59:58"), 1342.0), r.state.buckets.getValue(b1950).close)
    }

    /** D03 (T04): a price a millisecond before the boundary and the next at the boundary make two buckets, nothing carried. */
    @Test fun D03_theBoundarySplitsTwoBucketsWithoutCarryingAPrice() {
        val r = reduce(empty(), obs(kst("19:59:59.999"), 1342.0), obs(kst("20:00:00"), 1341.5))
        assertEquals(1342.0 to 1342.0, range(r.state, b1950))
        assertEquals(1341.5 to 1341.5, range(r.state, b2000))
    }

    /**
     * D04 (T17): an old high (and, separately, an old low) delivered again and again makes no new bucket; only a new
     * observation does, and its bucket starts from that observation alone.
     */
    @Test fun D04_aRepeatedOldExtremeMakesNoNewBucket() {
        for (old in listOf(1345.7, 1337.7)) {
            val first = reduce(empty(), obs(kst("19:59:10"), old))
            val again = reduce(first.state, obs(kst("19:59:10"), old), obs(kst("19:59:10"), old))
            assertEquals("$old again", listOf(DUPLICATE, DUPLICATE), again.outcomes)
            assertEquals("$old: no bucket before a new observation", setOf(b1950), again.state.buckets.keys)
            val fresh = reduce(again.state, obs(kst("20:03:00"), 1341.7))
            assertEquals("$old: the new bucket is the new observation only", 1341.7 to 1341.7, range(fresh.state, b2000))
            assertEquals(old to old, range(fresh.state, b1950))
        }
    }

    /** D05 (T18): a fall and, separately, a rise inside one bucket widen it, and the later one is its close. */
    @Test fun D05_aFallOrARiseWidensTheBucketAndTheLaterIsTheClose() {
        val down = reduce(empty(), obs(kst("20:00:00"), 1342.0), obs(kst("20:01:00"), 1341.5)).state
        assertEquals(1341.5 to 1342.0, range(down, b2000))
        assertEquals(id(kst("20:01:00"), 1341.5), down.buckets.getValue(b2000).close)
        val up = reduce(empty(), obs(kst("20:00:00"), 1342.0), obs(kst("20:01:00"), 1342.5)).state
        assertEquals(1342.0 to 1342.5, range(up, b2000))
        assertEquals(id(kst("20:01:00"), 1342.5), up.buckets.getValue(b2000).close)
    }

    /**
     * D06: out of order, the latest observation time is the close and the tip - to the millisecond - while an earlier arrival
     * still widens the range, and a much older one keeps its own bucket.
     */
    @Test fun D06_theLatestObservationTimeIsTheCloseWhateverTheArrivalOrder() {
        val r = reduce(empty(), obs(kst("20:04:50.900"), 1341.7), obs(kst("20:04:50.100"), 1343.0), obs(kst("19:59:00"), 1340.0))
        assertEquals(listOf(ADDED, ADDED, ADDED), r.outcomes)
        assertEquals(1341.7 to 1343.0, range(r.state, b2000))
        assertEquals(id(kst("20:04:50.900"), 1341.7), r.state.buckets.getValue(b2000).close)
        assertEquals(id(kst("20:04:50.900"), 1341.7), r.state.tip)
        assertEquals(1340.0 to 1340.0, range(r.state, b1950))
    }

    /** D07: the same observation delivered again later - with another in between - is one observation, changing nothing. */
    @Test fun D07_aRedeliveryIsOneObservationEvenAfterAnother() {
        val r = reduce(empty(), obs(kst("20:02:00"), 1342.0), obs(kst("20:03:00"), 1343.0), obs(kst("20:02:00"), 1342.0))
        assertEquals(listOf(ADDED, ADDED, DUPLICATE), r.outcomes)
        assertEquals(setOf(id(kst("20:02:00"), 1342.0), id(kst("20:03:00"), 1343.0)), r.state.observations)
        assertEquals(1342.0 to 1343.0, range(r.state, b2000))
        assertEquals(id(kst("20:03:00"), 1343.0), r.state.buckets.getValue(b2000).close)
    }

    /** D08: the same price at a new time is a new observation - it fills the bucket of its own time. */
    @Test fun D08_theSamePriceAtANewTimeIsANewObservation() {
        val r = reduce(empty(), obs(kst("19:59:00"), 1342.0), obs(kst("20:00:00"), 1342.0), obs(kst("20:10:00"), 1342.0))
        assertEquals(listOf(ADDED, ADDED, ADDED), r.outcomes)
        assertEquals(setOf(b1950, b2000, b2010), r.state.buckets.keys)
        assertEquals(id(kst("20:10:00"), 1342.0), r.state.tip)
    }

    /**
     * D09: one source and asset reporting two prices for the same time keeps both in the range, keeps the first as the close
     * and the tip, and the conflicting value delivered again is a duplicate.
     */
    @Test fun D09_aSameTimeConflictWidensTheRangeAndKeepsTheFirstClose() {
        val r = reduce(empty(), obs(kst("20:01:00"), 1342.0), obs(kst("20:01:00"), 1340.0), obs(kst("20:01:00"), 1340.0))
        assertEquals(listOf(ADDED, CONFLICT, DUPLICATE), r.outcomes)
        assertEquals(1340.0 to 1342.0, range(r.state, b2000))
        assertEquals(id(kst("20:01:00"), 1342.0), r.state.buckets.getValue(b2000).close)
        assertEquals(id(kst("20:01:00"), 1342.0), r.state.tip)
        assertEquals(setOf(id(kst("20:01:00"), 1342.0), id(kst("20:01:00"), 1340.0)), r.state.observations)
    }

    /**
     * D10: an empty batch changes nothing; an implausible price is rejected and leaves no trace, so a valid price for the same
     * time afterwards is added as new.
     */
    @Test fun D10_anImplausiblePriceIsRejectedAndLeavesNoTrace() {
        val start = empty()
        val none = reduce(start)
        assertEquals(emptyList<GraphObservationOutcome>(), none.outcomes)
        assertTrue(none.state.buckets.isEmpty())
        val at = kst("20:01:00")
        val bad = reduce(start, obs(at, Double.NaN), obs(at, Double.POSITIVE_INFINITY), obs(at, Double.NEGATIVE_INFINITY),
            obs(at, 0.0), obs(at, -1.0), obs(at, 1e9))
        assertEquals(List(6) { REJECTED }, bad.outcomes)
        assertTrue(bad.state.buckets.isEmpty())
        assertTrue(bad.state.observations.isEmpty())
        assertNull(bad.state.tip)
        val good = reduce(bad.state, obs(at, 1342.0))
        assertEquals(listOf(ADDED), good.outcomes)
        assertEquals(1342.0 to 1342.0, range(good.state, b2000))
    }

    /** D11: with nothing new, repeated reductions make no bucket and move no tip - there is no clock to move them. */
    @Test fun D11_nothingNewMakesNoBucketAndMovesNoTip() {
        var state = reduce(empty(), obs(kst("19:00:00"), 1340.0)).state
        val buckets = state.buckets
        repeat(3) { state = reduce(state).state }
        assertEquals(buckets, state.buckets)
        assertEquals(id(kst("19:00:00"), 1340.0), state.tip)
    }

    /**
     * D12: the source and asset are part of the identity; at the same time the preferred source is the close and the tip
     * whatever arrives first, a listed source beats an unlisted one, and with no list the first adopted stays; an
     * observation of another series or scope is rejected and touches nothing.
     */
    @Test fun D12_identityPriorityAndSeriesBoundary() {
        val at = kst("20:05:00")
        val order = GraphObservationOrder(listOf("investing", "cnbc"))
        val a = obs(at, 99.1, source = "investing", asset = "dxy")
        val b = obs(at, 99.2, source = "cnbc", asset = "dxy")
        for ((label, first, second) in listOf(Triple("A first", a, b), Triple("B first", b, a))) {
            val r = reduce(empty(order), first, second)
            assertEquals(label, listOf(ADDED, ADDED), r.outcomes)
            assertEquals(label, a.id, r.state.buckets.getValue(b2000).close)
            assertEquals(label, a.id, r.state.tip)
            assertEquals(label, 99.1 to 99.2, range(r.state, b2000))
        }
        val unlisted = reduce(empty(order), obs(at, 99.0, source = "yahoo", asset = "dxy"), b).state
        assertEquals("a listed source beats an unlisted one", b.id, unlisted.buckets.getValue(b2000).close)
        val firstKept = reduce(empty(), b, a).state
        assertEquals("with no list the first adopted stays", b.id, firstKept.buckets.getValue(b2000).close)

        val assets = reduce(empty(), obs(at, 1342.0, asset = "usd-krw"), obs(at, 1342.0, asset = "usd-krw-alt"))
        assertEquals("the asset is part of the identity", listOf(ADDED, ADDED), assets.outcomes)

        val before = reduce(empty(), obs(at, 1342.0)).state
        val otherSeries = GraphObservationSeriesKey(scope, "hana.usd")
        val otherScope = GraphObservationSeriesKey(GraphDataScope("u2", "e1"), "kb.usd")
        val r = reduce(before, obs(at, 1300.0, seriesKey = otherSeries), obs(at, 1400.0, seriesKey = otherScope))
        assertEquals(listOf(REJECTED, REJECTED), r.outcomes)
        assertEquals(1342.0 to 1342.0, range(r.state, b2000))
        assertEquals(before.observations, r.state.observations)
    }
}
